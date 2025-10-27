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
import zmaster587.libVulpes.items.ItemLinker;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.IComparatorOverride;
import zmaster587.libVulpes.util.IAdjBlockUpdate;
import zmaster587.libVulpes.util.INetworkMachine;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;

public class TileRocketMonitoringStation extends TileEntity implements IModularInventory, ITickable, IAdjBlockUpdate, IInfrastructure, ILinkableTile, INetworkMachine, IButtonInventory, IProgressBar, IComparatorOverride {

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
    // 0=idle, 1=prelaunch, 2=launching, 3=orbit, 4=landed, 5=aborted
    private int uiStatus = 0;
    private transient ModuleText launchStatus;      // client-only widget
    private transient int lastUiStatusShown = -1;   // client change-detect
    // How long a status is considered fresh after the last event (in ticks)
    private static final long STATUS_STALE_TICKS = 400L; // over 20 seconds is outdated
    private long lastStatusTick = 0L; // server-only; persisted

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
        lastUiStatusShown = -1; // force client label to refresh to empty
        pushState();
    }

    public TileRocketMonitoringStation() {
        mission = null;
        missionText = new ModuleText(20, 90, LibVulpes.proxy.getLocalizedString("msg.monitoringStation.missionProgressNA"), 0x2b2b2b);
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

            if (stale || linkedRocket == null) {
                clearUiStatus();
                lastStatusTick = 0L;  // reset tick
            } else {
                pushState();           // keep fresh status visible
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

        if (!world.isRemote) {
            clearUiStatus();
            lastStatusTick = 0L;       // reset tick
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
            clearUiStatus();
            lastStatusTick = 0L;       // reset tick
        }
    }


    // --- Ticking ---

    @Override
    public void update() {
        if (world.isRemote) return;

        // One-time prime (in case no neighbor event has fired yet)
        if (!initPower) {
            isPoweredCached = world.isBlockIndirectlyGettingPowered(pos) > 0;
            initPower = true;
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
            snapFuel    = linkedRocket.getFuelAmount(active);
            snapFuelCap = linkedRocket.getFuelCapacity(active);

            snapOx      = linkedRocket.getFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER);
            snapOxCap   = linkedRocket.getFuelCapacity(FuelRegistry.FuelType.LIQUID_OXIDIZER);
        }
    }

    // --- Forge Rocket Events -> authorititative UI status (server -> client via TE update) ---

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPreLaunch(RocketEvent.RocketPreLaunchEvent e) {
        if (world == null || world.isRemote) return;
        if (linkedRocket != null && e.getEntity() == linkedRocket) {
            uiStatus = e.isCanceled() ? 5 : 1;   // aborted or prelaunch
            lastStatusTick = world.getTotalWorldTime();
            pushState();                         // single place to mark+notify
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
    public void onLanded(RocketEvent.RocketLandedEvent e) {
        if (world == null || world.isRemote) return;
        if (linkedRocket != null && e.getEntity() == linkedRocket) {
            uiStatus = 4;
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
        return nbt;
    }

    // --- LibVulpes network bridge  ---

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == 1)
            out.writeLong(mission == null ? -1 : mission.getMissionId());
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
        if (packetId == 1) {
            nbt.setLong("id", in.readLong());
        } else if (packetId == 2) {
            nbt.setByte("state", in.readByte());
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (id == 1) {
            long idNum = nbt.getLong("id");
            if (idNum == -1) {
                mission = null;
                setMissionText();
            } else {
                SatelliteBase base = DimensionManager.getInstance().getSatellite(idNum);
                if (base instanceof IMission) {
                    mission = (IMission) base;
                    setMissionText();
                }
            }
        } else if (id == 2) {
            // redstone control path was commented in original; preserved
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

        modules.add(new ModuleButton(20, 40, 0, "Launch!", this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild));

        // Status line for rocket events (client only)
        if (world.isRemote) {
            launchStatus = new ModuleText(10, 30, "", 0xFFFFFF22);
            modules.add(launchStatus);

            // Force the label to refresh on this GUI open
            lastUiStatusShown = -1;
        }


        modules.add(new ModuleProgress(98, 4, 0, new IndicatorBarImage(2, 7, 12, 81, 17, 0, 6, 6, 1, 0, EnumFacing.UP, TextureResources.rocketHud), this));
        modules.add(new ModuleProgress(120, 14, 1, new IndicatorBarImage(2, 95, 12, 71, 17, 0, 6, 6, 1, 0, EnumFacing.UP, TextureResources.rocketHud), this));
        modules.add(new ModuleProgress(142, 14, 2, new ProgressBarImage(2, 173, 12, 71, 17, 6, 3, 69, 1, 1, EnumFacing.UP, TextureResources.rocketHud), this));
        modules.add(new ModuleProgress(148, 14, 6, new ProgressBarImage(2, 173, 12, 71, 17, 75, 3, 69, 1, 1, EnumFacing.UP, TextureResources.rocketHud), this));

        setMissionText();
        modules.add(missionText);
        modules.add(new ModuleProgress(30, 110, 3, TextureResources.progressToMission, this));
        modules.add(new ModuleProgress(30, 120, 4, TextureResources.workMission, this));
        modules.add(new ModuleProgress(30, 130, 5, TextureResources.progressFromMission, this));

        if (!world.isRemote) {
            PacketHandler.sendToPlayer(new PacketMachine(this, (byte) 1), player); // mission sync
            pushState(); // TE sync (includes cleared/derived uiStatus)
        }

        return modules;
    }

    private void setMissionText() {
        if (mission != null) {
            int time = mission.getTimeRemainingInSeconds();
            int seconds = time % 60;
            int minutes = (time / 60) % 60;
            int hours = time / 3600;

            String name = (mission instanceof SatelliteBase)
                    ? ((SatelliteBase) mission).getName()
                    : LibVulpes.proxy.getLocalizedString("msg.monitoringStation.mission");

            missionText.setText(name + LibVulpes.proxy.getLocalizedString("msg.monitoringStation.progress")
                    + String.format("\n%02dhr:%02dm:%02ds", hours, minutes, seconds));
        } else {
            missionText.setText(LibVulpes.proxy.getLocalizedString("msg.monitoringStation.missionProgressNA"));
        }
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId != -1)
            PacketHandler.sendToServer(new PacketMachine(this, (byte) (buttonId + 100)));
        else
            PacketHandler.sendToServer(new PacketMachine(this, (byte) 2));
    }

    @Override
    public String getModularInventoryName() {
        return "container.monitoringstation";
    }

    @Override
    public float getNormallizedProgress(int id) {
        if (id == 1) {
            return Math.max(Math.min(0.5f + (getProgress(id) / (float) getTotalProgress(id)), 1), 0f);
        } else if (id == 3) {
            if (mission == null)
                return 0f;
            return (float) Math.min(3f * mission.getProgress(this.world), 1f);
        } else if (id == 4) {
            if (mission == null)
                return 0f;
            return (float) Math.min(Math.max(3f * (mission.getProgress(this.world) - 0.333f), 0f), 1f);
        } else if (id == 5) {
            if (mission == null)
                return 0f;
            return (float) Math.min(Math.max(3f * (mission.getProgress(this.world) - 0.666f), 0f), 1f);
        }

        // Client: reflect server-driven rocket event status in the GUI text
        if (world.isRemote && launchStatus != null && uiStatus != lastUiStatusShown) {
            lastUiStatusShown = uiStatus;
            String msg;
            switch (uiStatus) {
                case 1:  msg = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.prelaunch"); break; // "Pre-launch checks…"
                case 2:  msg = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.launching"); break; // "Launching!"
                case 3:  msg = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.orbit");     break; // "Reached orbit"
                case 4:  msg = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.landed");    break; // "Landed"
                case 5:  msg = LibVulpes.proxy.getLocalizedString("msg.monitoringStation.aborted");   break; // "Launch aborted"
                default: msg = ""; break;
            }
            launchStatus.setText(msg);
        }

        // Keep mission text updated on client
        if (world.isRemote && mission != null)
            setMissionText();

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
    public boolean linkMission(IMission mission) {
        this.mission = mission;
        PacketHandler.sendToNearby(new PacketMachine(this, (byte) 1), world.provider.getDimension(), getPos(), 16);
        return true;
    }

    @Override
    public void unlinkMission() {
        mission = null;
        setMissionText();
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
