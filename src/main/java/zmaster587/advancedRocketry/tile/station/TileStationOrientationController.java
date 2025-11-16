package zmaster587.advancedRocketry.tile.station;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.network.PacketStationUpdate;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.stations.SpaceStationObject;
import zmaster587.advancedRocketry.world.provider.WorldProviderSpace;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.util.INetworkMachine;

import java.util.LinkedList;
import java.util.List;

public class TileStationOrientationController extends TileEntity implements ITickable, IModularInventory, INetworkMachine, ISliderBar, IButtonInventory {

    private int[] progress;
    private long lastRotSyncTick = -5;

    private ModuleText moduleAngularVelocity, numThrusters, maxAngularAcceleration, targetRotations;

    public TileStationOrientationController() {
        moduleAngularVelocity = new ModuleText(6, 15, LibVulpes.proxy.getLocalizedString("msg.stationorientctrl.alt"), 0xaa2020);
        //numThrusters = new ModuleText(10, 25, "Number Of Thrusters: ", 0xaa2020);
        targetRotations = new ModuleText(6, 25, LibVulpes.proxy.getLocalizedString("msg.stationorientctrl.tgtalt"), 0x202020);
        progress = new int[3];

        progress[0] = getTotalProgress(0) / 2;
        progress[1] = getTotalProgress(1) / 2;
        progress[2] = getTotalProgress(2) / 2;
    }

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new LinkedList<>();
        modules.add(moduleAngularVelocity);
        //modules.add(numThrusters);
        //modules.add(maxAngularAcceleration);
        modules.add(targetRotations);

        modules.add(new ModuleText(10, 54, "X:", 0x202020));
        modules.add(new ModuleText(10, 69, "Y:", 0x202020)); //AYYYY

        modules.add(new ModuleSlider(24, 50, 0, TextureResources.doubleWarningSideBarIndicator, this));
        modules.add(new ModuleSlider(24, 65, 1, TextureResources.doubleWarningSideBarIndicator, this));
        modules.add(new ModuleButton(25, 35, 2, LibVulpes.proxy.getLocalizedString("msg.spacelaser.reset"), this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 36, 15));
        //modules.add(new ModuleSlider(24, 35, 2, TextureResources.doubleWarningSideBarIndicator, (ISliderBar)this));

        // inline updater that runs only while GUI is open
        modules.add(new ModuleBase(0, 0) {
            private SpaceStationObject cached;
            private int cachedId = Integer.MIN_VALUE;

            private int lastVelX = Integer.MIN_VALUE, lastVelY = Integer.MIN_VALUE, lastVelZ = Integer.MIN_VALUE;
            private int lastTgtX = Integer.MIN_VALUE, lastTgtY = Integer.MIN_VALUE, lastTgtZ = Integer.MIN_VALUE;

            private final String prefixVel = LibVulpes.proxy.getLocalizedString("msg.stationorientctrl.alt");
            private final String prefixTgt = LibVulpes.proxy.getLocalizedString("msg.stationorientctrl.tgtalt");

            private String oneDp(int key) {
                int abs = Math.abs(key), whole = abs / 10, frac = abs % 10;
                String s = whole + "." + frac;
                return key < 0 ? "-" + s : s;
            }

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

                double dX = cached.getDeltaRotation(EnumFacing.EAST);
                double dY = cached.getDeltaRotation(EnumFacing.UP);
                double dZ = cached.getDeltaRotation(EnumFacing.NORTH);
                int[] tgt = cached.targetRotationsPerHour;

                int velX = (int)Math.round(72000D * dX * 10D);
                int velY = (int)Math.round(72000D * dY * 10D);
                int velZ = (int)Math.round( 7200D * dZ * 10D);

                if (velX != lastVelX || velY != lastVelY || velZ != lastVelZ) {
                    moduleAngularVelocity.setText(prefixVel + oneDp(velX) + " " + oneDp(velY) + " " + oneDp(velZ));
                    lastVelX = velX; lastVelY = velY; lastVelZ = velZ;
                }

                if (tgt[0] != lastTgtX || tgt[1] != lastTgtY || tgt[2] != lastTgtZ) {
                    targetRotations.setText(prefixTgt + tgt[0] + " " + tgt[1] + " " + tgt[2]);
                    lastTgtX = tgt[0]; lastTgtY = tgt[1]; lastTgtZ = tgt[2];
                }
            }

            @Override public int getSizeX() { return 0; }
            @Override public int getSizeY() { return 0; }
        });

        
        return modules;
    }

    @Override
    public void update() {
        // Only relevant in space
        if (!(world.provider instanceof WorldProviderSpace)) return;
        // Server-side only
        if (world.isRemote) return;

        ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
        if (spaceObject == null) return;

        EnumFacing[] dirs = { EnumFacing.EAST, EnumFacing.UP, EnumFacing.NORTH };
        int[] targetRotationsPerHour = ((SpaceStationObject) spaceObject).targetRotationsPerHour;

        // keep sliders in sync with server state
        for (int i = 0; i < 3; i++) {
            setProgress(i, targetRotationsPerHour[i] + (getTotalProgress(i) / 2));
        }

        boolean updated = false;

        for (int i = 0; i < 3; i++) {
            double targetAngularVelocity = targetRotationsPerHour[i] / 72000D;
            double angVel = spaceObject.getDeltaRotation(dirs[i]);
            double acc = spaceObject.getMaxRotationalAcceleration();

            double difference = targetAngularVelocity - angVel;
            if (difference != 0) {
                double finalVel = angVel + (difference < 0 ? Math.max(difference, -acc) : Math.min(difference, acc));
                spaceObject.setDeltaRotation(finalVel, dirs[i]);
                updated = true;
            }
        }

        if (updated) {
            long t = world.getTotalWorldTime();
            if (t - lastRotSyncTick >= 5) { // ~4 Hz
                PacketHandler.sendToAll(new PacketStationUpdate(spaceObject, PacketStationUpdate.Type.ROTANGLE_UPDATE));
                lastRotSyncTick = t;
            }
        }
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockOrientationController.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        if (id == 0) {
            out.writeShort(progress[0]);
            out.writeShort(progress[1]);
            out.writeShort(progress[2]);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        if (packetId == 0) {
            setProgress(0, in.readShort());
            setProgress(1, in.readShort());
            setProgress(2, in.readShort());
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {

    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
    }


    @Override
    public float getNormallizedProgress(int id) {
        return getProgress(id) / (float) getTotalProgress(id);
    }

    @Override
    public void setProgress(int id, int progress) {

        this.progress[id] = progress;
        if (SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(this.pos) != null) {
            ((SpaceStationObject) (SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(this.pos))).setTargetRotationsPerHour(id, (progress - getTotalProgress(id) / 2));
        }
    }

    @Override
    public int getProgress(int id) {
        return this.progress[id];
    }

    @Override
    public int getTotalProgress(int id) {
        return 120;
    }

    @Override
    public void setTotalProgress(int id, int progress) {

    }

    @Override
    public void setProgressByUser(int id, int progress) {
        setProgress(id, progress);
        PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
    }

    @Override
    public void onInventoryButtonPressed(int i) {
        if (i == 2) {
            setProgress(0, getTotalProgress(0) / 2);
            setProgress(1, getTotalProgress(1) / 2);
            setProgress(2, getTotalProgress(2) / 2);
        }
        PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
    }
}
