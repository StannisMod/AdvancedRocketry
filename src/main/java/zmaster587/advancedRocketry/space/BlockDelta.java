package zmaster587.advancedRocketry.space;

/**
 * A displacement in blocks: the difference between two {@link AbsolutePos} taken at the same tick, or
 * an offset inside a cell's frame.
 *
 * <p>A delta has no cell and no name, which is the point &mdash; it is what survives the subtraction
 * of two positions whose frames were moving. The render channel carries one per body (the
 * observer&rarr;body vector), and its length is the true distance at that moment.</p>
 *
 * <p>Immutable value type.</p>
 */
public final class BlockDelta {

    public static final BlockDelta ZERO = new BlockDelta(0L, 0L, 0L);

    private final long dx;
    private final long dy;
    private final long dz;

    private BlockDelta(long dx, long dy, long dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public static BlockDelta of(long dx, long dy, long dz) {
        return (dx == 0L && dy == 0L && dz == 0L) ? ZERO : new BlockDelta(dx, dy, dz);
    }

    public long dx() { return dx; }
    public long dy() { return dy; }
    public long dz() { return dz; }

    public BlockDelta plus(BlockDelta other) {
        return other == null ? this : of(dx + other.dx, dy + other.dy, dz + other.dz);
    }

    /** Length in blocks. */
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
        return dx == other.dx && dy == other.dy && dz == other.dz;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(dx);
        result = 31 * result + Long.hashCode(dy);
        result = 31 * result + Long.hashCode(dz);
        return result;
    }

    @Override
    public String toString() {
        return "BlockDelta[" + dx + "," + dy + "," + dz + "]";
    }
}
