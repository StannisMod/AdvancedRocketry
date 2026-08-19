package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * One round, one fresh wall, and it marks it.
 *
 * <h3>Written as a reproduction, kept as a pin</h3>
 * <p>This class was written to fail. Another scenario had a round fly through a stone wall with its
 * budget untouched, and the trigger looked like "the FIRST round fired into a freshly prepared
 * arrangement". Three versions of this test were built to reproduce that, each closer to the sequence
 * it was seen in — a single lane; two lanes prepared and built; and a preceding scenario firing down a
 * third lane and left in the air. <b>All three passed.</b> So the characterisation was wrong, and it is
 * recorded here rather than quietly dropped.</p>
 *
 * <p>What it leaves behind is worth keeping anyway: the plain property that a round fired once into a
 * wall it flies down the middle of comes out having marked it. Nothing else pins that on its own — the
 * shot suites all fire more than once, and the armour column test deliberately discards a round first.
 * If this ever goes red, the thing it was written to catch has finally come out into the open.</p>
 *
 * <h3>What IS established about the defect, and where</h3>
 * <p>The damage side is sound (a hand-fired impact at the same point bores four blocks deep), it does
 * not follow the impact kind (swapping lanes swaps which round sails through), it is not the lane and
 * not chunk loading. It reproduces only inside `ArmourAnswersByKindAndAngleE2ETest`'s own sequence,
 * which is why that class discards a round before the pair it measures — the reason is written there,
 * and the defect is carried in the bug ledger with everything ruled out so far.</p>
 */
public class FirstShotIntoAFreshWallE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    /** A lane of this class's own, so nothing else has fired down it. */
    private static final int X = 2400, Y = 70, Z = 1400;
    /**
     * A SECOND arrangement, prepared and built after the first and never fired into. It is part of the
     * reproduction rather than scenery: with only one lane prepared the round bores normally, and the
     * minimal version of this test went green. Whatever the first shot fails to see, it takes a second
     * arrangement built after the first to bring it out.
     */
    private static final int Z2 = 1420;
    /** The preceding scenario's lane — fired down and abandoned, never measured. */
    private static final int Z3 = 1440;
    private static final int WALL_DEPTH = 10;

    private static final Pattern ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern STAGE = Pattern.compile("\"stage\":(-?\\d+)");
    private static final Pattern STAGE_COST = Pattern.compile("\"stageCost\":(-?\\d+)");
    private static final Pattern MAX_STAGE = Pattern.compile("\"maxStage\":(-?\\d+)");

    @Test
    public void theFirstRoundFiredIntoAFreshWallDamagesIt() throws Exception {
        // The exact sequence the defect was found in: clear what is in the air, build the target, fire
        // ONE round. Nothing here is unusual — it is what any scenario does first.
        // A PRECEDING scenario, because two standalone versions of this test went green without one:
        // rounds fired down another lane and left to fly, exactly as the scenario before the failing
        // one does. Whatever the round below fails to see, it takes an earlier scenario to bring out.
        exec("artest fill " + DIM + " " + (X - 8) + " " + (Y - 2) + " " + (Z3 - 3) + " "
                + (X + 40) + " " + (Y + 4) + " " + (Z3 + 3) + " minecraft:air");
        exec("artest fill " + DIM + " " + X + " " + Y + " " + Z3 + " " + (X + WALL_DEPTH - 1)
                + " " + Y + " " + Z3 + " minecraft:glass_pane");
        exec("artest shot fire " + DIM + " " + (X - 3.0D) + " " + (Y + 0.5D) + " " + (Z3 + 0.5D)
                + " 0.45 0 0 3000 1200 KINETIC 0.25 1.0");
        Thread.sleep(1500L);

        exec("artest shot clear " + DIM);
        for (int cx = (X - 16) >> 4; cx <= (X + 32) >> 4; cx++) {
            exec("artest chunk forceload " + DIM + " " + cx + " " + (Z >> 4));
        }
        assertTrue("could not clear the lane", exec("artest fill " + DIM + " " + (X - 8) + " "
                + (Y - 2) + " " + (Z - 3) + " " + (X + 40) + " " + (Y + 4) + " " + (Z + 3)
                + " minecraft:air").contains("\"ok\":true"));
        assertTrue("could not clear the second lane", exec("artest fill " + DIM + " " + (X - 8) + " "
                + (Y - 2) + " " + (Z2 - 3) + " " + (X + 40) + " " + (Y + 4) + " " + (Z2 + 3)
                + " minecraft:air").contains("\"ok\":true"));
        assertTrue("could not build the wall", exec("artest fill " + DIM + " " + X + " " + Y + " " + Z
                + " " + (X + WALL_DEPTH - 1) + " " + Y + " " + Z + " minecraft:stone")
                .contains("\"ok\":true"));
        assertTrue("could not build the second wall", exec("artest fill " + DIM + " " + X + " " + Y
                + " " + Z2 + " " + (X + WALL_DEPTH - 1) + " " + Y + " " + Z2 + " minecraft:stone")
                .contains("\"ok\":true"));

        // Priced off the wall itself: rich enough that failing to mark it cannot be a budget story.
        String priced = stageAt(X);
        Matcher cost = STAGE_COST.matcher(priced);
        Matcher stages = MAX_STAGE.matcher(priced);
        assertTrue("the wall has no price, so nothing below means anything",
                cost.find() && stages.find());
        int budget = Integer.parseInt(cost.group(1))
                * Math.max(1, Integer.parseInt(stages.group(1))) * 20;

        long id = idOf(exec("artest shot fire " + DIM + " " + (X - 3.0D) + " " + (Y + 0.5D) + " "
                + (Z + 0.5D) + " 0.45 0 0 " + budget + " 1200 KINETIC 0.25 1.0"));
        assertTrue("the substrate refused the shot", id >= 0);

        // Wait until it is either gone or well past the far side of the wall.
        long deadline = System.currentTimeMillis() + 25_000L;
        String state = read(id);
        while (System.currentTimeMillis() < deadline && state.contains("\"present\":true")
                && xOf(state) < X + WALL_DEPTH + 4) {
            Thread.sleep(100L);
            state = read(id);
        }

        int depth = 0;
        for (int i = 0; i < WALL_DEPTH; i++) {
            if (stageOf(stageAt(X + i)) > 0 || stageAt(X + i).contains("\"block\":\"minecraft:air\"")) {
                depth = i + 1;
            }
        }
        assertTrue("a round fired once into a wall it flew down the middle of came out the far side"
                + " having marked nothing. Its budget is untouched, so it is not a question of price:"
                + " the wall was never seen. budget=" + budget + " wall=" + stageAt(X)
                + " round=" + state, depth > 0);
    }

    private String stageAt(int x) throws Exception {
        return exec("artest damage stage " + DIM + " " + x + " " + Y + " " + Z);
    }

    private String read(long id) throws Exception {
        return exec("artest shot read " + DIM + " " + id);
    }

    private static int stageOf(String json) {
        Matcher m = STAGE.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static double xOf(String json) {
        Matcher m = Pattern.compile("\"x\":(-?[\\d.eE+-]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NEGATIVE_INFINITY;
    }

    private static long idOf(String json) {
        Matcher m = ID.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    private static String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }
}
