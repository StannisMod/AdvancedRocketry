package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SPIKE — does a per-ship "crossing": snapshot ONE Valkyrien Skies
 * ship's subspace shipyard blocks (+ its TileEntities) with {@code StorageChunk}, deregister it, paste
 * the blocks elsewhere and re-assemble them into a fresh VS ship — preserve the ship's linked-TE state
 * and re-VS, carrying an aboard rider? This is the GO/NO-GO the transit subsystem is gated on: it decides
 * whether a jump can move a live piloted ship, or whether we fall back to whole-slot rebind.
 *
 * <p>Subject chosen to FALSIFY: a {@code with-pilot-seat} ship whose pilot seat is linked
 * to an Advanced Flight Computer at a fixed RELATIVE offset — cross-TE state that a scrambled pack/paste
 * would break. Witness: the seat still resolves its AFC (`afcResolved`) at the SAME relative offset after
 * the crossing. CONTROL: the seat probe reports {@code seatFound:false} before any ship exists,
 * proving the witness can report a negative.</p>
 *
 * <p>Gated on the server's real VS presence (run with {@code -PwithVS}); skips cleanly otherwise. This is
 * a spike test — if it goes GREEN the crossing is GO and its contract should be promoted into the transit
 * subsystem's own e2e; if it goes RED it records a NO-GO (fall back to whole-slot rebind).</p>
 */
public class VSShipCrossingSpikeTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** Where the piloted ship is built, and where it is crossed to (well separated, same loaded region). */
    private static final int SRC_X = 5000, SRC_Y = 80, SRC_Z = 5000;
    // Destination is up in clear sky (well above terrain) so the re-assembled ship is isolated from the
    // ground — VS's FIND_ALL_BLOCKS flood-fill must grab only the ship, not connect it to terrain.
    private static final int DST_X = 5064, DST_Y = 150, DST_Z = 5000;

    @Test
    public void aPilotedVsShipSurvivesAPerShipPackPasteCrossing() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)", serverHasVs());

        // A headless server has no player to hold a ship loaded, so a freshly assembled ship auto-unloads
        // between probe calls (its physics object drops out of the loaded set). Pin ships loaded so the
        // observations below are stable; reset in @After. (This is the permanentlyLoaded lever.)
        exec("artest vs permaload true");

        // CONTROL: no ship exists yet, so the seat witness must report a negative. This proves a
        // later "afcResolved:true" is a real observation, not a stuck-on witness.
        String control = exec("artest vs seat-input 0 0 0 0 0 0 0");
        assertTrue("witness sensitivity control — seat probe must report seatFound:false before any ship: "
                + control, control.contains("\"seatFound\":false"));

        // Build a piloted ship (pilot seat linked to an AFC) at the source and assemble it into a VS ship.
        clearArea(SRC_X, SRC_Z);
        clearArea(DST_X, DST_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip() >= 1);

        // BASELINE: the seat resolves its flight computer, and we record the RELATIVE offset between them
        // (invariant under any rigid relocation — the number the crossing must preserve).
        String pre = exec("artest vs seat-input 0 0 0 0 0 0 0");
        assertTrue("pre-crossing: seat must be found: " + pre, pre.contains("\"seatFound\":true"));
        assertTrue("pre-crossing: seat must be linked to its AFC: " + pre, pre.contains("\"seatLinked\":true"));
        assertTrue("pre-crossing: seat must resolve its AFC: " + pre, pre.contains("\"afcResolved\":true"));
        int[] preOffset = seatToAfcOffset(pre);

        // Put a rider aboard (an EntityDummy bound to the pilot seat).
        String mount = exec("artest vs seat-mount 0");
        assertTrue("could not seat a rider on the source ship: " + mount, mount.contains("\"seatFound\":true"));

        // Locate the ship's live world position, then perform the crossing to the destination.
        String srcInfo = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("source ship not managed by VS before crossing: " + srcInfo, srcInfo.contains("\"managed\":true"));
        double sx = extractDouble(srcInfo, "posX"), sy = extractDouble(srcInfo, "posY"), sz = extractDouble(srcInfo, "posZ");

        String cross = exec("artest vs ship-repack 0 " + (int) sx + " " + (int) sy + " " + (int) sz
                + " " + DST_X + " " + DST_Y + " " + DST_Z);
        assertTrue("crossing failed (NO-GO signal): " + cross, cross.contains("\"ok\":true"));
        // No assertion on HOW the source stops being a ship. This used to require the crossing to have
        // deregistered it itself, which pinned the mechanism rather than the promise - and the mechanism
        // it pinned was the one that leaked a ship per crossing. What the crossing owes is that the world
        // it left does not keep the ship it moved, and that is asserted where it belongs, in
        // VSCrossingLeavesNoShipBehindE2ETest.
        assertTrue("crossing did not carry the aboard rider: " + cross,
                extractInt(cross, "ridersCarried") >= 1);

        // The re-assembled ship must load again at the destination.
        int loadedAfter = waitForLoadedShip();
        assertTrue("the crossed VS ship never re-loaded at the destination; crossing=" + cross
                + " countAll=" + exec("artest vs ship-count-all 0"), loadedAfter >= 1);
        String dstInfo = exec("artest vs ship-info 0 " + DST_X + " " + DST_Y + " " + DST_Z);
        assertTrue("re-assembled ship is not managed by VS at the destination (crossing did not re-VS): "
                + dstInfo, dstInfo.contains("\"managed\":true"));

        // POST: the seat still resolves its AFC, at the SAME relative offset — the linked-TE state and the
        // ship's internal geometry survived the pack/paste round-trip.
        String post = exec("artest vs seat-input 0 0 0 0 0 0 0");
        assertTrue("post-crossing: seat must be found: " + post, post.contains("\"seatFound\":true"));
        assertTrue("post-crossing: seat must still be linked to its AFC: " + post,
                post.contains("\"seatLinked\":true"));
        assertTrue("post-crossing: seat must still resolve its AFC: " + post, post.contains("\"afcResolved\":true"));
        int[] postOffset = seatToAfcOffset(post);
        assertEquals("seat->AFC relative offset X changed across the crossing (geometry scrambled); pre="
                + java.util.Arrays.toString(preOffset) + " post=" + java.util.Arrays.toString(postOffset),
                preOffset[0], postOffset[0]);
        assertEquals("seat->AFC relative offset Y changed across the crossing", preOffset[1], postOffset[1]);
        assertEquals("seat->AFC relative offset Z changed across the crossing", preOffset[2], postOffset[2]);
    }

    @org.junit.After
    public void resetPermaload() throws Exception {
        // Shared-harness state-leak contract: don't leave "permanently loaded" set for later tests.
        if (serverHasVs()) {
            exec("artest vs permaload false");
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    /** Poll for a loaded VS ship (assembly is async on the physics thread; a headless server has no
     *  player near to auto-load it, so force a load each round). Bounded ~10 s. Returns the loaded count. */
    private int waitForLoadedShip() throws Exception {
        for (int i = 0; i < 40; i++) {
            if (extractInt(exec("artest vs ship-count-all 0"), "count") >= 1) {
                exec("artest vs load-ships 0");
                int loaded = extractInt(exec("artest vs ship-count 0"), "count");
                if (loaded >= 1) {
                    return loaded;
                }
            }
            Thread.sleep(250);
        }
        return 0;
    }

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " " + (baseZ - 4)
                + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20) + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int[] seatToAfcOffset(String json) {
        return new int[]{
                extractInt(json, "afcX") - extractInt(json, "seatX"),
                extractInt(json, "afcY") - extractInt(json, "seatY"),
                extractInt(json, "afcZ") - extractInt(json, "seatZ"),
        };
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }
}
