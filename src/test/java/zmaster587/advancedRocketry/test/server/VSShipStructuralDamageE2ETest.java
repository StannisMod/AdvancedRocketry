package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Damaging a block that belongs to a SHIP — the case the whole damage engine exists for, and the one
 * its world-block tests cannot reach.
 *
 * <p>A ship's blocks do not live where the ship appears to be. They sit at fixed addresses in a distant
 * shipyard subspace while the hull flies around, so an impact arriving in world coordinates has to be
 * mapped into that frame before anything can be looked up, and the report's points mapped back out. In
 * an ordinary world the two frames are the same thing, which means every assertion fired at ordinary
 * blocks would pass just as happily with the mapping deleted — that is precisely why this test exists
 * as a separate class rather than another case beside them.</p>
 *
 * <h3>The arrangement has to MOVE the ship</h3>
 * <p>A freshly assembled ship sits at its build site with an identity transform, where world and
 * subspace still coincide. Testing there would be the same empty test with more steps. So the ship is
 * rigid-teleported far away first, and the test asserts <b>as a control</b> that the two frames have
 * genuinely diverged before it draws any conclusion from what follows.</p>
 */
public class VSShipStructuralDamageE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** Build site, well clear of the other ship scenarios on this shared server. */
    private static final int SRC_X = 7200, SRC_Y = 80, SRC_Z = 7200;
    /** Where the ship is moved to. Far enough that no world-frame accident could reach the hull. */
    private static final int FAR_X = 7200, FAR_Y = 240, FAR_Z = 9600;
    /** Below this the two frames have not diverged enough for the control to mean anything. */
    private static final double MIN_FRAME_DIVERGENCE = 100.0D;

    @Test
    public void aBlockOfAMovedShipIsDamagedThroughItsWorldPosition() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());
        exec("artest vs permaload true");
        exec("artest damage clear-impacts");

        // Build a ship and move it, so world and subspace no longer coincide.
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must become a ship, not a rocket: " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the ship never loaded", waitForLoadedShip(0) >= 1);

        String info = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("ship not managed by VS: " + info, info.contains("\"managed\":true"));
        String shipId = extractString(info, "id");
        String tp = exec("artest vs teleport-ship 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z
                + " " + FAR_X + " " + FAR_Y + " " + FAR_Z);
        assertTrue("the ship could not be moved, so the frames never diverged: " + tp,
                tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + FAR_X + " " + FAR_Y + " " + FAR_Z);

        // A block of this ship whose subspace address we know: its pilot seat.
        String seat = exec("artest vs find-seat 0 id " + shipId);
        assertTrue("could not locate the ship's seat, so there is no known block to aim at: " + seat,
                seat.contains("\"seatFound\":true"));
        int subX = extractInt(seat, "seatX"), subY = extractInt(seat, "seatY"), subZ = extractInt(seat, "seatZ");

        String mapped = exec("artest vs to-world 0 " + FAR_X + " " + FAR_Y + " " + FAR_Z
                + " " + subX + " " + subY + " " + subZ);
        assertTrue("the seat's subspace address could not be mapped to a world point: " + mapped,
                mapped.contains("\"ok\":true"));
        double worldX = extractDouble(mapped, "worldX");
        double worldY = extractDouble(mapped, "worldY");
        double worldZ = extractDouble(mapped, "worldZ");

        // ARRANGEMENT CONTROL. The mapped point must actually be on the ship as the world sees it;
        // if it is not, everything below measures a broken fixture rather than the damage engine.
        String moved = exec("artest vs ship-info 0 " + FAR_X + " " + FAR_Y + " " + FAR_Z);
        assertTrue("the moved ship is not managed at its new position: " + moved,
                moved.contains("\"managed\":true"));
        double shipX = extractDouble(moved, "posX"), shipY = extractDouble(moved, "posY"),
                shipZ = extractDouble(moved, "posZ");
        double offHull = Math.sqrt(sq(worldX - shipX) + sq(worldY - shipY) + sq(worldZ - shipZ));
        assertTrue("the seat's mapped world point (" + worldX + "," + worldY + "," + worldZ + ") is "
                + offHull + " blocks from the ship's own world position (" + shipX + "," + shipY + ","
                + shipZ + "): the fixture, not the engine, is what this run would be measuring."
                + " subspace seat=" + subX + "," + subY + "," + subZ + " mapped=" + mapped,
                offHull < 64.0D);

        // THE CONTROL. Everything below is only evidence if the two frames actually differ: at the
        // build site they coincide, and an impact declared in world coordinates would land on the
        // right block by accident, with the conversion deleted.
        double divergence = Math.sqrt(sq(worldX - subX) + sq(worldY - subY) + sq(worldZ - subZ));
        assertTrue("world and subspace frames are only " + divergence + " blocks apart (world "
                + worldX + "," + worldY + "," + worldZ + " vs subspace " + subX + "," + subY + ","
                + subZ + "): this arrangement cannot tell a correct conversion from no conversion",
                divergence > MIN_FRAME_DIVERGENCE);

        // The subject block, read at its SUBSPACE address — where a ship's blocks actually are.
        String before = stage(subX, subY, subZ);
        assertTrue("the seat's subspace address holds no block, so nothing below is about the ship: "
                + before, !before.contains("\"block\":\"minecraft:air\""));
        assertTrue("the subject block is damaged before the impact: " + before,
                readLong(before, "stage") == 0);

        // Fire straight down through the seat's WORLD position with a budget that will not be spent
        // in one block, and give it an identity of its own.
        String result = exec("artest damage impact 0 " + worldX + " " + (worldY + 3.0D) + " " + worldZ
                + " 0 -1 0 200000 KINETIC 77001");
        assertTrue("the impact point resolved to no ship at all (candidates offered: "
                + readLong(result, "candidateShips") + "), so the engine walked the world frame where "
                + "this ship has no blocks:\n" + result, result.contains("\"onShip\":true"));
        assertTrue("an impact at the ship's world position struck nothing — the world point was not "
                + "mapped into the frame the ship's blocks live in:\n" + result,
                !result.contains("\"outcome\":\"NOTHING_STRUCK\""));
        assertTrue("the impact spent nothing on the ship:\n" + result, readLong(result, "spent") > 0);

        // The damage landed on the SHIP's own block, at its subspace address.
        String after = stage(subX, subY, subZ);
        boolean staged = readLong(after, "stage") > 0;
        boolean destroyed = after.contains("\"wasDestroyed\":true")
                || after.contains("\"block\":\"minecraft:air\"");
        assertTrue("the impact reported damage but the ship's own block is untouched at its subspace "
                + "address (before=" + before + " after=" + after + "):\n" + result, staged || destroyed);

        // And the report comes back in WORLD coordinates: a shot that resumes on a subspace point
        // would carry on inside a shipyard nobody can see.
        assertTrue("the report names no entry point:\n" + result, result.contains("\"hasEntry\":true"));
        double entryY = extractDouble(result, "entryY");
        assertTrue("the entry point came back at " + entryY + ", nowhere near the world position it "
                + "was fired at (" + worldY + "): the report was not mapped back out of the ship frame",
                Math.abs(entryY - worldY) < 8.0D);
    }

    private String stage(int x, int y, int z) throws Exception {
        return exec("artest damage stage 0 " + x + " " + y + " " + z);
    }

    private static double sq(double v) {
        return v * v;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private int waitForLoadedShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (extractInt(exec("artest vs ship-count-all " + dim), "count") >= 1) {
                exec("artest vs load-ships " + dim);
                int loaded = extractInt(exec("artest vs ship-count " + dim), "count");
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

    private static long readLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
