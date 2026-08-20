package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Asking for a ship that is already being loaded must load it once, never take the server down.
 *
 * <p>Two independent things can want the same unloaded ship loaded on the same tick: the world's own
 * loading pass, which queues a permanently-loaded ship for a BACKGROUND load while no player is near it,
 * and any explicit request for an immediate load. The immediate load runs first and completes; the
 * background pass then finds the ship already loaded. That is a satisfied request, not an error, but it
 * is raised as an exception from the world tick, with nothing between it and the server loop, so the
 * whole dedicated server dies.</p>
 *
 * <p><b>Why this is deterministic rather than a race.</b> The loading pass runs immediately before the
 * load queues are drained, every tick, so the background entry is minted on the very tick the immediate
 * load is served. The ordering inside that tick is fixed; there is no window to miss.</p>
 *
 * <p><b>The arrangement is the whole difficulty.</b> The ship has to be REGISTERED and NOT LOADED at the
 * moment the load is requested, which is why it is built first (a ship is created loaded), then left
 * alone until the world unloads it again, and only then is permanent loading switched on. A ship that
 * never unloaded would make both requests collapse into one and the test would measure nothing, so the
 * unloaded state is asserted, not assumed.</p>
 *
 * <p><b>Its own server, per method.</b> While the defect is live this test does not fail an assertion:
 * it kills the server process. On a shared harness that would take every later method in the class with
 * it and read as several unrelated failures.</p>
 *
 * <p><b>The survival assertion comes first, and the ship-is-loaded assertion is its control.</b> "The
 * server is still up" passes trivially on a build where the load request did nothing at all, so the
 * second assertion is what proves the request was actually served.</p>
 *
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class VSDoubleQueuedShipLoadDoesNotKillTheServerE2ETest extends AbstractHeadlessServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final int BASE_X = 8200, BASE_Z = 8200, BUILD_Y = 80;

    /** How far the ship's own pose may sit from the anchor it was assembled on. */
    private static final double POSE_TOLERANCE = 64.0;

    @Test
    public void anImmediateLoadOfAPermanentlyLoadedShipDoesNotKillTheServer() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        buildShip();

        // The enabling condition: registered, with nothing loaded behind it. Without it the immediate
        // load and the background pass cannot both want this ship, and the test measures nothing.
        assertTrue("the ship never unloaded, so nothing here could ask for it twice: " + counters(),
                waitUntilNoShipIsAt(BASE_X, BUILD_Y));
        assertTrue("the ship left the registry as well as the loaded set, so there is nothing to load: "
                + counters(), queryableShips() >= 1);

        // Now both wanters exist: permanent loading makes the world's pass queue a background load every
        // tick this ship is unloaded, and the explicit request queues the immediate one.
        exec("artest vs permaload true");
        exec("artest vs load-ships 0");
        settle();

        assertTrue("asking for an immediate load of a ship the world was already loading in the "
                        + "background took the dedicated server down. A satisfied request - the ship is "
                        + "loaded, which is what both wanted - is being raised as an exception out of the "
                        + "world tick, and nothing above it catches.",
                client().isAlive());

        assertTrue("the server survived, but the ship was never loaded, so its survival says nothing "
                        + "about serving two load requests at once: " + counters(), waitUntilLoaded());
    }

    // --- arrangement --------------------------------------------------------------------------------

    private void buildShip() throws Exception {
        clearArea(BASE_X, BUILD_Y);
        int registryBefore = queryableShips();
        String coords = placeFixture(BASE_X, BUILD_Y, BASE_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the ship never entered the registry: " + counters(),
                waitUntilRegistryExceeds(registryBefore));
    }

    // --- observation --------------------------------------------------------------------------------

    private int loadedShips() throws Exception {
        return extractInt(exec("artest vs ship-count 0"), "count");
    }

    private int queryableShips() throws Exception {
        return extractInt(exec("artest vs ship-count-all 0"), "count");
    }

    private String counters() throws Exception {
        return "[loaded=" + loadedShips() + " registry=" + queryableShips() + "]";
    }

    /** The probe's ship lookup is unbounded, so the pose comparison is what makes this about THIS spot. */
    private boolean shipIsAt(int x, int y) throws Exception {
        String info = exec("artest vs ship-info 0 " + x + " " + y + " " + BASE_Z);
        if (!info.contains("\"managed\":true")) {
            return false;
        }
        double dx = extractDouble(info, "posX") - x;
        double dy = extractDouble(info, "posY") - y;
        double dz = extractDouble(info, "posZ") - BASE_Z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= POSE_TOLERANCE;
    }

    private boolean waitUntilRegistryExceeds(int floor) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (queryableShips() > floor) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    /** Poll until no loaded ship sits at {@code (x,y,BASE_Z)}, deliberately without pumping any load. */
    private boolean waitUntilNoShipIsAt(int x, int y) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (!shipIsAt(x, y)) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    /** Poll until the loaded set holds a ship. Permanent loading is already on, so nothing is re-pumped. */
    private boolean waitUntilLoaded() throws Exception {
        for (int i = 0; i < 40; i++) {
            if (loadedShips() >= 1) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    /** A bounded pause for the world ticks that serve the two queued loads. */
    private void settle() throws Exception {
        Thread.sleep(3000);
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private void clearArea(int baseX, int baseY) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (BASE_Z - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (BASE_Z + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (baseY - 2) + " " + (BASE_Z - 4)
                + " " + (baseX + 20) + " " + (baseY + 12) + " " + (BASE_Z + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }
}
