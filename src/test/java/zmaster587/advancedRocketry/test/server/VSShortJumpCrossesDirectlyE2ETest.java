package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.DIRECT_JUMP_SPEED;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;

/**
 * E2E: a jump short enough to have no cruise moves a real VS ship between two cells in ONE crossing.
 *
 * <p>The arrival acceptance here is deliberately the SAME body for both mechanisms
 * ({@link #arrivesInTheTargetCell}), run once at a speed that selects the direct crossing and once at a
 * speed that selects a hyperspace flight. Two mechanisms with two copies of "did it arrive" drift apart
 * within weeks, and the copy that stops being maintained is the one whose mechanism nobody is changing
 * — which is the one that will break silently.</p>
 *
 * <p>What is asserted about the direct path beyond arriving: it never reports a flight in progress.
 * That is the whole claim — no lane, no park, no mid-flight for a restart to resume — and it is read
 * off the probe's own {@code inTransit}/{@code crossing} pair rather than off how long anything
 * took.</p>
 */
public class VSShortJumpCrossesDirectlyE2ETest extends AbstractSharedServerTest {

    /** Probe-driven ticks a crossing or a flight gets to complete before the test calls it stuck. */
    private static final int TICK_POLLS = 80;

    @Test
    public void aShortJumpArrivesWithoutEverBeingInFlight() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server", serverHasVs());
        exec("artest vs permaload true");

        String setup = setUpPilotedShip();
        int originDim = extractInt(setup, "originDim");

        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 "
                + DIRECT_JUMP_SPEED);
        assertTrue("the short jump must begin: " + begin, begin.contains("\"began\":true"));
        assertEquals("a direct crossing is not a flight — nothing may be in transit the moment it "
                        + "starts, because there is no flight to be in the middle of: " + begin,
                0, extractInt(begin, "inTransit"));

        String lastTick = arrivesInTheTargetCell();
        assertEquals("and nothing was ever in transit while it settled: " + lastTick,
                0, extractInt(lastTick, "inTransit"));
    }

    /**
     * The control leg, and it is not decoration: it is what makes the assertion above mean "the SPEED
     * chose this" rather than "this fixture always does this". Same ship, same cells, same acceptance —
     * only the drive is slower, and the jump becomes a flight with a lane under it.
     */
    @Test
    public void theSameJumpFlownSlowlyStillGoesThroughHyperspace() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server", serverHasVs());
        exec("artest vs permaload true");

        String setup = setUpPilotedShip();
        int originDim = extractInt(setup, "originDim");

        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 "
                + HYPERSPACE_JUMP_SPEED);
        assertTrue("the jump must begin: " + begin, begin.contains("\"began\":true"));
        assertEquals("a slow jump IS a flight, and reports one: " + begin,
                1, extractInt(begin, "inTransit"));

        arrivesInTheTargetCell();
    }

    /**
     * The shared acceptance: tick until the jump is over, then require the ship to be VS-managed at the
     * target cell's pose. Returns the last tick reply so a caller can assert on the mechanism too.
     */
    private String arrivesInTheTargetCell() throws Exception {
        int targetDim = -1;
        String lastTick = "";
        for (int i = 0; i < TICK_POLLS && targetDim < 0; i++) {
            lastTick = exec("artest space transit-tick 10");
            if (extractInt(lastTick, "inTransit") == 0 && extractInt(lastTick, "crossing") == 0
                    && extractInt(lastTick, "targetDim") >= 0) {
                targetDim = extractInt(lastTick, "targetDim");
                break;
            }
            Thread.sleep(250);
        }
        assertTrue("the ship never reached the target cell; last tick=" + lastTick, targetDim >= 0);
        assertTrue("the ship never (re)loaded in the target cell (dim " + targetDim + "); countAll="
                + exec("artest vs ship-count-all " + targetDim), waitForLoadedShip(targetDim) >= 1);
        String dstInfo = exec("artest vs ship-info " + targetDim + " 0 200 0");
        assertTrue("the arrived ship is not VS-managed in the target cell: " + dstInfo,
                dstInfo.contains("\"managed\":true"));
        return lastTick;
    }

    private String setUpPilotedShip() throws Exception {
        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup failed: " + setup, setup.contains("\"ok\":true"));
        int originDim = extractInt(setup, "originDim");
        assertTrue("the fixture must mint a durable id — a crossing resolves its ship by identity, "
                + "never by the anchor every transit fixture shares: " + setup,
                setup.contains("\"durableId\":\"") && !setup.contains("\"durableId\":\"\""));
        assertTrue("origin ship never assembled/loaded in the pool-slot cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);
        return setup;
    }

    @org.junit.After
    public void resetPermaload() throws Exception {
        if (serverHasVs()) {
            exec("artest vs permaload false");
        }
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

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
