package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for {@link FreeFlightPhysics#atmosphericDrag} — the bound that replaced the speed cap.
 *
 * <p>Free flight is bounded by acceleration and not by speed, which leaves one hole: a craft may
 * arrive at a planet arbitrarily fast and nothing charges it. An atmosphere charges it. What is pinned
 * here is that the charge behaves like air — it opposes motion, scales with density, never turns a
 * craft and never pushes it backwards — and that the drag constant means what its derivation says.</p>
 */
public class AtmosphericDragTest {

    private static final double EPS = 1e-9;

    private static double speed(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    @Test
    public void vacuumChangesNothing() {
        double[] v = FreeFlightPhysics.atmosphericDrag(30.0, -12.0, 4.0, 0.0);
        assertEquals(30.0, v[0], EPS);
        assertEquals(-12.0, v[1], EPS);
        assertEquals(4.0, v[2], EPS);

        double[] negative = FreeFlightPhysics.atmosphericDrag(30.0, -12.0, 4.0, -1.0);
        assertEquals("a negative density is vacuum, not thrust", 30.0, negative[0], EPS);
    }

    /**
     * The derivation itself: at the stated terminal speed in one atmosphere, drag must exactly cancel
     * full thrust — that is what makes it a TERMINAL speed rather than a number someone liked. Read
     * from the class, so re-deriving either input keeps this honest.
     */
    @Test
    public void atTheTerminalSpeedDragCancelsFullThrust() {
        double vTerm = FreeFlightPhysics.ATMOSPHERIC_TERMINAL_SPEED;
        double[] after = FreeFlightPhysics.atmosphericDrag(vTerm, 0.0, 0.0, 1.0);
        double lost = vTerm - after[0];
        assertEquals("drag at terminal speed must equal the thrust budget, or the constant is not "
                        + "the one its derivation claims",
                FreeFlightPhysics.MAX_THRUST_ACCEL, lost, 1e-9);
    }

    @Test
    public void dragOpposesMotionAndDoesNotTurnIt() {
        double[] before = {12.0, -5.0, 3.0};
        double[] after = FreeFlightPhysics.atmosphericDrag(before[0], before[1], before[2], 1.0);

        assertTrue("air must slow a craft", speed(after) < speed(before));
        // Same direction: the cross product of the two velocity vectors is zero.
        double cx = before[1] * after[2] - before[2] * after[1];
        double cy = before[2] * after[0] - before[0] * after[2];
        double cz = before[0] * after[1] - before[1] * after[0];
        assertEquals("drag may not steer", 0.0, Math.sqrt(cx * cx + cy * cy + cz * cz), 1e-9);
        assertTrue("and may not reverse the craft", after[0] > 0.0 && after[1] < 0.0 && after[2] > 0.0);
    }

    /**
     * The clamp. An unclamped quadratic at high speed removes more velocity than the craft has, which
     * would fly it backwards out of the atmosphere it just entered — a hull bouncing off the sky.
     */
    @Test
    public void airBringsACraftToRestButNeverThroughIt() {
        double absurd = 100.0 * FreeFlightPhysics.ATMOSPHERIC_TERMINAL_SPEED;
        double[] after = FreeFlightPhysics.atmosphericDrag(absurd, 0.0, 0.0, 1.0);
        assertTrue("never reversed: " + after[0], after[0] >= 0.0);
        assertTrue("and never faster than it arrived", after[0] <= absurd);
    }

    @Test
    public void denserAirBrakesHarder() {
        double[] thin = FreeFlightPhysics.atmosphericDrag(50.0, 0.0, 0.0, 0.2);
        double[] thick = FreeFlightPhysics.atmosphericDrag(50.0, 0.0, 0.0, 1.0);
        assertTrue("a thicker atmosphere must take more speed: thin=" + thin[0] + " thick=" + thick[0],
                thick[0] < thin[0]);
    }

    /**
     * Quadratic, not linear: doubling the speed must more than double the loss. Pinned because a
     * linear drag would let a craft enter arbitrarily fast and lose a fixed fraction — which is the
     * hole this closes, reopened.
     */
    @Test
    public void theLossGrowsWithTheSquareOfSpeed() {
        double slowLoss = 20.0 - FreeFlightPhysics.atmosphericDrag(20.0, 0.0, 0.0, 1.0)[0];
        double fastLoss = 40.0 - FreeFlightPhysics.atmosphericDrag(40.0, 0.0, 0.0, 1.0)[0];
        assertEquals("twice the speed, four times the loss", 4.0, fastLoss / slowLoss, 1e-6);
    }
}
