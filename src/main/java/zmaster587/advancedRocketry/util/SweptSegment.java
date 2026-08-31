package zmaster587.advancedRocketry.util;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Every block a straight segment passes through, in the order it passes through them.
 *
 * <h3>Why not "sample the path every block"</h3>
 * <p>Because sampling misses things and nobody can say which. Stepping a unit distance along the ray
 * and reading the block at each sample walks past a voxel whenever the ray clips its corner, and the
 * shape of what it misses depends on the angle — so a wall that stops a shot from the front lets it
 * through at forty degrees, intermittently. This is an exact traversal (Amanatides &amp; Woo): it
 * yields the voxels the segment actually enters, in order, with the parameter at which it enters
 * each. No step size, so no speed at which a wall becomes transparent.</p>
 *
 * <h3>Pure</h3>
 * <p>Nothing here touches a world. It is geometry, and it is unit-testable as geometry — which is the
 * point, because the property that matters ("a fast segment does not skip a block") is a property of
 * the traversal, not of the blocks it happens to find.</p>
 */
public final class SweptSegment {

    /**
     * Told about each voxel the segment enters.
     */
    public interface Visitor {
        /**
         * @param pos    the voxel
         * @param tEnter the parameter in {@code [0,1]} along {@code from -> to} at which the segment
         *               enters it ({@code 0} for the voxel the segment starts in)
         * @param entryFace the face of {@code pos} the segment came in through, as an OUTWARD normal
         *               — it points back the way the segment came, which is the surface normal
         *               anything answering a contact needs. {@code null} for the voxel the segment
         *               starts in: nothing was crossed to get there.
         * @return true to stop the traversal here
         */
        boolean visit(BlockPos pos, double tEnter, EnumFacing entryFace);
    }

    private SweptSegment() {
    }

    /**
     * Walk the voxels of {@code from -> to}, at most {@code maxVoxels} of them.
     *
     * <p>The cap is a bound on work, not a physical statement: a segment longer than the cap stops
     * being examined part way, and a caller that must not silently under-test has to notice it hit
     * the cap. It returns the number of voxels visited so the caller can.</p>
     */
    public static int traverse(Vec3d from, Vec3d to, int maxVoxels, Visitor visitor) {
        if (from == null || to == null || visitor == null || maxVoxels <= 0) {
            return 0;
        }
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        int x = floor(from.x);
        int y = floor(from.y);
        int z = floor(from.z);

        int stepX = signum(dx);
        int stepY = signum(dy);
        int stepZ = signum(dz);

        double tMaxX = firstBoundary(from.x, dx, x, stepX);
        double tMaxY = firstBoundary(from.y, dy, y, stepY);
        double tMaxZ = firstBoundary(from.z, dz, z, stepZ);

        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dz);

        double t = 0.0D;
        int visited = 0;
        // Null for the first voxel and then the face last crossed: the segment is always reported
        // WITH the way it got in, so a caller never has to re-derive it from the entry point — which
        // at a corner cannot be done unambiguously.
        EnumFacing entryFace = null;
        while (visited < maxVoxels) {
            visited++;
            if (visitor.visit(new BlockPos(x, y, z), t, entryFace)) {
                return visited;
            }
            double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (next > 1.0D || next == Double.POSITIVE_INFINITY) {
                return visited; // the segment ends inside the voxel we are in
            }
            t = next;
            // A corner crossing advances one axis here and the other on the next iteration at the
            // same t: one extra voxel is visited, which over-includes rather than skips. For a hit
            // test that is the safe direction to be wrong in.
            if (next == tMaxX) {
                x += stepX;
                tMaxX += tDeltaX;
                entryFace = stepX > 0 ? EnumFacing.WEST : EnumFacing.EAST;
            } else if (next == tMaxY) {
                y += stepY;
                tMaxY += tDeltaY;
                entryFace = stepY > 0 ? EnumFacing.DOWN : EnumFacing.UP;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
                entryFace = stepZ > 0 ? EnumFacing.NORTH : EnumFacing.SOUTH;
            }
        }
        return visited;
    }

    /** The parameter at which the segment first leaves the voxel it starts in, along one axis. */
    private static double firstBoundary(double origin, double delta, int voxel, int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = step > 0 ? voxel + 1 : voxel;
        return (boundary - origin) / delta;
    }

    private static int signum(double value) {
        return value > 0.0D ? 1 : (value < 0.0D ? -1 : 0);
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
