package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * What a declared impact does to real blocks in a real world — the damage engine driven through the
 * same service a weapon will call.
 *
 * <p>Four contracts, each one a thing a weapon has to be able to rely on:</p>
 *
 * <ul>
 *   <li>an impact that meets a wall <b>spends into it and says so</b>: something is staged or
 *       destroyed, the report names how deep it reached and where it entered;</li>
 *   <li>an impact whose budget outlasts the wall <b>exits carrying the rest</b> — that is what lets a
 *       shot continue instead of being silently swallowed by the first thing it touches;</li>
 *   <li>the <b>same impact identity applied twice damages once</b>: retries are real on the resolution
 *       path, and double damage is invisible in a diff;</li>
 *   <li>a wall of tougher stuff <b>is not penetrated further</b> than a flimsy one at equal budget —
 *       the ordering the toughness table exists to express, pinned as an ordering rather than as any
 *       particular number, all of which are tunable.</li>
 * </ul>
 *
 * <p>Impacts are declared with {@code /artest damage impact ...}, which calls the production service
 * on the logical server; the stage of any block is read back through the same unified reader
 * production uses.</p>
 */
public class StructuralDamageContractTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 70;

    @Test
    public void anImpactIntoAWallSpendsIntoItAndReportsWhereItReached() throws Exception {
        int x = 1200, z = 1200;
        buildWall("minecraft:stone", x, z, 4);
        clearImpactMemory();

        // Fired from outside the wall's near face, straight along +X into it, with a budget big
        // enough to matter but far too small to walk four blocks of stone.
        String result = impact(x - 2.5D, z + 0.5D, 1, 0, 0, 3000, "KINETIC", 9001);
        assertTrue("an impact into a solid wall reported striking nothing:\n" + result,
                !result.contains("\"outcome\":\"NOTHING_STRUCK\""));
        assertTrue("an impact into a wall spent none of its budget:\n" + result,
                readLong(result, "spent") > 0);
        assertTrue("the report names no depth, so no weapon could tell a slug from a pellet:\n" + result,
                readLong(result, "depth") > 0);
        assertTrue("the report names no entry point, so a continuing shot has nowhere to resume:\n"
                + result, result.contains("\"hasEntry\":true"));
        assertTrue("nothing was staged and nothing destroyed, yet budget was spent:\n" + result,
                readLong(result, "staged") + readLong(result, "destroyed") > 0);
    }

    @Test
    public void anImpactThatOutlastsTheWallExitsCarryingTheRest() throws Exception {
        int x = 1200, z = 1220;
        buildWall("minecraft:glass", x, z, 1);
        clearImpactMemory();

        // One pane of glass against a budget sized for a great deal more than one pane.
        String result = impact(x - 2.5D, z + 0.5D, 1, 0, 0, 400000, "KINETIC", 9002);
        assertTrue("a budget that dwarfs a single pane did not report exiting:\n" + result,
                result.contains("\"outcome\":\"EXITED\""));
        assertTrue("an exiting impact must say it left the far side:\n" + result,
                result.contains("\"stopReason\":\"EXITED_FAR_SIDE\""));
        assertTrue("an exiting impact carries no budget onward — the shot was silently swallowed:\n"
                + result, readLong(result, "left") > 0);
        assertTrue("an exiting impact names no exit point, so a continuing shot cannot resume:\n"
                + result, result.contains("\"hasExit\":true"));
        assertTrue("the pane survived a budget that should have taken it:\n" + result,
                readLong(result, "destroyed") > 0);
    }

    @Test
    public void theSameImpactIdentityAppliedTwiceDamagesOnce() throws Exception {
        int x = 1200, z = 1240;
        buildWall("minecraft:stone", x, z, 4);
        clearImpactMemory();

        long id = 9003L;
        String first = impact(x - 2.5D, z + 0.5D, 1, 0, 0, 3000, "KINETIC", id);
        long firstSpend = readLong(first, "spent");
        assertTrue("the first application of the impact did nothing, so the second proves nothing:\n"
                + first, firstSpend > 0);

        String second = impact(x - 2.5D, z + 0.5D, 1, 0, 0, 3000, "KINETIC", id);
        assertTrue("the same impact identity was applied a second time — a retry on the resolution "
                + "path would therefore damage twice, and no diff would show it:\n" + second,
                second.contains("\"stopReason\":\"DUPLICATE_IMPACT\""));
        assertTrue("a refused duplicate spent budget:\n" + second, readLong(second, "spent") == 0);
        assertTrue("a refused duplicate did not hand the budget back:\n" + second,
                readLong(second, "left") == 3000);

        // A DIFFERENT identity at the same place is a genuinely new impact and must still land —
        // otherwise the dedup would have turned into "one impact per position, ever".
        String third = impact(x - 2.5D, z + 0.5D, 1, 0, 0, 3000, "KINETIC", id + 1);
        assertTrue("a fresh impact identity was refused as a duplicate:\n" + third,
                !third.contains("\"stopReason\":\"DUPLICATE_IMPACT\""));
        assertTrue("a fresh impact identity spent nothing:\n" + third, readLong(third, "spent") > 0);
    }

    @Test
    public void aTougherWallIsNotPenetratedFurtherThanAFlimsyOneAtEqualBudget() throws Exception {
        int thickness = 8;
        int glassX = 1200, glassZ = 1260;
        int ironX = 1200, ironZ = 1280;
        buildWall("minecraft:glass", glassX, glassZ, thickness);
        buildWall("minecraft:iron_block", ironX, ironZ, thickness);
        clearImpactMemory();

        // The budget is derived from what production itself charges, not from a number written here:
        // exactly enough to take the whole glass wall. Every cost in this engine is tunable, so a
        // budget picked by hand would pin the tuning instead of the ordering, and would go red the
        // day someone rebalances armour without breaking anything a player would notice.
        String glassProbe = stage(glassX, glassZ);
        String ironProbe = stage(ironX, ironZ);
        long glassStageCost = readLong(glassProbe, "stageCost");
        long ironStageCost = readLong(ironProbe, "stageCost");
        long maxStage = readLong(glassProbe, "maxStage");
        assertTrue("iron is not costed above glass, so the toughness table orders nothing and the "
                + "comparison below is empty (glass=" + glassProbe + " iron=" + ironProbe + ")",
                ironStageCost > glassStageCost);

        int budget = (int) (glassStageCost * maxStage * thickness);
        String glass = impact(glassX - 2.5D, glassZ + 0.5D, 1, 0, 0, budget, "KINETIC", 9010);
        String iron = impact(ironX - 2.5D, ironZ + 0.5D, 1, 0, 0, budget, "KINETIC", 9011);
        long glassDestroyed = readLong(glass, "destroyed");
        long ironDestroyed = readLong(iron, "destroyed");

        // The control: the cheap wall must actually give way, or "iron resisted" is vacuous.
        assertTrue("a budget sized to take the whole glass wall did not take it (destroyed "
                + glassDestroyed + " of " + thickness + "), so this comparison measures nothing:\n"
                + glass, glassDestroyed == thickness);
        assertTrue("the same budget went as far into iron as into glass (glass destroyed "
                + glassDestroyed + ", iron destroyed " + ironDestroyed + "): a hull's material buys "
                + "its crew nothing.\niron=" + iron, ironDestroyed < glassDestroyed);
    }

    /** The unified stage reader at a wall's first block: stage, max stage, and what a stage costs there. */
    private String stage(int x, int z) throws Exception {
        return exec("artest damage stage " + DIM + " " + x + " " + Y + " " + z);
    }

    private String impact(double x, double z, double dx, double dy, double dz, int budget, String kind,
                          long impactId) throws Exception {
        return exec("artest damage impact " + DIM + " " + x + " " + (Y + 0.5D) + " " + z
                + " " + dx + " " + dy + " " + dz + " " + budget + " " + kind + " " + impactId);
    }

    /** A run of blocks along +X at the test's own row, the wall an impact is fired into. */
    private void buildWall(String block, int x, int z, int thickness) throws Exception {
        String resp = exec("artest fill " + DIM + " " + x + " " + Y + " " + z + " "
                + (x + thickness - 1) + " " + Y + " " + z + " " + block);
        assertTrue("failed to build the " + block + " wall at " + x + "," + Y + "," + z + ": " + resp,
                resp.contains("\"ok\":true"));
        assertTrue("the " + block + " wall placed no blocks, so every assertion below would be about "
                + "an empty row of air: " + resp, readLong(resp, "placed") == thickness);
    }

    private void clearImpactMemory() throws Exception {
        // The dedup memory is server-lifetime state on a shared server; a scenario that does not
        // clear it can be refused for an id another scenario happened to use.
        exec("artest damage clear-impacts");
    }

    private static long readLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
