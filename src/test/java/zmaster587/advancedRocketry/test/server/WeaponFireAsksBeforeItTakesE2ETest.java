package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Two promises a server owner has to be able to rely on, neither of which the mechanic made on its
 * own.
 *
 * <h3>A weapon asks before it takes a block</h3>
 * <p>Every protection system on this version - claims, regions, an admin's own listener - works by
 * cancelling a block-break event. A weapon that removed blocks directly was invisible to all of
 * them, so a turret was a way around the claim system rather than a weapon in it. What is pinned
 * here is the refusal: a guarded block that is fired on keeps standing, and the same block
 * unguarded does not.</p>
 *
 * <h3>An off switch ends what is in the air</h3>
 * <p>The shot registry is world-saved data. A switch that stopped stepping rounds without ending
 * them left them in the save, to resume whenever it was switched back on - a pause wearing the name
 * of an off switch.</p>
 */
public class WeaponFireAsksBeforeItTakesE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int X = 9800, Y = 82, Z = 9800;

    @Test
    public void aGuardedBlockSurvivesTheHitThatTakesTheUnguardedOneBesideIt() throws Exception {
        prepare();

        // Two identical blocks, one of them spoken for.
        place(X, "minecraft:stone");
        place(X + 4, "minecraft:stone");
        String guarded = exec("artest damage guard " + DIM + " " + X + " " + Y + " " + Z + " true");
        assertTrue("the veto listener refused to take the position: " + guarded,
                guarded.contains("\"ok\":true"));

        // The same energy into each, straight down the middle of the block.
        shoot(X);
        shoot(X + 4);
        Thread.sleep(1_500L);

        String control = exec("artest damage stage " + DIM + " " + (X + 4) + " " + Y + " " + Z);
        assertTrue("the UNGUARDED block survived the shot, so this run says nothing about the"
                + " guarded one: " + control, gone(control));

        String subject = exec("artest damage stage " + DIM + " " + X + " " + Y + " " + Z);
        assertTrue("a guarded block was destroyed by weapon fire anyway: every claim, region and"
                + " spawn protection on the server is bypassed by building a turret: " + subject,
                !gone(subject));

        exec("artest damage unguard-all");
    }

    @Test
    public void switchingTheSubstrateOffEndsTheRoundsAlreadyInTheAir() throws Exception {
        prepare();
        try {
            // Straight up, with a long life: it will still be flying when the switch is thrown.
            String fired = exec("artest shot fire " + DIM + " " + (X + 20) + " " + Y + " " + Z
                    + " 0 4 0 2000 400");
            long id = readLong(fired, "id");
            assertTrue("the launch was refused, so there is nothing in the air to end: " + fired,
                    id >= 0L);
            String inAir = exec("artest shot read " + DIM + " " + id);
            assertTrue("the round was not in the air a tick after it was fired: " + inAir,
                    inAir.contains("\"present\":true"));

            exec("artest config set enableProjectileSubstrate false");
            Thread.sleep(1_000L);

            String after = exec("artest shot read " + DIM + " " + id);
            assertTrue("a round left in the air when the substrate was switched off is still in the"
                    + " registry: the switch suspends the mechanic instead of ending it, and the"
                    + " round is written back into the save on every tick that follows: " + after,
                    after.contains("\"present\":false"));
            assertTrue("the round ended, but for the wrong reason - it should say the substrate was"
                    + " switched off under it: " + after, after.contains("SUBSTRATE_DISABLED"));
        } finally {
            exec("artest config set enableProjectileSubstrate true");
        }
    }

    // ---- driving

    private void prepare() throws Exception {
        exec("artest chunk warmup " + DIM + " " + ((X - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((X + 32) >> 4) + " " + ((Z + 16) >> 4));
        exec("artest fill " + DIM + " " + (X - 2) + " " + (Y - 2) + " " + (Z - 2) + " " + (X + 30)
                + " " + (Y + 6) + " " + (Z + 2) + " minecraft:air");
        for (int cx = ((X - 16) >> 4); cx <= ((X + 32) >> 4); cx++) {
            exec("artest chunk forceload " + DIM + " " + cx + " " + (Z >> 4));
        }
    }

    private void place(int x, String block) throws Exception {
        exec("artest fill " + DIM + " " + x + " " + Y + " " + Z + " " + x + " " + Y + " " + Z
                + " " + block);
    }

    /** A round with enough energy to take a stone block out in one arrival, fired from close range. */
    private void shoot(int targetX) throws Exception {
        exec("artest shot fire " + DIM + " " + (targetX - 6) + " " + Y + " " + Z
                + " 4 0 0 2000000 200");
    }

    /** Has this position been emptied - destroyed outright, or recorded as destroyed? */
    private static boolean gone(String stageJson) {
        return stageJson.contains("\"wasDestroyed\":true")
                || stageJson.contains("\"block\":\"minecraft:air\"");
    }

    private static long readLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    private String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }
}
