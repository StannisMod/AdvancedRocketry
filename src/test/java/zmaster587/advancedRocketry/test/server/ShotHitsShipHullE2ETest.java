package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
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
 * about this shot. The shot's own position after the crossing is then checked against the ship's
 * WORLD position — with the mapping-back-out leg deleted it would be five million blocks away in a
 * shipyard nobody can see, and every other assertion here would still pass.</p>
 *
 * <p><b>Retired 2026-08-17, and worth saying why</b>: this used to assert that the round ENDED at the
 * hull. It no longer does, because penetration takes time — a round carrying more than the hull costs
 * is now correct to punch through and fly on, and a test that kept the old clause would be pinning
 * behaviour the game deliberately dropped. What replaced it says the same thing about the frame
 * without saying anything about stopping power: the round's budget fell by what the hull cost it.</p>
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
    /**
     * How much of a block's destruction price the round is given, as a multiple. Enough to spend into
     * the hull and stop inside it, not enough to bore out the far side — since penetration takes time
     * a round richer than the hull is CORRECT to fly on through, and this test is about frames, not
     * about stopping power. Priced off the target block's own cost, never hard-coded: that cost comes
     * from the toughness table, which is balance and moves.
     */
    private static final double BUDGET_IN_BLOCKS = 1.5D;

    @Test
    public void aShotFindsAMovedShipsHullInItsOwnFrameAndDamagesTheRightBlock() throws Exception {
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
        int energy = (int) Math.round(readLong(before, "stageCost") * Math.max(1L,
                readLong(before, "maxStage")) * BUDGET_IN_BLOCKS);
        assertTrue("the target block has no price, so the round's budget would be meaningless: "
                + before, energy > 0);
        long id = readLong(exec("artest shot fire 0 " + worldX + " " + (worldY + 30.0D) + " " + worldZ
                + " 0 " + (-SPEED) + " 0 " + energy + " 40"), "id");
        assertTrue("the launch was refused, so nothing else here means anything", id > 0);
        exec("artest shield tick 0");

        String after = exec("artest shot read 0 " + id);
        assertTrue("the shot must still be readable — present in flight, or remembered as ended: "
                + after, after.contains("\"ok\":true"));
        assertTrue("the shot's budget is untouched after a step that crossed the hull — a segment"
                + " computed in the world frame finds nothing where a ship visibly is, which is"
                + " exactly what this substrate maps around: " + after,
                readLong(after, "energy") < energy);

        // The damage landed on the SHIP's own block, at its subspace address.
        String hull = stage(subX, subY, subZ);
        boolean staged = readLong(hull, "stage") > 0;
        boolean destroyed = hull.contains("\"wasDestroyed\":true")
                || hull.contains("\"block\":\"minecraft:air\"");
        assertTrue("the shot reported hitting structure but the ship's own block is untouched at its"
                + " subspace address (before=" + before + " after=" + hull + "): the impact was handed"
                + " over in the wrong frame, or to the wrong target", staged || destroyed);

        // And the crossing was expressed in WORLD coordinates. Without the mapping back out the shot
        // would be sitting at a shipyard address millions of blocks from anything a player can see —
        // and every assertion above would still have passed. The bound is generous on purpose: it is
        // a millions-of-blocks error this is built to catch, not a metre.
        double atX = readDouble(after, "x"), atY = readDouble(after, "y"), atZ = readDouble(after, "z");
        double offSeat = Math.sqrt(sq(atX - worldX) + sq(atY - worldY) + sq(atZ - worldZ));
        assertTrue("after crossing the hull the shot is at (" + atX + "," + atY + "," + atZ + "), "
                + offSeat + " blocks from the world point it was fired through: the crossing point was"
                + " never mapped out of the ship's frame", offSeat < 400.0D);
    }

    /** A second site: two ship scenarios on one shared server must not build over each other. */
    private static final int MOVE_SRC_X = 6700, MOVE_SRC_Y = 80, MOVE_SRC_Z = 6400;
    private static final int MOVE_FAR_X = 6700, MOVE_FAR_Y = 150, MOVE_FAR_Z = 8800;
    /** Where the ship goes WHILE the round is inside it — far enough that no tolerance can absorb it. */
    private static final int MOVE_AGAIN_X = 6700, MOVE_AGAIN_Y = 150, MOVE_AGAIN_Z = 9100;

    @Test
    public void aRoundDrillingAHullGoesWhereTheShipGoes() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());
        exec("artest vs permaload true");
        exec("artest shot clear 0");

        String shipId = buildAndMoveShip(MOVE_SRC_X, MOVE_SRC_Y, MOVE_SRC_Z,
                MOVE_FAR_X, MOVE_FAR_Y, MOVE_FAR_Z);
        String seat = exec("artest vs find-seat 0 id " + shipId);
        assertTrue("could not locate the ship's seat, so there is no known block to lodge in: " + seat,
                seat.contains("\"seatFound\":true"));
        int subX = extractInt(seat, "seatX"), subY = extractInt(seat, "seatY"),
                subZ = extractInt(seat, "seatZ");
        String mapped = exec("artest vs to-world 0 " + MOVE_FAR_X + " " + MOVE_FAR_Y + " " + MOVE_FAR_Z
                + " " + subX + " " + subY + " " + subZ);
        assertTrue("the seat's subspace address could not be mapped to a world point: " + mapped,
                mapped.contains("\"ok\":true"));
        double worldX = extractDouble(mapped, "worldX");
        double worldY = extractDouble(mapped, "worldY");
        double worldZ = extractDouble(mapped, "worldZ");

        // A round too poor to buy even one stage: it lodges in the plate without damaging it. That is
        // deliberate — this test is about WHERE a lodged round is, and a round that chews its way out
        // (or dies paying) leaves nothing to ask the question about.
        String plate = stage(subX, subY, subZ);
        long stageCost = readLong(plate, "stageCost");
        assertTrue("the plate has no price, so no budget can be chosen against it: " + plate,
                stageCost > 1);
        int energy = (int) (stageCost / 2);

        // Slow, so it spends several ticks crossing one block rather than passing through in one.
        long id = readLong(exec("artest shot fire 0 " + worldX + " " + (worldY + 1.5D) + " " + worldZ
                + " 0 -0.2 0 " + energy + " 400"), "id");
        assertTrue("the launch was refused, so nothing else here means anything", id > 0);

        String lodged = null;
        for (int tick = 0; tick < 40 && lodged == null; tick++) {
            exec("artest shield tick 0");
            String read = exec("artest shot read 0 " + id);
            if (read.contains("\"hull\":\"")) {
                lodged = read;
            } else if (read.contains("\"present\":false")) {
                break;
            }
        }
        assertTrue("the round never came to be inside the hull — with nothing lodged there is no frame"
                + " question to ask: " + exec("artest shot read 0 " + id), lodged != null);

        double hullX = readDouble(lodged, "hullX");
        double hullY = readDouble(lodged, "hullY");
        double hullZ = readDouble(lodged, "hullZ");
        double beforeX = readDouble(lodged, "x");
        double beforeZ = readDouble(lodged, "z");

        // The ship manoeuvres with the round still in it. No tick of the shot in between: what moves
        // is the SHIP, and the only question is whether the round is a part of it or a thing left
        // hanging in the air where the ship used to be.
        String tp = exec("artest vs teleport-ship 0 " + MOVE_FAR_X + " " + MOVE_FAR_Y + " " + MOVE_FAR_Z
                + " " + MOVE_AGAIN_X + " " + MOVE_AGAIN_Y + " " + MOVE_AGAIN_Z);
        assertTrue("the ship could not be moved, so this run never tested a manoeuvre: " + tp,
                tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + MOVE_AGAIN_X + " " + MOVE_AGAIN_Y + " " + MOVE_AGAIN_Z);

        String after = exec("artest shot read 0 " + id);
        assertTrue("the round left the hull when the ship moved: " + after,
                after.contains("\"hull\":\""));
        // ACROSS the bore the plate holds it exactly: a ship's translation must not show up as the
        // round sliding sideways inside its own hole. ALONG the bore it keeps drilling, because this
        // is a live server whose world ticks on its own — so the claim there is that it advanced by a
        // few tenths of a block of boring and not by the hundreds the ship travelled.
        assertEquals("the round moved sideways WITHIN the plate because the ship moved — the hull's"
                + " own motion must not reach its frame at all: " + after,
                hullX, readDouble(after, "hullX"), 1.0E-6D);
        assertEquals(hullZ, readDouble(after, "hullZ"), 1.0E-6D);
        double boredFurther = hullY - readDouble(after, "hullY");
        assertTrue("along the bore the round went " + boredFurther + " blocks while the ship travelled "
                + Math.abs(MOVE_AGAIN_Z - MOVE_FAR_Z) + ": it is being carried, not drilling: " + after,
                boredFurther >= 0.0D && boredFurther < 4.0D);

        double movedZ = readDouble(after, "z") - beforeZ;
        double shipMovedZ = MOVE_AGAIN_Z - MOVE_FAR_Z;
        assertEquals("the round stayed where the ship USED to be: a body inside a hull that manoeuvres"
                + " travels with it, and one stored in world coordinates does not. " + after,
                shipMovedZ, movedZ, 8.0D);
        assertEquals("the round drifted across the manoeuvre on an axis the ship did not move along: "
                + after, 0.0D, readDouble(after, "x") - beforeX, 8.0D);

        // And it goes on from the ship's NEW place. Not "it is still lodged": a round that finished
        // drilling through the plate and came out the far side has left the hull for good reasons, and
        // a test that demanded it still be inside would be pinning how thick this fixture's plate is.
        // What must hold either way is where it carries on FROM — a round resumed off a stale world
        // position would be back at the site the ship left, hanging in the air.
        exec("artest shield tick 0");
        String resumed = exec("artest shot read 0 " + id);
        if (resumed.contains("\"present\":true")) {
            assertEquals("after the manoeuvre the round carried on from where the ship USED to be: "
                    + resumed, (double) MOVE_AGAIN_Z, readDouble(resumed, "z"), 8.0D);
        }

        exec("artest shot clear 0");
    }

    /** Build the fixture, assemble it into a ship and move it far from where it was built. */
    private String buildAndMoveShip() throws Exception {
        return buildAndMoveShip(SRC_X, SRC_Y, SRC_Z, FAR_X, FAR_Y, FAR_Z);
    }

    /** The same, at a site of the caller's choosing — two ship scenarios must not share a build site. */
    private String buildAndMoveShip(int srcX, int srcY, int srcZ, int farX, int farY, int farZ)
            throws Exception {
        clearArea(srcX, srcZ);
        String coords = placeFixture(srcX, srcY, srcZ, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must become a ship, not a rocket: " + asm,
                asm.contains("\"rocketCount\":0"));

        String info = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            exec("artest vs load-ships 0");
            info = exec("artest vs ship-info 0 " + srcX + " " + srcY + " " + srcZ);
            if (info.contains("\"managed\":true")) {
                break;
            }
            Thread.sleep(250);
        }
        assertTrue("the build never became a ship managed at its build site: " + info,
                info != null && info.contains("\"managed\":true"));

        String tp = exec("artest vs teleport-ship 0 " + srcX + " " + srcY + " " + srcZ
                + " " + farX + " " + farY + " " + farZ);
        assertTrue("the ship could not be moved, so it never left the world blocks it was built from: "
                + tp, tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + farX + " " + farY + " " + farZ);
        return extractString(info, "id");
    }

    private String stage(int x, int y, int z) throws Exception {
        return exec("artest damage stage 0 " + x + " " + y + " " + z);
    }

    /** Cleared around the build height every fixture here is placed at. */
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
