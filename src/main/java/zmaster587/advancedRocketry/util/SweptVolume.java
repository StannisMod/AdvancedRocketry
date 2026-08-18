package zmaster587.advancedRocketry.util;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every block a body of some WIDTH sweeps through, layer by layer, in the order it reaches them.
 *
 * <h3>Why a width at all</h3>
 * <p>A ray says a shot meets one block per step, which makes every calibre the same shot with a
 * different number on it. A swept cylinder is what makes the difference a player is being sold exist
 * INSIDE the hull rather than only at its skin: a heavy slug punches a wide hole, a needle bores a
 * narrow channel. The volume is the body's disc dragged along the segment.</p>
 *
 * <h3>Layers, not blocks</h3>
 * <p>The blocks reached within one slice of the axis form one <b>layer</b>, and a layer is handed over
 * as a set. Without that there is no coherent place to divide the body's energy: a shot that reaches
 * nine blocks either does nine times the work or picks one of them arbitrarily. Each block in a layer
 * carries its <b>share</b> — how much of the cross-section it covers — and a layer's shares sum to
 * one, so widening a body spreads what it has instead of multiplying it.</p>
 *
 * <h3>Exact along the path, quadrature across it</h3>
 * <p>Along the axis this is the same exact traversal a ray gets ({@link SweptSegment}) — there is no
 * step size and therefore no speed at which a wall becomes transparent. ACROSS the axis a cell's
 * share is measured by testing a fixed set of points in it, which is a different axis and a different
 * claim: the quadrature decides how much of a block is covered, never whether the path reached it.</p>
 *
 * <h3>A narrow body is a ray, exactly</h3>
 * <p>Below half a block the volume degenerates to the segment itself, one block per layer at a share
 * of one. That is not an approximation of the cylinder — it is the statement that a body thinner than
 * a voxel has nothing to spread, and it keeps the reference body (radius 0.25) behaving precisely as
 * it did before there was a width.</p>
 *
 * <h3>Pure</h3>
 * <p>Nothing here touches a world. It is geometry, and the property that matters — that a wide fast
 * body does not skip what it passes through — is a property of the traversal.</p>
 */
public final class SweptVolume {

    /** Below this radius a body is a ray: it cannot straddle enough of a voxel to share anything. */
    public static final double MIN_WIDE_RADIUS = 0.5D;

    /** Half the diagonal of a unit cell — how far a cell's centre can be from a point still inside it. */
    private static final double CELL_HALF_DIAGONAL = 0.8660254037844386D;

    /** One layer of the sweep: the blocks reached in one slice of the axis, and their shares. */
    public static final class Layer {

        /** The parameter in {@code [0,1]} along {@code from -> to} at which this layer is reached. */
        public final double tEnter;
        /**
         * The voxel the AXIS passed through in this slice — the one a ray would have found alone.
         * It is the layer's identity and is always stated, whether or not it appears in
         * {@link #blocks}: a slice whose centre block was already contacted earlier in the sweep
         * still happened, and a caller asking where the centre went deserves an answer rather than
         * whichever side block happened to be listed first.
         */
        public final BlockPos axis;
        /**
         * What this layer CONTRIBUTES: the blocks reached here and not already reached earlier in the
         * sweep, the axis one first when it is among them. Never empty — a slice with nothing new to
         * offer is not reported at all.
         */
        public final List<BlockPos> blocks;
        /** How much of the cross-section each block covers, parallel to {@link #blocks}, summing to 1. */
        public final List<Double> shares;
        /**
         * The face the AXIS came in through, as an outward normal — the surface normal anything
         * answering a contact needs. Null for the layer the body starts in: nothing was crossed.
         */
        public final EnumFacing entryFace;

        Layer(double tEnter, BlockPos axis, List<BlockPos> blocks, List<Double> shares,
              EnumFacing entryFace) {
            this.tEnter = tEnter;
            this.axis = axis;
            this.blocks = blocks;
            this.shares = shares;
            this.entryFace = entryFace;
        }

        public int size() {
            return blocks.size();
        }
    }

    /** Told about each layer the body reaches. */
    public interface LayerVisitor {
        /** @return true to stop the traversal here */
        boolean visit(Layer layer);
    }

    private SweptVolume() {
    }

    /**
     * How many voxels one slice of a body this wide may examine — the declared bound on the work a
     * width costs, stated where the width is understood rather than guessed at by each caller.
     *
     * <p>It is the cube of the Chebyshev neighbourhood the sweep considers, which over-counts on
     * purpose: most of those candidates are rejected on distance before anything reads a block. A
     * caller that knows how many slices its path has multiplies by this and gets a bound it can hold
     * itself to.</p>
     */
    public static int candidatesPerSlice(double radius) {
        if (radius < MIN_WIDE_RADIUS) {
            return 1;
        }
        int span = 2 * (int) Math.ceil(radius) + 1;
        return span * span * span;
    }

    /**
     * Walk the layers of the body of {@code radius} swept along {@code from -> to}, examining at most
     * {@code maxVoxels} voxels in total.
     *
     * <p>The cap bounds work, not distance: it counts every voxel LOOKED AT, side ones included, so a
     * wide body exhausts it sooner than a narrow one does over the same travel. It answers how many
     * were examined, so a caller that must not silently under-test can tell it ran out.</p>
     */
    public static int traverse(Vec3d from, Vec3d to, double radius, int maxVoxels,
                               final LayerVisitor visitor) {
        if (from == null || to == null || visitor == null || maxVoxels <= 0) {
            return 0;
        }
        if (radius < MIN_WIDE_RADIUS) {
            return traverseAsRay(from, to, maxVoxels, visitor);
        }
        return traverseWide(from, to, radius, maxVoxels, visitor);
    }

    /** The degenerate case, and deliberately the SAME traversal a ray gets rather than a copy of it. */
    private static int traverseAsRay(Vec3d from, Vec3d to, int maxVoxels, final LayerVisitor visitor) {
        return SweptSegment.traverse(from, to, maxVoxels, new SweptSegment.Visitor() {
            @Override
            public boolean visit(BlockPos pos, double tEnter, EnumFacing entryFace) {
                List<BlockPos> blocks = new ArrayList<BlockPos>(1);
                blocks.add(pos);
                List<Double> shares = new ArrayList<Double>(1);
                shares.add(1.0D);
                return visitor.visit(new Layer(tEnter, pos, blocks, shares, entryFace));
            }
        });
    }

    private static int traverseWide(final Vec3d from, final Vec3d to, final double radius,
                                    final int maxVoxels, final LayerVisitor visitor) {
        final Vec3d axis = to.subtract(from);
        final double axisLength = axis.lengthVector();
        if (axisLength <= 1.0E-9D) {
            return 0;
        }
        final int reach = (int) Math.ceil(radius);
        // A cell whose centre is further from the axis than the body's radius plus half a cell's
        // diagonal cannot contain a point of the cylinder at all — that is the tight bound, and it is
        // what keeps a wide body at about (2r+1) columns rather than a cube of candidates.
        final double centreBound = radius + CELL_HALF_DIAGONAL;
        final Set<Long> alreadyContacted = new HashSet<Long>();
        final int[] examined = new int[1];
        final boolean[] exhausted = new boolean[1];

        SweptSegment.traverse(from, to, maxVoxels, new SweptSegment.Visitor() {
            @Override
            public boolean visit(BlockPos axisPos, double tEnter, EnumFacing entryFace) {
                List<BlockPos> blocks = new ArrayList<BlockPos>();
                List<Double> weights = new ArrayList<Double>();

                for (int dx = -reach; dx <= reach && !exhausted[0]; dx++) {
                    for (int dy = -reach; dy <= reach && !exhausted[0]; dy++) {
                        for (int dz = -reach; dz <= reach; dz++) {
                            if (examined[0] >= maxVoxels) {
                                exhausted[0] = true;
                                break;
                            }
                            examined[0]++;
                            BlockPos cell = axisPos.add(dx, dy, dz);
                            if (distanceToAxis(cell.getX() + 0.5D, cell.getY() + 0.5D,
                                    cell.getZ() + 0.5D, from, axis, axisLength) > centreBound) {
                                continue;
                            }
                            double weight = coverage(cell, from, axis, axisLength, radius);
                            if (weight <= 0.0D) {
                                continue;
                            }
                            // A block is contacted once per sweep. Consecutive slices overlap by
                            // construction, and offering the same block twice would charge a body
                            // twice for standing still against it.
                            if (!alreadyContacted.add(key(cell))) {
                                continue;
                            }
                            // The axis block leads: it is the one a ray would have found, and a caller
                            // that only cares where the centre went should not have to search for it.
                            if (dx == 0 && dy == 0 && dz == 0) {
                                blocks.add(0, cell);
                                weights.add(0, weight);
                            } else {
                                blocks.add(cell);
                                weights.add(weight);
                            }
                        }
                    }
                }
                if (blocks.isEmpty()) {
                    // Every block of this slice was already answered in an earlier one. There is
                    // nothing to divide and nothing to say; the sweep goes on.
                    return exhausted[0];
                }
                double total = 0.0D;
                for (Double weight : weights) {
                    total += weight;
                }
                List<Double> shares = new ArrayList<Double>(weights.size());
                for (Double weight : weights) {
                    shares.add(weight / total);
                }
                return visitor.visit(new Layer(tEnter, axisPos, blocks, shares, entryFace))
                        || exhausted[0];
            }
        });
        return examined[0];
    }

    /**
     * How much of this cell the body covers, as a count of test points inside the cylinder: the cell's
     * centre and its eight corners. Bounded, monotone in the real overlap, and zero exactly when the
     * body misses the cell — which is the only part of it a contact decision rests on.
     */
    private static double coverage(BlockPos cell, Vec3d from, Vec3d axis, double axisLength,
                                   double radius) {
        int inside = 0;
        for (int cx = 0; cx <= 1; cx++) {
            for (int cy = 0; cy <= 1; cy++) {
                for (int cz = 0; cz <= 1; cz++) {
                    if (distanceToAxis(cell.getX() + cx, cell.getY() + cy, cell.getZ() + cz,
                            from, axis, axisLength) <= radius) {
                        inside++;
                    }
                }
            }
        }
        if (distanceToAxis(cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D,
                from, axis, axisLength) <= radius) {
            inside++;
        }
        return inside;
    }

    /**
     * Distance from a point to the axis LINE, not to the segment: the cylinder's caps are the ends of
     * the traversal, which the axis walk already decides, so clamping here would cut the body's own
     * width off at the first and last slice.
     */
    private static double distanceToAxis(double px, double py, double pz, Vec3d from, Vec3d axis,
                                         double axisLength) {
        double rx = px - from.x;
        double ry = py - from.y;
        double rz = pz - from.z;
        double along = (rx * axis.x + ry * axis.y + rz * axis.z) / (axisLength * axisLength);
        double cx = rx - axis.x * along;
        double cy = ry - axis.y * along;
        double cz = rz - axis.z * along;
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** One long per block position — a set key that does not allocate an object per candidate. */
    private static long key(BlockPos pos) {
        return ((long) (pos.getX() & 0x3FFFFF) << 42)
                | ((long) (pos.getY() & 0xFFFFF) << 22)
                | (long) (pos.getZ() & 0x3FFFFF);
    }
}
