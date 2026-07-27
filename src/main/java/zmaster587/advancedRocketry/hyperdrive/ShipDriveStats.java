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

    /** A ship with no generator at all. Every stat is zero, which is what makes it refusable. */
    public static final ShipDriveStats NONE = new ShipDriveStats(0L, 0L, 0L);

    private final long drivePower;
    private final long inFlightDraw;
    private final long burstCost;

    public ShipDriveStats(long drivePower, long inFlightDraw, long burstCost) {
        this.drivePower = Math.max(0L, drivePower);
        this.inFlightDraw = Math.max(0L, inFlightDraw);
        this.burstCost = Math.max(0L, burstCost);
    }

    /**
     * The stats a generator of {@code drivePower} produces. The draw and the burst are both derived
     * from the power, so a player who builds a stronger drive automatically signs up for the bigger
     * capacitor and the heavier in-flight bill that come with it.
     */
    public static ShipDriveStats ofPower(long drivePower) {
        long power = Math.max(0L, drivePower);
        if (power == 0L) {
            return NONE;
        }
        return new ShipDriveStats(power,
                (long) Math.ceil(power * DriveTuning.IN_FLIGHT_DRAW_PER_POWER),
                (long) Math.ceil(power * DriveTuning.BURST_COST_PER_POWER));
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
    }

    public static ShipDriveStats readFromNBT(NBTTagCompound nbt) {
        return new ShipDriveStats(nbt.getLong(NBT_POWER), nbt.getLong(NBT_DRAW),
                nbt.getLong(NBT_BURST));
    }

    @Override
    public String toString() {
        return "ShipDriveStats[power=" + drivePower + ",draw=" + inFlightDraw
                + ",burst=" + burstCost + "]";
    }
}
