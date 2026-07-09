package zmaster587.advancedRocketry.space;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Absolute galactic position as a <b>sectorized fixed-point</b> coordinate - the exact-integer
 * absolute frame for the movable-ship space subsystem. Decoupled from (and NOT to be confused with)
 * the legacy {@code SpacePosition}, which is the {@code double} solar-map render coordinate.
 *
 * <p>Each axis is a {@code long sector} index plus a local block offset, with
 * {@code absolute = sector * CELL + local}. Integer arithmetic gives uniform precision at any
 * magnitude and does not drift when a position is integrated over a long automatic transit - unlike
 * a {@code double}, whose spacing grows with magnitude (~220&nbsp;km per ULP at galactic scale).</p>
 *
 * <p>The sector grid <i>is</i> the bubble grid: a cell is one {@link #CELL}-block cube, so two
 * positions with equal sector triples share a cell (and, once loaded, the same world). The local
 * offset is kept canonical in {@code [-HALF_CELL, HALF_CELL)}, i.e. within &plusmn;2M blocks of the
 * cell centre, so every local coordinate stays inside the range where 1.12.2 entity doubles, chunks
 * and lighting are crisp. Cell-centre content is at local {@code (0,0,0)}.</p>
 *
 * <p>Immutable value type. Proximity is computed on the (small, near) local delta cast to
 * {@code double} via {@link #distanceSqTo(GalacticCoord)} - precise because the delta between nearby
 * positions is small even though the absolute magnitudes are huge.</p>
 */
public final class GalacticCoord {

    /** Edge length of one cell / sector, in blocks. The sector grid is the bubble grid. */
    public static final long CELL = 4_000_000L;

    /** Half a cell; the canonical local offset lives in {@code [-HALF_CELL, HALF_CELL)}. */
    public static final long HALF_CELL = CELL / 2L;

    /** Absolute origin: sector {@code (0,0,0)}, local {@code (0,0,0)}. */
    public static final GalacticCoord ORIGIN = new GalacticCoord(0L, 0L, 0L, 0, 0, 0);

    private final long sectorX;
    private final long sectorY;
    private final long sectorZ;
    private final int localX; // canonical: [-HALF_CELL, HALF_CELL)
    private final int localY;
    private final int localZ;

    private GalacticCoord(long sectorX, long sectorY, long sectorZ, int localX, int localY, int localZ) {
        this.sectorX = sectorX;
        this.sectorY = sectorY;
        this.sectorZ = sectorZ;
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
    }

    /**
     * Build from a sector triple and a (possibly out-of-range) local offset triple, renormalising the
     * local offsets into {@code [-HALF_CELL, HALF_CELL)} and carrying the overflow into the sectors.
     */
    public static GalacticCoord ofSectorLocal(long sectorX, long sectorY, long sectorZ,
                                              long localX, long localY, long localZ) {
        long carryX = Math.floorDiv(localX + HALF_CELL, CELL);
        long carryY = Math.floorDiv(localY + HALF_CELL, CELL);
        long carryZ = Math.floorDiv(localZ + HALF_CELL, CELL);
        return new GalacticCoord(
                sectorX + carryX, sectorY + carryY, sectorZ + carryZ,
                (int) (localX - carryX * CELL),
                (int) (localY - carryY * CELL),
                (int) (localZ - carryZ * CELL));
    }

    /**
     * Build from an absolute block coordinate triple. Precise up to the {@code long} range of the
     * absolute value; beyond that the caller must supply sectors directly via
     * {@link #ofSectorLocal(long, long, long, long, long, long)}.
     */
    public static GalacticCoord ofAbsolute(long absX, long absY, long absZ) {
        return ofSectorLocal(0L, 0L, 0L, absX, absY, absZ);
    }

    public long sectorX() { return sectorX; }
    public long sectorY() { return sectorY; }
    public long sectorZ() { return sectorZ; }

    public int localX() { return localX; }
    public int localY() { return localY; }
    public int localZ() { return localZ; }

    /** Absolute X in blocks. May overflow {@code long} at extreme sector magnitudes (see class doc). */
    public long absoluteX() { return sectorX * CELL + localX; }
    public long absoluteY() { return sectorY * CELL + localY; }
    public long absoluteZ() { return sectorZ * CELL + localZ; }

    /** {@code true} iff {@code other} is in the same cell (equal sector triple) as this coordinate. */
    public boolean sameCell(GalacticCoord other) {
        return sectorX == other.sectorX && sectorY == other.sectorY && sectorZ == other.sectorZ;
    }

    /**
     * The centre of this coordinate's cell (local offsets zeroed), keeping the sector triple. This is
     * where precision-critical content (stations, docking) is snapped so it never carries float jitter.
     */
    public GalacticCoord cellCentre() {
        return new GalacticCoord(sectorX, sectorY, sectorZ, 0, 0, 0);
    }

    /**
     * This coordinate shifted by a local block delta, renormalised. The unit step of transit
     * integration: repeatedly adding a per-tick velocity vector never drifts (exact integer carry).
     */
    public GalacticCoord plusLocal(long dx, long dy, long dz) {
        return ofSectorLocal(sectorX, sectorY, sectorZ, localX + dx, localY + dy, localZ + dz);
    }

    /**
     * Squared absolute distance to {@code other}, in blocks&sup2;, as a {@code double}. Computed from
     * the sector delta plus the local delta so nearby positions are exact even at galactic magnitude.
     */
    public double distanceSqTo(GalacticCoord other) {
        double dx = (double) (other.sectorX - sectorX) * CELL + (other.localX - localX);
        double dy = (double) (other.sectorY - sectorY) * CELL + (other.localY - localY);
        double dz = (double) (other.sectorZ - sectorZ) * CELL + (other.localZ - localZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Absolute distance to {@code other}, in blocks. */
    public double distanceTo(GalacticCoord other) {
        return Math.sqrt(distanceSqTo(other));
    }

    /**
     * Stable key for this coordinate's cell - the sector triple. Equal iff {@link #sameCell}. Used to
     * key the on-disk cell store and to bind a pool slot to a cell.
     */
    public String cellKey() {
        return sectorX + "_" + sectorY + "_" + sectorZ;
    }

    /** Write this coordinate into {@code nbt} under the {@code "galacticCoord"} sub-tag. */
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound sub = new NBTTagCompound();
        sub.setLong("sx", sectorX);
        sub.setLong("sy", sectorY);
        sub.setLong("sz", sectorZ);
        sub.setInteger("lx", localX);
        sub.setInteger("ly", localY);
        sub.setInteger("lz", localZ);
        nbt.setTag("galacticCoord", sub);
    }

    /**
     * Read a coordinate written by {@link #writeToNBT(NBTTagCompound)}, or {@link #ORIGIN} when the
     * sub-tag is absent (mirrors the lenient default of the legacy space types).
     */
    public static GalacticCoord readFromNBT(NBTTagCompound nbt) {
        if (!nbt.hasKey("galacticCoord")) {
            return ORIGIN;
        }
        NBTTagCompound sub = nbt.getCompoundTag("galacticCoord");
        return ofSectorLocal(
                sub.getLong("sx"), sub.getLong("sy"), sub.getLong("sz"),
                sub.getInteger("lx"), sub.getInteger("ly"), sub.getInteger("lz"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GalacticCoord)) {
            return false;
        }
        GalacticCoord other = (GalacticCoord) o;
        return sectorX == other.sectorX && sectorY == other.sectorY && sectorZ == other.sectorZ
                && localX == other.localX && localY == other.localY && localZ == other.localZ;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(sectorX);
        result = 31 * result + Long.hashCode(sectorY);
        result = 31 * result + Long.hashCode(sectorZ);
        result = 31 * result + localX;
        result = 31 * result + localY;
        result = 31 * result + localZ;
        return result;
    }

    @Override
    public String toString() {
        return "GalacticCoord[sector=(" + sectorX + "," + sectorY + "," + sectorZ + "), local=("
                + localX + "," + localY + "," + localZ + ")]";
    }
}
