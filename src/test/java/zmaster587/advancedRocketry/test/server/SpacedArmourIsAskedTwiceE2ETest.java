package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A round that comes out the far side of one plate is asked by the next one, in the SAME tick.
 *
 * <p>Spaced armour is the arrangement the reactive family exists for: two thin charges with air
 * between them stop more than one charge, because the second is asked only once the first is gone.
 * That claim was false for anything faster than a slow round. When a body passed through, the
 * substrate moved it the WHOLE of the tick's remaining travel in one step — not the distance the walk
 * had actually covered — so a plate standing inside that remaining travel was stepped straight over
 * and never consulted. The round arrived beyond it with its energy intact and the plate still
 * standing, unmarked and unspent.</p>
 *
 * <p>The two legs differ in ONE thing: how far the round can travel in a tick. Slow, the second plate
 * is inside the next tick's travel and gets its question either way; fast, it is inside THIS tick's,
 * which is the case the bug lived in. Without the slow leg a test could pass because nothing ever
 * reached the second plate at all.</p>
 */
public class SpacedArmourIsAskedTwiceE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 84;
    private static final int X = 11_200;
    /** Two lanes, so the two legs cannot inherit each other's spent charges. */
    private static final int SLOW_Z = 11_300, FAST_Z = 11_320;

    /** The gap: wide enough that the second plate is a separate meeting, not the same voxel. */
    private static final int SECOND_PLATE_OFFSET = 3;

    /**
     * Slow enough that one tick cannot span the gap; fast enough that another OVERSHOOTS it.
     *
     * <p>The fast number is not "large": it is chosen so that what is left of the tick after the
     * first plate lands the round well BEYOND the second one. A first attempt used 6, which — from
     * three blocks out, with the second plate three further on — left exactly enough travel to land
     * the round on the second plate's own voxel, where the next tick met it anyway. The test passed
     * with the fix removed. An overshoot has to be unambiguous or the experiment measures the
     * arithmetic and not the mechanic.</p>
     */
    private static final double SLOW = 0.45D;
    private static final double FAST = 12.0D;

    /** How far in front of the first plate a round is admitted. Short, so the fast leg overshoots. */
    private static final double MUZZLE_STANDOFF = 1.0D;

    private static final Pattern ID = Pattern.compile("\"id\":(-?\\d+)");

    /**
     * The claim, on the arrangement that makes it matter.
     *
     * <p>Both plates must be gone. A reactive charge removes itself when it swallows, so "gone" is
     * the plate's own report that it was asked; a plate still standing was stepped over.</p>
     */
    @Test
    public void aRoundThroughTheFirstPlateIsAskedByTheSecondHoweverFastItIsGoing() throws Exception {
        for (double speed : new double[]{SLOW, FAST}) {
            int lane = speed == SLOW ? SLOW_Z : FAST_Z;
            String leg = speed == SLOW ? "slow" : "fast";

            prepare(lane);
            place(X, lane, "advancedrocketry:reactivePlate");
            place(X + SECOND_PLATE_OFFSET, lane, "advancedrocketry:reactivePlate");

            long id = fire(lane, speed);
            assertTrue("the " + leg + " round was refused, so this leg measured nothing", id >= 0);
            awaitGone(id);

            assertTrue("the FIRST plate survived the " + leg + " round: nothing arrived at all, so"
                    + " this leg says nothing about the second one", gone(X, lane));
            assertTrue("the second plate is still standing after a " + leg + " round came through the"
                    + " first one. It was never asked: the round was advanced by the whole of the"
                    + " tick's remaining travel instead of by the distance the walk covered, and"
                    + " stepped clean over it — so spaced armour is one plate with a decoration"
                    + " behind it", gone(X + SECOND_PLATE_OFFSET, lane));
        }
    }

    // ---- driving

    /** Enough to spend both charges and still be moving: the subject is the QUESTION, not the budget. */
    private long fire(int lane, double speed) throws Exception {
        return idOf(exec("artest shot fire " + DIM + " " + (X - MUZZLE_STANDOFF) + " " + (Y + 0.5D) + " "
                + (lane + 0.5D) + " " + speed + " 0 0 200000 1200 KINETIC 0.25 1.0"));
    }

    private void place(int x, int lane, String block) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + lane + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + lane + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private void prepare(int lane) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup " + DIM + " " + ((X - 16) >> 4)
                + " " + ((lane - 16) >> 4) + " " + ((X + 40) >> 4) + " " + ((lane + 16) >> 4))
                .contains("\"ok\":true"));
        assertTrue("could not clear the lane", exec("artest fill " + DIM + " " + (X - 8) + " "
                + (Y - 2) + " " + (lane - 3) + " " + (X + 40) + " " + (Y + 4) + " " + (lane + 3)
                + " minecraft:air").contains("\"ok\":true"));
    }

    // ---- reading

    private void awaitGone(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline
                && exec("artest shot read " + DIM + " " + id).contains("\"present\":true")) {
            Thread.sleep(100L);
        }
    }

    /** A reactive charge that was asked spent itself, so the voxel it held is air. */
    private boolean gone(int x, int lane) throws Exception {
        return exec("artest damage stage " + DIM + " " + x + " " + Y + " " + lane)
                .contains("\"block\":\"minecraft:air\"");
    }

    private static long idOf(String json) {
        Matcher m = ID.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    private String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }
}
