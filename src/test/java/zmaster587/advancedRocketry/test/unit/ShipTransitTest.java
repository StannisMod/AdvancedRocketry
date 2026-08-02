package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipTransit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the automatic transit integrator. A flight is a SCALAR — origin name, target
 * name, blocks flown against the distance priced at departure (C15 ADDR-12) — so what has to hold is
 * that it converges in the tick count the price implies, never overshoots, never stalls, and never
 * moves backwards.
 */
public class ShipTransitTest {

    private static GalacticCoord at(long ax, long ay, long az) {
        return GalacticCoord.ofAbsolute(ax, ay, az);
    }

    private static ShipTransit flight(long distanceBlocks) {
        return new ShipTransit(at(0, 0, 0), GalacticCoord.ofSectorLocal(3, -2, 5, 0, 0, 0),
                distanceBlocks);
    }

    @Test
    public void aFlightArrivesInExactTickCount() {
        ShipTransit t = flight(1000L);
        int ticks = 0;
        while (!t.arrived() && ticks < 1000) {
            t = t.advance(100); // 1000 / 100 = 10 ticks
            ticks++;
        }
        assertTrue(t.arrived());
        assertEquals(10, ticks);
        assertEquals(0.0, t.remainingDistance(), 0.0);
    }

    @Test
    public void arrivalNeverOvershootsTheDistanceItWasPriced() {
        // A speed that does not divide the distance must still finish exactly at the price.
        ShipTransit t = flight(1000L);
        for (int i = 0; i < 100 && !t.arrived(); i++) {
            t = t.advance(300); // 300, 600, 900, then the final partial step
        }
        assertTrue(t.arrived());
        assertEquals(1000L, t.travelledBlocks());
        assertEquals(0.0, t.remainingDistance(), 0.0);
        assertEquals(1.0, t.progress(), 1e-9);
    }

    @Test
    public void progressIsMonotonic() {
        ShipTransit t = flight(9_000L);
        double prev = t.remainingDistance();
        for (int i = 0; i < 200 && !t.arrived(); i++) {
            t = t.advance(137); // odd speed
            double now = t.remainingDistance();
            assertTrue("distance must not increase", now <= prev + 1e-6);
            prev = now;
        }
        assertTrue(t.arrived());
    }

    @Test
    public void speedExceedingDistanceArrivesNextTick() {
        ShipTransit t = flight(50L).advance(1000);
        assertTrue(t.arrived());
        assertEquals(50L, t.travelledBlocks());
    }

    @Test
    public void aZeroLengthFlightIsAlreadyArrivedAndStable() {
        ShipTransit t = new ShipTransit(at(42, 42, 42), at(42, 42, 42), 0L);
        assertTrue(t.arrived());
        ShipTransit next = t.advance(10);
        assertEquals(t.travelledBlocks(), next.travelledBlocks());
        assertTrue(next.arrived());
    }

    @Test
    public void nonPositiveSpeedDoesNotStallForeverButFinishes() {
        // A zero/negative speed is degenerate; the integrator resolves it by finishing rather than
        // looping forever (callers gate on ship power before starting a transit).
        assertTrue(flight(100L).advance(0).arrived());
        assertTrue(flight(100L).advance(-5).arrived());
    }

    @Test
    public void bothEndsOfTheFlightSurviveIt() {
        // The names are what the arrival is realized against, so they must be exactly what was set:
        // the ship is put down inside the TARGET cell's frame, wherever that frame has got to.
        GalacticCoord origin = GalacticCoord.ofSectorLocal(1, 2, 3, 400, 0, 0);
        GalacticCoord target = GalacticCoord.ofSectorLocal(3, -2, 5, 123, -456, 789);
        ShipTransit t = new ShipTransit(origin, target, 4_000L).advance(1_000);
        assertEquals(origin, t.origin());
        assertEquals(target, t.target());
        assertFalse(t.arrived());
        assertEquals(0.25, t.progress(), 1e-9);
    }

    @Test
    public void aLongFlightNeverStalls() {
        ShipTransit t = flight(1_000_000L);
        long before = t.travelledBlocks();
        t = t.advance(10);
        assertTrue("a flight must advance every tick while in progress", t.travelledBlocks() > before);
    }
}
