package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Ignore;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A space cell has no gravity, so a craft released in one does not fall.
 *
 * <h2>The third case, and the only one nothing watched</h2>
 *
 * <p>The per-world gravity field answers three ways: a registered body scales the configured vector by
 * its own multiplier, a foreign or vanilla world gets that vector unchanged, and a space cell gets
 * ZERO. The first is witnessed by a craft that falls a quarter as far over a quarter-gravity body; the
 * second is the untouched path every other world already flew. The cell was pinned by nothing at all —
 * and it is the case a player spends the whole tier-2 game inside.</p>
 *
 * <h2>Why "it did not move" needs a control, here more than anywhere</h2>
 *
 * <p>On a planet, a craft that does not fall is doing something: holding. In a cell there is nothing
 * to hold against, and <b>a craft in zero gravity is indistinguishable from a craft nobody is
 * simulating</b> — both sit exactly still, both report zero velocity, and the second is a defect this
 * mod has actually shipped: physics used to be switched on only after a craft had been flown once, so
 * a newly built ship hung in the air and read as station-keeping.</p>
 *
 * <p>So stillness is asserted only AFTER the same craft is shown to be under the solver's hand: it is
 * pushed, and it must translate. A build where the ship is inert fails that leg and never reaches the
 * one below — which is the whole point, because on such a build the stillness below would be true for
 * the wrong reason and the test would certify the wrong thing.</p>
 *
 * <h2>Why it is ignored, and what would un-ignore it</h2>
 *
 * <p>The control leg needs a ship that can be DRIVEN, and this tier cannot drive one: a Valkyrien
 * Skies ship assembled on a headless server never becomes loaded, because with no client near it the
 * physics mod leaves it in the registry and never pulls its chunks in. {@code VSShipMotionServerTest}
 * carries the same limit in its own ignore reason and was the first casualty of it. Measured here as
 * a craft that answers a 4 blocks/s push with 0.0 blocks of travel, through three arrangements — as
 * the transit setup leaves it, plus a force-load, plus an unpark.</p>
 *
 * <p>So this is not a defect in the cell or in gravity: it is a test written at the wrong tier. It is
 * kept as executable documentation of the arrangement, and belongs at the CLIENT tier, where ship
 * load and drive already work — moving it is the work, not repairing it.</p>
 *
 * <p>Needs the physics mod: without it there is no ship and no cell to put one in.</p>
 */
@Ignore("The control leg needs a DRIVEN ship, which the server tier cannot provide: a VS ship on a "
        + "headless server never becomes loaded (no client near it), so a push moves it 0.0 blocks - "
        + "measured through three arrangements. Same limit VSShipMotionServerTest is ignored for. "
        + "Not a defect in the cell: the witness belongs at the client tier and is kept here as "
        + "executable documentation of its arrangement until it is moved.")
public class AShipInASpaceCellDoesNotFallE2ETest extends AbstractSharedServerTest {

    /**
     * How far the craft may drift vertically and still count as not falling, in blocks. Generous
     * against the settle a freshly assembled hull does, and far below what a fall covers: at the
     * configured field a released craft clears fifty blocks in the window used here, so the two
     * readings cannot be confused.
     */
    private static final double STILL_TOLERANCE = 2.0;

    /** The push used to prove the craft is being simulated at all, in blocks per second. */
    private static final double PUSH_VZ = 4.0;

    /** How far it must actually travel under that push before the stillness leg means anything. */
    private static final double PUSH_MIN_TRAVEL = 3.0;

    private static final int SAMPLE_TICKS = 40;

    @Test
    public void aReleasedCraftInACellKeepsItsAltitudeAndIsStillBeingSimulated() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        exec("artest vs permaload true");

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("the cell arrangement failed: " + setup, setup.contains("\"ok\":true"));
        int cellDim = extractInt(setup, "originDim");
        assertTrue("ARRANGEMENT: the ship never assembled in the cell (dim " + cellDim + ")",
                waitForLoadedShip(cellDim) >= 1);

        String info = exec("artest vs ship-info " + cellDim + " 1 64 1");
        assertTrue("ARRANGEMENT: no ship answered in the cell: " + info, info.contains("\"managed\":true"));
        String shipId = extractString(info, "id");
        assertTrue("ARRANGEMENT: the ship answered without an identity: " + info, shipId != null);

        // Hand the craft to the solver explicitly. A headless server has nobody standing near a ship,
        // and the physics mod decides loadedness from player proximity every tick - so without this a
        // craft sits inert for a reason that has nothing to do with gravity, which is exactly the
        // confusion the control leg below exists to refuse.
        assertTrue("ARRANGEMENT: the cell's ships must be loaded and simulated: nobody is near them",
                exec("artest vs load-ships " + cellDim).contains("\"requested\":"));

        // ...and unpark it. A craft placed by a paste is rigid until something hands it back, and a
        // rigid craft is exactly as still as a weightless one.
        exec("artest vs unpark " + cellDim + " " + (int) extractDouble(info, "posX") + " "
                + (int) extractDouble(info, "posY") + " " + (int) extractDouble(info, "posZ"));

        // Release it: Flight Assist off is what hands an unpiloted craft to the field, whatever the
        // field turns out to be. Over a planet this is what makes it fall.
        assertTrue("could not reach the flight computer to release the craft",
                exec("artest vs fa-by-id " + cellDim + " " + shipId + " false")
                        .contains("\"afcResolved\":true"));

        // --- the subject: released, over nothing, it must keep its altitude ------------------------
        double before = shipY(cellDim, shipId);
        Thread.sleep(SAMPLE_TICKS * 50L);
        double after = shipY(cellDim, shipId);
        double sank = before - after;

        // --- the control, deliberately AFTER the reading and BEFORE the assertion on it -----------
        // Taken second so the push cannot disturb the altitude it is vouching for, and asserted first
        // so a craft nobody is simulating fails HERE, on a leg that says so, instead of passing the
        // stillness leg for a reason that has nothing to do with gravity.
        double zBefore = shipZ(cellDim, shipId);
        assertTrue("could not push the craft: the control leg cannot run",
                exec("artest vs push-ship-by-id " + cellDim + " " + shipId + " 0 0 " + PUSH_VZ)
                        .contains("\"pushed\":true"));
        Thread.sleep(SAMPLE_TICKS * 50L);
        double travelled = Math.abs(shipZ(cellDim, shipId) - zBefore);

        assertTrue("ARRANGEMENT/CONTROL: the craft must be under the solver's hand for its stillness to"
                        + " mean anything. Pushed at " + PUSH_VZ + " blocks/s it moved " + travelled
                        + " blocks, which is not motion. In zero gravity a craft nobody simulates sits"
                        + " exactly as still as one that is weightless, and this mod has shipped that"
                        + " defect before - so the stillness measured above would be true for the wrong"
                        + " reason.",
                travelled >= PUSH_MIN_TRAVEL);

        assertTrue("a released craft in a space cell must keep its altitude: there is nothing for it to"
                        + " fall towards, and the field a cell supplies is zero. This one moved " + sank
                        + " blocks vertically in " + SAMPLE_TICKS + " ticks (from " + before + " to "
                        + after + "). A sink of about fifty blocks is what the configured field would"
                        + " produce, i.e. the cell being handed a planet's gravity.",
                Math.abs(sank) <= STILL_TOLERANCE);
    }

    @org.junit.After
    public void resetPermaload() throws Exception {
        if (serverHasVs()) {
            exec("artest vs permaload false");
        }
    }

    // --- observation --------------------------------------------------------------------------------

    private double shipY(int dim, String shipId) throws Exception {
        return axis(dim, shipId, "posY");
    }

    private double shipZ(int dim, String shipId) throws Exception {
        return axis(dim, shipId, "posZ");
    }

    /** By identity, never "whichever ship is nearest": a pushed craft would outrun a positional query. */
    private double axis(int dim, String shipId, String key) throws Exception {
        String info = exec("artest vs ship-info " + dim + " id " + shipId);
        assertTrue("the ship under test stopped answering: " + info, info.contains("\"managed\":true"));
        return extractDouble(info, key);
    }

    private int waitForLoadedShip(int dim) throws Exception {
        int count = 0;
        for (int i = 0; i < 40; i++) {
            count = extractInt(exec("artest vs ship-count " + dim), "count");
            if (count >= 1) {
                return count;
            }
            Thread.sleep(250);
        }
        return count;
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
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
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
