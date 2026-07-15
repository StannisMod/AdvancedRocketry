package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Repro (bug-report-workflow, finding C066) for the warp-arrival
 * ConcurrentModificationException.
 *
 * <p>{@code SpaceObjectManager.onServerTick} iterated {@code
 * spaceStationOrbitMap.get(WARPDIMID)} with a live for-each while {@code
 * moveStationToBody} removed the arriving station from that same list. With three or
 * more stations completing their warp transition on the same tick this threw a {@code
 * ConcurrentModificationException}, aborting the transition tick (with exactly two the
 * LinkedList quietly drops the second instead of throwing — a sibling symptom of the
 * same iterate-while-mutate defect). The fix iterates a snapshot copy.</p>
 *
 * <p>The {@code station warp-collision} probe puts three stations into the warp orbit
 * (WARPDIMID) with an already-elapsed transition and invokes {@code onServerTick(null)}
 * directly, catching. This pins the corrected contract: no throw, and ALL stations
 * arrive at their destination orbit. Server-tier (a server-thread crash); no client
 * surface. Fresh server per method — warp/orbit state is a global mutation.</p>
 */
public class WarpArrivalConcurrentModificationTest extends AbstractHeadlessServerTest {

    private static final int SPACE_DIM = -2;
    private static final Pattern THREW = Pattern.compile("\"threw\":(true|false)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(\\d+)");
    private static final Pattern ARRIVED = Pattern.compile("\"arrived\":(\\d+)");

    @Test
    public void threeStationsArrivingSameTickDoNotThrowConcurrentModification() throws Exception {
        exec("artest dim load " + SPACE_DIM);

        String r = exec("artest station warp-collision 0 3");
        assertTrue("probe must run: " + r, r.contains("\"ok\":true"));

        Matcher threw = THREW.matcher(r);
        assertTrue("no threw field in: " + r, threw.find());
        assertTrue("C066: three stations completing warp on the same tick must NOT throw — the fix "
                        + "iterates a snapshot copy; the buggy live for-each threw "
                        + "ConcurrentModificationException. Got: " + r,
                "false".equals(threw.group(1)));

        int count = extract(COUNT, r);
        int arrived = extract(ARRIVED, r);
        assertTrue("all warped stations must arrive at the destination orbit (dim 0), proving the "
                        + "loop processed EVERY station (the buggy loop aborted / dropped after the "
                        + "first): arrived=" + arrived + " count=" + count + " in " + r,
                count == 3 && arrived == count);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static int extract(Pattern p, String s) {
        Matcher m = p.matcher(s);
        assertTrue("pattern " + p + " not found in: " + s, m.find());
        return Integer.parseInt(m.group(1));
    }
}
