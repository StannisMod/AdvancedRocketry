package zmaster587.advancedRocketry.hyperdrive;

import net.minecraft.nbt.NBTTagCompound;

/**
 * What one ship's field generator is worth: the three numbers every other part of a jump reads.
 *
 * <p>They are produced by scanning the generator and the coils welded to it, so a bigger generator
 * is a better one — the ship's build is the progression, not a tier stamped on a single block.</p>
 */
public final class ShipDriveStats {

    private static final String NBT_POWER = "drivePower";
    private static final String NBT_DRAW = "inFlightDraw";
    private static final String NBT_BURST = "burstCost";
    private static final String NBT_TIER = "driveTier";

    /** A ship with no generator at all. Every stat is zero, which is what makes it refusable. */
    public static final ShipDriveStats NONE =
            new ShipDriveStats(0L, 0L, 0L, DriveTier.baseline());

    private final long drivePower;
    private final long inFlightDraw;
    private final long burstCost;
    private final DriveTier tier;

    public ShipDriveStats(long drivePower, long inFlightDraw, long burstCost, DriveTier tier) {
        this.drivePower = Math.max(0L, drivePower);
        this.inFlightDraw = Math.max(0L, inFlightDraw);
        this.burstCost = Math.max(0L, burstCost);
        this.tier = (tier == null) ? DriveTier.baseline() : tier;
    }

    /**
     * The stats a generator of {@code drivePower} and generation {@code tier} produces. The draw and
     * the burst are both derived from the power, so a player who builds a stronger drive automatically
     * signs up for the bigger capacitor and the heavier in-flight bill that come with it.
     *
     * <p>The tier is stated rather than assumed: it is the one thing about a drive that the blocks
     * themselves declare, and a stats object that guessed it would fly a later generation at the
     * baseline's speed with nothing to show that it had.</p>
     */
    public static ShipDriveStats ofPower(long drivePower, DriveTier tier) {
        long power = Math.max(0L, drivePower);
        if (power == 0L) {
            return NONE;
        }
        return new ShipDriveStats(power,
                (long) Math.ceil(power * DriveTuning.IN_FLIGHT_DRAW_PER_POWER),
                (long) Math.ceil(power * DriveTuning.BURST_COST_PER_POWER), tier);
    }

    /** Which generation of drive this is — the efficiency half of the speed law. */
    public DriveTier tier() {
        return tier;
    }

    /** How deep a well this drive crosses, and how fast it crosses it. */
    public long drivePower() {
        return drivePower;
    }

    /** Energy per tick the drive pulls while the window is open. */
    public long inFlightDraw() {
        return inFlightDraw;
    }

    /** Energy the capacitor must dump in one moment to open the window. */
    public long burstCost() {
        return burstCost;
    }

    /** Whether there is a drive here at all. */
    public boolean present() {
        return drivePower > 0L;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setLong(NBT_POWER, drivePower);
        nbt.setLong(NBT_DRAW, inFlightDraw);
        nbt.setLong(NBT_BURST, burstCost);
        nbt.setInteger(NBT_TIER, tier.ordinal());
    }

    public static ShipDriveStats readFromNBT(NBTTagCompound nbt) {
        return new ShipDriveStats(nbt.getLong(NBT_POWER), nbt.getLong(NBT_DRAW),
                nbt.getLong(NBT_BURST), DriveTier.byOrdinal(nbt.getInteger(NBT_TIER)));
    }

    @Override
    public String toString() {
        return "ShipDriveStats[power=" + drivePower + ",draw=" + inFlightDraw
                + ",burst=" + burstCost + ",tier=" + tier + "]";
    }
}
