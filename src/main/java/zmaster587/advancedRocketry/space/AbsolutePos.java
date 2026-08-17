package zmaster587.advancedRocketry.space;

/**
 * A position in absolute galactic space <b>at one stated moment</b>.
 *
 * <p>This is deliberately NOT a {@link GalacticCoord}. A {@code GalacticCoord} is a cell NAME plus an
 * offset inside that cell's frame, and a cell's frame moves: its origin is the position of the body
 * the cell belongs to. So "where this thing is, absolutely, at tick t" is a different kind of value
 * from "which cell it is in and where inside it", and the two must not share a type &mdash; not for
 * tidiness, but because {@link GalacticCoord#ofSectorLocal} <i>carries</i> an out-of-range offset into
 * the sector triple. Expressing a frame-displaced position as a {@code GalacticCoord} would therefore
 * silently RENAME the cell the moment the frame origin drifts more than half a cell from the cell's
 * own grid position, which is a routine amount of orbital travel.</p>
 *
 * <p>An absolute position is only ever an intermediate: it exists to be subtracted from another one at
 * the same tick, giving a {@link BlockDelta} &mdash; a direction and a true distance. Nothing is stored
 * as one and nothing is addressed by one: what goes on disk is always a cell name plus an in-cell
 * offset, never a value whose meaning depends on the tick it happened to be written at.</p>
 *
 * <h3>Why it is sectorised, and not three block counts</h3>
 *
 * <p>It used to hold three raw block {@code long}s. A sector index reaches 9.2&middot;10<sup>18</sup>,
 * while {@code sector * CELL} overflows a {@code long} at 2.9&middot;10<sup>11</sup> &mdash; so the
 * coordinate system could NAME positions this type could not express, over seven orders of magnitude,
 * silently and with no error. Nothing caught it because nothing had yet been placed far enough out.
 * Holding a sector triple and an in-cell offset removes the ceiling entirely: the whole addressable
 * range is expressible, and a distance is computed from the two deltas rather than from a product
 * that cannot fit.</p>
 *
 * <p>Immutable value type.</p>
 */
public final class AbsolutePos {

    /** Absolute origin: sector {@code (0,0,0)}, offset {@code (0,0,0)}. */
    public static final AbsolutePos ORIGIN = new AbsolutePos(0L, 0L, 0L, 0L, 0L, 0L);

    private final long sectorX;
    private final long sectorY;
    private final long sectorZ;
    private final long localX; // canonical: [-HALF_CELL, HALF_CELL)
    private final long localY;
    private final long localZ;

    private AbsolutePos(long sectorX, long sectorY, long sectorZ,
                        long localX, long localY, long localZ) {
        this.sectorX = sectorX;
        this.sectorY = sectorY;
        this.sectorZ = sectorZ;
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
    }

    /** Build from a sector triple and a (possibly out-of-range) offset triple, carrying the overflow. */
    public static AbsolutePos ofSectorLocal(long sectorX, long sectorY, long sectorZ,
                                            long localX, long localY, long localZ) {
        long carryX = Math.floorDiv(localX + GalacticCoord.HALF_CELL, GalacticCoord.CELL);
        long carryY = Math.floorDiv(localY + GalacticCoord.HALF_CELL, GalacticCoord.CELL);
        long carryZ = Math.floorDiv(localZ + GalacticCoord.HALF_CELL, GalacticCoord.CELL);
        return new AbsolutePos(
                sectorX + carryX, sectorY + carryY, sectorZ + carryZ,
                localX - carryX * GalacticCoord.CELL,
                localY - carryY * GalacticCoord.CELL,
                localZ - carryZ * GalacticCoord.CELL);
    }

    /**
     * Build from a raw block triple, i.e. an offset from the origin cell. Exact for anything inside
     * the range a {@code long} of blocks can hold; beyond that, state the sectors.
     */
    public static AbsolutePos of(long x, long y, long z) {
        return ofSectorLocal(0L, 0L, 0L, x, y, z);
    }

    /**
     * The absolute position a cell NAME denotes under a STATIC frame. This is the frame origin of a
     * void cell &mdash; one with no primary to ride, so it never moves &mdash; and the fallback for
     * any cell whose primary cannot be resolved.
     */
    public static AbsolutePos ofCellName(GalacticCoord name) {
        if (name == null) {
            return ORIGIN;
        }
        // The cell's own grid position, and ONLY that: a name denotes a CELL. Where something stands
        // inside that cell is carried separately, by the frame's law, and adding it here would count
        // the offset twice for every body in the game.
        return new AbsolutePos(name.sectorX(), name.sectorY(), name.sectorZ(), 0L, 0L, 0L);
    }

    public long sectorX() { return sectorX; }
    public long sectorY() { return sectorY; }
    public long sectorZ() { return sectorZ; }

    public long localX() { return localX; }
    public long localY() { return localY; }
    public long localZ() { return localZ; }

    /** This position displaced by {@code delta}. */
    public AbsolutePos plus(BlockDelta delta) {
        return delta == null ? this : plus(delta.dx(), delta.dy(), delta.dz());
    }

    /** This position displaced by a raw block triple. */
    public AbsolutePos plus(long dx, long dy, long dz) {
        return ofSectorLocal(sectorX, sectorY, sectorZ, localX + dx, localY + dy, localZ + dz);
    }

    /**
     * The vector FROM {@code from} TO this position &mdash; the observer&rarr;body direction when
     * {@code from} is the observer.
     *
     * <p>Saturates instead of wrapping, <b>and the delta says that it did</b>. A separation past a
     * {@code long} of blocks is one between things in different galaxies &mdash; roughly 244 000 light
     * years out, which the galaxy lattice reaches routinely &mdash; and there a block vector is a
     * direction rather than a distance. Two things must never happen: that it comes back as a small
     * number pointing the wrong way (which is what wrapping would do), and that a clamped vector is
     * indistinguishable from a real one (which is what silent saturation did). For a distance at any
     * magnitude use {@link #distanceTo}, which is computed from the sector delta and never clamps.</p>
     */
    public BlockDelta minus(AbsolutePos from) {
        AbsolutePos origin = (from == null) ? ORIGIN : from;
        long dSectorX = sectorX - origin.sectorX;
        long dSectorY = sectorY - origin.sectorY;
        long dSectorZ = sectorZ - origin.sectorZ;
        long dLocalX = localX - origin.localX;
        long dLocalY = localY - origin.localY;
        long dLocalZ = localZ - origin.localZ;

        boolean clamped = boundHit(dSectorX, dLocalX) != 0
                || boundHit(dSectorY, dLocalY) != 0
                || boundHit(dSectorZ, dLocalZ) != 0;
        long dx = saturatingBlocks(dSectorX, dLocalX);
        long dy = saturatingBlocks(dSectorY, dLocalY);
        long dz = saturatingBlocks(dSectorZ, dLocalZ);
        return clamped ? BlockDelta.saturated(dx, dy, dz) : BlockDelta.of(dx, dy, dz);
    }

    /** {@code sectors * CELL + local}, held at the {@code long} bounds rather than wrapping past them. */
    private static long saturatingBlocks(long sectors, long local) {
        int hit = boundHit(sectors, local);
        if (hit != 0) {
            return hit > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return sectors * GalacticCoord.CELL + local;
    }

    /**
     * Which {@code long} bound {@code sectors * CELL + local} runs into: {@code +1} past the top,
     * {@code -1} past the bottom, {@code 0} when it fits.
     *
     * <p>The ONE place the overflow is decided. The clamped value and the flag that reports it are
     * both read off this, so a delta cannot come back held at a bound while claiming to be exact.</p>
     */
    private static int boundHit(long sectors, long local) {
        if (sectors > Long.MAX_VALUE / GalacticCoord.CELL) {
            return 1;
        }
        if (sectors < Long.MIN_VALUE / GalacticCoord.CELL) {
            return -1;
        }
        long scaled = sectors * GalacticCoord.CELL;
        long sum = scaled + local;
        if (((scaled ^ sum) & (local ^ sum)) < 0L) {
            return local > 0L ? 1 : -1;
        }
        return 0;
    }

    /**
     * Squared distance to {@code other}, in blocks&sup2;. Both must be evaluated at the SAME tick.
     *
     * <p>Computed from the sector delta plus the offset delta, so nearby positions stay exact at any
     * magnitude and distant ones do not overflow on the way to being measured.</p>
     */
    public double distanceSqTo(AbsolutePos other) {
        if (other == null) {
            return 0.0;
        }
        double dx = (double) (other.sectorX - sectorX) * GalacticCoord.CELL + (other.localX - localX);
        double dy = (double) (other.sectorY - sectorY) * GalacticCoord.CELL + (other.localY - localY);
        double dz = (double) (other.sectorZ - sectorZ) * GalacticCoord.CELL + (other.localZ - localZ);
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
        return sectorX == other.sectorX && sectorY == other.sectorY && sectorZ == other.sectorZ
                && localX == other.localX && localY == other.localY && localZ == other.localZ;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(sectorX);
        result = 31 * result + Long.hashCode(sectorY);
        result = 31 * result + Long.hashCode(sectorZ);
        result = 31 * result + Long.hashCode(localX);
        result = 31 * result + Long.hashCode(localY);
        result = 31 * result + Long.hashCode(localZ);
        return result;
    }

    @Override
    public String toString() {
        return "AbsolutePos[sector=(" + sectorX + "," + sectorY + "," + sectorZ + "), offset=("
                + localX + "," + localY + "," + localZ + ")]";
    }
}
