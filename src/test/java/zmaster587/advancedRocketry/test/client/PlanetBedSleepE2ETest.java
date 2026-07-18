package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * The live bot-sleep e2e for per-dimension time + planetary dawn rounding
 * (dercodeKoenig/AdvancedRocketry#66) — the player-truth layer the
 * unit ({@code SleepWakeTimeTest}) and integration
 * ({@code ARDimensionWorldInfoTest}) pins could not reach before the
 * framework grew {@code interact_block}.
 *
 * <p>A real client player stands on an AR planet whose day is
 * {@code rotationalPeriod = 30000} ticks (deliberately ≠ 24000), right-clicks
 * a real bed at planet-night, and falls asleep through the production
 * {@code trySleep} path. The sleep skip must then land on the PLANET's next
 * dawn — a multiple of 30000, where vanilla's hard-coded rounding would put
 * 24000 (still night on this planet, the original #66 symptom) — and the
 * overworld's clock must not move beyond normal ticking, proving the per-dim
 * clock isolation.</p>
 */
public class PlanetBedSleepE2ETest {

    private static final int DIM = 9501;
    private static final int ROTATIONAL_PERIOD = 30000;
    private static final String PLAYER = "ForgeTestClient";

    /** Mid-air stone platform well above worldgen terrain — no mobs, flat, deterministic. */
    private static final int PLAT_Y = 150;
    private static final int BED_X = 8, BED_Y = PLAT_Y + 1, BED_FOOT_Z = 9, BED_HEAD_Z = 10;

    private Path workDir;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled — set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-client-bed-sleep-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                + "        <planet name=\"SleepPlanet\" DIMID=\"" + DIM + "\">\n"
                + "            <isKnown>true</isKnown>\n"
                + "            <fogColor>0.5,0.5,0.5</fogColor>\n"
                + "            <skyColor>0.4,0.6,0.9</skyColor>\n"
                + "            <gravitationalMultiplier>100</gravitationalMultiplier>\n"
                + "            <orbitalDistance>100</orbitalDistance>\n"
                + "            <orbitalTheta>0</orbitalTheta>\n"
                + "            <orbitalPhi>0</orbitalPhi>\n"
                + "            <retrograde>false</retrograde>\n"
                + "            <averageTemperature>250</averageTemperature>\n"
                + "            <rotationalPeriod>" + ROTATIONAL_PERIOD + "</rotationalPeriod>\n"
                + "            <atmosphereDensity>100</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n"
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));

        serverHarness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startupException) {
            try {
                serverHarness.close();
            } catch (Exception cleanup) {
                startupException.addSuppressed(cleanup);
            }
            serverHarness = null;
            throw startupException;
        }
    }

    @After
    public void stopBoth() throws Exception {
        Exception deferred = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception e) {
                deferred = e;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception e) {
                if (deferred == null) deferred = e;
                else deferred.addSuppressed(e);
            }
            serverHarness = null;
        }
        if (deferred != null) throw deferred;
    }

    @Test
    public void sleepingOnPlanetSkipsToPlanetaryDawnOnly() throws Exception {
        clientHarness.bot().waitForWorld();

        // trySleep's mob scan (±8 around the bed) must stay empty; the mid-air
        // platform handles existing worldgen mobs, this handles new spawns.
        serverHarness.client().execute("gamerule doMobSpawning false");

        // Load + pin the planet, then stage the sleeping site: stone platform
        // and a bed (foot at z=9, head at z=10, both facing south — meta 0/8).
        serverHarness.client().execute("artest weather set " + DIM + " clear 12000");
        serverHarness.client().execute("artest fill " + DIM + " 4 " + PLAT_Y + " 4 12 " + PLAT_Y + " 12 minecraft:stone");
        serverHarness.client().execute("artest place " + DIM + " " + BED_X + " " + BED_Y + " " + BED_FOOT_Z + " minecraft:bed 0");
        serverHarness.client().execute("artest place " + DIM + " " + BED_X + " " + BED_Y + " " + BED_HEAD_Z + " minecraft:bed 8");

        serverHarness.client().execute("artest tp " + DIM);
        waitForClientDim(DIM);
        // Vanilla console /tp (same-dim) puts the player on the platform, a
        // bed-reach-range step north of the bed head (|Δz| = 2.5 ≤ 3).
        serverHarness.client().execute("tp " + PLAYER + " 8.5 " + BED_Y + " 7.5");
        clientHarness.bot().waitTicks(20);

        // Night on every clock: vanilla /time set writes ALL loaded worlds, and
        // on the wrapped planet that lands in the per-dim state. Phase
        // 20000/30000 ≈ 0.67 is night on the planet; 20000/24000 is night in
        // the overworld.
        serverHarness.client().execute("time set 20000");
        clientHarness.bot().waitTicks(30); // let skylightSubtracted catch up (isDaytime gate)

        JsonObject before = dimTime(DIM);
        long staged = before.get("worldTime").getAsLong();
        assertTrue("planet clock must be at the staged night time (~20000, tick drift "
                + "tolerated): " + before, staged >= 20000 && staged < 22000);

        // The real player right-clicks the bed foot (server normalizes to the
        // head) -> production trySleep -> fully asleep after 100 ticks -> the
        // sleep skip runs WorldServer's setWorldTime through MixinWorldServer's
        // rotationalPeriod rounding.
        JsonObject click = clientHarness.bot().interactBlock(BED_X, BED_Y, BED_FOOT_Z);
        assertTrue("bed right-click must not error: " + click, click.has("result"));

        // Poll for the planetary dawn: next multiple of 30000 after 20000 is
        // exactly 30000. Vanilla's hard-coded rounding would give 24000 —
        // mid-night on this planet — which the modulo assertion rejects.
        long planetTime = waitForPlanetDawn();
        assertTrue("sleep skip must land at/after the next planetary dawn (30000), got "
                + planetTime, planetTime >= ROTATIONAL_PERIOD);
        assertTrue("sleep skip must land ON planetary dawn (multiple of " + ROTATIONAL_PERIOD
                        + ", vanilla 24000-rounding would miss it): " + planetTime,
                planetTime % ROTATIONAL_PERIOD < 2400);

        // Per-dim isolation: the overworld's clock keeps ticking from 20000 —
        // the planet's sleep skip must NOT touch it.
        long overworldTime = dimTime(0).get("worldTime").getAsLong();
        assertTrue("overworld clock must be unaffected by the planet's sleep skip "
                        + "(expected ~20000 + elapsed, got " + overworldTime + ")",
                overworldTime >= 20000 && overworldTime < 24000);
    }

    private JsonObject dimTime(int dim) throws Exception {
        String raw = String.join("\n",
                serverHarness.client().execute("artest dim time " + dim));
        int start = raw.indexOf('{');
        assertTrue("dim time probe must return JSON: " + raw, start >= 0);
        return new JsonParser().parse(raw.substring(start)).getAsJsonObject();
    }

    /** Polls ~30 s for the planet clock to jump past the staged night (sleep takes 100+ ticks). */
    private long waitForPlanetDawn() throws Exception {
        long last = -1;
        for (int waited = 0; waited < 600; waited += 20) {
            last = dimTime(DIM).get("worldTime").getAsLong();
            if (last >= ROTATIONAL_PERIOD) {
                return last;
            }
            clientHarness.bot().waitTicks(20);
        }
        throw new AssertionError("planet never reached its dawn — either the player "
                + "never fell asleep (trySleep rejected?) or the sleep skip landed off "
                + "planetary dawn (vanilla 24000-rounding instead of rotationalPeriod); "
                + "last planet worldTime=" + last);
    }

    private void waitForClientDim(int expectedDim) throws Exception {
        for (int waited = 0; waited < 200; waited += 10) {
            clientHarness.bot().waitTicks(10);
            JsonObject w = clientHarness.bot().reportWeather();
            if (w != null && w.has("dim") && w.get("dim").getAsInt() == expectedDim) {
                return;
            }
        }
        throw new AssertionError("client never reached dim " + expectedDim
                + " (last weather report: " + clientHarness.bot().reportWeather() + ")");
    }
}
