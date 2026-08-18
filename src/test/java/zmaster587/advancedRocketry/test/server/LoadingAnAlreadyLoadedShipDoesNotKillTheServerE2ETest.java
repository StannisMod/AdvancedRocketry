package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Asking for a ship that is ALREADY loaded must be a no-op, never a dead server.
 *
 * <p><b>The defect this guards.</b> The physics mod's load pass asserts that nothing in its load
 * queue is already loaded, and raises {@code IllegalStateException("Tried loading a ShipData that was
 * already loaded?")} when one is. That throw leaves the world tick with nothing between it and the
 * server loop, so the whole dedicated server dies. Two loaded ships is not an error state — it is two
 * parties wanting the same thing, and both already have it.</p>
 *
 * <p><b>Why it happens in play.</b> A tier-2 assembly spawns its ship already loaded. If a player is
 * standing near the pad, the world's own proximity pass queues that very ship for a load on the next
 * tick, and the assertion fires. The existing crossing and load e2es never see it because they keep
 * their observer far away during the spawn; a human who builds where he stands cannot.</p>
 *
 * <p><b>Why this test does not use a player.</b> That road is not reachable from the server tier at
 * all. The headless fake player is deliberately never added to the world (a connectionless
 * {@code EntityPlayerMP} in the entity tracker sends metadata to itself and NPEs), so it is not in
 * {@code world.playerEntities} — which is the exact list the proximity pass walks. A test driven by a
 * real player standing near a pad is a CLIENT-tier test. What is reproduced here is the same
 * precondition by a different road: a load requested for a ship that is already loaded. The
 * assertion the physics mod makes is about that state and nothing else, so the state is what this
 * pins.</p>
 *
 * <p><b>The survival assertion comes first, and the still-loaded assertion is its control</b> — "the
 * server is up" passes trivially on a build where the load request did nothing at all, so the second
 * assertion is what proves the request was really served.</p>
 *
 * <p><b>Its own server, per method.</b> While the guard is absent this does not fail an assertion: it
 * kills the server process. On a shared harness that would take every later method with it and read
 * as several unrelated failures.</p>
 *
 * <p>Gated on the server's real physics-mod presence; skips cleanly otherwise.</p>
 */
public class LoadingAnAlreadyLoadedShipDoesNotKillTheServerE2ETest extends AbstractHeadlessServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final int BASE_X = 10200, BASE_Z = 10200, BUILD_Y = 80;

    @Test
    public void requestingALoadOfAShipThatIsAlreadyLoadedIsANoOp() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        // Permanently loaded, so the craft assembled below STAYS loaded: the whole point is to ask
        // for a load of something that already has one.
        exec("artest vs permaload true");
        buildShip();
        assertTrue("the craft never became a loaded ship, so there is nothing to ask for twice: "
                + counters(), waitUntilLoaded());

        // ARRANGEMENT, gated rather than assumed: this must request a load for a ship that IS loaded.
        // If the craft had unloaded in between, the request would be an ordinary load and the
        // assertion below would pass without the defect ever having been reachable.
        int loadedBefore = loadedShips();
        assertTrue("the craft must still be loaded at the moment the load is requested, or this "
                + "measures an ordinary load: " + counters(), loadedBefore >= 1);
        String requested = exec("artest vs load-ships 0");
        assertTrue("the load request itself failed, so nothing was asked of the load pass: " + requested,
                requested.contains("\"requested\":"));
        settle();

        assertTrue("asking for a load of a ship that was already loaded took the dedicated server "
                        + "down. A satisfied request - the ship is loaded, which is what the caller "
                        + "wanted - is being raised as an exception out of the world tick, and nothing "
                        + "above it catches. This is the crash a player triggers by assembling a tier-2 "
                        + "craft while standing next to the pad.",
                client().isAlive());

        assertTrue("the server survived, but the ship is no longer loaded, so its survival says "
                        + "nothing about serving a load request for an already-loaded ship: " + counters(),
                loadedShips() >= 1);
    }

    // --- arrangement --------------------------------------------------------------------------------

    private void buildShip() throws Exception {
        clearArea();
        int registryBefore = queryableShips();
        String coords = placeFixture("with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with the physics mod an AFC-bearing build must route to a ship (no rocket): " + asm,
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

    private boolean waitUntilRegistryExceeds(int floor) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (queryableShips() > floor) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private boolean waitUntilLoaded() throws Exception {
        for (int i = 0; i < 40; i++) {
            if (loadedShips() >= 1) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    /** A bounded pause for the world ticks that serve the queued load. */
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

    private void clearArea() throws Exception {
        int cx1 = (BASE_X - 4) >> 4, cz1 = (BASE_Z - 4) >> 4;
        int cx2 = (BASE_X + 20) >> 4, cz2 = (BASE_Z + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (BASE_X - 4) + " " + (BUILD_Y - 2) + " " + (BASE_Z - 4)
                + " " + (BASE_X + 20) + " " + (BUILD_Y + 12) + " " + (BASE_Z + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + BASE_X + " " + BUILD_Y + " " + BASE_Z + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
