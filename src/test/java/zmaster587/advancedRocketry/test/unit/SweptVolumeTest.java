package zmaster587.advancedRocketry.test.unit;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import zmaster587.advancedRocketry.util.SweptSegment;
import zmaster587.advancedRocketry.util.SweptVolume;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a body with WIDTH sweeps through, as geometry.
 *
 * <p>Two promises, and they are different from the ray's. The ray's promise — nothing between the two
 * ends is passed unseen — still holds along the axis and is inherited rather than re-tested here.
 * What is new is that a body which is wider than a line reaches things beside the line, that what it
 * has is DIVIDED between them rather than multiplied, and that no block is asked to answer the same
 * body twice. None of it mentions blocks, worlds or shots: a traversal that reported these voxels
 * would satisfy these tests whatever it was later used to look up.</p>
 */
public class SweptVolumeTest {

    private static List<SweptVolume.Layer> sweep(Vec3d from, Vec3d to, double radius) {
        final List<SweptVolume.Layer> layers = new ArrayList<>();
        SweptVolume.traverse(from, to, radius, 100_000, new SweptVolume.LayerVisitor() {
            @Override
            public boolean visit(SweptVolume.Layer layer) {
                layers.add(layer);
                return false;
            }
        });
        return layers;
    }

    private static List<BlockPos> rayBlocks(Vec3d from, Vec3d to) {
        final List<BlockPos> blocks = new ArrayList<>();
        SweptSegment.traverse(from, to, 100_000, new SweptSegment.Visitor() {
            @Override
            public boolean visit(BlockPos pos, double tEnter, EnumFacing entryFace) {
                blocks.add(pos);
                return false;
            }
        });
        return blocks;
    }

    /**
     * The reference body is 0.25 blocks across. Everything the substrate did before it had a width is
     * that body, so if the narrow case were merely CLOSE to the ray, every shipped behaviour would
     * have moved a little for no stated reason.
     */
    @Test
    public void aBodyNarrowerThanAVoxelIsExactlyTheRay() {
        Vec3d from = new Vec3d(0.3D, 0.4D, 0.2D);
        Vec3d to = new Vec3d(9.7D, 4.1D, 2.6D);

        List<BlockPos> ray = rayBlocks(from, to);
        List<SweptVolume.Layer> layers = sweep(from, to, 0.25D);

        assertEquals("a body thinner than a voxel reached a different number of slices than the ray"
                + " it is supposed to BE", ray.size(), layers.size());
        for (int i = 0; i < ray.size(); i++) {
            SweptVolume.Layer layer = layers.get(i);
            assertEquals("slice " + i + " is not the block the ray found", ray.get(i),
                    layer.axis);
            assertEquals("a narrow body spread itself over more than one block at slice " + i,
                    1, layer.size());
            assertEquals("a body with nothing to share must carry the whole of it", 1.0D,
                    layer.shares.get(0), 1.0E-12D);
        }
    }

    /** Whatever a body covers, it covers ONE cross-section of it: the shares are a division. */
    @Test
    public void aLayerDividesOneCrossSectionAndNeverMultipliesIt() {
        for (double radius : new double[] {0.25D, 0.5D, 1.0D, 1.75D}) {
            List<SweptVolume.Layer> layers = sweep(new Vec3d(0.5D, 0.5D, 0.5D),
                    new Vec3d(12.5D, 3.5D, 0.5D), radius);
            assertFalse("radius " + radius + " swept nothing at all", layers.isEmpty());
            for (SweptVolume.Layer layer : layers) {
                double total = 0.0D;
                for (Double share : layer.shares) {
                    assertTrue("a block was given a negative or zero share at radius " + radius,
                            share > 0.0D);
                    total += share;
                }
                assertEquals("the shares of one layer are not one whole cross-section (radius "
                        + radius + ", " + layer.size() + " blocks)", 1.0D, total, 1.0E-9D);
            }
        }
    }

    /**
     * The difference the whole width exists to make: a wide body reaches blocks the axis passes
     * beside. Fired along a lane between block centres, a ray touches one column and a body a block
     * across touches its neighbours too.
     */
    @Test
    public void aWideBodyReachesWhatTheAxisOnlyPassesBeside() {
        Vec3d from = new Vec3d(0.5D, 0.5D, 0.5D);
        Vec3d to = new Vec3d(8.5D, 0.5D, 0.5D);

        Set<BlockPos> narrow = blocksOf(sweep(from, to, 0.25D));
        Set<BlockPos> wide = blocksOf(sweep(from, to, 1.0D));

        assertTrue("the wide body reached fewer blocks than the ray — a body cannot cover less than"
                + " its own axis", wide.containsAll(narrow));
        assertTrue("a body a block across reached nothing beside the line it travelled along:"
                + " narrow=" + narrow.size() + " wide=" + wide.size(), wide.size() > narrow.size());
        assertTrue("the block directly beside the axis was never reached",
                wide.contains(new BlockPos(4, 0, 1)) || wide.contains(new BlockPos(4, 1, 0)));
    }

    /** Wider reaches more, at every width: the trade the calibre choice sells has to be monotone. */
    @Test
    public void aWiderBodyNeverReachesFewerBlocksThanANarrowerOne() {
        Vec3d from = new Vec3d(0.5D, 0.5D, 0.5D);
        Vec3d to = new Vec3d(10.5D, 2.5D, 1.5D);

        int previous = 0;
        for (double radius : new double[] {0.25D, 0.5D, 1.0D, 1.5D, 2.0D}) {
            int reached = blocksOf(sweep(from, to, radius)).size();
            assertTrue("radius " + radius + " reached " + reached + " blocks where the next narrower"
                    + " body reached " + previous, reached >= previous);
            previous = reached;
        }
    }

    /**
     * Consecutive slices of a wide body overlap by construction. A block offered twice would be asked
     * to answer the same body twice — and, one layer up, be charged for it twice.
     */
    @Test
    public void noBlockIsOfferedTwiceInOneSweep() {
        List<SweptVolume.Layer> layers = sweep(new Vec3d(0.5D, 0.5D, 0.5D),
                new Vec3d(14.2D, 5.7D, 3.1D), 1.5D);
        Set<BlockPos> seen = new HashSet<>();
        for (SweptVolume.Layer layer : layers) {
            for (BlockPos block : layer.blocks) {
                assertTrue("block " + block + " was offered by more than one layer of the same sweep",
                        seen.add(block));
            }
        }
    }

    /**
     * A wide sweep's layers are the ray's own slices, in the ray's own order — a subsequence of them,
     * because a slice whose blocks were all reached earlier has nothing left to offer and is not
     * reported. What must never happen is a layer out of order, or one at a place the axis never
     * went: that would be the body reaching backwards, or sideways, through the hull.
     */
    @Test
    public void everyLayerSitsWhereTheAxisWentAndInThatOrder() {
        Vec3d from = new Vec3d(0.5D, 0.5D, 0.5D);
        Vec3d to = new Vec3d(9.5D, 3.5D, 1.5D);
        List<BlockPos> ray = rayBlocks(from, to);
        List<SweptVolume.Layer> layers = sweep(from, to, 1.0D);

        assertFalse("the wide sweep reported no layers at all", layers.isEmpty());
        int cursor = 0;
        double lastT = -1.0D;
        for (SweptVolume.Layer layer : layers) {
            int at = ray.subList(cursor, ray.size()).indexOf(layer.axis);
            assertTrue("layer at " + layer.axis + " is either somewhere the axis never went, or out"
                    + " of the order the axis went in", at >= 0);
            cursor += at + 1;
            assertTrue("layers arrived out of order along the axis (" + lastT + " then "
                    + layer.tEnter + ")", layer.tEnter >= lastT);
            lastT = layer.tEnter;
            assertFalse("a layer was reported with nothing in it", layer.blocks.isEmpty());
        }
    }

    /**
     * The cap is a bound on WORK, and a wide body spends it faster than a narrow one over the same
     * travel — that is what makes it a bound rather than a distance limit in disguise.
     */
    @Test
    public void theVoxelCapBoundsWorkAndIsReported() {
        Vec3d from = new Vec3d(0.5D, 0.5D, 0.5D);
        Vec3d to = new Vec3d(400.5D, 0.5D, 0.5D);
        final int cap = 64;

        int examined = SweptVolume.traverse(from, to, 1.0D, cap, new SweptVolume.LayerVisitor() {
            @Override
            public boolean visit(SweptVolume.Layer layer) {
                return false;
            }
        });
        assertTrue("the traversal examined " + examined + " voxels under a cap of " + cap,
                examined <= cap);
        assertTrue("the traversal reported no work at all against a segment 400 blocks long",
                examined > 0);
    }

    private static Set<BlockPos> blocksOf(List<SweptVolume.Layer> layers) {
        Set<BlockPos> blocks = new HashSet<>();
        for (SweptVolume.Layer layer : layers) {
            blocks.addAll(layer.blocks);
        }
        return blocks;
    }
}
