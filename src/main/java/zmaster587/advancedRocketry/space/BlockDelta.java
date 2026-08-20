package zmaster587.advancedRocketry.space;

/**
 * A displacement in blocks: the difference between two {@link AbsolutePos} taken at the same tick, or
 * an offset inside a cell's frame.
 *
 * <p>A delta has no cell and no name, which is the point &mdash; it is what survives the subtraction
 * of two positions whose frames were moving. The render channel carries one per body (the
 * observer&rarr;body vector), and its length is the true distance at that moment.</p>
 *
 * <h3>A separation can be larger than this type can hold, and it SAYS SO</h3>
 *
 * <p>Three block {@code long}s reach about 244 000 light years, which is a quarter of the way to the
 * nearest galaxy: the universe NAMES separations this type cannot express, and it always did &mdash;
 * a cell name is a sector triple, so the addressable range is orders wider than a block count. What
 * changed is that such a separation is now reachable in play rather than hypothetical.</p>
 *
 * <p>So a delta carries {@link #isSaturated()}. A saturated delta holds each over-range component at
 * the {@code long} bound &mdash; the direction survives, which is what a renderer and a nav computer
 * actually read &mdash; and its {@link #length()} is a LOWER BOUND on the true distance. What must
 * never happen, and is what the flag exists to prevent, is a consumer measuring a clamped vector and
 * reporting the number as a distance: for that, ask the two {@link AbsolutePos} for
 * {@link AbsolutePos#distanceTo}, which is computed from the sector delta and does not clamp.</p>
 *
 * <p>Immutable value type.</p>
 */
public final class BlockDelta {

    public static final BlockDelta ZERO = new BlockDelta(0L, 0L, 0L, false);

    private final long dx;
    private final long dy;
    private final long dz;
    private final boolean saturated;

    private BlockDelta(long dx, long dy, long dz, boolean saturated) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.saturated = saturated;
    }

    /** An EXACT displacement: these three numbers are the whole separation. */
    public static BlockDelta of(long dx, long dy, long dz) {
        return (dx == 0L && dy == 0L && dz == 0L) ? ZERO : new BlockDelta(dx, dy, dz, false);
    }

    /**
     * A displacement that ran into the {@code long} bound on at least one axis: the components are
     * held at the bound and the value reports itself {@link #isSaturated()}.
     *
     * <p>Named rather than a flag on {@link #of}, because which of the two a caller is producing is
     * something it KNOWS &mdash; and a boolean at the call site would let it be got wrong silently,
     * which is the whole defect this pair exists to close.</p>
     */
    public static BlockDelta saturated(long dx, long dy, long dz) {
        return new BlockDelta(dx, dy, dz, true);
    }

    public long dx() { return dx; }
    public long dy() { return dy; }
    public long dz() { return dz; }

    /**
     * {@code true} when at least one component was held at the {@code long} bound, so the components
     * are a direction and {@link #length()} is a lower bound rather than a distance.
     */
    public boolean isSaturated() {
        return saturated;
    }

    /**
     * The two displacements added. Saturation is CARRIED: a sum involving a clamped vector is itself
     * only a lower bound, and losing the flag here would launder one back into an exact answer.
     */
    public BlockDelta plus(BlockDelta other) {
        if (other == null) {
            return this;
        }
        long sx = dx + other.dx;
        long sy = dy + other.dy;
        long sz = dz + other.dz;
        return (saturated || other.saturated) ? saturated(sx, sy, sz) : of(sx, sy, sz);
    }

    /** Length in blocks &mdash; a LOWER BOUND when {@link #isSaturated()}. */
    public double length() {
        double x = dx;
        double y = dy;
        double z = dz;
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** {@code true} iff this is the zero displacement. */
    public boolean isZero() {
        return dx == 0L && dy == 0L && dz == 0L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockDelta)) {
            return false;
        }
        BlockDelta other = (BlockDelta) o;
        return dx == other.dx && dy == other.dy && dz == other.dz
                && saturated == other.saturated;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(dx);
        result = 31 * result + Long.hashCode(dy);
        result = 31 * result + Long.hashCode(dz);
        result = 31 * result + (saturated ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "BlockDelta[" + dx + "," + dy + "," + dz + (saturated ? ",saturated]" : "]");
    }
}
