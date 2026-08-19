package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A full pass over a ship's hull agrees with the running total the engine kept while building it.
 *
 * <h2>What is actually under test</h2>
 *
 * <p>A ship's mass is maintained two ways, and only one of them is cheap. The working path is
 * incremental — a delta applied as each block arrives or leaves — and it is correct exactly as long
 * as nothing is ever missed. The authority is a full walk over the hull, run at the few moments where
 * the incremental path cannot be trusted to have kept up: an assembly, a paste, a load from disk.</p>
 *
 * <p>This asserts they agree on a craft that was just assembled — the one case where they really
 * ought to, because the deltas were fed the whole hull moments earlier. A disagreement here is not a
 * rounding complaint: it means the two halves of the mass model are pricing the same ship
 * differently, and every derived number downstream (thrust-to-weight, turn rate, fuel per manoeuvre)
 * inherits whichever one it happened to read.</p>
 *
 * <h2>Why the recompute count is asserted first</h2>
 *
 * <p>"No disagreement" is what a build with no recompute at all reports too. The counter separates
 * the two, and without it this test would pass forever on a build where the trigger never fired —
 * which is precisely the failure the recompute exists to catch, reproduced in the test itself.</p>
 *
 * <h2>Why drift is read rather than thrown</h2>
 *
 * <p>The recompute runs inside the world tick. Raising an exception there kills the dedicated server,
 * and a dead server reports that the process exited, not that a hull was 4% light. So a disagreement
 * is recorded with its sign and read back here, where the failure message can carry the number.</p>
 *
 * <p>Needs the physics mod: without it there is no ship and nothing to weigh.</p>
 */
public class AnAssembledHullWeighsWhatItsBlocksWeighE2ETest extends AbstractHeadlessServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final int BASE_X = 10600, BASE_Z = 10600, BUILD_Y = 80;

    @Test
    public void theAuthoritativeRecomputeAgreesWithTheIncrementalTotalOnAFreshAssembly()
            throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        exec("artest vs permaload true");
        assertTrue("the recorder must start empty, or the counts below are somebody else's",
                exec("artest vs mass-drift reset").contains("\"ok\":true"));

        buildShip();
        assertTrue("the craft never became a ship, so no recompute could have been triggered",
                waitUntilLoaded());

        String massDrift = waitUntilRecomputed();
        assertTrue("the authoritative hull recompute never ran. Without it nothing measures the "
                        + "incremental path, and this test's silence would mean nothing: " + massDrift,
                extractInt(massDrift, "recomputes") >= 1);
        assertEquals("the recompute ran but found no hull to weigh, so it compared nothing: "
                        + massDrift, 0, extractInt(massDrift, "skipped"));

        assertEquals("the full hull pass and the running total the engine kept while assembling this "
                        + "craft disagree. They price the same blocks from the same table, so a "
                        + "difference here means one of the two write paths is not seeing part of the "
                        + "ship - and every derived figure downstream inherits whichever it read: "
                        + massDrift,
                0, extractInt(massDrift, "driftCount"));
    }

    // --- arrangement --------------------------------------------------------------------------------

    private void buildShip() throws Exception {
        clearArea();
        String coords = placeFixture("with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with the physics mod an AFC-bearing build must become a ship, not a rocket: " + asm,
                asm.contains("\"rocketCount\":0"));
    }

    // --- observation --------------------------------------------------------------------------------

    /** Poll until at least one recompute is recorded; returns the last reading either way. */
    private String waitUntilRecomputed() throws Exception {
        String last = "";
        for (int i = 0; i < 40; i++) {
            last = exec("artest vs mass-drift");
            if (extractInt(last, "recomputes") >= 1) {
                return last;
            }
            Thread.sleep(250);
        }
        return last;
    }

    private boolean waitUntilLoaded() throws Exception {
        for (int i = 0; i < 40; i++) {
            if (extractInt(exec("artest vs ship-count 0"), "count") >= 1) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private void clearArea() throws Exception {
        int cx1 = (BASE_X - 4) >> 4, cz1 = (BASE_Z - 4) >> 4;
        int cx2 = (BASE_X + 20) >> 4, cz2 = (BASE_Z + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (BASE_X - 4) + " " + (BUILD_Y - 2) + " " + (BASE_Z - 4)
                + " " + (BASE_X + 20) + " " + (BUILD_Y + 12) + " " + (BASE_Z + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + BASE_X + " " + BUILD_Y + " " + BASE_Z + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
