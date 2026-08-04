package zmaster587.advancedRocketry.tile;

import java.util.LinkedList;
import java.util.List;

import javax.annotation.Nonnull;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;

import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.item.ItemMemoryCrystal;
import zmaster587.advancedRocketry.navigation.TargetPrediction;
import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.navigation.CrystalSync;
import zmaster587.advancedRocketry.navigation.JumpGate;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.advancedRocketry.navigation.NavBodyView;
import zmaster587.advancedRocketry.navigation.NavInfoRedaction;
import zmaster587.advancedRocketry.network.PacketNavBodyInfo;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.SpaceSubsystem;
import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.libVulpes.inventory.modules.IButtonInventory;
import zmaster587.libVulpes.inventory.modules.IGuiCallback;
import zmaster587.libVulpes.inventory.modules.ModuleNumericTextbox;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModuleButton;
import zmaster587.libVulpes.inventory.modules.ModuleSlotArray;
import zmaster587.libVulpes.inventory.modules.ModuleText;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.multiblock.hatch.TileInventoryHatch;
import zmaster587.libVulpes.util.INetworkMachine;

/**
 * The ship's navigation computer: it holds the addresses the ship can jump to, and the one it is
 * currently aimed at.
 *
 * <p>Two crystal slots make the computer the place where knowledge is exchanged — a crystal brought
 * aboard can be copied into the ship's own without either losing an address, which is how a station,
 * a friend, or a survey ship hands over what it has learned.</p>
 *
 * <p>A target may come from the inserted crystal OR be typed in by hand. A hand-typed coordinate is
 * deliberately allowed: jumping to an address nobody has surveyed is a leap of faith — something may
 * already occupy the point you arrive at — and that risk is the reason to go and scan first.</p>
 *
 * <p>The computer is linked to its ship's flight computer by a RELATIVE offset, not an absolute
 * position, because a ship's blocks move as a body: the offset survives every relocation the ship
 * makes, an absolute position survives none of them.</p>
 */
public class TileNavigationComputer extends TileInventoryHatch
        implements IModularInventory, IButtonInventory, INetworkMachine, IGuiCallback,
        net.minecraft.util.ITickable {

    /** The crystal being read FROM during a copy. */
    public static final int SLOT_SOURCE = 0;
    /** The ship's own crystal: the copy destination, and the source of jump targets. */
    public static final int SLOT_SHIP = 1;

    private static final int BUTTON_COPY = 0;
    private static final int BUTTON_ERASE_SOURCE = 1;
    private static final int BUTTON_CLEAR_TARGET = 2;
    private static final int BUTTON_AIM_TYPED = 3;
    private static final int BUTTON_SYNC = 4;
    private static final int BUTTON_ARM = 5;
    private static final int BUTTON_SKY_LABELS = 6;
    /** Button id of the first listed address; the n-th address is {@code BUTTON_PICK_FIRST + n}. */
    private static final int BUTTON_PICK_FIRST = 10;
    /**
     * How many addresses the front page lists. Four is what FITS the window's right column above the
     * clear/arm pair (y 18..96 at 20 px a row); the old value of 8 put rows 7 and 8 at y 178 and 200,
     * off the panel entirely. Beyond this the pilot copies to a station to browse.
     */
    private static final int LISTED_ADDRESSES = 4;

    private static final byte NET_COPY = 0;
    private static final byte NET_ERASE_SOURCE = 1;
    private static final byte NET_CLEAR_TARGET = 2;
    private static final byte NET_PICK = 3;
    private static final byte NET_AIM_TYPED = 4;
    private static final byte NET_SYNC = 5;
    private static final byte NET_ARM = 6;
    private static final byte NET_SKY_LABELS = 7;

    private static final String NBT_TARGET = "navTarget";
    private static final String NBT_HAS_TARGET = "navHasTarget";
    private static final String NBT_TARGET_DIM = "navTargetDim";
    private static final String NBT_TARGET_RESOLVED = "navTargetResolved";
    private static final String NBT_AFC_OFFSET = "afcOffset";
    private static final String NBT_SYNC_CHANNEL = "syncChannel";
    private static final String NBT_ARMED = "navArmed";
    private static final String NBT_SKY_LABELS = "navSkyLabels";

    /**
     * The cell the ship is currently aimed at, or {@code null} when the pilot has not chosen.
     *
     * <p>For a BODY target this is a DERIVED value — the computer's own prediction of where the body
     * will be when the ship comes out of hyperspace — and it is recomputed on the server every
     * {@link #FORECAST_INTERVAL_TICKS} and again at the moment the jump commits. For a hand-typed
     * CELL target it is simply what the pilot typed.</p>
     */
    private GalacticCoord target;

    /**
     * The body this ship is aimed at, or {@link Constants#INVALID_PLANET} when the target is a
     * hand-typed cell.
     *
     * <p>These are the two kinds of destination, and they are not the same kind. An address picked
     * off a crystal names a BODY: the pilot chose a planet, and a planet is somewhere that moves, so
     * the ship must stay aimed at the planet rather than at the point the planet was standing on.
     * A coordinate typed by hand names a CELL and nothing else — aiming at an unsurveyed point is a
     * deliberate leap of faith, and there is no body there to track.</p>
     */
    private int targetDim = Constants.INVALID_PLANET;

    /**
     * Whether the computer can currently say where its target IS. Only ever false for a BODY target
     * whose body it cannot locate — a dimension a pack update removed, or a registry that is not up.
     * Synced, because it is the reason the panel and the gate give the pilot.
     */
    private boolean targetResolved = true;

    /** Offset from this computer to its flight computer; {@code null} until the assembler links them. */
    private BlockPos flightComputerOffset;

    /** Whether the pilot has armed the jump at this console. See {@link #isArmed()}. */
    private boolean armed;

    /**
     * The pre-jump forecast, as the SERVER last computed it.
     *
     * <p>It is computed server-side and synced as text rather than recomputed on the client, because
     * every number in it — where the ship is, what its capacitor holds, how far the flight is — is
     * server-authoritative. A client that recomputed it would be guessing, and a pilot who commits to
     * a guess has been misled by his own instruments.</p>
     */
    private String forecast = "";
    private ModuleText forecastText;

    private ModuleText statusText;
    private ModuleText addressText;
    /** The arm/disarm button, kept so its caption can follow the armed state in an open window. */
    private ModuleButton armButton;
    /** All {@link #LISTED_ADDRESSES} address buttons, shown or hidden as the crystal's list changes. */
    private final java.util.List<ModuleButton> pickButtons = new java.util.ArrayList<>();
    /** Hand-typed sector coordinate: the pilot may aim at an address nobody has surveyed. */
    private ModuleNumericTextbox typedX;
    private ModuleNumericTextbox typedY;
    private ModuleNumericTextbox typedZ;
    private final long[] typed = new long[3];
    /** The channel this computer syncs its crystal on; 0 = on no channel, so it syncs with nobody. */
    private int syncChannel;
    private ModuleNumericTextbox channelBox;

    public TileNavigationComputer() {
        super(2);
    }

    // ─── The jump target ───────────────────────────────────────────────────────

    /** Where this ship is aimed, or {@code null}. Read by the jump gate. */
    public GalacticCoord getTarget() {
        return target;
    }

    /**
     * Aim the ship at a bare CELL (or clear the aim with {@code null}) — the hand-typed mode. Nothing
     * is tracked: the coordinate is the destination. Re-aiming always disarms.
     */
    public void setTarget(GalacticCoord coord) {
        aim(coord, Constants.INVALID_PLANET, true);
    }

    /**
     * Aim the ship at a BODY. {@code observed} is where that body was last seen — used as the shown
     * aim until the first prediction lands, and as the honest last word if the body cannot be
     * located. Re-aiming always disarms.
     */
    public void setTargetBody(int dimId, GalacticCoord observed) {
        if (dimId == Constants.INVALID_PLANET) {
            setTarget(observed);
            return;
        }
        aim(observed, dimId, true);
        refreshTarget();
    }

    private void aim(GalacticCoord coord, int dimId, boolean disarming) {
        this.target = coord;
        this.targetDim = coord == null ? Constants.INVALID_PLANET : dimId;
        this.targetResolved = true;
        if (disarming) {
            // Changing where the ship is pointed cannot leave it armed at the old answer: the whole
            // point of arming at a console and firing at the helm is that the pilot committed to a
            // destination he had looked at.
            this.armed = false;
        }
        markDirty();
        if (world != null && !world.isRemote) {
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
    }

    /** The body this ship is aimed at, or {@link Constants#INVALID_PLANET} for a hand-typed cell. */
    public int getTargetDim() {
        return targetDim;
    }

    /** Whether the computer can say where its target is. See {@link #targetResolved}. */
    public boolean isTargetResolved() {
        return targetResolved;
    }

    /**
     * Re-aim at the target BODY: predict where it will be when a jump started now would end, and
     * make that the ship's aim point. A no-op for a hand-typed cell (there is no body to follow) and
     * on the client (the aim is the server's answer; the client displays it).
     *
     * <p>Called on the console's own tick so the pilot watches a live aim, and again at the instant
     * the jump commits so the flight is priced from the freshest possible answer. Free — pure
     * arithmetic over the body's orbit.</p>
     */
    public void refreshTarget() {
        if (world == null || world.isRemote || targetDim == Constants.INVALID_PLANET) {
            return;
        }
        final UniverseRegistry registry = UniverseRegistry.get(world.getMinecraftServer());
        final zmaster587.advancedRocketry.dimension.DimensionManager dims =
                zmaster587.advancedRocketry.dimension.DimensionManager.getInstance();
        BlockPos afc = getFlightComputerPos();
        zmaster587.advancedRocketry.navigation.ShipNavigation nav = afc == null ? null
                : new zmaster587.advancedRocketry.navigation.ShipNavigation(world, afc, shipIdOf(afc));
        GalacticCoord origin = nav == null ? null : nav.currentCoord();
        final long speed = nav == null ? 0L : nav.plannedSpeed();
        // The SPACE clock, not a world's own. The aim is a prediction of where the target will be
        // when this flight ends, and the arrival is priced against SpaceSubsystem.spaceClock(); read
        // anything else here and the two disagree by however far the reader's clock has drifted from
        // the overworld's. `proxy.getWorldTimeUniversal(0)` looks like it asks for exactly this and
        // does not: its client implementation ignores the dimension argument and answers with the
        // player's CURRENT world, whose clock advances only while that world ticks.
        long now = SpaceSubsystem.spaceClock();

        GalacticCoord aimed = registry == null ? null : TargetPrediction.aimAt(targetDim, origin, now,
                new TargetPrediction.Ephemeris() {
                    @Override
                    public GalacticCoord addressAt(int dimId, long worldTick) {
                        // The STRICT lookup: a lenient one answers an unknown dimension with the
                        // overworld, which would quietly re-aim the ship at Earth.
                        zmaster587.advancedRocketry.dimension.DimensionProperties props =
                                dims.getDimensionPropertiesOrNull(dimId);
                        if (props == null) {
                            return null;
                        }
                        // The body's FULL address, not merely its cell: a moon shares its parent's
                        // cell but sits tens of thousands of blocks off its centre, and a ship
                        // dropped at the centre has not arrived at the moon.
                        java.util.Optional<GalacticCoord> at =
                                registry.addressForPlanet(props, worldTick);
                        return at.isPresent() ? at.get() : null;
                    }
                },
                new TargetPrediction.Flight() {
                    @Override
                    public long ticksFor(double distanceBlocks) {
                        return zmaster587.advancedRocketry.hyperdrive.JumpSpeed
                                .transitTicks(distanceBlocks, speed);
                    }
                },
                // Distances are measured through both cells' frames at the tick being priced: the
                // destination's frame is what moves over a jump, and it dominates a moon's own
                // offset by two orders of magnitude.
                registry);

        boolean resolved = aimed != null;
        // A target that cannot be located keeps its LAST KNOWN coordinate rather than being wiped:
        // the pilot's choice is not the computer's to discard over a registry that is momentarily
        // down, and the gate refuses the jump anyway while resolution is false.
        GalacticCoord next = resolved ? aimed : target;
        if (resolved == targetResolved && (next == null ? target == null : next.equals(target))) {
            return;
        }
        targetResolved = resolved;
        target = next;
        markDirty();
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
    }

    private java.util.UUID shipIdOf(BlockPos afc) {
        net.minecraft.tileentity.TileEntity te = world.getTileEntity(afc);
        return te instanceof TileAdvancedFlightComputer
                ? ((TileAdvancedFlightComputer) te).shipIdOrNull() : null;
    }

    // ─── Arming ────────────────────────────────────────────────────────────────

    /**
     * Whether the pilot has committed to this destination at the console.
     *
     * <p>Choosing where to go and choosing to go are two different acts, and they happen in two
     * different places. Arming is the first: it is done at the computer, with the forecast in front
     * of you, and it does not move the ship. The second happens at the helm, where the pilot can see
     * what is around him — which is exactly where you want somebody to be when a ship leaves.</p>
     */
    public boolean isArmed() {
        return armed && target != null;
    }

    /** Commit to the current destination. Refused with no target: there is nothing to commit to. */
    public boolean arm() {
        if (target == null) {
            return false;
        }
        armed = true;
        markDirty();
        return true;
    }

    /** Stand down. Called on abort, after a jump commits, and whenever the aim changes. */
    public void disarm() {
        if (armed) {
            armed = false;
            markDirty();
        }
    }

    // ─── The link to the ship's flight computer ────────────────────────────────

    /**
     * Bind this computer to the flight computer at {@code flightComputerPos}, storing the RELATIVE
     * offset so the link survives the ship's relocation into its own subspace.
     */
    public void linkToFlightComputer(BlockPos flightComputerPos) {
        this.flightComputerOffset = flightComputerPos == null ? null : flightComputerPos.subtract(pos);
        markDirty();
    }

    /** How often an UNLINKED console looks for a flight computer to adopt. Cheap and only while unlinked. */
    private static final int LINK_SCAN_INTERVAL_TICKS = 20;

    /**
     * Adopt the flight computer of the ship this console is standing in, when nothing linked one.
     *
     * <p>The assembler is what normally binds the pair, at the one moment it holds the whole craft —
     * so a console <b>added to a ship that is already assembled</b> was never linked to anything and
     * stayed inert forever, with no way for the player to bind it short of rebuilding the craft. That
     * is the only thing this covers: the console adopts the flight computer that shares its ship's
     * subspace claim, which is the same region {@code ShipNavigation} searches in the other
     * direction.</p>
     *
     * <p>Off a ship there is no claim and nothing is adopted — a console standing on a planet next to
     * a flight computer must NOT silently bind itself to it.</p>
     */
    private void adoptShipFlightComputer() {
        net.minecraft.util.math.AxisAlignedBB yard =
                zmaster587.advancedRocketry.integration.vs.VSIntegration.shipyardBoundsAt(
                        world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (yard == null) {
            return;
        }
        for (net.minecraft.tileentity.TileEntity te
                : world.loadedTileEntityList.toArray(new net.minecraft.tileentity.TileEntity[0])) {
            if (!(te instanceof TileAdvancedFlightComputer)) {
                continue;
            }
            BlockPos afc = te.getPos();
            if (afc.getX() < yard.minX || afc.getX() > yard.maxX
                    || afc.getZ() < yard.minZ || afc.getZ() > yard.maxZ) {
                continue; // another ship's flight computer
            }
            linkToFlightComputer(afc);
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
            return;
        }
    }

    /** The linked flight computer's position, or {@code null} when this computer is not on a ship. */
    public BlockPos getFlightComputerPos() {
        return flightComputerOffset == null ? null : pos.add(flightComputerOffset);
    }

    public boolean isLinked() {
        return flightComputerOffset != null;
    }

    // ─── Crystals ──────────────────────────────────────────────────────────────

    /**
     * Seed a crystal the moment it is put into this computer. This is the first time the game can be
     * sure a real world is available to read the starter addresses from.
     */
    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        super.setInventorySlotContents(slot, stack);
        if (world != null && !world.isRemote) {
            ItemMemoryCrystal.ensureSeeded(getStackInSlot(slot), world);
        }
    }

    /** The addresses on the ship's own crystal; empty when no crystal is inserted. */
    public CrystalMemory shipCrystal() {
        return ItemMemoryCrystal.memoryOf(getStackInSlot(SLOT_SHIP));
    }

    /**
     * Copy the source crystal into the ship's crystal — add-only, keeping the fresher record of any
     * address both know. Returns the number of addresses the ship's crystal gained or refreshed.
     */
    public int copySourceIntoShipCrystal() {
        ItemStack shipStack = getStackInSlot(SLOT_SHIP);
        if (!ItemMemoryCrystal.isCrystal(shipStack)) {
            return 0;
        }
        CrystalMemory ship = ItemMemoryCrystal.memoryOf(shipStack);
        int changed = ship.copyFrom(ItemMemoryCrystal.memoryOf(getStackInSlot(SLOT_SOURCE)));
        if (changed > 0) {
            ItemMemoryCrystal.writeMemory(shipStack, ship);
            markDirty();
        }
        return changed;
    }

    /** Blank the source crystal. The ship's own crystal is never touched by this. */
    public void eraseSourceCrystal() {
        ItemStack source = getStackInSlot(SLOT_SOURCE);
        if (ItemMemoryCrystal.isCrystal(source)) {
            ItemMemoryCrystal.writeMemory(source, new CrystalMemory());
            markDirty();
        }
    }

    // ─── Inventory ─────────────────────────────────────────────────────────────

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull ItemStack stack) {
        return ItemMemoryCrystal.isCrystal(stack);
    }

    // ─── GUI ───────────────────────────────────────────────────────────────────

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = new LinkedList<>();
        pickButtons.clear();
        // GEOMETRY. This window is MODULARNOINV: the player's 3x9 inventory grid (which a MODULAR
        // window draws at y 89..143) is absent and only the hotbar (y 147..165) remains, so the free
        // band is x 8..168, y 18..146 and every module below stays inside it. The previous layout put
        // half the console -- the coordinate boxes, the aim, sync and ARM buttons -- on top of the
        // inventory grid, where a button cannot be clicked at all.
        modules.add(new ModuleSlotArray(8, 18, this, SLOT_SOURCE, SLOT_SOURCE + 1));
        modules.add(new ModuleSlotArray(8, 38, this, SLOT_SHIP, SLOT_SHIP + 1));

        modules.add(new ModuleButton(30, 18, BUTTON_COPY,
                LibVulpes.proxy.getLocalizedString("msg.navcomputer.copy"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 58, 18));
        modules.add(new ModuleButton(30, 38, BUTTON_ERASE_SOURCE,
                LibVulpes.proxy.getLocalizedString("msg.navcomputer.erase"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 58, 18));

        statusText = new ModuleText(8, 58, targetLine(), 0x00FF00);
        addressText = new ModuleText(8, 70, addressLines(), 0xAAAAAA);
        modules.add(statusText);
        modules.add(addressText);

        // The forecast's LAST line is the jump gate's own verdict - the one line that answers "why
        // can I not jump", which until now the pilot could only get by pressing the helm key.
        forecastText = new ModuleText(8, 82, verdictLine(), 0x404040);
        modules.add(forecastText);

        // The address buttons are ALWAYS built, then shown or hidden per tick, so a crystal inserted
        // while the window is open still produces something to click. Building them from the list
        // once meant an empty ship slot rendered no buttons at all and no way to discover why.
        List<CrystalEntry> known = shipCrystal().list();
        for (int i = 0; i < LISTED_ADDRESSES; i++) {
            // Caption seeded through the CONSTRUCTOR, never through setVisible/setText here: this
            // method runs on the dedicated server too, and ModuleButton.setVisible dereferences the
            // @SideOnly(Side.CLIENT) GuiImageButton it wraps. Touching it here throws
            // NoSuchFieldError before the container exists and the console then refuses to open with
            // nothing the player can see - the same failure this file's world.isRemote guard below
            // already exists for. Visibility is settled on the first client tick instead.
            String caption = "";
            if (i < known.size()) {
                CrystalEntry entry = known.get(i);
                caption = entry.name().isEmpty() ? entry.coord().cellKey() : entry.name();
            }
            ModuleButton pick = new ModuleButton(96, 18 + i * 20, BUTTON_PICK_FIRST + i, caption,
                    this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 72, 18);
            pickButtons.add(pick);
            modules.add(pick);
        }

        armButton = new ModuleButton(96, 120, BUTTON_ARM,
                LibVulpes.proxy.getLocalizedString(
                        isArmed() ? "msg.navcomputer.disarm" : "msg.navcomputer.arm"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, forecastLines(), 72, 18);
        modules.add(armButton);
        modules.add(new ModuleButton(96, 98, BUTTON_CLEAR_TARGET,
                LibVulpes.proxy.getLocalizedString("msg.navcomputer.cleartarget"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 72, 18));

        // The text boxes are CLIENT-ONLY, and this list is built on BOTH sides: the server builds it
        // too, to assemble the container behind the window. A text box's backing GuiTextField is a
        // client-side field that is stripped from the dedicated server, so touching one here — even
        // just to seed it with the current value — throws before the container is ever made, and the
        // console then refuses to open with no error the player can see. Same guard as the docking
        // port and the railgun; only the SLOT-bearing modules must exist on both sides.
        if (world.isRemote) {
            typedX = new ModuleNumericTextbox(this, 8, 94, 24, 12, 8);
            typedY = new ModuleNumericTextbox(this, 36, 94, 24, 12, 8);
            typedZ = new ModuleNumericTextbox(this, 64, 94, 24, 12, 8);
            modules.add(typedX);
            modules.add(typedY);
            modules.add(typedZ);

            channelBox = new ModuleNumericTextbox(this, 8, 130, 24, 12, 5);
            channelBox.setText(Integer.toString(syncChannel));
            modules.add(channelBox);
        }
        modules.add(new ModuleButton(8, 108, BUTTON_AIM_TYPED,
                LibVulpes.proxy.getLocalizedString("msg.navcomputer.aimtyped"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 84, 18));

        modules.add(new ModuleButton(36, 128, BUTTON_SYNC,
                LibVulpes.proxy.getLocalizedString("msg.navcomputer.sync"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 52, 18));

        // Shares the bottom row with the sync channel box (x 8..32) and its button (x 36..88), taking
        // x 96..168, y 128..146. The free band ENDS at y 146 - the hotbar starts at 147 - and a button
        // that reaches past it cannot be clicked at all, which the geometry note at the top of this
        // method says in as many words and which the first draft of this one did anyway.
        modules.add(new ModuleButton(96, 128, BUTTON_SKY_LABELS,
                LibVulpes.proxy.getLocalizedString(skyLabels
                        ? "msg.navcomputer.labelsoff" : "msg.navcomputer.labelson"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 72, 18));

        return modules;
    }

    /**
     * Bring every readout in the OPEN window up to date with this tile's current state.
     *
     * <p>Called every client tick. Without it each readout kept whatever value it was built with:
     * picking an address left the target line reading "none set", arming left the button still
     * offering to arm, and the forecast froze at the moment the window opened — so a console whose
     * server side was working perfectly read as a row of dead buttons.</p>
     *
     * <p>The address buttons are shown/hidden here rather than built from the list, so inserting the
     * ship's crystal with the window open makes them appear.</p>
     */
    private void refreshDisplay() {
        if (world == null || !world.isRemote) {
            // CLIENT ONLY, and the guard belongs here rather than only at the call site:
            // ModuleButton.setVisible dereferences a @SideOnly(Side.CLIENT) field, so one careless
            // server-side call takes the whole GUI down with a NoSuchFieldError and no visible cause.
            return;
        }
        if (statusText != null) {
            statusText.setText(targetLine());
        }
        if (addressText != null) {
            addressText.setText(addressLines());
        }
        if (forecastText != null) {
            forecastText.setText(verdictLine());
        }
        if (armButton != null) {
            armButton.setText(LibVulpes.proxy.getLocalizedString(
                    isArmed() ? "msg.navcomputer.disarm" : "msg.navcomputer.arm"));
            armButton.setToolTipText(forecastLines());
        }
        List<CrystalEntry> known = shipCrystal().list();
        for (int i = 0; i < pickButtons.size(); i++) {
            ModuleButton pick = pickButtons.get(i);
            if (i < known.size()) {
                CrystalEntry entry = known.get(i);
                pick.setText(entry.name().isEmpty() ? entry.coord().cellKey() : entry.name());
                pick.setVisible(true);
            } else {
                pick.setVisible(false);
            }
        }
    }

    private String targetLine() {
        return LibVulpes.proxy.getLocalizedString("msg.navcomputer.target") + " "
                + (target == null
                        ? LibVulpes.proxy.getLocalizedString("msg.navcomputer.notarget")
                        : target.cellKey());
    }

    private String addressLines() {
        // The two slots are NOT interchangeable and nothing else on the panel says so: the upper one
        // is the crystal being copied FROM, while every jump target comes from the lower one. A pilot
        // with a single crystal puts it in the upper slot, gets an empty list, and has no way to tell
        // why - so the empty case names the slot instead of just reporting zero.
        if (!ItemMemoryCrystal.isCrystal(getStackInSlot(SLOT_SHIP))) {
            return LibVulpes.proxy.getLocalizedString("msg.navcomputer.noshipcrystal");
        }
        int count = shipCrystal().size();
        return LibVulpes.proxy.getLocalizedString("msg.navcomputer.addresses") + " " + count;
    }

    /**
     * The forecast's LAST line — the jump gate's own verdict ("ready to jump", or the first thing
     * refusing it). Until this was on the panel, the only way to learn why a jump would be refused
     * was to sit in the helm and press the key.
     */
    private String verdictLine() {
        String full = forecastLines();
        int lastBreak = full.lastIndexOf('\n');
        return lastBreak < 0 ? full : full.substring(lastBreak + 1);
    }

    /** The forecast the pilot reads before he arms. Never recomputed here — only displayed. */
    private String forecastLines() {
        return forecast == null || forecast.isEmpty()
                ? LibVulpes.proxy.getLocalizedString("msg.navcomputer.noforecast")
                : forecast;
    }

    // ─── The forecast ──────────────────────────────────────────────────────────

    /** How often the console refreshes its numbers. Fast enough to feel live, slow enough to be free. */
    private static final int FORECAST_INTERVAL_TICKS = 20;

    @Override
    public void update() {
        if (world == null) {
            return;
        }
        if (world.isRemote) {
            // The forecast is the server's answer; the client only ever DISPLAYS it - but it must
            // display the current one. Every readout is refreshed from this tile's synced state each
            // tick, which is what makes a click on the panel visibly do something.
            refreshDisplay();
            return;
        }
        if (world.getTotalWorldTime() % FORECAST_INTERVAL_TICKS != 0) {
            return;
        }
        if (!isLinked() && world.getTotalWorldTime() % LINK_SCAN_INTERVAL_TICKS == 0) {
            adoptShipFlightComputer();
        }
        // Follow the target body before the forecast is priced, so the ETA and the flight cost the
        // pilot reads describe the flight the ship would actually make.
        refreshTarget();
        String fresh = computeForecast();
        if (!fresh.equals(forecast)) {
            forecast = fresh;
            markDirty();
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
    }

    /**
     * What this ship's jump would cost and how long it would take — assembled from the ship's real
     * machines, so a pilot reading it is reading his own drive rather than a table.
     */
    private String computeForecast() {
        BlockPos afc = getFlightComputerPos();
        if (afc == null) {
            return LibVulpes.proxy.getLocalizedString("msg.navcomputer.notonship");
        }
        java.util.UUID shipId = null;
        net.minecraft.tileentity.TileEntity afcTe = world.getTileEntity(afc);
        if (afcTe instanceof TileAdvancedFlightComputer) {
            shipId = ((TileAdvancedFlightComputer) afcTe).shipIdOrNull();
        }
        zmaster587.advancedRocketry.navigation.ShipNavigation nav =
                new zmaster587.advancedRocketry.navigation.ShipNavigation(world, afc, shipId);
        zmaster587.advancedRocketry.hyperdrive.ShipDrive drive = nav.drive();
        zmaster587.advancedRocketry.hyperdrive.ShipDriveStats stats = drive.stats();
        long now = SpaceSubsystem.spaceClock();
        StringBuilder out = new StringBuilder();
        out.append(LibVulpes.proxy.getLocalizedString("msg.navcomputer.drivepower"))
                .append(' ').append(stats.drivePower()).append('\n');
        out.append(LibVulpes.proxy.getLocalizedString("msg.navcomputer.burst"))
                .append(' ').append(drive.capacitorCharge(now))
                .append('/').append(stats.burstCost()).append('\n');
        long cooldown = drive.cooldownTicks(now);
        if (cooldown > 0L) {
            out.append(LibVulpes.proxy.getLocalizedString("msg.navcomputer.cooldown"))
                    .append(' ').append(cooldown / 20L).append("s\n");
        }
        if (target != null) {
            out.append(LibVulpes.proxy.getLocalizedString("msg.navcomputer.eta"))
                    .append(' ').append(nav.plannedTransitTicks() / 20L).append("s\n");
            out.append(LibVulpes.proxy.getLocalizedString("msg.navcomputer.flightcost"))
                    .append(' ').append(nav.storedEnergy())
                    .append('/').append(nav.flightEnergyCost()).append('\n');
        }
        zmaster587.advancedRocketry.hyperdrive.JumpWindow.Coverage coverage = drive.coverage();
        if (coverage != null && !coverage.complete()) {
            out.append(LibVulpes.proxy.getLocalizedString("msg.navcomputer.hullexposed"))
                    .append(' ').append(coverage.uncoveredBlocks()).append('\n');
        }
        zmaster587.advancedRocketry.navigation.JumpGate.Verdict verdict =
                zmaster587.advancedRocketry.navigation.JumpGate.check(nav);
        if (!verdict.allowed()) {
            out.append(LibVulpes.proxy.getLocalizedString(verdict.firstMessage()));
        } else if (verdict.needsConfirmation()) {
            out.append(LibVulpes.proxy.getLocalizedString(verdict.firstMessage()));
        } else {
            out.append(LibVulpes.proxy.getLocalizedString("msg.navcomputer.ready"));
        }
        return out.toString();
    }

    @Override
    public void onModuleUpdated(ModuleBase module) {
        typed[0] = parseSector(typedX);
        typed[1] = parseSector(typedY);
        typed[2] = parseSector(typedZ);
    }

    private static long parseSector(ModuleNumericTextbox box) {
        if (box == null) {
            return 0L;
        }
        try {
            String text = box.getText();
            return text == null || text.isEmpty() ? 0L : Long.parseLong(text.trim());
        } catch (NumberFormatException notANumber) {
            return 0L; // a half-typed coordinate is not an error, it is a pilot mid-keystroke
        }
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockNavigationComputer.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer player) {
        return true;
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == BUTTON_COPY) {
            PacketHandler.sendToServer(new PacketMachine(this, NET_COPY));
        } else if (buttonId == BUTTON_ERASE_SOURCE) {
            PacketHandler.sendToServer(new PacketMachine(this, NET_ERASE_SOURCE));
        } else if (buttonId == BUTTON_CLEAR_TARGET) {
            PacketHandler.sendToServer(new PacketMachine(this, NET_CLEAR_TARGET));
        } else if (buttonId == BUTTON_AIM_TYPED) {
            onModuleUpdated(null);
            PacketHandler.sendToServer(new PacketMachine(this, NET_AIM_TYPED));
        } else if (buttonId == BUTTON_SYNC) {
            PacketHandler.sendToServer(new PacketMachine(this, NET_SYNC));
        } else if (buttonId == BUTTON_ARM) {
            PacketHandler.sendToServer(new PacketMachine(this, NET_ARM));
        } else if (buttonId == BUTTON_SKY_LABELS) {
            PacketHandler.sendToServer(new PacketMachine(this, NET_SKY_LABELS));
        } else if (buttonId >= BUTTON_PICK_FIRST) {
            pickIndex = buttonId - BUTTON_PICK_FIRST;
            PacketHandler.sendToServer(new PacketMachine(this, NET_PICK));
        }
    }

    /** Which listed address the client last clicked; travels to the server with {@link #NET_PICK}. */
    private int pickIndex;

    /**
     * Whether this console asks the cell sky to write each body's name and distance beside it.
     * Default ON. Persisted here and applied client-side by {@code SkyLabels} when the tile's
     * state syncs.
     */
    private boolean skyLabels = true;

    /** Whether this console currently asks for body labels in the sky. */
    public boolean skyLabelsEnabled() {
        return skyLabels;
    }

    // ─── Network ───────────────────────────────────────────────────────────────

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == NET_PICK) {
            out.writeInt(pickIndex);
        } else if (id == NET_SYNC) {
            out.writeInt(channelBox == null ? syncChannel : parseChannel(channelBox));
        } else if (id == NET_AIM_TYPED) {
            out.writeLong(typed[0]);
            out.writeLong(typed[1]);
            out.writeLong(typed[2]);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        if (packetId == NET_PICK) {
            nbt.setInteger("pick", in.readInt());
        } else if (packetId == NET_SYNC) {
            nbt.setInteger("channel", in.readInt());
        } else if (packetId == NET_AIM_TYPED) {
            nbt.setLong("sx", in.readLong());
            nbt.setLong("sy", in.readLong());
            nbt.setLong("sz", in.readLong());
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (side.isClient()) {
            return; // every one of these mutates the ship's knowledge: the server owns it
        }
        if (id == NET_COPY) {
            copySourceIntoShipCrystal();
        } else if (id == NET_ERASE_SOURCE) {
            eraseSourceCrystal();
        } else if (id == NET_CLEAR_TARGET) {
            setTarget(null);
        } else if (id == NET_PICK) {
            List<CrystalEntry> known = shipCrystal().list();
            int index = nbt.getInteger("pick");
            if (index >= 0 && index < known.size()) {
                CrystalEntry entry = known.get(index);
                // A listed address names a BODY. Picking it aims at that body, not at the point the
                // body was standing on when the crystal was written.
                setTargetBody(entry.dimId(), entry.coord());
                answerBodyInfo(player, entry);
            }
        } else if (id == NET_SYNC) {
            syncChannel = nbt.getInteger("channel");
            markDirty();
            syncOnChannel();
        } else if (id == NET_AIM_TYPED) {
            // A hand-typed address is legal and deliberately unvetted - the risk of arriving where
            // something already is IS the mechanic.
            setTarget(GalacticCoord.ofSectorLocal(
                    nbt.getLong("sx"), nbt.getLong("sy"), nbt.getLong("sz"), 0L, 0L, 0L));
        } else if (id == NET_SKY_LABELS) {
            skyLabels = !skyLabels;
            markDirty();
            if (world != null) {
                // The clients that can see this console are the ones this setting is for; the tile's
                // own state sync is what carries it, so the toggle costs no packet of its own.
                world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
            }
            tell(player, skyLabels ? "msg.navcomputer.labelson" : "msg.navcomputer.labelsoff");
        } else if (id == NET_ARM) {
            if (isArmed()) {
                disarm();
                tell(player, "msg.jump.disarmed");
            } else if (arm()) {
                tell(player, "msg.jump.armed");
            } else {
                tell(player, JumpGate.MSG_NO_TARGET);
            }
            if (world != null) {
                world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
            }
        }
    }

    private static void tell(EntityPlayer player, String langKey) {
        if (player != null) {
            player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(langKey));
        }
    }

    // --- Base <-> ship crystal sync -------------------------------------------

    /** The channel this computer syncs on; 0 means it talks to nobody. */
    public int getSyncChannel() {
        return syncChannel;
    }

    public void setSyncChannel(int channel) {
        this.syncChannel = channel;
        markDirty();
    }

    private static int parseChannel(ModuleNumericTextbox box) {
        try {
            String text = box.getText();
            return text == null || text.isEmpty() ? 0 : Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * Bring every other navigation computer on this channel into step with this one, in BOTH
     * directions. A base and its ship end up holding the same addresses, each with the fresher of the
     * two observations, and neither loses anything it knew.
     *
     * <p>Channel 0 syncs with nobody: a computer no one set a channel on must not silently pool its
     * knowledge with every other ship on the server.</p>
     */
    public int syncOnChannel() {
        if (world == null || world.isRemote || syncChannel == 0) {
            return 0;
        }
        ItemStack ourStack = getStackInSlot(SLOT_SHIP);
        if (!ItemMemoryCrystal.isCrystal(ourStack)) {
            return 0;
        }
        int changed = 0;
        CrystalMemory ours = ItemMemoryCrystal.memoryOf(ourStack);
        for (net.minecraft.world.WorldServer peerWorld
                : net.minecraftforge.common.DimensionManager.getWorlds()) {
            net.minecraft.tileentity.TileEntity[] tiles = peerWorld.loadedTileEntityList
                    .toArray(new net.minecraft.tileentity.TileEntity[0]);
            for (net.minecraft.tileentity.TileEntity te : tiles) {
                if (!(te instanceof TileNavigationComputer) || te == this) {
                    continue;
                }
                TileNavigationComputer peer = (TileNavigationComputer) te;
                if (peer.getSyncChannel() != syncChannel) {
                    continue;
                }
                ItemStack peerStack = peer.getStackInSlot(SLOT_SHIP);
                if (!ItemMemoryCrystal.isCrystal(peerStack)) {
                    continue;
                }
                CrystalMemory theirs = ItemMemoryCrystal.memoryOf(peerStack);
                int delta = CrystalSync.sync(ours, theirs);
                if (delta > 0) {
                    ItemMemoryCrystal.writeMemory(peerStack, theirs);
                    peer.markDirty();
                    changed += delta;
                }
            }
        }
        if (changed > 0) {
            ItemMemoryCrystal.writeMemory(ourStack, ours);
            markDirty();
        }
        return changed;
    }

    // ─── The redacted answer channel ───────────────────────────────────────────

    /**
     * Send {@code player} what this ship has earned the right to know about {@code entry}'s body.
     * The redaction happens HERE, on the server: a field the pilot has not earned never reaches his
     * client at all, so no client-side change can reveal it.
     */
    private void answerBodyInfo(EntityPlayer player, CrystalEntry entry) {
        if (!(player instanceof net.minecraft.entity.player.EntityPlayerMP) || world == null) {
            return;
        }
        UniverseRegistry registry = UniverseRegistry.get(world.getMinecraftServer());
        if (registry == null) {
            return;
        }
        // Found by IDENTITY where the entry has one: the body has moved since the entry was written,
        // so looking it up by the recorded coordinate would find nothing exactly when the pilot most
        // wants to be told what he just aimed at.
        SystemBody body = null;
        GalacticCoord where = entry.namesBody() && target != null ? target : entry.coord();
        for (SystemBody candidate : registry.bodiesAt(where)) {
            // Without an identity, the crystal holds a COORDINATE the pilot wrote down, and what he
            // wrote down is a cell name: comparing the whole address would fail for a moon the
            // moment it moved off the point it was observed at, which is within the minute.
            if (entry.namesBody()
                    ? candidate.dimId() == entry.dimId()
                    : where.sameCell(candidate.name())) {
                body = candidate;
                break;
            }
        }
        if (body == null) {
            return; // an address with nothing at it: the pilot finds that out by going there
        }
        long now = zmaster587.advancedRocketry.space.SpaceSubsystem.spaceClock();
        InfoTier tier = NavInfoRedaction.tierFor(shipCoord(), body.name(),
                registry.distanceBetween(shipCoord(), body.addressAt(now), now), entry.detail());
        PacketHandler.sendToPlayer(PacketNavBodyInfo.of(tier,
                NavInfoRedaction.redact(NavBodyView.of(body, entry), tier)),
                (net.minecraft.entity.player.EntityPlayerMP) player);
    }

    /** Where this ship currently is, per the ledger, or {@code null} when it is not a settled ship. */
    public GalacticCoord shipCoord() {
        ShipLedger ledger = SpaceSubsystem.ledger();
        BlockPos afc = getFlightComputerPos();
        if (ledger == null || afc == null || world == null) {
            return null;
        }
        net.minecraft.tileentity.TileEntity te = world.getTileEntity(afc);
        if (!(te instanceof TileAdvancedFlightComputer)) {
            return null;
        }
        ShipLedger.Entry entry = ledger.get(((TileAdvancedFlightComputer) te).getOrCreateShipId());
        return entry == null ? null : entry.coord;
    }

    // ─── NBT ───────────────────────────────────────────────────────────────────

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean(NBT_HAS_TARGET, target != null);
        if (target != null) {
            NBTTagCompound sub = new NBTTagCompound();
            target.writeToNBT(sub);
            nbt.setTag(NBT_TARGET, sub);
        }
        if (flightComputerOffset != null) {
            nbt.setLong(NBT_AFC_OFFSET, flightComputerOffset.toLong());
        }
        nbt.setInteger(NBT_TARGET_DIM, targetDim);
        nbt.setBoolean(NBT_TARGET_RESOLVED, targetResolved);
        nbt.setInteger(NBT_SYNC_CHANNEL, syncChannel);
        nbt.setBoolean(NBT_ARMED, armed);
        nbt.setBoolean(NBT_SKY_LABELS, skyLabels);
        nbt.setString("navForecast", forecast == null ? "" : forecast);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        target = nbt.getBoolean(NBT_HAS_TARGET)
                ? GalacticCoord.readFromNBT(nbt.getCompoundTag(NBT_TARGET))
                : null;
        flightComputerOffset = nbt.hasKey(NBT_AFC_OFFSET)
                ? BlockPos.fromLong(nbt.getLong(NBT_AFC_OFFSET))
                : null;
        targetDim = nbt.hasKey(NBT_TARGET_DIM)
                ? nbt.getInteger(NBT_TARGET_DIM) : Constants.INVALID_PLANET;
        targetResolved = !nbt.hasKey(NBT_TARGET_RESOLVED) || nbt.getBoolean(NBT_TARGET_RESOLVED);
        syncChannel = nbt.getInteger(NBT_SYNC_CHANNEL);
        armed = nbt.getBoolean(NBT_ARMED);
        // Default ON: the label is a diagnostic before it is a player affordance — it is how anyone
        // reads that a body is receding without a probe — so a console that has never been touched
        // shows it. An absent key therefore means ON, not OFF.
        skyLabels = !nbt.hasKey(NBT_SKY_LABELS) || nbt.getBoolean(NBT_SKY_LABELS);
        if (world != null && world.isRemote) {
            // This is the whole wire for the toggle: the console already ships its state to the
            // clients that can see it, and the sky is drawn client-side.
            zmaster587.advancedRocketry.client.render.planet.SkyLabels.setConsoleEnabled(skyLabels);
        }
        forecast = nbt.getString("navForecast");
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(super.getUpdateTag());
    }

    /**
     * Ship this tile's state to the client whenever it changes.
     *
     * <p>{@code getUpdateTag} alone only covers the moment the CHUNK is sent — the initial load. A
     * later {@code notifyBlockUpdate} is delivered through {@code getUpdatePacket}, and vanilla's
     * default returns {@code null}: nothing in this tile's ancestry
     * ({@code TileInventoryHatch} → {@code TilePointer} → {@code TileEntity}) overrides it. So every
     * server-side change this console makes — the target it was aimed at, the armed flag, the
     * forecast it recomputes every second — reached the client exactly never, and the panel showed
     * whatever was true when the player walked up to it. Refreshing the readouts from client state
     * (which this class also does) cannot help while that state is frozen; this is the half that
     * unfreezes it.</p>
     */
    @Override
    public net.minecraft.network.play.server.SPacketUpdateTileEntity getUpdatePacket() {
        return new net.minecraft.network.play.server.SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(net.minecraft.network.NetworkManager net,
                             net.minecraft.network.play.server.SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }
}
