package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.space.DescentShell;
import zmaster587.advancedRocketry.space.ShipEntryController;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The range a pilot is shown counts down to the surface he crosses.
 *
 * <p>The contract is not "subtract a constant" — it is that <b>the number reaches zero exactly when
 * the crossing happens</b>. That is why the legs below do not check arithmetic against itself: they
 * check the readout against {@link zmaster587.advancedRocketry.space.DescentController} the mechanic,
 * so a change to either that leaves them disagreeing is red. A test that only asserted
 * {@code max(0, d - R)} would pass just as happily on a build where the trigger fired at some other
 * radius, and the defect this exists for is precisely a readout describing a different surface from
 * the one the game acts on.</p>
 */
public class DescentShellTest {

    /** The shell as production sizes it today; read, never typed, so a retune moves the test with it. */
    private static final long R = ShipEntryController.DESCENT_RADIUS_BLOCKS;

    private static boolean triggers(double distanceToCentre) {
        return zmaster587.advancedRocketry.space.DescentController
                .shouldTriggerDescent(true, true, distanceToCentre, R);
    }

    @Test
    public void theRangeReachesZeroExactlyWhereTheDescentFires() {
        // Just outside: something left to fly, and the game has not taken the ship.
        double justOutside = R + 1d;
        assertTrue("a ship outside the shell must still have range to cover",
                DescentShell.distanceToShell(justOutside, R) > 0d);
        assertFalse("...and must not have been taken by the descent yet", triggers(justOutside));

        // On it: the readout is spent at the same instant the mechanic fires. This pair is the
        // contract; either one alone is satisfiable by a build that is wrong.
        assertEquals("the range must be spent AT the shell", 0d,
                DescentShell.distanceToShell(R, R), 0d);
        assertTrue("...and the descent must fire there", triggers(R));
    }

    @Test
    public void insideTheShellTheRangeIsZeroRatherThanNegative() {
        assertEquals("a ship already inside has nothing left to cover, not a negative amount", 0d,
                DescentShell.distanceToShell(R / 2d, R), 0d);
        assertEquals("and that holds at the body's own address", 0d,
                DescentShell.distanceToShell(0d, R), 0d);
    }

    @Test
    public void aBodyWithNoShellIsLabelledWithItsPlainDistance() {
        // A star is not something to descend to, so it carries a zero radius and the SAME arithmetic
        // must leave its distance untouched. This is what lets the renderer stay free of a
        // "which kinds have a shell" branch it would have to keep in step with the server.
        assertEquals("a shell-less body's range is its distance", 4_321d,
                DescentShell.distanceToShell(4_321d, 0L), 0d);
    }

    @Test
    public void theBoundaryOpensAsTheShipCloses() {
        // The property that makes the drawn boundary a PLACE rather than a decoration: it grows
        // monotonically on approach. The band this replaced was constant at every distance, which
        // is the thing being ruled out here.
        double far = DescentShell.boundaryHalfAngle(100d * R, R);
        double near = DescentShell.boundaryHalfAngle(2d * R, R);
        assertTrue("a distant shell must subtend less than a near one (" + far + " vs " + near + ")",
                far < near);
        assertTrue("and a distant one must still be visible at all", far > 0d);
    }

    @Test
    public void atTheCrossingTheBoundarySurroundsTheViewer() {
        assertEquals("on the shell the boundary is a great circle - you are on it",
                Math.PI / 2d, DescentShell.boundaryHalfAngle(R, R), 1.0E-9d);
        // Inside, asin's argument would exceed 1. The clamp must yield the same right angle rather
        // than NaN: a NaN here propagates into every vertex and the boundary silently vanishes at
        // exactly the moment it matters most.
        double inside = DescentShell.boundaryHalfAngle(R / 4d, R);
        assertFalse("inside the shell the angle must not be NaN", Double.isNaN(inside));
        assertEquals("...and stays the great circle", Math.PI / 2d, inside, 1.0E-9d);
    }

    @Test
    public void aBodyWithNoShellSubtendsNoBoundary() {
        assertEquals("nothing is drawn around a body that cannot be descended to", 0d,
                DescentShell.boundaryHalfAngle(5_000d, 0L), 0d);
    }

    @Test
    public void theRangeIsShorterThanTheDistanceByTheWholeShell() {
        // The defect in one line: a body-centre readout overstates the approach by a full shell.
        double d = 10_000d;
        assertEquals("the readout must differ from the centre distance by exactly the shell",
                R, d - DescentShell.distanceToShell(d, R), 0d);
    }
}
