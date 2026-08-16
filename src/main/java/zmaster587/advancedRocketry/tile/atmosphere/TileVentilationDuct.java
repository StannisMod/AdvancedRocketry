package zmaster587.advancedRocketry.tile.atmosphere;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.atmosphere.LifeSupportNetwork;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemCable;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkManager;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkRegistry;

/**
 * The two-channel duct: stale air one way, fresh the other, as one block.
 * <p>
 * It carries a RATE and never a gas content — there is no air inside a duct to simulate, only a
 * limit on how fast a plant can serve a zone through it (D127-5). That is why the whole thing is
 * one number: run a second line, or a shorter one, and the ship supports more crew.
 */
public class TileVentilationDuct extends TileEntity implements ISubsystemCable {

    /** Work carried on the last solved tick, for a readout; not persisted, it is a per-tick figure. */
    private int transferredThisTick;

    @Override
    public SubsystemNetworkDomain getNetworkDomain() {
        return LifeSupportNetwork.DOMAIN;
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
    public int getThroughputPerTick() {
        if (!ARConfiguration.getCurrentConfig().lifeSupportZones)
            return 0;
        return LifeSupportNetwork.perTick(ARConfiguration.getCurrentConfig().lifeSupportDuctThroughput);
    }

    @Override
    public void addTransferred(int amount) {
        transferredThisTick = Math.max(0, amount);
    }

    /** What went through on the last solve. A saturated duct is what tells a player to lay another. */
    public int getTransferredThisTick() {
        return transferredThisTick;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            SubsystemNetworkRegistry.register(this);
            SubsystemNetworkManager.markDirty(LifeSupportNetwork.DOMAIN, world);
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
            SubsystemNetworkManager.markDirty(LifeSupportNetwork.DOMAIN, world);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        return super.writeToNBT(nbt);
    }
}
