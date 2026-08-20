package zmaster587.advancedRocketry.tile.hyperdrive;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;

import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.hyperdrive.CapacitorCharge;
import zmaster587.advancedRocketry.hyperdrive.ComponentScan;
import zmaster587.advancedRocketry.hyperdrive.DriveTuning;
import zmaster587.advancedRocketry.tile.TileShipComponent;

/**
 * The bank that dumps the burst opening a jump window.
 *
 * <p>A jump does not need a lot of energy over time so much as a great deal of it in one instant,
 * which is why this is a separate machine standing beside the generator rather than a bigger battery
 * inside it. <b>Cells decide how much it holds; heat sinks decide how fast it can ACCEPT charge.</b>
 * The cooldown a pilot feels between jumps is how long his own power plant takes to refill it, so
 * there is no timer here and no thermal state to keep.</p>
 *
 * <h3>The energy comes from the SHIP — it is not manufactured here</h3>
 *
 * <p>This is a Forge Energy receiver like any other machine: reactors, solar arrays and cables push
 * into it. It refuses EXTRACTION through the capability on purpose — a jump bank is not a battery for
 * the rest of the vessel, and only the drive's own burst may take from it. So the biggest single cost
 * in the hyperdrive family is paid for out of generation the player built, which is what makes
 * "sustained generation aboard" a pressure rather than a sentence in a design document.</p>
 *
 * <p><b>RETRACTED, and the retraction is the point of this class's history.</b> It used to hold no
 * energy at all: the level was a closed form of the world clock,
 * {@code min(capacity, c0 + rate·(t − since))}, with the rate conjured by welding heat sinks on. That
 * bought one property — a capacitor aboard a ship in an unloaded cell was exactly as charged as one in
 * a busy chunk — and the property was only defensible while the energy was FREE. An unloaded ship's
 * reactors are not running either, so charging through an absence was creating energy from nothing a
 * second time, more quietly. The fairness it was reaching for belongs to whatever powers the ship, not
 * to its buffer.</p>
 */
public class TileJumpCapacitor extends TileShipComponent {

    static final String KIND_CELL = "cell";
    static final String KIND_SINK = "sink";

    private static final String NBT_CHARGE = "capCharge";

    /** What is actually in the bank, in Forge Energy units. Never above {@link #capacity()}. */
    private long charge;

    /**
     * The face the ship's grid pushes into. Capacity and accept rate are read from the BUILD on every
     * call rather than fixed at construction: a cell pulled out mid-flight has to make the bank
     * smaller the moment it is pulled, exactly as a coil pulled out makes the ship slower.
     */
    private final IEnergyStorage port = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return (int) acceptCharge(maxReceive, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0; // a jump bank is not the ship's battery; only the drive's burst takes from it
        }

        @Override
        public int getEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, charge);
        }

        @Override
        public int getMaxEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, capacity());
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    /** How much this bank holds when full. */
    public long capacity() {
        ComponentScan.Result scan = scan();
        return DriveTuning.CAPACITOR_BASE_CAPACITY
                + scan.count(KIND_CELL) * DriveTuning.CAPACITY_PER_CELL;
    }

    /**
     * How much charge this bank can take in one tick — a THROUGHPUT limit, not a supply. Heat sinks
     * are what let a buffer swallow a large inflow without cooking; they do not make the energy, and
     * a bank with every sink in the world fills at nothing if nothing is feeding it.
     */
    public long acceptRate() {
        ComponentScan.Result scan = scan();
        return DriveTuning.CAPACITOR_BASE_ACCEPT_RATE
                + scan.count(KIND_SINK) * DriveTuning.ACCEPT_RATE_PER_SINK;
    }

    /** What is in the bank right now. */
    public long charge() {
        return Math.min(capacity(), Math.max(0L, charge));
    }

    /**
     * Take up to {@code amount} of charge from whatever is feeding this bank, bounded by the room left
     * and by {@link #acceptRate()}. Returns how much was taken.
     *
     * <p>The RULE lives here and the Forge Energy port is three lines of delegation on top of it, so
     * "how much a bank will swallow" is a property of the machine rather than of one adapter — and it
     * can be asked about without a capability registry standing up around it.</p>
     *
     * @param simulate report what would be taken without taking it
     */
    public long acceptCharge(long amount, boolean simulate) {
        if (amount <= 0L) {
            return 0L;
        }
        long room = Math.max(0L, capacity() - charge());
        long accepted = Math.min(Math.min(room, acceptRate()), amount);
        if (accepted <= 0L) {
            return 0L;
        }
        if (!simulate) {
            charge = charge() + accepted;
            markDirty();
        }
        return accepted;
    }

    /**
     * Ticks until this bank holds {@code needed} <b>if it is fed at its full accept rate</b>, or
     * {@code -1} when it never will because it cannot hold that much.
     *
     * <p>A BEST CASE, and the honest name for it is a forecast: what the bank could do, not what the
     * ship will actually deliver. Whether the inflow is there is the power plant's business, and a
     * pilot who has under-built his reactors waits longer than this says.</p>
     */
    public long ticksUntilAtFullInflow(long needed) {
        return CapacitorCharge.ticksToReach(charge(), capacity(), acceptRate(), needed);
    }

    /**
     * Take {@code amount} out of the bank. Returns how much was actually drawn, which is all of it or
     * nothing: half a burst does not open half a window.
     */
    public long discharge(long amount) {
        long available = charge();
        if (amount <= 0L || available < amount) {
            return 0L;
        }
        charge = available - amount;
        markDirty();
        return amount;
    }

    /** Fill the bank to the brim. Used by fixtures and by creative-mode charging. */
    public void fill() {
        charge = capacity();
        markDirty();
    }

    private ComponentScan.Result scan() {
        if (world == null) {
            return ComponentScan.from(null, null, 0);
        }
        return ComponentScan.from(pos, new ComponentScan.Component() {
            @Override
            public String kindAt(BlockPos at) {
                Block block = world.getBlockState(at).getBlock();
                if (block == AdvancedRocketryBlocks.blockJumpCapacitorCell) {
                    return KIND_CELL;
                }
                if (block == AdvancedRocketryBlocks.blockJumpHeatSink) {
                    return KIND_SINK;
                }
                return null;
            }
        }, DriveTuning.MAX_CAPACITOR_COMPONENTS);
    }

    @Override
    public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability,
                                 @Nullable EnumFacing facing) {
        return capability == CapabilityEnergy.ENERGY || super.hasCapability(capability, facing);
    }

    @Override
    @Nullable
    public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability,
                               @Nullable EnumFacing facing) {
        if (capability == CapabilityEnergy.ENERGY) {
            return CapabilityEnergy.ENERGY.cast(port);
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setLong(NBT_CHARGE, charge);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        charge = nbt.getLong(NBT_CHARGE);
    }
}
