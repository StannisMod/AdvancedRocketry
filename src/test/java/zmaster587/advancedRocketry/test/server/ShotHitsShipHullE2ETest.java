package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A shot that meets a SHIP — the case the whole substrate exists for, and the one its world-block
 * tests cannot reach.
 *
 * <p>A ship's blocks are not where the ship appears to be: they sit at fixed addresses in a shipyard
 * subspace millions of blocks away while the hull flies around. So a swept segment computed in world
 * coordinates crosses <b>nothing</b> — the world frame is empty air where the hull visibly is. The
 * substrate therefore maps both ends of the segment into each candidate ship's frame, traverses
 * there, and maps the crossing point back out. Three conversions, none of which announces itself when
 * it is wrong: a frame error here is not an exception, it is a round that flies through a hull.</p>
 *
 * <h3>What makes this evidence rather than a coincidence</h3>
 * <p>Two controls, both asserted before any conclusion is drawn. The world frame at the target must
 * genuinely hold <b>air</b>, so a hit cannot have come from the world-frame traversal; and the
 * subject block must be undamaged at its subspace address beforehand, so "damaged afterwards" is
 * about this shot. The end point is then checked against the ship's WORLD position — with the
 * mapping-back-out leg deleted, a shot would report ending five million blocks away in a shipyard
 * nobody can see, and every other assertion here would still pass.</p>
 */
public class ShotHitsShipHullE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** A build site of this class's own, clear of the other ship scenarios on this shared server. */
    private static final int SRC_X = 6400, SRC_Y = 80, SRC_Z = 6400;
    /** Where the ship is moved to: in the air, INSIDE build height, so "the world is air there" is a
     *  measurement rather than a consequence of being above the world's ceiling. */
    private static final int FAR_X = 6400, FAR_Y = 150, FAR_Z = 8800;

    /** Fast enough that one tick's segment crosses the whole hull — the case a point test misses. */
    private static final double SPEED = 40.0D;
    /** Enough budget to be spent on more than the first block it meets. */
    private static final int ENERGY = 200000;

    @Test
    public void aShotStopsAtTheHullOfAMovedShipAndDamagesItsOwnBlock() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());
        exec("artest vs permaload true");
        exec("artest damage clear-impacts");
        exec("artest shot clear 0");

        String shipId = buildAndMoveShip();

        // A block of this ship whose subspace address we know: its pilot seat.
        String seat = exec("artest vs find-seat 0 id " + shipId);
        assertTrue("could not locate the ship's seat, so there is no known block to aim at: " + seat,
                seat.contains("\"seatFound\":true"));
        int subX = extractInt(seat, "seatX"), subY = extractInt(seat, "seatY"),
                subZ = extractInt(seat, "seatZ");

        String mapped = exec("artest vs to-world 0 " + FAR_X + " " + FAR_Y + " " + FAR_Z
                + " " + subX + " " + subY + " " + subZ);
        assertTrue("the seat's subspace address could not be mapped to a world point: " + mapped,
                mapped.contains("\"ok\":true"));
        double worldX = extractDouble(mapped, "worldX");
        double worldY = extractDouble(mapped, "worldY");
        double worldZ = extractDouble(mapped, "worldZ");

        // ARRANGEMENT CONTROL — the mapped point is actually on the ship as the world sees it. If it
        // is not, everything below measures a broken fixture rather than the substrate.
        String moved = exec("artest vs ship-info 0 " + FAR_X + " " + FAR_Y + " " + FAR_Z);
        assertTrue("the moved ship is not managed at its new position: " + moved,
                moved.contains("\"managed\":true"));
        double shipX = extractDouble(moved, "posX"), shipY = extractDouble(moved, "posY"),
                shipZ = extractDouble(moved, "posZ");
        double offHull = Math.sqrt(sq(worldX - shipX) + sq(worldY - shipY) + sq(worldZ - shipZ));
        assertTrue("the seat's mapped world point (" + worldX + "," + worldY + "," + worldZ + ") is "
                + offHull + " blocks from the ship's own world position: the fixture, not the"
                + " substrate, is what this run would be measuring. mapped=" + mapped, offHull < 64.0D);

        // CONTROL 1 — the WORLD frame is air along the line of fire. A hit therefore cannot have come
        // from the world-frame traversal, which is the only other way this substrate finds anything.
        for (int drop = -4; drop <= 4; drop++) {
            String worldBlock = exec("artest damage stage 0 " + (int) Math.floor(worldX) + " "
                    + ((int) Math.floor(worldY) + drop) + " " + (int) Math.floor(worldZ));
            assertTrue("the world frame holds a block at the target, " + drop + " blocks off the seat: "
                    + worldBlock + ". A shot stopping here would prove nothing about ship frames",
                    worldBlock.contains("\"block\":\"minecraft:air\""));
        }

        // CONTROL 2 — the subject is undamaged at its SUBSPACE address, where the ship's blocks are.
        String before = stage(subX, subY, subZ);
        assertTrue("the seat's subspace address holds no block, so nothing below is about the ship: "
                + before, !before.contains("\"block\":\"minecraft:air\""));
        assertTrue("the subject block is already damaged before the shot: " + before,
                readLong(before, "stage") == 0);

        // Fire straight down through the seat's WORLD position, from clear air above it.
        long id = readLong(exec("artest shot fire 0 " + worldX + " " + (worldY + 30.0D) + " " + worldZ
                + " 0 " + (-SPEED) + " 0 " + ENERGY + " 40"), "id");
        assertTrue("the launch was refused, so nothing else here means anything", id > 0);
        exec("artest shield tick 0");

        String after = exec("artest shot read 0 " + id);
        assertTrue("the shot is still in flight after a step that crossed the hull — a segment computed"
                + " in the world frame finds nothing where a ship visibly is, which is exactly what"
                + " this substrate maps around: " + after, after.contains("\"present\":false"));
        assertTrue("the shot stopped, but not by meeting structure: " + after,
                "STRUCTURE_IMPACT".equals(extractString(after, "ended")));

        // The damage landed on the SHIP's own block, at its subspace address.
        String hull = stage(subX, subY, subZ);
        boolean staged = readLong(hull, "stage") > 0;
        boolean destroyed = hull.contains("\"wasDestroyed\":true")
                || hull.contains("\"block\":\"minecraft:air\"");
        assertTrue("the shot reported hitting structure but the ship's own block is untouched at its"
                + " subspace address (before=" + before + " after=" + hull + "): the impact was handed"
                + " over in the wrong frame, or to the wrong target", staged || destroyed);

        // And the shot ended in WORLD coordinates. Without the mapping back out it would report
        // ending at a shipyard address millions of blocks from anything a player can see — and every
        // assertion above would still have passed.
        double endX = readDouble(after, "endX"), endY = readDouble(after, "endY"),
                endZ = readDouble(after, "endZ");
        double offSeat = Math.sqrt(sq(endX - worldX) + sq(endY - worldY) + sq(endZ - worldZ));
        assertTrue("the shot ended at (" + endX + "," + endY + "," + endZ + "), " + offSeat
                + " blocks from the world point it was fired through: the crossing point was never"
                + " mapped out of the ship's frame", offSeat < 16.0D);
    }

    /** Build the fixture, assemble it into a ship and move it far from where it was built. */
    private String buildAndMoveShip() throws Exception {
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must become a ship, not a rocket: " + asm,
                asm.contains("\"rocketCount\":0"));

        String info = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            exec("artest vs load-ships 0");
            info = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
            if (info.contains("\"managed\":true")) {
                break;
            }
            Thread.sleep(250);
        }
        assertTrue("the build never became a ship managed at its build site: " + info,
                info != null && info.contains("\"managed\":true"));

        String tp = exec("artest vs teleport-ship 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z
                + " " + FAR_X + " " + FAR_Y + " " + FAR_Z);
        assertTrue("the ship could not be moved, so it never left the world blocks it was built from: "
                + tp, tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + FAR_X + " " + FAR_Y + " " + FAR_Z);
        return extractString(info, "id");
    }

    private String stage(int x, int y, int z) throws Exception {
        return exec("artest damage stage 0 " + x + " " + y + " " + z);
    }

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " "
                + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " "
                + (baseZ - 4) + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " "
                + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private static double sq(double v) {
        return v * v;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static long readLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static double readDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9][0-9.eE+-]*)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Double.parseDouble(m.group(1));
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
