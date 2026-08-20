package zmaster587.advancedRocketry.universe;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * The patch of sky one pointing covers: a CONE with its apex at the instrument, a direction and a
 * half-angle — enumerated as an indexed list of look points so a survey can be walked, paused and
 * resumed.
 *
 * <p><b>Why a cone and not a box.</b> A box of coordinates has no observer: it has two corners and
 * no idea where it is being looked at from. Everything awkward about surveying one — what a stride
 * means, why a distant region is a different shape of work from a near one — comes from a shape that
 * does not know where its viewer stands. A cone has an apex, so "how far along the sight line" and
 * "how far off axis" are different questions with different answers, which is what an instrument
 * actually distinguishes.</p>
 *
 * <p><b>The walk is shell by shell.</b> Look points sit on a lattice of {@code stride} cells: one
 * step along the axis per shell, and inside each shell a disc of the same spacing whose radius grows
 * as {@code s·stride·tan(halfAngle)}. So a pointing is narrow near the instrument and wide far away,
 * which is the whole geometric content of "a patch of sky" — the same angular patch subtends more
 * space the farther out you read it.</p>
 *
 * <p><b>Indexed, not iterated.</b> {@link #lookAt(int)} answers any index without walking the ones
 * before it, because a survey outlives the chunk it started in: it stores how many looks it has done
 * and resumes there. The per-shell counts are exact — a disc is counted as a disc and not as the
 * square around it — so a survey's progress describes the sky it covers rather than the bookkeeping
 * around it.</p>
 *
 * <p>Immutable. The NBT shape is a same-version save contract: a pointing outlives its chunk.</p>
 */
public final class ConeWalk {

    private static final String KEY_APEX = "apex";
    private static final String KEY_DIR_X = "dx";
    private static final String KEY_DIR_Y = "dy";
    private static final String KEY_DIR_Z = "dz";
    private static final String KEY_HALF_ANGLE = "halfAngle";
    private static final String KEY_REACH = "reachCells";
    private static final String KEY_STRIDE = "stride";

    /**
     * The most shells one pointing may hold.
     *
     * <p>A REPRESENTATION bound and not a balance one: every shell owns an entry in the prefix table
     * this class builds at construction, so the table is what is being bounded. A million shells is
     * already 4 MB of index for a survey whose look count passed what an {@code int} cursor can carry
     * long before — the two limits are refused together, and the message says which.</p>
     */
    private static final int MAX_SHELLS = 1_000_000;

    private final GalacticCoord apex;
    private final double dirX;
    private final double dirY;
    private final double dirZ;
    private final double halfAngleRadians;
    private final long reachCells;
    private final long strideCells;

    /** The disc basis: two unit vectors across the axis, so a shell is enumerated in its own plane. */
    private final double uX;
    private final double uY;
    private final double uZ;
    private final double vX;
    private final double vY;
    private final double vZ;

    /** {@code shellStart[s]} is the index of shell {@code s}'s first look; the last entry is the total. */
    private final int[] shellStart;

    private ConeWalk(GalacticCoord apex, double dirX, double dirY, double dirZ,
                     double halfAngleRadians, long reachCells, long strideCells) {
        this.apex = apex;
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        this.dirX = dirX / length;
        this.dirY = dirY / length;
        this.dirZ = dirZ / length;
        this.halfAngleRadians = halfAngleRadians;
        this.reachCells = Math.max(0L, reachCells);
        this.strideCells = Math.max(1L, strideCells);

        // A vector not parallel to the axis, chosen by which component of the axis is SMALLEST: any
        // fixed helper is parallel to some axis, and the cross product with it degenerates exactly
        // there. Picking the smallest component guarantees at least a 1/sqrt(3) separation.
        double hx = 0d;
        double hy = 0d;
        double hz = 0d;
        double ax = Math.abs(this.dirX);
        double ay = Math.abs(this.dirY);
        double az = Math.abs(this.dirZ);
        if (ax <= ay && ax <= az) {
            hx = 1d;
        } else if (ay <= az) {
            hy = 1d;
        } else {
            hz = 1d;
        }
        double cx = this.dirY * hz - this.dirZ * hy;
        double cy = this.dirZ * hx - this.dirX * hz;
        double cz = this.dirX * hy - this.dirY * hx;
        double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
        this.uX = cx / cl;
        this.uY = cy / cl;
        this.uZ = cz / cl;
        this.vX = this.dirY * uZ - this.dirZ * uY;
        this.vY = this.dirZ * uX - this.dirX * uZ;
        this.vZ = this.dirX * uY - this.dirY * uX;

        this.shellStart = buildShells();
    }

    /**
     * Aim a pointing from {@code apex} along {@code (dirX, dirY, dirZ)}.
     *
     * @param halfAngleRadians how wide the patch of sky is, from the axis to the edge
     * @param reachCells       how far the pointing carries — derived from what the instrument can
     *                         SEE (see {@link StellarMagnitude#instrumentReachLightYears}), never a
     *                         horizon of its own
     * @param strideCells      the spacing of the look lattice — one star's territory
     * @throws IllegalArgumentException when there is no apex, no direction, or the pointing holds
     *                                  more looks than a survey cursor can index
     */
    public static ConeWalk aimed(GalacticCoord apex, double dirX, double dirY, double dirZ,
                                 double halfAngleRadians, long reachCells, long strideCells) {
        if (apex == null) {
            throw new IllegalArgumentException("a pointing needs an instrument to be aimed from");
        }
        if (dirX * dirX + dirY * dirY + dirZ * dirZ <= 0d) {
            throw new IllegalArgumentException("a pointing with no direction does not name a patch of sky");
        }
        // Clamped rather than refused: an operator who asks for a hemisphere gets the widest patch the
        // geometry can mean, and one who asks for zero gets the single sight line, which is a pointing
        // with no width and still a pointing.
        double half = Math.max(0d, Math.min(Math.PI / 2d - 1e-6d, halfAngleRadians));
        return new ConeWalk(apex.cellCentre(), dirX, dirY, dirZ, half, reachCells, strideCells);
    }

    /**
     * The prefix table of shell starts — and the place a pointing too large to walk is REFUSED.
     *
     * <p>Refused and never clamped, for the reason a region survey already states: a walk cursor is an
     * {@code int}, so a pointing with more looks than one can index would report itself complete with
     * most of the sky untouched, and progress would read 100 % over a survey that never happened.
     * Silence is the one outcome worse than a slow survey.</p>
     */
    private int[] buildShells() {
        long shells = reachCells / strideCells;
        if (shells > MAX_SHELLS) {
            throw new IllegalArgumentException("a pointing of " + shells + " shells cannot be walked"
                    + " (at most " + MAX_SHELLS + "): the instrument reaches " + reachCells
                    + " cells at a stride of " + strideCells + ". Lower the limiting magnitude.");
        }
        int n = (int) Math.max(0L, shells);
        int[] start = new int[n + 1];
        long total = 0L;
        for (int s = 1; s <= n; s++) {
            start[s - 1] = (int) total;
            total += discLooks(radiusOfShell(s));
            if (total > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("a pointing of half-angle "
                        + String.format("%.3f", Math.toDegrees(halfAngleRadians)) + " degrees over "
                        + reachCells + " cells holds more looks than a survey can index."
                        + " Narrow the aperture or lower the limiting magnitude.");
            }
        }
        start[n] = (int) total;
        return start;
    }

    /** The disc radius of shell {@code s}, in STRIDES — {@code s·tan(halfAngle)}, floored to the lattice. */
    private int radiusOfShell(int s) {
        double radius = s * Math.tan(halfAngleRadians);
        return (int) Math.max(0d, Math.min(Integer.MAX_VALUE, Math.floor(radius)));
    }

    /** How many lattice points a disc of {@code radius} strides holds — counted row by row, exactly. */
    private static long discLooks(int radius) {
        long count = 0L;
        for (int i = -radius; i <= radius; i++) {
            count += 2L * rowHalfWidth(radius, i) + 1L;
        }
        return count;
    }

    /** Half the width of the disc's row at offset {@code i} — {@code floor(sqrt(r² − i²))}. */
    private static int rowHalfWidth(int radius, int i) {
        long r2 = (long) radius * radius - (long) i * i;
        return r2 <= 0L ? 0 : (int) Math.sqrt((double) r2);
    }

    /** How many looks the whole pointing holds. Never a clamped count standing in for a real one. */
    public int totalLooks() {
        return shellStart[shellStart.length - 1];
    }

    /** How many shells deep the pointing goes — one step of {@link #strideCells()} each. */
    public int shells() {
        return shellStart.length - 1;
    }

    public GalacticCoord apex() {
        return apex;
    }

    public double halfAngleRadians() {
        return halfAngleRadians;
    }

    /** How far the pointing carries, in cells — the instrument's reach, not a configured horizon. */
    public long reachCells() {
        return reachCells;
    }

    public long strideCells() {
        return strideCells;
    }

    /** The unit direction the instrument is aimed along. */
    public double dirX() {
        return dirX;
    }

    public double dirY() {
        return dirY;
    }

    public double dirZ() {
        return dirZ;
    }

    /**
     * The cell the look at {@code index} lands on, in the pointing's own order: shell by shell
     * outwards, and inside a shell row by row across the disc.
     *
     * <p>Outwards first is not cosmetic. A survey resolves its looks in this order and may be aborted
     * at any point, so what a half-finished pointing has covered is a SHORTER cone rather than a
     * scatter — the operator has surveyed the near sky and not a random sample of the far.</p>
     */
    public GalacticCoord lookAt(int index) {
        int shell = shellFor(index);
        int radius = radiusOfShell(shell);
        int offset = index - shellStart[shell - 1];
        // Walk the disc's rows to place the offset. At most 2r+1 steps, against a look that costs a
        // lattice draw — the cost is in what the look RESOLVES, never in finding where it points.
        int i = -radius;
        while (i <= radius) {
            int width = 2 * rowHalfWidth(radius, i) + 1;
            if (offset < width) {
                break;
            }
            offset -= width;
            i++;
        }
        int j = offset - rowHalfWidth(radius, i);

        double axial = (double) shell * strideCells;
        double across = (double) i * strideCells;
        double along = (double) j * strideCells;
        return GalacticCoord.ofSectorLocal(
                apex.sectorX() + Math.round(dirX * axial + uX * across + vX * along),
                apex.sectorY() + Math.round(dirY * axial + uY * across + vY * along),
                apex.sectorZ() + Math.round(dirZ * axial + uZ * across + vZ * along),
                0L, 0L, 0L);
    }

    /** Which shell an index falls in, by binary search over the prefix table. Shells are 1-based. */
    private int shellFor(int index) {
        if (index < 0 || index >= totalLooks()) {
            throw new IndexOutOfBoundsException("look " + index + " of " + totalLooks());
        }
        int lo = 1;
        int hi = shells();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (shellStart[mid - 1] <= index) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** How far out shell {@code shell} stands, in cells — what a look at that depth costs to resolve. */
    public long axialCellsOfShell(int shell) {
        return (long) shell * strideCells;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound at = new NBTTagCompound();
        apex.writeToNBT(at);
        nbt.setTag(KEY_APEX, at);
        nbt.setDouble(KEY_DIR_X, dirX);
        nbt.setDouble(KEY_DIR_Y, dirY);
        nbt.setDouble(KEY_DIR_Z, dirZ);
        nbt.setDouble(KEY_HALF_ANGLE, halfAngleRadians);
        nbt.setLong(KEY_REACH, reachCells);
        nbt.setLong(KEY_STRIDE, strideCells);
    }

    /** The pointing stored in {@code nbt}, or {@code null} when nothing was stored. */
    public static ConeWalk readFromNBT(NBTTagCompound nbt) {
        if (nbt == null || !nbt.hasKey(KEY_APEX)) {
            return null;
        }
        return new ConeWalk(GalacticCoord.readFromNBT(nbt.getCompoundTag(KEY_APEX)),
                nbt.getDouble(KEY_DIR_X), nbt.getDouble(KEY_DIR_Y), nbt.getDouble(KEY_DIR_Z),
                nbt.getDouble(KEY_HALF_ANGLE), nbt.getLong(KEY_REACH), nbt.getLong(KEY_STRIDE));
    }

    @Override
    public String toString() {
        return "ConeWalk[" + apex.cellKey() + " -> (" + String.format("%.3f", dirX) + ", "
                + String.format("%.3f", dirY) + ", " + String.format("%.3f", dirZ) + "), "
                + String.format("%.3f", Math.toDegrees(halfAngleRadians)) + " deg, "
                + shells() + " shells, " + totalLooks() + " looks]";
    }
}
