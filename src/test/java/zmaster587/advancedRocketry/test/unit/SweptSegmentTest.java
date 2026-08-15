package zmaster587.advancedRocketry.test.unit;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import zmaster587.advancedRocketry.projectile.SweptSegment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The one promise a swept traversal makes: <b>nothing between the two ends is passed unseen</b>.
 *
 * <p>That is the whole reason the substrate does not step along its path reading blocks — and it is a
 * property of the geometry, so it is checkable here, at a cost of a millisecond, rather than by
 * firing rounds at walls on a server and hoping the angle that fails is one somebody tried. These
 * tests say nothing about blocks, worlds or shots: a traversal that reported the right voxels would
 * satisfy them whatever it was later used to look up.</p>
 */
public class SweptSegmentTest {

    /** Collect every voxel a segment enters, with the parameter it entered at. */
    private static List<Visit> walk(Vec3d from, Vec3d to) {
        final List<Visit> visits = new ArrayList<>();
        SweptSegment.traverse(from, to, 100_000, new SweptSegment.Visitor() {
            @Override
            public boolean visit(BlockPos pos, double tEnter) {
                visits.add(new Visit(pos, tEnter));
                return false;
            }
        });
        return visits;
    }

    @Test
    public void aOneBlockWallIsEnteredHoweverFastTheSegmentAndFromWhateverAngle() {
        // The wall is the plane x = 40, one block thick. A segment starting well short of it and
        // ending well past it MUST enter a voxel of that plane — that is what "cannot be passed
        // through" means, and it has to hold at any speed, because speed is only segment length.
        int failures = 0;
        StringBuilder detail = new StringBuilder();
        for (int speed : new int[]{2, 17, 60, 400, 3000}) {
            for (double slope = -1.5D; slope <= 1.5D; slope += 0.17D) {
                Vec3d from = new Vec3d(38.5D, 64.5D, 12.5D);
                Vec3d to = new Vec3d(38.5D + speed, 64.5D + speed * slope, 12.5D + speed * slope * 0.3D);
                boolean entered = false;
                for (Visit visit : walk(from, to)) {
                    if (visit.pos.getX() == 40) {
                        entered = true;
                        break;
                    }
                }
                if (!entered) {
                    failures++;
                    detail.append("\n  speed=").append(speed).append(" slope=").append(slope);
                }
            }
        }
        assertEquals("a segment crossing the plane x=40 skipped it:" + detail, 0, failures);
    }

    @Test
    public void consecutiveVoxelsTouchFaceToFace() {
        // A gap between two reported voxels is a hole in the path: whatever lives there was never
        // asked about. Every step must therefore move exactly one block along exactly one axis.
        List<Visit> visits = walk(new Vec3d(0.3D, 0.7D, 0.1D), new Vec3d(53.9D, -21.4D, 37.2D));
        assertTrue("a long diagonal should cross many voxels, got " + visits.size(),
                visits.size() > 50);
        for (int i = 1; i < visits.size(); i++) {
            BlockPos previous = visits.get(i - 1).pos;
            BlockPos current = visits.get(i).pos;
            int delta = Math.abs(current.getX() - previous.getX())
                    + Math.abs(current.getY() - previous.getY())
                    + Math.abs(current.getZ() - previous.getZ());
            assertEquals("step " + i + " jumped from " + previous + " to " + current, 1, delta);
        }
    }

    @Test
    public void entryParametersRunForwardAndStayInsideTheSegment() {
        // The parameter is what the caller turns into a distance and a point. Out of order, or
        // outside [0,1], and a crossing gets compared against another layer's at the wrong place.
        List<Visit> visits = walk(new Vec3d(2.25D, 70.5D, -4.75D), new Vec3d(-31.5D, 58.0D, 19.25D));
        double previous = -1.0D;
        for (Visit visit : visits) {
            assertTrue("entry parameter " + visit.t + " at " + visit.pos + " is outside the segment",
                    visit.t >= 0.0D && visit.t <= 1.0D);
            assertTrue("entry parameters went backwards at " + visit.pos, visit.t >= previous);
            previous = visit.t;
        }
        assertEquals("the traversal must start in the voxel the segment starts in", 0.0D,
                visits.get(0).t, 0.0D);
    }

    @Test
    public void aSegmentThatEndsWhereItStartedReportsOnlyItsOwnVoxel() {
        // A shot that is not going anywhere this tick must not be told it crossed something.
        List<Visit> visits = walk(new Vec3d(10.5D, 64.5D, 10.5D), new Vec3d(10.5D, 64.5D, 10.5D));
        assertEquals("a zero-length segment covers one voxel", 1, visits.size());
        assertEquals(new BlockPos(10, 64, 10), visits.get(0).pos);
    }

    @Test
    public void theVoxelCapBoundsTheWorkAndSaysHowMuchItDid() {
        // The cap has to be visible: a caller that silently examined a tenth of its path would
        // report "nothing there" about a stretch nobody looked at.
        final int[] seen = {0};
        int visited = SweptSegment.traverse(new Vec3d(0.5D, 0.5D, 0.5D), new Vec3d(900.5D, 0.5D, 0.5D),
                7, new SweptSegment.Visitor() {
                    @Override
                    public boolean visit(BlockPos pos, double tEnter) {
                        seen[0]++;
                        return false;
                    }
                });
        assertEquals("the traversal must stop at the cap", 7, visited);
        assertEquals("and must report exactly what it examined", 7, seen[0]);
    }

    private static final class Visit {
        private final BlockPos pos;
        private final double t;

        private Visit(BlockPos pos, double t) {
            this.pos = pos;
            this.t = t;
        }
    }
}
