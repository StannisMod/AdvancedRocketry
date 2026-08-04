package zmaster587.advancedRocketry.client.render.planet;

/**
 * How big a fed body is drawn in the cell sky, given how far away it is.
 *
 * <p>The rule is one sentence: <b>strictly decreasing in distance, clamped at both ends.</b> Both
 * halves are contract, not polish. The fed range runs from a few thousand blocks (a moon in the
 * observer's own cell) to ~10<sup>9</sup> (the far side of a system's neighbourhood), so an unclamped
 * inverse law draws the star at a fraction of a pixel and the near body across the whole sky. And the
 * renderer already drops a body whose direction vector is shorter than 10<sup>-6</sup>, i.e. a body
 * vanishes exactly when it is closest — a maximum is what stops that being the only cue.</p>
 *
 * <p>The mapping is logarithmic because the range spans six decades: a linear one would put every
 * body in a system at the minimum and leave the whole scale to be spent inside one cell. Which
 * function it is, and the four numbers below, are {@code tunable} — what is contract is that it falls
 * with distance and cannot leave {@code [MIN_HALF_SIZE, MAX_HALF_SIZE]}.</p>
 *
 * <p>Pure arithmetic — no GL, no client state — so the rule can be checked without a client.</p>
 */
public final class ApparentSize {

    /** Half-size (in sky units) of a body at or beyond {@link #FAR_BLOCKS}. Never zero. {@code tunable}. */
    public static final float MIN_HALF_SIZE = 1.5F;
    /** Half-size of a body at or inside {@link #NEAR_BLOCKS}. {@code tunable}. */
    public static final float MAX_HALF_SIZE = 16.0F;
    /** At or below this distance a body is drawn at {@link #MAX_HALF_SIZE}. {@code tunable}. */
    public static final double NEAR_BLOCKS = 2_000d;
    /** At or beyond this distance a body is drawn at {@link #MIN_HALF_SIZE}. {@code tunable}. */
    public static final double FAR_BLOCKS = 1.0e9;

    private static final double LOG_NEAR = Math.log(NEAR_BLOCKS);
    private static final double LOG_SPAN = Math.log(FAR_BLOCKS) - LOG_NEAR;

    private ApparentSize() {
    }

    /**
     * The half-size to draw a body at {@code distanceBlocks}. A non-finite or non-positive distance
     * is the nearest thing there is, so it takes the maximum rather than becoming invisible.
     */
    public static float halfSizeFor(double distanceBlocks) {
        if (Double.isNaN(distanceBlocks) || distanceBlocks <= NEAR_BLOCKS) {
            return MAX_HALF_SIZE;
        }
        if (distanceBlocks >= FAR_BLOCKS) {
            return MIN_HALF_SIZE;
        }
        double t = (Math.log(distanceBlocks) - LOG_NEAR) / LOG_SPAN;
        return (float) (MAX_HALF_SIZE + (MIN_HALF_SIZE - MAX_HALF_SIZE) * t);
    }

    /**
     * A distance rendered the way a pilot reads it: whole blocks under 10 km, then km, then Mm, then
     * Gm. The label has to be legible at a glance across six decades, and "1183472901 m" is not.
     */
    public static String formatDistance(double distanceBlocks) {
        double d = Math.max(0d, distanceBlocks);
        if (d < 10_000d) {
            return Math.round(d) + " m";
        }
        if (d < 10_000_000d) {
            return Math.round(d / 1_000d) + " km";
        }
        if (d < 10_000_000_000d) {
            return Math.round(d / 1_000_000d) + " Mm";
        }
        return Math.round(d / 1_000_000_000d) + " Gm";
    }
}
