package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipTransit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the automatic transit integrator: it must converge exactly on the target,
 * snap on arrival, make monotonic progress, and never drift or stall - across cell boundaries and
 * at galactic magnitude.
 */
public class ShipTransitTest {

    private static GalacticCoord at(long ax, long ay, long az) {
        return GalacticCoord.ofAbsolute(ax, ay, az);
    }

    @Test
    public void straightLineArrivesInExactTickCount() {
        ShipTransit t = new ShipTransit(at(0, 0, 0), at(1000, 0, 0));
        int ticks = 0;
        while (!t.arrived() && ticks < 1000) {
            t = t.advance(100); // 1000 / 100 = 10 ticks
            ticks++;
        }
        assertTrue(t.arrived());
        assertEquals(10, ticks);
        assertEquals(at(1000, 0, 0), t.position());
    }

    @Test
    public void arrivalSnapsExactlyOntoTarget() {
        // A speed that does not divide the distance must still land precisely on the target.
        ShipTransit t = new ShipTransit(at(0, 0, 0), at(1000, 0, 0));
        for (int i = 0; i < 100 && !t.arrived(); i++) {
            t = t.advance(300); // 300,600,900, then within-reach snap to 1000
        }
        assertTrue(t.arrived());
        assertEquals(at(1000, 0, 0), t.position());
        assertEquals(0.0, t.remainingDistance(), 0.0);
    }

    @Test
    public void progressIsMonotonic() {
        ShipTransit t = new ShipTransit(at(0, 0, 0), at(5000, 3000, -2000));
        double prev = t.remainingDistance();
        for (int i = 0; i < 200 && !t.arrived(); i++) {
            t = t.advance(137); // odd speed, oblique direction
            double now = t.remainingDistance();
            assertTrue("distance must not increase", now <= prev + 1e-6);
            prev = now;
        }
        assertTrue(t.arrived());
    }

    @Test
    public void speedExceedingDistanceArrivesNextTick() {
        ShipTransit t = new ShipTransit(at(0, 0, 0), at(50, 0, 0));
        t = t.advance(1000);
        assertTrue(t.arrived());
        assertEquals(at(50, 0, 0), t.position());
    }

    @Test
    public void alreadyAtTargetIsArrivedAndStable() {
        ShipTransit t = new ShipTransit(at(42, 42, 42), at(42, 42, 42));
        assertTrue(t.arrived());
        ShipTransit next = t.advance(10);
        assertEquals(t.position(), next.position());
        assertTrue(next.arrived());
    }

    @Test
    public void nonPositiveSpeedDoesNotStallForeverButSnaps() {
        // A zero/negative speed is degenerate; the integrator resolves it by snapping rather than
        // looping forever (callers gate on ship power before starting a transit).
        ShipTransit t = new ShipTransit(at(0, 0, 0), at(100, 0, 0));
        assertEquals(at(100, 0, 0), t.advance(0).position());
        assertEquals(at(100, 0, 0), t.advance(-5).position());
    }

    @Test
    public void transitCrossesCellBoundariesAndConverges() {
        // Target several cells away on every axis: the fixed-point carry must keep the flight exact.
        GalacticCoord target = GalacticCoord.ofSectorLocal(3, -2, 5, 123, -456, 789);
        ShipTransit t = new ShipTransit(GalacticCoord.ORIGIN, target);
        int ticks = 0;
        while (!t.arrived() && ticks < 100000) {
            t = t.advance(250000); // big steps still land exactly on target
            ticks++;
        }
        assertTrue("must converge within the tick budget", t.arrived());
        assertEquals(target, t.position());
    }

    @Test
    public void obliqueTransitNeverStalls() {
        // A direction dominated by one axis must still advance the position every tick (no zero-move).
        ShipTransit t = new ShipTransit(at(0, 0, 0), at(1_000_000, 3, 0));
        GalacticCoord before = t.position();
        t = t.advance(10);
        assertFalse("position must change each tick while in flight", before.equals(t.position()));
    }
}
