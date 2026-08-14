package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.space.HyperspaceTiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the hyperspace transit-lane allocator: lanes are distinct, spaced at least the
 * design's 128 chunks (2048 blocks) apart so riders never see/track across ships on vanilla mechanics,
 * and freed lanes are recycled.
 */
public class HyperspaceTilesTest {

    @Test
    public void lanesAreDistinctAndAtLeast2048BlocksApart() {
        HyperspaceTiles tiles = new HyperspaceTiles();
        List<BlockPos> positions = new ArrayList<>();
        Set<Integer> indices = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            HyperspaceTiles.Tile t = tiles.allocate();
            assertTrue("indices are unique", indices.add(t.index));
            positions.add(t.pos);
        }
        // Every pair of live lanes is >= one spiral step (2048 blocks) apart on at least one axis.
        for (int a = 0; a < positions.size(); a++) {
            for (int b = a + 1; b < positions.size(); b++) {
                BlockPos p = positions.get(a);
                BlockPos q = positions.get(b);
                int chebyshev = Math.max(Math.abs(p.getX() - q.getX()), Math.abs(p.getZ() - q.getZ()));
                assertTrue("lanes " + a + " and " + b + " too close: " + chebyshev,
                        chebyshev >= HyperspaceTiles.SPACING_BLOCKS);
            }
        }
    }

    @Test
    public void firstLaneIsAtTheOriginColumn() {
        BlockPos p = HyperspaceTiles.tilePos(0);
        assertEquals(0, p.getX());
        assertEquals(0, p.getZ());
        assertEquals(HyperspaceTiles.BASE_Y, p.getY());
    }

    /**
     * <b>A parked ship's lane is answered by the ship's own position, at any distance.</b> This is
     * what the boot reconciliation stands on: the hulls it has to find are the ones no surviving
     * record points at, so anything that bounds the question by what the allocator knows cannot
     * reach them. Walks several rings out, because "far" is exactly the case that used to fail.
     */
    @Test
    public void everyLaneIsRecognisedFromWhereItsShipStands() {
        for (int index = 0; index < 60; index++) {
            BlockPos at = HyperspaceTiles.tilePos(index);
            assertEquals("lane " + index + " at " + at,
                    index, HyperspaceTiles.laneIndexAt(at.getX(), at.getZ()));
        }
    }

    /**
     * The converse, and it is the half that keeps the reconciliation honest: a ship that is NOT in a
     * lane must not be attributed to the nearest one. Debris from an interrupted crossing, or
     * something another mod put here, is a hull no record can claim - and disposing of the lane it
     * was wrongly assigned to would retire a lane a real jump is using.
     */
    @Test
    public void aShipThatIsInNoLaneIsNotAttributedToTheNearestOne() {
        assertEquals("halfway along the axis between two lanes belongs to neither",
                -1, HyperspaceTiles.laneIndexAt(HyperspaceTiles.SPACING_BLOCKS / 2.0, 0.0));
        // Diagonally between four lanes. Note how much room a lane owns: the margin is half the
        // spacing, so a point 990 blocks out is still inside lane 0 - "far from the middle" and
        // "outside" are not the same distance here.
        assertEquals("and neither does a point between four of them",
                -1, HyperspaceTiles.laneIndexAt(1300.0, 1300.0));
    }

    /**
     * A ship whose transform is not a number is not standing in the origin lane - it is standing
     * nowhere. Rounding NaN yields 0, so the "which lane" question answers 0 for it unless the
     * out-of-lane test is written to reject rather than to accept; the reconciliation would then
     * retire a lane with nothing in it and report a hull it could not dispose of.
     */
    @Test
    public void aPoseThatIsNotANumberIsNotTheOriginLane() {
        assertEquals(-1, HyperspaceTiles.laneIndexAt(Double.NaN, Double.NaN));
        assertEquals(-1, HyperspaceTiles.laneIndexAt(Double.NaN, 0.0));
        assertEquals(-1, HyperspaceTiles.laneIndexAt(0.0, Double.NaN));
    }

    @Test
    public void freedLaneIsRecycledLowestFirst() {
        HyperspaceTiles tiles = new HyperspaceTiles();
        HyperspaceTiles.Tile t0 = tiles.allocate(); // index 0
        HyperspaceTiles.Tile t1 = tiles.allocate(); // index 1
        tiles.allocate();                            // index 2
        assertEquals(3, tiles.inUseCount());

        tiles.free(t1);
        assertEquals(2, tiles.inUseCount());
        HyperspaceTiles.Tile reused = tiles.allocate();
        assertEquals("lowest freed index reused", t1.index, reused.index);
        assertEquals(3, tiles.inUseCount());

        // t0 untouched, still counted.
        assertEquals(0, t0.index);
    }
}
