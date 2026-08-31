package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A beam a mirror turned is DRAWN turned, and not straight through the mirror that turned it.
 *
 * <p>The server resolves a bent beam as a path with a corner in it. Everything about that is
 * invisible from the client's side unless the corner crosses the wire: a beam sent as two ends is
 * still one beam, still drawn, still counted — and drawn as a laser passing clean through a plate
 * that is, in fact, reflecting it. So the beam COUNT cannot see this, which is exactly why it is a
 * separate scenario and a separate observable.</p>
 *
 * <p>The control leg is the ordinary beam. A gun burning into an iron wall must be drawn with NO
 * corner: without that half, a bug that reported every beam as bent would pass this file.</p>
 */
public class ABentBeamIsDrawnBentE2ETest extends AbstractClientE2ETest {

    private static final String TRACKER = "zmaster587.advancedRocketry.client.ClientBeamTracker";

    private static final int DIM = 0;
    private static final int Y = 84, Z = 420;
    private static final int GUN_X = 700;
    private static final int TARGET_X = GUN_X + 20;

    /** The mount has to swing onto the target before anything lights. */
    private static final long LIGHT_TIMEOUT_MS = 45_000L;

    @Test
    public void aBeamTurnedByAMirrorReachesTheClientWithItsCornerInIt() throws Exception {
        buildBeamGun();

        // Leg one, the control: plain iron. A beam that meets it is a straight line to its end.
        exec("artest fill " + DIM + " " + TARGET_X + " " + Y + " " + Z + " " + (TARGET_X + 5) + " "
                + Y + " " + Z + " minecraft:iron_block");
        aimAtTarget();
        serverClient().execute("tp @a " + (GUN_X + 4) + ".5 " + (Y + 1) + " " + (Z + 0.5D));
        bot().waitTicks(20);

        assertTrue("the beam never reached the client at all, so nothing here measured how it is "
                + "drawn: " + read(), await(1, false));
        assertEquals("a beam burning into a plain iron wall was drawn with a corner in it. Nothing "
                + "turned it, so either the server invented a bend or the client is calling every "
                + "beam bent — and this file's real assertion would then pass for that reason "
                + "rather than for the mirror", 0, bentBeams());

        // Leg two: swap the iron the beam is standing on for a mirror. Same gun, same aim, same
        // distance — the ONE thing that changes is what the beam meets.
        exec("artest fill " + DIM + " " + TARGET_X + " " + Y + " " + Z + " " + (TARGET_X + 5) + " "
                + Y + " " + Z + " minecraft:air");
        place("advancedrocketry:mirrorPlatingGold", TARGET_X, Y, Z);
        place("minecraft:iron_block", TARGET_X + 1, Y, Z);

        assertTrue("with a mirror in the beam's way the client is still drawing a straight line: "
                + "the corner never crossed the wire, so a player watching this sees a laser going "
                + "clean through a plate that is reflecting it. " + read(), await(1, true));
    }

    // ---- building

    private void buildBeamGun() throws Exception {
        exec("artest chunk warmup " + DIM + " " + ((GUN_X - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((GUN_X + 48) >> 4) + " " + ((Z + 16) >> 4));
        exec("artest fill " + DIM + " " + (GUN_X - 8) + " " + (Y - 2) + " " + (Z - 4) + " "
                + (GUN_X + 40) + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air");
        for (int cx = ((GUN_X - 16) >> 4); cx <= ((GUN_X + 40) >> 4); cx++) {
            exec("artest chunk forceload " + DIM + " " + cx + " " + (Z >> 4));
        }
        place("advancedrocketry:turret", GUN_X, Y, Z);
        for (int i = 1; i <= 3; i++) {
            place("advancedrocketry:gunBeamEmitter", GUN_X, Y + i, Z);
        }
        place("advancedrocketry:gunCooling", GUN_X, Y, Z + 1);
        place("advancedrocketry:gunCooling", GUN_X, Y, Z - 1);
    }

    private void aimAtTarget() throws Exception {
        exec("artest turret target " + DIM + " " + GUN_X + " " + Y + " " + Z + " "
                + (TARGET_X + 0.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));
    }

    // ---- reading

    /**
     * Keep the gun fed until the client is drawing at least {@code beams}, and — when asked — until
     * one of them is bent. Feeding is arrangement, not subject: an unfed gun burns its buffer down
     * and goes dark to save up, and what is being watched here is the packet.
     */
    private boolean await(int beams, boolean bent) throws Exception {
        long deadline = System.currentTimeMillis() + LIGHT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            exec("artest turret charge " + DIM + " " + GUN_X + " " + Y + " " + Z);
            bot().waitTicks(10);
            if (trackedBeams() >= beams && (!bent || bentBeams() >= 1)) {
                return true;
            }
        }
        return false;
    }

    private int trackedBeams() throws Exception {
        return Integer.parseInt(bot().invokeStaticInt(TRACKER, "count").get("returned").getAsString());
    }

    private int bentBeams() throws Exception {
        return Integer.parseInt(
                bot().invokeStaticInt(TRACKER, "bentCount").get("returned").getAsString());
    }

    private String read() throws Exception {
        return exec("artest turret read " + DIM + " " + GUN_X + " " + Y + " " + Z);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + resp, resp.contains("\"placed\":true"));
    }

    private String exec(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }
}
