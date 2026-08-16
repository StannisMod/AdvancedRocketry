package zmaster587.advancedRocketry.tile.infrastructure;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.subsystem.ejection.EjectionPort;
import zmaster587.libVulpes.tile.multiblock.hatch.TileInventoryHatch;

/**
 * An airlock that throws what is put in it overboard.
 * <p>
 * Life support's scrubbers pull carbon out of the air and leave it as dust in an output slot, and a
 * full slot backs the machine up rather than deleting the carbon — so a closed air loop needs
 * somewhere for the carbon to GO. This is that somewhere. The honest accounting: the carbon entered
 * the air as exhaled CO2 whose carbon came from imported food, so venting it exports imported mass
 * rather than breaking the oxygen loop, which stays closed.
 * <p>
 * It is deliberately a plain block — no power, no chemistry, a hole with a door. The thermal
 * system's emergency dump throws things overboard too, but it is a different machine: charging a
 * slug to just under its melting point needs materials and a duty cycle an airlock has no business
 * carrying, and that machine is meant to be unusable as a steady state where this one IS one. What
 * the two share is {@link EjectionPort}, the act itself.
 * <p>
 * Nothing restricts what may be jettisoned. Carbon dust is what the loop produces, but a port that
 * accepted only dust would be a worse block for no reason.
 */
public class TileJettisonPort extends TileInventoryHatch implements ITickable {

    /**
     * Ticks since this port last tried to fire. Its OWN counter rather than a shared
     * {@code world.getTotalWorldTime() % N}: one clock wakes every port on a ship in the same tick,
     * and a shared clock also makes the block unreachable from a harness, because force-ticking a
     * tile does not advance world time.
     */
    private int ticksSinceAttempt;

    /**
     * Distance to the first thing in the way, or 0 when the exit is clear. Kept as state rather
     * than recomputed on demand so a readout can answer "why is nothing leaving" with a place to
     * walk to instead of a boolean.
     */
    private int obstruction;

    public TileJettisonPort() {
        super(1);
        inventory.setCanInsertSlot(0, true);
        inventory.setCanExtractSlot(0, true);
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockJettisonPort.getLocalizedName();
    }

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        int interval = Math.max(1, ARConfiguration.getCurrentConfig().jettisonPortIntervalTicks);
        if (++ticksSinceAttempt < interval) {
            return;
        }
        ticksSinceAttempt = 0;

        EnumFacing facing = getFacing();
        obstruction = EjectionPort.obstructionDistance(world, pos, facing,
                ARConfiguration.getCurrentConfig().jettisonPortClearance);

        ItemStack held = getStackInSlot(0);
        if (obstruction != 0 || held.isEmpty()) {
            // A blocked port HOLDS its cargo rather than voiding it. Losing the dust because
            // somebody parked a crate in front of the door would be indistinguishable, from
            // inside the ship, from the port working.
            return;
        }
        if (EjectionPort.eject(world, pos, facing, held)) {
            setInventorySlotContents(0, ItemStack.EMPTY);
            markDirty();
        }
    }

    /** Where the door points. Falls back to UP so a port that lost its state still vents somewhere. */
    private EnumFacing getFacing() {
        try {
            EnumFacing facing = world.getBlockState(pos).getValue(
                    zmaster587.libVulpes.block.RotatableBlock.FACING);
            return facing == null ? EnumFacing.UP : facing;
        } catch (IllegalArgumentException noSuchProperty) {
            return EnumFacing.UP;
        }
    }

    /** 0 when the exit is clear, else how many blocks along the facing the obstruction sits. */
    public int getObstruction() {
        return obstruction;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        ticksSinceAttempt = nbt.getInteger("ticksSinceAttempt");
        obstruction = nbt.getInteger("obstruction");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setInteger("ticksSinceAttempt", ticksSinceAttempt);
        nbt.setInteger("obstruction", obstruction);
        return nbt;
    }
}
