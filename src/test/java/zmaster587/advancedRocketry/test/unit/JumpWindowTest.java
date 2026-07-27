package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.hyperdrive.JumpWindow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the jump window promises: a generator can carry a small ship on its own, emitters are how a
 * big hull gets to travel in one piece, and a hull that does not fit is <b>measured</b> rather than
 * guessed at.
 *
 * <p>The last one is the sharp edge. A window is a union of envelopes, and a union of envelopes is
 * not an envelope: a hull can lie entirely inside the box that bounds two emitters while poking
 * straight through the gap between them. A coverage check that tested the bounding box would call
 * that ship covered and shear nothing — so the test below builds exactly that shape.</p>
 */
public class JumpWindowTest {

    private static JumpWindow.Envelope hull(int minX, int minY, int minZ,
                                            int maxX, int maxY, int maxZ) {
        return new JumpWindow.Envelope(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Test
    public void aGeneratorAloneCarriesASmallHull() {
        JumpWindow window = JumpWindow.of(new BlockPos(0, 64, 0), new ArrayList<BlockPos>());

        JumpWindow.Coverage coverage = window.cover(hull(-1, 63, -1, 1, 65, 1));

        assertTrue("a starter ship with no emitters at all must still be able to jump: " + coverage,
                coverage.complete());
    }

    @Test
    public void aHullBiggerThanTheBaselineWindowSticksOut() {
        JumpWindow window = JumpWindow.of(new BlockPos(0, 64, 0), new ArrayList<BlockPos>());

        JumpWindow.Coverage coverage = window.cover(hull(-10, 54, -10, 10, 74, 10));

        assertFalse(coverage.complete());
        assertTrue("the warning quotes how much is outside, so a corner reads differently "
                + "from half the ship: " + coverage, coverage.uncoveredBlocks() > 0L);
        assertTrue("and most of the hull is outside a 5x5x5 window", coverage.fraction() < 0.5D);
    }

    @Test
    public void emittersExtendTheWindowOverALongerHull() {
        BlockPos generator = new BlockPos(0, 64, 0);
        JumpWindow bare = JumpWindow.of(generator, new ArrayList<BlockPos>());
        JumpWindow extended = JumpWindow.of(generator,
                Arrays.asList(new BlockPos(6, 64, 0), new BlockPos(12, 64, 0)));

        JumpWindow.Envelope longHull = hull(-2, 62, -2, 16, 66, 2);

        assertFalse("precondition: the bare generator cannot hold this hull",
                bare.cover(longHull).complete());
        assertTrue("emitters spread along the hull are what let it travel in one piece",
                extended.cover(longHull).complete());
    }

    @Test
    public void aHullThroughTheGapBetweenTwoEmittersIsNotCovered() {
        // The two envelopes are far enough apart to leave a hole between them, and the hull spans
        // both. Anything that tested the BOUNDING BOX of the window would report full coverage here.
        List<BlockPos> emitters = Arrays.asList(new BlockPos(-40, 64, 0), new BlockPos(40, 64, 0));
        JumpWindow window = JumpWindow.of(new BlockPos(0, 200, 0), emitters);

        JumpWindow.Coverage coverage = window.cover(hull(-40, 64, 0, 40, 64, 0));

        assertFalse("the middle of this hull is in no envelope at all: " + coverage,
                coverage.complete());
        assertTrue("and the gap is what is reported", coverage.uncoveredBlocks() > 0L);
    }

    @Test
    public void aShipWithNoGeneratorHasNoWindow() {
        JumpWindow window = JumpWindow.of(null, new ArrayList<BlockPos>());

        JumpWindow.Coverage coverage = window.cover(hull(0, 64, 0, 0, 64, 0));

        assertFalse(coverage.complete());
        assertEquals(1L, coverage.uncoveredBlocks());
    }
}
