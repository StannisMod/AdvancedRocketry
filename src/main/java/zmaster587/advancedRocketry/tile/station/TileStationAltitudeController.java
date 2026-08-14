package zmaster587.advancedRocketry.tile.station;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.network.PacketSpaceStationInfo;
import zmaster587.advancedRocketry.network.PacketStationUpdate;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.stations.SpaceStationObject;
import zmaster587.advancedRocketry.world.provider.WorldProviderSpace;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.IComparatorOverride;
import zmaster587.libVulpes.util.INetworkMachine;
import zmaster587.libVulpes.util.ZUtils.RedstoneState;

import java.util.LinkedList;
import java.util.List;

public class TileStationAltitudeController extends TileEntity implements IModularInventory, ITickable, INetworkMachine, ISliderBar, IButtonInventory, IComparatorOverride {

    int progress;
    private RedstoneState state = RedstoneState.OFF;
    private long lastAltSyncTick = -10;
    private ModuleText moduleGrav, numGravPylons, maxGravBuildSpeed, targetGrav;
    private ModuleRedstoneOutputButton redstoneControl;
    private boolean wasChanging = false;
    private ModuleText anchoredWarning;
    public TileStationAltitudeController() {
        moduleGrav = new ModuleText(6, 15, "Altitude: ", 0xaa2020);
        anchoredWarning = new ModuleText(6, 45, "", 0xaa2020);
        //numGravPylons = new ModuleText(10, 25, "Number Of Thrusters: ", 0xaa2020);
        //maxGravBuildSpeed = new ModuleText(6, 25, LibVulpes.proxy.getLocalizedString("msg.stationaltctrl.maxaltrate"), 0xaa2020);
        targetGrav = new ModuleText(6, 35, LibVulpes.proxy.getLocalizedString("msg.stationaltctrl.tgtalt"), 0x202020);
        redstoneControl = new ModuleRedstoneOutputButton(174, 4, -1, "", this);
        redstoneControl.setRedstoneState(RedstoneState.OFF);
    }

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new LinkedList<>();
        if (!world.isRemote) {
            ISpaceObject so = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
            if (so instanceof SpaceStationObject) {
                PacketHandler.sendToPlayer(new PacketSpaceStationInfo(so.getId(), (SpaceStationObject) so), player);
            }
        }
        modules.add(moduleGrav);
        //modules.add(numThrusters);
        //modules.add(maxGravBuildSpeed);
        modules.add(targetGrav);
        modules.add(anchoredWarning);
        modules.add(new ModuleSlider(6, 60, 0, TextureResources.doubleWarningSideBarIndicator, this));
        modules.add(redstoneControl);

        // inline updater that runs only while GUI is open
        modules.add(new ModuleBase(0, 0) {
            private SpaceStationObject cached;
            private int cachedId = Integer.MIN_VALUE;

            // last shown keys (ints in Km)
            private int lastAltKm = Integer.MIN_VALUE;
            private int lastTgtKm = Integer.MIN_VALUE;
            private int lastAnchored = Integer.MIN_VALUE;

            // localized prefixes (cache per GUI session)
            private final String anchoredText = LibVulpes.proxy.getLocalizedString("msg.station.anchored");
            private final String prefixAlt = LibVulpes.proxy.getLocalizedString("msg.stationaltctrl.alt");
            private final String prefixTgt = LibVulpes.proxy.getLocalizedString("msg.stationaltctrl.tgtalt");

            private boolean ensureStation() {
                if (cached == null) {
                    ISpaceObject so = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
                    if (!(so instanceof SpaceStationObject)) return false;
                    cached = (SpaceStationObject) so;
                    cachedId = so.getId();
                    return true;
                }
                ISpaceObject current = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
                if (!(current instanceof SpaceStationObject)) { cached = null; cachedId = Integer.MIN_VALUE; return false; }
                if (current.getId() != cachedId) {
                    cached = (SpaceStationObject) current;
                    cachedId = current.getId();
                }
                return true;
            }

            @Override
            public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
                if (!ensureStation()) return;

                // Anchored status
                boolean anchored = cached.isAnchored();
                int anchoredKey = anchored ? 1 : 0;
                if (anchoredKey != lastAnchored) {
                    anchoredWarning.setText(anchored ? anchoredText : "");
                    lastAnchored = anchoredKey;
                }

                // Compute display keys (Km as ints)
                int curAltKm = (int)Math.round(cached.getOrbitalDistance() * 200.0 + 100.0);
                int tgtAltKm = cached.targetOrbitalDistance * 200 + 100;

                // Update only when the visible value changes
                if (curAltKm != lastAltKm) {
                    // "Altitude: XXXKm"
                    moduleGrav.setText(prefixAlt + " " + curAltKm + "Km");
                    lastAltKm = curAltKm;
                }
                if (tgtAltKm != lastTgtKm) {
                    // "Target Altitude: YYY"
                    targetGrav.setText(prefixTgt + " " + tgtAltKm);
                    lastTgtKm = tgtAltKm;
                }
            }

            @Override public int getSizeX() { return 0; }
            @Override public int getSizeY() { return 0; }
        });


        return modules;
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId != -1)
            PacketHandler.sendToServer(new PacketMachine(this, (byte) (buttonId + 100)));
        else {
            state = redstoneControl.getState();
            PacketHandler.sendToServer(new PacketMachine(this, (byte) 2));
            markDirty();
        }
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound nbt = writeToNBT(new NBTTagCompound());


        return new SPacketUpdateTileEntity(pos, 0, nbt);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        super.onDataPacket(net, pkt);
        readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public void update() {
        if (!(world.provider instanceof WorldProviderSpace) || world.isRemote) return;

        ISpaceObject so = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
        if (so == null) return;
        SpaceStationObject sso = (SpaceStationObject) so;

        // Redstone → target. Cap at the GUI's max (getTotalProgress == 190) with
        // Math.min so lower redstone signals select lower altitudes; Math.max floored
        // every signal 0..14 to 190, leaving only 190/199 reachable by redstone.
        if (redstoneControl.getState() == RedstoneState.ON) {
            sso.targetOrbitalDistance = Math.min((world.getStrongPower(pos) * 13) + 4, 190);
        } else if (redstoneControl.getState() == RedstoneState.INVERTED) {
            sso.targetOrbitalDistance = Math.min(Math.abs(15 - world.getStrongPower(pos)) * 13 + 4, 190);
        }

        progress = sso.targetOrbitalDistance;

        // Converge with epsilon
        double target = sso.targetOrbitalDistance;
        double current = so.getOrbitalDistance();
        double diff = target - current;
        double acc = 0.02D;
        boolean changing = Math.abs(diff) >= 0.001D;

        if (changing) {
            double next = current + (diff < 0 ? Math.max(diff, -acc) : Math.min(diff, acc));
            so.setOrbitalDistance((float) next);

            long wt = world.getTotalWorldTime();
            if (wt - lastAltSyncTick >= 10L) { // ~4 Hz while changing
                PacketHandler.sendToAll(new PacketStationUpdate(so, PacketStationUpdate.Type.ALTITUDE_UPDATE));
                lastAltSyncTick = wt;
            }
            markDirty();
        } else if (wasChanging) {
            // final flush on settle so clients end exactly on the last value
            PacketHandler.sendToAll(new PacketStationUpdate(so, PacketStationUpdate.Type.ALTITUDE_UPDATE));
            markDirty();
        }

        wasChanging = changing;
    }


    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockAltitudeController.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == 0) {
            out.writeShort(progress);
        } else if (id == 2)
            out.writeByte(state.ordinal());
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        if (packetId == 0) {
            setProgress(0, in.readShort());
        } else if (packetId == 2) {
            nbt.setByte("state", in.readByte());
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
        if (id == 2) {
            state = RedstoneState.values()[nbt.getByte("state")];
            redstoneControl.setRedstoneState(state);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setByte("redstoneState", (byte) state.ordinal());
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        state = RedstoneState.values()[nbt.getByte("redstoneState")];
        redstoneControl.setRedstoneState(state);
    }


    @Override
    public float getNormallizedProgress(int id) {
        return getProgress(0) / (float) getTotalProgress(0);
    }

    @Override
    public void setProgress(int id, int progress) {

        this.progress = progress;
        if (SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(this.pos) != null) {
            ((SpaceStationObject) (SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(this.pos))).targetOrbitalDistance = progress;
        }
    }

    @Override
    public int getProgress(int id) {
        return this.progress;
    }

    @Override
    public int getTotalProgress(int id) {
        return 190;
    }

    @Override
    public void setTotalProgress(int id, int progress) {

    }

    @Override
    public int getComparatorOverride() {
        if (this.world.provider instanceof WorldProviderSpace) {
            if (!world.isRemote) {
                ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
                if (spaceObject != null) {
                    return (int) (spaceObject.getOrbitalDistance() + 5) / 13;
                }
            }
        }
        return 0;
    }

    @Override
    public void setProgressByUser(int id, int progress) {
        setProgress(id, progress);
        PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
    }
}
