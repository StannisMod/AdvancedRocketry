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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code AtmosphereHandler} per-player cache bookkeeping — server tier.
 * Relabeled down the pyramid from the old client-harness
 * {@code AtmospherePlayerEventE2ETest} the
 * contract (onTick populates {@code prevAtmosphere} for players in AR dims;
 * {@code onPlayerChangeDim} clears the entry so the next dim repopulates)
 * is server-side handler state the old test read through server probes
 * anyway.
 *
 * <p>Player supply: {@code ensure-fake} (cross-dim moves fire the same
 * {@code PlayerChangedDimensionEvent} Forge's transfer fires);
 * {@code tick-living} supplies the per-tick {@code LivingUpdateEvent}
 * cadence {@code AtmosphereHandler.onTick} subscribes to.</p>
 */
public class AtmospherePlayerEventTest {

    private static final int DIM_VAC = 9411;
    private static final int DIM_AIR = 9412;

    private static final Pattern HAS_CACHED = Pattern.compile("\"hasCachedAtmosphere\":(true|false)");
    private static final Pattern CACHED_ATMOS = Pattern.compile("\"cachedAtmosphere\":\"([^\"]*)\"");

    private Path workDir;
    private RealDedicatedServerHarness harness;

    @Before
    public void startServer() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-atm-player-");
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

    /** Stations the fake player in {@code dim} and ticks it {@code ticks} times. */
    private void enterDimAndTick(int dim, int ticks) throws Exception {
        String fake = exec("artest player ensure-fake " + dim + " 8.5 120 8.5");
        assertTrue("ensure-fake must succeed: " + fake, fake.contains("\"ok\":true"));
        assertTrue(exec("artest player tick-living " + ticks).contains("\"ok\":true"));
        // Off-thread wait — the server free-runs the ticks meanwhile.
        Thread.sleep(ticks * 50L + 500L);
    }

    private String field(Pattern p, String src) {
        Matcher m = p.matcher(src);
        assertTrue("field " + p.pattern() + " missing in: " + src, m.find());
        return m.group(1);
    }

    /** Overworld baseline: no AR atmosphere may be cached for the player. */
    @Test
    public void arDimWithoutVisitDoesNotCacheAtmosphereForPlayer() throws Exception {
        enterDimAndTick(0, 10);
        String cache = exec("artest atmosphere cached-for-player");
        String has = field(HAS_CACHED, cache);
        String atmos = field(CACHED_ATMOS, cache);
        assertTrue("overworld baseline: cache must be empty or non-AR; hasCached=" + has
                + " atmos=" + atmos + " " + cache,
                "false".equals(has) || atmos.isEmpty() || !atmos.contains("vacuum"));
    }

    /** Ticking in an AR dim populates the per-player cache. */
    @Test
    public void arDimTickPopulatesPerPlayerCache() throws Exception {
        enterDimAndTick(DIM_VAC, 40);
        String cache = exec("artest atmosphere cached-for-player");
        assertEquals("after >=1 living-update in an AR dim the per-player cache "
                + "MUST be populated; cache=" + cache, "true", field(HAS_CACHED, cache));
        assertFalse("cached atmosphere name must be non-empty: " + cache,
                field(CACHED_ATMOS, cache).isEmpty());
    }

    /** Dim change clears the entry; the new dim repopulates with its own. */
    @Test
    public void dimChangeClearsAtmosphereCacheForPlayer() throws Exception {
        enterDimAndTick(DIM_VAC, 40);
        String cacheVac = exec("artest atmosphere cached-for-player");
        String atmoVac = field(CACHED_ATMOS, cacheVac);
        assertFalse("vacuum-dim cache must populate before the dim change: " + cacheVac,
                atmoVac.isEmpty());

        enterDimAndTick(DIM_AIR, 40);
        String cacheAir = exec("artest atmosphere cached-for-player");
        String atmoAir = field(CACHED_ATMOS, cacheAir);
        assertFalse("breathable-dim cache must repopulate after dim change: " + cacheAir,
                atmoAir.isEmpty());
        assertFalse("the vacuum-dim atmosphere must NOT carry over into the breathable "
                + "dim's cache slot (onPlayerChangeDim must clear); vacuumAtmos=" + atmoVac
                + " breathableAtmos=" + atmoAir, atmoVac.equals(atmoAir));
    }
}
