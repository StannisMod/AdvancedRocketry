package zmaster587.advancedRocketry.client.render.planet;

/**
 * How big a fed body is drawn in the cell sky, given how far away it is <b>and how big it is</b>.
 *
 * <p>The rule is one sentence: <b>strictly increasing in the body's angular size, clamped at both
 * ends.</b> All three parts are contract, not polish.</p>
 *
 * <p><b>Why the argument is a RATIO and not a distance.</b> Until 2026-08-16 this took a distance
 * alone, so every body at the same range drew the same disc: a moon and a gas giant beside each other
 * were indistinguishable, and the only cue a sky gave was "near" versus "far". The honest quantity is
 * the angle a body subtends, {@code r/d} — that is what makes a giant outdraw a moon at the same
 * range and what makes flying closer grow a world.</p>
 *
 * <p><b>Why the compression stays.</b> The fed range runs from a few thousand blocks (a moon in the
 * observer's own cell) to ~10<sup>9</sup> (the far side of a system's neighbourhood), and radii span
 * from a small moon to a star, so an unclamped inverse law draws the star at a fraction of a pixel and
 * the near body across the whole sky. The renderer also drops a body whose direction vector is shorter
 * than 10<sup>-6</sup>, i.e. a body vanishes exactly when it is closest — a maximum is what stops that
 * being the only cue. So the ratio replaces the distance as the thing being compressed; it does not
 * replace the compression. An unclamped angular size is <i>correct</i> and <i>unreadable</i>, and this
 * renderer chose readable once, on purpose, with the reason written down.</p>
 *
 * <p>The mapping is logarithmic because the range spans many decades. Which function it is, and the
 * four numbers below, are {@code tunable} — what is contract is that it RISES with {@code r/d} and
 * cannot leave {@code [MIN_HALF_SIZE, MAX_HALF_SIZE]}.</p>
 *
 * <p>Pure arithmetic — no GL, no client state — so the rule can be checked without a client.</p>
 */
public final class ApparentSize {

    /** Half-size (in sky units) of a body at or below {@link #FAR_RATIO}. Never zero. {@code tunable}. */
    public static final float MIN_HALF_SIZE = 1.5F;
    /** Half-size of a body at or above {@link #NEAR_RATIO}. {@code tunable}. */
    public static final float MAX_HALF_SIZE = 16.0F;

    /**
     * The angular size ({@code radius / distance}) at or above which a body is drawn at
     * {@link #MAX_HALF_SIZE} — 0.1 rad, about 11 degrees of sky.
     *
     * <p>It replaces a NEAR_BLOCKS of 2 000, which was a distance and therefore meant something
     * different for every body: 2 000 blocks is deep inside an Earth (25 512 blocks of radius on the
     * shipped chart metric) and a long way outside a small moon. {@code tunable}.</p>
     */
    public static final double NEAR_RATIO = 0.1d;
    /**
     * The angular size at or below which a body is drawn at {@link #MIN_HALF_SIZE} — 10<sup>-6</sup>
     * rad, roughly an Earth seen from a tenth of a light-hour. Below this a body is a point either
     * way, and the floor is what keeps it visible at all. {@code tunable}.
     */
    public static final double FAR_RATIO = 1.0e-6d;

    private static final double LOG_FAR = Math.log(FAR_RATIO);
    private static final double LOG_SPAN = Math.log(NEAR_RATIO) - LOG_FAR;

    private ApparentSize() {
    }

    /**
     * The half-size to draw a body of {@code radiusBlocks} seen from {@code distanceBlocks}.
     *
     * <p>A body with no radius of its own ({@code radiusBlocks <= 0} — a belt, a station slot) is not
     * a sphere and has no angular size; it takes {@link #MIN_HALF_SIZE}, the marker size, rather than
     * being guessed at. A non-finite or non-positive DISTANCE is the nearest thing there is, so it
     * takes the maximum rather than becoming invisible.</p>
     */
    public static float halfSizeFor(double radiusBlocks, double distanceBlocks) {
        if (Double.isNaN(radiusBlocks) || radiusBlocks <= 0d) {
            return MIN_HALF_SIZE;
        }
        if (Double.isNaN(distanceBlocks) || distanceBlocks <= 0d) {
            return MAX_HALF_SIZE;
        }
        double ratio = radiusBlocks / distanceBlocks;
        if (ratio >= NEAR_RATIO) {
            return MAX_HALF_SIZE;
        }
        if (ratio <= FAR_RATIO) {
            return MIN_HALF_SIZE;
        }
        double t = (Math.log(ratio) - LOG_FAR) / LOG_SPAN;
        return (float) (MIN_HALF_SIZE + (MAX_HALF_SIZE - MIN_HALF_SIZE) * t);
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
