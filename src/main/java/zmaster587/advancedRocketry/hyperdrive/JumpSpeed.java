package zmaster587.advancedRocketry.hyperdrive;

/**
 * How fast a ship crosses hyperspace: drive power against hull mass.
 *
 * <p>This is the whole reason mass stops being cosmetic. Two ships with the same generator do not
 * fly at the same speed if one of them is a freighter, and a cruiser that wants a warship's transit
 * time has to carry a warship's drive.</p>
 *
 * <h3>One constant speed does NOT cover every band — measured</h3>
 *
 * <p>This class used to claim it did, on the argument that the distances already span nine orders of
 * magnitude so nothing piecewise is needed. That was measured and is false. Crossing a system and
 * reaching the nearest star differ by about ×5 900, and one linear coefficient cannot serve both:
 * calibrated for the star, a system collapses into a single tick; calibrated for the system, the star
 * costs months. The far figure was not an endgame gate, it was the same coefficient failing at the
 * other end of its range.</p>
 *
 * <p>So speed has THREE inputs, and each one answers a different question:</p>
 * <ul>
 *   <li><b>power</b> — how big the machine is. Bought with coils, spent ONCE, and it closes the first
 *       band.</li>
 *   <li><b>mass</b> — what it is hauling. This is the whole reason mass stops being cosmetic: two
 *       ships with the same generator do not fly at the same speed if one is a freighter.</li>
 *   <li><b>{@link DriveTier}</b> — how efficiently that power becomes speed. A whole band gap per
 *       generation, because by the time a tier matters the coils are already spent.</li>
 * </ul>
 *
 * <p>Nothing here is piecewise even so: it is one formula whose efficiency term is a property of the
 * drive rather than of the distance. A leg is never classified, and no range is ever refused.</p>
 */
public final class JumpSpeed {

    private JumpSpeed() {
    }

    /**
     * Blocks per tick for a drive of {@code drivePower} and generation {@code tier} hauling
     * {@code shipMass}. Never below 1 — the transit integrator refuses a zero step, and a ship that
     * cannot move is a softlock rather than a slow ship.
     *
     * <p>There is deliberately no overload that omits the tier. Which generation of drive is flying is
     * something every caller KNOWS, and a default would quietly make the answer the baseline one for
     * whichever call site forgot — a wrong speed being harder to notice than a missing argument.</p>
     */
    public static long blocksPerTick(long drivePower, long shipMass, DriveTier tier) {
        if (drivePower <= 0L) {
            return 0L; // no drive, no transit: this is refused upstream, not flown slowly
        }
        long mass = Math.max(1L, shipMass);
        double ratio = (drivePower / (double) DriveTuning.BASELINE_DRIVE_POWER)
                / (mass / (double) DriveTuning.BASELINE_SHIP_MASS);
        double efficiency = (tier == null ? DriveTier.baseline() : tier).efficiency();
        double speed = DriveTuning.BASELINE_SPEED_BLOCKS_PER_TICK * ratio * efficiency;
        if (speed >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (long) speed);
    }

    /**
     * Total energy a leg of {@code distanceBlocks} costs a ship of {@code shipMass} on {@code tier} —
     * the in-flight draw over the whole flight.
     *
     * <p><b>Drive POWER does not appear, and that is the point.</b> Ticks go as {@code d·m/(η·P)} and
     * the draw goes as {@code P}, so the two cancel exactly: a bigger drive does not change the bill
     * for a trip, it changes how fast you pay it. "Size buys power, the tier buys efficiency" is
     * therefore arithmetic rather than a slogan — η is the only term here that a player can improve,
     * and it sits in the denominator.</p>
     */
    public static double routeEnergy(double distanceBlocks, long shipMass, DriveTier tier) {
        if (distanceBlocks <= 0d) {
            return 0d;
        }
        double efficiency = (tier == null ? DriveTier.baseline() : tier).efficiency();
        double massRatio = Math.max(1L, shipMass) / (double) DriveTuning.BASELINE_SHIP_MASS;
        return DriveTuning.IN_FLIGHT_DRAW_PER_POWER * distanceBlocks * massRatio
                * DriveTuning.BASELINE_DRIVE_POWER
                / (efficiency * DriveTuning.BASELINE_SPEED_BLOCKS_PER_TICK);
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
