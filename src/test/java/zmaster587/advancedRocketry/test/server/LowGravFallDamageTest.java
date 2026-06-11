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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code PlanetEventHandler.fallEvent} fall-distance scaling — server tier.
 * Relabeled down the pyramid from the old client-harness
 * {@code LowGravFallDamageE2ETest} per honest-client-e2e.md: the contract
 * (LivingFallEvent.distance × gravity multiplier on IPlanetaryProvider dims,
 * untouched elsewhere) is server-authoritative event-handler logic, and the
 * old client test drove it exclusively through the {@code try-fall} probe
 * anyway. Player supply: {@code ensure-fake}.
 */
public class LowGravFallDamageTest {

    private static final int DIM_LOW_GRAV = 9701;
    private static final Pattern IS_PLANETARY = Pattern.compile("\"isPlanetaryProvider\":(true|false)");
    private static final Pattern INPUT_DIST = Pattern.compile("\"inputDistance\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern RESULT_DIST = Pattern.compile("\"resultDistance\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern GRAVITY = Pattern.compile("\"gravityMultiplier\":(-?\\d+(?:\\.\\d+)?)");

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void startServer() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-lowgrav-fall-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                + "        <planet name=\"LowGravPlanet\" DIMID=\"" + DIM_LOW_GRAV + "\">\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>17</gravitationalMultiplier>\n"
                + "            <orbitalDistance>100</orbitalDistance>\n"
                + "            <orbitalTheta>0</orbitalTheta>\n"
                + "            <orbitalPhi>0</orbitalPhi>\n"
                + "            <retrograde>false</retrograde>\n"
                + "            <averageTemperature>250</averageTemperature>\n"
                + "            <rotationalPeriod>24000</rotationalPeriod>\n"
                + "            <atmosphereDensity>100</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n"
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
    }

    @After
    public void stopServer() throws Exception {
        if (harness != null) harness.close();
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", harness.client().execute(cmd));
    }

    private void stationFake(int dim) throws Exception {
        String fake = exec("artest player ensure-fake " + dim + " 8.5 120 8.5");
        assertTrue("ensure-fake must succeed: " + fake, fake.contains("\"ok\":true"));
        // Off-thread settle (see AdvancementsTriggerTest: `artest server wait`
        // blocks the server thread and must not be used to advance ticks).
        Thread.sleep(1000L);
    }

    /** Overworld: not an IPlanetaryProvider → distance untouched. */
    @Test
    public void overworldDoesNotScaleFallDistance() throws Exception {
        stationFake(0);
        String resp = exec("artest player try-fall 20");
        assertEquals("overworld must NOT be an IPlanetaryProvider; " + resp,
                false, boolField(IS_PLANETARY, resp));
        assertEquals("overworld fall distance must be unchanged by the AR handler; " + resp,
                doubleField(INPUT_DIST, resp), doubleField(RESULT_DIST, resp), 0.001);
    }

    /** Low-grav AR dim: distance × multiplier (17 → 0.17). */
    @Test
    public void lowGravDimScalesFallDistanceByGravityMultiplier() throws Exception {
        stationFake(DIM_LOW_GRAV);
        String resp = exec("artest player try-fall 20");
        assertEquals("low-grav AR dim must report as IPlanetaryProvider; " + resp,
                true, boolField(IS_PLANETARY, resp));
        double input = doubleField(INPUT_DIST, resp);
        double result = doubleField(RESULT_DIST, resp);
        double gravity = doubleField(GRAVITY, resp);
        assertEquals("gravity multiplier must be ~0.17; " + resp, 0.17, gravity, 0.02);
        assertEquals("low-grav AR dim must scale fall distance by gravity; " + resp,
                input * gravity, result, 0.05);
        assertTrue("scaled distance must be strictly less than input; input=" + input
                + " result=" + result, result < input);
    }

    private static boolean boolField(Pattern p, String src) {
        Matcher m = p.matcher(src);
        assertTrue("field " + p.pattern() + " missing in: " + src, m.find());
        return Boolean.parseBoolean(m.group(1));
    }

    private static double doubleField(Pattern p, String src) {
        Matcher m = p.matcher(src);
        assertTrue("field " + p.pattern() + " missing in: " + src, m.find());
        return Double.parseDouble(m.group(1));
    }
}
