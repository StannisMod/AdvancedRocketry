package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.TestTimeouts;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Does a jump to ANOTHER STAR SYSTEM fly, and how long does it take?
 *
 * <p>Every jump ever flown here has been a hop of one sector. The distance between two real systems
 * is three orders of magnitude larger — the generator partitions space into 512-cell super-cells and
 * a cell is 4M blocks — so the interstellar leg has never been exercised: not the integrator over
 * thousands of ticks, not the arrival into a cell that far out, not the ledger address it settles at.
 * Nothing in the gate refuses it; nobody had flown it.</p>
 *
 * <p><b>The control is in the run.</b> The same ship jumps one sector first. That leg must arrive
 * almost immediately — a hop is four ticks at the baseline speed — and it establishes that the
 * arrangement (entry stack, ledger, arrival, the poll that reads them) works at all. Only then does
 * the far leg get to speak: a red near leg indicts the scaffolding and says nothing about distance.
 * The two legs also stand as each other's measurement — the same ship, the same stack, the same
 * instrument, one variable changed.</p>
 *
 * <p>What is asserted is SHAPE, never a duration: that the far leg arrives at the cell it was aimed
 * at, and that it takes strictly longer than the hop. The tick counts it prints are balance
 * readings — speed, spacing and cell size are all `tunable` — and a test that pinned one would go
 * red on a rebalance with nothing broken.</p>
 */
public class InterstellarJumpLegE2ETest extends AbstractSharedServerTest {

    /** Where the craft is built — a loaded overworld region well clear of the other space suites. */
    private static final int SRC_X = 6800, SRC_Y = 80, SRC_Z = 6800;
    /** A world Y comfortably above the default orbit ceiling (ARConfiguration.orbit = 1000). */
    private static final int ABOVE_CEILING_Y = 1200;

    /**
     * Sectors to the neighbouring system. Measured, not invented: over 20 seeds of the default
     * generator the nearest other system sits 443-591 cells away (median 537), which is what one
     * super-cell edge of 512 works out to once two anchors sit in adjacent cubes.
     */
    private static final int INTERSTELLAR_SECTORS = 537;

    /**
     * Blocks per tick the legs are flown at. The DISTANCE is the real one; the speed is not, and the
     * difference is deliberate. What this test pins is that a target a whole system away arrives at
     * the cell it was aimed at and costs more time than a hop — a shape that holds at any speed —
     * while the speed a jump is flown at is supplied by the caller here, never derived, so flying
     * slowly would buy realism the probe path cannot deliver anyway.
     *
     * <p>The duration MEASUREMENT was taken separately, at the baseline drive's own
     * {@code 1_000_000} blocks/tick: 537 sectors took 2 167 ticks (108 s), against 2 148 predicted
     * from distance/speed. Set this back to 1 000 000 to re-measure; at 5x it costs the suite ~20 s
     * instead of ~2 min, which is the only reason it is not the baseline here.</p>
     */
    private static final long FLIGHT_SPEED = 5_000_000L;

    private static final Pattern BUILDER_POS = Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern CELL_KEY = Pattern.compile("^(-?\\d+)_(-?\\d+)_(-?\\d+)$");

    /** Poll iterations for the CLIMB into space (250 ms apart), stretched by the fork factor. */
    private static final int SETTLE_POLLS = (int) Math.ceil(120 * TestTimeouts.factor());
    /**
     * Poll iterations for an ARRIVAL, one second apart. The far leg is thousands of ticks of real
     * server time by design, so this budget is sized from the leg itself — 537 sectors x 4M blocks
     * at 1M blocks/tick is ~2 150 ticks ~ 108 s — with room for the arrival's own retries on top.
     */
    private static final int ARRIVAL_POLLS = (int) Math.ceil(300 * TestTimeouts.factor());

    @Test
    public void aJumpToAnotherStarSystemArrivesAndCostsMoreTimeThanAHop() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        exec("artest vs permaload true");
        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, setup.contains("\"ok\":true"));

        // A ship that reached space the way a ship does: built, assembled, flown past the ceiling.
        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip(0) >= 1);

        String srcInfo = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("source ship not managed by VS: " + srcInfo, srcInfo.contains("\"managed\":true"));
        int sx = (int) extractDouble(srcInfo, "posX");
        int sy = (int) extractDouble(srcInfo, "posY");
        int sz = (int) extractDouble(srcInfo, "posZ");
        String held = exec("artest vs ff-input-by-id 0 " + extractString(srcInfo, "id") + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + held,
                held.contains("\"afcResolved\":true"));
        assertTrue("climb teleport failed", exec("artest vs teleport-ship 0 " + sx + " " + sy + " " + sz
                + " " + sx + " " + ABOVE_CEILING_Y + " " + sz).contains("\"ok\":true"));
        exec("artest vs unpark 0 " + sx + " " + ABOVE_CEILING_Y + " " + sz);

        String status = waitForState("SETTLED", null, setup, SETTLE_POLLS, 250L);
        assertTrue("precondition: the ship never entered space, so there is nothing to jump; last="
                + status, "SETTLED".equals(extractString(status, "state")));
        int slotDim = extractInt(status, "slotDim");
        String originCell = extractString(status, "cellKey");
        Matcher origin = CELL_KEY.matcher(originCell == null ? "" : originCell);
        assertTrue("entry-status reported no decodable origin cell key: " + status, origin.matches());
        long osx = Long.parseLong(origin.group(1));
        String osy = origin.group(2), osz = origin.group(3);

        // ---- CONTROL LEG: one sector over. Four ticks of flight; it proves the arrangement. -------
        long hopTicks = flyTo(osx + 1, osy, osz, slotDim, originCell, setup, "hop");
        assertTrue("ARRANGEMENT: a one-sector hop must arrive, or nothing below is about distance."
                + " Fix the scaffolding before reading the far leg.", hopTicks >= 0);

        String afterHop = exec("artest space entry-status");
        String hopCell = extractString(afterHop, "cellKey");
        int hopSlot = extractInt(afterHop, "slotDim");
        Matcher hopOrigin = CELL_KEY.matcher(hopCell == null ? "" : hopCell);
        assertTrue("no decodable cell key after the hop: " + afterHop, hopOrigin.matches());

        // ---- THE SUBJECT: the same ship, the same stack, 537 sectors instead of one. ---------------
        long farTicks = flyTo(Long.parseLong(hopOrigin.group(1)) + INTERSTELLAR_SECTORS,
                hopOrigin.group(2), hopOrigin.group(3), hopSlot, hopCell, setup, "interstellar");

        System.out.println("[interstellar-leg] hop=" + hopTicks + " ticks, interstellar(" + INTERSTELLAR_SECTORS
                + " sectors)=" + farTicks + " ticks = " + (farTicks / 20.0D) + " s"
                + " at " + FLIGHT_SPEED + " blocks/tick");

        assertTrue("a jump to another star system must ARRIVE — the leg the game is built around has"
                + " never been flown, and if it cannot complete, M2's script has no third step."
                + " hop=" + hopTicks + " far=" + farTicks, farTicks >= 0);
        assertTrue("...and it must cost more time than a one-sector hop, or the transit is not"
                + " integrating distance at all: hop=" + hopTicks + " far=" + farTicks,
                farTicks > hopTicks);
    }

    /**
     * Fly the settled ship to {@code (tsx,tsy,tsz)} and return how many server ticks the leg took,
     * or {@code -1} when it never arrived. The jump is fired at the baseline speed and nothing pumps
     * the manager: the live Ticker advances the transit every tick, which is what makes the returned
     * number the flight's own duration rather than the poll loop's.
     */
    private long flyTo(long tsx, String tsy, String tsz, int slotDim, String fromCell,
                       String setup, String label) throws Exception {
        String jump = exec("artest space jump " + tsx + " " + tsy + " " + tsz + " " + slotDim
                + " " + FLIGHT_SPEED);
        assertTrue("[" + label + "] the jump probe found no settled ship to move: " + jump,
                jump.contains("\"began\":true"));
        String targetCell = extractString(jump, "toCell");
        assertTrue("[" + label + "] jump reported no target cell: " + jump, targetCell != null);
        assertTrue("[" + label + "] CONTROL: target must differ from origin, else arrival proves"
                + " nothing: " + fromCell + " -> " + targetCell, !targetCell.equals(fromCell));

        long departed = clock();
        String arrived = waitForState("SETTLED", targetCell, setup, ARRIVAL_POLLS, 1000L);
        long elapsed = clock() - departed;
        if (!targetCell.equals(extractString(arrived, "cellKey"))) {
            System.out.println("[interstellar-leg] " + label + " NEVER ARRIVED after " + elapsed
                    + " ticks; last=" + arrived + " subsystem=" + exec("artest space subsystem-status"));
            return -1L;
        }
        assertEquals("[" + label + "] nothing may still be in transit once the ledger reports arrival",
                0, extractInt(exec("artest space subsystem-status"), "transits"));
        return elapsed;
    }

    /** The space clock the transit is priced on — the same counter production integrates against. */
    private long clock() throws Exception {
        Matcher m = Pattern.compile("\"clock\":(-?\\d+)").matcher(exec("artest space frame 0 0 0"));
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /** Poll entry-status until the ledger reports {@code state} (and {@code cell}, when given). */
    private String waitForState(String state, String cell, String setup, int polls, long sleepMs)
            throws Exception {
        String status = "";
        for (int i = 0; i < polls; i++) {
            status = exec("artest space entry-status");
            if (state.equals(extractString(status, "state"))
                    && (cell == null || cell.equals(extractString(status, "cellKey")))) {
                return status;
            }
            loadAllEntrySlots(setup);
            Thread.sleep(sleepMs);
        }
        return status;
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

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
