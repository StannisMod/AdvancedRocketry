package zmaster587.advancedRocketry.test.server;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * An arrival's seat lookup must answer for the ship it is ASKING ABOUT, however close another craft
 * is parked to the point it asks at.
 *
 * <p>The lookup that seats a crew after a crossing takes a world point and scans the shipyard of the
 * ship it finds there. Resolving that ship by POSITION has no distance bound: it returns the nearest
 * registered craft in the world, which is the right one exactly while there is only one. A player's
 * entry to orbit has already failed this way — the destination cell held a second ship, the arrival
 * scanned that ship's shipyard, found no pilot seat in it (there was none to find, ever) and gave up
 * after 200 attempts with his own seat 51,200 blocks away in the same world.</p>
 *
 * <p><b>Both legs are load-bearing.</b> The BY-POSITION leg is the defect itself, run as the control:
 * it must come back with zero seats, because that is what proves this arrangement can exhibit the
 * bug at all. Only then does the BY-IDENTITY leg's non-zero answer mean anything — without the
 * control it would also pass in a world where every lookup trivially found the seat.</p>
 *
 * <p>Arrangement: a seated craft and a seatless one, assembled 64 blocks apart, and both lookups
 * asked at the SEATLESS craft's position. Gated on the server's real VS presence; skips cleanly
 * otherwise.</p>
 */
public class ArrivalSeatLookupNamesItsOwnShipE2ETest extends AbstractSharedServerTest {

    /** The craft that HAS a pilot seat — the one an arrival would be asking about. */
    private static final int SEATED_X = 5800, SEATED_Y = 80, SEATED_Z = 5800;
    /** A second craft with a flight computer but NO pilot seat, parked far enough to be a separate
     *  ship and near enough to win every position lookup made at its own position. */
    private static final int SEATLESS_X = 5864, SEATLESS_Y = 80, SEATLESS_Z = 5800;

    @Test
    public void theSeatLookupFindsItsOwnShipsSeatWithAnotherCraftNearer() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        // Headless: nobody is near a ship to hold it loaded, and an unloaded ship reads as a missing
        // one. Reset in @After (shared-harness contract).
        exec("artest vs permaload true");

        clearArea(SEATED_X, SEATED_Z);
        clearArea(SEATLESS_X, SEATLESS_Z);

        String seatedAsm = exec("artest rocket assemble 0 "
                + placeFixture(SEATED_X, SEATED_Y, SEATED_Z, "with-pilot-seat"));
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + seatedAsm,
                seatedAsm.contains("\"rocketCount\":0"));
        String seatlessAsm = exec("artest rocket assemble 0 "
                + placeFixture(SEATLESS_X, SEATLESS_Y, SEATLESS_Z, "with-nav-computer"));
        assertTrue("the seatless craft did not become a ship either: " + seatlessAsm,
                seatlessAsm.contains("\"rocketCount\":0"));
        assertTrue("the ships never loaded", waitForLoadedShip(0) >= 2);

        // ARRANGEMENT CHECK, before either leg: the two crafts must be two REGISTERED ships, or the
        // whole question ("which one does the lookup answer for") does not exist in this world.
        String all = exec("artest vs ship-count-all 0");
        assertTrue("fewer than two ships are registered, so no lookup can pick the wrong one: " + all,
                extractInt(all, "count") >= 2);

        String seatedShip = shipUuidAt(SEATED_X, SEATED_Y + 2, SEATED_Z);
        assertNotNull("could not read the seated craft's ship identity — without it the subject leg "
                + "cannot ask about that ship at all", seatedShip);

        // CONTROL — the defect, asked the old way. At the SEATLESS craft's position a position
        // lookup resolves that craft, and its shipyard holds no pilot seat.
        String byPosition = exec("artest vs seat-yard 0 "
                + SEATLESS_X + " " + (SEATLESS_Y + 2) + " " + SEATLESS_Z);
        assertEquals("control leg: a position lookup at the seatless craft must find NO seat. It "
                        + "found one, so this arrangement cannot exhibit a wrong-ship arrival and the "
                        + "subject leg below would pass for the wrong reason: " + byPosition,
                0, extractInt(byPosition, "seats"));

        // SUBJECT — the same point, asked about the seated ship BY IDENTITY.
        String byIdentity = exec("artest vs seat-yard 0 "
                + SEATLESS_X + " " + (SEATLESS_Y + 2) + " " + SEATLESS_Z + " " + seatedShip);
        assertTrue("asked about its own ship, the arrival's seat lookup still scanned whichever "
                        + "shipyard was nearest and found no seat: " + byIdentity,
                extractInt(byIdentity, "seats") >= 1);
    }

    /**
     * The uuid of the ship a POSITION lookup resolves at {@code (x,y,z)} — read off the same
     * diagnostic an arrival prints, which leads with the resolved ship's identity.
     */
    private String shipUuidAt(int x, int y, int z) throws Exception {
        String nearest = extractString(
                exec("artest vs seat-yard 0 " + x + " " + y + " " + z), "nearest");
        if (nearest == null || nearest.startsWith("none") || nearest.startsWith("vs-absent")) {
            return null;
        }
        int space = nearest.indexOf(' ');
        return space < 0 ? nearest : nearest.substring(0, space);
    }

    @After
    public void restoreSharedServerState() throws Exception {
        if (!serverHasVs()) {
            return;
        }
        exec("artest vs permaload false");
    }

    // --- helpers (mirror VSShipEntryE2ETest) --------------------------------------------------

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
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " "
                + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SEATED_Y - 2)
                + " " + (baseZ - 4) + " " + (baseX + 20) + " " + (SEATED_Y + 12) + " " + (baseZ + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        java.util.regex.Matcher bp = java.util.regex.Pattern
                .compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]").matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static String extractString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
