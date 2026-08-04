package zmaster587.advancedRocketry.test.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import zmaster587.advancedRocketry.space.SpaceClockSync;

/**
 * The client's copy of the space clock, as a contract rather than as an implementation.
 *
 * <p>What is pinned here is what a CONSUMER may rely on: that an un-synced client is distinguishable
 * from one told "tick zero", that a baseline plus elapsed client ticks is the answer, that leaving a
 * server forgets the baseline, and that a later baseline WINS — including one that moves the answer
 * backwards, because the server's counter is the truth and a client that ran ahead of it is simply
 * wrong. Nothing here pins the field layout or the arithmetic's internal form.</p>
 */
public class SpaceClockSyncTest {

    @Before
    public void forgetAnyPreviousSync() {
        SpaceClockSync.reset();
    }

    @Test
    public void aClientNobodyHasToldIsDistinguishableFromOneToldItIsTickZero() {
        assertFalse("a client that has never been synced must say so", SpaceClockSync.hasSync());
        assertEquals("and must answer 0 rather than a stale or random value",
                0L, SpaceClockSync.now());

        SpaceClockSync.accept(0L);

        assertTrue("being told the clock IS zero is a sync, not an absence of one",
                SpaceClockSync.hasSync());
        assertEquals(0L, SpaceClockSync.now());
    }

    @Test
    public void theAnswerIsTheBaselinePlusTheClientTicksSinceIt() {
        SpaceClockSync.accept(1_000L);
        assertEquals("immediately after a sync the answer is the value synced",
                1_000L, SpaceClockSync.now());

        for (int i = 0; i < 7; i++) {
            SpaceClockSync.onClientTick();
        }

        assertEquals("seven client ticks after a baseline of 1000 the clock reads 1007",
                1_007L, SpaceClockSync.now());
    }

    @Test
    public void ticksBeforeTheFirstSyncDoNotAccumulateIntoTheAnswer() {
        // The control for the test above: a client ticks for a long time before it is ever told the
        // clock. Those ticks belong to no baseline, so the first sync must answer exactly what it
        // was given - if they leaked in, this reads 1500 instead of 1000.
        for (int i = 0; i < 500; i++) {
            SpaceClockSync.onClientTick();
        }

        SpaceClockSync.accept(1_000L);

        assertEquals("the first baseline is the answer, whatever the client did before it",
                1_000L, SpaceClockSync.now());
    }

    @Test
    public void aLaterBaselineWinsEvenWhenItMovesTheAnswerBackwards() {
        SpaceClockSync.accept(1_000L);
        for (int i = 0; i < 100; i++) {
            SpaceClockSync.onClientTick();
        }
        assertEquals("the client has run 100 ticks ahead on its own", 1_100L, SpaceClockSync.now());

        // The server was lagging: 100 client ticks were only 60 server ticks.
        SpaceClockSync.accept(1_060L);

        assertEquals("a correction from the server is authoritative, downwards included",
                1_060L, SpaceClockSync.now());
    }

    @Test
    public void leavingAServerForgetsItsClock() {
        SpaceClockSync.accept(50_000L);
        SpaceClockSync.onClientTick();
        assertTrue(SpaceClockSync.hasSync());

        SpaceClockSync.reset();

        assertFalse("a disconnected client must not keep answering with the old server's clock",
                SpaceClockSync.hasSync());
        assertEquals(0L, SpaceClockSync.now());

        // And it must not carry the old elapsed ticks into the next server's baseline either.
        SpaceClockSync.accept(10L);
        assertEquals(10L, SpaceClockSync.now());
    }
}
