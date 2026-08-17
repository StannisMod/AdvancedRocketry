package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.HYPERSPACE_JUMP_SPEED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>A jump carries what is lying on the deck, not only who is sitting on it.</b> The half of JUMP-11
 * that is not a crew member: a dropped item aboard a ship is at the destination after the jump, and
 * it is aboard the SHIP there rather than merely somewhere in the cell.
 *
 * <h2>Why this tier</h2>
 *
 * There is no client in this contract. A dropped item has no client that owns its movement, reports
 * nothing about itself, and is placed entirely by the server — so a server test IS the honest path,
 * and a client e2e would only add a boot to watch the same server state through a longer pipe.
 *
 * <h2>Why an item rather than a mob</h2>
 *
 * An item has no AI. A mob that ended up somewhere else after the jump could have walked there, and
 * separating "was not carried" from "was carried and then wandered" would need a second measurement;
 * an item that moved was moved by something. It is also the body a player is most likely to have
 * lying about — the thing he dropped while building.
 *
 * <p>Gated on the server's real VS presence (run with {@code -PwithVS}); skips cleanly otherwise.</p>
 */
public class VSJumpCarriesLooseBodiesE2ETest extends AbstractSharedServerTest {

    /** How close to the ship the body must land to count as aboard it rather than merely in the cell. */
    private static final double ABOARD_RADIUS = 8.0;

    @Test
    public void aJumpCarriesTheBodiesLyingOnItsDeck() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)",
                serverHasVs());

        exec("artest vs permaload true");

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup failed: " + setup, setup.contains("\"ok\":true"));
        int originDim = extractInt(setup, "originDim");
        assertTrue("ARRANGEMENT: the origin ship never assembled/loaded (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        // Where the ship actually is in its cell — the deck the body is dropped onto.
        String seat = exec("artest vs find-seat " + originDim + " 1 64 1");
        assertTrue("ARRANGEMENT: the ship must resolve a world position: " + seat,
                seat.contains("\"shipWorldX\""));
        double shipX = extractDouble(seat, "shipWorldX");
        double shipY = extractDouble(seat, "shipWorldY");
        double shipZ = extractDouble(seat, "shipWorldZ");

        // Dropped AT the hull, not above it. A body spawned over a deck is a body falling, and this
        // fixture sits in a void cell: by the time the cut runs it can be well past the ship, which
        // makes "it was not carried" indistinguishable from "it was not there". The ship's own
        // identity is handed in so the probe can answer production's question rather than a proxy.
        String shipId = extractString(exec("artest vs ship-info " + originDim + " " + (int) shipX + " "
                + (int) shipY + " " + (int) shipZ), "id");
        String dropped = exec("artest space loose-body " + originDim + " " + shipX + " " + shipY + " "
                + shipZ + " " + shipId);
        assertTrue("ARRANGEMENT: the body must be dropped: " + dropped, dropped.contains("\"ok\":true"));

        // CONTROL, and it is production's OWN aboard test rather than a proximity proxy: the body has
        // to be inside the ship's stay region — the same volume the crossing enumerates by, and the
        // same one the hyperspace void judges a crew member by. A green here means a later red is
        // about the carry.
        assertTrue("ARRANGEMENT: the dropped body must be ABOARD by the definition the crossing uses,"
                + " not merely near the ship: " + dropped, dropped.contains("\"aboard\":true"));

        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + HYPERSPACE_JUMP_SPEED);
        assertTrue("the transit must begin: " + begin, begin.contains("\"began\":true"));

        int targetDim = -1;
        String lastTick = "";
        for (int i = 0; i < 80 && targetDim < 0; i++) {
            lastTick = exec("artest space transit-tick 10");
            if (extractInt(lastTick, "inTransit") == 0) {
                targetDim = extractInt(lastTick, "targetDim");
                break;
            }
            Thread.sleep(250);
        }
        assertTrue("the jump never completed; last tick=" + lastTick, targetDim >= 0);

        // The placement is retry-based like the crew's, so drive the same retries the crew leg drives.
        String arrived = "";
        boolean carried = false;
        for (int i = 0; i < 60 && !carried; i++) {
            exec("artest space transit-tick 10");
            arrived = exec("artest vs ship-info " + targetDim + " 0 200 0");
            if (arrived.contains("\"posX\"")) {
                double px = extractDouble(arrived, "posX");
                double py = extractDouble(arrived, "posY");
                double pz = extractDouble(arrived, "posZ");
                carried = extractInt(exec("artest space loose-body-count " + targetDim + " " + px + " "
                        + py + " " + pz + " " + ABOARD_RADIUS), "count") >= 1;
            }
            Thread.sleep(250);
        }

        assertTrue("a body lying on the deck must arrive WITH the ship — the crew is not the only "
                + "thing aboard a jump. Ship report at the destination: " + arrived, carried);

        // ...and it is not still lying in the cell it left, which is the failure this replaces: a body
        // left behind is also "somewhere", and only asking both ends tells the two apart.
        int leftBehind = extractInt(exec("artest space loose-body-count " + originDim + " " + shipX
                + " " + shipY + " " + shipZ + " " + ABOARD_RADIUS), "count");
        assertEquals("nothing may be left standing in the origin cell where the ship used to be",
                0, leftBehind);
    }

    @org.junit.After
    public void resetPermaload() throws Exception {
        if (serverHasVs()) {
            exec("artest vs permaload false");
        }
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
                if (extractInt(exec("artest vs ship-count " + dim), "count") >= 1) {
                    return 1;
                }
            }
            Thread.sleep(250);
        }
        return 0;
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        assertTrue("expected string \"" + key + "\" in: " + json, m.find());
        return m.group(1);
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.eE+\\-]+)").matcher(json);
        assertTrue("expected number \"" + key + "\" in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }
}
