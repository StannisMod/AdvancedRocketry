package zmaster587.advancedRocketry.test.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import com.github.stannismod.forge.testing.TestTimeouts;

import zmaster587.advancedRocketry.test.client.ClientPoll;

/**
 * Contract of {@link ClientPoll} — the load-scaled poll-until-predicate that replaces fixed
 * {@code waitTicks(N)} budgets in the VS client e2e suite. Pure unit: the "client" is a
 * counter, so the poll's mechanics (early-exit, scaled ceiling, already-satisfied, never-satisfied)
 * are pinned without a client boot.
 *
 * <p>The scaling tests drive {@link TestTimeouts#factor()} through its backing system property and
 * restore it after each test so they cannot leak into a forked run's real factor.</p>
 */
public class ClientPollTest {

    private String savedFactor;

    private void setFactor(String value) {
        savedFactor = System.getProperty(TestTimeouts.PROP_FACTOR);
        if (value == null) {
            System.clearProperty(TestTimeouts.PROP_FACTOR);
        } else {
            System.setProperty(TestTimeouts.PROP_FACTOR, value);
        }
    }

    @After
    public void restoreFactor() {
        if (savedFactor == null) {
            System.clearProperty(TestTimeouts.PROP_FACTOR);
        } else {
            System.setProperty(TestTimeouts.PROP_FACTOR, savedFactor);
        }
        savedFactor = null;
    }

    /** A monotonically rising counter is the stand-in for a converging world value. */
    @Test
    public void reachesThePredicateAndReportsTheIterationItTook() throws Exception {
        setFactor("1");
        int[] value = {0};
        // Each poll step raises the "world" value by 1; predicate = value >= 3, so it takes 3 steps
        // (probe reads 0 before the loop, then 1,2,3 across three steps).
        ClientPoll.Result<Integer> r = ClientPoll.until(
                t -> value[0]++,
                () -> value[0],
                v -> v >= 3,
                1, 50);
        assertTrue("must reach the predicate: " + r, r.satisfied);
        assertEquals("exits the iteration the predicate first holds", 3, r.iterations);
        assertTrue("must early-exit well under the ceiling: " + r, r.iterations < r.ceiling);
    }

    /** Predicate already true on the first read: zero steps, nothing waited. */
    @Test
    public void alreadySatisfiedDoesNotStep() throws Exception {
        setFactor("1");
        int[] steps = {0};
        ClientPoll.Result<Integer> r = ClientPoll.until(
                t -> steps[0] += t,
                () -> 7,
                v -> v >= 3,
                5, 20);
        assertTrue("already-true predicate is satisfied", r.satisfied);
        assertEquals("no iterations run when already satisfied", 0, r.iterations);
        assertEquals("no client ticks advanced when already satisfied", 0, steps[0]);
    }

    /** Predicate that never holds: the poll gives up at the ceiling and reports not-satisfied. */
    @Test
    public void neverSatisfiedStopsAtTheCeiling() throws Exception {
        setFactor("1");
        int[] steps = {0};
        ClientPoll.Result<Integer> r = ClientPoll.until(
                t -> steps[0]++,
                () -> 0,
                v -> v >= 1,
                1, 8);
        assertFalse("a never-true predicate is not satisfied", r.satisfied);
        assertEquals("stops exactly at the single-fork ceiling when factor=1", 8, r.ceiling);
        assertEquals("runs exactly ceiling iterations before giving up", 8, r.iterations);
    }

    /** The ceiling scales by TestTimeouts.factor() — the whole point: a loaded fork gets more budget. */
    @Test
    public void ceilingScalesByTheLoadFactor() throws Exception {
        setFactor("3");
        ClientPoll.Result<Integer> r = ClientPoll.until(
                t -> { },
                () -> 0,
                v -> v >= 1,
                1, 10);
        assertEquals("ceiling = ceil(baseIterations * factor) = 10 * 3", 30, r.ceiling);
        assertEquals("a never-true poll runs the full scaled ceiling", 30, r.iterations);
    }

    /** factor() clamps below 1 up to 1, so the scaled ceiling never shrinks below the single-fork base. */
    @Test
    public void ceilingNeverShrinksBelowTheBase() throws Exception {
        setFactor("0.5"); // clamped to 1.0 by TestTimeouts.factor()
        ClientPoll.Result<Integer> r = ClientPoll.until(
                t -> { },
                () -> 0,
                v -> v >= 1,
                1, 12);
        assertEquals("a sub-1 factor is clamped to 1, ceiling stays at the base", 12, r.ceiling);
    }

    @Test
    public void rejectsNonPositiveBudgets() throws Exception {
        setFactor("1");
        try {
            ClientPoll.until(t -> { }, () -> 0, v -> true, 0, 10);
            fail("stepTicks <= 0 must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            ClientPoll.until(t -> { }, () -> 0, v -> true, 1, 0);
            fail("baseIterations <= 0 must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
