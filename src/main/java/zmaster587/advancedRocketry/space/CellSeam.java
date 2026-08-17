package zmaster587.advancedRocketry.space;

/**
 * The cell face, read as something a ship can FLY THROUGH.
 *
 * <p>A cell's local range is finite, so a ship under sustained thrust reaches its face. Two answers
 * were possible: stop it, or carry it. This class holds the second one's arithmetic — when a pose
 * has left its cell far enough to count, and where the ship belongs in the neighbour it entered.
 * Nothing here touches Minecraft, a world or a ledger; that is {@link CellSeamController}'s work.</p>
 *
 * <h3>Why a margin exists at all</h3>
 *
 * <p>A face is a mathematical plane, and a ship loitering ON one would otherwise re-decide its cell
 * every tick, ping-ponging between two worlds and paying a full cut-and-paste each time. So the
 * crossing arms only once the pose is {@link #CARRY_MARGIN} PAST the face, and the ship is placed
 * {@link #REENTRY_DEPTH} inside the neighbour rather than on its face — with
 * {@code REENTRY_DEPTH > CARRY_MARGIN}, so coming back costs
 * {@code REENTRY_DEPTH + CARRY_MARGIN} of deliberate travel and cannot happen by drift.</p>
 *
 * <h3>Both are FRACTIONS of the cell, never absolutes</h3>
 *
 * <p>An absolute margin silently encodes an assumed speed and an assumed cell size: change either and
 * a number chosen for "about two seconds" quietly becomes two minutes or two ticks. Derived from
 * {@link GalacticCoord#HALF_CELL} they move with the cell, and the property they were chosen for —
 * a duration — survives.</p>
 */
public final class CellSeam {

    /**
     * How far past its cell's face a pose must be before the ship is carried: {@code HALF_CELL/10 000}
     * = 1 600 blocks at today's cell. Ratified 2026-08-17 in flight time, which is the unit that
     * matters: about 2 s at a 40 b/t cruise, and still 4 ticks for a craft doing 395 b/t (first cosmic
     * velocity, which the acceleration law makes reachable). Small enough that the ship is never long
     * in a place its cell does not name, large enough that no single tick of any plausible speed
     * straddles the decision.
     */
    public static final long CARRY_MARGIN = GalacticCoord.HALF_CELL / 10_000L;

    /**
     * How far inside the neighbour's opposite face the carried ship is placed: {@code HALF_CELL/1 000}
     * = 16 000 blocks, ten times {@link #CARRY_MARGIN}. Ratified 2026-08-17: coming straight back is
     * about 20 s of deliberate flight at a 40 b/t cruise, so a pilot who crosses knows he crossed.
     */
    public static final long REENTRY_DEPTH = GalacticCoord.HALF_CELL / 1_000L;

    private CellSeam() { }

    /**
     * The local offset a world-frame pose component maps to, per {@link CellWorldMapper}'s honest-3D
     * mapping. Y carries the pose band; X and Z do not.
     */
    public static long localOf(double world, boolean isY) {
        long rounded = Math.round(world);
        return isY ? rounded - GalacticCoord.HALF_CELL - CellWorldMapper.POSE_BAND_Y : rounded;
    }

    /**
     * Whether a pose has left its cell far enough to be CARRIED rather than merely reported at the
     * boundary. Strictly more than the margin past the face, on any one axis.
     *
     * <p>Deliberately not the same question as {@link CellWorldMapper#poseEscapesCell}: that one asks
     * whether the REPORT had to saturate, and it is true the moment a pose steps a single block out —
     * including the arrival paste band, which sits far below the cell's own pose range for the few
     * ticks between the paste and the settle. A carry keyed on that question would fire on every
     * arrival.</p>
     */
    public static boolean shouldCarry(double wx, double wy, double wz) {
        return beyondMargin(localOf(wx, false))
                || beyondMargin(localOf(wy, true))
                || beyondMargin(localOf(wz, false));
    }

    private static boolean beyondMargin(long local) {
        return local > GalacticCoord.HALF_CELL + CARRY_MARGIN
                || local < -GalacticCoord.HALF_CELL - CARRY_MARGIN;
    }

    /**
     * Where the ship belongs after being carried out of {@code cell} by {@code pose}: the neighbouring
     * cell it left through, with the ship set {@link #REENTRY_DEPTH} inside the face it came in by.
     *
     * <p>Only the axes that actually crossed move to the entry face. An axis that did not cross keeps
     * the position the pilot flew it to (clamped into the local range, since a pose may sit a little
     * outside without having crossed) — a ship leaving through the +X face has not consented to being
     * re-centred in Y and Z.</p>
     */
    public static GalacticCoord carriedCoord(GalacticCoord cell, double wx, double wy, double wz) {
        long lx = localOf(wx, false);
        long ly = localOf(wy, true);
        long lz = localOf(wz, false);
        return GalacticCoord.ofSectorLocal(
                cell.sectorX() + step(lx), cell.sectorY() + step(ly), cell.sectorZ() + step(lz),
                placed(lx), placed(ly), placed(lz));
    }

    /** Which neighbour an axis left through: -1, 0 or +1 cell. */
    private static long step(long local) {
        if (local > GalacticCoord.HALF_CELL + CARRY_MARGIN) {
            return 1L;
        }
        return local < -GalacticCoord.HALF_CELL - CARRY_MARGIN ? -1L : 0L;
    }

    /** The local offset inside the destination cell for one axis. */
    private static long placed(long local) {
        long crossed = step(local);
        if (crossed > 0L) {
            // Left through the +face: arrive just inside the neighbour's -face.
            return -GalacticCoord.HALF_CELL + REENTRY_DEPTH;
        }
        if (crossed < 0L) {
            return GalacticCoord.HALF_CELL - REENTRY_DEPTH;
        }
        return Math.max(-GalacticCoord.HALF_CELL, Math.min(GalacticCoord.HALF_CELL - 1L, local));
    }
}
