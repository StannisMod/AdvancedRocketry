package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 4 — C130 reproduction + regression guard.
 *
 * <p>Contract under test: random planets must be generated exactly once, on the
 * fresh-world load. {@code DimensionManager.createAndLoadDimensions} set its
 * {@code loadedFromXML} flag only inside the fresh-world ({@code loadedPlanets}
 * empty) branch, so on a reload where a {@code numPlanets>0} planetDefs.xml is
 * present (via {@code resetFromXml}, or a re-copied config) the second
 * generation block re-ran and accreted duplicate random planets on every load.</p>
 *
 * <p>Boot 1 generates the galaxy and saves. Between boots the saved world XML is
 * edited to bump a star's {@code numPlanets} from the {@code "0"} that
 * {@code writeXML} always emits (this simulates the {@code numPlanets>0} XML that
 * {@code resetFromXml} would re-copy). Boot 2 on the same world must NOT grow the
 * registered-dimension count. Pre-fix the count grows by the bumped
 * {@code numPlanets}; post-fix (guard on {@code loadedPlanets.isEmpty()}) it is
 * unchanged.</p>
 */
public class DimensionRandomPlanetReloadTest {

    private static final Pattern AR_DIMS = Pattern.compile("\"arDimensions\":\\[([^\\]]*)]");

    private Path workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-c130-planet-regen-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private static int arDimCount(RealDedicatedServerHarness h) throws Exception {
        String list = ok(h.client().execute("artest dim list"));
        Matcher m = AR_DIMS.matcher(list);
        assertTrue("dim list missing arDimensions: " + list, m.find());
        String body = m.group(1).trim();
        if (body.isEmpty()) return 0;
        return body.split(",").length;
    }

    private static Path planetDefsPath(RealDedicatedServerHarness h) throws Exception {
        String save = ok(h.client().execute("artest server save-dimensions"));
        assertTrue("save-dimensions failed: " + save, save.contains("\"xmlExists\":true"));
        Matcher m = Pattern.compile("\"xmlPath\":\"([^\"]*)\"").matcher(save);
        assertTrue("save-dimensions missing xmlPath: " + save, m.find());
        return Paths.get(m.group(1).replace("\\\\", "\\"));
    }

    @Test
    public void randomPlanetsAreNotRegeneratedOnReload() throws Exception {
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);
        int count0 = arDimCount(firstBoot);
        Path xmlPath = planetDefsPath(firstBoot);
        firstBoot.close();
        firstBoot = null;

        // writeXML always emits numPlanets="0"; bump the first star so a reload
        // would (buggily) regenerate random planets for it.
        String content = new String(Files.readAllBytes(xmlPath), StandardCharsets.UTF_8);
        assertTrue("saved world XML must contain numPlanets=\"0\": " + xmlPath,
                content.contains("numPlanets=\"0\""));
        String edited = content.replaceFirst("numPlanets=\"0\"", "numPlanets=\"3\"");
        assertNotEquals("edit must change the XML", content, edited);
        Files.write(xmlPath, edited.getBytes(StandardCharsets.UTF_8));

        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
        int count1 = arDimCount(secondBoot);

        assertEquals("random planets must NOT be regenerated on reload (C130); "
                        + "registered AR dims went " + count0 + " -> " + count1,
                count0, count1);
    }
}
