package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An impact arriving at an ANGLE must not leave blocks it passed through untouched.
 *
 * <p>The engine bores by walking its own ray, and what "one step" means along that ray is not what one
 * step means on the block grid unless the ray is parallel to an axis. Every damage test written before
 * this one fires straight down or straight along Z, where the two coincide exactly — so a walk that
 * samples its path instead of traversing it passes all of them and still lets an oblique round through
 * blocks it physically crossed.</p>
 *
 * <h3>What is asserted, and why it is not a restatement of the implementation</h3>
 * <p>Not "the engine visits voxel list L" — that would pin whichever traversal happens to be in the
 * code. The claim is about the RESULT a player can see: <b>the damaged blocks form an unbroken
 * chain</b>. A block left pristine between two damaged ones is a hole in the bore, and it means the
 * budget was never offered to it: something the round went through did not have to pay. The same
 * property holds at every angle, which is the point.</p>
 */
public class DiagonalBoreE2ETest extends AbstractSharedServerTest {

    /**
     * A site per case, not one shared site. Damage records are keyed by POSITION and survive a
     * re-fill — nothing fires a break event for a probe's {@code setBlockState} — so two cases in one
     * place would read each other's craters, and which one read which would depend on JUnit's method
     * ordering.
     */
    private static final int OBLIQUE_X = 11_600, STRAIGHT_X = 11_800;
    private static final int Y = 84, Z = 11_600;
    /** How deep the solid block of stone runs along the line of fire. */
    private static final int DEPTH = 28;
    /** Half-width either side, so a shallow angle stays inside the target for its whole path. */
    private static final int HALF = 20;

    /** About 30 degrees off the X axis, in the XZ plane: shallow enough to skip, steep enough to see. */
    private static final double DIR_X = 0.866D, DIR_Z = 0.5D;

    /** Below this the chain is too short for "unbroken" to mean anything. */
    private static final int MIN_BLOCKS_DAMAGED = 6;

    @Test
    public void anObliqueImpactLeavesNoUntouchedBlockInsideItsOwnBore() throws Exception {
        exec("artest damage clear-impacts");
        int X = OBLIQUE_X;
        buildSolidTarget(X);

        int stageCost = extractInt(exec("artest damage stage 0 " + (X + 4) + " " + Y + " " + Z),
                "stageCost");
        int maxStage = extractInt(exec("artest damage stage 0 " + (X + 4) + " " + Y + " " + Z),
                "maxStage");
        assertTrue("no stage cost inside the target, so nothing here could be damaged", stageCost > 0);
        assertTrue("no stages inside the target", maxStage > 0);

        // Enough budget to work through a run of blocks, so the chain is long enough to have holes.
        int budget = 10 * maxStage * stageCost;
        String report = exec("artest damage impact 0 " + (X - 2.5D) + " " + (Y + 0.5D) + " "
                + (Z + 0.5D) + " " + DIR_X + " 0 " + DIR_Z + " " + budget + " KINETIC 91001");
        assertTrue("the oblique impact struck nothing at all — the arrangement, not the engine, is what"
                + " this run would be measuring:\n" + report,
                !report.contains("\"outcome\":\"NOTHING_STRUCK\""));

        List<int[]> damaged = damagedBlocks(X);
        assertTrue("only " + damaged.size() + " blocks were damaged; a chain that short cannot show a"
                + " hole. report:\n" + report, damaged.size() >= MIN_BLOCKS_DAMAGED);

        // Order them the way the round met them: by how far along its own direction each one sits.
        Collections.sort(damaged, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return Double.compare(along(a), along(b));
            }
        });

        List<String> holes = new ArrayList<>();
        for (int i = 1; i < damaged.size(); i++) {
            int[] previous = damaged.get(i - 1);
            int[] current = damaged.get(i);
            int step = Math.abs(current[0] - previous[0]) + Math.abs(current[1] - previous[1])
                    + Math.abs(current[2] - previous[2]);
            if (step != 1) {
                holes.add(describe(previous) + " -> " + describe(current) + " (" + step + " apart)");
            }
        }
        assertEquals("the bore has " + holes.size() + " hole(s): blocks the round passed through were"
                + " never offered any of its budget, so they stand pristine inside a crater. This is"
                + " what a path SAMPLED every unit of ray does at any angle that is not axis-aligned."
                + "\n  " + String.join("\n  ", holes)
                + "\n  damaged chain: " + describeAll(damaged),
                0, holes.size());
    }

    @Test
    public void anAxisAlignedImpactIsUnaffected() throws Exception {
        // The control for the change, and the reason it is safe: where the ray is parallel to an axis
        // a sampled path and a traversed one are the same list of blocks. If this ever moves, the fix
        // changed something it had no business changing.
        exec("artest damage clear-impacts");
        int X = STRAIGHT_X;
        buildSolidTarget(X);

        int stageCost = extractInt(exec("artest damage stage 0 " + (X + 4) + " " + Y + " " + Z),
                "stageCost");
        int maxStage = extractInt(exec("artest damage stage 0 " + (X + 4) + " " + Y + " " + Z),
                "maxStage");
        int wanted = 5;
        String report = exec("artest damage impact 0 " + (X - 2.5D) + " " + (Y + 0.5D) + " "
                + (Z + 0.5D) + " 1 0 0 " + (wanted * maxStage * stageCost) + " KINETIC 91002");
        assertTrue("the straight impact struck nothing:\n" + report,
                !report.contains("\"outcome\":\"NOTHING_STRUCK\""));

        List<int[]> damaged = damagedBlocks(X);
        assertEquals("a straight shot must damage exactly the blocks its budget covers, in one row:"
                + " " + describeAll(damaged) + "\nreport:\n" + report, wanted, damaged.size());
        for (int[] block : damaged) {
            assertEquals("a straight shot along X wandered off its row: " + describe(block), Z,
                    block[2]);
            assertEquals("a straight shot along X changed height: " + describe(block), Y, block[1]);
        }
    }

    private static double along(int[] block) {
        return DIR_X * block[0] + DIR_Z * block[2];
    }

    private static String describe(int[] block) {
        return "(" + block[0] + "," + block[1] + "," + block[2] + ")";
    }

    private static String describeAll(List<int[]> blocks) {
        StringBuilder sb = new StringBuilder();
        for (int[] block : blocks) {
            sb.append(describe(block)).append(' ');
        }
        return sb.toString();
    }

    /** Every damage record inside the target, as {x,y,z}. */
    private List<int[]> damagedBlocks(int X) throws Exception {
        String records = exec("artest damage records 0 " + (X - 4) + " " + (Y - 2) + " " + (Z - HALF)
                + " " + (X + DEPTH + 4) + " " + (Y + 2) + " " + (Z + HALF));
        assertTrue("the records probe failed: " + records, records.contains("\"ok\":true"));
        List<int[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{\"x\":(-?\\d+),\"y\":(-?\\d+),\"z\":(-?\\d+)").matcher(records);
        while (m.find()) {
            out.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))});
        }
        return out;
    }

    /**
     * A solid block of stone, rebuilt from scratch each time so a previous case's crater — and its
     * damage records — cannot be read as this one's.
     */
    private void buildSolidTarget(int X) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + ((X - 8) >> 4) + " "
                + ((Z - HALF - 4) >> 4) + " " + ((X + DEPTH + 8) >> 4) + " " + ((Z + HALF + 4) >> 4))
                .contains("\"ok\":true"));
        assertTrue("could not clear the approach", exec("artest fill 0 " + (X - 8) + " " + (Y - 2)
                + " " + (Z - HALF - 2) + " " + (X - 1) + " " + (Y + 2) + " " + (Z + HALF + 2)
                + " minecraft:air").contains("\"ok\":true"));
        // Air first, then stone: filling straight over a previous crater would leave that crater's
        // damage records attached to the fresh blocks standing in the same positions.
        assertTrue("could not clear the target", exec("artest fill 0 " + X + " " + (Y - 2) + " "
                + (Z - HALF) + " " + (X + DEPTH) + " " + (Y + 2) + " " + (Z + HALF)
                + " minecraft:air").contains("\"ok\":true"));
        assertTrue("could not build the target", exec("artest fill 0 " + X + " " + (Y - 2) + " "
                + (Z - HALF) + " " + (X + DEPTH) + " " + (Y + 2) + " " + (Z + HALF)
                + " minecraft:stone").contains("\"ok\":true"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
