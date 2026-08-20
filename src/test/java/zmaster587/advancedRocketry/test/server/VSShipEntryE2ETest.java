package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.TestTimeouts;

import zmaster587.advancedRocketry.space.GalacticCoord;

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

    /**
     * Poll iterations (250 ms apart) allowed for an async crossing to finish settling, stretched by
     * the build's fork factor. 120 was the single-fork sizing; under load the same arrangement needs
     * proportionally longer wall-clock for the same amount of work.
     */
    private static final int SETTLE_POLLS = (int) Math.ceil(120 * TestTimeouts.factor());

    /** Where the piloted ship is built (a loaded overworld region, well clear of other tests). */
    private static final int SRC_X = 6000, SRC_Y = 80, SRC_Z = 6000;
    /** A world Y comfortably above the default orbit ceiling (ARConfiguration.orbit = 1000). */
    private static final int ABOVE_CEILING_Y = 1200;
    /** The jump leg builds its own ship, well clear of the entry leg's region (shared server, both run). */
    private static final int JUMP_SRC_X = 6400, JUMP_SRC_Z = 6400;
    /**
     * A cell key is {@code sx_sy_sz}. The jump target is derived from the ORIGIN, one sector over: the
     * integrator steps {@code speed} blocks per tick, so the time in hyperspace is distance/speed, and a
     * sector is enormous. An absolute far-away target (first attempt: sector 9001 from an origin at 25)
     * leaves the ship legitimately IN_TRANSIT for thousands of ticks and the test reds on its own poll
     * window while production is working correctly.
     */
    private static final Pattern CELL_KEY = Pattern.compile("^(-?\\d+)_(-?\\d+)_(-?\\d+)$");

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

        // A held throttle on THIS ship's own flight computer => a pilot is flying. Addressed by ship,
        // and the resolution is asserted: an input that reached nothing would leave the climb below
        // reading as unpiloted while claiming to be the piloted leg.
        String heldInput = exec("artest vs ff-input-by-id 0 " + extractString(srcInfo, "id") + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + heldInput,
                heldInput.contains("\"afcResolved\":true"));
        String tp = exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy + " " + (int) sz
                + " " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        assertTrue("climb teleport failed: " + tp, tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        // Keep the crossed ship loadable in its new slot while the async re-assembly settles.
        // (The Ticker drives ShipEntryController.tick() every server tick once the stack is installed.)

        // Poll the ledger: the flight-computer tick fires the entry, the entry crosses + settles the ship.
        String status = "";
        boolean settled = false;
        // The window is ARRANGEMENT, not the contract: what is asserted is that the ship settles, not
        // that it settles inside 30 s. A fixed ceiling around an async crossing turns machine load
        // into a red - measured 2026-07-28, when a ~10% heavier server boot (the space subsystem now
        // registers on every boot) tipped this loop over on the FULL suite while every subset of it,
        // up to 385 tests, stayed green. Scale with the fork factor the build already publishes;
        // the loop still exits the moment the ship is SETTLED, so a healthy run costs nothing extra.
        for (int i = 0; i < SETTLE_POLLS; i++) {
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

    /**
     * The leg AFTER the on-ramp: a ship that reached space through the production entry path can then JUMP
     * to another cell on the SAME live stack, driven only by the server tick.
     *
     * <p>Why this is not already covered: {@code VSShipTransitE2ETest} proves the transit state machine on
     * an ISOLATED stack — its own {@code SpaceManager}, its own hard-coded origin/target, advanced by manual
     * {@code transit-tick} calls. Nothing joined the two halves, so a ship that actually FLEW into space had
     * never been jumped. The join is exactly where the previous hands-on tier-2 session found its blockers.</p>
     *
     * <p>The contract asserted here is the join, not the transit internals: a ship SETTLED by the entry path
     * departs its cell when a jump begins, and settles in the requested target cell without anything pumping
     * the manager by hand. CONTROL: the pre-jump cell is read from the ledger and asserted DIFFERENT from
     * the target, so "settled at the target" cannot pass by never having moved.</p>
     */
    @Test
    public void aShipThatEnteredSpaceCanJumpToAnotherCellOnTheLiveStack() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)", serverHasVs());

        exec("artest vs permaload true");
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        clearArea(JUMP_SRC_X, JUMP_SRC_Z);
        String coords = placeFixture(JUMP_SRC_X, SRC_Y, JUMP_SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip(0) >= 1);

        String srcInfo = exec("artest vs ship-info 0 " + JUMP_SRC_X + " " + SRC_Y + " " + JUMP_SRC_Z);
        assertTrue("source ship not managed by VS: " + srcInfo, srcInfo.contains("\"managed\":true"));
        double sx = extractDouble(srcInfo, "posX"), sy = extractDouble(srcInfo, "posY"),
                sz = extractDouble(srcInfo, "posZ");
        String heldInput = exec("artest vs ff-input-by-id 0 " + extractString(srcInfo, "id") + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + heldInput,
                heldInput.contains("\"afcResolved\":true"));
        assertTrue("climb teleport failed", exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy
                + " " + (int) sz + " " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz)
                .contains("\"ok\":true"));
        exec("artest vs unpark 0 " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);

        String status = "";
        boolean settled = false;
        for (int i = 0; i < SETTLE_POLLS; i++) {
            status = exec("artest space entry-status");
            if (extractInt(status, "ships") >= 1 && "SETTLED".equals(extractString(status, "state"))) {
                settled = true;
                break;
            }
            loadAllEntrySlots(setup);
            Thread.sleep(250);
        }
        assertTrue("precondition: the ship never entered space, so there is nothing to jump; last status="
                + status, settled);
        int slotDim = extractInt(status, "slotDim");
        String originCell = extractString(status, "cellKey");
        assertTrue("the entered ship's cell world is not live in a slot; status=" + status,
                waitForLoadedShip(slotDim) >= 1);

        // Jump it ONE sector over. The slot dim is passed explicitly: the console sender's own world is
        // the overworld, and the default would read that instead of the cell the ship is in.
        Matcher origin = CELL_KEY.matcher(originCell == null ? "" : originCell);
        assertTrue("entry-status reported no decodable origin cell key: " + status, origin.matches());
        String jump = exec("artest space jump " + (Integer.parseInt(origin.group(1)) + 1)
                + " " + origin.group(2) + " " + origin.group(3) + " " + slotDim);
        assertTrue("the jump probe found no settled ship to move: " + jump, jump.contains("\"began\":true"));
        String targetCell = extractString(jump, "toCell");
        assertTrue("jump reported no target cell: " + jump, targetCell != null);
        // CONTROL: a target equal to the origin would make the arrival assert vacuous.
        assertTrue("the jump target must differ from the origin cell, else arrival proves nothing: "
                + originCell + " -> " + targetCell, !targetCell.equals(originCell));

        // A completed jump leaves the ship AT the cell it was aimed at — the WHOLE key, not just the
        // axis the jump asked to move. Everything downstream of a jump reads that address: the
        // descent proximity check looks up the bodies of the ship's own cell, so an address in the
        // wrong cell means the destination system is not there at all.
        // Nobody rides a jump today, so the destination cell has NO observer — and VS loads a ship
        // only for one. Drop the headless keep-loaded crutch for the whole arrival: with it on, the
        // pasted ship becomes live for free and the arrival's own readiness gate is never tested,
        // which is how this leg stayed green while the same jump, flown by hand, gave up in the
        // paste lane. From here the arrival must make its own ship loadable.
        exec("artest vs permaload false");

        // Nothing below pumps the manager either: the live Ticker advances the transit every tick.
        String arrived = "";
        boolean done = false;
        for (int i = 0; i < SETTLE_POLLS; i++) {
            arrived = exec("artest space entry-status");
            if ("SETTLED".equals(extractString(arrived, "state"))
                    && targetCell.equals(extractString(arrived, "cellKey"))) {
                done = true;
                break;
            }
            Thread.sleep(250);
        }
        assertTrue("the ship never arrived at the cell the jump was aimed at; origin=" + originCell
                + " requested=" + targetCell + " last status=" + arrived
                + " subsystem=" + exec("artest space subsystem-status"), done);

        // A ledger row written by the arrival itself proves only what the arrival BELIEVES. The
        // player's reading comes later, when he walks up to his ship: his presence loads it, its
        // flight computer starts ticking, and the address he sees is inverted from the ship's REAL
        // pose. An arrival that gave up in the paste lane still stamps the requested cell above and
        // only slips a sector once that first tick lands. So bring the observer in now — after the
        // jump, never during it — and read the address again.
        int arrivedSlot = extractInt(arrived, "slotDim");
        exec("artest vs load-ships " + arrivedSlot);
        String pose = "";
        for (int i = 0; i < 8; i++) {
            Thread.sleep(250);
            exec("artest vs load-ships " + arrivedSlot);
            pose = exec("artest vs ship-info " + arrivedSlot + " 0 200 0");
            String held = exec("artest space entry-status");
            assertEquals("the arrived ship's address drifted out of the cell it flew to once its"
                            + " flight computer began self-reporting its position; status=" + held
                            + " ship=" + pose,
                    targetCell, extractString(held, "cellKey"));
        }
        // And the same fact read off the ship rather than off the ledger: a cell realizes its
        // contents in the pose band, while an arrival that never settled is left in the paste lane's
        // ordinary block Y. The band floor is the discriminator — no exact pose is pinned.
        assertTrue("the arrived ship was never loaded in its destination cell: " + pose,
                pose.contains("\"managed\":true"));
        assertTrue("the arrived ship is not in its cell's pose band, so it never reached the"
                        + " coordinate the jump was aimed at (band floor " + GalacticCoord.HALF_CELL
                        + ", the paste lane sits near ordinary block Y): " + pose,
                extractDouble(pose, "posY") > GalacticCoord.HALF_CELL);
        assertEquals("nothing may still be in transit once the ledger reports arrival", 0,
                extractInt(exec("artest space subsystem-status"), "transits"));
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
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
