package zmaster587.advancedRocketry.test.server;

import org.junit.Test;
import zmaster587.advancedRocketry.test.ServerTicks;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Does {@code artest server wait} measure the WORLD, or does it measure itself?
 *
 * <p>Measured 2026-08-17 on a space slot world: {@code wait <slotDim> 60} reported
 * {@code elapsedTicks=0} after 12 s of wall clock, and a test built on that reading spent two
 * revisions hunting a crossing bug that did not exist. Two causes fit equally: the slot world really
 * does not tick (headless, no player, no ticking chunks), or the probe polls
 * {@code getTotalWorldTime()} from the command — i.e. on the server thread, the one thread that
 * advances it — and so blocks its own subject.</p>
 *
 * <p>This is that discriminator, asked of a world nobody doubts. <b>Green</b> = the probe reports real
 * elapsed ticks on a ticking world, so a zero elsewhere is a fact about that world. <b>Red</b> = the
 * probe cannot observe ticks at all and every reading it has ever produced is its own reflection.</p>
 *
 * <p>It stays in the suite rather than being deleted with its answer: what it pins is a HARNESS
 * contract that other tests read as ground truth, and the day it starts failing is the day those
 * tests begin measuring nothing.</p>
 */
public class ServerWaitProbeReportsRealTicksTest extends AbstractSharedServerTest {

    /** Small enough to stay fast, large enough that a scheduler hiccup cannot fake it. */
    private static final int TICKS = 20;

    /**
     * ANSWERED 2026-08-17: it measures itself. On the OVERWORLD — a world that ticks by definition —
     * the probe reported zero elapsed ticks, so the handler runs on the very thread that advances the
     * clock and can never see it move. Every "wait N ticks" in the suite has been a sleep.
     *
     * <p>What this test pins now is therefore not "the clock advances" (it cannot, until the probe is
     * rebuilt) but the property that keeps the next reader out of the same hole: <b>the probe must SAY
     * that it did not advance</b>. A reply claiming success with no such field is what cost this
     * session two wrong diagnoses.</p>
     */
    @Test
    public void theWaitProbeNeverClaimsTicksItDidNotObserve() throws Exception {
        String reply = exec("artest server wait 0 " + TICKS);
        assertTrue("the wait probe failed on the overworld: " + reply, reply.contains("\"requested\""));

        int elapsed = extractInt(reply, "elapsedTicks");
        boolean claimsAdvanced = reply.contains("\"advanced\":true");
        if (elapsed >= TICKS) {
            assertTrue("the clock DID advance, so the probe must say so — a real wait that reports "
                    + "itself as a non-wait is the same defect mirrored: " + reply, claimsAdvanced);
            return;
        }
        assertTrue("the probe returned fewer ticks than asked and must not report that as a wait: "
                + reply, reply.contains("\"advanced\":false"));
        assertTrue("and it must name what to do instead, or the next caller repeats the mistake: "
                + reply, reply.contains("\"hint\""));
    }

    /**
     * The other half of the same contract: a test that asks for N ticks must be able to SEE the
     * world's own clock move by N. The probe above cannot deliver that from the server thread, so the
     * waiting lives in the test jvm ({@link ServerTicks}) and this is its acceptance — asked, again,
     * of a world whose answer is not in doubt.
     *
     * <p>Note what is asserted and what is not: the clock advanced by at least what was asked. Not
     * how long it took, not that it stopped there. A wall-clock pin here would be a test of this
     * machine's load, which is the very confusion the task exists to end.</p>
     */
    @Test
    public void aTestSideWaitAdvancesTheWorldsOwnClock() throws Exception {
        // The premise, measured rather than asserted in a comment: the handler answering this runs on
        // the thread that advances the clock. That is WHY the wait cannot live in a probe, and it was
        // once written down here the other way round and believed for months.
        String clock = exec("artest server tick-count 0");
        assertTrue("a probe handler must report that it runs on the server thread — if this ever "
                + "flips, a probe-side wait becomes possible and ServerTicks can be retired: " + clock,
                clock.contains("\"onServerThread\":true"));

        long before = ServerTicks.count(client(), 0);
        long observed = ServerTicks.await(client(), 0, TICKS);
        long after = ServerTicks.count(client(), 0);

        assertTrue("the wait reported " + observed + " ticks but was asked for " + TICKS
                + " — a wait may never return short", observed >= TICKS);
        assertTrue("the overworld clock must have moved by at least " + TICKS + " ticks across the "
                + "wait, but went " + before + " -> " + after, after - before >= TICKS);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
