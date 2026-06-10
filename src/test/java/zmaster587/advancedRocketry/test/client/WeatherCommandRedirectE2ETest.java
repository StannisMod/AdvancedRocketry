package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;
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
 * E2e regression guard for the vanilla {@code /weather} → per-dim
 * {@code /advancedrocketry weather} redirect
 * ({@code PlanetWeatherEventHandler.redirectWeatherCommand}).
 *
 * <p><b>Why a real client + real chat.</b> The bug this guards is
 * sender-position-dependent: vanilla {@code CommandWeather} hard-codes
 * {@code server.worlds[0]}, so a player standing on an AR planet who runs
 * {@code /weather rain} silently rains the OVERWORLD and leaves the planet
 * untouched. A console-driven command cannot reproduce that — the console
 * sender stands in the overworld. The framework's {@code send_chat} probe
 * routes through {@code EntityPlayerSP.sendChatMessage} (the real
 * {@code CPacketChatMessage} path), so the server handles the command with the
 * planet-standing player as sender and the {@code CommandEvent} redirect runs
 * its production path.</p>
 *
 * <p>Lifecycle is reproduced inline rather than via {@link AbstractClientE2ETest}
 * for the same reason as {@code WeatherClientSyncE2ETest}: the planet fixture
 * XML must exist in the workdir BEFORE the server boots.</p>
 */
public class WeatherCommandRedirectE2ETest {

    private static final int DIM = 9304;
    /** Must match the framework's single-client default username — the op grant keys on it. */
    private static final String PLAYER = "ForgeTestClient";

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

        workDir = Files.createTempDirectory("forge-client-weather-redirect-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                + "        <planet name=\"RedirectPlanet\" DIMID=\"" + DIM + "\">\n"
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
    public void slashWeatherOnPlanetRainsThePlanetNotTheOverworld() throws Exception {
        clientHarness.bot().waitForWorld();

        // /weather (and the redirect target /advancedrocketry weather) require
        // permission level 2 — grant it the way a server admin would.
        serverHarness.client().execute("op " + PLAYER);

        // Known baseline: both dims explicitly clear. The set/get probes also
        // load + pin the planet dim before the teleport.
        serverHarness.client().execute("artest weather set 0 clear 12000");
        serverHarness.client().execute("artest weather set " + DIM + " clear 12000");
        String before = String.join("\n",
                serverHarness.client().execute("artest weather get " + DIM));
        assertTrue("planet must be wrapped before the command test: " + before,
                before.contains("ARDimensionWorldInfo"));
        assertFalse("planet must start clear: " + before,
                before.contains("\"isRaining\":true"));

        serverHarness.client().execute("artest tp " + DIM);
        waitForClientDim(DIM);

        // The player — standing on the planet — types vanilla /weather rain.
        clientHarness.bot().sendChat("/weather rain 600");

        // Server truth: the PLANET's per-dim state flips to raining...
        JsonObject planetAfter = waitForServerRaining(DIM, true);
        assertTrue("planet did not start raining after player /weather rain "
                        + "(redirect to /advancedrocketry weather missing?): " + planetAfter,
                planetAfter.get("raw").getAsString().contains("\"isRaining\":true"));

        // ...and the OVERWORLD stays clear. Without the redirect vanilla
        // CommandWeather writes to server.worlds[0] — this is the assertion
        // that fails on the unfixed build.
        String overworld = String.join("\n",
                serverHarness.client().execute("artest weather get 0"));
        assertFalse("player /weather rain on a planet leaked to the overworld "
                        + "(vanilla worlds[0] path, redirect not applied): " + overworld,
                overworld.contains("\"isRaining\":true"));

        // Player truth: the client in the planet dim renders the rain the
        // command asked for. Strength streams per tick (code 7); the
        // begin-raining FLAG (code 1) is only broadcast when the server-side
        // strength crosses the isRaining() threshold (> 0.2), so wait past
        // that before asserting the flag.
        JsonObject onPlanet = waitForClientRainStrengthAtLeast(0.25f);
        assertTrue("client should still be in the planet dim: " + onPlanet,
                onPlanet.has("dim") && onPlanet.get("dim").getAsInt() == DIM);
        assertTrue("client-visible isRaining must flip true on the planet: " + onPlanet,
                onPlanet.get("isRaining").getAsBoolean());
        assertTrue("client rainStrength must start climbing on the planet: " + onPlanet,
                onPlanet.get("rainStrength").getAsFloat() > 0f);

        // Reverse direction: /weather clear from the same spot clears the
        // planet (and the overworld stays untouched — still clear).
        clientHarness.bot().sendChat("/weather clear 600");
        waitForServerRaining(DIM, false);
        String overworldAfterClear = String.join("\n",
                serverHarness.client().execute("artest weather get 0"));
        assertFalse("overworld must remain clear after planet /weather clear: "
                + overworldAfterClear, overworldAfterClear.contains("\"isRaining\":true"));
    }

    /** Polls until the client world reports the expected dimension (~10 s cap). */
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

    /**
     * Polls the SERVER-side wrapped weather flag of {@code dim} until it equals
     * {@code raining} (~10 s cap) — the chat command travels client → server and
     * lands on the next tick, so a one-shot read would race it. Returns a JSON
     * object with the final raw probe output under {@code raw}.
     */
    private JsonObject waitForServerRaining(int dim, boolean raining) throws Exception {
        String raw = "";
        for (int waited = 0; waited < 200; waited += 10) {
            raw = String.join("\n",
                    serverHarness.client().execute("artest weather get " + dim));
            if (raw.contains("\"isRaining\":" + raining)) {
                JsonObject out = new JsonObject();
                out.addProperty("raw", raw);
                return out;
            }
            clientHarness.bot().waitTicks(10);
        }
        throw new AssertionError("server dim " + dim + " never reached isRaining="
                + raining + "; last probe: " + raw);
    }

    /** Polls until client-visible rainStrength reaches {@code minStrength} (~10 s cap, soft). */
    private JsonObject waitForClientRainStrengthAtLeast(float minStrength) throws Exception {
        JsonObject latest = clientHarness.bot().reportWeather();
        for (int waited = 0; waited < 200; waited += 10) {
            if (latest.has("rainStrength") && latest.get("rainStrength").getAsFloat() >= minStrength) {
                return latest;
            }
            clientHarness.bot().waitTicks(10);
            latest = clientHarness.bot().reportWeather();
        }
        return latest; // soft wait — caller asserts and prints the report
    }
}
