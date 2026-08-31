package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * What a calibre BUYS, inside the hull rather than on a tooltip.
 *
 * <p>A round used to be a line: whatever its declared width, it met one block per step and left a
 * one-block channel. So the choice between a needle and a slug was a number that changed how far the
 * same hole went. A body with width sweeps a cylinder, and the two claims here are the two halves of
 * the trade it sells — a wide round takes the blocks BESIDE the line it flew along, and it pays for
 * them by not going as deep. Neither is a quantity: both are orderings, because the depths and the
 * prices are balance and will move.</p>
 *
 * <p>The wall is a SLAB rather than a column, which is the whole arrangement: against a one-block
 * column a wide round and a narrow one are indistinguishable, and every test that has fired at one
 * would pass against a substrate that still treated a body as a line.</p>
 */
public class WideRoundCutsAWideHoleE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    /** A site of this class's own, clear of the other shot scenarios on this shared server. */
    private static final int Y = 70, Z = 940;
    private static final int NARROW_X = 1600, WIDE_X = 1660;
    /**
     * Deep enough that NEITHER round comes out the far side. A slab both rounds punch through reports
     * the same depth for both and says nothing about the trade — which is exactly what a six-block
     * wall did on the first run of this test.
     */
    private static final int SLAB_DEPTH = 20;

    /** The reference body: everything the substrate did before it had a width was this wide. */
    private static final double NARROW_RADIUS = 0.25D;
    /** A body a block across — wide enough to straddle its neighbours, inside the configured cap. */
    private static final double WIDE_RADIUS = 1.0D;

    private static final Pattern ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern STAGE = Pattern.compile("\"stage\":(-?\\d+)");
    private static final Pattern STAGE_COST = Pattern.compile("\"stageCost\":(-?\\d+)");
    private static final Pattern MAX_STAGE = Pattern.compile("\"maxStage\":(-?\\d+)");

    @Test
    public void aWideRoundTakesTheBlocksBesideItsLineAndANarrowOneDoesNot() throws Exception {
        prepare(NARROW_X);
        buildSlab(NARROW_X, SLAB_DEPTH);
        prepare(WIDE_X);
        buildSlab(WIDE_X, SLAB_DEPTH);

        // Priced off the wall's own block, never hard-coded: the cost comes from the toughness table,
        // which is balance and moves. Rich enough that a body sixteen times the reference area still
        // buys depth — otherwise "the wide round damaged nothing" would be a statement about the
        // budget rather than about the geometry — and poor enough that neither round reaches the far
        // side, because two rounds that both punch through report the same depth and prove nothing.
        int budget = budgetForBlocks(NARROW_X, 12.0D);
        assertTrue("the wall block has no price, so no budget here means anything", budget > 0);

        long narrow = fire(NARROW_X - 3.0D, budget, NARROW_RADIUS);
        assertTrue("the substrate refused the narrow shot", narrow >= 0);
        long wide = fire(WIDE_X - 3.0D, budget, WIDE_RADIUS);
        assertTrue("the substrate refused the wide shot", wide >= 0);

        awaitGone(narrow);
        awaitGone(wide);

        int narrowBeside = touchedBeside(NARROW_X);
        int wideBeside = touchedBeside(WIDE_X);
        int narrowDepth = channelDepth(NARROW_X);
        int wideDepth = channelDepth(WIDE_X);

        assertTrue("a body a quarter of a block across touched " + narrowBeside + " blocks beside the"
                + " line it flew along: a needle must leave a needle's channel", narrowBeside == 0);
        assertTrue("the wide round left the same one-block channel a ray leaves (beside=" + wideBeside
                + "): its width bought nothing, which is the whole thing a calibre is meant to buy",
                wideBeside > 0);
        assertTrue("the narrow round never got into the wall at all (depth=" + narrowDepth + "), so"
                + " the comparison below is between two zeroes", narrowDepth > 0);
        assertTrue("the wide round bored as deep as the needle (wide=" + wideDepth + ", narrow="
                + narrowDepth + ") while also taking blocks beside it — then width is strictly better"
                + " and there is no trade at all", wideDepth < narrowDepth);
    }

    // ---- driving

    private long fire(double x, int energy, double radius) throws Exception {
        String resp = exec("artest shot fire " + DIM + " " + x + " " + (Y + 0.5D) + " " + (Z + 0.5D)
                + " 0.45 0 0 " + energy + " 1200 KINETIC " + radius + " 1.0");
        Matcher m = ID.matcher(resp);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /** A slab five blocks tall and five wide, so a body a block across has neighbours to reach. */
    private void buildSlab(int fromX, int depth) throws Exception {
        assertTrue("could not build the slab", exec("artest fill " + DIM + " " + fromX + " " + (Y - 2)
                + " " + (Z - 2) + " " + (fromX + depth - 1) + " " + (Y + 2) + " " + (Z + 2)
                + " minecraft:stone").contains("\"ok\":true"));
    }

    private void prepare(int wallX) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup " + DIM + " "
                + ((wallX - 16) >> 4) + " " + ((Z - 16) >> 4) + " " + ((wallX + 24) >> 4) + " "
                + ((Z + 16) >> 4)).contains("\"ok\":true"));
        assertTrue("could not clear the site", exec("artest fill " + DIM + " " + (wallX - 8) + " "
                + (Y - 4) + " " + (Z - 5) + " " + (wallX + 20) + " " + (Y + 5) + " " + (Z + 5)
                + " minecraft:air").contains("\"ok\":true"));
    }

    // ---- reading

    /**
     * How many blocks OFF the line of flight the round touched — staged or gone. The line itself is
     * excluded, so this counts only what a ray could never have reached.
     */
    private int touchedBeside(int wallX) throws Exception {
        int touched = 0;
        for (int depth = 0; depth < SLAB_DEPTH; depth++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dy == 0 && dz == 0) {
                        continue;
                    }
                    if (wasTouched(wallX + depth, Y + dy, Z + dz)) {
                        touched++;
                    }
                }
            }
        }
        return touched;
    }

    /** How far along the line of flight the round got: the last touched block on the axis. */
    private int channelDepth(int wallX) throws Exception {
        int depth = 0;
        for (int i = 0; i < SLAB_DEPTH; i++) {
            if (wasTouched(wallX + i, Y, Z)) {
                depth = i + 1;
            }
        }
        return depth;
    }

    private boolean wasTouched(int x, int y, int z) throws Exception {
        String state = exec("artest damage stage " + DIM + " " + x + " " + y + " " + z);
        if (state.contains("\"block\":\"minecraft:air\"") || state.contains("\"wasDestroyed\":true")) {
            return true;
        }
        Matcher m = STAGE.matcher(state);
        return m.find() && Integer.parseInt(m.group(1)) > 0;
    }

    /** A budget worth this many whole blocks of the wall, read off the wall rather than assumed. */
    private int budgetForBlocks(int wallX, double blocks) throws Exception {
        String state = exec("artest damage stage " + DIM + " " + wallX + " " + Y + " " + Z);
        Matcher cost = STAGE_COST.matcher(state);
        Matcher stages = MAX_STAGE.matcher(state);
        if (!cost.find() || !stages.find()) {
            return 0;
        }
        return (int) (Integer.parseInt(cost.group(1)) * Math.max(1, Integer.parseInt(stages.group(1)))
                * blocks);
    }

    /** Wait until the round has left the air, so what is read afterwards is its whole crater. */
    private void awaitGone(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 25_000L;
        while (System.currentTimeMillis() < deadline) {
            if (exec("artest shot read " + DIM + " " + id).contains("\"present\":false")) {
                return;
            }
            Thread.sleep(100L);
        }
    }

    private static String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }
}
