package zmaster587.advancedRocketry.tile.hyperdrive;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

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
 * inside it. Cells decide how much it holds; heat sinks decide how fast it recovers — and the
 * cooldown a pilot feels between jumps is nothing but that recovery, so there is no timer here and
 * no thermal state to keep.</p>
 *
 * <p><b>It never ticks.</b> The charge is arithmetic over the world clock, so a capacitor aboard a
 * ship that spent a month in hyperspace, or parked in a cell nobody loaded, is exactly as charged as
 * one that sat in a busy chunk the whole time. Only the level at the last real event, and when that
 * event was, are ever written down.</p>
 */
public class TileJumpCapacitor extends TileShipComponent {

    static final String KIND_CELL = "cell";
    static final String KIND_SINK = "sink";

    private static final String NBT_BASE_CHARGE = "capBaseCharge";
    private static final String NBT_SINCE = "capSince";

    /** The charge as of {@link #since} — the level at the last thing that actually happened. */
    private long baseCharge;
    /** World-clock tick of that event. Everything after it is computed, never accumulated. */
    private long since;

    /** How much this bank holds when full. */
    public long capacity() {
        ComponentScan.Result scan = scan();
        return DriveTuning.CAPACITOR_BASE_CAPACITY
                + scan.count(KIND_CELL) * DriveTuning.CAPACITY_PER_CELL;
    }

    /** How fast it refills. Heat sinks are the whole of the cooling system. */
    public long chargeRate() {
        ComponentScan.Result scan = scan();
        return DriveTuning.CAPACITOR_BASE_CHARGE_RATE
                + scan.count(KIND_SINK) * DriveTuning.CHARGE_RATE_PER_SINK;
    }

    /** The charge at world-clock tick {@code now}. */
    public long chargeAt(long now) {
        return CapacitorCharge.at(baseCharge, since, chargeRate(), capacity(), now);
    }

    /**
     * Ticks until this bank holds {@code needed}, or {@code -1} when it never will because it cannot
     * hold that much. This is the cooldown, and it is a consequence of the build rather than a
     * number of its own.
     */
    public long ticksUntil(long needed, long now) {
        return CapacitorCharge.ticksUntil(baseCharge, since, chargeRate(), capacity(), now, needed);
    }

    /**
     * Take {@code amount} out of the bank at {@code now}. Returns how much was actually drawn, which
     * is all of it or nothing: half a burst does not open half a window.
     */
    public long discharge(long amount, long now) {
        long available = chargeAt(now);
        if (amount <= 0L || available < amount) {
            return 0L;
        }
        baseCharge = available - amount;
        since = now;
        markDirty();
        return amount;
    }

    /** Fill the bank to the brim as of {@code now}. Used by fixtures and by creative-mode charging. */
    public void fill(long now) {
        baseCharge = capacity();
        since = now;
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
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setLong(NBT_BASE_CHARGE, baseCharge);
        nbt.setLong(NBT_SINCE, since);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        baseCharge = nbt.getLong(NBT_BASE_CHARGE);
        since = nbt.getLong(NBT_SINCE);
    }
}
