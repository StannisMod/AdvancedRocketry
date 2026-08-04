package zmaster587.advancedRocketry.space;

/**
 * A position in absolute galactic blocks <b>at one stated moment</b>.
 *
 * <p>This is deliberately NOT a {@link GalacticCoord}. A {@code GalacticCoord} is a cell NAME plus an
 * offset inside that cell's frame, and a cell's frame moves: its origin is the position of the body
 * the cell belongs to. So "where this thing is, absolutely, at tick t" is a different kind of value
 * from "which cell it is in and where inside it", and the two must not share a type &mdash; not for
 * tidiness, but because {@link GalacticCoord#ofSectorLocal} <i>carries</i> an out-of-range offset into
 * the sector triple. Expressing a frame-displaced position as a {@code GalacticCoord} would therefore
 * silently RENAME the cell the moment the frame origin drifts more than half a cell from
 * {@code sector * CELL}, which is a routine amount of orbital travel.</p>
 *
 * <p>An absolute position is only ever an intermediate: it exists to be subtracted from another one at
 * the same tick, giving a {@link BlockDelta} &mdash; a direction and a true distance. Nothing is stored
 * as one and nothing is addressed by one: what goes on disk is always a cell name plus an in-cell
 * offset, never a value whose meaning depends on the tick it happened to be written at.</p>
 *
 * <p>Immutable value type. As with {@link GalacticCoord#absoluteX()}, the {@code long} arithmetic can
 * overflow at extreme sector magnitudes; that is the same bound the sectorized coordinate already
 * carries and is far outside any generated galaxy.</p>
 */
public final class AbsolutePos {

    /** Absolute (0,0,0) — the centre of the origin cell of a static frame. */
    public static final AbsolutePos ORIGIN = new AbsolutePos(0L, 0L, 0L);

    private final long x;
    private final long y;
    private final long z;

    private AbsolutePos(long x, long y, long z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static AbsolutePos of(long x, long y, long z) {
        return new AbsolutePos(x, y, z);
    }

    /**
     * The absolute position a cell NAME denotes under a STATIC frame: {@code sector * CELL}. This is
     * the frame origin of a void cell &mdash; one with no primary to ride, so it never moves &mdash;
     * and the fallback for any cell whose primary cannot be resolved.
     */
    public static AbsolutePos ofCellName(GalacticCoord name) {
        if (name == null) {
            return ORIGIN;
        }
        return new AbsolutePos(name.sectorX() * GalacticCoord.CELL,
                name.sectorY() * GalacticCoord.CELL,
                name.sectorZ() * GalacticCoord.CELL);
    }

    public long x() { return x; }
    public long y() { return y; }
    public long z() { return z; }

    /** This position displaced by {@code delta}. */
    public AbsolutePos plus(BlockDelta delta) {
        return delta == null ? this : new AbsolutePos(x + delta.dx(), y + delta.dy(), z + delta.dz());
    }

    /** This position displaced by a raw block triple. */
    public AbsolutePos plus(long dx, long dy, long dz) {
        return new AbsolutePos(x + dx, y + dy, z + dz);
    }

    /** The vector FROM {@code from} TO this position — the observer&rarr;body direction when
     *  {@code from} is the observer. */
    public BlockDelta minus(AbsolutePos from) {
        return from == null ? BlockDelta.of(x, y, z)
                : BlockDelta.of(x - from.x, y - from.y, z - from.z);
    }

    /** Squared distance to {@code other}, in blocks&sup2;. Both must be evaluated at the SAME tick. */
    public double distanceSqTo(AbsolutePos other) {
        if (other == null) {
            return 0.0;
        }
        double dx = (double) other.x - x;
        double dy = (double) other.y - y;
        double dz = (double) other.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Distance to {@code other}, in blocks. Both must be evaluated at the SAME tick. */
    public double distanceTo(AbsolutePos other) {
        return Math.sqrt(distanceSqTo(other));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AbsolutePos)) {
            return false;
        }
        AbsolutePos other = (AbsolutePos) o;
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(x);
        result = 31 * result + Long.hashCode(y);
        result = 31 * result + Long.hashCode(z);
        return result;
    }

    @Override
    public String toString() {
        return "AbsolutePos[" + x + "," + y + "," + z + "]";
    }
}
