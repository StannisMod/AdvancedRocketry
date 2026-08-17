package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * The chiller: two coolant loops with a heat pump between them, and the one clause a pump breaks
 * without anything complaining.
 *
 * <p>The ship's machines heat the cold loop; the chiller moves that heat into the hot loop and pays
 * electricity; the hot loop therefore runs hot, and radiated power is quartic in temperature, so its
 * radiators shed several times what the cold loop's could. <b>Nobody sets the hot loop's
 * temperature.</b> Energy piles up in it against its own capacity and the temperature follows — which
 * is what makes it a real reservoir rather than a number added to another number.</p>
 *
 * <p>What the pump costs joins the HOT side: the hot loop receives the heat PLUS the work, and only
 * the heat comes off the cold one. A pump implemented the obvious way moves `Q` and delivers `Q`,
 * which looks right in every readout and hands the player free thermodynamics.</p>
 */
public class HeatChillerTest extends AbstractSharedServerTest {

    private static final Pattern CHARGED = Pattern.compile("\"charged\":(-?\\d+)");
    private static final Pattern PUMPED_OUT = Pattern.compile("\"pumpedOut\":(-?\\d+)");
    private static final Pattern DELIVERED = Pattern.compile("\"delivered\":(-?\\d+)");
    private static final Pattern HEAT_STORED = Pattern.compile("\"heatStored\":(-?\\d+)");
    private static final Pattern WORK = Pattern.compile("\"work\":(-?\\d+)");
    private static final Pattern PUMPS = Pattern.compile("\"pumps\":(-?\\d+)");
    private static final Pattern MEMBERS = Pattern.compile("\"members\":(-?\\d+)");
    private static final Pattern CAPACITY = Pattern.compile("\"heatCapacity\":(-?\\d+)");
    private static final Pattern TEMPERATURE = Pattern.compile("\"temperatureMilliK\":(-?\\d+)");

    /** High and clear, so a radiating cell has nothing over it. */
    private static final int Y = 100;
    private static final int Z = 2760;
    private static final int X_PUMP = 1100;
    private static final int X_UNPOWERED = 1140;

    /** `EnumFacing.getIndex()`: 5 is EAST, so the hot side is the +X end of the run. */
    private static final String CHILLER_FACING_EAST = "5";
    private static final String RADIATOR_FACING_UP = "1";

    /** Cold run, then the chiller, then the hot run: three pipes each side. */
    private static final int COLD_LENGTH = 3;

    /**
     * The clause, as three numbers from ONE tick of ONE loop: what came off the cold side, what was
     * handed to the hot side, and what was paid. The first plus the last must equal the middle.
     *
     * <p>Read from the cold loop deliberately. The receiving loop is a separate component and is solved
     * in whatever order the solver reaches it, so a test that compared one loop's tick against the
     * other's would be measuring the visit order as much as the physics. The hot loop's own arrival
     * figure is asserted too, as an independent witness that the energy really landed.</p>
     */
    @Test
    public void theHotLoopReceivesTheHeatPlusTheWork() throws Exception {
        buildTwoLoops(X_PUMP);
        solve(2);

        String cold = loopInfo(coldAnchor(X_PUMP));
        String hot = loopInfo(hotAnchor(X_PUMP));
        assertEquals("premise: the cold run must be its own loop: " + cold,
                COLD_LENGTH, longOf(cold, MEMBERS));
        assertEquals("premise: and the hot run another — a chiller between them must NOT have joined "
                + "them into one: " + hot, COLD_LENGTH, longOf(hot, MEMBERS));
        assertEquals("premise: both must see the chiller beside them: " + cold, 1, longOf(cold, PUMPS));
        assertEquals("premise: from the hot side too: " + hot, 1, longOf(hot, PUMPS));

        // The chiller's own metal counts as the HOT loop's thermal mass, and only the hot loop's: it is
        // a lump of refrigerant in contact with that coolant. Both runs are the same length, so if the
        // machine's mass were being ignored the two capacities would simply match.
        assertTrue("a chiller bolted onto the hot loop must add its own thermal mass to it — the hot "
                        + "side has to climb more slowly than its pipes alone would explain (cold="
                        + longOf(cold, CAPACITY) + " hot=" + longOf(hot, CAPACITY) + ")",
                longOf(hot, CAPACITY) > longOf(cold, CAPACITY));
        assertEquals("and only to the hot side — the loop it merely draws FROM carries none of the "
                        + "machine: " + cold, COLD_LENGTH * 20L, longOf(cold, CAPACITY));

        powerChiller(X_PUMP);
        long capacity = longOf(cold, CAPACITY);
        long charge = 100L * capacity;
        // ONE tick, on a tick where the loop actually holds heat. These are per-tick figures: run the
        // loop dry over many ticks and the last one reports zeros, which says nothing about the pump.
        String cycled = cycle(coldAnchor(X_PUMP), charge, 1);

        long movedOut = longOf(cycled, PUMPED_OUT);
        long delivered = longOf(cycled, DELIVERED);
        long work = longOf(cycled, WORK);

        assertTrue("premise: the chiller must have shifted something: " + cycled, movedOut > 0);
        assertTrue("premise: and paid for it: " + cycled, work > 0);
        assertEquals("THE CLAUSE: the hot loop receives the heat PLUS the work — a pump whose own work "
                + "does not join the hot side has invented energy from nowhere: " + cycled,
                delivered, movedOut + work);

        // The receiving end, independently — witnessed by the hot loop's STATE and not by a per-tick
        // counter. `pumpedIn` is drained on the hot loop's own tick, and the world ticks between probe
        // calls, so by the time a second command can read it the figure is legitimately zero again.
        // What is durable is that the energy is sitting there.
        String hotAfter = loopInfo(hotAnchor(X_PUMP));
        assertTrue("the hot loop must be HOLDING the energy that was handed to it (delivered="
                        + delivered + "): " + hotAfter, longOf(hotAfter, HEAT_STORED) > 0);
        assertTrue("and be above ambient because of it: " + hotAfter,
                longOf(hotAfter, TEMPERATURE) > 1000L * ambientKelvinFrom(cold));
    }

    /**
     * Nobody sets the hot loop's temperature: it is what its own capacity makes of the energy it has
     * been given. So a chiller run for a while must leave the hot loop measurably hotter than the cold
     * one — which is the whole reason the tier exists, since rejection is quartic in temperature.
     */
    @Test
    public void theHotLoopIsHotterBecauseEnergyAccumulatesInIt() throws Exception {
        buildTwoLoops(X_UNPOWERED);
        solve(2);
        long capacity = longOf(loopInfo(coldAnchor(X_UNPOWERED)), CAPACITY);

        // An unpowered chiller first: it must shift nothing, and the hot loop must stay at ambient.
        String starved = cycle(coldAnchor(X_UNPOWERED), 100L * capacity, 1);
        assertEquals("premise: the loop must see the chiller: " + starved, 1, longOf(starved, PUMPS));
        assertEquals("an unpowered chiller shifts nothing: " + starved, 0, longOf(starved, PUMPED_OUT));
        assertEquals("and pays nothing: " + starved, 0, longOf(starved, WORK));
        long hotAmbient = longOf(loopInfo(hotAnchor(X_UNPOWERED)), TEMPERATURE);

        // Power it and run ONE tick: the transfer is a per-tick figure and must be read on a tick
        // where the cold loop still held something. What the hot loop does with the energy afterwards
        // is a STATE, and that is what the rest of this test reads.
        powerChiller(X_UNPOWERED);
        String driven = cycle(coldAnchor(X_UNPOWERED), 100L * capacity, 1);
        assertTrue("the same chiller with power must shift heat, or the zeros above measured nothing: "
                + driven, longOf(driven, PUMPED_OUT) > 0);

        String hotAfter = loopInfo(hotAnchor(X_UNPOWERED));
        long hotNow = longOf(hotAfter, TEMPERATURE);
        assertTrue("the hot loop must be hotter than it was left at ambient (" + hotAmbient + " → "
                + hotNow + "): " + hotAfter, hotNow > hotAmbient);
        assertTrue("and hotter than the cold loop it is fed from — the pump works AGAINST the gradient, "
                        + "which is what its electricity buys: " + hotAfter + " | " + driven,
                hotNow > longOf(driven, TEMPERATURE));
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /**
     * Cold run, chiller, hot run, in a straight line along X. The chiller faces east, so its hot side
     * is the far run and its cold side the near one — and because it is not a network node, the two
     * runs stay two loops with it sitting between them.
     */
    private void buildTwoLoops(int x0) throws Exception {
        for (int i = 0; i < COLD_LENGTH; i++) {
            place(x0 + i, "advancedrocketry:heatPipe", null);
        }
        place(x0 + COLD_LENGTH, "advancedrocketry:heatChiller", CHILLER_FACING_EAST);
        // The hot run: two pipes and a radiating cell, so it can actually shed what it is given.
        place(x0 + COLD_LENGTH + 1, "advancedrocketry:heatPipe", null);
        place(x0 + COLD_LENGTH + 2, "advancedrocketry:heatPipe", null);
        place(x0 + COLD_LENGTH + 3, "advancedrocketry:heatRadiator", RADIATOR_FACING_UP);
    }

    private int coldAnchor(int x0) {
        return x0;
    }

    private int hotAnchor(int x0) {
        return x0 + COLD_LENGTH + 1;
    }

    private void powerChiller(int x0) throws Exception {
        String resp = exec("artest energy inject 0 " + (x0 + COLD_LENGTH) + " " + Y + " " + Z
                + " 100000000");
        assertTrue("chiller power failed: " + resp, resp.contains("\"ok\":true"));
    }

    /** Charge a loop and advance it, atomically — the world ticks between probe calls. */
    private String cycle(int x, long charge, int ticks) throws Exception {
        String resp = exec("artest heat cycle 0 " + x + " " + Y + " " + Z + " " + charge + " " + ticks);
        assertTrue("heat cycle failed at " + x + ": " + resp, resp.contains("\"inLoop\":true"));
        assertEquals("premise: the loop must hold exactly what was asked: " + resp,
                charge, longOf(resp, CHARGED));
        return resp;
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

    /** Ambient in kelvin, read off a loop that is holding nothing rather than restated as a number. */
    private static long ambientKelvinFrom(String coldLoopWhileEmpty) {
        return longOf(coldLoopWhileEmpty, TEMPERATURE) / 1000L;
    }

    private static long longOf(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Long.parseLong(m.group(1));
    }
}
