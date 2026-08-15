package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What the server tells a cell's sky about the clouds around it, driven on a real server.
 *
 * <p>The unit tier pins the geometry — which way a cloud lies, how big it looks, what is filtered out.
 * This pins the thing that tier cannot see: that a real generator in a real world actually SEATS
 * clouds, and that the reply a client would be sent is derived from that world's own seed rather than
 * from anything a test arranged.</p>
 *
 * <p>Per-method harness on purpose: this installs a procedural generator, which is a JVM-global, and a
 * shared server would carry it into every class that ran after it.</p>
 */
public class NebulaSkyFeedE2ETest extends AbstractHeadlessServerTest {

    /** A dense galaxy so a bounded sweep finds a cluster, at the shipped star spacing. */
    private static final String GEN_INSTALL = "artest space gen-install 0.9 8 987654321";

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @After
    public void restoreGenerator() throws Exception {
        try {
            exec("artest space gen-reset");
        } catch (Exception ignored) {
        }
    }

    private static long field(String json, String name) {
        String key = "\"" + name + "\":";
        int at = json.indexOf(key);
        assertTrue("probe reply has no field " + name + ": " + json, at >= 0);
        int from = at + key.length();
        int to = from;
        while (to < json.length() && "-0123456789".indexOf(json.charAt(to)) >= 0) {
            to++;
        }
        return Long.parseLong(json.substring(from, to));
    }

    @Test
    public void aGalaxyWithClustersInItHasCloudsToLookAt() throws Exception {
        String installed = exec(GEN_INSTALL);
        assertTrue("the procedural generator must install: " + installed,
                installed.contains("\"ok\":true"));

        String found = exec("artest space nebula-find 512 64");
        assertTrue("a dense galaxy must have a cloud somewhere in it: " + found,
                found.contains("\"found\":true"));

        long sectorX = field(found, "sectorX");
        String feed = exec("artest space nebulae " + sectorX + " 0 0");
        assertTrue("the cell the finder named must report its sky: " + feed, feed.contains("\"ok\":true"));
        assertTrue("and that sky must hold the cloud the finder found: " + feed,
                field(feed, "drawn") >= 1);
        assertTrue("a cloud that is drawn must cover something of the sky: " + feed,
                feed.contains("\"angularRadius\":"));
    }

    @Test
    public void withoutAProceduralGeneratorTheSkyIsEmptyRatherThanInvented() throws Exception {
        // The negative leg, and it is the one that matters: an authored-only pack has no galaxies, so
        // it has no clusters and no gas. A feed that produced a cloud here would be producing it from
        // nothing — and a landmark nobody generated is worse than no landmark.
        String reset = exec("artest space gen-reset");
        assertTrue("the default generator must be restorable: " + reset, reset.contains("\"ok\":true"));

        String feed = exec("artest space nebulae 0 0 0");
        assertTrue("the probe must still answer: " + feed, feed.contains("\"ok\":true"));
        assertEquals("a universe with no clusters must seat no clouds: " + feed, 0L,
                field(feed, "seated"));
        assertEquals("and must draw none: " + feed, 0L, field(feed, "drawn"));
    }

    @Test
    public void whatIsSeatedAndWhatIsDrawnAreReportedSeparately() throws Exception {
        // So a reader can tell a working level-of-detail filter from a missing cloud. Without the two
        // numbers side by side, "the sky shows one" and "there is one out there" are the same reading,
        // and a filter doing its job would be indistinguishable from a generator that stopped seating.
        String installed = exec(GEN_INSTALL);
        assertTrue("the procedural generator must install: " + installed,
                installed.contains("\"ok\":true"));

        String found = exec("artest space nebula-find 512 64");
        assertTrue("a dense galaxy must have a cloud somewhere in it: " + found,
                found.contains("\"found\":true"));
        String feed = exec("artest space nebulae " + field(found, "sectorX") + " 0 0");

        assertTrue("what is drawn may never exceed what is seated: " + feed,
                field(feed, "drawn") <= field(feed, "seated"));
    }
}
