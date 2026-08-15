package com.github.stannismod.affs.te;

import com.github.stannismod.affs.world.shield.IShieldCable;
import com.github.stannismod.affs.world.shield.ShieldNetworkManager;
import com.github.stannismod.affs.world.shield.ShieldNetworkRegistry;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

public class TileEntityShieldCable extends TileEntity implements ITickable, IShieldCable {

    private static final int CLIENT_SYNC_BASE_INTERVAL_TICKS = 20;
    private static final int CLIENT_SYNC_JITTER_TICKS = 10;
    private int clientSyncCountdown = -1;
    private boolean clientSyncQueued = false;

    private boolean componentConnected = false;
    private int componentStatus = 0;
    private int componentCableCount = 0;
    private int componentSourceCount = 0;
    private int componentSinkCount = 0;
    private int componentSourceAvailable = 0;
    private int componentSinkRequested = 0;
    private int componentCableCapacity = 0;
    private int componentDeliveredFlow = 0;
    private int componentSaturatedCables = 0;
    private int componentBottleneckUtilizationPermille = 0;
    private int componentAnchorX = 0;
    private int componentAnchorY = 0;
    private int componentAnchorZ = 0;
    private int componentBottleneckX = 0;
    private int componentBottleneckY = 0;
    private int componentBottleneckZ = 0;

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        queueClientSync();
        tickClientSync();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            ShieldNetworkRegistry.register(this);
            ShieldNetworkManager.markDirty(world);
            if (com.github.stannismod.affs.AdvancedForceFieldSystem.LOG != null) {
                com.github.stannismod.affs.AdvancedForceFieldSystem.LOG.info("[ShieldNetwork] load cable at {} dim={}", pos, world.provider.getDimension());
            }
        }
    }

    @Override
    public void invalidate() {
        if (world != null && !world.isRemote) {
            ShieldNetworkRegistry.unregister(this);
            ShieldNetworkManager.markDirty(world);
            if (com.github.stannismod.affs.AdvancedForceFieldSystem.LOG != null) {
                com.github.stannismod.affs.AdvancedForceFieldSystem.LOG.info("[ShieldNetwork] invalidate cable at {} dim={}", pos, world.provider.getDimension());
            }
        }
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (world != null && !world.isRemote) {
            ShieldNetworkRegistry.unregister(this);
            ShieldNetworkManager.markDirty(world);
            if (com.github.stannismod.affs.AdvancedForceFieldSystem.LOG != null) {
                com.github.stannismod.affs.AdvancedForceFieldSystem.LOG.info("[ShieldNetwork] chunk unload cable at {} dim={}", pos, world.provider.getDimension());
            }
        }
        super.onChunkUnload();
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    @Override
    public net.minecraft.world.World getNodeWorld() {
        return world;
    }

    @Override
    public int getThroughputPerTick() {
        // Config-tunable (P6): transport is meant to be the limiter of LAST resort, so this sits well
        // above one emitter's recharge throughput and a normal build is bound by emitter placement.
        return com.github.stannismod.affs.config.ModConfig.cableThroughputPerTick;
    }

    @Override
    public void addTransferredShield(int amount) {
        // The cable tracks throughput through network statistics, not local accumulation.
    }

    /**
     * The network's report, as the shared primitive delivers it. A cable is the block a player looks
     * at to ask "why is this network not keeping up", so it mirrors the whole component readout.
     */
    @Override
    public void onNetworkStats(zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState state) {
        setNetworkStats(
                state.isConnected(),
                state.getStatus(),
                state.getRoot(),
                state.getCableCount(),
                state.getSourceCount(),
                state.getSinkCount(),
                state.getSourceAvailable(),
                state.getSinkRequested(),
                state.getCableCapacity(),
                state.getDeliveredFlow(),
                state.getSaturatedCables(),
                state.getBottleneck(),
                state.getBottleneckUtilizationPermille());
    }

    public void setNetworkStats(boolean connected, int status, BlockPos anchor, int cableCount, int sourceCount, int sinkCount, int sourceAvailable, int sinkRequested, int cableCapacity, int deliveredFlow, int saturatedCables, BlockPos bottleneck, int bottleneckUtilizationPermille) {
        BlockPos safeAnchor = anchor == null ? BlockPos.ORIGIN : anchor;
        BlockPos safeBottleneck = bottleneck == null ? BlockPos.ORIGIN : bottleneck;
        boolean changed = componentConnected != connected
                || componentStatus != status
                || componentCableCount != cableCount
                || componentSourceCount != sourceCount
                || componentSinkCount != sinkCount
                || componentSourceAvailable != sourceAvailable
                || componentSinkRequested != sinkRequested
                || componentCableCapacity != cableCapacity
                || componentDeliveredFlow != deliveredFlow
                || componentSaturatedCables != saturatedCables
                || componentBottleneckUtilizationPermille != bottleneckUtilizationPermille
                || componentAnchorX != safeAnchor.getX()
                || componentAnchorY != safeAnchor.getY()
                || componentAnchorZ != safeAnchor.getZ()
                || componentBottleneckX != safeBottleneck.getX()
                || componentBottleneckY != safeBottleneck.getY()
                || componentBottleneckZ != safeBottleneck.getZ();

        componentConnected = connected;
        componentStatus = status;
        componentCableCount = cableCount;
        componentSourceCount = sourceCount;
        componentSinkCount = sinkCount;
        componentSourceAvailable = sourceAvailable;
        componentSinkRequested = sinkRequested;
        componentCableCapacity = cableCapacity;
        componentDeliveredFlow = deliveredFlow;
        componentSaturatedCables = saturatedCables;
        componentBottleneckUtilizationPermille = bottleneckUtilizationPermille;
        componentAnchorX = safeAnchor.getX();
        componentAnchorY = safeAnchor.getY();
        componentAnchorZ = safeAnchor.getZ();
        componentBottleneckX = safeBottleneck.getX();
        componentBottleneckY = safeBottleneck.getY();
        componentBottleneckZ = safeBottleneck.getZ();

        if (changed) {
            markDirty();
            queueClientSync();
        }
    }

    public String getNetworkStatusText() {
        switch (componentStatus) {
            case 1:
                return "disconnected";
            case 2:
                return "source-limited";
            case 3:
                return "sink-limited";
            case 4:
                return "cable-limited";
            case 5:
                return "balanced";
            default:
                return componentConnected ? "unknown" : "disconnected";
        }
    }

    public String getComponentAnchorString() {
        return componentAnchorX + ", " + componentAnchorY + ", " + componentAnchorZ;
    }

    public int getComponentCableCount() {
        return componentCableCount;
    }

    public int getComponentSourceCount() {
        return componentSourceCount;
    }

    public int getComponentSinkCount() {
        return componentSinkCount;
    }

    public int getComponentSourceAvailable() {
        return componentSourceAvailable;
    }

    public int getComponentSinkRequested() {
        return componentSinkRequested;
    }

    public int getComponentCableCapacity() {
        return componentCableCapacity;
    }

    public int getComponentDeliveredFlow() {
        return componentDeliveredFlow;
    }

    public int getComponentSaturatedCables() {
        return componentSaturatedCables;
    }

    public String getBottleneckCableString() {
        return componentBottleneckX + ", " + componentBottleneckY + ", " + componentBottleneckZ;
    }

    public String getBottleneckUtilizationText() {
        return (componentBottleneckUtilizationPermille / 10.0D) + "%";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setBoolean("componentConnected", componentConnected);
        compound.setInteger("componentStatus", componentStatus);
        compound.setInteger("componentCableCount", componentCableCount);
        compound.setInteger("componentSourceCount", componentSourceCount);
        compound.setInteger("componentSinkCount", componentSinkCount);
        compound.setInteger("componentSourceAvailable", componentSourceAvailable);
        compound.setInteger("componentSinkRequested", componentSinkRequested);
        compound.setInteger("componentCableCapacity", componentCableCapacity);
        compound.setInteger("componentDeliveredFlow", componentDeliveredFlow);
        compound.setInteger("componentSaturatedCables", componentSaturatedCables);
        compound.setInteger("componentBottleneckUtilizationPermille", componentBottleneckUtilizationPermille);
        compound.setInteger("componentAnchorX", componentAnchorX);
        compound.setInteger("componentAnchorY", componentAnchorY);
        compound.setInteger("componentAnchorZ", componentAnchorZ);
        compound.setInteger("componentBottleneckX", componentBottleneckX);
        compound.setInteger("componentBottleneckY", componentBottleneckY);
        compound.setInteger("componentBottleneckZ", componentBottleneckZ);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        componentConnected = compound.getBoolean("componentConnected");
        componentStatus = compound.getInteger("componentStatus");
        componentCableCount = compound.getInteger("componentCableCount");
        componentSourceCount = compound.getInteger("componentSourceCount");
        componentSinkCount = compound.getInteger("componentSinkCount");
        componentSourceAvailable = compound.getInteger("componentSourceAvailable");
        componentSinkRequested = compound.getInteger("componentSinkRequested");
        componentCableCapacity = compound.getInteger("componentCableCapacity");
        componentDeliveredFlow = compound.getInteger("componentDeliveredFlow");
        componentSaturatedCables = compound.getInteger("componentSaturatedCables");
        componentBottleneckUtilizationPermille = compound.getInteger("componentBottleneckUtilizationPermille");
        componentAnchorX = compound.getInteger("componentAnchorX");
        componentAnchorY = compound.getInteger("componentAnchorY");
        componentAnchorZ = compound.getInteger("componentAnchorZ");
        componentBottleneckX = compound.getInteger("componentBottleneckX");
        componentBottleneckY = compound.getInteger("componentBottleneckY");
        componentBottleneckZ = compound.getInteger("componentBottleneckZ");
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Nullable
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    private void syncToClient() {
        if (world == null || world.isRemote) {
            return;
        }
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
    }

    private void queueClientSync() {
        if (world == null || world.isRemote) {
            return;
        }
        if (!clientSyncQueued) {
            clientSyncQueued = true;
            clientSyncCountdown = CLIENT_SYNC_BASE_INTERVAL_TICKS - 1 + world.rand.nextInt(CLIENT_SYNC_JITTER_TICKS + 1);
        }
    }

    private void tickClientSync() {
        if (world == null || world.isRemote || !clientSyncQueued) {
            return;
        }
        if (clientSyncCountdown > 0) {
            clientSyncCountdown--;
            return;
        }
        syncToClient();
        clientSyncQueued = false;
        clientSyncCountdown = -1;
    }
}
