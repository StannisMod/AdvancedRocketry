package com.github.stannismod.affs.te;

import com.github.stannismod.affs.config.ModConfig;
import com.github.stannismod.affs.world.shield.ShieldCondition;
import com.github.stannismod.affs.world.shield.ShieldNetworkManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.EnergyStorage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemSource;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkManager;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkRegistry;

public class TileEntityShieldGenerator extends TileEntity implements ITickable, ISubsystemSource {

    public static final int CONVERSION_PER_TICK = 4_000;
    private static final int CLIENT_SYNC_BASE_INTERVAL_TICKS = 20;
    private static final int CLIENT_SYNC_JITTER_TICKS = 10;

    // Buffer sizes are read from config at construction (config is loaded in preInit, long before any
    // tile is built). The generator holds only a small conversion-smoothing store, not a reserve.
    private final InputEnergyStorage feStorage = new InputEnergyStorage(ModConfig.generatorFeBuffer, CONVERSION_PER_TICK);
    private final ShieldEnergyStorage shieldStorage = new ShieldEnergyStorage(ModConfig.generatorShieldBuffer, ModConfig.generatorShieldBuffer, ModConfig.generatorShieldBuffer);
    private int feReceivedThisTick = 0;
    private int feConsumedThisTick = 0;
    private int shieldProducedThisTick = 0;
    private int shieldExtractedThisTick = 0;
    private int clientSyncCountdown = -1;
    private boolean clientSyncQueued = false;

    @Override
    public void update() {
        if (world == null) {
            return;
        }

        if (world.isRemote) {
            updateClientPrediction();
            return;
        }

        feReceivedThisTick = 0;
        feConsumedThisTick = 0;
        shieldProducedThisTick = 0;
        shieldExtractedThisTick = 0;

        int convertible = Math.min(getConversionPerTick(), feStorage.getEnergyStored());
        convertible = Math.min(convertible, shieldStorage.getMaxEnergyStored() - shieldStorage.getEnergyStored());
        if (convertible > 0) {
            feStorage.drainInternal(convertible);
            shieldStorage.receiveEnergy(convertible, false);
            feConsumedThisTick += convertible;
            shieldProducedThisTick += convertible;
            markDirty();
        }

        queueClientSync();
        tickClientSync();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            SubsystemNetworkRegistry.register(this);
            SubsystemNetworkManager.markDirty(ShieldNetworkManager.DOMAIN, world);
            if (com.github.stannismod.affs.AdvancedForceFieldSystem.LOG != null) {
                com.github.stannismod.affs.AdvancedForceFieldSystem.LOG.info("[ShieldNetwork] load generator at {} dim={}", pos, world.provider.getDimension());
            }
        }
    }

    @Override
    public void invalidate() {
        if (world != null && !world.isRemote) {
            SubsystemNetworkRegistry.unregister(this);
            SubsystemNetworkManager.markDirty(ShieldNetworkManager.DOMAIN, world);
            if (com.github.stannismod.affs.AdvancedForceFieldSystem.LOG != null) {
                com.github.stannismod.affs.AdvancedForceFieldSystem.LOG.info("[ShieldNetwork] invalidate generator at {} dim={}", pos, world.provider.getDimension());
            }
        }
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (world != null && !world.isRemote) {
            SubsystemNetworkRegistry.unregister(this);
            SubsystemNetworkManager.markDirty(ShieldNetworkManager.DOMAIN, world);
            if (com.github.stannismod.affs.AdvancedForceFieldSystem.LOG != null) {
                com.github.stannismod.affs.AdvancedForceFieldSystem.LOG.info("[ShieldNetwork] chunk unload generator at {} dim={}", pos, world.provider.getDimension());
            }
        }
        super.onChunkUnload();
    }

    @Override
    public SubsystemNetworkDomain getNetworkDomain() {
        return ShieldNetworkManager.DOMAIN;
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
    public int getAvailable() {
        return shieldStorage.getEnergyStored();
    }

    @Override
    public int extract(int amount) {
        if (world == null || world.isRemote || amount <= 0) {
            return 0;
        }
        int extracted = shieldStorage.extractEnergy(amount, false);
        if (extracted > 0) {
            shieldExtractedThisTick += extracted;
            markDirty();
            queueClientSync();
        }
        return extracted;
    }

    public int getFeStored() {
        return feStorage.getEnergyStored();
    }

    public int getFeReceivedThisTick() {
        return feReceivedThisTick;
    }

    public int getShieldStored() {
        return shieldStorage.getEnergyStored();
    }

    /**
     * How much FE this generator can turn into shield energy in one tick, in the condition it is in.
     * A battered plant converts less: the rated figure scaled by the block's own damage stage, pulled
     * from the world rather than pushed by whatever hit it.
     */
    public int getConversionPerTick() {
        return ShieldCondition.derate(world, pos, CONVERSION_PER_TICK);
    }

    public int getShieldProductionPotential() {
        return Math.max(0, Math.min(getConversionPerTick(), Math.min(feStorage.getEnergyStored(), shieldStorage.getMaxEnergyStored() - shieldStorage.getEnergyStored())));
    }

    /**
     * What the network should report as GENERATION, which is what this generator can convert this
     * tick — not the buffer it happens to be sitting on. A readout that showed the buffer could not
     * tell a running plant from a stopped one with a full tank.
     */
    @Override
    public int getGenerationPerTick() {
        return getShieldProductionPotential();
    }

    public int getMaxFeStored() {
        return feStorage.getMaxEnergyStored();
    }

    public int getMaxShieldStored() {
        return shieldStorage.getMaxEnergyStored();
    }

    public int getFeConsumedThisTick() {
        return feConsumedThisTick;
    }

    public int getShieldProducedThisTick() {
        return shieldProducedThisTick;
    }

    public int getShieldExtractedThisTick() {
        return shieldExtractedThisTick;
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
            return CapabilityEnergy.ENERGY.cast(feStorage);
        }
        return super.getCapability(capability, facing);
    }

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("fe", feStorage.getEnergyStored());
        compound.setInteger("shield", shieldStorage.getEnergyStored());
        compound.setInteger("feReceivedThisTick", feReceivedThisTick);
        compound.setInteger("feConsumedThisTick", feConsumedThisTick);
        compound.setInteger("shieldProducedThisTick", shieldProducedThisTick);
        compound.setInteger("shieldExtractedThisTick", shieldExtractedThisTick);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        feStorage.setEnergyStored(Math.max(0, Math.min(feStorage.getMaxEnergyStored(), compound.getInteger("fe"))));
        shieldStorage.setEnergyStored(Math.max(0, Math.min(shieldStorage.getMaxEnergyStored(), compound.getInteger("shield"))));
        feReceivedThisTick = Math.max(0, compound.getInteger("feReceivedThisTick"));
        feConsumedThisTick = Math.max(0, compound.getInteger("feConsumedThisTick"));
        shieldProducedThisTick = Math.max(0, compound.getInteger("shieldProducedThisTick"));
        shieldExtractedThisTick = Math.max(0, compound.getInteger("shieldExtractedThisTick"));
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        readFromNBT(tag);
    }

    @Nullable
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
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

    private void updateClientPrediction() {
        feConsumedThisTick = 0;
        shieldProducedThisTick = 0;
        shieldExtractedThisTick = 0;

        int convertible = Math.min(CONVERSION_PER_TICK, feStorage.getEnergyStored());
        convertible = Math.min(convertible, shieldStorage.getMaxEnergyStored() - shieldStorage.getEnergyStored());
        if (convertible > 0) {
            feStorage.drainInternal(convertible);
            shieldStorage.receiveEnergy(convertible, false);
            feConsumedThisTick += convertible;
            shieldProducedThisTick += convertible;
        }
    }

    private final class InputEnergyStorage extends EnergyStorage {
        InputEnergyStorage(int capacity, int maxReceive) {
            super(capacity, maxReceive, 0);
        }

        void setEnergyStored(int value) {
            this.energy = value;
        }

        void drainInternal(int amount) {
            if (amount <= 0) {
                return;
            }
            this.energy = Math.max(0, this.energy - amount);
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int accepted = super.receiveEnergy(maxReceive, simulate);
            if (accepted > 0 && !simulate) {
                feReceivedThisTick += accepted;
                markDirty();
                queueClientSync();
            }
            return accepted;
        }
    }

    private static final class ShieldEnergyStorage extends EnergyStorage {
        ShieldEnergyStorage(int capacity, int maxReceive, int maxExtract) {
            super(capacity, maxReceive, maxExtract);
        }

        void setEnergyStored(int value) {
            this.energy = value;
        }
    }
}
