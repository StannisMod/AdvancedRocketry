package zmaster587.advancedRocketry.tile.heat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.capability.CapabilityHeatSink;
import zmaster587.advancedRocketry.api.capability.IHeatSink;
import zmaster587.advancedRocketry.subsystem.ejection.EjectionPort;
import zmaster587.advancedRocketry.subsystem.heat.HeatNetwork;
import zmaster587.advancedRocketry.subsystem.heat.ThermalMaterial;
import zmaster587.advancedRocketry.subsystem.heat.ThermalMaterials;
import zmaster587.libVulpes.block.RotatableBlock;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModulePower;
import zmaster587.libVulpes.tile.TileInventoriedRFConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The emergency heat dump: when rejection cannot win, energy leaves the ship inside a lump of matter
 * that is thrown overboard.
 *
 * <p><b>It is deliberately not a cooling system, and the shape says so.</b> It runs only while the
 * loop is already past a temperature nothing healthy reaches, it consumes the material it charges,
 * and what it throws away is gone for good. Every one of those is a cost a radiator does not have -
 * which is the whole of the contract clause behind it: no quantity of carried slugs can stand in for
 * building enough surface, because each one is spent.
 *
 * <p><b>What it charges is whatever you put in it.</b> A slug is not a special item: it is a quantity
 * of a substance, so the capacity comes from the material table and from the shape of what is in the
 * slot. Iron beats lead not because a table says so but because iron survives to 1811 K.
 *
 * <p><b>It needs a clear exit, like a radiator does.</b> Firing a near-molten slug into a wall is not
 * an emergency measure, so a blocked port holds the slug and reports the obstruction rather than
 * dropping it inside the hull.
 */
public class TileHeatDump extends TileInventoriedRFConsumer
        implements IModularInventory, IHeatSink {

    /** How much energy the stack in the slot is carrying, in heat units. */
    public static final String NBT_CHARGE = "arHeatCharge";

    private static final int SLOT_COUNT = 1;

    /** What the port did last time it tried, for the readout. Per-tick state, never persisted. */
    private int obstruction;
    private long chargedThisTick;

    /** A real battery: the first cut passed zero here, and a machine that can hold no power never ran. */
    public TileHeatDump() {
        super(10000, SLOT_COUNT);
    }

    /** Whether it has the power to run at all, for a readout that must distinguish that from idle. */
    public boolean isPowered() {
        return hasEnoughEnergy(1);
    }

    /** The temperature past which a ship is losing badly enough to start throwing matter away. */
    public static int triggerKelvin() {
        return Math.max(1, ARConfiguration.getCurrentConfig().shipHeatDumpTriggerKelvin);
    }

    private static long throughputPerTick() {
        return Math.max(0, HeatNetwork.perTick(
                ARConfiguration.getCurrentConfig().shipHeatDumpThroughput));
    }

    /** What the slug in the slot can still take, in heat units. */
    public long headroom() {
        ItemStack stack = getStackInSlot(0);
        if (stack.isEmpty()) {
            return 0L;
        }
        ThermalMaterial material = ThermalMaterials.INSTANCE.of(stack);
        long capacity = ThermalMaterials.slugCapacity(material,
                ThermalMaterials.volumeMillilitres(stack));
        return Math.max(0L, capacity - chargeOf(stack));
    }

    @Override
    public long getSinkRequestPerTick(double loopKelvin) {
        if (!HeatNetwork.enabled() || loopKelvin < triggerKelvin() || !hasEnoughEnergy(1)) {
            return 0L;
        }
        return Math.min(throughputPerTick(), headroom());
    }

    @Override
    public long acceptHeat(long amount) {
        ItemStack stack = getStackInSlot(0);
        long taken = Math.max(0L, Math.min(amount, headroom()));
        if (taken <= 0L || stack.isEmpty()) {
            return 0L;
        }
        setChargeOf(stack, chargeOf(stack) + taken);
        chargedThisTick = taken;
        markDirty();
        // Charging is work, and it is paid per tick rather than per unit: the machine is a compressor
        // and a handling rig, not a wire, so the bill does not vanish when the loop offers little.
        energy.extractEnergy(1, false);
        if (headroom() <= 0L) {
            fire();
        }
        return taken;
    }

    /** Throw the charged slug out, if the way is clear. Answers whether it left. */
    public boolean fire() {
        ItemStack stack = getStackInSlot(0);
        if (world == null || world.isRemote || stack.isEmpty()) {
            return false;
        }
        EnumFacing facing = RotatableBlock.getFront(world.getBlockState(pos));
        obstruction = EjectionPort.obstructionDistance(world, pos, facing,
                Math.max(1, ARConfiguration.getCurrentConfig().shipHeatRadiatorClearance));
        if (obstruction != 0) {
            return false; // a blocked port holds the slug rather than dropping it inside the hull
        }
        if (!EjectionPort.eject(world, pos, facing, stack.copy())) {
            return false;
        }
        setInventorySlotContents(0, ItemStack.EMPTY);
        markDirty();
        return true;
    }

    public int getObstruction() {
        return obstruction;
    }

    public long getChargedThisTick() {
        return chargedThisTick;
    }

    /** How much heat one stack is carrying. Zero for anything that has never been in a dump. */
    public static long chargeOf(@Nonnull ItemStack stack) {
        NBTTagCompound nbt = stack.getTagCompound();
        return nbt == null ? 0L : nbt.getLong(NBT_CHARGE);
    }

    private static void setChargeOf(@Nonnull ItemStack stack, long charge) {
        NBTTagCompound nbt = stack.getTagCompound();
        if (nbt == null) {
            nbt = new NBTTagCompound();
            stack.setTagCompound(nbt);
        }
        nbt.setLong(NBT_CHARGE, charge);
    }

    @Override
    public boolean canPerformFunction() {
        return !getStackInSlot(0).isEmpty() && headroom() > 0L;
    }

    @Override
    public void performFunction() {
        // The loop drives the charging, not this tick: a sink is fed by whatever touches it, and a
        // machine that pulled for itself would have to know which loop it belonged to.
    }

    /** One slot, reachable from every side: a hopper feeding scrap in is a legitimate build. */
    @Override
    public int[] getSlotsForFace(@Nullable EnumFacing side) {
        return new int[] {0};
    }

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return ThermalMaterials.INSTANCE.of(stack) != null
                && ThermalMaterials.volumeMillilitres(stack) > 0L;
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
        return capability == CapabilityHeatSink.HEAT_SINK
                || super.hasCapability(capability, facing);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if (capability == CapabilityHeatSink.HEAT_SINK) {
            return (T) this;
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>();
        modules.add(new ModulePower(18, 20, this.energy));
        return modules;
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockHeatDump.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer player) {
        return true;
    }
}
