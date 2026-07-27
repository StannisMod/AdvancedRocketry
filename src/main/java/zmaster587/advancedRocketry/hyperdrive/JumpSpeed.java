package zmaster587.advancedRocketry.hyperdrive;

/**
 * How fast a ship crosses hyperspace: drive power against hull mass.
 *
 * <p>This is the whole reason mass stops being cosmetic. Two ships with the same generator do not
 * fly at the same speed if one of them is a freighter, and a cruiser that wants a warship's transit
 * time has to carry a warship's drive.</p>
 *
 * <p>One constant speed covers every band the game wants — seconds inside a system, an hour across a
 * galaxy, months across the universe — because the distances themselves already span nine orders of
 * magnitude. Nothing piecewise is needed, and the months figure at the far end is the endgame's
 * gate, not a bug to tune away.</p>
 */
public final class JumpSpeed {

    private JumpSpeed() {
    }

    /**
     * Blocks per tick for a drive of {@code drivePower} hauling {@code shipMass}. Never below 1 —
     * the transit integrator refuses a zero step, and a ship that cannot move is a softlock rather
     * than a slow ship.
     */
    public static long blocksPerTick(long drivePower, long shipMass) {
        if (drivePower <= 0L) {
            return 0L; // no drive, no transit: this is refused upstream, not flown slowly
        }
        long mass = Math.max(1L, shipMass);
        double ratio = (drivePower / (double) DriveTuning.BASELINE_DRIVE_POWER)
                / (mass / (double) DriveTuning.BASELINE_SHIP_MASS);
        double speed = DriveTuning.BASELINE_SPEED_BLOCKS_PER_TICK * ratio;
        if (speed >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (long) speed);
    }

    /**
     * Ticks a transit of {@code distanceBlocks} takes at {@code speedBlocksPerTick}, the same way
     * the transit manager computes its own arrival tick — so the forecast the pilot reads before he
     * commits is the flight he actually gets.
     */
    public static long transitTicks(double distanceBlocks, long speedBlocksPerTick) {
        if (speedBlocksPerTick <= 0L || distanceBlocks <= 0.0D) {
            return 0L;
        }
        return (long) Math.ceil(distanceBlocks / (double) speedBlocksPerTick);
    }
}
