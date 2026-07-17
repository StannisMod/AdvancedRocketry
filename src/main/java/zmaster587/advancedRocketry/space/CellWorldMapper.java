package zmaster587.advancedRocketry.space;

/**
 * The single owner of the cell&harr;slot-world POSE mapping — how an absolute {@link GalacticCoord}
 * inside a cell realizes as a world-frame position in that cell's slot world, and back.
 *
 * <p>The mapping is <b>honest-3D</b>: all three axes realize directly.</p>
 * <ul>
 *   <li>world X = local X, world Z = local Z (the cell centre sits at the world XZ origin);</li>
 *   <li>world Y = local Y + {@link GalacticCoord#HALF_CELL} + {@link #POSE_BAND_Y} — a positive
 *       offset, so the full canonical local range {@code [-HALF_CELL, HALF_CELL)} maps to
 *       {@code [POSE_BAND_Y, CELL + POSE_BAND_Y)}, always above the vanilla void-kill line. A pose
 *       at the cell centre (local 0) sits near world Y &asymp; 2M — entity/ship poses are doubles
 *       with no build-height cap (the 256 limit caps BLOCKS only).</li>
 * </ul>
 *
 * <p>BLOCK content (a crossing's paste band, station blocks) stays at ordinary block Y (0..256);
 * only entity/ship POSES use the honest range. Proximity math runs on {@link GalacticCoord}
 * directly, never on realized world doubles.</p>
 */
public final class CellWorldMapper {

    /**
     * The world-Y offset added under every realized pose so the lowest local Y stays above the
     * vanilla void-kill (and clear of the ordinary block band). {@code tunable}.
     */
    public static final long POSE_BAND_Y = 256L;

    private CellWorldMapper() { }

    /**
     * The world-frame pose position {@code [x,y,z]} realizing {@code coord} in its own cell's slot
     * world. Only meaningful for the slot world bound to {@code coord.cellKey()}.
     */
    public static double[] poseWorldOf(GalacticCoord coord) {
        return new double[]{
                coord.localX(),
                (double) coord.localY() + GalacticCoord.HALF_CELL + POSE_BAND_Y,
                coord.localZ()};
    }

    /**
     * The absolute coordinate of a world-frame pose {@code (wx,wy,wz)} inside {@code cell}'s slot
     * world — the inverse of {@link #poseWorldOf}. {@code cell} identifies the slot world's bound
     * cell (local offsets ignored); an out-of-range pose renormalises into a neighbouring sector
     * per {@link GalacticCoord#ofSectorLocal}, which is exactly the seam-crossing semantics.
     */
    public static GalacticCoord coordOfPose(GalacticCoord cell, double wx, double wy, double wz) {
        return GalacticCoord.ofSectorLocal(
                cell.sectorX(), cell.sectorY(), cell.sectorZ(),
                Math.round(wx),
                Math.round(wy) - GalacticCoord.HALF_CELL - POSE_BAND_Y,
                Math.round(wz));
    }
}
