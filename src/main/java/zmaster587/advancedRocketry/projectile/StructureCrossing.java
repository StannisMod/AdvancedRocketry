package zmaster587.advancedRocketry.projectile;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.damage.StructureDamageEngine;
import zmaster587.advancedRocketry.util.SweptSegment;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

import java.util.Map;

/**
 * Where along a swept segment the first solid thing is — and nothing about what happens next.
 *
 * <h3>Two places blocks live, one answer</h3>
 * <p>A world's own blocks sit in the world frame. A ship's blocks do not: they stay at fixed
 * addresses in a shipyard subspace while the ship flies elsewhere, so a traversal of the world frame
 * finds nothing where a hull visibly is. Both are searched here and the earlier crossing wins, which
 * is what lets everything above this class hold a single question — "what did I hit first" — instead
 * of a branch on target class.</p>
 *
 * <h3>The ship segment is transformed, not sampled</h3>
 * <p>A ship's transform is rigid, so a straight world segment is a straight subspace segment: both
 * endpoints are mapped and the same exact traversal runs there. Sampling the world segment and asking
 * "is there a ship block under this point" would reintroduce, one layer up, the skipping that the
 * traversal exists to remove.</p>
 *
 * <h3>What this does NOT see — stage 1</h3>
 * <p>An unloaded region. A voxel whose chunk is not loaded is <b>skipped</b>, not treated as solid and
 * not treated as empty — nobody looked. That is honest only because stage 1 fires in the band ships
 * fly in, where there are no world blocks at all; on a planet it means a shot crosses unloaded terrain
 * untouched. Closing it is stage 2's conservative occupancy summary, and until that lands this
 * limitation is the reason a ground battery is not yet a shipped feature.</p>
 */
public final class StructureCrossing {

    /**
     * How many voxels one segment may be examined over in one tick, per frame searched. A bound on
     * work: a shot faster than this per tick stops being tested part way along its step. Nothing in
     * stage 1 travels that fast, and the cap logs rather than lies when something does.
     */
    private static final int MAX_VOXELS_PER_SEGMENT = 4096;

    /** The crossing, in the caller's own (world) terms. */
    static final class Hit {
        /** Distance from the segment start, in blocks. */
        final double distance;
        /** Where it happened, in WORLD coordinates. */
        final Vec3d point;
        /** The block struck, in the frame it was found in. */
        final BlockPos block;
        /** The ship whose blocks were struck, or null for the world's own. */
        final String shipId;
        /**
         * The face the segment came in through, as an outward normal IN THAT FRAME — so on a ship it
         * is a subspace face, which is what makes it comparable with a subspace-expressed velocity.
         * Null when the segment began already inside the block it struck.
         */
        final EnumFacing entryFace;

        private Hit(double distance, Vec3d point, BlockPos block, String shipId, EnumFacing entryFace) {
            this.distance = distance;
            this.point = point;
            this.block = block;
            this.shipId = shipId;
            this.entryFace = entryFace;
        }
    }

    private StructureCrossing() {
    }

    /**
     * Whether anything solid stands between two WORLD points — the question a weapon asks about its
     * own line of fire before it commits a round to it.
     *
     * <p>Exposed rather than re-derived because a second implementation of "is there structure here"
     * is a second answer: a gun that cleared a path the substrate then found blocked would fire into
     * its own hull for reasons nobody could reproduce.</p>
     */
    public static boolean isBlocked(World world, Vec3d from, Vec3d to) {
        return firstAlong(world, from, to) != null;
    }

    /** The first structure the segment {@code from -> to} meets, or null when it meets none. */
    static Hit firstAlong(World world, Vec3d from, Vec3d to) {
        return firstAlong(world, from, to, null);
    }

    /**
     * The same question, optionally narrowed to ONE hull.
     *
     * <p>{@code onlyHullId} is not a filter for convenience: a shot that is inside a hull's material
     * is inside that hull and nothing else, so asking the world frame and every other loaded ship
     * about it is work whose answer is known in advance. Null asks everything, which is what a body
     * in open space needs.</p>
     */
    static Hit firstAlong(World world, Vec3d from, Vec3d to, String onlyHullId) {
        if (world == null || from == null || to == null) {
            return null;
        }
        double length = to.subtract(from).lengthVector();
        if (length <= 0.0D) {
            return null;
        }

        if (onlyHullId != null) {
            return shipFrameHit(world, onlyHullId, from, to, length);
        }

        Hit best = worldFrameHit(world, from, to, length);
        // The segment's own bounding box, min-first: AxisAlignedBB#intersects reads its six doubles
        // as an ordered box and quietly answers "no" for one given the other way round.
        double minX = Math.min(from.x, to.x);
        double minY = Math.min(from.y, to.y);
        double minZ = Math.min(from.z, to.z);
        double maxX = Math.max(from.x, to.x);
        double maxY = Math.max(from.y, to.y);
        double maxZ = Math.max(from.z, to.z);
        Map<String, AxisAlignedBB> ships = VSIntegration.loadedShipWorldBounds(world);
        for (Map.Entry<String, AxisAlignedBB> ship : ships.entrySet()) {
            if (!ship.getValue().intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
                continue;
            }
            Hit hit = shipFrameHit(world, ship.getKey(), from, to, length);
            if (hit != null && (best == null || hit.distance < best.distance)) {
                best = hit;
            }
        }
        return best;
    }

    private static Hit worldFrameHit(World world, Vec3d from, Vec3d to, double length) {
        // Above or below the build height there are no world blocks by construction, and the pose
        // band ships fly in is entirely up there. Skipping the traversal is not an optimisation for
        // its own sake: it is what keeps a shot crossing a cell from touching the chunk system at all.
        double minY = Math.min(from.y, to.y);
        double maxY = Math.max(from.y, to.y);
        if (maxY < 0.0D || minY > world.getHeight()) {
            return null;
        }
        return traverse(world, from, to, length, null);
    }

    private static Hit shipFrameHit(World world, String shipId, Vec3d from, Vec3d to, double length) {
        double[] localFrom = VSIntegration.toShipFrameFor(world, shipId, from.x, from.y, from.z);
        double[] localTo = VSIntegration.toShipFrameFor(world, shipId, to.x, to.y, to.z);
        if (localFrom == null || localTo == null) {
            return null;
        }
        return traverse(world, new Vec3d(localFrom[0], localFrom[1], localFrom[2]),
                new Vec3d(localTo[0], localTo[1], localTo[2]), length, shipId);
    }

    /**
     * Traverse one frame's voxels and report the first solid one, expressed back in world terms.
     * {@code worldLength} is the segment's length in the WORLD frame: a ship's transform is rigid so
     * lengths are equal, and using the world length keeps every distance this class hands out
     * comparable with the field layer's, which is measured in the world frame.
     */
    private static Hit traverse(World world, Vec3d from, Vec3d to, double worldLength,
                                final String shipId) {
        final Hit[] found = new Hit[1];
        final Vec3d segFrom = from;
        final Vec3d segTo = to;
        SweptSegment.traverse(from, to, MAX_VOXELS_PER_SEGMENT, new SweptSegment.Visitor() {
            @Override
            public boolean visit(BlockPos pos, double tEnter, net.minecraft.util.EnumFacing entryFace) {
                if (!world.isBlockLoaded(pos)) {
                    return false; // nobody looked; see the class note
                }
                if (!StructureDamageEngine.isStructure(world, pos, world.getBlockState(pos))) {
                    return false;
                }
                Vec3d localPoint = segFrom.add(segTo.subtract(segFrom).scale(tEnter));
                Vec3d worldPoint = localPoint;
                if (shipId != null) {
                    double[] w = VSIntegration.toWorldFrameFor(world, shipId, localPoint.x,
                            localPoint.y, localPoint.z);
                    if (w == null) {
                        return true; // the ship stopped answering mid-traversal: stop, claim nothing
                    }
                    worldPoint = new Vec3d(w[0], w[1], w[2]);
                }
                found[0] = new Hit(tEnter * worldLength, worldPoint, pos, shipId, entryFace);
                return true;
            }
        });
        return found[0];
    }
}
