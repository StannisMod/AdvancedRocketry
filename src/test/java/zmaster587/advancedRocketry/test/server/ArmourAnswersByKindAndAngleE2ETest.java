package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * What a hull does about WHAT hit it and HOW, rather than only about how much.
 *
 * <p>Until now a block resisted with one number and every arrival paid it: a beam dug like a slug, and
 * a round skimming a steel plate at five degrees dug in exactly as one arriving square-on. Three
 * claims here, and each is an ordering rather than a quantity, because every number behind them is
 * balance and will move.</p>
 *
 * <ul>
 *   <li><b>Two columns.</b> Being boiled away costs far more per joule than being pushed through, so
 *       a beam buys much less depth than a slug carrying the same energy. That is not a nerf: it is
 *       what makes a laser buy precision and having nothing to reload instead of digging power.</li>
 *   <li><b>A price a faint beam cannot meet.</b> A stage is bought whole or not at all, so a beam
 *       carrying a slug's price for a block does not scratch it — and, keeping its energy rather
 *       than banking it, never will however long it is held.</li>
 *   <li><b>A graze skips off METAL.</b> And off metal only, so a player meets bouncing rounds where a
 *       player expects them and never off a plank wall.</li>
 * </ul>
 */
public class ArmourAnswersByKindAndAngleE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    /** A site of this class's own, clear of the other shot scenarios on this shared server. */
    private static final int Y = 70, X = 1700;
    /**
     * Every scenario gets its own LANE, and they are separated across the line of fire rather than
     * along it.
     *
     * <p>Down one lane they were not separate at all: a round rich enough to be interesting punches
     * through its own ten-block wall with budget in hand and flies on into the next scenario's wall
     * twenty blocks downrange — so "how deep did the beam get" was measured on a hole the SLUG made.
     * It passed for as long as the budgets were too small to leave the first wall, which is the worst
     * way for an arrangement to be wrong: silently, until the numbers get interesting.</p>
     */
    private static final int SLUG_Z = 1010, BEAM_Z = 1030, FAINT_Z = 1050, STRONG_Z = 1070;
    private static final int STEEL_Z = 1090, WOOD_Z = 1110;

    private static final int WALL_DEPTH = 10;
    private static final double BORE_SPEED = 0.45D;

    private static final Pattern ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern STAGE = Pattern.compile("\"stage\":(-?\\d+)");
    private static final Pattern STAGE_COST = Pattern.compile("\"stageCost\":(-?\\d+)");
    private static final Pattern MAX_STAGE = Pattern.compile("\"maxStage\":(-?\\d+)");

    /**
     * The same energy, the same body, the same wall — only the KIND differs, and the depths must not
     * be the same. Priced off the wall rather than asserted as a number: what is claimed is that
     * boiling material away costs more per joule than pushing through it, not by how much.
     */
    @Test
    public void aBeamBuysFarLessDepthThanASlugOfTheSameEnergy() throws Exception {
        prepare(SLUG_Z);
        prepare(BEAM_Z);
        buildWall(SLUG_Z);
        buildWall(BEAM_Z);

        // Rich enough that the beam is over the intensity threshold and genuinely drilling: below it
        // this test would go green for the threshold's reasons and say nothing about the columns.
        int budget = budgetForBlocks(SLUG_Z, 20.0D);
        assertTrue("the wall has no price, so no budget here means anything", budget > 0);
        // Read the two columns off an INTACT block, before anything is fired at it. Taken afterwards
        // the block is air, whose toughness is zero — and zero times any factor is zero, so both
        // columns agree there and the reading says nothing about either.
        String priced = stageAt(X, SLUG_Z);

        long slug = fire(SLUG_Z, budget, "KINETIC");
        long beam = fire(BEAM_Z, budget, "BEAM");
        assertTrue("both shots must be admitted or the comparison is about one of them",
                slug >= 0 && beam >= 0);
        awaitGone(slug);
        awaitGone(beam);

        int slugDepth = boreDepth(SLUG_Z);
        int beamDepth = boreDepth(BEAM_Z);
        assertTrue("the slug did not get into the wall at all, so the comparison is between two"
                + " zeroes", slugDepth > 0);
        assertTrue("a beam dug as deep as a slug carrying the same energy (beam=" + beamDepth
                + " slug=" + slugDepth + ", budget=" + budget + "): then the two channels are one"
                + " column and a laser is simply a better gun."
                + " intactPrice=" + priced,
                beamDepth < slugDepth);
    }

    /**
     * A beam carrying exactly what would destroy a block outright as a slug does not scratch it.
     *
     * <p>This is the two-column claim at its sharpest, and it is also where the intensity threshold
     * turns out to live already: a stage is bought whole or not at all, so a beam that cannot afford
     * one buys nothing this tick and — carrying its energy onward rather than banking it — nothing on
     * any later tick either. The faint laser that cuts a battleship if you hold it long enough cannot
     * happen, and it cannot happen because of the PRICE, not because of a separate gate.</p>
     */
    @Test
    public void aBeamCarryingABlocksWorthOfEnergyDoesNotScratchIt() throws Exception {
        prepare(FAINT_Z);
        buildWall(FAINT_Z);

        // Exactly one block's worth at the mechanical column — a slug with this much destroys it.
        int budget = budgetForBlocks(FAINT_Z, 1.0D);
        assertTrue("the wall has no price, so this budget means nothing", budget > 0);
        long id = fire(FAINT_Z, budget, "BEAM");
        assertTrue("the substrate refused the beam", id >= 0);
        awaitGone(id);

        assertTrue("a beam removed material with a slug's price for it (" + stageAt(X, FAINT_Z) + "):"
                + " then boiling a block away costs what pushing through it costs, the two channels"
                + " are one column, and a laser is simply a better gun",
                stageOf(stageAt(X, FAINT_Z)) == 0 && !destroyed(X, FAINT_Z));
    }

    /**
     * The intensity threshold, as the one thing the price does not do.
     *
     * <p>Two beams into identical walls, the same body, the same wall, differing only in how much
     * energy is behind that face. The weaker one could afford several stages — the price alone would
     * let it dig — and it removes nothing, because it is not INTENSE enough; the stronger one digs.
     * What makes this worth a test rather than a tuning note is where the weak beam's energy goes: it
     * is absorbed by the plate. Without the threshold that beam does not warm the hull, it passes
     * clean through it carrying everything it arrived with.</p>
     */
    @Test
    public void aBeamBelowTheIntensityThresholdRemovesNothingWhileAStrongerOneDigs() throws Exception {
        prepare(FAINT_Z);
        prepare(STRONG_Z);
        buildWall(FAINT_Z);
        buildWall(STRONG_Z);

        // Straddling the shipped threshold at the reference cross-section: both could afford stages
        // on price, so the only thing separating them is intensity.
        long faint = fire(FAINT_Z, 8_000, "BEAM");
        long strong = fire(STRONG_Z, 14_000, "BEAM");
        assertTrue("both beams must be admitted", faint >= 0 && strong >= 0);
        awaitGone(faint);
        awaitGone(strong);

        assertTrue("a beam below the intensity threshold removed material anyway ("
                + stageAt(X, FAINT_Z) + "): then the threshold is not a gate and a faint beam either digs"
                + " or, worse, passes clean through the plate with all it arrived with",
                stageOf(stageAt(X, FAINT_Z)) == 0 && !destroyed(X, FAINT_Z));
        assertTrue("the stronger beam removed nothing either (" + stageAt(X, STRONG_Z) + "): then this"
                + " run compared two refusals and the threshold is not where it was thought to be",
                stageOf(stageAt(X, STRONG_Z)) > 0 || destroyed(X, STRONG_Z));
    }

    /**
     * A graze skips off steel and digs into wood. Two plates, one angle, one round: the material is
     * the only difference, which is what makes this about the narrowing rather than about the angle.
     */
    @Test
    public void aGrazingRoundSkipsOffSteelAndDigsIntoWood() throws Exception {
        prepare(STEEL_Z);
        prepare(WOOD_Z);
        buildPlate(STEEL_Z, "minecraft:iron_block");
        buildPlate(WOOD_Z, "minecraft:planks");

        int budget = budgetForBlocks(WOOD_Z, 4.0D);
        assertTrue("the plate has no price, so no budget here means anything", budget > 0);

        // The evidence is the round TURNING, not the plate being unmarked: a plate is unmarked by a
        // round that missed it entirely, and that reading would pass against a substrate with no
        // ricochet in it at all. It arrives descending, so a bounce off the top face is the moment
        // its vertical velocity goes UP.
        long offSteel = grazeAt(STEEL_Z, budget);
        assertTrue("the steel shot was refused", offSteel >= 0);
        boolean steelTurned = awaitClimbing(offSteel);
        assertTrue("a round grazing a steel plate never turned — it dug in, and metal is the one"
                + " material a glancing hit is supposed to skip off: " + read(offSteel),
                steelTurned);
        assertTrue("the steel plate took damage from a round that skipped off it: "
                + firstTouched(STEEL_Z), firstTouched(STEEL_Z) == null);

        long intoWood = grazeAt(WOOD_Z, budget);
        assertTrue("the wood shot was refused", intoWood >= 0);
        boolean woodTurned = awaitClimbing(intoWood);
        assertTrue("the same round at the same angle skipped off WOOD: a plank wall must never bounce"
                + " a shell, or ricochet stops being where a player expects it: " + read(intoWood),
                !woodTurned);
        assertTrue("the round neither turned nor marked the wooden plate anywhere along it — then it"
                + " missed, and this run compared nothing", firstTouched(WOOD_Z) != null);
    }

    // ---- driving

    /** Straight down the X axis into the face of the wall: square-on, so nothing can ricochet. */
    private long fire(int lane, int energy, String kind) throws Exception {
        return idOf(exec("artest shot fire " + DIM + " " + (X - 3.0D) + " " + (Y + 0.5D) + " "
                + (lane + 0.5D) + " " + BORE_SPEED + " 0 0 " + energy + " 1200 " + kind + " 0.25 1.0"));
    }

    /**
     * A round arriving at a very shallow angle to the plate's top face: mostly along it, barely into
     * it. The plate is one block thick and the round comes in from above and beside.
     */
    private long grazeAt(int lane, int energy) throws Exception {
        return idOf(exec("artest shot fire " + DIM + " " + (X - 1.0D) + " " + (Y + 1.6D) + " "
                + (lane + 0.5D) + " 1.5 -0.2 0 " + energy + " 1200 KINETIC 0.25 1.0"));
    }

    private void buildWall(int lane) throws Exception {
        assertTrue("could not build the wall", exec("artest fill " + DIM + " " + X + " " + Y + " "
                + lane + " " + (X + WALL_DEPTH - 1) + " " + Y + " " + lane + " minecraft:stone")
                .contains("\"ok\":true"));
    }

    /** One block thick and long enough to be grazed along, with clear air above it. */
    private void buildPlate(int lane, String block) throws Exception {
        assertTrue("could not build the plate", exec("artest fill " + DIM + " " + X + " " + Y + " "
                + (lane - 1) + " " + (X + 8) + " " + Y + " " + (lane + 1) + " " + block)
                .contains("\"ok\":true"));
    }

    private void prepare(int lane) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup " + DIM + " "
                + ((X - 16) >> 4) + " " + ((lane - 16) >> 4) + " " + ((X + 60) >> 4) + " "
                + ((lane + 16) >> 4)).contains("\"ok\":true"));
        // Cleared far past the wall along the line of fire: a round that punches through must fly out
        // into empty air rather than into the next thing this class built.
        assertTrue("could not clear the site", exec("artest fill " + DIM + " " + (X - 8) + " "
                + (Y - 2) + " " + (lane - 3) + " " + (X + 60) + " " + (Y + 6) + " " + (lane + 3)
                + " minecraft:air").contains("\"ok\":true"));
    }

    // ---- reading

    private int boreDepth(int lane) throws Exception {
        int depth = 0;
        for (int i = 0; i < WALL_DEPTH; i++) {
            if (stageOf(stageAt(X + i, lane)) > 0 || destroyed(X + i, lane)) {
                depth = i + 1;
            }
        }
        return depth;
    }

    private int budgetForBlocks(int lane, double blocks) throws Exception {
        String state = stageAt(X, lane);
        Matcher cost = STAGE_COST.matcher(state);
        Matcher stages = MAX_STAGE.matcher(state);
        if (!cost.find() || !stages.find()) {
            return 0;
        }
        return (int) (Integer.parseInt(cost.group(1))
                * Math.max(1, Integer.parseInt(stages.group(1))) * blocks);
    }

    private String stageAt(int x, int lane) throws Exception {
        return exec("artest damage stage " + DIM + " " + x + " " + Y + " " + lane);
    }

    private boolean destroyed(int x, int lane) throws Exception {
        String state = stageAt(x, lane);
        return state.contains("\"wasDestroyed\":true") || state.contains("\"block\":\"minecraft:air\"");
    }

    private static int stageOf(String json) {
        Matcher m = STAGE.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static long idOf(String json) {
        Matcher m = ID.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /**
     * Did this round ever start CLIMBING? It is fired descending, so an upward vertical velocity can
     * only come from the plate turning it — which makes this the bounce itself rather than a proxy
     * for one. Answers false when the round ends without ever having climbed.
     */
    private boolean awaitClimbing(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 25_000L;
        while (System.currentTimeMillis() < deadline) {
            String state = read(id);
            if (!state.contains("\"present\":true")) {
                return false;
            }
            if (vyOf(state) > 1.0E-6D) {
                return true;
            }
            Thread.sleep(60L);
        }
        return false;
    }

    /** Anywhere along the plate, the first block that took something — or null if none did. */
    private String firstTouched(int lane) throws Exception {
        for (int x = X; x <= X + 8; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                String state = exec("artest damage stage " + DIM + " " + x + " " + Y + " " + (lane + dz));
                boolean gone = state.contains("\"wasDestroyed\":true")
                        || state.contains("\"block\":\"minecraft:air\"");
                if (gone || stageOf(state) > 0) {
                    return x + "," + (lane + dz) + " -> " + state;
                }
            }
        }
        return null;
    }

    private String read(long id) throws Exception {
        return exec("artest shot read " + DIM + " " + id);
    }

    private static double vyOf(String json) {
        Matcher m = Pattern.compile("\"vy\":(-?[\\d.eE+-]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0D;
    }

    private void awaitGone(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 25_000L;
        while (System.currentTimeMillis() < deadline
                && read(id).contains("\"present\":true")) {
            Thread.sleep(120L);
        }
    }

    private static String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }
}
