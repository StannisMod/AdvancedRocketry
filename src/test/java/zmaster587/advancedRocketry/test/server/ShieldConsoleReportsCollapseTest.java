package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * A console reports its network as dead once the network dies.
 *
 * <p>This started life as a regression test for a defect that turned out not to exist: the solver's
 * disconnected path does not notify controllers, but the console never depended on that push — its
 * own tick pulls the network state and falls back to a cleared readout when there is none. The test
 * was kept because the property it actually pins is worth pinning, and it was RE-AIMED at that: the
 * fallback path, not the push. It fails if the console stops clearing itself, which is the way this
 * display can really go stale.</p>
 *
 * <p>Recorded in ledger #260, including the wrong version — it was caught only by re-running this
 * test with the "fix" reverted and watching it pass anyway.</p>
 */
public class ShieldConsoleReportsCollapseTest extends AbstractSharedServerTest {

    private static final Pattern STATUS = Pattern.compile("\"networkStatus\":(-?\\d+)");
    private static final Pattern CONNECTED = Pattern.compile("\"networkConnected\":(true|false)");

    /** `SubsystemNetworkStatus.DISCONNECTED` — no source, or no sink, so nothing can flow. */
    private static final int DISCONNECTED = 1;

    private static final int DIM = 0;
    private static final int Y = 64;

    @Test
    public void aConsoleStopsReportingANetworkThatLostItsLastSource() throws Exception {
        int z = 900;
        int source = 1200;
        int sink = source + 1;
        int console = source + 2;

        place("affs:shield_generator", source, z);
        place("affs:field_generator", sink, z);
        place("affs:shield_console", console, z);
        exec("artest energy inject " + DIM + " " + source + " " + Y + " " + z + " 1000000");

        exec("artest shield tick " + DIM);
        String working = consoleInfo(console, z);
        assertTrue("premise: with a source and a sink the console must report a live network: "
                + working, extract(working, CONNECTED).equals("true"));

        // Take the source away. The network can no longer move anything, and the console must say so.
        exec("artest fill " + DIM + " " + source + " " + Y + " " + z + " "
                + source + " " + Y + " " + z + " minecraft:air");
        exec("artest shield tick " + DIM);

        String collapsed = consoleInfo(console, z);
        assertEquals("a console whose network lost its last source must stop reporting it as live: "
                + collapsed, "false", extract(collapsed, CONNECTED));
        assertEquals("and must report the disconnected status rather than the previous one: "
                + collapsed, DISCONNECTED, Integer.parseInt(extract(collapsed, STATUS)));
    }

    private void place(String block, int x, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + z + " " + block);
        assertTrue(block + " place failed: " + resp, resp.contains("\"placed\":true"));
    }

    private String consoleInfo(int x, int z) throws Exception {
        return exec("artest shield console-info " + DIM + " " + x + " " + Y + " " + z);
    }

    private static String extract(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return m.group(1);
    }
}
