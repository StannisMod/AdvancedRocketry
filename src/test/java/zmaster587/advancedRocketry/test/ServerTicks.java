package zmaster587.advancedRocketry.test;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.github.stannismod.forge.testing.server.TestClient;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A wait that actually waits: let the SERVER's world clock advance by N ticks.
 *
 * <p>Console commands are drained on the server thread — the one thread that advances world time —
 * so a probe handler that polls the clock is blocking the very thing it is watching. Measured
 * 2026-08-17: {@code artest server wait 0 20} reports {@code elapsedTicks=0} on the OVERWORLD, a
 * world that ticks by definition. Every call site that read that verb as "N ticks have now happened"
 * was reading a sleep, and two wrong diagnoses were paid for it.</p>
 *
 * <p>So the waiting lives here, in the TEST jvm, which is idle while the server ticks: read the
 * clock, sleep, read again. The same shape the client half already uses — {@code ClientBot.waitTicks}
 * polls a counter from the bridge thread rather than from the client thread.</p>
 *
 * <p>What a caller gets that a sleep never gave it: the returned value is <b>observed</b>, off the
 * world's own clock, and a wait that does not happen fails loudly instead of passing silently.</p>
 */
public final class ServerTicks {

    /** One game tick, nominal. The server may be slower; it is never faster. */
    private static final long TICK_MS = 50L;

    /**
     * How much longer than nominal a wait may take before it is called a failure. A headless server
     * under concurrent forks runs behind, and {@link TestTimeouts} scales this again by fork count —
     * this factor covers ordinary slack (chunk loads, GC), not contention.
     */
    private static final int SLACK_FACTOR = 4;

    /** Floor for the ceiling: a short wait still gets room for one slow round-trip. */
    private static final Duration MIN_BUDGET = Duration.ofSeconds(3);

    /** Ceiling for the ceiling — a runaway wait must surface as a red, not as a hung suite. */
    private static final Duration MAX_BUDGET = Duration.ofSeconds(60);

    private static final Pattern TICK_FIELD = Pattern.compile("\"tick\":(-?\\d+)");

    private ServerTicks() { }

    /** The world's own clock, right now. One round-trip, no waiting. */
    public static long count(TestClient client, int dim) throws Exception {
        String reply = String.join("\n", client.execute("artest server tick-count " + dim));
        Matcher matcher = TICK_FIELD.matcher(reply);
        if (!matcher.find()) {
            throw new AssertionError("artest server tick-count " + dim
                    + " did not report a clock (is the dimension loaded?): " + reply);
        }
        return Long.parseLong(matcher.group(1));
    }

    /**
     * Block the calling test until dimension {@code dim}'s clock has advanced by at least
     * {@code ticks}, and return how far it actually advanced (never less than {@code ticks}).
     *
     * @throws AssertionError if the clock does not get there inside the budget — which is the
     *         interesting case, and the one the old probe reported as success.
     */
    public static long await(TestClient client, int dim, int ticks) throws Exception {
        return await(client, dim, ticks, budgetFor(ticks));
    }

    /** As {@link #await(TestClient, int, int)}, with a caller-chosen ceiling. */
    public static long await(TestClient client, int dim, int ticks, Duration budget) throws Exception {
        if (ticks <= 0) {
            throw new IllegalArgumentException("ticks must be positive, got " + ticks);
        }
        long start = count(client, dim);
        long target = start + ticks;
        long deadlineNanos = System.nanoTime() + budget.toNanos();

        long observed = start;
        while (observed < target) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("dim " + dim + " advanced only " + (observed - start)
                        + " of the " + ticks + " ticks asked for, inside " + budget.toMillis()
                        + " ms. Either the world is not ticking (no players, no forced chunks,"
                        + " a slot world nobody drives) or the server is stalled — both are"
                        + " findings, and neither is a wait.");
            }
            // Sleep the time the remaining ticks would take at nominal rate, so a long wait costs
            // one or two round-trips rather than one per tick. Bounded so a slow server is noticed
            // early rather than at the deadline.
            long remaining = target - observed;
            Thread.sleep(Math.max(TICK_MS, Math.min(500L, remaining * TICK_MS)));
            observed = count(client, dim);
        }
        return observed - start;
    }

    /** The default ceiling for {@code ticks}: nominal duration with slack, load-scaled and clamped. */
    static Duration budgetFor(int ticks) {
        Duration nominal = Duration.ofMillis(ticks * TICK_MS * SLACK_FACTOR);
        Duration base = nominal.compareTo(MIN_BUDGET) < 0 ? MIN_BUDGET : nominal;
        Duration scaled = TestTimeouts.scaled(base);
        return scaled.compareTo(MAX_BUDGET) > 0 ? MAX_BUDGET : scaled;
    }
}
