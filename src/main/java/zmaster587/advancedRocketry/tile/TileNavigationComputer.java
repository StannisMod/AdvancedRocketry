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
import zmaster587.advancedRocketry.item.ItemMemoryCrystal;
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
    /** Button id of the first listed address; the n-th address is {@code BUTTON_PICK_FIRST + n}. */
    private static final int BUTTON_PICK_FIRST = 10;
    /** How many addresses the front page lists. Beyond this the pilot copies to a station to browse. */
    private static final int LISTED_ADDRESSES = 8;

    private static final byte NET_COPY = 0;
    private static final byte NET_ERASE_SOURCE = 1;
    private static final byte NET_CLEAR_TARGET = 2;
    private static final byte NET_PICK = 3;
    private static final byte NET_AIM_TYPED = 4;
    private static final byte NET_SYNC = 5;
    private static final byte NET_ARM = 6;

    private static final String NBT_TARGET = "navTarget";
    private static final String NBT_HAS_TARGET = "navHasTarget";
    private static final String NBT_AFC_OFFSET = "afcOffset";
    private static final String NBT_SYNC_CHANNEL = "syncChannel";
    private static final String NBT_ARMED = "navArmed";

    /** Where the ship is aimed, or {@code null} when the pilot has not chosen. */
    private GalacticCoord target;

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

    /** Aim the ship (or clear the aim with {@code null}). Re-aiming always disarms. */
    public void setTarget(GalacticCoord coord) {
        this.target = coord;
        // Changing where the ship is pointed cannot leave it armed at the old answer: the whole
        // point of arming at a console and firing at the helm is that the pilot committed to a
        // destination he had looked at.
        this.armed = false;
        markDirty();
        if (world != null && !world.isRemote) {
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
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
        modules.add(new ModuleSlotArray(20, 20, this, SLOT_SOURCE, SLOT_SOURCE + 1));
        modules.add(new ModuleSlotArray(20, 50, this, SLOT_SHIP, SLOT_SHIP + 1));

        modules.add(new ModuleButton(50, 18, BUTTON_COPY,
                LibVulpes.proxy.getLocalizedString("msg.navcomputer.copy"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 64, 20));
        modules.add(new ModuleButton(50, 48, BUTTON_ERASE_SOURCE,
                LibVulpes.proxy.getLocalizedString("msg.navcomputer.erase"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 64, 20));
        modules.add(new ModuleButton(50, 78, BUTTON_CLEAR_TARGET,
                LibVulpes.proxy.getLocalizedString("msg.navcomputer.cleartarget"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 64, 20));

        statusText = new ModuleText(130, 20, targetLine(), 0x00FF00);
        addressText = new ModuleText(130, 34, addressLines(), 0xAAAAAA);
        modules.add(statusText);
        modules.add(addressText);

        forecastText = new ModuleText(20, 152, forecastLines(), 0x202020);
        modules.add(forecastText);
        modules.add(new ModuleButton(130, 150, BUTTON_ARM,
                LibVulpes.proxy.getLocalizedString(
                        isArmed() ? "msg.navcomputer.disarm" : "msg.navcomputer.arm"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 64, 20));

        // The text boxes are CLIENT-ONLY, and this list is built on BOTH sides: the server builds it
        // too, to assemble the container behind the window. A text box's backing GuiTextField is a
        // client-side field that is stripped from the dedicated server, so touching one here — even
        // just to seed it with the current value — throws before the container is ever made, and the
        // console then refuses to open with no error the player can see. Same guard as the docking
        // port and the railgun; only the SLOT-bearing modules must exist on both sides.
        if (world.isRemote) {
            typedX = new ModuleNumericTextbox(this, 20, 96, 34, 12, 8);
            typedY = new ModuleNumericTextbox(this, 58, 96, 34, 12, 8);
            typedZ = new ModuleNumericTextbox(this, 96, 96, 34, 12, 8);
            modules.add(typedX);
            modules.add(typedY);
            modules.add(typedZ);

            channelBox = new ModuleNumericTextbox(this, 20, 132, 34, 12, 5);
            channelBox.setText(Integer.toString(syncChannel));
            modules.add(channelBox);
        }
        modules.add(new ModuleButton(20, 112, BUTTON_AIM_TYPED,
                LibVulpes.proxy.getLocalizedString("msg.navcomputer.aimtyped"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 110, 20));

        modules.add(new ModuleButton(58, 130, BUTTON_SYNC,
                LibVulpes.proxy.getLocalizedString("msg.navcomputer.sync"), this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 72, 20));

        List<CrystalEntry> known = shipCrystal().list();
        for (int i = 0; i < known.size() && i < LISTED_ADDRESSES; i++) {
            modules.add(new ModuleButton(130, 46 + i * 22, BUTTON_PICK_FIRST + i,
                    known.get(i).name().isEmpty()
                            ? known.get(i).coord().cellKey() : known.get(i).name(),
                    this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 80, 20));
        }
        return modules;
    }

    private String targetLine() {
        return LibVulpes.proxy.getLocalizedString("msg.navcomputer.target") + " "
                + (target == null
                        ? LibVulpes.proxy.getLocalizedString("msg.navcomputer.notarget")
                        : target.cellKey());
    }

    private String addressLines() {
        int count = shipCrystal().size();
        return LibVulpes.proxy.getLocalizedString("msg.navcomputer.addresses") + " " + count;
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
        if (world == null || world.isRemote) {
            return; // the forecast is the server's answer; the client only ever displays it
        }
        if (world.getTotalWorldTime() % FORECAST_INTERVAL_TICKS != 0) {
            return;
        }
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
        } else if (buttonId >= BUTTON_PICK_FIRST) {
            pickIndex = buttonId - BUTTON_PICK_FIRST;
            PacketHandler.sendToServer(new PacketMachine(this, NET_PICK));
        }
    }

    /** Which listed address the client last clicked; travels to the server with {@link #NET_PICK}. */
    private int pickIndex;

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
                setTarget(entry.coord());
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
        SystemBody body = null;
        for (SystemBody candidate : registry.bodiesAt(entry.coord())) {
            if (entry.coord().equals(candidate.address())) {
                body = candidate;
                break;
            }
        }
        if (body == null) {
            return; // an address with nothing at it: the pilot finds that out by going there
        }
        InfoTier tier = NavInfoRedaction.tierFor(shipCoord(), body.address(), entry.detail());
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
        nbt.setInteger(NBT_SYNC_CHANNEL, syncChannel);
        nbt.setBoolean(NBT_ARMED, armed);
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
        syncChannel = nbt.getInteger(NBT_SYNC_CHANNEL);
        armed = nbt.getBoolean(NBT_ARMED);
        forecast = nbt.getString("navForecast");
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(super.getUpdateTag());
    }
}
