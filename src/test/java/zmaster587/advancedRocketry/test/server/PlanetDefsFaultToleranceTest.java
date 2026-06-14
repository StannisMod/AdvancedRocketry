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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Server-level regression guard for the tolerant planetDefs.xml loading
 * (dercodeKoenig/AdvancedRocketry#77).
 *
 * <p>The original report: a planetDefs.xml referencing content from a mod
 * that isn't installed crashed world creation, and the crash killed the JVM
 * via a silent {@code FMLCommonHandler.exitJava} — no crash report, the
 * window just closed. The parser-level guards are pinned in
 * {@code XMLPlanetLoaderTest} (reserved-but-empty ore name, per-planet
 * isolation); what only a real dedicated server can prove is the headline
 * behaviour: <b>the server still boots</b> with a dirty file, the malformed
 * planet is skipped, and the well-formed planets around it survive.</p>
 *
 * <p>The malformed trigger mirrors the integration fixture: a non-numeric
 * {@code <rainMarker>} throws deep inside {@code readPlanetFromNode}, which
 * the per-planet isolation must catch-and-skip.</p>
 */
public class PlanetDefsFaultToleranceTest {

    private static final int GOOD_DIM = 9401;
    private static final int BAD_DIM = 9402;

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void writeDirtyFixture() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-server-planetdefs-fault-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                + planetXml("GoodPlanet", GOOD_DIM,
                        "")
                + planetXml("BadWeatherPlanet", BAD_DIM,
                        "            <rainMarker>NOT_A_NUMBER</rainMarker>\n")
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String planetXml(String name, int dim, String extraElements) {
        return "        <planet name=\"" + name + "\" DIMID=\"" + dim + "\">\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>100</gravitationalMultiplier>\n"
                + "            <orbitalDistance>100</orbitalDistance>\n"
                + "            <orbitalTheta>0</orbitalTheta>\n"
                + "            <orbitalPhi>0</orbitalPhi>\n"
                + "            <retrograde>false</retrograde>\n"
                + "            <averageTemperature>250</averageTemperature>\n"
                + "            <rotationalPeriod>24000</rotationalPeriod>\n"
                + "            <atmosphereDensity>100</atmosphereDensity>\n"
                + extraElements
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    @After
    public void stopHarness() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    public void serverBootsWithMalformedPlanetSkipped() throws Exception {
        // The assertion that matters most is implicit in this line: before the
        // #77 fix a malformed planet killed the JVM during startup (silent
        // exitJava), so startWith() would fail with "server process exited
        // before becoming ready".
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String dimList = String.join("\n", harness.client().execute("artest dim list"));
        assertTrue("well-formed planet must survive a dirty planetDefs.xml: " + dimList,
                dimList.contains(String.valueOf(GOOD_DIM)));
        assertFalse("malformed planet must be skipped, not registered: " + dimList,
                dimList.contains(String.valueOf(BAD_DIM)));

        // The good planet is fully functional, not just listed.
        String info = String.join("\n",
                harness.client().execute("artest planet info " + GOOD_DIM));
        assertTrue("good planet must round-trip its config: " + info,
                info.contains("\"name\":\"GoodPlanet\""));
    }
}
