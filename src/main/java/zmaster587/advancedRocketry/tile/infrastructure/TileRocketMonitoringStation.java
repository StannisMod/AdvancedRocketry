package zmaster587.advancedRocketry.tile.infrastructure;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.EntityRocketBase;
import zmaster587.advancedRocketry.api.IInfrastructure;
import zmaster587.advancedRocketry.api.IMission;
import zmaster587.advancedRocketry.api.RocketEvent;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.inventory.TextureResources;

import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.client.util.IndicatorBarImage;
import zmaster587.libVulpes.client.util.ProgressBarImage;
import zmaster587.libVulpes.interfaces.ILinkableTile;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.inventory.GuiHandler;
import zmaster587.libVulpes.items.ItemLinker;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.IComparatorOverride;
import zmaster587.libVulpes.util.IAdjBlockUpdate;
import zmaster587.libVulpes.util.INetworkMachine;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;

public class TileRocketMonitoringStation extends TileEntity
    implements IModularInventory, ITickable, IAdjBlockUpdate, IInfrastructure,
               ILinkableTile, INetworkMachine, IButtonInventory, IProgressBar,
               IComparatorOverride, IGuiCallback { 
    // ==== TUNABLE TICK THROTTLES ====
    // 2–3 ticks for height/vel feels live; 5–10 ticks is fine for fuel.
    private static final int T_HEIGHTVEL_TICKS  = 3;   // ~6.7 Hz
    private static final int T_FUEL_TICKS       = 10;  // ~2 Hz
    private static final int T_COMPARATOR_TICKS = 3;   // match height cadence
    // =================================

    EntityRocketBase linkedRocket;
    IMission mission;
    ModuleText missionText;

    // Cached redstone state from neighbor callbacks (don’t poll every tick)
    private boolean isPoweredCached = false, initPower = false;

    // Throttles
    private int heightVelTick = 0, fuelTick = 0, comparatorTick = 0;

    // Comparator cache (change-only)
    private int lastComparator = -1;

    // Server snapshots (served via ModuleProgress polling)
    private int snapHeight = 0, snapVel = 0;
    private int snapFuel = 0,  snapFuelCap = 0;  // active fuel (id=2 semantics)
    private int snapOx   = 0,  snapOxCap   = 0;  // oxidizer    (id=6 semantics)

    // GUI cached fields (client)
    boolean was_powered = false;
    int rocketHeight;
    int velocity;
    int fuelLevel, maxFuelLevel;
    int oxidizerFuelLevel;


    // === GUI event status (server -> client via TE update) ===
    // 0=idle, 1=prelaunch, 2=launching, 3=orbit, 4=deorbiting, 5=landed, 6=aborted

    private int uiStatus = 0;
    private transient ModuleText launchStatus;      // client-only widget
    private transient ModuleText abortDetail;
    private transient int lastUiStatusShown = -1;   // client change-detect
    // How long a status is considered fresh after the last event (in ticks)
    private static final long STATUS_STALE_TICKS = 600L; // over 30 seconds is outdated
    private long lastStatusTick = 0L; // server-only; persisted
    private String lastAbortReason = "";
    
    // Tabs (client-only)
    private static final byte TAB_SWITCH = 10;
    private ModuleTab tabModule;
    // Event bus registration flag
    private boolean registeredBus = false;
    
    private void pushState() {
        if (world != null && !world.isRemote) {
            markDirty();
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
    }

    private void clearUiStatus() {
        uiStatus = 0;
        lastAbortReason = "";
        lastUiStatusShown = -1; // force client label to refresh to empty
        pushState();
    }

    public TileRocketMonitoringStation() {
        mission = null;
        missionText = null;

        tabModule = new ModuleTab(
            4, 0, 0, this, 2,
            new String[] {
                LibVulpes.proxy.getLocalizedString("msg.monitoringStation.tab.status"),
                LibVulpes.proxy.getLocalizedString("msg.monitoringStation.tab.mission")
            },
            new net.minecraft.util.ResourceLocation[][] {
                TextureResources.tabPlanet,
                TextureResources.tabPlanetTracking
            }
        );
    }

    // --- Lifecycle / bus registration ---

    @Override
    public void onLoad() {
        if (!world.isRemote && !registeredBus) {
            MinecraftForge.EVENT_BUS.register(this);
            registeredBus = true;
        }
        if (!world.isRemote && !initPower) {
            boolean now = world.isBlockIndirectlyGettingPowered(pos) > 0;
            isPoweredCached = now;
            was_powered = now;
            initPower = true;
        }

        if (!world.isRemote) {
            boolean stale = lastStatusTick == 0L ||
                            (world.getTotalWorldTime() - lastStatusTick) > STATUS_STALE_TICKS;

            if (stale || (linkedRocket == null && mission == null)) {
                clearUiStatus();
                lastStatusTick = 0L;
            } else {
                pushState();
            }
        }
            

    }


    @Override
    public void invalidate() {
        super.invalidate();

        // Unregister bus
        if (!world.isRemote && registeredBus) {
            MinecraftForge.EVENT_BUS.unregister(this);
            registeredBus = false;
        }

        // Preserve original unlink-on-destroy semantics
        if (linkedRocket != null) {
            linkedRocket.unlinkInfrastructure(this);
            unlinkRocket();
        }
        if (mission != null) {
            mission.unlinkInfrastructure(this);
            unlinkMission();
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        // IMPORTANT: do NOT unlink here — preserve original behavior:
        // this tile remains linked across unload/reload and during flight/space.
        if (!world.isRemote && registeredBus) {
            MinecraftForge.EVENT_BUS.unregister(this);
            registeredBus = false;
        }
    }

    // --- Redstone power caching via block neighbor callbacks ---

    @Deprecated
    public boolean getEquivalentPower() {
        return world.isBlockIndirectlyGettingPowered(pos) > 0;
    }

    @Override
    public void onAdjacentBlockUpdated() {
        if (world == null || world.isRemote) return;

        boolean now = world.isBlockIndirectlyGettingPowered(pos) > 0;
        boolean rising = now && !isPoweredCached;

        // Update cache first so it stays correct even with no rocket linked
        isPoweredCached = now;
        was_powered = now; // optional if you surface this elsewhere

        if (rising && linkedRocket != null) {
            linkedRocket.prepareLaunch();   // only on true 0->1 edge
            markDirty();
        }
    }



    // --- IInfrastructure ---

    @Override
    public int getMaxLinkDistance() {
        return 300000;
    }

    @Override
    public boolean disconnectOnLiftOff() {
        return false;
    }

    @Override
    public boolean linkRocket(EntityRocketBase rocket) {
        this.linkedRocket = rocket;
        this.lastComparator = -1;

        // Haxy gas mission returning case
        if (!world.isRemote) {
            boolean returning =
                (rocket instanceof EntityRocket)
                && ((EntityRocket) rocket).isInOrbit()
                && ((EntityRocket) rocket).isInFlight();

            if (returning) {
                uiStatus = 4; // deorbiting
                lastStatusTick = world.getTotalWorldTime();
                pushState();
            } else {
                clearUiStatus();
                lastStatusTick = 0L;
            }
        }
        return true;
    }


    @Override
    public void unlinkRocket() {
        linkedRocket = null;

        // reset snapshots
        snapHeight = snapVel = 0;
        snapFuel = snapFuelCap = 0;
        snapOx = snapOxCap = 0;

        if (!world.isRemote) {
            lastComparator = 0;
            world.updateComparatorOutputLevel(pos, world.getBlockState(pos).getBlock());

            // Keep "Reached orbit" visible while the mission is active.
            if (mission == null) {
                clearUiStatus();
                lastStatusTick = 0L;   // reset tick
            }
        }
    }


    // --- Ticking ---

    @Override
    public void update() {
        // ensure we are listening on the bus ---
        if (!world.isRemote && !registeredBus) {
            MinecraftForge.EVENT_BUS.register(this);
            registeredBus = true;
        }
        if (world.isRemote) return;

        // One-time prime (in case no neighbor event has fired yet)
        if (!initPower) {
            isPoweredCached = world.isBlockIndirectlyGettingPowered(pos) > 0;
            initPower = true;
        }

        if (!world.isRemote) {
            long age = world.getTotalWorldTime() - lastStatusTick;

            // Aborted
            if (uiStatus == 6 && age > STATUS_STALE_TICKS) clearUiStatus();

            // Reached orbit — only time out when no mission is linked
            if (uiStatus == 3 && mission == null && age > STATUS_STALE_TICKS) clearUiStatus();

            // Landed
            if (uiStatus == 5 && age > STATUS_STALE_TICKS) clearUiStatus();
        }   
        // Runs infrequently to recover from any missed neighbor events.
        if (world.getTotalWorldTime() % 100 == 0) { // every 100 ticks
            boolean polled = world.isBlockIndirectlyGettingPowered(pos) > 0;
            isPoweredCached = polled; // DO NOT trigger launch here; just reconcile the cache
        }
        // Idle fast-exit
        if (linkedRocket == null) { return; }

        // ---- height + velocity snapshots, every T_HEIGHTVEL_TICKS ----
        if (++heightVelTick >= Math.max(1, T_HEIGHTVEL_TICKS)) {
            heightVelTick = 0;

            snapHeight = (int) linkedRocket.posY;
            snapVel    = (int) (linkedRocket.motionY * 100);

            // comparator (0–15) change-only, every T_COMPARATOR_TICKS
            if (++comparatorTick >= Math.max(1, T_COMPARATOR_TICKS)) {
                comparatorTick = 0;
                if (linkedRocket instanceof EntityRocket) {
                    int comp = (int)(15 * ((EntityRocket) linkedRocket).getRelativeHeightFraction());
                    if (comp != lastComparator) {
                        lastComparator = comp;
                        world.updateComparatorOutputLevel(pos, world.getBlockState(pos).getBlock());
                    }
                }
            }
        }

        // ---- fuel snapshots, every T_FUEL_TICKS ----
        if (++fuelTick >= Math.max(1, T_FUEL_TICKS)) {
            fuelTick = 0;

            // Original semantics:
            //  - id=2 shows the *active* rocket fuel
            //  - id=6 shows oxidizer independently
            final FuelRegistry.FuelType active = linkedRocket.getRocketFuelType();
            snapFuel    = (active != null) ? linkedRocket.getFuelAmount(active)    : 0;
            snapFuelCap = (active != null) ? linkedRocket.getFuelCapacity(active) : 0;

            snapOx      = linkedRocket.getFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER);
            snapOxCap   = linkedRocket.getFuelCapacity(FuelRegistry.FuelType.LIQUID_OXIDIZER);
        }
    }

    // --- Forge Rocket Events -> authorititative UI status (server -> client via TE update) ---

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPreLaunch(RocketEvent.RocketPreLaunchEvent e) {
        if (world == null || world.isRemote) return;
        if (linkedRocket != null && e.getEntity() == linkedRocket) {
            uiStatus = e.isCanceled() ? 6 : 1;
            if (!e.isCanceled()) lastAbortReason = "";  // fresh launch, drop old reason
            lastStatusTick = world.getTotalWorldTime();
            pushState();
        }
    }

    @SubscribeEvent
    public void onLaunch(RocketEvent.RocketLaunchEvent e) {
        if (world == null || world.isRemote) return;
        if (linkedRocket != null && e.getEntity() == linkedRocket) {
            uiStatus = 2;
            lastStatusTick = world.getTotalWorldTime();
            pushState();
        }
    }

    @SubscribeEvent
    public void onOrbit(RocketEvent.RocketReachesOrbitEvent e) {
        if (world == null || world.isRemote) return;
        if (linkedRocket != null && e.getEntity() == linkedRocket) {
            uiStatus = 3;
            lastStatusTick = world.getTotalWorldTime();
            pushState();
        }
    }

    @SubscribeEvent
    public void onDeorbit(RocketEvent.RocketDeOrbitingEvent e) {
        if (world == null || world.isRemote) return;
        if (linkedRocket != null && e.getEntity() == linkedRocket) {
            uiStatus = 4;                           // reuse “landed”/returning state
            lastStatusTick = world.getTotalWorldTime();
            pushState();
        }
    }

    @SubscribeEvent
    public void onLanded(RocketEvent.RocketLandedEvent e) {
        if (world == null || world.isRemote) return;
        if (linkedRocket != null && e.getEntity() == linkedRocket) {
            uiStatus = 5;
            lastStatusTick = world.getTotalWorldTime();
            pushState();
        }
    }

    @SubscribeEvent
    public void onAbort(RocketEvent.RocketAbortEvent e) {
        if (world == null || world.isRemote) return;
        if (linkedRocket != null && e.getEntity() == linkedRocket) {
            uiStatus = 6;                         // “aborted”
            lastAbortReason = (e.reason == null) ? "" : e.reason;
            lastStatusTick = world.getTotalWorldTime();
            pushState();
        }
    }


    // --- Linker flow ---

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        ItemLinker.setMasterCoords(item, getPos());
        if (linkedRocket != null) {
            linkedRocket.unlinkInfrastructure(this);
            unlinkRocket();
        }
        if (mission != null) {
            mission.unlinkInfrastructure(this);
            unlinkMission();
        }

        if (player.world.isRemote)
            Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(
                    new TextComponentTranslation("%s %s",
                            new TextComponentTranslation("msg.monitoringStation.link"),
                            ": " + getPos().getX() + " " + getPos().getY() + " " + getPos().getZ()));
        return true;
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        if (player.world.isRemote)
            Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("msg.linker.error.firstMachine"));
        return false;
    }

    // --- NBT / TE sync ---

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeToNBT(tag);
        return new SPacketUpdateTileEntity(pos, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        was_powered = nbt.getBoolean("was_powered");

        if (nbt.hasKey("missionID")) {
            long id = nbt.getLong("missionID");
            SatelliteBase sat = DimensionManager.getInstance().getSatellite(id);
            if (sat instanceof IMission) {
                mission = (IMission) sat;
            }
        }
        uiStatus = nbt.getInteger("uiStatus");
        lastStatusTick = nbt.getLong("lastStatusTick");
        lastAbortReason = nbt.hasKey("abortReason") ? nbt.getString("abortReason") : "";

        // --- client: force GUI labels to refresh next frame ---
        if (world != null && world.isRemote) {
            lastUiStatusShown = -1; // guarantees next render tick will reapply the text
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean("was_powered", was_powered);
        if (mission != null) {
            nbt.setLong("missionID", mission.getMissionId());
            nbt.setInteger("missionDimId", mission.getOriginatingDimension());
        }
        nbt.setInteger("uiStatus", uiStatus);
        nbt.setLong("lastStatusTick", lastStatusTick);
        nbt.setString("abortReason", lastAbortReason == null ? "" : lastAbortReason);
        return nbt;
    }

    // --- LibVulpes network bridge  ---

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == 1) out.writeLong(mission == null ? -1 : mission.getMissionId());
        else if (id == TAB_SWITCH) out.writeShort(tabModule.getTab());
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        if (packetId == 1) nbt.setLong("id", in.readLong());
        else if (packetId == 2) nbt.setByte("state", in.readByte());
        else if (packetId == TAB_SWITCH) nbt.setShort("tab", in.readShort());
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (id == 1) {
            long idNum = nbt.getLong("id");
            if (idNum == -1) {
                mission = null;
                if (world.isRemote && missionText != null) setMissionText();
            } else {
                SatelliteBase base = DimensionManager.getInstance().getSatellite(idNum);
                if (base instanceof IMission) {
                    mission = (IMission) base;
                    if (world.isRemote && missionText != null) setMissionText();
                }
            }
        }
        else if (id == 2) {
            // redstone control path was commented in original; preserved
        }
        else if (id == TAB_SWITCH && !world.isRemote) {
            tabModule.setTab(nbt.getShort("tab"));
            player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARNOINV.ordinal(),
                        getWorld(), pos.getX(), pos.getY(), pos.getZ());
        }        
        if (id == 100) {
            if (linkedRocket != null)
                linkedRocket.prepareLaunch();
        }
    }

    // --- GUI / Modules ---
    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        LinkedList<ModuleBase> modules = new LinkedList<>();

        // Tabs control
        modules.add(tabModule);

        if (tabModule.getTab() == 0) {
            // === STATUS TAB ===
            modules.add(new ModuleButton(20, 40, 0, "Launch!", this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild));

            if (world.isRemote) {
                launchStatus = new ModuleText(88, 92, "", 0xFFFFFF22, true); // centered
                modules.add(launchStatus);

                abortDetail  = new ModuleText(88, 108, "", 0xFF4444, true);  // centered
                modules.add(abortDetail);

                lastUiStatusShown = -1;
            }

            modules.add(new ModuleProgress(98,  4, 0, new IndicatorBarImage(2,  7, 12, 81, 17, 0, 6, 6, 1, 0, EnumFacing.UP, TextureResources.rocketHud), this));
            modules.add(new ModuleProgress(120, 14, 1, new IndicatorBarImage(2, 95, 12, 71, 17, 0, 6, 6, 1, 0, EnumFacing.UP, TextureResources.rocketHud), this));
            modules.add(new ModuleProgress(142, 14, 2, new ProgressBarImage(2,173, 12, 71, 17, 6, 3, 69, 1, 1, EnumFacing.UP, TextureResources.rocketHud), this));
            modules.add(new ModuleProgress(148, 14, 6, new ProgressBarImage(2,173, 12, 71, 17,75, 3, 69, 1, 1, EnumFacing.UP, TextureResources.rocketHud), this));

            if (!world.isRemote) {
                PacketHandler.sendToPlayer(new PacketMachine(this, (byte)1), player);
                pushState();
            }
            return modules;
        }

        // === MISSION TAB ===
        {
            final boolean hasMission = mission != null;

            // If there is NO mission: show a single centered line and exit early
            if (!hasMission) {
                modules.add(new ModuleText(
                    88, 72,
                    LibVulpes.proxy.getLocalizedString("msg.monitoringStation.missionNoActiveMission"),
                    0x2b2b2b,   // color
                    true        // centered
                ));
                if (!world.isRemote) {
                    PacketHandler.sendToPlayer(new PacketMachine(this, (byte)1), player);
                    pushState();
                }
                return modules; 
            }

            // ---- Has mission: structured list ----
            final String typeLine;
            {
                String cls = mission.getClass().getSimpleName().toLowerCase();
                typeLine = cls.contains("gas")
                        ? LibVulpes.proxy.getLocalizedString("msg.monitoringStation.mission.type.gas")   // "Gas Collection Mission"
                        : LibVulpes.proxy.getLocalizedString("msg.monitoringStation.mission.type.ore");  // "Asteroid Mining Mission"
            }


            modules.add(new ModuleText(
                88, 16, net.minecraft.util.text.TextFormatting.BOLD + typeLine + net.minecraft.util.text.TextFormatting.RESET,
                0x2b2b2b,
                true   // centered
            ));

            // Decide mission type once (use the text you already built)
            final boolean isGas = typeLine.toLowerCase().contains("gas");

            // Target: if GAS mission, show the chosen fluid; otherwise keep default
            if (isGas) {
                String gasLabel = "";
                try {
                    if (mission instanceof zmaster587.advancedRocketry.mission.MissionGasCollection) {
                        net.minecraftforge.fluids.Fluid f =
                            ((zmaster587.advancedRocketry.mission.MissionGasCollection) mission).getGasFluid();
                        if (f != null) {
                            gasLabel = new net.minecraftforge.fluids.FluidStack(f, 1).getLocalizedName();
                        }
                    }
                } catch (Throwable t) { /* be defensive */ }

                modules.add(new ModuleText(
                    10, 39,
                    (gasLabel.isEmpty()
                        ? LibVulpes.proxy.getLocalizedString("msg.monitoringStation.mission.target.default")
                        : LibVulpes.proxy.getLocalizedString("msg.monitoringStation.mission.targetPrefix") + " " + gasLabel),
                    0x2b2b2b
                ));
            } else {
                // ---------- NON-GAS (ORE) SECTION — single, minimal block ----------
                String oreType = "";
                String shortId = "";

                try {
                    if (mission instanceof zmaster587.advancedRocketry.mission.MissionOreMining) {
                        zmaster587.advancedRocketry.mission.MissionOreMining m =
                            (zmaster587.advancedRocketry.mission.MissionOreMining) mission;

                        oreType = m.getAsteroidTypeOrEmpty();
                        Long aUuid = m.getAsteroidUUIDOrNull();

                        if (aUuid != null) {
                            long base = aUuid;
                            long th   = Integer.toUnsignedLong((oreType == null ? "" : oreType).hashCode());
                            long z = base ^ (th << 1);
                            z += 0x9E3779B97F4A7C15L;
                            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
                            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
                            z = (z ^ (z >>> 31));
                            String hex = Long.toUnsignedString(z, 16).toUpperCase();
                            shortId = (hex.length() > 6) ? hex.substring(hex.length() - 6) : hex;
                        }
                    }
                } catch (Throwable t) { /* defensive */ }

                // Line 1: Asteroid: <id>  (or just "Asteroid:" if id missing)
                String lineAsteroid = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.mission.Asteroid.targetPrefix")
                                    + (shortId.isEmpty() ? "" : " " + shortId);
                modules.add(new ModuleText(10, 39, lineAsteroid, 0x2b2b2b));

                // Line 2: Type: <type>    (omit if unknown)
                if (oreType != null && !oreType.isEmpty()) {
                    String lineType = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.mission.asteroidIdPrefix")
                                    + " " + oreType;
                    modules.add(new ModuleText(10, 53, lineType, 0x2b2b2b));
                }
            }


            // --- Specific line per mission type ---
            if (isGas) {
                // Read planned harvest written by the rocket into the mission's persist NBT
                long plannedMb = -1L;
                try {
                    if (mission instanceof zmaster587.advancedRocketry.mission.MissionResourceCollection) {
                        plannedMb = ((zmaster587.advancedRocketry.mission.MissionResourceCollection) mission)
                                .getPlannedHarvestMbOrDefault();
                    }
                } catch (Throwable t) { /* be defensive */ }

                final String plannedText = (plannedMb >= 0)
                        ? (LibVulpes.proxy.getLocalizedString("msg.monitoringStation.mission.plannedAmountPrefix") + " " + plannedMb + " mB")
                        : LibVulpes.proxy.getLocalizedString("msg.monitoringStation.mission.plannedAmountPending");

                modules.add(new ModuleText(10, 53, plannedText, 0x2b2b2b));
            } 
            //else {     if we want to add ore-specific lines later, show loot etc.    }

            // Duration text (above the stage bars, like original)
            missionText = new ModuleText(88, 94, "", 0x2b2b2b, true);
            setMissionText();
            modules.add(missionText);
            // Stage bars just above the time block
            modules.add(new ModuleProgress(30, 110, 3, TextureResources.progressToMission,   this));
            modules.add(new ModuleProgress(30, 120, 4, TextureResources.workMission,         this));
            modules.add(new ModuleProgress(30, 130, 5, TextureResources.progressFromMission, this));

            if (!world.isRemote) {
                PacketHandler.sendToPlayer(new PacketMachine(this, (byte)1), player);
                pushState();
            }
            return modules;
        }
    }    


    private void setMissionText() {
        // If the text widget isn’t built yet (e.g., GUI closed or on other tab), just bail out.
        if (missionText == null) return;

        if (mission != null) {
            int time = mission.getTimeRemainingInSeconds();
            int seconds = time % 60;
            int minutes = (time / 60) % 60;
            int hours = time / 3600;

            missionText.setText(
                LibVulpes.proxy.getLocalizedString("msg.monitoringStation.progress")
                + String.format(" %02d:%02d:%02d", hours, minutes, seconds)
            );
        } else {
            missionText.setText(LibVulpes.proxy.getLocalizedString("msg.monitoringStation.missionProgressNA"));
        }
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId != -1)
            PacketHandler.sendToServer(new PacketMachine(this, (byte)(buttonId + 100)));
        else
            PacketHandler.sendToServer(new PacketMachine(this, (byte)2));
    }

    private static String wrapToWidthClient(String s, int maxWidthPx) {
        if (s == null || s.isEmpty()) return "";
        net.minecraft.client.gui.FontRenderer fr = net.minecraft.client.Minecraft.getMinecraft().fontRenderer;
        java.util.List<String> lines = fr.listFormattedStringToWidth(s, Math.max(1, maxWidthPx));
        return String.join("\n", lines);
    }

    @Override
    public String getModularInventoryName() {
        return "container.monitoringstation";
    }

    @Override
    public float getNormallizedProgress(int id) {
        if (world.isRemote) {
            // Status tab label
            if (launchStatus != null && uiStatus != lastUiStatusShown) {
                lastUiStatusShown = uiStatus;

                String header = "";
                String detail = "";

                switch (uiStatus) {
                    case 1: header = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.prelaunch"); break;
                    case 2: header = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.launching"); break;
                    case 3: header = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.orbit");     break;
                    case 4: header = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.deorbiting"); break;
                    case 5: header = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.landed");    break;
                    case 6: header = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.aborted");
                        if (lastAbortReason != null && !lastAbortReason.isEmpty()) {detail = lastAbortReason;} break;
                    default:
                        header = "";
                }
                launchStatus.setText(header);
                if (abortDetail != null) {
                    final int ABORT_WRAP_WIDTH = 150;
                    abortDetail.setText(wrapToWidthClient(detail, ABORT_WRAP_WIDTH));
                }
            }

            // Mission tab duration label (make it live)
            if (mission != null && missionText != null) {
                setMissionText();
            }
        }

        if (id == 1) {
            return Math.max(Math.min(0.5f + (getProgress(id) / (float) getTotalProgress(id)), 1), 0f);
        } else if (id == 3) {
            if (mission == null) return 0f;
            return (float) Math.min(3f * mission.getProgress(this.world), 1f);
        } else if (id == 4) {
            if (mission == null) return 0f;
            return (float) Math.min(Math.max(3f * (mission.getProgress(this.world) - 0.333f), 0f), 1f);
        } else if (id == 5) {
            if (mission == null) return 0f;
            return (float) Math.min(Math.max(3f * (mission.getProgress(this.world) - 0.666f), 0f), 1f);
        }

        return Math.min(getProgress(id) / (float) getTotalProgress(id), 1.0f);
    }


    @Override
    public void setProgress(int id, int progress) {
        if (id == 0)
            rocketHeight = progress;
        else if (id == 1)
            velocity = progress;
        else if (id == 2)
            fuelLevel = progress;
        else if (id == 6)
            oxidizerFuelLevel = progress;
    }

    @Override
    public int getProgress(int id) {
        // Client: use client-side cached fields (preserve original mission/height quirk)
        if (world.isRemote) {
            if (mission != null && id == 0) return getTotalProgress(id); // original oddity preserved
            if (id == 0) return rocketHeight;
            if (id == 1) return velocity;
            if (id == 2) return fuelLevel;
            if (id == 6) return oxidizerFuelLevel;
            return 0;
        }
        // Server: return snapshots only (cheap)
        if (id == 0) return snapHeight;
        if (id == 1) return snapVel;
        if (id == 2) return snapFuel; // active fuel amount 
        if (id == 6) return snapOx;   // oxidizer amount 
        return 0;
    }

    @Override
    public int getTotalProgress(int id) {
        if (id == 0)
            return ARConfiguration.getCurrentConfig().orbit;
        else if (id == 1)
            return 1000;
        else if (id == 2)
            return world.isRemote ? maxFuelLevel : snapFuelCap;
        else if (id == 6)
            return world.isRemote ? maxFuelLevel : snapOxCap;
        return 1;
    }

    @Override
    public void setTotalProgress(int id, int progress) {
        //Should only become an issue if configs are desynced or fuel
        if (id == 2 || id == 6)
            maxFuelLevel = progress;
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }
    
    @Override
    public void onModuleUpdated(ModuleBase module) {
        PacketHandler.sendToServer(new PacketMachine(this, TAB_SWITCH));
    }

    @Override
    public boolean linkMission(IMission mission) {
        this.mission = mission;
        // If we don’t already have a status, show “in orbit” while mission runs.
        if (!world.isRemote) {
            // If we were at idle/prelaunch/launching, move to "reached orbit" now.
            if (uiStatus < 3) {
                uiStatus = 3;
                lastStatusTick = world.getTotalWorldTime();
                pushState();
            }
        }

        PacketHandler.sendToNearby(new PacketMachine(this, (byte) 1), world.provider.getDimension(), getPos(), 16);
        return true;
    }

    @Override
    public void unlinkMission() {
        mission = null;
        if (missionText != null) setMissionText();  // guard
        PacketHandler.sendToNearby(new PacketMachine(this, (byte) 1), world.provider.getDimension(), getPos(), 16);
    }

    @Override
    public boolean canRenderConnection() {
        return false;
    }

    @Override
    public int getComparatorOverride() {
        if (linkedRocket instanceof EntityRocket) {
            return (int) (15 * ((EntityRocket) linkedRocket).getRelativeHeightFraction());
        }
        return 0;
    }
}
