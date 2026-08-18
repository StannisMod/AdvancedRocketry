package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A round does not finish its whole life in the tick it touches a hull.
 *
 * <p>Before this, meeting structure was terminal: one call resolved a bore up to sixty-four blocks
 * deep and the round ceased to exist at the surface. Now it <b>keeps being a round while it bores</b>
 * — it is still there next tick, deeper and worth less, until it either comes out the far side or
 * runs out inside. That is one claim and it is the one worth a server test, because nothing smaller
 * can exhibit it: it is a statement about what is true BETWEEN two ticks.</p>
 *
 * <p>The second claim is the only body ordering the penetration law makes on its own: at the same
 * energy, the narrower round goes deeper. Not a depth — an ordering, because the depth is balance.</p>
 */
public class ShotBoresOverTimeE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 70;
    private static final int Z = 870;
    private static final long TIMEOUT_MS = 25_000L;

    /** Slow on purpose: a round that crosses a block per tick cannot be caught in the middle of one. */
    private static final double BORE_SPEED = 0.45D;

    private static final Pattern PRESENT = Pattern.compile("\"present\":(true|false)");
    private static final Pattern ENERGY = Pattern.compile("\"energy\":(-?\\d+)");
    private static final Pattern ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern STAGE = Pattern.compile("\"stage\":(-?\\d+)");

    @Test
    public void aRoundKeepsBoringAcrossTicksInsteadOfEndingAtTheSurface() throws Exception {
        int wallX = 1400;
        prepare(wallX);
        buildWall(wallX, 10);

        // Sized from what a block of this wall actually costs, read off the probe rather than guessed:
        // the price comes from the toughness table, which is balance and will move, and a hard-coded
        // budget silently becomes "sails clean through" the day it does.
        int budget = budgetForBlocks(wallX, 3.5D);
        long id = fire(wallX - 3.5D, BORE_SPEED, budget, 0.25D);
        assertTrue("the substrate refused the shot, so there is nothing to observe: id=" + id, id >= 0);

        // The moment of contact: energy starts falling. The round must still EXIST at that moment —
        // this is the whole difference from the behaviour this replaces.
        String duringBore = awaitEnergyBelow(id, budget);
        assertTrue("the round ended in the tick it met the wall, which is exactly the behaviour"
                + " penetration-over-time replaces:\n" + duringBore, isPresent(duringBore));
        long energyInside = energyOf(duringBore);

        // ...and it is still spending, tick after tick, while it is in there.
        String later = awaitEnergyBelow(id, energyInside);
        assertTrue("the round stopped paying for its depth while still inside the wall — then it is"
                + " not boring, it is parked:\n" + later, energyOf(later) < energyInside);

        // It ends inside rather than sailing through: the wall is thicker than its budget.
        assertTrue("a round with a fraction of the budget the wall costs came out the other side:\n"
                + read(id), awaitGone(id));

        // And it left a bore, not a crater: the front of the wall is gone or damaged, and the far
        // side of it was never reached.
        assertTrue("the wall's front block is untouched, so the round never actually spent anything"
                + " into it: " + stageAt(wallX), stageOf(stageAt(wallX)) > 0 || destroyed(wallX));
        assertTrue("the round reached the far side of a wall it could not afford: " + stageAt(wallX + 9),
                stageOf(stageAt(wallX + 9)) == 0 && !destroyed(wallX + 9));
    }

    /**
     * The one body ordering the law makes by itself: energy buys depth against the material's
     * resistance ACROSS THE BODY'S FACE, so the same energy through a narrower round goes further.
     */
    @Test
    public void aNarrowerRoundOutrunsAWiderOneOnTheSameEnergy() throws Exception {
        int narrowX = 1440, wideX = 1470;
        prepare(narrowX);
        prepare(wideX);
        buildWall(narrowX, 10);
        buildWall(wideX, 10);

        int budget = budgetForBlocks(narrowX, 3.5D);
        long narrow = fire(narrowX - 3.5D, BORE_SPEED, budget, 0.25D);
        long wide = fire(wideX - 3.5D, BORE_SPEED, budget, 0.75D);
        assertTrue("both rounds must be admitted or the comparison is about one of them: " + narrow
                + " / " + wide, narrow >= 0 && wide >= 0);

        awaitGone(narrow);
        awaitGone(wide);

        int narrowDepth = boreDepth(narrowX);
        int wideDepth = boreDepth(wideX);
        assertTrue("the narrow round must bore at least as deep as the wide one on the same energy"
                + " (narrow=" + narrowDepth + " wide=" + wideDepth + "): the material resists across"
                + " the body's face, so a wider face buys less depth per unit of energy",
                narrowDepth > wideDepth);
        assertTrue("the narrow round did not get into the wall at all, so the comparison is between"
                + " two zeroes", narrowDepth > 0);
    }

    // ---- driving

    private long fire(double x, double speed, int energy, double radius) throws Exception {
        String resp = exec("artest shot fire " + DIM + " " + x + " " + (Y + 0.5D) + " " + (Z + 0.5D)
                + " " + speed + " 0 0 " + energy + " 1200 KINETIC " + radius + " 1.0");
        Matcher m = ID.matcher(resp);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    private void buildWall(int fromX, int depth) throws Exception {
        for (int i = 0; i < depth; i++) {
            place("minecraft:stone", fromX + i);
        }
    }

    private void prepare(int wallX) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup " + DIM + " "
                + ((wallX - 16) >> 4) + " " + ((Z - 16) >> 4) + " " + ((wallX + 24) >> 4) + " "
                + ((Z + 16) >> 4)).contains("\"ok\":true"));
        assertTrue("could not clear the site", exec("artest fill " + DIM + " " + (wallX - 8) + " "
                + (Y - 2) + " " + (Z - 3) + " " + (wallX + 20) + " " + (Y + 4) + " " + (Z + 3)
                + " minecraft:air").contains("\"ok\":true"));
    }

    // ---- reading

    /** Poll until the round's energy drops below {@code above}, or the budget runs out. */
    private String awaitEnergyBelow(long id, long above) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String state = read(id);
        while (System.currentTimeMillis() < deadline && isPresent(state) && energyOf(state) >= above) {
            Thread.sleep(120L);
            state = read(id);
        }
        return state;
    }

    private boolean awaitGone(long id) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && isPresent(read(id))) {
            Thread.sleep(150L);
        }
        return !isPresent(read(id));
    }

    /** How many blocks deep into the wall took damage: the bore's own length. */
    private int boreDepth(int wallX) throws Exception {
        int depth = 0;
        for (int i = 0; i < 10; i++) {
            String stage = stageAt(wallX + i);
            if (stageOf(stage) > 0 || destroyed(wallX + i)) {
                depth = i + 1;
            }
        }
        return depth;
    }

    /** What boring {@code blocks} of this wall costs at the reference cross-section, priced by the game. */
    private int budgetForBlocks(int wallX, double blocks) throws Exception {
        String stage = stageAt(wallX);
        int cost = readInt(stage, "stageCost");
        int stages = Math.max(1, readInt(stage, "maxStage"));
        assertTrue("the wall block has no stage cost, so nothing below is priced: " + stage, cost > 0);
        return (int) Math.round(cost * stages * blocks);
    }

    private static int readInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private String stageAt(int x) throws Exception {
        return exec("artest damage stage " + DIM + " " + x + " " + Y + " " + Z);
    }

    private boolean destroyed(int x) throws Exception {
        return stageAt(x).contains("\"wasDestroyed\":true");
    }

    private static int stageOf(String json) {
        Matcher m = STAGE.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String read(long id) throws Exception {
        return exec("artest shot read " + DIM + " " + id);
    }

    private static boolean isPresent(String json) {
        Matcher m = PRESENT.matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    private static long energyOf(String json) {
        Matcher m = ENERGY.matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    private void place(String block, int x) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + Y + " " + Z + " " + block);
        assertTrue("failed to place " + block + " at " + x + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private static String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }
}
