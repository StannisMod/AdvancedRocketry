package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract C16 CLOCK-3, on a real client: <b>the space clock is readable on both sides and answers
 * the same value on each.</b>
 *
 * <p>Before this shipped, a client asking the space subsystem what time it was got a constant
 * {@code 0} — {@code SpaceSubsystem.spaceClock()} resolved a {@code MinecraftServer} that does not
 * exist in a client JVM. Nothing consumed it, so nothing noticed; and a client-side feature that
 * needed "where is that body now" would have reached for
 * {@code Minecraft.getMinecraft().world.getTotalWorldTime()}, which is a per-dimension clock and is
 * exactly the mistake ledger #164 was.</p>
 *
 * <h2>What makes this test able to fail</h2>
 * A clock that merely LOOKS right at rest proves nothing: in a fresh world every counter is small, so
 * "the client's number is close to the server's" is satisfiable by two unrelated small numbers. So
 * the server's clock is JUMPED a million ticks and the client is required to follow it. On a build
 * with no sync the client's answer does not move at all, and the two claims below separate by six
 * orders of magnitude rather than by rounding.
 *
 * <p>The first assertion is the cheapest and the strongest: a client that was never told anything
 * says so. It is asserted before the arrangement does any work, so a failure there is unambiguous.</p>
 */
public class TheClientKnowsTheSpaceClockE2ETest extends AbstractClientE2ETest {

    private static final String CLOCK = "zmaster587.advancedRocketry.space.SpaceClockSync";

    /** How far the server's clock is jumped. Far past anything a sync period could account for. */
    private static final long JUMP_TICKS = 1_000_000L;

    /** Two full sync periods plus slack, so a missed phase is not a failure. */
    private static final int SYNC_WAIT_TICKS = 520;

    /**
     * How far the two sides may stand apart. One sync period is 200 ticks and the client keeps
     * counting on its own between baselines, so the honest bound is a period plus the round trip —
     * not zero. Six orders of magnitude below the jump above.
     */
    private static final long ALLOWED_SPLIT_TICKS = 600L;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private long clientClock() throws Exception {
        JsonObject answer = bot().invokeStaticInt(CLOCK, "now");
        return Long.parseLong(answer.get("returned").getAsString().trim());
    }

    private String clientHasSync() throws Exception {
        return bot().invokeStaticInt(CLOCK, "hasSync").get("returned").getAsString().trim();
    }

    private long serverClock() throws Exception {
        String frame = exec("artest space frame 0 0 0");
        Matcher m = Pattern.compile("\"clock\":(-?\\d+)").matcher(frame);
        assertTrue("the probe reports no server clock: " + frame, m.find());
        return Long.parseLong(m.group(1));
    }

    @Test
    public void theClientsSpaceClockFollowsTheServers() throws Exception {
        bot().waitForWorld();

        // The load-bearing claim first: a client that has never been told the clock says so, and a
        // build that never sends it can only answer "false" here.
        assertEquals("a joined client must have been told the space clock — C16 CLOCK-3. \"false\""
                + " means the baseline never arrived, which is the whole mechanism missing.",
                "true", clientHasSync());

        long serverBefore = serverClock();
        long clientBefore = clientClock();

        try {
            String moved = exec("artest space set-clock " + (serverBefore + JUMP_TICKS));
            assertTrue("the server clock must move: " + moved, moved.contains("\"ok\":true"));

            bot().waitTicks(SYNC_WAIT_TICKS);

            long serverAfter = serverClock();
            long clientAfter = clientClock();

            // THE DISCRIMINATOR. A client that is not being synced keeps counting its own ticks and
            // moves by a few hundred; one that is synced moves by the jump.
            long clientMoved = clientAfter - clientBefore;
            assertTrue("the client's space clock must FOLLOW the server's, not merely tick along"
                            + " beside it: the server jumped " + JUMP_TICKS + " ticks and the client"
                            + " moved " + clientMoved + " (before=" + clientBefore + " after="
                            + clientAfter + "; server before=" + serverBefore + " after="
                            + serverAfter + ")",
                    clientMoved > JUMP_TICKS / 2L);

            // ...and having followed it, the two agree.
            long split = Math.abs(serverAfter - clientAfter);
            assertTrue("the two sides must answer the same clock to within a sync period: server="
                            + serverAfter + " client=" + clientAfter + " split=" + split
                            + " (allowed " + ALLOWED_SPLIT_TICKS + ")",
                    split <= ALLOWED_SPLIT_TICKS);
        } finally {
            // Put the world's clock back: this harness server is not this test's private property.
            exec("artest space set-clock " + serverBefore);
        }
    }
}
