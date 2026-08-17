package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.TestTimeouts;

import zmaster587.advancedRocketry.space.CellSeam;
import zmaster587.advancedRocketry.space.GalacticCoord;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * E2E: a ship flown out through its cell's face ARRIVES IN THE NEIGHBOUR, and its ledger row names the
 * cell it is actually in.
 *
 * <p>Before the seam existed, such a ship was neither stopped nor carried: the pose kept going while
 * the ledger report saturated at the boundary, leaving the ship in one place and named in another —
 * unable to descend, refused its jumps, and no longer protecting the cell it was really in.</p>
 *
 * <p>The arrangement uses the REAL on-ramp to get a ship legitimately settled in a cell (assemble,
 * hold a throttle, climb past the ceiling, let the flight computer's own tick call entry), then moves
 * it past the face and drives {@code SpaceSubsystem.seam().requestCarry()} — production code, through
 * a probe verb. The crossing itself is the shared one every other crossing uses.</p>
 *
 * <p><b>What this test does NOT cover, stated rather than implied:</b> the trigger wiring inside
 * {@code TileAdvancedFlightComputer}. A headless slot world has no player and no ticking chunks, so
 * its tiles do not tick and no e2e here can observe that call — the same limit the descent e2e has
 * (it drives {@code space descent-begin} and says so). WHEN a carry fires is pinned deterministically
 * by {@code CellSeamTest}; the one link neither covers is the two lines in the tile that join them.</p>
 *
 * <p>Witnesses, in order: the ledger names the +X neighbour and no other cell; the ship arrives
 * {@code REENTRY_DEPTH} INSIDE that neighbour's opposite face rather than on it; and it stays there —
 * a ship placed on the face would be one drift away from crossing straight back, which is the whole
 * content of the hysteresis. CONTROL: the pre-move ledger read is asserted to name the source cell, so
 * the later change is a real observation rather than a first reading.</p>
 *
 * <p>Gated on the server's real VS presence (run with {@code -PwithVS}); skips cleanly otherwise.</p>
 */
public class VSShipCellSeamE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern CELL_KEY = Pattern.compile("^(-?\\d+)_(-?\\d+)_(-?\\d+)$");

    /** Where this test builds its ship — its own region, clear of the entry/descent legs. */
    private static final int SRC_X = 6800, SRC_Y = 80, SRC_Z = 6800;
    /** A world Y comfortably above the default orbit ceiling (ARConfiguration.orbit = 1000). */
    private static final int ABOVE_CEILING_Y = 1200;

    /** Async settle budget, stretched by the build's fork factor (the entry leg's sizing). */
    private static final int SETTLE_POLLS = (int) Math.ceil(120 * TestTimeouts.factor());

    /**
     * How far past the face the ship is placed: comfortably beyond the carry margin, so the test is
     * not sitting on the decision boundary — that is {@code CellSeamTest}'s job, on the pure layer,
     * where a one-block question can be asked without a physics engine in the way.
     */
    private static final long PAST_THE_FACE =
            GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN + 2_000L;

    /**
     * Tolerance on the arrival position. The contract is "inside the face, not on it", and the two
     * candidates are {@code REENTRY_DEPTH} apart (16 000 blocks), so a few hundred blocks of settle
     * slop cannot confuse them.
     */
    private static final double ARRIVAL_TOLERANCE = 2_000d;

    @Test
    public void aShipFlownPastItsCellFaceIsCarriedIntoTheNeighbourAndStaysThere() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)",
                serverHasVs());

        exec("artest vs permaload true");
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        // CONTROL: nothing is ledgered yet, so a later reading is a real observation — and the
        // "first ledgered ship" this test reads its durable id from is unambiguously ours.
        String before = exec("artest space entry-status");
        assertEquals("no ship must be ledgered before the climb: " + before, 0,
                extractInt(before, "ships"));

        // --- Arrangement: get a ship into a cell through the production on-ramp ------------------
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip(0) >= 1);

        // TWO IDENTITIES, deliberately kept apart. `vs ship-info` answers the VS ship uuid
        // (`VSBridge.nearestShipId` -> `getShipData().getUuid()`), which every `vs` verb takes and
        // which a crossing REPLACES — the arriving ship is a new VS body. The ledger is keyed by AR's
        // durable ship id, read from `entry-status` once the ship is in space. Asking either side with
        // the other's id answers "not found" and reads exactly like the mechanic being broken.
        String srcInfo = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("source ship not managed by VS: " + srcInfo, srcInfo.contains("\"managed\":true"));
        String srcVsId = extractString(srcInfo, "id");
        assertTrue("the assembled ship reported no VS id: " + srcInfo, srcVsId != null);
        double sx = extractDouble(srcInfo, "posX"), sy = extractDouble(srcInfo, "posY"),
                sz = extractDouble(srcInfo, "posZ");

        String heldInput = exec("artest vs ff-input-by-id 0 " + srcVsId + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + heldInput,
                heldInput.contains("\"afcResolved\":true"));
        assertTrue("climb teleport failed",
                exec("artest vs teleport-ship 0 " + (int) sx + " " + (int) sy + " " + (int) sz
                        + " " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz)
                        .contains("\"ok\":true"));
        exec("artest vs unpark 0 " + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);

        String status = "";
        String sourceCell = null;
        for (int i = 0; i < SETTLE_POLLS; i++) {
            status = exec("artest space entry-status");
            if (extractInt(status, "ships") >= 1 && "SETTLED".equals(extractString(status, "state"))) {
                sourceCell = extractString(status, "cellKey");
                break;
            }
            loadAllEntrySlots(setup);
            Thread.sleep(250);
        }
        assertTrue("the ship never reached space through the entry path; last status=" + status,
                sourceCell != null);
        String arShipId = extractString(status, "shipId");
        assertTrue("the settled ship has no durable id: " + status, arShipId != null);
        int sourceSlot = extractInt(status, "slotDim");
        assertTrue("settled ship has no bound slot: " + status, sourceSlot > Integer.MIN_VALUE);
        assertTrue("the settled ship's cell world is not live", waitForLoadedShip(sourceSlot) >= 1);

        // --- CONTROL: while it is inside its cell, the ledger names THAT cell --------------------
        String inside = exec("artest space ledger-get " + arShipId);
        assertTrue("the ledger does not know the settled ship: " + inside,
                inside.contains("\"found\":true"));
        assertEquals("the ledger must name the source cell before the ship leaves it: " + inside,
                sourceCell, extractString(inside, "cell"));

        // --- Act: put the ship past the +X face of its cell --------------------------------------
        String inCell = shipInThatSlot(sourceSlot);
        double cx = extractDouble(inCell, "posX"), cy = extractDouble(inCell, "posY"),
                cz = extractDouble(inCell, "posZ");
        assertFalse("the ship's in-cell pose could not be read: " + inCell,
                Double.isNaN(cx) || Double.isNaN(cy) || Double.isNaN(cz));

        String outward = exec("artest vs teleport-ship " + sourceSlot + " "
                + (long) cx + " " + (long) cy + " " + (long) cz + " "
                + PAST_THE_FACE + " " + (long) cy + " " + (long) cz);
        assertTrue("the move past the cell face failed: " + outward, outward.contains("\"ok\":true"));
        exec("artest vs unpark " + sourceSlot + " " + PAST_THE_FACE + " " + (long) cy + " " + (long) cz);

        // THE ARRANGEMENT IS ASSERTED, not assumed. "the probe returned ok" is not "the ship is past
        // the face": a clamp, a refused transform or a Y-limit would all report ok and leave the ship
        // inside its cell, and the carry would then be correctly not firing — a green mechanic
        // reported as a red one.
        String moved = shipInThatSlot(sourceSlot);
        double mx = extractDouble(moved, "posX");
        assertFalse("the moved ship's pose could not be read: " + moved, Double.isNaN(mx));
        assertTrue("the ship is not actually past the cell face after the move — it is at x=" + mx
                        + ", and the carry threshold is " + (GalacticCoord.HALF_CELL
                        + CellSeam.CARRY_MARGIN) + "; the test moved nothing: " + moved,
                mx > GalacticCoord.HALF_CELL + CellSeam.CARRY_MARGIN);

        // Drive the production carry. NOT the flight computer's own tick: a headless slot world has
        // no player and no ticking chunks, so its tiles do not tick — an earlier revision of this test
        // waited 30 s for a trigger that cannot fire here and reported the CROSSING as broken. This is
        // the same split the descent e2e already uses (`space descent-begin`): WHEN a carry fires is
        // pinned deterministically by `CellSeamTest`, and what a carry DOES is pinned here, on a real
        // ship. `wouldCarry` is production's own reading of the live pose, so the arrangement is
        // witnessed by the code under test rather than only by this test's arithmetic.
        String carry = exec("artest space seam-carry " + sourceSlot);
        assertTrue("production does not agree the ship has left its cell (its own predicate on the "
                + "live pose): " + carry, carry.contains("\"wouldCarry\":true"));
        assertTrue("the carry did not start — the reason is in the reply: " + carry,
                carry.contains("\"started\":true"));

        // --- Assert: carried into the neighbour ---------------------------------------------------
        String carriedCell = null;
        String afterMove = "";
        for (int i = 0; i < SETTLE_POLLS; i++) {
            afterMove = exec("artest space ledger-get " + arShipId);
            String cell = extractString(afterMove, "cell");
            if (cell != null && !sourceCell.equals(cell)
                    && "SETTLED".equals(extractString(afterMove, "state"))) {
                carriedCell = cell;
                break;
            }
            loadAllEntrySlots(setup);
            Thread.sleep(250);
        }
        assertTrue("the carry started but the ship never settled in the neighbour; source="
                + sourceCell + " shipX=" + mx + " carry=" + carry + " last ledger=" + afterMove,
                carriedCell != null);

        long[] from = cellSectors(sourceCell);
        long[] to = cellSectors(carriedCell);
        assertEquals("carried into the +X neighbour and no other", from[0] + 1L, to[0]);
        assertEquals("the Y sector must not move — the ship crossed one face", from[1], to[1]);
        assertEquals("the Z sector must not move — the ship crossed one face", from[2], to[2]);

        // It arrived INSIDE the neighbour's opposite face, not on it. This is the hysteresis as the
        // world sees it: the expected world X is the local offset itself (XZ realize directly).
        int carriedSlot = extractInt(afterMove, "slotDim");
        assertTrue("the carried ship has no bound slot: " + afterMove, carriedSlot > Integer.MIN_VALUE);
        assertTrue("the neighbour's cell world never came up", waitForLoadedShip(carriedSlot) >= 1);
        String arrived = shipInThatSlot(carriedSlot);
        double ax = extractDouble(arrived, "posX");
        assertFalse("the arrived ship's pose could not be read: " + arrived, Double.isNaN(ax));
        double expectedX = -(double) GalacticCoord.HALF_CELL + CellSeam.REENTRY_DEPTH;
        assertEquals("the ship must arrive the re-entry depth inside the face it came in by, not on it",
                expectedX, ax, ARRIVAL_TOLERANCE);

        // And it STAYS there: a ship parked on the face would cross straight back.
        for (int i = 0; i < 8; i++) {
            Thread.sleep(250);
            String held = exec("artest space ledger-get " + arShipId);
            assertEquals("the carried ship bounced back across the face (ping-pong): " + held,
                    carriedCell, extractString(held, "cell"));
        }
    }

    @After
    public void cleanup() throws Exception {
        if (serverHasVs()) {
            exec("artest space entry-clear");
            exec("artest vs permaload false");
        }
    }

    /** The three sector indices of a {@code sx_sy_sz} cell key. */
    private static long[] cellSectors(String cellKey) {
        Matcher m = CELL_KEY.matcher(cellKey);
        assertTrue("unparsable cell key: " + cellKey, m.matches());
        return new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)),
                Long.parseLong(m.group(3))};
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /**
     * The one ship in a cell's slot world, asked for POSITIONALLY and guarded by a count.
     *
     * <p>A ship cannot be followed across a crossing by VS id — the arriving body is a new one — and
     * the cell frame is far from any origin a query could guess, so the lookup is "nearest to the
     * cell centre pose, within the whole cell". That is only an identity because the slot world holds
     * exactly ONE ship, which is asserted here rather than assumed: without the count this returns a
     * neighbour the moment a second ship shares the slot, and it reads identically.</p>
     */
    private String shipInThatSlot(int slotDim) throws Exception {
        String count = exec("artest vs ship-count " + slotDim);
        assertEquals("this lookup is only an identity while the slot holds exactly one ship: " + count,
                1, extractInt(count, "count"));
        long centreY = GalacticCoord.HALF_CELL + 256L; // the cell-centre pose (CellWorldMapper band)
        String info = exec("artest vs ship-info " + slotDim + " 0 " + centreY + " 0 "
                + (GalacticCoord.CELL * 2L));
        assertTrue("the ship in slot " + slotDim + " could not be located: " + info,
                info.contains("\"managed\":true"));
        return info;
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
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " "
                + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " "
                + (baseZ - 4) + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20)
                + " minecraft:air").contains("\"ok\":true"));
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

    /**
     * A number out of a probe reply, exponent form included — a coordinate past 10⁷ prints as
     * {@code 1.6000256E7}, and an extractor that cannot read that silently compares 1.6 against
     * sixteen million. NaN when absent, deliberately: the alternative (0.0) is a legal coordinate and
     * would make a missing field read as "the ship is at the origin".
     */
    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)")
                .matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
