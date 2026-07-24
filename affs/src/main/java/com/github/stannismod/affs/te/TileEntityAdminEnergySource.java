package com.github.stannismod.affs.te;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.EnergyStorage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TileEntityAdminEnergySource extends TileEntity implements ITickable {

    private static final int TICK_SEND_AMOUNT = Integer.MAX_VALUE;
    private static final int CLIENT_SYNC_BASE_INTERVAL_TICKS = 20;
    private static final int CLIENT_SYNC_JITTER_TICKS = 10;
    private final InfiniteEnergyStorage energy = new InfiniteEnergyStorage();
    private int feTransferredThisTick = 0;
    private int clientSyncCountdown = -1;
    private boolean clientSyncQueued = false;

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }

        feTransferredThisTick = 0;
        // Admin source: endlessly pushes FE into adjacent FE receivers.
        for (EnumFacing facing : EnumFacing.VALUES) {
            TileEntity neighbor = world.getTileEntity(pos.offset(facing));
            if (neighbor == null) {
                continue;
            }

            if (neighbor.hasCapability(CapabilityEnergy.ENERGY, facing.getOpposite())) {
                net.minecraftforge.energy.IEnergyStorage storage = neighbor.getCapability(CapabilityEnergy.ENERGY, facing.getOpposite());
                if (storage != null && storage.canReceive()) {
                    feTransferredThisTick += storage.receiveEnergy(TICK_SEND_AMOUNT, false);
                }
            }
        }

        queueClientSync();
        tickClientSync();
    }

    public int getFeTransferredThisTick() {
        return feTransferredThisTick;
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityEnergy.ENERGY) {
            return true;
        }
        return super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityEnergy.ENERGY) {
            return CapabilityEnergy.ENERGY.cast(energy);
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("feTransferredThisTick", feTransferredThisTick);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        feTransferredThisTick = Math.max(0, compound.getInteger("feTransferredThisTick"));
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

    private static final class InfiniteEnergyStorage extends EnergyStorage {
        InfiniteEnergyStorage() {
            super(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return maxExtract;
        }

        @Override
        public int getEnergyStored() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int getMaxEnergyStored() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    }
}
