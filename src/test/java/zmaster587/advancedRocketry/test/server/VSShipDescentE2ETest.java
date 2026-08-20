package zmaster587.advancedRocketry.test.server;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * E2E: does the tier-2 PLANET DESCENT take a ship in space across into a real planet dimension through the
 * built crossing? This composes on the entry on-ramp: a {@code with-pilot-seat} ship is assembled and
 * ENTERED into a slot cell (the proven entry path settles it in the {@code ShipLedger}), then the
 * production {@code DescentController.requestDescent} is driven for that settled ship toward the overworld
 * (dim 0 — a guaranteed real dimension with terrain). The descent runs the SAME generalized
 * {@code ShipCrossingService} the entry uses: it cuts the ship out of its space cell (releasing it from the
 * ledger) and pastes it, terrain-aware, into the destination dim, where it re-assembles.
 *
 * <p>Witnesses: BEFORE descent the overworld has no VS ship (entry cut the source out); AFTER descent a VS
 * ship is loaded in the overworld and the ship has left the ledger. The proximity TRIGGER predicate is
 * pinned separately + deterministically by {@code DescentControllerTest}; this e2e is the "the crossing
 * physically moves a settled ship into a planet dim" acceptance. Gated on the server's real VS presence
 * (run with {@code -PwithVS}); skips cleanly otherwise.</p>
 */
public class VSShipDescentE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** A loaded overworld region distinct from the entry e2e's, well clear of other tests. */
    private static final int SRC_X = 6400, SRC_Y = 80, SRC_Z = 6400;
    /** A world Y comfortably above the default orbit ceiling (ARConfiguration.orbit = 1000). */
    private static final int ABOVE_CEILING_Y = 1200;
    /** The descent target: the overworld — always registered, terrain-generated. */
    private static final int TARGET_DIM = 0;

    @Test
    public void aSettledShipDescendsIntoAPlanetDimViaTheCrossing() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)", serverHasVs());

        exec("artest vs permaload true");
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        // --- Phase 1: ENTER a ship so it is settled in a slot cell (the proven entry path). ---
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip(0) >= 1);

        String srcInfo = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("source ship not managed by VS: " + srcInfo, srcInfo.contains("\"managed\":true"));
        double sx = extractDouble(srcInfo, "posX"), sy = extractDouble(srcInfo, "posY"),
                sz = extractDouble(srcInfo, "posZ");

        // A held throttle on THIS ship's own flight computer => a pilot is flying.
        String held = exec("artest vs ff-input-by-id 0 " + extractString(srcInfo, "id") + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + held,
                held.contains("\"afcResolved\":true"));
        String tp = exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy + " " + (int) sz
                + " " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        assertTrue("climb teleport failed: " + tp, tp.contains("\"ok\":true"));
        exec("artest vs unpark 0 " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);

        String status = "";
        boolean settled = false;
        for (int i = 0; i < 120; i++) {
            status = exec("artest space entry-status");
            if (extractInt(status, "ships") >= 1 && "SETTLED".equals(extractString(status, "state"))) {
                settled = true;
                break;
            }
            loadAllEntrySlots(setup);
            Thread.sleep(250);
        }
        assertTrue("precondition: ship never entered space to descend from; last status=" + status, settled);
        int slotDim = extractInt(status, "slotDim");
        String shipId = extractString(status, "shipId");
        assertTrue("settled slot dim not reported: " + status, slotDim > Integer.MIN_VALUE);

        // --- Phase 2: DESCEND that settled ship into the overworld. ---
        // CONTROL: the overworld holds no VS ship now (entry cut the source out) — a later "1" is the descent.
        assertEquals("witness sensitivity control — overworld must hold no VS ship before the descent",
                0, extractInt(exec("artest vs ship-count-all " + TARGET_DIM), "count"));

        // Nothing to stop feeding: the pilot input lives on the SHIP's own flight computer, and that
        // tile was cut out of dim 0 by the entry above. This used to clear a world-wide static that
        // would otherwise have followed the ship into space and every later scenario with it.

        // Ensure the settled ship is loaded in its slot, then locate its flight computer. The ship's
        // blocks (incl. the AFC tile entity) live in the slot world's far subspace shipyard; they enter
        // loadedTileEntityList only once VS loads the ship, so force-load + poll (async load).
        assertTrue("the settled ship never loaded in its slot", waitForLoadedShip(slotDim) >= 1);
        String afc = null;
        for (int i = 0; i < 40 && afc == null; i++) {
            exec("artest vs load-ships " + slotDim);
            // By id: this scenario already knows which ship it flew up, and "the first settled ship
            // in the slot" is a different question that happens to have the same answer today.
            String r = exec("artest space find-afc " + slotDim + " " + shipId);
            if (r.contains("\"found\":true")) {
                afc = r;
            } else {
                Thread.sleep(250);
            }
        }
        assertTrue("could not locate the ship's flight computer in the slot", afc != null);
        int ax = extractInt(afc, "x"), ay = extractInt(afc, "y"), az = extractInt(afc, "z");

        String begin = exec("artest space descent-begin " + slotDim + " " + ax + " " + ay + " " + az
                + " " + shipId + " " + TARGET_DIM);
        assertTrue("descent did not start: " + begin, begin.contains("\"started\":true"));

        // The cut dropped the ship from the ledger at once (it has left the subsystem).
        assertEquals("the descending ship leaves the ledger on the cut", 0,
                extractInt(exec("artest space entry-status"), "ships"));

        // The crossing re-assembles the ship in the overworld (async); poll until it is loaded there.
        boolean landed = false;
        for (int i = 0; i < 120; i++) {   // ~30 s ceiling: async crossing + re-assembly
            if (waitForLoadedShip(TARGET_DIM) >= 1) {
                landed = true;
                break;
            }
            exec("artest space descent-status");
            Thread.sleep(250);
        }
        assertTrue("the ship never crossed into the overworld via the descent; countAll="
                + exec("artest vs ship-count-all " + TARGET_DIM), landed);
    }

    @After
    public void cleanup() throws Exception {
        if (serverHasVs()) {
            exec("artest space entry-clear");
            exec("artest vs permaload false");
        }
    }

    // --- helpers (mirror VSShipEntryE2ETest) --------------------------------------------------------

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
