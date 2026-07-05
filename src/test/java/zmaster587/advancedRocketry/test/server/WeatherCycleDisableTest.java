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
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Disableability contract for the custom planet weather CYCLE.
 *
 * <p>{@code WorldProviderPlanet.updateWeather()} overrides the vanilla weather
 * cycle for planets whose XML carries non-default rain/thunder markers. The bug:
 * that override keyed only off the markers, so it kept forcing weather even with
 * {@code enableCustomPlanetWeather} off — overwriting the (un-wrapped) shared
 * overworld weather. The fix gates the override on the config flag too.</p>
 *
 * <p>Contract pinned here, deterministically, by driving {@code updateWeather()}
 * directly via a probe: with a forced-clear marker (rain = -1) set on a planet
 * that we've just made rain, one weather tick suppresses the rain when the flag
 * is ON (custom cycle runs) but leaves it raining when the flag is OFF (vanilla
 * delegation). The marker stays set across both cases; only the config flips.</p>
 */
public class WeatherCycleDisableTest {

    private static final int FIXTURE_DIM = 9301;

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void writePlanetFixture() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-server-weather-disable-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                + "        <planet name=\"WeatherDisablePlanet\" DIMID=\"" + FIXTURE_DIM + "\">\n"
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
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n"
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void stopHarness() throws Exception {
        if (harness != null) harness.close();
    }

    private String cmd(String c) throws Exception {
        return String.join("\n", harness.client().execute(c));
    }

    private boolean isRaining(int dim) throws Exception {
        return cmd("artest weather get " + dim).contains("\"isRaining\":true");
    }

    @Test
    public void customWeatherCycleRunsOnlyWhenConfigEnabled() throws Exception {
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        String dimList = cmd("artest dim list");
        assertTrue("fixture dim not registered: " + dimList,
                dimList.contains(String.valueOf(FIXTURE_DIM)));

        // Load the planet while custom weather is still ENABLED (boot default) so it
        // wraps with its own ARDimensionWorldInfo. Wrapping is sticky for the dim's
        // lifetime, so the later config-off sub-case operates on the same wrapped,
        // overworld-isolated WorldInfo — isolating the updateWeather() gate from the
        // separate (already-tested) wrapping gate.
        assertTrue(cmd("artest config set enableCustomPlanetWeather true").contains("\"ok\":true"));
        String wrapped = cmd("artest weather get " + FIXTURE_DIM);
        // Anchor on the probe's named worldInfoClass field, not a bare substring
        // of the whole response.
        assertTrue("planet must be wrapped while custom weather is on: " + wrapped,
                Pattern.compile("\"worldInfoClass\":\"[^\"]*ARDimensionWorldInfo\"")
                        .matcher(wrapped).find());

        // Forced-clear marker (rain=-1, thunder=-1): the custom cycle, when it runs,
        // drives this planet to clear regardless of what we set.
        String marker = cmd("artest weather set-marker " + FIXTURE_DIM + " -1 -1");
        assertTrue("set-marker failed: " + marker, marker.contains("\"usesCustomWorldInfo\":true"));

        // --- config ON: the forced-clear cycle runs and suppresses the rain ---
        // (No intermediate "is raining" assert — with the cycle active the natural
        // server tick clears it before we could observe it; the post-tick state is
        // the deterministic contract.)
        assertTrue("weather set rain failed",
                cmd("artest weather set " + FIXTURE_DIM + " rain 12000").contains("\"ok\":true"));
        assertTrue("tick-provider failed",
                cmd("artest weather tick-provider " + FIXTURE_DIM + " 3").contains("\"ok\":true"));
        String onAfterTick = cmd("artest weather get " + FIXTURE_DIM);
        assertFalse("with custom planet weather ON, the forced-clear cycle must suppress the "
                + "rain — got " + onAfterTick, onAfterTick.contains("\"isRaining\":true"));

        // --- config OFF (the fix): updateWeather delegates to vanilla; the custom
        // forced-clear cycle does NOT run, so rain we set takes and survives ticks.
        // This fails if the fix is reverted (the marker cycle would clear it).
        assertTrue(cmd("artest config set enableCustomPlanetWeather false").contains("\"ok\":true"));
        assertTrue("weather set rain failed",
                cmd("artest weather set " + FIXTURE_DIM + " rain 12000").contains("\"ok\":true"));
        String offAfterSet = cmd("artest weather get " + FIXTURE_DIM);
        assertTrue("with custom planet weather OFF, set rain must take (no custom cycle to "
                + "suppress it) — got " + offAfterSet, offAfterSet.contains("\"isRaining\":true"));
        assertTrue("tick-provider failed",
                cmd("artest weather tick-provider " + FIXTURE_DIM + " 3").contains("\"ok\":true"));
        String offAfterTick = cmd("artest weather get " + FIXTURE_DIM);
        assertTrue("with custom planet weather OFF, the rain must survive weather ticks "
                + "(vanilla delegation, marker ignored) — got " + offAfterTick,
                offAfterTick.contains("\"isRaining\":true"));
    }
}
