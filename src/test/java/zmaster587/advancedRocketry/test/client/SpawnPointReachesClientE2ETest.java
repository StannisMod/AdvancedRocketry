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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Does a player's client ever learn where the world's spawn point is?
 *
 * <p>Vanilla answers that inside
 * {@code PlayerList.updateTimeAndWeatherForPlayer}, which sends
 * {@code SPacketSpawnPosition(worldIn.getSpawnPoint())}. That method is called
 * on login, on respawn and on cross-dimension transfer. The client applies the
 * packet to both {@code player.setSpawnPoint} and
 * {@code world.getWorldInfo().setSpawn}; the latter is what the compass reads.
 * A fresh {@code WorldClient} seeds a placeholder {@code (8,64,8)} until the
 * packet arrives, and is rebuilt from scratch on every dimension change.</p>
 *
 * <p>This is a client-tier test because the contract is client state and
 * nothing else: the server holds the correct spawn either way, so no
 * server-side probe can distinguish "told the client" from "did not". The
 * repo's litmus holds — delete the client jar and this test cannot run, let
 * alone pass.</p>
 *
 * <p>The respawn leg is deliberately NOT covered: vanilla sends the packet
 * separately one line before the call there, so such a test would pass on a
 * broken and a fixed build alike — vacuous by construction.</p>
 *
 * <p>The class does not extend {@link AbstractClientE2ETest} because that base
 * class boots server and client in one uninterruptible {@code @Before}. The
 * login leg needs a seam between the two so the world spawn can be moved while
 * nobody is connected — otherwise {@code /setworldspawn}'s own broadcast
 * repairs the client independently of the login path, and the test asserts
 * nothing. Lifecycle is reproduced inline, as in {@code WeatherClientSyncE2ETest}.</p>
 */
public class SpawnPointReachesClientE2ETest {

    /** Pre-staged planet with a deterministic dim id, as in the weather e2e. */
    private static final int PLANET_DIM = 9401;

    // Both Y values are deliberately not 64. A naturally generated overworld
    // spawn always has Y == provider.getAverageGroundLevel() == 64 on
    // level-type=DEFAULT, for every seed, and the client placeholder is
    // (8,64,8). Choosing Y outside that makes "the client holds the value we
    // installed" a guarantee rather than a probability — which matters because
    // the harness generates a random world seed on every boot.
    private static final int SPAWN_A_X = 1337, SPAWN_A_Y = 71, SPAWN_A_Z = -424;
    private static final int SPAWN_B_X = -2048, SPAWN_B_Y = 79, SPAWN_B_Z = 777;

    private Path workDir;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startServer() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue(
                "Client harness disabled — set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-client-spawn-point-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"1\" numGasGiants=\"0\">\n"
                + planetXml("SpawnProbePlanet", PLANET_DIM)
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));

        serverHarness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);
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

    /** Connects the real client. Called from the test body, not {@code @Before}. */
    private void startClient() throws Exception {
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

    private static String planetXml(String name, int dim) {
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
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    /**
     * This test fails if production breaks the contract that a player who joins
     * a world has their client told where that world's spawn point is — so the
     * compass points at the world spawn from the first frame instead of at a
     * hardcoded placeholder.
     */
    @Test
    public void joiningPlayerIsToldTheSpawnPointOfTheWorldItJoined() throws Exception {
        // Move the world spawn while NOBODY is connected. /setworldspawn also
        // broadcasts SPacketSpawnPosition to all players; with an empty player
        // list that broadcast reaches nobody, so the login path is left as the
        // only possible carrier of this value to the client.
        String set = exec("setworldspawn " + SPAWN_A_X + " " + SPAWN_A_Y + " " + SPAWN_A_Z);
        String oracle = exec("artest dim info 0");
        assertTrue("server-side overworld spawn must be the value we set"
                        + " (setworldspawn output=" + set + "): " + oracle,
                oracle.contains("\"spawnX\":" + SPAWN_A_X)
                        && oracle.contains("\"spawnY\":" + SPAWN_A_Y)
                        && oracle.contains("\"spawnZ\":" + SPAWN_A_Z));

        startClient();
        clientHarness.bot().waitForWorld();

        JsonObject spawn = waitForClientSpawn(SPAWN_A_X, SPAWN_A_Y, SPAWN_A_Z);
        assertEquals("client should be in the overworld: " + spawn, 0, spawn.get("dim").getAsInt());
        assertSpawnEquals("client world spawn after login", spawn,
                SPAWN_A_X, SPAWN_A_Y, SPAWN_A_Z);
        assertTrue("client player must have a spawn location after login — the same packet"
                        + " writes it: " + spawn,
                spawn.get("hasBedLocation").getAsBoolean());
        assertEquals("client player spawn X after login: " + spawn,
                SPAWN_A_X, spawn.get("bedX").getAsInt());
        assertEquals("client player spawn Y after login: " + spawn,
                SPAWN_A_Y, spawn.get("bedY").getAsInt());
        assertEquals("client player spawn Z after login: " + spawn,
                SPAWN_A_Z, spawn.get("bedZ").getAsInt());
    }

    /**
     * This test fails if production breaks the contract that a player who
     * crosses a dimension boundary has their client told the destination
     * dimension's spawn point — so the compass in the destination points there
     * rather than at a placeholder left over from a freshly constructed client
     * world.
     */
    @Test
    public void transferredPlayerIsToldTheDestinationDimensionSpawnPoint() throws Exception {
        startClient();
        clientHarness.bot().waitForWorld();

        // Positive control: force a known-good client state through vanilla's
        // own broadcast. If this fails, the probe or the client is broken and
        // nothing below would mean anything.
        exec("setworldspawn " + SPAWN_A_X + " " + SPAWN_A_Y + " " + SPAWN_A_Z);
        JsonObject synced = waitForClientSpawn(SPAWN_A_X, SPAWN_A_Y, SPAWN_A_Z);
        assertSpawnEquals("POSITIVE CONTROL: a broadcast SPacketSpawnPosition must reach the"
                        + " client (a failure here is the probe or the client, not AR)",
                synced, SPAWN_A_X, SPAWN_A_Y, SPAWN_A_Z);

        // Now move the spawn SILENTLY — no packet. The client must still hold A.
        String silent = exec("artest dim set-spawn 0 "
                + SPAWN_B_X + " " + SPAWN_B_Y + " " + SPAWN_B_Z);
        assertTrue("silent set-spawn must have taken effect server-side: " + silent,
                silent.contains("\"ok\":true")
                        && silent.contains("\"spawnX\":" + SPAWN_B_X)
                        && silent.contains("\"spawnY\":" + SPAWN_B_Y)
                        && silent.contains("\"spawnZ\":" + SPAWN_B_Z));
        clientHarness.bot().waitTicks(20);
        assertSpawnEquals("silent set-spawn must NOT have pushed a packet — if the client"
                        + " already reads B here the arrange leaked and the assertion below"
                        + " would be vacuous",
                clientHarness.bot().reportSpawn(), SPAWN_A_X, SPAWN_A_Y, SPAWN_A_Z);

        // An AR planet's WorldInfo delegates spawn to the overworld, so the
        // destination's spawn is B. Assert it rather than assume it — which
        // needs the dim loaded, since an unloaded dim has no WorldServer to
        // read a spawn point from.
        String load = exec("artest dim load " + PLANET_DIM);
        assertTrue("destination dim must load before it can be inspected: " + load,
                load.contains("\"ok\":true") || load.contains("\"loaded\":true"));
        String destOracle = exec("artest dim info " + PLANET_DIM);
        assertTrue("destination server-side spawn must be B: " + destOracle,
                destOracle.contains("\"spawnX\":" + SPAWN_B_X)
                        && destOracle.contains("\"spawnY\":" + SPAWN_B_Y)
                        && destOracle.contains("\"spawnZ\":" + SPAWN_B_Z));

        // Real cross-dimension transfer through PlayerList.transferPlayerToDimension.
        exec("artest tp " + PLANET_DIM);
        waitForClientDim(PLANET_DIM);

        JsonObject onPlanet = waitForClientSpawn(SPAWN_B_X, SPAWN_B_Y, SPAWN_B_Z);
        assertEquals("client should be on the planet: " + onPlanet,
                PLANET_DIM, onPlanet.get("dim").getAsInt());
        assertSpawnEquals("client world spawn after cross-dim transfer", onPlanet,
                SPAWN_B_X, SPAWN_B_Y, SPAWN_B_Z);

        // Same contract in the opposite direction.
        exec("artest tp 0");
        waitForClientDim(0);
        assertSpawnEquals("client world spawn after transferring back to the overworld",
                waitForClientSpawn(SPAWN_B_X, SPAWN_B_Y, SPAWN_B_Z),
                SPAWN_B_X, SPAWN_B_Y, SPAWN_B_Z);
    }

    /**
     * Polls ~10 s for the expected triple and returns the LAST sample either
     * way — a soft wait, so the caller's assertion carries the full JSON into
     * the failure message.
     */
    private JsonObject waitForClientSpawn(int x, int y, int z) throws Exception {
        JsonObject latest = clientHarness.bot().reportSpawn();
        for (int waited = 0; waited < 200; waited += 10) {
            if (latest != null && latest.has("spawnX")
                    && latest.get("spawnX").getAsInt() == x
                    && latest.get("spawnY").getAsInt() == y
                    && latest.get("spawnZ").getAsInt() == z) {
                return latest;
            }
            clientHarness.bot().waitTicks(10);
            latest = clientHarness.bot().reportSpawn();
        }
        return latest;
    }

    /** Polls until the client reports the expected dim, capped at ~10 seconds. */
    private void waitForClientDim(int expectedDim) throws Exception {
        for (int waited = 0; waited < 200; waited += 10) {
            clientHarness.bot().waitTicks(10);
            JsonObject s = clientHarness.bot().reportSpawn();
            if (s != null && s.has("dim") && s.get("dim").getAsInt() == expectedDim) {
                return;
            }
        }
        throw new AssertionError("client never reached dim " + expectedDim
                + " (last spawn report: " + clientHarness.bot().reportSpawn() + ")");
    }

    private static void assertSpawnEquals(String what, JsonObject s, int x, int y, int z) {
        assertEquals(what + " — X: " + s, x, s.get("spawnX").getAsInt());
        assertEquals(what + " — Y: " + s, y, s.get("spawnY").getAsInt());
        assertEquals(what + " — Z: " + s, z, s.get("spawnZ").getAsInt());
    }
}
