package zmaster587.advancedRocketry.test.server;

import org.junit.After;
import org.junit.Test;

import zmaster587.advancedRocketry.universe.GalaxyGenConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The sky feed of a slot cell belongs to the CELL, not to a ship's lifecycle stage.
 *
 * <p>The render feed ({@code SystemBodiesProducer} &rarr; {@code PacketSystemBodiesSync} &rarr;
 * {@code BoundarySky}) is what tells a client which bodies to draw around a live cell. This test drives
 * the PRODUCTION producer on a real server and reads back the feed it would broadcast (the
 * {@code space bodies} probe reports the actual packet, so the observation cannot drift from what is
 * sent), for the two arrangements where a cell is live but no ship in it is settled:</p>
 *
 * <ol>
 *   <li><b>Nobody's ship is in the cell at all.</b> The cell is held live by an occupant with no ship —
 *       the state of a crew member whose ship departed without him, or a passenger dropped in by an
 *       on-ramp. His sky is the cell's.</li>
 *   <li><b>The only ship in the cell is mid-jump.</b> Measured off a real session: a pilot sitting in a
 *       live cell world (slot bound, six bodies in it) whose ship the ledger called {@code IN_TRANSIT}
 *       after an arrival crossing stranded it. Every body in his sky disappeared, and a blank sky looks
 *       exactly like an empty cell — which is why this leg exists as a test and not as a playtest note.</li>
 * </ol>
 *
 * <p>Both legs are arranged through probes that change only WHICH DATA the producer has (a registered POI,
 * an occupant refcount, a ledger state), never which object or code path produces the feed. Each leg
 * carries its own control: the same cell measured before the POI exists, so a later body count is a real
 * observation rather than a coincidence.</p>
 *
 * <p>No Valkyrien Skies needed — {@code entry-setup} builds the stack without touching physics, and
 * nothing here loads a ship.</p>
 */
public class SystemBodiesFeedFollowsTheCellE2ETest extends AbstractSharedServerTest {

    /**
     * Cells far enough out that nothing else claims them, so the body count of a cell is exactly what
     * this test put in it. The two methods use different cells: the shared server runs both, and a
     * cell is global state.
     *
     * <p><b>The distance that matters is {@code minSpacing/2}, not "away from sector zero".</b> These
     * used to sit at {@code sy = 5000} on the reasoning that a non-zero sector Y kept them clear of the
     * generated fallback stars at {@code sy=sz=0} — which guarded against the wrong neighbour and left
     * both legs failing. A cell is attributed to a stored anchor by
     * {@code UniverseRegistry.storedAnchorNear}, whose reach is HALF THE SUPER-CELL — about 2 501 180
     * cells at the shipped spacing — so {@code sy = 5000} is 0.2 % of the way out and both cells were
     * squarely inside the shipped solar system's own neighbourhood. The feed was answering correctly:
     * it offered the sun, the overworld and a moon at 1.6·10¹¹ blocks (ledger #291).</p>
     *
     * <p>Stated as a multiple of the reach rather than as a literal, so the fixture cannot silently
     * move back inside the neighbourhood the day the spacing is retuned.</p>
     */
    private static final long CLEAR_OF_ANY_ANCHOR =
            3L * (GalaxyGenConfig.DEFAULT_MIN_SPACING / 2L);
    private static final String CELL_NO_SHIP = "31 " + CLEAR_OF_ANY_ANCHOR + " 2";
    private static final String CELL_MID_JUMP = "32 " + CLEAR_OF_ANY_ANCHOR + " 2";

    /** A body a few thousand blocks out, i.e. the geometry a pilot has to fly at to descend. */
    private static final String BODY_LOCAL = "2900 0 -1200";

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @After
    public void clearStack() throws Exception {
        try {
            exec("artest space entry-clear");
        } catch (Exception ignored) {
        }
    }

    @Test
    public void aLiveCellWithNoShipInItIsStillToldWhatIsAroundIt() throws Exception {
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        // Hold the cell live with an occupant refcount and NO ship anywhere in the ledger.
        String occupy = exec("artest space occupy " + CELL_NO_SHIP);
        assertTrue("occupy must materialize the cell: " + occupy, occupy.contains("\"ok\":true"));
        int slotDim = jsonInt(occupy, "slotDim");

        // CONTROL: the cell is live and its feed entry exists, but it holds nothing yet. A later
        // non-zero count is then attributable to the POI and to nothing else.
        String before = exec("artest space bodies");
        assertEquals("a live cell must be in the feed even while it is empty; " + before,
                0, feedBodyCount(before, slotDim));

        String poi = exec("artest space add-poi " + CELL_NO_SHIP + " " + BODY_LOCAL + " PLANET 0 7");
        assertTrue("add-poi must register a descend target: " + poi,
                poi.contains("\"ok\":true") && poi.contains("\"descendTarget\":true"));

        String after = exec("artest space bodies");
        assertEquals("the cell's own body must reach the feed with no ship in the cell at all; "
                + after, 1, feedBodyCount(after, slotDim));
        assertEquals("and it must not have invented a second feed dimension; " + after,
                1, jsonInt(after, "feedDims"));
        assertEquals("no ship was ledgered by any of this; " + after, 0, jsonInt(after, "shipCount"));
    }

    @Test
    public void aLiveCellWhoseOnlyShipIsMidJumpIsStillToldWhatIsAroundIt() throws Exception {
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        String poi = exec("artest space add-poi " + CELL_MID_JUMP + " " + BODY_LOCAL + " MOON 0 7");
        assertTrue("add-poi must register a descend target: " + poi,
                poi.contains("\"ok\":true") && poi.contains("\"descendTarget\":true"));

        // A settled ship first: this is the state the feed already handled, and it is the control that
        // proves the arrangement can produce a body at all.
        String settle = exec("artest space ledger-settle " + CELL_MID_JUMP + " -1");
        assertTrue("ledger-settle must succeed: " + settle, settle.contains("\"ok\":true"));
        int slotDim = jsonInt(settle, "slotDim");
        String shipId = jsonString(settle, "shipId");
        String settled = exec("artest space bodies");
        assertEquals("control: with the ship SETTLED the cell's body is fed; " + settled,
                1, feedBodyCount(settled, slotDim));

        // Now the ship goes mid-jump. Nothing about the WORLD changes: the cell stays bound to the same
        // slot, still holds its body, and whoever is standing in it is still looking at it.
        String transit = exec("artest space ledger-transit " + CELL_MID_JUMP + " " + shipId);
        assertTrue("ledger-transit must record the ship as in transit: " + transit,
                transit.contains("\"state\":\"IN_TRANSIT\""));

        String bodies = exec("artest space bodies");
        assertTrue("the arrangement must really have a non-settled ship in this cell; " + bodies,
                bodies.contains("\"state\":\"IN_TRANSIT\""));
        assertEquals("the cell is still bound to the same slot world; " + bodies,
                slotDim, jsonInt(exec("artest space ledger-get " + shipId), "slotDim"));
        assertEquals("a cell's bodies must not vanish from its sky because a ship in it is mid-jump; "
                + bodies, 1, feedBodyCount(bodies, slotDim));
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * The body count the feed carries for {@code slotDim}, or {@code -1} when the feed does not mention
     * that dimension at all. The two answers are deliberately different: "no bodies here" and "this
     * world is not in the feed" are the two halves a blank sky splits into.
     */
    private static int feedBodyCount(String json, int slotDim) {
        Matcher m = Pattern.compile("\\{\"slotDim\":" + slotDim + ",\"bodyCount\":(\\d+)")
                .matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static int jsonInt(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\":(-?\\d+)").matcher(json);
        assertTrue("probe response carries no numeric \"" + field + "\": " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static String jsonString(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\":\"([^\"]*)\"").matcher(json);
        assertTrue("probe response carries no string \"" + field + "\": " + json, m.find());
        return m.group(1);
    }
}
