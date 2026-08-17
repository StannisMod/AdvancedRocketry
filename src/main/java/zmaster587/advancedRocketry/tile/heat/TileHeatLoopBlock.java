package zmaster587.advancedRocketry.tile.heat;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.subsystem.heat.HeatNetwork;
import zmaster587.advancedRocketry.subsystem.heat.IHeatNode;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemCable;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkManager;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkRegistry;

/**
 * What every block of a coolant loop has in common: it is part of the loop's thermal mass, it
 * carries heat between the loop and what the loop is attached to, and it keeps its own share of the
 * energy so a reload finds the ship exactly as hot as it was left.
 * <p>
 * The share is stored rather than the temperature on purpose. Temperature belongs to the loop, not
 * to a block in it, and a block that wrote down a temperature would have to be told the loop's
 * capacity to be read back — which is the one thing that changes every time a player lays another
 * pipe.
 */
public abstract class TileHeatLoopBlock extends TileEntity implements ISubsystemCable, IHeatNode {

    private static final String NBT_STORED_HEAT = "heatStored";

    private long storedHeat;
    /** Heat carried on the last solved tick, for a readout; per-tick, so never persisted. */
    private int transferredThisTick;

    /** Heat units per kelvin this block absorbs. */
    @Override
    public abstract int getHeatCapacity();

    @Override
    public SubsystemNetworkDomain getNetworkDomain() {
        return HeatNetwork.DOMAIN;
    }

    @Override
    public World getNodeWorld() {
        return world;
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    @Override
    public long getStoredHeat() {
        return storedHeat;
    }

    @Override
    public void setStoredHeat(long heat) {
        long clamped = Math.max(0L, heat);
        if (clamped == storedHeat) {
            return;
        }
        storedHeat = clamped;
        markDirty();
    }

    @Override
    public int getThroughputPerTick() {
        if (!HeatNetwork.enabled())
            return 0;
        return HeatNetwork.perTick(ARConfiguration.getCurrentConfig().shipHeatPipeThroughput);
    }

    @Override
    public void addTransferred(int amount) {
        transferredThisTick = Math.max(0, amount);
    }

    /** What went through on the last solve. A saturated line is what tells a player to lay another. */
    public int getTransferredThisTick() {
        return transferredThisTick;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            SubsystemNetworkRegistry.register(this);
            SubsystemNetworkManager.markDirty(HeatNetwork.DOMAIN, world);
        }
    }

    @Override
    public void invalidate() {
        leaveNetwork();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        leaveNetwork();
        super.onChunkUnload();
    }

    private void leaveNetwork() {
        if (world != null && !world.isRemote) {
            SubsystemNetworkRegistry.unregister(this);
            SubsystemNetworkManager.markDirty(HeatNetwork.DOMAIN, world);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        storedHeat = nbt.getLong(NBT_STORED_HEAT);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setLong(NBT_STORED_HEAT, storedHeat);
        return nbt;
    }
}
