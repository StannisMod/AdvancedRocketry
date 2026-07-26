package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * The canonical ship-fixture sites must stay buildable ground.
 *
 * <p>Client ship fixtures are anchored at fixed coordinates, which makes the LANDSCAPE an input to
 * those tests. With the harness world seed pinned that input is a constant, so the sites were
 * surveyed once and hardcoded — each one flat, solid and unroofed enough to assemble a ship on,
 * lift it and stand a body beside it.</p>
 *
 * <p>This pins that survey. Change the seed (or the generator) and the sites stop being sites: this
 * test then fails with "the fixture site is no longer flat/clear", instead of four client e2e tests
 * failing much later with symptoms that read as broken production — a ship that will not lift, a
 * client that will not fall, a body that never reaches the client. That misreading already cost one
 * investigation, which is the whole reason this guard exists.</p>
 *
 * <p>Re-survey with {@code /artest worldgen find-biome} + {@code find-site} and update BOTH the
 * table below and the fixtures that use it.</p>
 */
public class HarnessFixtureSitesTest {

    /** owner test, x, z, expected ground Y — the surveyed sites (see the class javadoc). */
    private static final Object[][] SITES = {
            {"VSPilotSeatRelogControlE2ETest", 1056, 6116, 67},
            {"VSShipRenderPoseSkewE2ETest", 268, 6176, 71},
            {"VSRemoteBodyModelGateE2ETest legA", 212, 7488, 64},
            {"VSRemoteBodyModelGateE2ETest legB", 128, 7524, 63},
            {"VSShipEntryRefusedKeepsPilotSeatedE2ETest", 100, 4100, 64},
    };

    /** What a ship fixture needs: its ~10-block hull plus margin, and room to lift and be fallen at. */
    private static final int PAD_RADIUS = 8;
    private static final int HEADROOM = 20;
    private static final int MAX_DEVIATION = 1;

    private Path dir;
    private RealDedicatedServerHarness harness;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        dir = Files.createTempDirectory("forge-server-fixture-sites-");
    }

    @After
    public void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    public void everyCanonicalFixtureSiteIsStillFlatSolidAndUnroofed() throws Exception {
        harness = RealDedicatedServerHarness.startWith(dir, /*cleanupOnClose=*/true);

        // Instrument control: a column known to be a mountain under this seed must FAIL the same
        // check. Without it a green sweep below could equally mean "the check always says yes".
        String mountain = check(7200, 7200);
        assertTrue("instrument control: (7200,7200) is an extreme-hills column and must NOT pass "
                + "the site check — if it does, the check is not measuring anything: " + mountain,
                mountain.contains("\"ok\":false"));

        for (Object[] site : SITES) {
            String owner = (String) site[0];
            int x = (Integer) site[1];
            int z = (Integer) site[2];
            int expectedY = (Integer) site[3];
            String resp = check(x, z);
            assertTrue(owner + "'s fixture site (" + x + "," + z + ") is no longer flat, solid and "
                    + "unroofed ground. The fixtures anchored here will fail in ways that read as "
                    + "production bugs (a ship that will not lift, a client that will not fall). "
                    + "Re-survey with `artest worldgen find-site` and move them. Probe: " + resp,
                    resp.contains("\"ok\":true"));
            assertTrue(owner + "'s fixture site changed height — it was surveyed at y=" + expectedY
                            + " and the fixture is anchored there. Probe: " + resp,
                    resp.contains("\"baseY\":" + expectedY));
        }
    }

    private String check(int x, int z) throws Exception {
        return String.join("\n", harness.client().execute(
                "artest worldgen site-check 0 " + x + " " + z + " "
                        + PAD_RADIUS + " " + HEADROOM + " " + MAX_DEVIATION));
    }
}
