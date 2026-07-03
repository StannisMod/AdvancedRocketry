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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * TASK-10b Phase 4 — sleep and flint-and-steel guards in vacuum dims —
 * server tier. Relabeled down the pyramid from the old client-harness
 * {@code VacuumGuardsE2ETest} per honest-client-e2e.md: the old test's own
 * javadoc said its probes were "synthetic event posts … sidestepping the
 * vanilla bed-right-click pre-checks" — that's a server-side handler
 * contract, and synthetic event posts ARE the honest stimulus at this tier.
 * Player supply: {@code ensure-fake}.
 */
public class VacuumGuardsTest {

    private static final int DIM_VAC = 9611;
    private static final int DIM_AIR = 9612;

    private static final Pattern SLEEP_RESULT = Pattern.compile("\"resultStatus\":\"([^\"]*)\"");
    private static final Pattern CANCELED = Pattern.compile("\"canceled\":(true|false)");

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void startServer() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-vacuum-guards-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                + planetXml("VacuumPlanet", DIM_VAC, 0)
                + planetXml("AirPlanet", DIM_AIR, 100)
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
    }

    private static String planetXml(String name, int dim, int atmosDensity) {
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
                + "            <atmosphereDensity>" + atmosDensity + "</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    @After
    public void stopServer() throws Exception {
        if (harness != null) harness.close();
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", harness.client().execute(cmd));
    }

    private String stringField(Pattern p, String src, String name) {
        Matcher m = p.matcher(src);
        assertTrue("field " + name + " missing in: " + src, m.find());
        return m.group(1);
    }

    /** Stations the fake player in the dim and lets the dim's
     *  AtmosphereHandler settle so the guards query a live atmosphere. */
    private void enterDim(int dim) throws Exception {
        String fake = exec("artest player ensure-fake " + dim + " 8.5 120 8.5");
        assertTrue("ensure-fake must succeed: " + fake, fake.contains("\"ok\":true"));
        exec("artest player tick-living 40");
        // Off-thread wait — the server free-runs the ticks meanwhile.
        Thread.sleep(2500L);
    }

    /** Sleep in a vacuum dim is refused with OTHER_PROBLEM. */
    @Test
    public void sleepInVacuumDimIsRefused() throws Exception {
        enterDim(DIM_VAC);
        String resp = exec("artest player try-sleep");
        assertEquals("sleep in vacuum dim must be refused with OTHER_PROBLEM; " + resp,
                "OTHER_PROBLEM", stringField(SLEEP_RESULT, resp, "resultStatus"));
    }

    /** The vacuum gate keys on isBreathable(), not "is AR dim". */
    @Test
    public void sleepInBreathableArDimNotRefusedByVacuumGate() throws Exception {
        enterDim(DIM_AIR);
        String resp = exec("artest player try-sleep");
        assertNotEquals("breathable AR dim must NOT be refused by the vacuum-sleep gate; "
                + resp, "OTHER_PROBLEM", stringField(SLEEP_RESULT, resp, "resultStatus"));
    }

    /** Flint-and-steel right-click in a vacuum dim is canceled. */
    @Test
    public void flintInVacuumDimDoesNotIgnite() throws Exception {
        enterDim(DIM_VAC);
        String resp = exec("artest player try-ignite");
        assertEquals("flint right-click in vacuum dim must be canceled by "
                + "PlanetEventHandler.blockRightClicked; " + resp,
                "true", stringField(CANCELED, resp, "canceled"));
    }

    /** Counter-test: breathable AR dim does not cancel ignition. */
    @Test
    public void flintInBreathableArDimDoesIgnite() throws Exception {
        enterDim(DIM_AIR);
        String resp = exec("artest player try-ignite");
        assertEquals("flint right-click in a breathable AR dim must NOT be canceled; "
                + resp, "false", stringField(CANCELED, resp, "canceled"));
    }
}
