package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 3 — EntityRocket SENDPLANETDATA reproduction + regression
 * guard (panel-surfaced pack-2 finding).
 *
 * <p>Two defects on the {@code EntityRocket} planet-selection wire path:</p>
 * <ul>
 *   <li><b>Bug A (server null deref)</b>: the SENDPLANETDATA server handler in
 *       {@code useNetworkData} dereferences {@code storage.getGuidanceComputer()}
 *       with no null guard. A satellite-only rocket (which legitimately has no
 *       guidance computer) NPEs when a destination is confirmed — reachable in
 *       ordinary play.</li>
 *   <li><b>Bug B (reader underflow)</b>: the server writer emits its planet-id
 *       int only when a chip is present, while the reader unconditionally
 *       {@code readInt()}s — a short/empty payload underflows the buffer
 *       (per-packet FML slice framing makes it throw rather than misread).</li>
 * </ul>
 *
 * <p>Post-fix a null guard makes the chipless server handler a no-op, and a
 * reader length guard tolerates an empty payload — neither changes the wire
 * format.</p>
 */
public class RocketSendPlanetDataNullGuidanceTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern THROWN = Pattern.compile("\"thrown\":\"([^\"]*)\"");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssembleRocket(int baseX) throws Exception {
        int baseY = 64;
        int baseZ = 760;
        ok(client().execute(
                "artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7)
                        + " minecraft:air"));
        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1));
        int by = Integer.parseInt(bp.group(2));
        int bz = Integer.parseInt(bp.group(3));
        ok(client().execute("artest rocket assemble 0 " + bx + " " + by + " " + bz));
        String list = ok(client().execute("artest rocket list 0"));
        Matcher rim = ROCKET_LIST_ID.matcher(list);
        int lastId = -1;
        while (rim.find()) lastId = Integer.parseInt(rim.group(1));
        assertTrue("no rocket after assemble: " + list, lastId >= 0);
        return lastId;
    }

    /** Bug A: confirming a destination on a rocket with no guidance computer
     *  must not NPE the server. */
    @Test
    public void sendPlanetDataWithNullGuidanceDoesNotCrash() throws Exception {
        int rid = buildAndAssembleRocket(9500);

        String strip = ok(client().execute("artest rocket strip-guidance " + rid));
        assertTrue("strip-guidance failed: " + strip, strip.contains("\"ok\":true"));
        assertTrue("guidance computer must be gone: " + strip,
                strip.contains("\"hasGuidanceComputer\":false"));

        String resp = ok(client().execute("artest rocket send-planet-data " + rid + " 0"));
        assertTrue("send-planet-data failed: " + resp, resp.contains("\"ok\":true"));

        Matcher m = THROWN.matcher(resp);
        assertTrue("thrown field missing: " + resp, m.find());
        assertTrue("a SENDPLANETDATA packet for a guidance-computer-less rocket "
                        + "must not throw (Bug A); got " + m.group(1) + ": " + resp,
                "null".equals(m.group(1)));
    }

    /** Bug B: the SENDPLANETDATA reader must tolerate an empty payload without
     *  underflowing the buffer. */
    @Test
    public void sendPlanetDataReaderToleratesEmptyPayload() throws Exception {
        int rid = buildAndAssembleRocket(9550);

        String resp = ok(client().execute("artest rocket planet-data-read-empty " + rid));
        assertTrue("planet-data-read-empty failed: " + resp, resp.contains("\"ok\":true"));

        Matcher m = THROWN.matcher(resp);
        assertTrue("thrown field missing: " + resp, m.find());
        assertTrue("reading a SENDPLANETDATA packet with an empty payload must not "
                        + "underflow the buffer (Bug B); got " + m.group(1) + ": " + resp,
                "null".equals(m.group(1)));
    }
}
