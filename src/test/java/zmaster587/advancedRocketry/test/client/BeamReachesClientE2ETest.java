package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Whether a burning beam is visible to the player standing next to the gun.
 *
 * <h3>Why this has to be a client test</h3>
 * <p>A held beam is not an entity, not a block and not a particle: it is a line the server resolves
 * every tick and forgets. Vanilla replicates none of that, so "you can see the gun burning" is
 * entirely a claim about a packet channel, and the only place that claim can be checked is a real
 * client. What is asserted is what the renderer draws FROM — the client's own tracker of burning
 * beams — because a renderer's output cannot be read from a test. Whether it LOOKS like a laser
 * stays a human's judgement.</p>
 *
 * <p>The control is the half that makes it worth running: a beam burning four kilometres away must
 * NOT arrive, or the filter that keeps every battery in the world off every connection is doing
 * nothing.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class BeamReachesClientE2ETest extends AbstractClientE2ETest {

    private static final String TRACKER = "zmaster587.advancedRocketry.client.ClientBeamTracker";

    private static final int DIM = 0;
    private static final int Y = 84, Z = 300;
    /** Where the player stands, and where the gun they should be able to see is built. */
    private static final int NEAR_X = 300;
    /** Comfortably outside the default 256-block visibility radius. */
    private static final int FAR_X = NEAR_X + 4_000;

    /** How long a beam may take to light: the mount has to swing onto the target first. */
    private static final long LIGHT_TIMEOUT_MS = 45_000L;

    @Test
    public void aBeamBurningNearbyIsDrawnByTheClientAndOneFourKilometresAwayIsNot() throws Exception {
        // The control first, and it has to be first: the near gun keeps burning once it is lit, so
        // "the client is drawing nothing" is only askable while the far gun is the only one alight.
        buildBeamGun(FAR_X);
        aimAlongTheWall(FAR_X);
        for (int i = 0; i < 4; i++) {
            charge(FAR_X);
            bot().waitTicks(20);
        }
        assertTrue("the far gun never burned at all, so this run proved nothing about the filter: "
                + read(FAR_X), everBurned(FAR_X));
        assertEquals("a beam burning four kilometres away was replicated to this client anyway — the"
                + " visibility filter is not filtering, and every gun in the world would be drawn on"
                + " every connection", 0, trackedBeams());

        // Now the one the player is standing beside.
        buildBeamGun(NEAR_X);
        aimAlongTheWall(NEAR_X);
        serverClient().execute("tp @a " + (NEAR_X + 4) + ".5 " + (Y + 1) + " " + (Z + 0.5D));
        bot().waitTicks(20);

        int drawn = 0;
        long deadline = System.currentTimeMillis() + LIGHT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && drawn == 0) {
            // Kept fed while we wait: a gun with no supply burns its buffer down and goes dark to
            // save up, and the point here is the packet, not the duty cycle.
            charge(NEAR_X);
            bot().waitTicks(10);
            drawn = trackedBeams();
        }
        assertTrue("a beam burning twelve blocks from the player never reached the client: a held"
                + " beam is a server-side line, so a client that is not told about one cannot draw"
                + " it and the weapon fires invisibly. gun=" + read(NEAR_X), drawn >= 1);
    }

    // ---- building

    /** The reference beam gun: a controller with emitters on it and cooling around it. */
    private void buildBeamGun(int bx) throws Exception {
        exec("artest chunk warmup " + DIM + " " + ((bx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((bx + 48) >> 4) + " " + ((Z + 16) >> 4));
        exec("artest fill " + DIM + " " + (bx - 4) + " " + (Y - 2) + " " + (Z - 4) + " "
                + (bx + 40) + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air");
        for (int cx = ((bx - 16) >> 4); cx <= ((bx + 40) >> 4); cx++) {
            exec("artest chunk forceload " + DIM + " " + cx + " " + (Z >> 4));
        }
        place("advancedrocketry:turret", bx, Y, Z);
        for (int i = 1; i <= 3; i++) {
            place("advancedrocketry:gunBeamEmitter", bx, Y + i, Z);
        }
        place("advancedrocketry:gunCooling", bx, Y, Z + 1);
        place("advancedrocketry:gunCooling", bx, Y, Z - 1);
    }

    /** Something to burn into, and an order to burn into it. */
    private void aimAlongTheWall(int bx) throws Exception {
        int wallX = bx + 20;
        exec("artest fill " + DIM + " " + wallX + " " + Y + " " + Z + " " + (wallX + 5) + " " + Y
                + " " + Z + " minecraft:iron_block");
        exec("artest turret target " + DIM + " " + bx + " " + Y + " " + Z + " " + (wallX + 0.5D)
                + " " + (Y + 0.5D) + " " + (Z + 0.5D));
    }

    // ---- reading

    private int trackedBeams() throws Exception {
        return Integer.parseInt(bot().invokeStaticInt(TRACKER, "count").get("returned").getAsString());
    }

    /**
     * Has this gun ever actually landed a tick of beam? A gun that never fired would make the
     * control pass for the wrong reason — nothing to replicate is not the same as replication
     * refusing to carry it.
     */
    private boolean everBurned(int bx) throws Exception {
        String state = read(bx);
        return state.contains("\"beamLit\":true") || !state.contains("\"shots\":0");
    }

    private String read(int bx) throws Exception {
        return exec("artest turret read " + DIM + " " + bx + " " + Y + " " + Z);
    }

    private void charge(int bx) throws Exception {
        exec("artest turret charge " + DIM + " " + bx + " " + Y + " " + Z);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + resp, resp.contains("\"placed\":true"));
    }

    private String exec(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }
}
