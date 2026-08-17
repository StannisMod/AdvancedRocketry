package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * How heat gets off a ship: the radiating cell, and the two things that decide how much it sheds.
 *
 * <p>Both are laws rather than tuned numbers, which is why they can be asserted at all. Area is
 * linear — twice the cells, twice the rejection — and temperature is QUARTIC, so a loop a hundred
 * degrees hotter sheds far more than twice as much. The difference between those two is the entire
 * reason a chiller is worth building later, so a test that could not tell them apart would be
 * pinning nothing.</p>
 *
 * <p>The third scenario is the clearance rule, and it is asserted from the loop's side: a blocked
 * cell must leave the loop's stored energy untouched, not merely report a zero.</p>
 */
public class HeatRejectionTest extends AbstractSharedServerTest {

    private static final Pattern HEAT_STORED = Pattern.compile("\"heatStored\":(-?\\d+)");
    private static final Pattern HEAT_CAPACITY = Pattern.compile("\"heatCapacity\":(-?\\d+)");
    private static final Pattern TEMPERATURE = Pattern.compile("\"temperatureMilliK\":(-?\\d+)");
    private static final Pattern EXCHANGERS = Pattern.compile("\"exchangers\":(-?\\d+)");
    private static final Pattern CELLS = Pattern.compile("\"radiatingCells\":(-?\\d+)");
    private static final Pattern OBSTRUCTION = Pattern.compile("\"obstruction\":(-?\\d+)");
    /** `heat cycle` names its own figures, so they do not collide with the loop readout's. */
    private static final Pattern CYCLE_REJECTED = Pattern.compile("\"rejected\":(-?\\d+)");
    private static final Pattern CHARGED = Pattern.compile("\"charged\":(-?\\d+)");

    /** High and in the open, so a radiator facing up has nothing over it but sky. */
    private static final int Y = 100;
    private static final int Z = 2700;
    private static final int X_AREA_ONE = 1000;
    private static final int X_AREA_THREE = 1010;
    private static final int X_QUARTIC = 1020;
    private static final int X_BLOCKED = 1030;

    /** `getStateFromMeta` maps this to a cell radiating UP, so obstructions go straight above. */
    private static final String RADIATOR_FACING_UP = "1";

    /** Both loops are four blocks long, so the same energy in each is the same temperature. */
    private static final int LOOP_LENGTH = 4;

    /**
     * Twice the radiating surface sheds twice the heat. Both loops are built to the same LENGTH and
     * given the same energy, so they sit at the same temperature and the only thing that differs is
     * how much of them is radiator — which is what makes the ratio mean area and nothing else.
     */
    @Test
    public void rejectionScalesWithTheAreaBuilt() throws Exception {
        // Same four blocks in each, one radiator against three.
        buildLoop(X_AREA_ONE, 1);
        buildLoop(X_AREA_THREE, 3);
        solve(1);

        String one = loopInfo(X_AREA_ONE);
        String three = loopInfo(X_AREA_THREE);
        assertEquals("premise: both loops must be the same size: " + one + " | " + three,
                longOf(one, HEAT_CAPACITY), longOf(three, HEAT_CAPACITY));
        assertEquals("premise: one radiating cell on the first loop: " + one, 1, longOf(one, CELLS));
        assertEquals("premise: three on the second: " + three, 3, longOf(three, CELLS));

        long capacity = longOf(one, HEAT_CAPACITY);
        long charge = 100L * capacity; // a hundred kelvin above ambient, in both
        long rejectedByOne = shedInOneTickFrom(X_AREA_ONE, charge);
        long rejectedByThree = shedInOneTickFrom(X_AREA_THREE, charge);
        assertTrue("premise: the single cell must shed something at all, or the ratio below is "
                + "meaningless: " + loopInfo(X_AREA_ONE), rejectedByOne > 0);
        // Integer shares, so allow a unit of slack per cell and no more.
        assertTrue("three cells must shed three times what one does (one=" + rejectedByOne
                        + " three=" + rejectedByThree + ")",
                Math.abs(rejectedByThree - 3L * rejectedByOne) <= 3L);
    }

    /**
     * The same cell, a hotter loop. Rejection follows `T⁴ − T_amb⁴`, so raising the loop from a
     * hundred degrees over ambient to two hundred must more than DOUBLE what it sheds — and by a
     * specific amount the test derives rather than states.
     */
    @Test
    public void rejectionFollowsTheFourthPowerOfTemperature() throws Exception {
        buildLoop(X_QUARTIC, 1);
        solve(1);

        String cold = loopInfo(X_QUARTIC);
        long capacity = longOf(cold, HEAT_CAPACITY);
        double ambient = longOf(cold, TEMPERATURE) / 1000.0D;
        assertEquals("premise: a loop that has done nothing holds nothing: " + cold,
                0, longOf(cold, HEAT_STORED));

        long shedAtHundred = shedInOneTickFrom(X_QUARTIC, 100L * capacity);
        long shedAtTwoHundred = shedInOneTickFrom(X_QUARTIC, 200L * capacity);

        double expectedRatio = (pow4(ambient + 200.0D) - pow4(ambient))
                / (pow4(ambient + 100.0D) - pow4(ambient));
        double actualRatio = (double) shedAtTwoHundred / shedAtHundred;

        assertTrue("premise: both legs must shed something (100K=" + shedAtHundred + " 200K="
                + shedAtTwoHundred + ")", shedAtHundred > 0 && shedAtTwoHundred > 0);
        assertTrue("doubling the temperature rise must do markedly MORE than double the rejection, "
                        + "or the law is linear and the whole chiller tier is pointless (ratio="
                        + actualRatio + ")", actualRatio > 2.5D);
        assertTrue("and it must follow the fourth power: expected ratio " + expectedRatio
                        + " from ambient " + ambient + " K, measured " + actualRatio,
                Math.abs(actualRatio - expectedRatio) < 0.1D * expectedRatio);
    }

    /**
     * A cell with something in front of it sheds nothing, and says where the obstruction is.
     *
     * <p>Asserted from the LOOP's side as well as the cell's: the energy must still be there after
     * the tick. A cell that reported zero while the heat quietly left anyway would pass a test that
     * only read the cell.</p>
     */
    @Test
    public void anObstructedCellShedsNothingAndSaysWhereTheBlockIs() throws Exception {
        buildLoop(X_BLOCKED, 1);
        solve(1);
        long capacity = longOf(loopInfo(X_BLOCKED), HEAT_CAPACITY);
        int radiatorX = X_BLOCKED + LOOP_LENGTH - 1;

        // Control first, with the sky still clear: the same rig must genuinely shed.
        String clear = exec("artest heat read 0 " + radiatorX + " " + Y + " " + Z);
        assertTrue("premise: the cell must be a radiator: " + clear, clear.contains("\"isRadiator\":true"));
        assertEquals("premise: nothing above it yet: " + clear, 0, longOf(clear, OBSTRUCTION));
        long shedWhileClear = shedInOneTickFrom(X_BLOCKED, 100L * capacity);
        assertTrue("premise: an unobstructed cell must genuinely shed, or the zero below is not "
                + "evidence of anything (shed=" + shedWhileClear + ")", shedWhileClear > 0);

        // Now put a block in its way, one above — inside any clearance the config can be set to.
        String placed = exec("artest place 0 " + radiatorX + " " + (Y + 1) + " " + Z + " minecraft:stone");
        assertTrue("obstruction place failed: " + placed, placed.contains("\"placed\":true"));

        long charge = 100L * capacity;
        String cycled = exec("artest heat cycle 0 " + X_BLOCKED + " " + Y + " " + Z + " " + charge + " 1");
        assertEquals("nothing may be shed by an obstructed cell: " + cycled,
                0, longOf(cycled, CYCLE_REJECTED));
        assertEquals("and the energy must still be in the loop, not quietly gone: " + cycled,
                charge, longOf(cycled, HEAT_STORED));

        String blockedCell = exec("artest heat read 0 " + radiatorX + " " + Y + " " + Z);
        assertEquals("the cell must report the obstruction one block away, so a player can go and "
                + "find it: " + blockedCell, 1, longOf(blockedCell, OBSTRUCTION));
        assertEquals("and must count as no radiating surface: " + blockedCell,
                0, longOf(blockedCell, CELLS));

        assertEquals("the loop must still see the machine — it is obstructed, not gone: " + cycled,
                1, longOf(cycled, EXCHANGERS));
        assertEquals("with no working surface between them: " + cycled, 0, longOf(cycled, CELLS));
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /**
     * A straight run of {@value #LOOP_LENGTH} blocks: pipes first, then radiating cells at the far
     * end, all facing up. Every loop is the same length whatever the mix, because a comparison
     * between two loops is only about area if their capacity is equal.
     */
    private void buildLoop(int x0, int radiators) throws Exception {
        for (int i = 0; i < LOOP_LENGTH - radiators; i++) {
            place(x0 + i, "advancedrocketry:heatPipe", null);
        }
        for (int i = LOOP_LENGTH - radiators; i < LOOP_LENGTH; i++) {
            place(x0 + i, "advancedrocketry:heatRadiator", RADIATOR_FACING_UP);
        }
        String info = loopInfo(x0);
        assertTrue("the run must be built before it is solved: " + info, info.contains("\"ok\":true"));
    }

    /**
     * Charge the loop to a known energy and advance exactly one tick, in ONE probe call; answers
     * what left.
     *
     * <p>The single call is the whole point. Between probe calls the world ticks normally and the
     * heat domain ticks with it, so charging in one command and measuring in the next measures
     * whatever survived some natural ticks — and two loops with different radiating area lose
     * different amounts in that gap, which corrupts precisely the ratio this test is about.</p>
     */
    private long shedInOneTickFrom(int x0, long charge) throws Exception {
        String cycled = exec("artest heat cycle 0 " + x0 + " " + Y + " " + Z + " " + charge + " 1");
        assertTrue("heat cycle failed at " + x0 + ": " + cycled, cycled.contains("\"inLoop\":true"));
        assertEquals("premise: the loop must have been charged with exactly what was asked: " + cycled,
                charge, longOf(cycled, CHARGED));
        return longOf(cycled, CYCLE_REJECTED);
    }

    private void place(int x, String block, String meta) throws Exception {
        String resp = exec("artest place 0 " + x + " " + Y + " " + Z + " " + block
                + (meta == null ? "" : " " + meta));
        assertTrue(block + " place failed at " + x + ": " + resp, resp.contains("\"placed\":true"));
    }

    private void solve(int ticks) throws Exception {
        String solved = exec("artest subnet solve heat 0 " + ticks);
        assertTrue("solve failed: " + solved, solved.contains("\"ticksSolved\":" + ticks));
    }

    private String loopInfo(int x) throws Exception {
        return exec("artest subnet info heat 0 " + x + " " + Y + " " + Z);
    }

    private static double pow4(double v) {
        return v * v * v * v;
    }

    private static long longOf(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Long.parseLong(m.group(1));
    }
}
