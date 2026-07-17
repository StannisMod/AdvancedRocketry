package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.AutoTakeoffPlanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the auto-takeoff corridor planner: the climb is a genuine DIAGONAL (gains
 * altitude AND translates), the corridor length reaches past the ceiling, a clear path engages, and
 * an obstruction is DECLINED (a normal surfaced outcome). The slope/speed/clearance magnitudes are
 * {@code tunable} and deliberately not pinned — only the geometric contract is.
 */
public class AutoTakeoffPlannerTest {

    private static final double EPS = 1e-9;

    @Test
    public void climbIsAUnitDiagonalGainingAltitudeAndTranslating() {
        double[] dir = AutoTakeoffPlanner.climbDirection(1.0, 0.0);
        assertEquals("climb direction is a unit vector",
                1.0, dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2], 1e-9);
        assertTrue("the ship gains altitude", dir[1] > 0.0);
        assertTrue("the ship also translates (diagonal, not vertical)",
                Math.abs(dir[0]) + Math.abs(dir[2]) > 0.0);
    }

    @Test
    public void climbFollowsTheShipHeading() {
        double[] alongX = AutoTakeoffPlanner.climbDirection(1.0, 0.0);
        double[] alongZ = AutoTakeoffPlanner.climbDirection(0.0, 1.0);
        assertTrue("heading +X translates along +X", alongX[0] > 0.0 && Math.abs(alongX[2]) < EPS);
        assertTrue("heading +Z translates along +Z", alongZ[2] > 0.0 && Math.abs(alongZ[0]) < EPS);
        // A degenerate (zero) heading still yields a valid diagonal (defaults to +X), never NaN.
        double[] zero = AutoTakeoffPlanner.climbDirection(0.0, 0.0);
        assertEquals(1.0, zero[0] * zero[0] + zero[1] * zero[1] + zero[2] * zero[2], 1e-9);
    }

    @Test
    public void corridorLengthReachesPastTheCeilingAndZeroesAtOrbit() {
        double len = AutoTakeoffPlanner.corridorLength(100.0, 1000.0);
        double[] dir = AutoTakeoffPlanner.climbDirection(1.0, 0.0);
        // Climbing `len` along the diagonal must lift the ship strictly past the ceiling.
        assertTrue("the corridor clears the ceiling", 100.0 + dir[1] * len > 1000.0);
        assertEquals("already at/above orbit needs no corridor",
                0.0, AutoTakeoffPlanner.corridorLength(1000.0, 1000.0), 0.0);
    }

    @Test
    public void clearCorridorEngages() {
        double[] dir = AutoTakeoffPlanner.climbDirection(1.0, 0.0);
        boolean clear = AutoTakeoffPlanner.corridorClear(0.5, 100.0, 0.5, dir,
                AutoTakeoffPlanner.corridorLength(100.0, 300.0), p -> false /* void above */);
        assertTrue("an empty corridor is clear (auto-takeoff engages)", clear);
    }

    @Test
    public void obstructedCorridorIsDeclined() {
        double[] dir = AutoTakeoffPlanner.climbDirection(1.0, 0.0);
        // A ceiling of solid blocks ~40 blocks up: the ray must hit one and decline.
        Predicate<long[]> solidAt = p -> p[1] >= 140 && p[1] <= 145; // a slab of terrain overhead
        boolean clear = AutoTakeoffPlanner.corridorClear(0.5, 100.0, 0.5, dir,
                AutoTakeoffPlanner.corridorLength(100.0, 400.0), solidAt);
        assertFalse("an obstructed corridor is declined (surfaced, fall back to manual)", clear);
    }

    // Minimal local functional interface alias so the test reads clearly without importing j.u.f.
    private interface Predicate<T> extends java.util.function.Predicate<T> { }
}
