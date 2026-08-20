package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A unit is TOLD what happened to it — including, and especially, when what happened killed it.
 *
 * <p>The stage a block carries answers <em>how broken am I</em> and survives everything. It cannot
 * answer <em>what just happened to me</em>: a shell and a collapsing hyperspace window leave the same
 * stage behind, and a unit that only ever reads its stage can never tell them apart. So the cause is
 * pushed once, at the moment it is true, and these are the claims that makes.</p>
 *
 * <p><b>The subject is a chest</b>, because a unit has to have a tile to hear anything and a chest is
 * the cheapest thing in the game that has one. What is being pinned is the DELIVERY, which is the same
 * for every unit; what a particular machine then DOES about being damaged is that machine's own and is
 * deliberately not here.</p>
 */
public class AUnitHearsWhatBrokeItE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 70, X = 2400;
    private static final int SURVIVES_Z = 1400, DIES_Z = 1420, DEAF_Z = 1440;

    private static final Pattern COUNT = Pattern.compile("\"count\":(\\d+)");
    private static final Pattern STAGE_COST = Pattern.compile("\"stageCost\":(-?\\d+)");
    private static final Pattern MAX_STAGE = Pattern.compile("\"maxStage\":(-?\\d+)");

    /**
     * A unit that survives hears about it, and what it hears names the cause, the severity and the
     * stage it moved to — not a verdict, because the consequence is the unit's own to compute.
     */
    @Test
    public void aUnitThatSurvivesIsToldWhatHappenedAndHowHard() throws Exception {
        prepare(SURVIVES_Z);
        place(SURVIVES_Z, "minecraft:chest");
        clearRecorder();

        // One stage's worth, priced off the block itself rather than written here: every cost in this
        // engine is tunable, so a budget picked by hand would pin the tuning.
        int oneStage = costOf(stage(SURVIVES_Z));
        assertTrue("the chest has no price, so no budget here means anything", oneStage > 0);
        impact(SURVIVES_Z, oneStage);

        String seen = occurrences();
        assertTrue("nothing was delivered to a unit that was just damaged: the stage moved and the "
                + "unit was never told, so a machine can only ever poll and can never react: " + seen,
                countOf(seen) > 0);
        assertTrue("the occurrence does not name what caused it: " + seen,
                seen.contains("\"cause\":\"IMPACT\"") && seen.contains("\"kind\":\"KINETIC\""));
        assertTrue("the occurrence carries no severity, so a unit cannot tell a scratch from a "
                + "near-miss-with-the-reactor: " + seen, seen.contains("\"spent\":") && spentOf(seen) > 0);
        assertTrue("the occurrence says the unit was destroyed when it is still standing: " + seen,
                seen.contains("\"destroyed\":false"));
        assertTrue("the occurrence carries no world, so a unit that wants to do anything about being "
                + "damaged has nothing to do it with: " + seen, seen.contains("\"hasWorld\":true"));
    }

    /**
     * <b>The one that matters.</b> A unit destroyed outright is still told — and it is told that it was
     * destroyed.
     *
     * <p>This is the occurrence a naive implementation loses: the block becomes air inside the damage
     * walk, so anything looking the unit up afterwards finds nothing and says nothing. It is also the
     * occurrence a unit most needs, because what a failing machine does about being killed is its own
     * business and differs by machine — a chemical engine goes like TNT, an ion engine merely ceases to
     * exist, a plasma engine is a tank letting go. A unit that never hears about its own death cannot
     * have one of those.</p>
     */
    @Test
    public void aUnitKilledOutrightIsStillToldThatItDied() throws Exception {
        prepare(DIES_Z);
        place(DIES_Z, "minecraft:chest");
        clearRecorder();

        // Enough for every stage at once, so the unit goes from pristine to gone in one blow.
        String probe = stage(DIES_Z);
        int all = costOf(probe) * Math.max(1, maxStageOf(probe)) * 4;
        impact(DIES_Z, all);

        assertTrue("the unit is still standing, so this run never tested a destruction at all",
                gone(DIES_Z));

        String seen = occurrences();
        assertTrue("a unit destroyed outright was told NOTHING: by the time anyone looks the block is "
                + "already air, and the blow that ends a unit is the one it most needs to hear about — "
                + "it is what a machine's own failure is made of: " + seen, countOf(seen) > 0);
        assertTrue("the unit was told, but not that it had DIED: then it cannot tell a dent from its "
                + "own destruction, and every failure mode collapses into one: " + seen,
                seen.contains("\"destroyed\":true"));
    }

    /**
     * A unit that is not listening is simply not told, and nothing about the damage changes. The
     * control: without it, every assertion above would also pass against a build that told EVERYTHING
     * to everyone, which is a different and much worse mechanism.
     */
    @Test
    public void aBlockThatIsNotAUnitIsSimplyNotTold() throws Exception {
        prepare(DEAF_Z);
        place(DEAF_Z, "minecraft:stone");
        clearRecorder();

        int oneStage = costOf(stage(DEAF_Z));
        assertTrue("the stone has no price, so no budget here means anything", oneStage > 0);
        String report = impact(DEAF_Z, oneStage);

        assertTrue("the impact did not land, so this control tested nothing: " + report,
                !report.contains("\"outcome\":\"NOTHING_STRUCK\""));
        String seen = occurrences();
        assertTrue("a plain block with no tile was handed an occurrence: then delivery is not opt-in "
                + "and every stone in the world is a listener: " + seen, countOf(seen) == 0);
    }

    // ---- driving

    private String impact(int lane, int budget) throws Exception {
        exec("artest damage clear-impacts");
        return exec("artest damage impact " + DIM + " " + (X - 2.5D) + " " + (Y + 0.5D) + " "
                + (lane + 0.5D) + " 1 0 0 " + budget + " KINETIC");
    }

    private void place(int lane, String block) throws Exception {
        String resp = exec("artest place " + DIM + " " + X + " " + Y + " " + lane + " " + block);
        assertTrue("failed to place " + block + ": " + resp, resp.contains("\"placed\":true"));
    }

    private void prepare(int lane) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup " + DIM + " " + ((X - 16) >> 4)
                + " " + ((lane - 16) >> 4) + " " + ((X + 16) >> 4) + " " + ((lane + 16) >> 4))
                .contains("\"ok\":true"));
        assertTrue("could not clear the lane", exec("artest fill " + DIM + " " + (X - 6) + " "
                + (Y - 1) + " " + (lane - 2) + " " + (X + 6) + " " + (Y + 2) + " " + (lane + 2)
                + " minecraft:air").contains("\"ok\":true"));
    }

    // ---- reading

    private void clearRecorder() throws Exception {
        exec("artest damage occurrences clear");
    }

    private String occurrences() throws Exception {
        return exec("artest damage occurrences");
    }

    private String stage(int lane) throws Exception {
        return exec("artest damage stage " + DIM + " " + X + " " + Y + " " + lane);
    }

    private boolean gone(int lane) throws Exception {
        String state = stage(lane);
        return state.contains("\"wasDestroyed\":true") || state.contains("\"block\":\"minecraft:air\"");
    }

    private static int costOf(String json) {
        Matcher m = STAGE_COST.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static int maxStageOf(String json) {
        Matcher m = MAX_STAGE.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static int countOf(String json) {
        Matcher m = COUNT.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static int spentOf(String json) {
        Matcher m = Pattern.compile("\"spent\":(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }
}
