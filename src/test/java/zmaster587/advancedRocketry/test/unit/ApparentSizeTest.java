package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.client.render.planet.ApparentSize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * C14 CON-C14-16: a fed body is drawn at a size that FALLS with distance and is CLAMPED at both ends.
 *
 * <p>Neither half is polish. The fed range runs from a few thousand blocks to ~10<sup>9</sup>, so an
 * unclamped inverse law draws the star at a fraction of a pixel; and the renderer drops a body whose
 * direction vector is shorter than 10<sup>-6</sup>, i.e. a body vanishes exactly when it is closest,
 * which the maximum is what stops being the only cue. The particular curve and the four numbers are
 * {@code tunable} and are deliberately not pinned here.</p>
 */
public class ApparentSizeTest {

    @Test
    public void sizeFallsAsDistanceGrows() {
        double[] distances = {2_000d, 10_000d, 100_000d, 1_000_000d, 10_000_000d, 100_000_000d};
        float previous = ApparentSize.halfSizeFor(distances[0]);
        for (int i = 1; i < distances.length; i++) {
            float now = ApparentSize.halfSizeFor(distances[i]);
            assertTrue("size must fall from " + distances[i - 1] + " to " + distances[i]
                    + " (" + previous + " -> " + now + ")", now < previous);
            previous = now;
        }
    }

    @Test
    public void sizeIsClampedAtBothEnds() {
        assertEquals("a body on top of you does not fill the sky", ApparentSize.MAX_HALF_SIZE,
                ApparentSize.halfSizeFor(0d), 1e-6);
        assertEquals(ApparentSize.MAX_HALF_SIZE, ApparentSize.halfSizeFor(1d), 1e-6);
        assertEquals("a body at the neighbourhood bound is still drawn",
                ApparentSize.MIN_HALF_SIZE, ApparentSize.halfSizeFor(1.0e12), 1e-6);
        assertTrue("nothing is ever drawn at zero size", ApparentSize.MIN_HALF_SIZE > 0f);
    }

    @Test
    public void everyDistanceInTheFedRangeStaysInsideTheClamps() {
        // The fed range: a moon in the observer's own cell out to the far side of a neighbourhood.
        for (double d = 1d; d < 1.0e10; d *= 3d) {
            float half = ApparentSize.halfSizeFor(d);
            assertTrue("size left the clamps at " + d + ": " + half,
                    half >= ApparentSize.MIN_HALF_SIZE && half <= ApparentSize.MAX_HALF_SIZE);
        }
    }

    @Test
    public void aNonsenseDistanceIsTreatedAsNearRatherThanInvisible() {
        // A body whose vector could not be measured must not silently disappear from the sky.
        assertEquals(ApparentSize.MAX_HALF_SIZE, ApparentSize.halfSizeFor(Double.NaN), 1e-6);
        assertEquals(ApparentSize.MAX_HALF_SIZE, ApparentSize.halfSizeFor(-5d), 1e-6);
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
