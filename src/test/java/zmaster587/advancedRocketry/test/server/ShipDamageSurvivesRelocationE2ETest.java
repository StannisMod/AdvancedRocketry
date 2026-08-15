package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A damaged ship that is RELOCATED stays damaged, and leaves nothing of its damage behind.
 *
 * <p>The stage of a block with no tile of its own is held by position, in the world the blocks
 * occupy. A ship's blocks live at fixed addresses in a shipyard subspace, and a relocation does not
 * move them — it cuts them out, pastes copies at fresh coordinates and assembles those into a new
 * ship at a new subspace address. Every one of those steps is a place where a position-keyed record
 * can be left behind, and a hull that arrives pristine is a repair the player did not pay for.</p>
 *
 * <h3>Why the count, and not one block's stage</h3>
 * <p>The subject is what the STRUCTURE carries. Asserting one known block's stage would pass a
 * relocation that carried that block and dropped the other eleven, and the damage engine spreads a
 * shot over whatever the ray meets. So the reading is the whole record set of the ship's own yard,
 * compared as a multiset of stages before and after — position-independent on purpose, since the
 * whole point is that the positions change.</p>
 *
 * <h3>The controls</h3>
 * <p>Two, and the test is worthless without them. The ship's subspace address must genuinely CHANGE
 * across the relocation, or both readings are of the same box and nothing was proven. And the source
 * yard must be EMPTY afterwards: a reading that only checks the destination cannot tell a carry from
 * a copy, and a copy leaves records at coordinates the next ship assembled there would inherit.</p>
 */
public class ShipDamageSurvivesRelocationE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** Build site, clear of the other ship scenarios on this shared server. */
    private static final int SRC_X = 7600, SRC_Y = 80, SRC_Z = 7200;
    /** Where the relocation puts the ship down. Far enough that its new yard cannot be the old one. */
    private static final int DST_X = 7600, DST_Y = 96, DST_Z = 7400;
    /** A second build site, for the scenario that mines a block out of a ship instead of moving it. */
    private static final int AFC_X = 7800, AFC_Y = 80, AFC_Z = 7200;

    /**
     * Half-width of the box a yard is read through, around a block known to belong to the ship.
     * Derived from the fixture (~20 blocks across) against the separation between shipyards, which
     * are a chunk claim apart: a 64-block cube around one of this ship's blocks lies inside this
     * ship's own yard and cannot reach a neighbour's.
     */
    private static final int YARD_PROBE_RADIUS = 64;

    /**
     * How far from a build site a "nearest ship" answer may sit and still be the ship this test
     * built. Derived from the fixture's ~20-block span against the 200-block spacing between the
     * sites this class uses; beyond it the reply is about a neighbour.
     */
    private static final double NEAREST_SHIP_IS_OURS_WITHIN = 64.0D;

    /**
     * The subject block, and it has to be ADDED: the rocket fixture is built entirely out of machines,
     * every one of which carries its own wear in its own tile NBT — which travels with the tile and so
     * says nothing about the map this test is about. A plain block has nowhere to put a stage, which is
     * the whole reason the map exists, so the craft is given one.
     *
     * <p>Placed face-adjacent to the pilot seat, before assembly, so the assembly flood-fill welds it
     * into the ship instead of leaving it standing on the pad. The fixture's own geometry is fixed
     * ({@code rocket = base + (3,1,3)}, seat at {@code rocket + (0,4,0)}), so this cell is one step
     * east of the seat both on the pad and, since assembly shifts every block by the same offset, in
     * the shipyard.
     */
    private static final String PLAIN_SUBJECT_BLOCK = "minecraft:iron_block";

    @Test
    public void aRelocatedShipCarriesItsDamageAndLeavesNoneBehind() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());
        exec("artest vs permaload true");
        exec("artest damage clear-impacts");

        clearArea(SRC_X, SRC_Y, SRC_Z);
        clearArea(DST_X, DST_Y, DST_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        addPlainSubjectBlock(SRC_X, SRC_Y, SRC_Z);
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must become a ship, not a rocket: " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the ship never loaded", waitForLoadedShip(0) >= 1);

        double[] pose = shipPose(SRC_X, SRC_Y, SRC_Z);

        // A block of this ship whose subspace address we know, used only as the anchor the yard is
        // read around. The shot below is aimed away from it so that it survives to be that anchor.
        String seat = exec("artest vs find-seat 0 " + (int) pose[0] + " " + (int) pose[1] + " " + (int) pose[2]);
        assertTrue("could not locate the ship's seat, so there is no anchor for the yard: " + seat,
                seat.contains("\"seatFound\":true"));
        int subX = extractInt(seat, "seatX"), subY = extractInt(seat, "seatY"), subZ = extractInt(seat, "seatZ");

        String result = shootThePlainBlock(pose, subX, subY, subZ, 77101);
        assertTrue("the impact spent nothing, so there is no damage to carry:\n" + result,
                readLong(result, "spent") > 0);

        List<String> before = yardDamage(subX, subY, subZ);
        assertTrue("the shot left no record in the ship's yard, so this run measures nothing: it "
                + "struck only blocks that carry their own wear, or none at all.\n" + result,
                !before.isEmpty());
        String beforeRaw = rawYardDamage(subX, subY, subZ);

        // THE RELOCATION — production's own crossing recipe: cut, paste, re-assemble.
        String repack = exec("artest vs ship-repack 0 " + pose[0] + " " + pose[1] + " " + pose[2]
                + " " + DST_X + " " + DST_Y + " " + DST_Z);
        assertTrue("the ship was not relocated, so there is nothing to measure: " + repack,
                repack.contains("\"ok\":true"));
        assertTrue("the relocated ship never loaded", waitForLoadedShip(0) >= 1);

        double[] movedPose = shipPose(DST_X, DST_Y, DST_Z);
        String movedSeat = exec("artest vs find-seat 0 " + (int) movedPose[0] + " "
                + (int) movedPose[1] + " " + (int) movedPose[2]);
        assertTrue("could not locate the relocated ship's seat: " + movedSeat,
                movedSeat.contains("\"seatFound\":true"));
        int newSubX = extractInt(movedSeat, "seatX");
        int newSubY = extractInt(movedSeat, "seatY");
        int newSubZ = extractInt(movedSeat, "seatZ");

        // ARRANGEMENT CONTROL. If the ship came back at the same subspace address, both readings are
        // of the same box and this test would pass with every carry deleted.
        assertTrue("the relocated ship kept its old subspace address (" + subX + "," + subY + ","
                + subZ + "): both readings are of the same box, so nothing here is evidence",
                newSubX != subX || newSubY != subY || newSubZ != subZ);

        List<String> after = yardDamage(newSubX, newSubY, newSubZ);
        // The paste site is named in the failure message because "the new yard is empty" has two very
        // different causes — the capture never carried the records, or the assembly that followed did
        // not take them along — and only a reading between the two steps tells them apart.
        assertEquals("the relocated ship does not carry the damage it left with."
                + "\n  seat subspace before: " + subX + "," + subY + "," + subZ
                + "   after: " + newSubX + "," + newSubY + "," + newSubZ
                + "\n  records before: " + beforeRaw
                + "\n  records now in the new yard: " + rawYardDamage(newSubX, newSubY, newSubZ)
                + "\n  records now at the paste site (" + DST_X + "," + DST_Y + "," + DST_Z + "): "
                + rawYardDamage(DST_X, DST_Y, DST_Z)
                + "\n  records now in the OLD yard: " + rawYardDamage(subX, subY, subZ),
                before, after);

        // CONTROL. Carried, not copied: what stayed behind would be inherited by the next ship built
        // at those coordinates.
        List<String> leftBehind = yardDamage(subX, subY, subZ);
        assertTrue("the relocation left " + leftBehind.size() + " damage records at the vacated "
                + "subspace address " + subX + "," + subY + "," + subZ + ": " + leftBehind,
                leftBehind.isEmpty());
    }

    /**
     * No single block is the custodian of the hull's condition — specifically not the flight computer,
     * the one block a player can always reach and replace.
     *
     * <p>The obvious home for a ship's damage map is the flight computer's NBT, because a relocation
     * copies tile NBT verbatim and the map would travel for free. It would also mean that mining that
     * one block and putting it back returns a wrecked hull to the showroom, which is why the map lives
     * nowhere a player can pick up. This test is the pin on that: it fails the moment the map is moved
     * onto any block's tile.</p>
     */
    @Test
    public void breakingAndReplacingTheFlightComputerDoesNotRepairTheHull() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());
        exec("artest vs permaload true");
        exec("artest damage clear-impacts");

        clearArea(AFC_X, AFC_Y, AFC_Z);
        String coords = placeFixture(AFC_X, AFC_Y, AFC_Z, "with-pilot-seat");
        addPlainSubjectBlock(AFC_X, AFC_Y, AFC_Z);
        assertTrue("the build did not become a ship",
                exec("artest rocket assemble 0 " + coords).contains("\"rocketCount\":0"));
        assertTrue("the ship never loaded", waitForLoadedShip(0) >= 1);

        double[] pose = shipPose(AFC_X, AFC_Y, AFC_Z);
        String seat = exec("artest vs find-seat 0 " + (int) pose[0] + " " + (int) pose[1] + " " + (int) pose[2]);
        assertTrue("could not locate the ship's seat: " + seat, seat.contains("\"seatFound\":true"));
        int subX = extractInt(seat, "seatX"), subY = extractInt(seat, "seatY"), subZ = extractInt(seat, "seatZ");

        // The fixture puts the flight computer one block west of and one below the seat, and an
        // assembly shifts every block of the craft by the same offset — so the relative layout, and
        // this offset with it, is the same in the shipyard as it was on the pad.
        int afcX = subX - 1, afcY = subY - 1, afcZ = subZ;
        String afcState = exec("artest damage stage 0 " + afcX + " " + afcY + " " + afcZ);
        String afcBlock = extractString(afcState, "block");
        // ARRANGEMENT CONTROL: without this the test would happily break a hull plate and prove nothing.
        assertTrue("the derived offset does not point at the flight computer but at " + afcBlock
                + " — the fixture layout this test assumes has changed", afcBlock != null
                && afcBlock.toLowerCase(java.util.Locale.ROOT).contains("flightcomputer"));

        String result = shootThePlainBlock(pose, subX, subY, subZ, 77102);
        assertTrue("the impact spent nothing, so there is no damage to preserve:\n" + result,
                readLong(result, "spent") > 0);

        List<String> before = yardDamage(subX, subY, subZ);
        assertTrue("the shot left no record, so this run measures nothing:\n" + result, !before.isEmpty());

        // Mine the flight computer out, and put an identical one back — the whole exploit, in two calls.
        String afcPos = afcX + " " + afcY + " " + afcZ + " " + afcX + " " + afcY + " " + afcZ;
        assertTrue("could not remove the flight computer",
                exec("artest fill 0 " + afcPos + " minecraft:air").contains("\"ok\":true"));
        assertEquals("the flight computer is still standing, so nothing was removed",
                "minecraft:air", extractString(exec("artest damage stage 0 " + afcX + " " + afcY
                        + " " + afcZ), "block"));
        assertTrue("could not put the flight computer back",
                exec("artest fill 0 " + afcPos + " " + afcBlock).contains("\"ok\":true"));
        assertEquals("the replacement flight computer is not there", afcBlock,
                extractString(exec("artest damage stage 0 " + afcX + " " + afcY + " " + afcZ), "block"));

        assertEquals("replacing the flight computer changed the hull's damage (before=" + before
                + " after=" + yardDamage(subX, subY, subZ) + ")", before, yardDamage(subX, subY, subZ));
    }

    /**
     * Weld one plain block onto the craft, face-adjacent to the pilot seat, before it is assembled.
     * See {@link #PLAIN_SUBJECT_BLOCK} for why the fixture cannot supply one.
     */
    private void addPlainSubjectBlock(int baseX, int baseY, int baseZ) throws Exception {
        int x = baseX + 4, y = baseY + 5, z = baseZ + 3;   // rocket+(1,4,0) — one east of the seat
        String fill = exec("artest fill 0 " + x + " " + y + " " + z + " " + x + " " + y + " " + z
                + " " + PLAIN_SUBJECT_BLOCK);
        assertTrue("could not add the plain subject block: " + fill, fill.contains("\"ok\":true"));
    }

    /**
     * Fire straight down through the plain block welded beside the seat, and confirm the shot reached
     * the ship. Aimed at that column rather than the seat's so the seat survives to be the anchor the
     * yard is read around.
     */
    private String shootThePlainBlock(double[] pose, int seatSubX, int seatSubY, int seatSubZ, int impactId)
            throws Exception {
        int subjX = seatSubX + 1, subjY = seatSubY, subjZ = seatSubZ;
        String subject = exec("artest damage stage 0 " + subjX + " " + subjY + " " + subjZ);
        // ARRANGEMENT CONTROL: if this is not the block we welded on, the shot below is aimed at
        // whatever the fixture happens to put there, and a green run would prove nothing.
        assertEquals("the cell east of the seat is not the plain block this test welded on — the "
                + "fixture layout it assumes has changed", PLAIN_SUBJECT_BLOCK,
                extractString(subject, "block"));

        String mapped = exec("artest vs to-world 0 " + pose[0] + " " + pose[1] + " " + pose[2]
                + " " + subjX + " " + subjY + " " + subjZ);
        assertTrue("the subject's subspace address could not be mapped to a world point: " + mapped,
                mapped.contains("\"ok\":true"));
        String result = exec("artest damage impact 0 " + extractDouble(mapped, "worldX") + " "
                + (extractDouble(mapped, "worldY") + 6.0D) + " " + extractDouble(mapped, "worldZ")
                + " 0 -1 0 200000 KINETIC " + impactId);
        assertTrue("the impact resolved to no ship, so nothing on this hull was damaged:\n" + result,
                result.contains("\"onShip\":true"));
        return result;
    }

    /**
     * Where the ship built near the site actually IS, as VS sees it — which is not the build site:
     * assembly gives the craft a pose of its own, and the frame conversions refuse a point no ship
     * occupies.
     *
     * <p>`ship-info` answers about the ship NEAREST the point, so the reply is checked against the
     * site before it is believed. The bound is the fixture's own size (~20 blocks across) against the
     * 200-block spacing between this class's build sites: a pose further away than this is a
     * neighbouring scenario's ship, and every number derived from it would be about the wrong hull.</p>
     */
    private double[] shipPose(int siteX, int siteY, int siteZ) throws Exception {
        String info = exec("artest vs ship-info 0 " + siteX + " " + siteY + " " + siteZ);
        assertTrue("no ship is managed near (" + siteX + "," + siteY + "," + siteZ + "): " + info,
                info.contains("\"managed\":true"));
        double px = extractDouble(info, "posX");
        double py = extractDouble(info, "posY");
        double pz = extractDouble(info, "posZ");
        double away = Math.sqrt((px - siteX) * (px - siteX) + (py - siteY) * (py - siteY)
                + (pz - siteZ) * (pz - siteZ));
        assertTrue("the nearest ship to (" + siteX + "," + siteY + "," + siteZ + ") sits " + away
                + " blocks away at (" + px + "," + py + "," + pz + "): that is another scenario's "
                + "ship, not the one this test built", away < NEAREST_SHIP_IS_OURS_WITHIN);
        return new double[]{px, py, pz};
    }

    /**
     * What the ship's yard holds, as a sorted multiset of "stage/destroyed" readings — deliberately
     * without positions, because the positions are what a relocation changes.
     */
    private List<String> yardDamage(int anchorX, int anchorY, int anchorZ) throws Exception {
        String json = rawYardDamage(anchorX, anchorY, anchorZ);
        assertTrue("the damage records could not be read: " + json, json.contains("\"ok\":true"));
        List<String> readings = new ArrayList<>();
        Matcher m = Pattern.compile("\"stage\":(-?\\d+),\"wasDestroyed\":(true|false),"
                + "\"destroyedBlock\":\"([^\"]*)\"").matcher(json);
        while (m.find()) {
            readings.add(m.group(1) + "/" + m.group(2) + "/" + m.group(3));
        }
        assertEquals("the entry list and the count disagree: " + json,
                extractInt(json, "count"), readings.size());
        Collections.sort(readings);
        return readings;
    }

    /** The same reading with its POSITIONS intact — for failure messages, where they are the evidence. */
    private String rawYardDamage(int anchorX, int anchorY, int anchorZ) throws Exception {
        return exec("artest damage records 0 "
                + (anchorX - YARD_PROBE_RADIUS) + " " + Math.max(0, anchorY - YARD_PROBE_RADIUS) + " "
                + (anchorZ - YARD_PROBE_RADIUS) + " "
                + (anchorX + YARD_PROBE_RADIUS) + " " + (anchorY + YARD_PROBE_RADIUS) + " "
                + (anchorZ + YARD_PROBE_RADIUS));
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

    private void clearArea(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (baseY - 2) + " " + (baseZ - 4)
                + " " + (baseX + 20) + " " + (baseY + 12) + " " + (baseZ + 20) + " minecraft:air").contains("\"ok\":true"));
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
