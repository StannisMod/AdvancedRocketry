package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertTrue;

/**
 * E2E: does the transit subsystem move a live VS ship between bubble cells? A ship assembled in a fresh
 * origin cell (pool slot world) departs into the shared hyperspace world, {@code ShipTransit} advances
 * its coordinate, and on arrival it crosses into a fresh target cell and re-VSes there. Proves the wiring
 * - the two per-ship crossings, the hyperspace hosting, and the origin&rarr;target refcount handoff -
 * composes in real, dynamically-registered worlds (not just the pure state machine's unit tests). Builds
 * on the proven per-ship crossing ({@code VSShipCrossingSpikeTest}) and on VS surviving in a pool-slot
 * world; the state machine itself is pinned deterministically by {@code ShipTransitManagerTest}.
 *
 * <p>Gated on the server's real VS presence (run with {@code -PwithVS}); skips cleanly otherwise.</p>
 */
public class VSShipTransitE2ETest extends AbstractSharedServerTest {

    @Test
    public void aVsShipTransitsFromOneCellToAnotherThroughHyperspace() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)", serverHasVs());

        // Headless: pin ships loaded so a freshly assembled ship does not auto-unload between probe calls.
        exec("artest vs permaload true");

        // Build a VS ship in a fresh origin cell (a pool slot world) + the whole transit stack.
        String setup = exec("artest space transit-setup");
        assertTrue("transit setup failed: " + setup, setup.contains("\"ok\":true"));
        int originDim = extractInt(setup, "originDim");
        int ax = extractInt(setup, "anchorX"), ay = extractInt(setup, "anchorY"), az = extractInt(setup, "anchorZ");

        // The origin ship must exist + load before we depart it (the departure crossing snapshots it).
        assertTrue("origin ship never assembled/loaded in the pool-slot cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        // Depart: begin the jump. The ship leaves the origin cell for hyperspace — at a speed that
        // makes it a real flight, because a fast enough jump is performed as a single crossing instead
        // and this test is about the hyperspace path. (This fixture could not take the other path
        // anyway: its bare cube has no flight computer, so it has no durable id to be crossed under.)
        String begin = exec("artest space transit-begin " + originDim + " " + ax + " " + ay + " " + az
                + " " + HYPERSPACE_JUMP_SPEED);
        assertTrue("transit did not begin (departure crossing failed): " + begin, begin.contains("\"began\":true"));

        // Advance the transit until it arrives (arrival retries while the async hyperspace ship assembles).
        int targetDim = -1;
        String lastTick = "";
        for (int i = 0; i < 80; i++) {
            lastTick = exec("artest space transit-tick 10");
            if (extractInt(lastTick, "inTransit") == 0) {
                targetDim = extractInt(lastTick, "targetDim");
                break;
            }
            Thread.sleep(250);
        }
        assertTrue("ship never arrived (still in transit after ~20 s); last tick=" + lastTick,
                targetDim >= 0);

        // The re-assembled ship must load + be VS-managed in the TARGET cell (arrival pastes near 0,200,0).
        assertTrue("transited ship never (re)loaded in the target cell (dim " + targetDim + "); countAll="
                + exec("artest vs ship-count-all " + targetDim), waitForLoadedShip(targetDim) >= 1);
        String dstInfo = exec("artest vs ship-info " + targetDim + " 0 200 0");
        assertTrue("arrived ship is not VS-managed in the target cell (transit did not re-VS): " + dstInfo,
                dstInfo.contains("\"managed\":true"));
    }

    @org.junit.After
    public void resetPermaload() throws Exception {
        if (serverHasVs()) {
            exec("artest vs permaload false");
        }
    }

    // --- helpers (mirror VSShipCrossingSpikeTest) ---------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    /** Poll for a loaded VS ship in {@code dim} (assembly is async; a headless server forces a load). */
    private int waitForLoadedShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (extractInt(exec("artest vs ship-count-all " + dim), "count") >= 1) {
                exec("artest vs load-ships " + dim);
                int loaded = extractInt(exec("artest vs ship-count " + dim), "count");
                if (loaded >= 1) {
                    return loaded;
                }
            }
            Thread.sleep(250);
        }
        return 0;
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
