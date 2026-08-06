package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.client.render.planet.CorridorGeometry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The hyperspace corridor has to read as travelling FORWARD.
 *
 * <p>A pilot in transit has no controls, no bodies in the sky and no number counting down, so the
 * corridor is the whole of what tells him the ship is moving — and a corridor whose rings recede
 * tells him, just as clearly, that it is moving backwards. That direction is not a matter of taste:
 * it follows from where the rings are, so it is checked here rather than eyeballed in a playtest.
 *
 * <p>How many rings there are, how far apart, how wide and how fast are {@code tunable} and are
 * deliberately not pinned. What is pinned is the two rules a viewer actually reads: rings approach,
 * and none of them is visible sitting on top of the camera.
 */
public class CorridorGeometryTest {

    /** Ticks spanning exactly one travel period, so no sample crosses the corridor's wrap. */
    private static double[] onePeriodOfTicks() {
        double period = 1.0 / CorridorGeometry.DRIFT_PER_TICK;
        double[] ticks = new double[9];
        for (int i = 0; i < ticks.length; i++) {
            // Strictly inside [0, period): the wrap itself is the seam, not part of the travel.
            ticks[i] = period * i / ticks.length;
        }
        return ticks;
    }

    @Test
    public void everyRingGetsCloserAsTimePasses() {
        double[] ticks = onePeriodOfTicks();
        for (int ring = 0; ring < CorridorGeometry.RINGS; ring++) {
            float previous = CorridorGeometry.ringDistance(ring, CorridorGeometry.driftAt(ticks[0]));
            for (int i = 1; i < ticks.length; i++) {
                float now = CorridorGeometry.ringDistance(ring, CorridorGeometry.driftAt(ticks[i]));
                assertTrue("the corridor must travel TOWARD the viewer, or the flight reads as going"
                                + " backwards: ring " + ring + " sat at " + previous + " at tick "
                                + ticks[i - 1] + " and at " + now + " at tick " + ticks[i],
                        now < previous);
                previous = now;
            }
        }
    }

    @Test
    public void theRingAtTheViewersNoseIsTheDimmest() {
        // The nearest ring is the one that arrives and passes. If it is drawn at full strength when
        // it reaches the camera, it pops into existence in the middle of the screen instead of
        // fading in. So its brightness must rise and fall WITH its distance, never against it.
        double[] ticks = onePeriodOfTicks();
        float nearest = Float.MAX_VALUE, alphaAtNearest = 0f;
        float farthest = -1f, alphaAtFarthest = 0f;
        for (double tick : ticks) {
            float drift = CorridorGeometry.driftAt(tick);
            float distance = CorridorGeometry.ringDistance(0, drift);
            float alpha = CorridorGeometry.ringAlpha(0, drift);
            if (distance < nearest) {
                nearest = distance;
                alphaAtNearest = alpha;
            }
            if (distance > farthest) {
                farthest = distance;
                alphaAtFarthest = alpha;
            }
        }
        assertTrue("the samples must actually span some travel, or nothing below is measured "
                + "(nearest " + nearest + ", farthest " + farthest + ")", farthest > nearest);
        assertTrue("the nearest ring must be dimmest when it is closest to the viewer, or it pops in"
                        + " at his nose: alpha " + alphaAtNearest + " at distance " + nearest
                        + " vs alpha " + alphaAtFarthest + " at distance " + farthest,
                alphaAtNearest < alphaAtFarthest);
    }

    @Test
    public void noRingIsEverBehindTheViewer() {
        // A ring at a negative distance is drawn behind the camera, where it is either invisible or
        // (in third person) wrapped around the pilot's own back.
        for (double tick : onePeriodOfTicks()) {
            float drift = CorridorGeometry.driftAt(tick);
            for (int ring = 0; ring < CorridorGeometry.RINGS; ring++) {
                assertTrue("ring " + ring + " must sit ahead of the viewer at tick " + tick
                                + " (distance " + CorridorGeometry.ringDistance(ring, drift) + ")",
                        CorridorGeometry.ringDistance(ring, drift) >= 0f);
            }
        }
    }

    @Test
    public void theAxisIsTheLookDirectionItIsHanded() {
        // Vanilla's own convention, so an attitude that came out of eulerFromQuat maps straight on.
        assertAxis("yaw 0 looks down +Z", CorridorGeometry.axis(0f, 0f), 0f, 0f, 1f);
        assertAxis("yaw 90 looks down -X", CorridorGeometry.axis(90f, 0f), -1f, 0f, 0f);
        assertAxis("yaw 180 looks down -Z", CorridorGeometry.axis(180f, 0f), 0f, 0f, -1f);
        assertAxis("pitch 90 looks straight down", CorridorGeometry.axis(0f, 90f), 0f, -1f, 0f);
        assertAxis("pitch -90 looks straight up", CorridorGeometry.axis(0f, -90f), 0f, 1f, 0f);

        for (float yaw = -180f; yaw <= 180f; yaw += 37f) {
            for (float pitch = -90f; pitch <= 90f; pitch += 23f) {
                float[] a = CorridorGeometry.axis(yaw, pitch);
                assertEquals("the axis must be a unit direction at yaw " + yaw + " pitch " + pitch,
                        1.0, Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]), 1e-5);
            }
        }
    }

    @Test
    public void theRingPlaneIsPerpendicularToTheAxisEverywhere() {
        // Including straight up and down, where a naive perpendicular degenerates and the corridor
        // would collapse to a line.
        for (float yaw = -180f; yaw <= 180f; yaw += 37f) {
            for (float pitch = -90f; pitch <= 90f; pitch += 15f) {
                float[] a = CorridorGeometry.axis(yaw, pitch);
                float[] u = CorridorGeometry.perpendicular(a[0], a[1], a[2]);
                float[] v = CorridorGeometry.cross(a[0], a[1], a[2], u[0], u[1], u[2]);
                String at = " at yaw " + yaw + " pitch " + pitch;
                assertEquals("the ring plane's first axis must be perpendicular to the corridor" + at,
                        0.0, a[0] * u[0] + a[1] * u[1] + a[2] * u[2], 1e-5);
                assertEquals("the ring plane's second axis must be perpendicular to the corridor" + at,
                        0.0, a[0] * v[0] + a[1] * v[1] + a[2] * v[2], 1e-5);
                assertEquals("the ring must not collapse: its plane axes must both be unit" + at,
                        1.0, Math.sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]), 1e-5);
                assertEquals("the ring must not collapse: its plane axes must both be unit" + at,
                        1.0, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-5);
            }
        }
    }

    private static void assertAxis(String what, float[] actual, float x, float y, float z) {
        assertEquals(what + " (x)", x, actual[0], 1e-5);
        assertEquals(what + " (y)", y, actual[1], 1e-5);
        assertEquals(what + " (z)", z, actual[2], 1e-5);
    }
}
