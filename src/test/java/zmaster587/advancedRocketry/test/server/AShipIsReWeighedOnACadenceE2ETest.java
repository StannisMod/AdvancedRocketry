package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A ship is re-weighed on a CADENCE, with no event to trigger it.
 *
 * <h2>Why a cadence has to exist at all</h2>
 *
 * <p>The authoritative recompute used to run only where the engine announced something: a craft
 * assembled, pasted, or loaded from disk. That covers every moment a hull's STRUCTURE changes
 * wholesale — and covers nothing else, because the other two halves of a ship's mass change with no
 * block ever changing. A tank empties over a burn. A crate is filled. Somebody steps aboard carrying
 * a stack of ore. There is no block event under any of it, so there is nothing to subscribe to, and a
 * mass model that only listens to events is one that reports what the craft weighed when it was
 * built.</p>
 *
 * <h2>What this pins, and what it deliberately does not</h2>
 *
 * <p>It pins the mechanism: after a craft has been assembled and the recorder emptied — so the
 * assembly's own recompute cannot be mistaken for this one — a full measurement runs anyway, on time,
 * with nothing at all happening to the ship. That is falsifiable in the strongest way available: with
 * the cadence removed the counter stays at zero forever, which is precisely the state this test was
 * written against.</p>
 *
 * <p>It does NOT yet pin the consequence — that a filled tank makes its ship heavier. Assembly moves
 * a hull's blocks into the ship's own shipyard address space, so filling a tank aboard means
 * addressing it there rather than at the coordinates it was built at, and no probe answers in that
 * space today. Recorded rather than quietly skipped: the mechanism above is what the consequence rests
 * on, and it is the half that could silently not exist.</p>
 *
 * <p>Needs the physics mod: without it there is no ship to weigh.</p>
 */
public class AShipIsReWeighedOnACadenceE2ETest extends AbstractHeadlessServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final int BASE_X = 10800, BASE_Z = 10800, BUILD_Y = 80;

    /**
     * How long to wait for a round, in milliseconds. The cadence is one round per hundred ticks per
     * ship, on a phase taken from the ship's own identity, so the worst case is a whole period plus
     * the phase — this is a little over two of them, which is a ceiling on waiting and not a
     * statement about how often production should measure.
     */
    private static final long ROUND_BUDGET_MS = 12_000L;

    @Test
    public void aSettledShipIsMeasuredAgainWithNothingHappeningToIt() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        exec("artest vs permaload true");
        buildShip();
        assertTrue("the craft never became a ship, so nothing could weigh it", waitUntilLoaded());

        // Empty the recorder AFTER the ship exists. Everything counted from here is the cadence's,
        // never the assembly's — without this the test would pass on a build that has no cadence at
        // all, on the strength of the recompute the assembly already does.
        assertTrue("the recorder must start empty or the count below is somebody else's",
                exec("artest vs mass-drift reset").contains("\"ok\":true"));
        assertEquals("the reset did not take, so the count below cannot be attributed", 0,
                extractInt(exec("artest vs mass-drift"), "recomputes"));

        String drift = waitForARound();

        assertTrue("a settled ship must be re-measured on its own cadence, with no event and nothing"
                        + " happening to it, or content and crew can never reach its mass - a tank"
                        + " empties and a crew member boards without a single block changing, so no"
                        + " block event exists to catch either. The recorder saw no recompute in "
                        + ROUND_BUDGET_MS + " ms: " + drift,
                extractInt(drift, "recomputes") >= 1);

        assertEquals("the round ran but found no hull to weigh, so it measured nothing: " + drift,
                0, extractInt(drift, "skipped"));

        // The round writes; it does not report. A difference between two rounds is the content
        // legitimately changing - fuel burned, cargo moved - and filing that as drift would bury the
        // one signal the recorder exists for, which is a block delta that went missing.
        assertEquals("a background round must not report drift: between two rounds the ship's content"
                        + " changes for real, and calling that a defect makes the recorder useless for"
                        + " the defect it exists to catch: " + drift,
                0, extractInt(drift, "driftCount"));
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

    /** Poll until a round is recorded; returns the last reading either way, so a red can be read. */
    private String waitForARound() throws Exception {
        String last = "";
        long deadline = System.currentTimeMillis() + ROUND_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
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
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (BASE_X - 4) + " " + (BUILD_Y - 2) + " " + (BASE_Z - 4)
                        + " " + (BASE_X + 20) + " " + (BUILD_Y + 12) + " " + (BASE_Z + 20)
                        + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + BASE_X + " " + BUILD_Y + " " + BASE_Z
                + " " + variant);
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
