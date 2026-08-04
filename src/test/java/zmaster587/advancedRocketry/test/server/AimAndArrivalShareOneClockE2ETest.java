package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Ledger #164's contract, at the server tier: <b>a jump's aim moves with the SPACE clock and with
 * nothing else.</b>
 *
 * <p>The measured defect was a jump that arrived about 8&nbsp;000 blocks from the moon it was aimed
 * at. The standoff ring was innocent — it displaced the arrival by exactly its 1024 blocks. The aim
 * had been evaluated on a different clock from the arrival: it read
 * {@code proxy.getWorldTimeUniversal(0)}, whose client-side implementation ignores the dimension it
 * is asked about and answers with the world the player is standing in, and every dimension but the
 * overworld carries a clock that advances only while it ticks. The two had drifted 14&nbsp;912 ticks
 * apart, and a moon covers about half a block a tick.</p>
 *
 * <h2>Why this cannot be the defect's own repro, stated rather than hidden</h2>
 * The defect needs a physical CLIENT JVM hosting the server, because that is the only arrangement in
 * which the sided proxy hands server logic the client implementation. This harness has no such tier:
 * every test here runs against a real DEDICATED server, whose proxy honours the dimension argument
 * and is therefore already correct. So what is reproduced is the DRIVER rather than the condition —
 * an accessor answering with a clock that is not the space clock — installed by
 * {@code artest space aim-clock lag}. The final word on the single-player symptom stays a playtest.
 *
 * <h2>The two legs, and why the first one is not optional</h2>
 * Leg&nbsp;B alone ("the aim did not move") is satisfied by an aim that never moves for any reason —
 * by a console that failed to resolve its body, by a probe that reports a stale field, by a target
 * that was null all along. Leg&nbsp;A moves the SPACE clock by the same magnitude and requires the
 * aim to move a long way, which is what makes leg&nbsp;B's zero a measurement instead of a silence.
 * Leg&nbsp;A fires on a broken build and a fixed one alike; only leg&nbsp;B discriminates.
 *
 * <h2>Why a moon</h2>
 * A planet-level body's in-cell law is static, so a stale aim costs it nothing and this test would
 * pass on any build. The subject has to be a body whose position inside its cell is live, which is a
 * MOON — and the test refuses to run rather than quietly pass if it cannot find one.
 */
public class AimAndArrivalShareOneClockE2ETest extends AbstractSharedServerTest {

    private static final int NAV_X = 7400, NAV_Y = 80, NAV_Z = 7400;

    /**
     * How far the clocks are driven apart. The playtest's own split was 14&nbsp;912 ticks; this is
     * two orders of magnitude past it, so the miss it produces cannot be mistaken for rounding.
     */
    private static final long SPLIT_TICKS = 1_000_000L;

    /**
     * How far the aim may move when only a NON-space clock moved. Zero is the contract; a few blocks
     * of slack absorbs the rounding in an orbit evaluated at two nearby ticks. Far below anything
     * the defect produces: a moon covers about half a block per tick, so the split above is worth
     * hundreds of thousands of blocks.
     */
    private static final double ALLOWED_DRIFT_BLOCKS = 8.0;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @Test
    public void theAimMovesWithTheSpaceClockAndWithNoOtherClock() throws Exception {
        int moonDim = findAMoon();
        assertTrue("ARRANGEMENT: this test needs a body whose in-cell position is LIVE. A planet's is"
                + " static, so a stale aim costs it nothing and every build would pass. No moon was"
                + " found in the shipped universe.", moonDim != Integer.MIN_VALUE);

        assertTrue("the navigation console must place: ",
                exec("artest nav place 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z).contains("\"ok\":true"));
        String aimed = exec("artest nav target-body 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z + " " + moonDim);
        assertTrue("the console must accept the moon as its target body: " + aimed,
                aimed.contains("\"ok\":true"));

        long clockBefore = jsonLong(exec("artest space frame 0 0 0"), "clock");
        try {
            // ---- LEG A: the positive control. Move the SPACE clock; the aim must follow it. ----
            exec("artest nav refresh 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            long[] aimAtStart = targetLocal(exec("artest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z));

            String moved = exec("artest space set-clock " + (clockBefore + SPLIT_TICKS));
            assertTrue("the space clock must move: " + moved, moved.contains("\"ok\":true"));
            exec("artest nav refresh 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            String afterSpaceMove = exec("artest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            long[] aimAfterSpaceMove = targetLocal(afterSpaceMove);

            double controlMove = distance(aimAtStart, aimAfterSpaceMove);
            assertTrue("CONTROL: the aim must track the SPACE clock — without this, leg B's \"it did"
                            + " not move\" is a statement about an aim that never moves at all."
                            + " clock +" + SPLIT_TICKS + " ticks moved the aim " + controlMove
                            + " blocks; status=" + afterSpaceMove,
                    controlMove > 1_000.0);

            // ---- LEG B: the contract. Move a clock that is NOT the space clock. Nothing may follow. ----
            String lagged = exec("artest space aim-clock lag " + SPLIT_TICKS);
            assertTrue("the lagging proxy must install: " + lagged, lagged.contains("\"ok\":true"));

            // Measure the INPUT before asserting the outcome: a green bought by an arrangement that
            // silently failed to diverge is the failure mode this line exists to make impossible.
            long spaceClock = jsonLong(lagged, "spaceClock");
            long proxyClock = jsonLong(lagged, "proxyClock");
            assertTrue("ARRANGEMENT: the two clocks must actually be " + SPLIT_TICKS + " ticks apart"
                            + " before anything is concluded from the aim. spaceClock=" + spaceClock
                            + " proxyClock=" + proxyClock + " split=" + (spaceClock - proxyClock),
                    spaceClock - proxyClock == SPLIT_TICKS);

            exec("artest nav refresh 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            String afterLag = exec("artest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            long[] aimAfterLag = targetLocal(afterLag);

            double drift = distance(aimAfterSpaceMove, aimAfterLag);
            assertTrue("THE CONTRACT (ledger #164): an aim is evaluated on the SPACE clock, so a clock"
                            + " that is not the space clock may not move it. A proxy answering "
                            + SPLIT_TICKS + " ticks behind moved the aim " + drift + " blocks"
                            + " (allowed " + ALLOWED_DRIFT_BLOCKS + "). The control above proves the"
                            + " aim does move when the SPACE clock moves, so this is not a dead"
                            + " instrument. status=" + afterLag,
                    drift <= ALLOWED_DRIFT_BLOCKS);
        } finally {
            // This server is shared with every other test in the fork: a left-behind proxy or an
            // aged clock is exactly the state that fails somebody else three classes later.
            exec("artest space aim-clock off");
            exec("artest space set-clock " + clockBefore);
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** The dimension id of the first MOON in the overworld body's own cell, or {@link Integer#MIN_VALUE}. */
    private int findAMoon() throws Exception {
        String home = exec("artest space cell-info 0 0 0 0");
        Matcher cell = Pattern.compile("\"dimCell\":\"([^\"]+)\"").matcher(home);
        if (!cell.find()) {
            return Integer.MIN_VALUE;
        }
        String[] sectors = cell.group(1).split("_");
        if (sectors.length != 3) {
            return Integer.MIN_VALUE;
        }
        String bodies = exec("artest space cell-info " + sectors[0] + " " + sectors[1] + " "
                + sectors[2] + " 0");
        Matcher moon = Pattern.compile("\\{\"dim\":(-?\\d+),\"kind\":\"MOON\"").matcher(bodies);
        return moon.find() ? Integer.parseInt(moon.group(1)) : Integer.MIN_VALUE;
    }

    private static long[] targetLocal(String status) {
        Matcher m = Pattern.compile("\"targetLocal\":\\[(-?\\d+),(-?\\d+),(-?\\d+)\\]").matcher(status);
        assertTrue("the console reports no resolved aim, so there is nothing to measure: " + status,
                m.find());
        return new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)),
                Long.parseLong(m.group(3))};
    }

    private static double distance(long[] a, long[] b) {
        assertNotNull(a);
        assertNotNull(b);
        double dx = (double) a[0] - b[0];
        double dy = (double) a[1] - b[1];
        double dz = (double) a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static long jsonLong(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\":(-?\\d+)").matcher(json);
        assertTrue("probe response carries no numeric \"" + field + "\": " + json, m.find());
        return Long.parseLong(m.group(1));
    }
}
