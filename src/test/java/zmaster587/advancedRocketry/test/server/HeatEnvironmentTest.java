package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * What the OUTSIDE does to a ship's heat: the incident flux, and the shield that thins it.
 *
 * <p>Three clauses, independent of one another. That every outside source arrives through ONE term —
 * shown by putting the same rig in two physically different environments and requiring its response to
 * track the one number both of them report. That where more arrives than a cell can shed the net runs
 * BACKWARDS and the ship heats regardless of its own temperature — with the control that a loop which
 * built no radiators does not heat at all, because a radiating cell is the ship's coupling to the
 * outside in both directions. And that a shield thins the flux and never removes it, asked with the
 * configuration demanding every last percent.</p>
 *
 * <p><b>Most of this happens in a real space cell</b>, materialized from the overworld's own system,
 * because that is where the mechanic lives: on a world a coolant loop cannot be driven backwards at
 * all — the environment there is the world's own temperature, which is by construction below what the
 * loop sits at. A test that arranged the backwards case on a planet would be arranging something the
 * game cannot do.</p>
 *
 * <p>The star's STRENGTH is a setting and is turned up rather than flown to. How bright a star is
 * belongs to the universe layer; what a ship does with what arrives is this one, and the arrival path
 * itself — a real star, at its real distance, through the production registry — is what the space leg
 * of the first scenario exercises and measures.</p>
 */
public class HeatEnvironmentTest extends AbstractSharedServerTest {

    private static final Pattern HEAT_STORED = Pattern.compile("\"heatStored\":(-?\\d+)");
    private static final Pattern HEAT_CAPACITY = Pattern.compile("\"heatCapacity\":(-?\\d+)");
    private static final Pattern CELLS = Pattern.compile("\"radiatingCells\":(-?\\d+)");
    private static final Pattern CYCLE_REJECTED = Pattern.compile("\"rejected\":(-?\\d+)");
    private static final Pattern CHARGED = Pattern.compile("\"charged\":(-?\\d+)");
    private static final Pattern INCIDENT_FLUX_MILLI = Pattern.compile("\"incidentFluxMilli\":(-?\\d+)");
    private static final Pattern SLOT_DIM = Pattern.compile("\"slotDim\":(-?\\d+)");

    /** High and in the open, so a cell facing up has nothing over it but sky. */
    private static final int Y = 100;
    private static final int Z = 2820;

    private static final int X_ONE_CHANNEL = 1000;
    private static final int X_WITH_RADIATOR = 1010;
    private static final int X_WITHOUT_RADIATOR = 1020;
    private static final int X_SHIELD_GENERATOR = 1040;
    private static final int X_SHIELDED = 1043;
    private static final int X_UNSHIELDED = 1060;

    /** `getStateFromMeta` maps this to a cell radiating UP, so nothing but sky is in front of it. */
    private static final String RADIATOR_FACING_UP = "1";

    private static final int LOOP_LENGTH = 4;

    private static final String DEFAULT_STAR_KELVIN = "278";

    /**
     * A star strong enough that what it delivers dwarfs what a cell at room temperature radiates, so
     * the backwards case and the shield's residue are both unmistakable rather than marginal. Stated
     * as the temperature a cell would settle at under it, which is what the setting means.
     */
    private static final String FIERCE_STAR_KELVIN = "2000";

    /**
     * Two physically different sources, one term. The same rig is built twice — once on a world, where
     * what reaches it is that world's own warmth, and once in a space cell, where what reaches it is a
     * star a hundred million blocks away — and in both places the loop's response is the same function
     * of the ONE number the environment reports.
     *
     * <p>Stated as a difference so nothing about the radiator's own physics has to be restated: both
     * legs sit at the same temperature, so what each cell RADIATES is identical and cancels, and the
     * whole gap between the two nets must be the gap between the two reported fluxes. A star wired as
     * its own mechanism would move one of those and not the other.</p>
     */
    @Test
    public void aWorldsWarmthAndAStarArriveThroughTheSameTerm() throws Exception {
        buildLoop(0, X_ONE_CHANNEL, 1);
        String built = loopInfo(0, X_ONE_CHANNEL);
        assertEquals("premise: exactly one radiating cell, so per-cell figures are per-loop figures: "
                + built, 1, longOf(built, CELLS));
        long capacity = longOf(built, HEAT_CAPACITY);
        long charge = 100L * capacity;

        String onAWorld = cycle(0, X_ONE_CHANNEL, charge);
        long fluxOnAWorld = longOf(onAWorld, INCIDENT_FLUX_MILLI);
        long netOnAWorld = longOf(onAWorld, CYCLE_REJECTED);
        assertTrue("premise: a world must be radiating something at the ship standing on it, or this "
                + "leg is deep space with extra steps: " + onAWorld, fluxOnAWorld > 0);

        Cell cell = occupyHomeCell();
        long fluxInSpace;
        long netInSpace;
        // Turned up because the two environments happen to be within a few units of each other at the
        // shipped numbers, and a test whose arms differ by less than its own rounding cannot tell a
        // working sum from a broken one.
        setConfig("shipHeatStarFluxReferenceKelvin", "600");
        try {
            buildLoop(cell.dim, X_ONE_CHANNEL, 1);
            String inSpace = cycle(cell.dim, X_ONE_CHANNEL, charge);
            assertEquals("premise: the two rigs must be the same size, or their temperatures differ "
                            + "and what they radiate no longer cancels: " + onAWorld + " | " + inSpace,
                    capacity, longOf(inSpace, HEAT_CAPACITY));
            assertEquals("premise: and must have the same radiating surface: " + inSpace,
                    1, longOf(inSpace, CELLS));
            fluxInSpace = longOf(inSpace, INCIDENT_FLUX_MILLI);
            netInSpace = longOf(inSpace, CYCLE_REJECTED);
            assertTrue("premise: a star must actually be reaching this cell, or the second source does "
                    + "not exist and only one thing is under test: " + inSpace, fluxInSpace > 0);
            assertTrue("premise: the two environments must be measurably different, or the difference "
                            + "below is rounding: world=" + fluxOnAWorld + " space=" + fluxInSpace,
                    Math.abs(fluxInSpace - fluxOnAWorld) > 100_000L);
        } finally {
            setConfig("shipHeatStarFluxReferenceKelvin", DEFAULT_STAR_KELVIN);
            release(cell);
        }

        double fluxDifference = (fluxInSpace - fluxOnAWorld) / 1000.0D;
        double netDifference = netOnAWorld - netInSpace;
        assertTrue("a warm world and a distant star must reach a radiator through ONE term: the same "
                        + "loop at the same temperature netted " + netOnAWorld + " on a world and "
                        + netInSpace + " in space, a difference of " + netDifference + ", against a "
                        + "reported flux difference of " + fluxDifference + ". They do not match, so "
                        + "one of the two sources reaches the loop by a path the environment readout "
                        + "does not describe.",
                Math.abs(netDifference - fluxDifference) <= 2.0D);
    }

    /**
     * More arriving than can be shed: the surface runs backwards and the ship heats although it is
     * doing nothing and holding nothing.
     *
     * <p>The control is a loop of the same size that built no radiators, under the same star. It must
     * stay at zero — a radiating cell is the ship's coupling to the outside in both directions, so a
     * ship that built none is not warmed by one. Without that control this scenario would also pass on
     * a bug that simply added the environment to every loop in the world.</p>
     */
    @Test
    public void aShipUnderAFierceStarHeatsThroughItsRadiators() throws Exception {
        Cell cell = occupyHomeCell();
        try {
            buildLoop(cell.dim, X_WITH_RADIATOR, 1);
            buildLoop(cell.dim, X_WITHOUT_RADIATOR, 0);
            assertEquals("premise: one cell of radiating surface: " + loopInfo(cell.dim, X_WITH_RADIATOR),
                    1, longOf(loopInfo(cell.dim, X_WITH_RADIATOR), CELLS));
            assertEquals("premise: and none at all on the control: "
                            + loopInfo(cell.dim, X_WITHOUT_RADIATOR),
                    0, longOf(loopInfo(cell.dim, X_WITHOUT_RADIATOR), CELLS));

            // The control on the environment first: an ordinary star must leave a charged loop
            // shedding, so the reversal below is the star and not something the rig does regardless.
            long capacity = longOf(loopInfo(cell.dim, X_WITH_RADIATOR), HEAT_CAPACITY);
            String calm = cycle(cell.dim, X_WITH_RADIATOR, 100L * capacity);
            assertTrue("premise: under an ordinary star a charged loop must still be shedding: " + calm,
                    longOf(calm, CYCLE_REJECTED) > 0);

            setConfig("shipHeatStarFluxReferenceKelvin", FIERCE_STAR_KELVIN);
            try {
                String radiating = cycle(cell.dim, X_WITH_RADIATOR, 0L);
                assertTrue("under a star this strong the net must run BACKWARDS — a loop that can only "
                                + "ever lose heat gives a ship free immunity to its environment: "
                                + radiating, longOf(radiating, CYCLE_REJECTED) < 0);
                assertTrue("and the energy must actually be in the loop, not merely reported: "
                        + radiating, longOf(radiating, HEAT_STORED) > 0);

                String bare = cycle(cell.dim, X_WITHOUT_RADIATOR, 0L);
                assertEquals("a loop with no radiating surface must take nothing from the environment "
                                + "— the cells are the coupling, and a hull is not one: " + bare,
                        0, longOf(bare, HEAT_STORED));
            } finally {
                setConfig("shipHeatStarFluxReferenceKelvin", DEFAULT_STAR_KELVIN);
            }
        } finally {
            release(cell);
        }
    }

    /**
     * A shield is sunscreen and never a wall. Asked for ALL of the incident flux, it takes most and
     * leaves a residue, so a ship parked in a star heats slowly rather than not at all.
     *
     * <p>Both halves matter and neither implies the other. The attenuation is real — the shielded loop
     * must gain far less than an identical unshielded one under the same star — and it is bounded: the
     * shielded loop must still gain something, with the configuration demanding a hundred percent. That
     * second assertion is the clause: the refusal lives in the code, so no setting can reach it.</p>
     */
    @Test
    public void aShieldThinsTheFluxAndNeverRemovesIt() throws Exception {
        Cell cell = occupyHomeCell();
        try {
            int emitterX = X_SHIELD_GENERATOR + 1;
            place(cell.dim, X_SHIELD_GENERATOR, "affs:shield_generator", null);
            place(cell.dim, emitterX, "affs:field_generator", null);
            buildLoopWithRadiatorFirst(cell.dim, X_SHIELDED);
            buildLoopWithRadiatorFirst(cell.dim, X_UNSHIELDED);

            for (int i = 0; i < 15; i++) {
                exec("artest energy inject " + cell.dim + " " + X_SHIELD_GENERATOR + " " + Y + " " + Z
                        + " 4000");
                exec("artest tile force-tick " + cell.dim + " " + X_SHIELD_GENERATOR + " " + Y + " " + Z
                        + " 1");
                exec("artest shield tick " + cell.dim);
            }
            String emitter = exec("artest shield read " + cell.dim + " " + emitterX + " " + Y + " " + Z);
            assertTrue("premise: the emitter never came up, so nothing below is a test of a shield: "
                    + emitter, emitter.contains("\"powered\":true"));

            setConfig("shipHeatStarFluxReferenceKelvin", FIERCE_STAR_KELVIN);
            setConfig("shipHeatShieldAttenuation", "1000");
            try {
                String shielded = cycle(cell.dim, X_SHIELDED, 0L);
                String unshielded = cycle(cell.dim, X_UNSHIELDED, 0L);
                long gainedShielded = longOf(shielded, HEAT_STORED);
                long gainedUnshielded = longOf(unshielded, HEAT_STORED);

                assertTrue("premise: the unshielded control must be heating hard, or there is nothing "
                        + "for the shield to have stopped: " + unshielded, gainedUnshielded > 0);
                assertTrue("a raised shield must take most of the incident flux off the ship (shielded="
                                + gainedShielded + " unshielded=" + gainedUnshielded + "): " + shielded,
                        gainedShielded < gainedUnshielded / 10L);
                assertTrue("and it must NOT take all of it, however much the configuration asks for — "
                                + "a ship parked in a star heats slowly and always (shielded="
                                + gainedShielded + "): " + shielded, gainedShielded > 0);
            } finally {
                setConfig("shipHeatShieldAttenuation", "900");
                setConfig("shipHeatStarFluxReferenceKelvin", DEFAULT_STAR_KELVIN);
            }
        } finally {
            release(cell);
        }
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /** A live cell of the overworld's own system, and the slot world it is bound to. */
    private static final class Cell {
        private final String args;
        private final int dim;

        private Cell(String args, int dim) {
            this.args = args;
            this.dim = dim;
        }
    }

    /**
     * Materialize the cell the overworld itself lives in, so the sky over the rig is a real system with
     * a real star at its real distance rather than something this test invented.
     */
    private Cell occupyHomeCell() throws Exception {
        String cellKey = dimCell(exec("artest space cell-info 0 0 0 0"));
        String[] sectors = cellKey.split("_");
        assertEquals("a cell key is a sector triple: " + cellKey, 3, sectors.length);
        String args = sectors[0] + " " + sectors[1] + " " + sectors[2];
        String occupied = exec("artest space occupy " + args);
        assertTrue("the cell must materialize, or there is no space environment to test in: "
                        + occupied,
                occupied.contains("\"ok\":true") && occupied.contains("\"worldLoaded\":true"));
        return new Cell(args, (int) longOf(occupied, SLOT_DIM));
    }

    /** Hand the slot back. A test that holds a pool slot is a test that breaks somebody else's. */
    private void release(Cell cell) throws Exception {
        exec("artest space release " + cell.args);
    }

    /** A straight run of {@value #LOOP_LENGTH} blocks with {@code radiators} cells at the far end. */
    private void buildLoop(int dim, int x0, int radiators) throws Exception {
        buildRun(dim, x0, LOOP_LENGTH - radiators, radiators);
    }

    /**
     * The same run with its single cell at the NEAR end — the shield scenario needs the radiator at a
     * known distance from the emitter and the rest of the run trailing away from it.
     */
    private void buildLoopWithRadiatorFirst(int dim, int x0) throws Exception {
        buildRun(dim, x0, 0, 1);
    }

    private void buildRun(int dim, int x0, int radiatorOffset, int radiators) throws Exception {
        for (int i = 0; i < LOOP_LENGTH; i++) {
            boolean radiator = i >= radiatorOffset && i < radiatorOffset + radiators;
            place(dim, x0 + i, radiator ? "advancedrocketry:heatRadiator" : "advancedrocketry:heatPipe",
                    radiator ? RADIATOR_FACING_UP : null);
        }
        String info = loopInfo(dim, x0);
        assertTrue("the run must be built before it is solved: " + info, info.contains("\"ok\":true"));
        String solved = exec("artest subnet solve heat " + dim + " 1");
        assertTrue("solve failed in dim " + dim + ": " + solved, solved.contains("\"ticksSolved\":1"));
    }

    /**
     * Charge the loop to a known energy and advance exactly one tick, in ONE probe call.
     *
     * <p>One call because between probe calls the world ticks normally and the heat domain ticks with
     * it, so charging in one command and measuring in the next measures whatever survived some natural
     * ticks — and here that gap would quietly deliver a whole star's worth of flux into the answer.</p>
     */
    private String cycle(int dim, int x0, long charge) throws Exception {
        String cycled = exec("artest heat cycle " + dim + " " + x0 + " " + Y + " " + Z + " " + charge
                + " 1");
        assertTrue("heat cycle failed at " + x0 + " in dim " + dim + ": " + cycled,
                cycled.contains("\"inLoop\":true"));
        assertEquals("premise: the loop must have been charged with exactly what was asked: " + cycled,
                charge, longOf(cycled, CHARGED));
        return cycled;
    }

    private void place(int dim, int x, String block, String meta) throws Exception {
        String resp = exec("artest place " + dim + " " + x + " " + Y + " " + Z + " " + block
                + (meta == null ? "" : " " + meta));
        assertTrue(block + " place failed at " + x + " in dim " + dim + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private void setConfig(String key, String value) throws Exception {
        String resp = exec("artest config set " + key + " " + value);
        assertTrue("config set " + key + "=" + value + " failed: " + resp, resp.contains("\"ok\":true"));
    }

    private String loopInfo(int dim, int x) throws Exception {
        return exec("artest subnet info heat " + dim + " " + x + " " + Y + " " + Z);
    }

    private static String dimCell(String json) {
        Matcher m = Pattern.compile("\"dimCell\":\"([^\"]+)\"").matcher(json);
        assertTrue("probe response carries no \"dimCell\": " + json, m.find());
        return m.group(1);
    }

    private static long longOf(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Long.parseLong(m.group(1));
    }
}
