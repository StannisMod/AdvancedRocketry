package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * What happens when a hull is opened, and what the ship can do about it (D127-9 / D127-6).
 *
 * <p>The three scenarios are one mechanic seen from three sides. A breach must cost the ship its
 * air — <em>lose</em> it, not delete it, which is what happened before: the flood-fill dropped the
 * room's cells and the gases went with them in the same tick, so a hull breach was free. A breached
 * zone must also stop drawing from the plant, or the ship answers a hole by pumping its reserves
 * into space. And closing a bulkhead must actually divide the ship, or "isolate the section before
 * you patch it" is not a move a player can make.</p>
 */
public class BreachAndIsolationTest extends AbstractSharedServerTest {

    private static final Pattern AIR_PRESSURE = Pattern.compile("\"airPressure\":(-?\\d+)");
    private static final Pattern AIR_SOURCE = Pattern.compile("\"airSource\":\"([a-z]+)\"");
    private static final Pattern VENT_HAS_AIR = Pattern.compile("\"ventHasAir\":(true|false)");
    private static final Pattern VENT_PRESSURE = Pattern.compile("\"ventAirPressure\":(-?\\d+)");
    private static final Pattern SINKS = Pattern.compile("\"sinks\":(-?\\d+)");
    private static final Pattern BLOB_SIZE = Pattern.compile("\"blobSize\":(-?\\d+)");

    private static final int CY = 64;
    private static final int CZ = 2900;
    private static final int CX_BREACH = 2000;
    private static final int CX_NO_DRAW = 2200;
    private static final int CX_BULKHEAD = 2400;

    /** D127-9: the air leaves through the hole, over seconds, and it is really gone. */
    @Test
    public void aBreachedRoomLosesItsAirToSpaceInsteadOfLosingItToBookkeeping() throws Exception {
        buildSealedRoom(CX_BREACH);

        String sealed = ventInfo(CX_BREACH);
        assertEquals("premise: the room must start as a live zone: " + sealed,
                "zone", extractString(sealed, AIR_SOURCE));
        int pressureBefore = extract(sealed, VENT_PRESSURE);
        assertTrue("premise: it must start pressurised: " + sealed, pressureBefore > 0);

        // Open the hull. The flood-fill drops the room's cells; the gases are not the cells.
        breach(CX_BREACH);
        forceTick(CX_BREACH, 5);

        String venting = ventInfo(CX_BREACH);
        assertEquals("a breached room is no longer a zone — its cells are gone: " + venting,
                0, extract(venting, BLOB_SIZE));
        assertEquals("the position is in no zone any more — that is what the breach did: " + venting,
                "none", extractString(venting, AIR_SOURCE));
        assertEquals("but the air must still be reachable from the VENT, because it is escaping "
                + "rather than being deleted: " + venting,
                "true", extractString(venting, VENT_HAS_AIR));

        // 20 ticks per drain step; well past what emptying takes at the default rate.
        forceTick(CX_BREACH, 600);

        String emptied = ventInfo(CX_BREACH);
        int pressureAfter = extract(emptied, VENT_PRESSURE);
        assertTrue("the air must actually leave (before=" + pressureBefore + " after="
                + pressureAfter + "): " + emptied, pressureAfter < pressureBefore);
        assertEquals("and keep leaving until the room is vacuum: " + emptied, 0, pressureAfter);
    }

    /**
     * D127-6, the automatic half, under the 2026-08-15 ruling that "the plant cuts a zone it cannot
     * keep safe" means disconnection from the network. A breached zone must stop asking, or the
     * plant spends the ship's reserves on a room that is open to space.
     */
    @Test
    public void aBreachedZoneStopsDrawingFromThePlant() throws Exception {
        buildSealedRoom(CX_NO_DRAW);
        placeDuct(CX_NO_DRAW + 1);
        placePlant(CX_NO_DRAW + 2);
        exec("artest energy inject 0 " + (CX_NO_DRAW + 2) + " " + CY + " " + CZ + " 1000000");
        exec("artest subnet solve lifesupport 0 2");

        String connected = subnetInfo(CX_NO_DRAW + 1);
        assertEquals("premise: a sealed room must be a sink on the ventilation network: " + connected,
                1, extract(connected, SINKS));

        breach(CX_NO_DRAW);
        forceTick(CX_NO_DRAW, 5);
        exec("artest subnet solve lifesupport 0 2");

        String afterBreach = subnetInfo(CX_NO_DRAW + 1);
        assertEquals("a breached zone must stop asking the plant for air — the vent is still a node, "
                + "but it requests nothing: " + afterBreach, 0, extractSinkRequest(afterBreach));
    }

    /**
     * D127-6, the manual half. This one asserts machinery that already existed rather than anything
     * added for life support: a closed airlock door counts as a sealing block, so it divides a hull
     * into separately-maintained volumes. Pinned because the whole isolation story rests on it, and
     * nothing said so.
     */
    @Test
    public void aClosedBulkheadDividesTheHullIntoTwoZones() throws Exception {
        int west = CX_BULKHEAD;
        int east = CX_BULKHEAD + 6;

        // One long hall, a vent at each end, and a doorway between them.
        exec("artest fill 0 " + (west - 2) + " " + (CY - 1) + " " + (CZ - 2)
                + " " + (east + 2) + " " + CY + " " + (CZ + 2) + " minecraft:stone");
        for (int yy = CY + 1; yy <= CY + 2; yy++) {
            exec("artest fill 0 " + (west - 2) + " " + yy + " " + (CZ - 2)
                    + " " + (east + 2) + " " + yy + " " + (CZ + 2) + " minecraft:stone");
            exec("artest fill 0 " + (west - 1) + " " + yy + " " + (CZ - 1)
                    + " " + (east + 1) + " " + yy + " " + (CZ + 1) + " minecraft:air");
        }
        exec("artest fill 0 " + (west - 2) + " " + (CY + 3) + " " + (CZ - 2)
                + " " + (east + 2) + " " + (CY + 3) + " " + (CZ + 2) + " minecraft:stone");

        commissionVent(west);
        commissionVent(east);

        int openHall = extract(ventInfo(west), BLOB_SIZE);
        assertTrue("premise: one open hall must be one large zone: " + openHall, openHall > 0);

        // Wall the middle off, leaving one gap, and close a bulkhead in it.
        int wall = (west + east) / 2;
        exec("artest fill 0 " + wall + " " + (CY + 1) + " " + (CZ - 1)
                + " " + wall + " " + (CY + 2) + " " + (CZ + 1) + " minecraft:stone");
        exec("artest fill 0 " + wall + " " + (CY + 1) + " " + CZ
                + " " + wall + " " + (CY + 2) + " " + CZ + " advancedrocketry:airlock_door");

        exec("artest vent reseal 0 " + west + " " + CY + " " + CZ);
        forceTick(west, 5);

        String divided = ventInfo(west);
        int westZone = extract(divided, BLOB_SIZE);
        assertTrue("a closed bulkhead must divide the hall — the west zone must be smaller than the "
                        + "whole hall was (" + openHall + " → " + westZone + "): " + divided,
                westZone > 0 && westZone < openHall);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private void buildSealedRoom(int cx) throws Exception {
        exec("artest fill 0 " + (cx - 2) + " " + (CY - 1) + " " + (CZ - 2)
                + " " + (cx + 2) + " " + CY + " " + (CZ + 2) + " minecraft:stone");
        for (int yy = CY + 1; yy <= CY + 2; yy++) {
            exec("artest fill 0 " + (cx - 2) + " " + yy + " " + (CZ - 2)
                    + " " + (cx + 2) + " " + yy + " " + (CZ + 2) + " minecraft:stone");
            exec("artest fill 0 " + (cx - 1) + " " + yy + " " + (CZ - 1)
                    + " " + (cx + 1) + " " + yy + " " + (CZ + 1) + " minecraft:air");
        }
        exec("artest fill 0 " + (cx - 2) + " " + (CY + 3) + " " + (CZ - 2)
                + " " + (cx + 2) + " " + (CY + 3) + " " + (CZ + 2) + " minecraft:stone");
        commissionVent(cx);
    }

    private void commissionVent(int cx) throws Exception {
        String vent = exec("artest place 0 " + cx + " " + CY + " " + CZ + " advancedrocketry:oxygenVent");
        assertTrue("vent place failed: " + vent, vent.contains("\"placed\":true"));
        exec("artest energy inject 0 " + cx + " " + CY + " " + CZ + " 1000000");
        exec("artest fluid inject 0 " + cx + " " + CY + " " + CZ + " oxygen 16000");
        forceTick(cx, 1);
        exec("artest vent reseal 0 " + cx + " " + CY + " " + CZ);
        forceTick(cx, 5);
    }

    /** Open the ceiling. One block is a hull breach. */
    private void breach(int cx) throws Exception {
        exec("artest fill 0 " + cx + " " + (CY + 3) + " " + CZ + " "
                + cx + " " + (CY + 3) + " " + CZ + " minecraft:air");
        exec("artest vent reseal 0 " + cx + " " + CY + " " + CZ);
    }

    private void placeDuct(int x) throws Exception {
        String resp = exec("artest place 0 " + x + " " + CY + " " + CZ + " advancedrocketry:ventilationDuct");
        assertTrue("duct place failed: " + resp, resp.contains("\"placed\":true"));
    }

    private void placePlant(int x) throws Exception {
        String resp = exec("artest place 0 " + x + " " + CY + " " + CZ + " advancedrocketry:lifeSupportPlant");
        assertTrue("plant place failed: " + resp, resp.contains("\"placed\":true"));
    }

    private void forceTick(int cx, int ticks) throws Exception {
        exec("artest tile force-tick 0 " + cx + " " + CY + " " + CZ + " " + ticks);
    }

    private String ventInfo(int cx) throws Exception {
        return exec("artest vent info 0 " + cx + " " + CY + " " + CZ);
    }

    private String subnetInfo(int x) throws Exception {
        return exec("artest subnet info lifesupport 0 " + x + " " + CY + " " + CZ);
    }

    private static int extractSinkRequest(String src) {
        return extract(src, Pattern.compile("\"sinkRequested\":(-?\\d+)"));
    }

    private static int extract(String src, Pattern pattern) {
        return Integer.parseInt(extractString(src, pattern));
    }

    private static String extractString(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return m.group(1);
    }
}
