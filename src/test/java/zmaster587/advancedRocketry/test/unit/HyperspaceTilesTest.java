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
