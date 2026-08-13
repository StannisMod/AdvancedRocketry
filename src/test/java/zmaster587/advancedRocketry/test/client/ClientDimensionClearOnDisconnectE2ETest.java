package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.client.RealClientHarness;
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
 * Repro for finding C031 (MED) — the
 * player-visible client side.
 *
 * <p>{@code PlanetEventHandler.connectToServer} lacked {@code @SubscribeEvent}
 * (dead), and the live {@code disconnected} handler had its
 * {@code unregisterAllDimensions()} call commented out — so a client leaving a
 * remote server never cleared AR's JVM-global client-side dimension list. Since
 * {@code PacketDimInfo} only merges per-id, dims unique to the previous server
 * lingered as ghost planets/stars after joining another server in the same
 * session.</p>
 *
 * <p>The full server-A → server-B ghost is not reproducible in this harness (a
 * client connects to exactly one dedicated server and cannot reconnect to a
 * second). This pins the corrected clearing contract that closes it: leaving a
 * REMOTE server clears the client's AR dimension registry. The client here is a
 * separate JVM connected to a dedicated server (so the remote-only guard,
 * {@code FMLCommonHandler.getMinecraftServerInstance() == null}, holds), and the
 * dim count is read on the client via the {@code invoke_static_chain} bridge
 * probe before and after a server kick.</p>
 *
 * <p><b>Corrected contract, pinned here (C031 fix, Path B)</b>: after a remote
 * disconnect the client's {@code DimensionManager.getRegisteredDimensions()} is
 * empty (was non-empty while connected).</p>
 */
public class ClientDimensionClearOnDisconnectE2ETest {

    private static final int DIM_A = 9701;
    private static final int DIM_B = 9702;
    private static final String DM_CLASS = "zmaster587.advancedRocketry.dimension.DimensionManager";

    private Path workDir;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue("Server harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue("Client harness disabled",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        workDir = Files.createTempDirectory("forge-client-dim-clear-");
        Path arConfigDir = workDir.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\" "
                + "          isBlackHole=\"false\" diskAngle=\"70\" "
                + "          numPlanets=\"2\" numGasGiants=\"0\">\n"
                + planetXml("PlanetA", DIM_A)
                + planetXml("PlanetB", DIM_B)
                + "    </star>\n"
                + "</galaxy>\n";
        Files.write(arConfigDir.resolve("planetDefs.xml"), xml.getBytes(StandardCharsets.UTF_8));

        serverHarness = RealDedicatedServerHarness.startWith(workDir, false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception ex) {
            try { serverHarness.close(); } catch (Exception cleanup) { ex.addSuppressed(cleanup); }
            serverHarness = null;
            throw ex;
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
                + "            <atmosphereDensity>0</atmosphereDensity>\n"
                + "            <generateCraters>false</generateCraters>\n"
                + "            <generateCaves>true</generateCaves>\n"
                + "            <generateVolcanos>false</generateVolcanos>\n"
                + "        </planet>\n";
    }

    @After
    public void stopBoth() throws Exception {
        Exception deferred = null;
        if (clientHarness != null) {
            try { clientHarness.close(); } catch (Exception e) { deferred = e; }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try { serverHarness.close(); }
            catch (Exception e) { if (deferred == null) deferred = e; else deferred.addSuppressed(e); }
            serverHarness = null;
        }
        if (deferred != null) throw deferred;
    }

    /** Client-side count of AR-registered dimensions via the reflective bridge probe. */
    private int clientArDimCount() throws Exception {
        JsonObject res = clientHarness.bot().invokeStaticChain(
                DM_CLASS, "getInstance,getRegisteredDimensions");
        return res.has("size") ? res.get("size").getAsInt() : -1;
    }

    @Test
    public void remoteDisconnectClearsClientDimensions() throws Exception {
        clientHarness.bot().waitForWorld();

        // On a remote login the server pushes a PacketDimInfo per AR dim, so the
        // client registers them. Poll until they arrive.
        int before = 0;
        for (int waited = 0; waited < 8000 && before <= 0; waited += 500) {
            clientHarness.bot().waitTicks(5);
            before = clientArDimCount();
        }
        assertTrue("client must have AR dimensions synced while connected (got " + before + ")",
                before > 0);

        // Kick the (single) connected player by name: a real remote disconnect that
        // keeps the client JVM alive at the disconnect screen so its AR dimension
        // registry can still be read. (`kick @a` does not resolve a single target.)
        String list = String.join("\n", serverHarness.client().execute("list"));
        String tail = list.contains(":") ? list.substring(list.lastIndexOf(':') + 1) : list;
        String[] names = tail.trim().split("[\\s,]+");
        String player = names.length > 0 && !names[0].isEmpty() ? names[0] : "@a";
        String kicked = String.join("\n",
                serverHarness.client().execute("kick " + player + " c031-remote-disconnect"));

        // Confirm the client actually left the world before checking the registry.
        boolean leftWorld = false;
        for (int waited = 0; waited < 8000 && !leftWorld; waited += 500) {
            clientHarness.bot().waitTicks(5);
            JsonObject st = clientHarness.bot().reportState();
            leftWorld = !st.get("worldReady").getAsBoolean()
                    || st.get("screen").getAsString().toLowerCase().contains("disconnect");
        }
        assertTrue("client must disconnect after kick (list='" + list.trim()
                + "', kick='" + kicked.trim() + "')", leftWorld);

        int after = before;
        for (int waited = 0; waited < 10000 && after != 0; waited += 500) {
            clientHarness.bot().waitTicks(5);
            after = clientArDimCount();
        }
        assertEquals("leaving a remote server must clear the client's AR dimension "
                        + "registry (was " + before + ")", 0, after);
    }
}
