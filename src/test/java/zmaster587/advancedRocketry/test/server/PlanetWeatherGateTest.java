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

import static org.junit.Assert.assertTrue;

/**
 * planet weather compatibility gates (feature/better_weather).
 *
 * <p>Pins two player-visible contracts of {@code WorldProviderPlanet.updateWeather}:</p>
 * <ol>
 *   <li><b>Atmosphere gate.</b> A planet whose atmosphere is thinner than
 *       {@code minAtmosphereDensityForRain} (default 75) must NOT rain, even with
 *       {@code rainMarker=1} ("always rain"). The same marker on a thick-atmosphere
 *       planet DOES rain — the contrast proves the gate, not a dead tick.</li>
 *   <li><b>Thunder requires rain.</b> {@code thunderMarker=1} combined with
 *       {@code rainMarker=-1} must leave the planet not thundering — vanilla
 *       couples thunder to rain and AR must not create a dry storm.</li>
 * </ol>
 *
 * <p>All three fixture planets keep a non-default marker so
 * {@code usesCustomWorldInfo()} engages the custom cycle; the live state is read
 * back through {@code artest weather get}.</p>
 */
public class PlanetWeatherGateTest {

    private static final int DIM_THIN_RAIN   = 9111; // density 10, rainMarker 1   → must stay clear
    private static final int DIM_THICK_RAIN  = 9112; // density 100, rainMarker 1  → must rain
    private static final int DIM_DRY_THUNDER = 9113; // density 100, thunder 1 / rain -1 → no thunder

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void writeFixture() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-server-weather-gate-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"3\" numGasGiants=\"0\">\n"
                + planetXml("ThinRainPlanet",  DIM_THIN_RAIN,   /*density*/ 10,  /*rainMarker*/ 1,  /*thunderMarker*/ 0)
                + planetXml("ThickRainPlanet", DIM_THICK_RAIN,  /*density*/ 100, /*rainMarker*/ 1,  /*thunderMarker*/ 0)
                + planetXml("DryThunderPlanet", DIM_DRY_THUNDER, /*density*/ 100, /*rainMarker*/ -1, /*thunderMarker*/ 1)
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String planetXml(String name, int dim, int density, int rainMarker, int thunderMarker) {
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
                + "            <atmosphereDensity>" + density + "</atmosphereDensity>\n"
                + "            <rainMarker>" + rainMarker + "</rainMarker>\n"
                + "            <thunderMarker>" + thunderMarker + "</thunderMarker>\n"
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
    public void atmosphereGatesRainAndThunderRequiresRain() throws Exception {
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String dimList = String.join("\n", harness.client().execute("artest dim list"));
        for (int dim : new int[]{DIM_THIN_RAIN, DIM_THICK_RAIN, DIM_DRY_THUNDER}) {
            assertTrue("fixture dim " + dim + " not registered: " + dimList,
                    dimList.contains(String.valueOf(dim)));
        }

        // Force a fresh weather evaluation on each planet, then read the live
        // state. A few ticks let updateWeather run its marker + gate logic.
        String thin   = weatherAfterSettle(DIM_THIN_RAIN);
        String thick  = weatherAfterSettle(DIM_THICK_RAIN);
        String dry    = weatherAfterSettle(DIM_DRY_THUNDER);

        // Contrast: same rainMarker=1, opposite atmosphere → opposite rain state.
        assertTrue("thick-atmosphere planet with rainMarker=1 must rain (gate baseline): " + thick,
                thick.contains("\"isRaining\":true"));
        assertTrue("thin-atmosphere planet must stay clear despite rainMarker=1 "
                        + "(atmosphere gate): " + thin,
                thin.contains("\"isRaining\":false"));

        // Thunder cannot exist without rain: dry planet (rainMarker=-1) must not thunder.
        assertTrue("dry planet (rainMarker=-1) must not rain: " + dry,
                dry.contains("\"isRaining\":false"));
        assertTrue("thunderMarker=1 with no rain must NOT thunder (vanilla couples them): " + dry,
                dry.contains("\"isThundering\":false"));
    }

    /**
     * Reads {@code artest weather get <dim>} a few times so the dedicated server
     * has ticked {@code updateWeather} at least once after the dims came online.
     */
    private String weatherAfterSettle(int dim) throws Exception {
        String last = "";
        for (int i = 0; i < 5; i++) {
            last = String.join("\n", harness.client().execute("artest weather get " + dim));
            if (!last.contains("\"error\"")) {
                // brief settle to let the weather cycle apply the marker/gate
                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
            }
        }
        return last;
    }
}
