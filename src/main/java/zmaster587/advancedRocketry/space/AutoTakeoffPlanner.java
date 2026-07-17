package zmaster587.advancedRocketry.space;

import java.util.function.Predicate;

/**
 * Pure decision core for the tier-2 <b>auto-takeoff</b> autopilot: a DIAGONAL climb corridor from the
 * ship toward orbit, and the check that the corridor is clear. Kept free of any world/VS type so the
 * geometry — the climb direction, the corridor length, and the obstruction test — is unit-testable
 * without a server.
 *
 * <p>The autopilot climb is a diagonal (not a pure vertical): the ship gains altitude AND translates
 * so it clears overhangs a vertical lift would jam against. If the corridor is blocked, that is a
 * NORMAL surfaced outcome (the pilot falls back to manual), never an error.</p>
 */
public final class AutoTakeoffPlanner {

    /** Horizontal run per block of climb (the diagonal slope: 1.0 = 45&deg;). {@code tunable}. */
    public static final double DIAGONAL_RUN_PER_RISE = 1.0;

    /** Sample step (blocks) along the corridor ray when testing for obstruction. {@code tunable}. */
    public static final double CORRIDOR_STEP = 2.0;

    /** Autopilot climb speed (blocks/second) mapped onto the diagonal. {@code tunable}. */
    public static final double CLIMB_SPEED = 6.0;

    private AutoTakeoffPlanner() { }

    /**
     * The unit climb direction {@code [x,y,z]}: straight up blended with a horizontal run along the
     * ship's heading {@code (headingX, headingZ)} (a unit XZ vector; a zero heading climbs +X).
     */
    public static double[] climbDirection(double headingX, double headingZ) {
        double hx = headingX, hz = headingZ;
        double hlen = Math.sqrt(hx * hx + hz * hz);
        if (hlen < 1e-6) {
            hx = 1.0;
            hz = 0.0;
        } else {
            hx /= hlen;
            hz /= hlen;
        }
        double dx = hx * DIAGONAL_RUN_PER_RISE;
        double dz = hz * DIAGONAL_RUN_PER_RISE;
        double dy = 1.0;
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[]{dx / n, dy / n, dz / n};
    }

    /** The commanded world-frame climb velocity {@code [x,y,z]} (blocks/second) along the corridor. */
    public static double[] climbVelocity(double headingX, double headingZ) {
        double[] dir = climbDirection(headingX, headingZ);
        return new double[]{dir[0] * CLIMB_SPEED, dir[1] * CLIMB_SPEED, dir[2] * CLIMB_SPEED};
    }

    /**
     * The corridor length (blocks along the diagonal) needed to climb from {@code fromY} to the
     * {@code ceiling}, plus a clearance margin so the ship clears the line rather than stopping on it.
     * Zero when already at/above the ceiling.
     */
    public static double corridorLength(double fromY, double ceiling) {
        double toClimb = ceiling - fromY;
        if (toClimb <= 0.0) {
            return 0.0; // already at/above orbit — no climb, no corridor to check
        }
        double rise = toClimb + CLIMB_CLEARANCE; // clear the line, don't stop on it
        double[] dir = climbDirection(1.0, 0.0);  // slope is heading-independent
        // length along the diagonal for a given vertical rise = rise / dir.y
        return rise / dir[1];
    }

    /** Blocks of extra climb past the ceiling so the ship crosses it cleanly. {@code tunable}. */
    static final double CLIMB_CLEARANCE = 16.0;

    /**
     * Whether the diagonal corridor from {@code (ox,oy,oz)} in unit direction {@code dir} is clear over
     * {@code length} blocks — sampled every {@link #CORRIDOR_STEP} blocks against {@code solidAt}
     * (which answers "is this block position solid"). {@code true} = clear (auto-takeoff may engage);
     * {@code false} = blocked (surface a decline, fall back to manual). A zero/negative length is
     * vacuously clear (already at orbit).
     */
    public static boolean corridorClear(double ox, double oy, double oz, double[] dir, double length,
                                        Predicate<long[]> solidAt) {
        if (length <= 0.0) {
            return true;
        }
        for (double t = CORRIDOR_STEP; t <= length; t += CORRIDOR_STEP) {
            long bx = (long) Math.floor(ox + dir[0] * t);
            long by = (long) Math.floor(oy + dir[1] * t);
            long bz = (long) Math.floor(oz + dir[2] * t);
            if (solidAt.test(new long[]{bx, by, bz})) {
                return false;
            }
        }
        return true;
    }
}
