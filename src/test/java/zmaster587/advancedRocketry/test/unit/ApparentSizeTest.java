package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.client.render.planet.ApparentSize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A fed body is drawn at an apparent size that RISES with the angle it subtends — its own radius over
 * the distance to it — and is CLAMPED at both ends.
 *
 * <p>Neither half is polish. Radii run from a small moon to a star and distances from a few thousand
 * blocks to ~10<sup>9</sup>, so an unclamped inverse law draws the star at a fraction of a pixel; and
 * the renderer drops a body whose direction vector is shorter than 10<sup>-6</sup>, i.e. a body
 * vanishes exactly when it is closest, which the maximum is what stops being the only cue. The
 * particular curve and the four numbers are {@code tunable} and are deliberately not pinned here.</p>
 *
 * <p>What IS pinned is the shape the sky was missing until 2026-08-16: size used to be a function of
 * distance alone, so a moon and a gas giant beside each other drew the same disc.</p>
 */
public class ApparentSizeTest {

    /** Earth-ish and Jupiter-ish, in the chart blocks the feed sends. */
    private static final double MOON_R = 6_800d;
    private static final double EARTH_R = 25_512d;
    private static final double GIANT_R = 280_000d;

    @Test
    public void sizeFallsAsTheSameBodyRecedes() {
        double[] distances = {200_000d, 1_000_000d, 10_000_000d, 100_000_000d, 1_000_000_000d};
        float previous = ApparentSize.halfSizeFor(EARTH_R, distances[0]);
        for (int i = 1; i < distances.length; i++) {
            float now = ApparentSize.halfSizeFor(EARTH_R, distances[i]);
            assertTrue("size must fall from " + distances[i - 1] + " to " + distances[i]
                    + " (" + previous + " -> " + now + ")", now < previous);
            previous = now;
        }
    }

    @Test
    public void aBiggerBodyOutdrawsASmallerOneAtTheSameRange() {
        // THE defect this file exists for: with size keyed on distance alone these three were equal.
        double range = 5_000_000d;
        float moon = ApparentSize.halfSizeFor(MOON_R, range);
        float earth = ApparentSize.halfSizeFor(EARTH_R, range);
        float giant = ApparentSize.halfSizeFor(GIANT_R, range);
        assertTrue("an Earth must outdraw a moon at the same range (" + moon + " vs " + earth + ")",
                earth > moon);
        assertTrue("a giant must outdraw an Earth at the same range (" + earth + " vs " + giant + ")",
                giant > earth);
    }

    @Test
    public void twoBodiesOfEqualRadiusAtEqualRangeAreDrawnEqual() {
        // The contract stated positively: nothing but the pair (radius, distance) may enter, so two
        // bodies that agree on both are the same size whatever else differs about them.
        assertEquals(ApparentSize.halfSizeFor(EARTH_R, 3_000_000d),
                ApparentSize.halfSizeFor(EARTH_R, 3_000_000d), 0f);
    }

    @Test
    public void onlyTheRATIOMatters() {
        // A body twice as big, twice as far, subtends the same angle — so it draws the same. This is
        // what makes the argument an angular size rather than two loosely-related numbers.
        assertEquals(ApparentSize.halfSizeFor(EARTH_R, 4_000_000d),
                ApparentSize.halfSizeFor(2d * EARTH_R, 8_000_000d), 1e-4);
        assertEquals(ApparentSize.halfSizeFor(MOON_R, 900_000d),
                ApparentSize.halfSizeFor(MOON_R / 10d, 90_000d), 1e-4);
    }

    @Test
    public void sizeIsClampedAtBothEnds() {
        assertEquals("a body on top of you does not fill the sky", ApparentSize.MAX_HALF_SIZE,
                ApparentSize.halfSizeFor(EARTH_R, 1d), 1e-6);
        assertEquals("a body at the neighbourhood bound is still drawn",
                ApparentSize.MIN_HALF_SIZE, ApparentSize.halfSizeFor(EARTH_R, 1.0e15), 1e-6);
        assertTrue("nothing is ever drawn at zero size", ApparentSize.MIN_HALF_SIZE > 0f);
    }

    @Test
    public void everyFedPairStaysInsideTheClamps() {
        for (double r : new double[] {1d, MOON_R, EARTH_R, GIANT_R, 2.8e6}) {
            for (double d = 1d; d < 1.0e10; d *= 3d) {
                float half = ApparentSize.halfSizeFor(r, d);
                assertTrue("size left the clamps at r=" + r + " d=" + d + ": " + half,
                        half >= ApparentSize.MIN_HALF_SIZE && half <= ApparentSize.MAX_HALF_SIZE);
            }
        }
    }

    @Test
    public void aBodyWithNoRadiusIsAMarkerNotAGuess() {
        // A belt or a station slot is not a sphere. It gets the marker size rather than a size
        // invented for it — the guessing that made every body the same disc in the first place.
        assertEquals(ApparentSize.MIN_HALF_SIZE, ApparentSize.halfSizeFor(0d, 100_000d), 1e-6);
        assertEquals(ApparentSize.MIN_HALF_SIZE, ApparentSize.halfSizeFor(-3d, 100_000d), 1e-6);
        assertEquals(ApparentSize.MIN_HALF_SIZE, ApparentSize.halfSizeFor(Double.NaN, 100_000d), 1e-6);
    }

    @Test
    public void aNonsenseDistanceIsTreatedAsNearRatherThanInvisible() {
        // A body whose vector could not be measured must not silently disappear from the sky.
        assertEquals(ApparentSize.MAX_HALF_SIZE, ApparentSize.halfSizeFor(EARTH_R, Double.NaN), 1e-6);
        assertEquals(ApparentSize.MAX_HALF_SIZE, ApparentSize.halfSizeFor(EARTH_R, -5d), 1e-6);
        assertEquals(ApparentSize.MAX_HALF_SIZE, ApparentSize.halfSizeFor(EARTH_R, 0d), 1e-6);
    }

    @Test
    public void aDistanceLabelIsLegibleAcrossTheWholeRange() {
        // The label has to read at a glance over six decades; "1183472901 m" does not.
        assertEquals("500 m", ApparentSize.formatDistance(500d));
        assertEquals("120 km", ApparentSize.formatDistance(120_000d));
        assertEquals("45 Mm", ApparentSize.formatDistance(45_000_000d));
        assertEquals("12 Gm", ApparentSize.formatDistance(12_000_000_000d));
    }
}
