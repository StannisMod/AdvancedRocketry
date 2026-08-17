package zmaster587.advancedRocketry.tile.heat;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.capability.CapabilityHeatPump;
import zmaster587.advancedRocketry.api.capability.IHeatPump;
import zmaster587.advancedRocketry.subsystem.heat.HeatNetwork;
import zmaster587.advancedRocketry.subsystem.heat.IHeatNode;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import zmaster587.libVulpes.block.RotatableBlock;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModulePower;
import zmaster587.libVulpes.tile.TileEntityRFConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The chiller: the machine that drives one coolant loop hot so its radiators can actually shed, at
 * the price of electricity forever.
 * <p>
 * <b>It stands between two loops and belongs to neither.</b> The loop behind it is the cold side —
 * the one the ship's machines heat — and the loop in front is the hot side, where the radiators live.
 * Being a member of neither graph is what keeps them two loops: adjacency is what joins a network, so
 * a machine plumbed INTO both would merge the very temperatures it exists to keep apart.
 * <p>
 * <b>It sets no temperature.</b> It moves energy; the energy piles up on the hot side against that
 * side's own capacity; the temperature follows. So the hot side is a real reservoir — a burst heats
 * it, a bigger one heats slower — and how far the ship can drive it is bounded by Carnot rather than
 * by a number somebody picked.
 */
public class TileHeatChiller extends TileEntityRFConsumer
        implements IModularInventory, IHeatPump, IHeatNode {

    private static final String NBT_STORED_HEAT = "heatStored";

    /** Work paid on the last tick, for a readout. Per-tick, so never persisted. */
    private long workThisTick;
    /** This machine's share of its hot loop's energy. A lump of metal that runs hot. */
    private long storedHeat;
    /** Heat shifted on the last tick, for a readout. */
    private long movedThisTick;

    public TileHeatChiller() {
        super(100000);
    }

    /** The side the hot loop is on. A chiller is aimed by placing it, like any machine with a front. */
    public EnumFacing getHotFacing() {
        if (world == null) {
            return EnumFacing.NORTH;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getProperties().containsKey(RotatableBlock.FACING)) {
            return state.getValue(RotatableBlock.FACING);
        }
        return EnumFacing.NORTH;
    }

    @Override
    public BlockPos getHotSideAnchor() {
        return pos == null ? null : pos.offset(getHotFacing());
    }

    /** The cold side is simply the other end of the machine. */
    public BlockPos getColdSideAnchor() {
        return pos == null ? null : pos.offset(getHotFacing().getOpposite());
    }

    @Override
    public boolean drawsFrom(BlockPos loopMemberPos) {
        BlockPos cold = getColdSideAnchor();
        return cold != null && cold.equals(loopMemberPos);
    }

    @Override
    public int getThroughputPerTick() {
        if (world == null || world.isRemote || !HeatNetwork.enabled())
            return 0;
        // Nothing in the buffer means nothing shifts: the benefit is scaled to what can be paid for,
        // so a starved chiller degrades toward the tier below rather than working for free.
        if (energy.getUniversalEnergyStored() <= 0)
            return 0;
        return HeatNetwork.perTick(ARConfiguration.getCurrentConfig().shipHeatChillerThroughput);
    }

    @Override
    public long payWork(long work) {
        if (work <= 0L) {
            workThisTick = 0L;
            return 0L;
        }
        int wanted = (int) Math.min(Integer.MAX_VALUE, work);
        workThisTick = Math.max(0L, energy.extractEnergy(wanted, false));
        return workThisTick;
    }

    /** For a readout: what this chiller paid, and what it shifted, on the last solve. */
    public long getWorkThisTick() {
        return workThisTick;
    }

    public long getMovedThisTick() {
        return movedThisTick;
    }

    public void setMovedThisTick(long moved) {
        this.movedThisTick = Math.max(0L, moved);
    }

    // ─── the machine's own thermal mass ────────────────────────────────
    //
    // A chiller is a lump of metal and refrigerant bolted to its hot loop, so it IS part of that
    // loop's thermal mass — which is why the hot side climbs more slowly than its pipes alone would
    // explain. It gets there without being a network member: the loop folds a bolted machine's
    // capacity in and hands it back a share at one common temperature, which is the calorimeter rule
    // the gas model already runs on. Membership would additionally make it conduct and be routed
    // through, and a machine between two temperatures must do neither.

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
    public int getHeatCapacity() {
        if (!HeatNetwork.enabled())
            return 0;
        return Math.max(0, ARConfiguration.getCurrentConfig().shipHeatChillerCapacity);
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

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityHeatPump.HEAT_PUMP) {
            return true;
        }
        return super.hasCapability(capability, facing);
    }

    @Override
    @Nullable
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityHeatPump.HEAT_PUMP) {
            return CapabilityHeatPump.HEAT_PUMP.cast(this);
        }
        return super.getCapability(capability, facing);
    }

    /**
     * The chiller does its work through the cold loop's own tick, not through a machine cycle of its
     * own — the loop is what knows the temperatures the exchange happens between.
     */
    @Override
    public boolean canPerformFunction() {
        return false;
    }

    @Override
    public void performFunction() {
    }

    @Override
    public int getPowerPerOperation() {
        return 0;
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockHeatChiller.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>(1);
        modules.add(new ModulePower(18, 20, this.energy));
        return modules;
    }
}
