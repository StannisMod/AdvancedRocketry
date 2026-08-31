package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Whether a fired round reaches the person it is fired near.
 *
 * <p>A shot is a server-side record: no entity, no chunk, nothing vanilla replicates on its own. So
 * "you can see the gun firing" is entirely a claim about a packet, and it is checkable on the real
 * client — the client's own tracker is asked how many rounds it is drawing, on the client thread,
 * after the server fired one. The control is the half that makes it worth running: a round fired far
 * enough away must NOT arrive, or the filter that keeps a battery off every connection in the world
 * is not doing anything.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class ShotReachesClientE2ETest extends AbstractClientE2ETest {

    private static final String TRACKER = "zmaster587.advancedRocketry.client.ClientShotTracker";

    /** Where the player stands for both halves. */
    private static final double PX = 8.5D, PY = 79.0D, PZ = 8.5D;

    /** Comfortably inside the default 256-block visibility radius. */
    private static final double NEAR = 40.0D;

    /** Comfortably outside it, and travelling further away. */
    private static final double FAR = 4_000.0D;

    @Test
    public void aRoundFiredNearbyIsDrawnByTheClientAndOneFiredFarAwayIsNot() throws Exception {
        serverClient().execute("tp @a " + PX + " " + PY + " " + PZ);
        bot().waitTicks(5);
        clearTracker();
        assertEquals("the client tracker did not start empty", 0, trackedShots());

        // Fired 40 blocks away, across the player's view. The launch is production's own entry
        // point — the same call a turret makes.
        String fired = String.join("\n", serverClient().execute("artest shot fire 0 "
                + (PX + NEAR) + " " + PY + " " + PZ + " 0 0 4 2000 200"));
        assertTrue("the launch was refused, so nothing else here means anything: " + fired,
                fired.contains("\"ok\":true"));

        int drawn = 0;
        for (int waited = 0; waited < 60 && drawn == 0; waited += 10) {
            bot().waitTicks(10);
            drawn = trackedShots();
        }
        assertTrue("a round fired 40 blocks from the player never reached the client: a shot is a"
                + " server record, so a client that is not told about one cannot draw it and the"
                + " turret fires invisibly", drawn >= 1);

        // The control. Without this the test would pass just as well against a replication layer
        // that told everybody about everything.
        clearTracker();
        String distant = String.join("\n", serverClient().execute("artest shot fire 0 "
                + (PX + FAR) + " " + PY + " " + (PZ + FAR) + " 4 0 4 2000 200"));
        assertTrue("the distant launch was refused: " + distant, distant.contains("\"ok\":true"));
        bot().waitTicks(40);
        assertEquals("a round fired four kilometres away was replicated to this client anyway —"
                + " the visibility filter is not filtering", 0, trackedShots());
    }

    private int trackedShots() throws Exception {
        return Integer.parseInt(bot().invokeStaticInt(TRACKER, "count")
                .get("returned").getAsString());
    }

    private void clearTracker() throws Exception {
        bot().invokeStaticInt(TRACKER, "clear");
    }
}
