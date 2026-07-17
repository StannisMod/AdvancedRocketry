package zmaster587.advancedRocketry.test.server;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * E2E: does the tier-2 ENTRY ON-RAMP take a piloted ship from a planet dimension into space through the
 * REAL gameplay path? A {@code with-pilot-seat} ship is assembled in the overworld, the entry stack is
 * installed, and a pilot presence + a climb PAST the dimension's orbit ceiling are arranged. The
 * <b>flight computer's own server tick</b> then detects the crossing and calls
 * {@code SpaceSubsystem.entry().requestEntry()} — production code, not the probe — which materializes the
 * launch body's cell, crosses the ship into it, and settles it in the {@code ShipLedger}.
 *
 * <p>Witnesses: the ship becomes ledgered as {@code SETTLED} at the SAME cell the production launch-coord
 * resolver answers for the launch dimension (gen-agnostic — no pinned coordinates), and its settled cell
 * world is live. CONTROL: {@code entry-status} reports zero ledgered ships before the climb, proving a
 * later "settled" is a real observation. This is the "real gameplay path calls materialize" acceptance of
 * the entry design; it composes the proven per-ship crossing with the entry state machine (pinned
 * deterministically by {@code ShipEntryControllerTest}).</p>
 *
 * <p>Gated on the server's real VS presence (run with {@code -PwithVS}); skips cleanly otherwise.</p>
 */
public class VSShipEntryE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** Where the piloted ship is built (a loaded overworld region, well clear of other tests). */
    private static final int SRC_X = 6000, SRC_Y = 80, SRC_Z = 6000;
    /** A world Y comfortably above the default orbit ceiling (ARConfiguration.orbit = 1000). */
    private static final int ABOVE_CEILING_Y = 1200;

    @Test
    public void aPilotedShipClimbingPastTheCeilingEntersSpaceViaTheFlightComputerTick() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)", serverHasVs());

        // Headless: pin ships loaded so a freshly assembled/crossed ship does not auto-unload between calls.
        exec("artest vs permaload true");
        // Install the entry stack into SpaceSubsystem so the PRODUCTION trigger path runs under the harness.
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        // CONTROL: nothing is ledgered before the climb — a later "settled" is then a real observation.
        String control = exec("artest space entry-status");
        assertEquals("witness sensitivity control — no ship must be ledgered before the climb: " + control,
                0, extractInt(control, "ships"));

        // Build a piloted tier-2 ship in the overworld and assemble it into a VS ship.
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip(0) >= 1);

        // The cell the production resolver answers for the launch dimension — the entry MUST land here.
        String launch = exec("artest space launch-cell 0");
        assertTrue("launch-cell resolve failed: " + launch, launch.contains("\"ok\":true"));
        String expectedCell = extractString(launch, "cellKey");
        assertTrue("launch dim resolved to no cell: " + launch, expectedCell != null);

        // Locate the ship, then arrange the entry preconditions: a pilot (the static FF input channel makes
        // the AFC tick see "someone is flying") and a climb PAST the ceiling (rigid-teleport to Y=1200).
        String srcInfo = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("source ship not managed by VS: " + srcInfo, srcInfo.contains("\"managed\":true"));
        double sx = extractDouble(srcInfo, "posX"), sy = extractDouble(srcInfo, "posY"),
                sz = extractDouble(srcInfo, "posZ");

        exec("artest vs ff-input 0 1 0 0 0 0");          // a held-throttle input => a pilot is flying
        String tp = exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy + " " + (int) sz
                + " " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        assertTrue("climb teleport failed: " + tp, tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        // Keep the crossed ship loadable in its new slot while the async re-assembly settles.
        // (The Ticker drives ShipEntryController.tick() every server tick once the stack is installed.)

        // Poll the ledger: the flight-computer tick fires the entry, the entry crosses + settles the ship.
        String status = "";
        boolean settled = false;
        for (int i = 0; i < 120; i++) {   // ~30 s ceiling: async crossing + re-assembly + settle
            status = exec("artest space entry-status");
            if (extractInt(status, "ships") >= 1 && "SETTLED".equals(extractString(status, "state"))) {
                settled = true;
                break;
            }
            // Keep the destination slots' ships load-queued (headless has no player to auto-load them).
            loadAllEntrySlots(setup);
            Thread.sleep(250);
        }
        assertTrue("ship never entered space via the flight-computer tick (not SETTLED); last status="
                + status, settled);

        // The entry landed in the launch body's OWN cell — the C-1 resolution, matched gen-agnostically.
        assertEquals("entry settled in a different cell than the launch resolver answers", expectedCell,
                extractString(status, "cellKey"));
        int slotDim = extractInt(status, "slotDim");
        assertTrue("settled slot dim not reported: " + status, slotDim > Integer.MIN_VALUE);
        assertTrue("the settled ship's cell world is not live in a slot; status=" + status
                + " countAll=" + exec("artest vs ship-count-all " + slotDim),
                waitForLoadedShip(slotDim) >= 1);
    }

    @After
    public void cleanup() throws Exception {
        if (serverHasVs()) {
            exec("artest space entry-clear");
            exec("artest vs permaload false");
        }
    }

    // --- helpers (mirror VSShipCrossingSpikeTest / VSShipTransitE2ETest) -----------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private void loadAllEntrySlots(String setup) throws Exception {
        Matcher m = Pattern.compile("\"dims\":\\[(-?\\d+),(-?\\d+)]").matcher(setup);
        if (m.find()) {
            exec("artest vs load-ships " + m.group(1));
            exec("artest vs load-ships " + m.group(2));
        }
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
