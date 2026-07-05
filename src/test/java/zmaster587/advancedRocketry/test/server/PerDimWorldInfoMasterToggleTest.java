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
 * Disableability contract for the {@code perDimWorldInfo} MASTER switch
 * (the single gate over AR's per-dimension WorldInfo subsystem: per-planet
 * weather + per-planet time/sleep + wrapper install).
 *
 * <p>Two observable contracts, both pinned by lazily loading a planet under a
 * specific config and reading the probe's named {@code worldInfoClass} field
 *. The flag is flipped at runtime BEFORE
 * the fixture dim is ever loaded — wrapping is decided at dim load and is sticky
 * for the dim's lifetime, so the load order is what makes each case
 * deterministic.</p>
 *
 * <ul>
 *   <li><b>OFF → vanilla.</b> With {@code perDimWorldInfo=false}, a freshly
 *       loaded planet must keep the vanilla shared-overworld WorldInfo — NO
 *       {@code ARDimensionWorldInfo} wrapper. Fails if the master gate in
 *       {@code PlanetWeatherManager.shouldWrap} is reverted.</li>
 *   <li><b>Weather sub-toggle OFF, master ON → wrapper survives (the leak fix).</b>
 *       With {@code perDimWorldInfo=true} but {@code enableCustomPlanetWeather=false},
 *       the wrapper — which owns per-dimension TIME, not just weather — must STILL
 *       install. Fails if {@code shouldWrap}/{@code isWeatherManaged} are
 *       re-gated on the weather flag (the bug where turning weather off also
 *       killed per-dim time).</li>
 * </ul>
 */
public class PerDimWorldInfoMasterToggleTest {

    private static final int FIXTURE_DIM = 9311;
    private static final Pattern WRAPPED =
            Pattern.compile("\"worldInfoClass\":\"[^\"]*ARDimensionWorldInfo\"");

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void writePlanetFixture() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-server-perdim-master-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                + "        <planet name=\"PerDimMasterPlanet\" DIMID=\"" + FIXTURE_DIM + "\">\n"
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

    private void assertDimRegistered() throws Exception {
        String dimList = cmd("artest dim list");
        assertTrue("fixture dim not registered: " + dimList,
                dimList.contains(String.valueOf(FIXTURE_DIM)));
    }

    @Test
    public void masterOffLeavesPlanetOnVanillaWorldInfo() throws Exception {
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
        assertDimRegistered();

        // Master OFF before the dim is EVER loaded → shouldWrap runtime-gates it
        // out, so the first load keeps the vanilla DerivedWorldInfo.
        assertTrue(cmd("artest config set perDimWorldInfo false").contains("\"ok\":true"));

        String info = cmd("artest weather get " + FIXTURE_DIM); // first load
        assertFalse("with perDimWorldInfo OFF a freshly-loaded planet must NOT be "
                + "wrapped (vanilla shared-overworld WorldInfo) — got " + info,
                WRAPPED.matcher(info).find());
    }

    @Test
    public void weatherOffButMasterOnKeepsTheWrapperForPerDimTime() throws Exception {
        harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
        assertDimRegistered();

        // Master ON (boot default, set explicitly for clarity) but the weather
        // SUB-toggle OFF — the leak-fix contract: the wrapper that owns per-dim
        // TIME must still install even though custom weather is disabled.
        assertTrue(cmd("artest config set perDimWorldInfo true").contains("\"ok\":true"));
        assertTrue(cmd("artest config set enableCustomPlanetWeather false").contains("\"ok\":true"));

        String info = cmd("artest weather get " + FIXTURE_DIM); // first load
        assertTrue("perDimWorldInfo ON + weather OFF must STILL wrap the planet "
                + "(per-dim time rides the wrapper) — got " + info,
                WRAPPED.matcher(info).find());

        // Tie the contract to TIME explicitly: the per-dim clock probe sees the
        // wrapper with weather off (proves the time mechanism was not collateral
        // damage of disabling weather).
        String time = cmd("artest dim time " + FIXTURE_DIM);
        assertTrue("dim-time probe must report the per-dim wrapper with weather "
                + "OFF — got " + time, WRAPPED.matcher(time).find());
    }
}
