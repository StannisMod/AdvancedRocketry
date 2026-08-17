package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * A compartment's air as a HEAT reservoir: it has a temperature, it has a capacity, and gas arriving
 * from somewhere else mixes into it rather than replacing what was there.
 *
 * <p>The mixing law is the one worth a test, and it is worth stating why the arrangement is lopsided.
 * The calorimeter rule and a plain average agree exactly when the two sides are the same size, so a
 * tidy 50/50 scenario would pass on an implementation that simply split the difference — which is not
 * the rule and is wrong the moment a small bottle is vented into a large room. The room here is
 * several times the gas admitted into it, and the expected answer is computed from the two pressures
 * the probe reports rather than stated.</p>
 *
 * <p><b>What this does NOT cover, and why.</b> D266-4 speaks of zones EXCHANGING air, and there is no
 * such path in the game yet: the recirculator serves one zone, a duct stores nothing, and the only
 * place gas actually arrives from elsewhere is a separator's tank. So the rule is pinned on the one
 * live route, and the seam for the future one is correct by construction — the temperature of
 * arriving gas is a required argument, so a zone-to-zone mover cannot be written without deciding it.
 * </p>
 */
public class ZoneAirIsAReservoirTest extends AbstractSharedServerTest {

    private static final Pattern AIR_O2 = Pattern.compile("\"airO2\":(-?\\d+)");
    private static final Pattern AIR_PRESSURE = Pattern.compile("\"airPressure\":(-?\\d+)");
    private static final Pattern AIR_TEMP = Pattern.compile("\"airTempMilliK\":(-?\\d+)");
    private static final Pattern AIR_CAPACITY = Pattern.compile("\"airHeatCapacity\":(-?\\d+)");
    private static final Pattern CONFIG_VALUE = Pattern.compile("\"value\":(-?\\d+)");

    private static final int CY = 64;
    private static final int CZ = 2960;
    private static final int CX_MIX = 2000;
    private static final int CX_DRAW = 2200;
    private static final int CX_VACUUM = 2400;

    /** Hot enough that the mix is unmistakable, and nowhere near any threshold this test cares about. */
    private static final int HOT_MILLI_K = 400_000;

    /**
     * Gas arriving at a different temperature mixes by HOW MUCH of each there is, not by halves.
     *
     * <p>A hot room, and oxygen let in from a tank at ordinary temperature. The room must end up
     * between the two and much nearer its own starting point, because there is far more room-air than
     * admitted gas — and the test derives exactly where from the pressures before and after, so the
     * assertion is the law and not a number.</p>
     */
    @Test
    public void gasArrivingMixesByHowMuchOfEachThereIs() throws Exception {
        buildRoomWithVent(CX_MIX);
        // Oxygen-poor so the governor leaves plenty of headroom to admit into.
        setAir(CX_MIX, 790_000, 60_000, 0, HOT_MILLI_K);

        String before = ventInfo(CX_MIX);
        long pressureBefore = extract(before, AIR_PRESSURE);
        long tempBefore = extract(before, AIR_TEMP);
        assertEquals("premise: the room must actually start hot, or there is nothing to mix into: "
                + before, HOT_MILLI_K, tempBefore);
        assertTrue("premise: and must hold air at all: " + before, pressureBefore > 0);

        int ambient = configInt("shipHeatAmbientKelvin");
        assertTrue("premise: the tank's gas must be at a different temperature from the room, or "
                        + "this test cannot tell mixing from doing nothing (room=" + tempBefore
                        + " tank=" + (ambient * 1000) + ")",
                Math.abs(tempBefore - ambient * 1000L) > 50_000L);

        runCombinerInto(CX_MIX);

        String after = ventInfo(CX_MIX);
        long pressureAfter = extract(after, AIR_PRESSURE);
        long tempAfter = extract(after, AIR_TEMP);
        assertTrue("premise: the combiner must actually have put gas in (before=" + pressureBefore
                + " after=" + pressureAfter + "): " + after, pressureAfter > pressureBefore);
        assertTrue("premise: and enough of it to tell a weighted mean from an average: " + after,
                pressureAfter - pressureBefore > pressureBefore / 20);

        double admitted = pressureAfter - pressureBefore;
        double expected = (pressureBefore * (tempBefore / 1000.0D) + admitted * ambient)
                / (pressureBefore + admitted);
        double plainAverage = (tempBefore / 1000.0D + ambient) / 2.0D;
        double measured = tempAfter / 1000.0D;

        assertTrue("the room must end up at the enthalpy-weighted mean of what was there and what "
                        + "arrived: expected " + expected + " K from " + pressureBefore + " of air at "
                        + (tempBefore / 1000.0D) + " K meeting " + admitted + " at " + ambient
                        + " K, measured " + measured + " K",
                Math.abs(measured - expected) < 1.5D);
        assertTrue("and NOT at the plain average of the two temperatures (" + plainAverage + " K) — "
                        + "the two agree only when the sides are equal, which is exactly the case "
                        + "this scenario avoids",
                Math.abs(measured - plainAverage) > 5.0D);
    }

    /**
     * Taking gas OUT leaves the temperature where it was and lowers the capacity.
     *
     * <p>It reads as though removing air should cool the room, and it must not: what is left is the
     * same gas at the same temperature, there is simply less of it. The capacity falling is the other
     * half — without it the assertion would also pass on an implementation where nothing happened at
     * all.</p>
     */
    @Test
    public void drawingGasOutLeavesTheTemperatureAndLowersTheCapacity() throws Exception {
        buildRoomWithVent(CX_DRAW);
        setAir(CX_DRAW, 790_000, 210_000, 0, HOT_MILLI_K);

        String before = ventInfo(CX_DRAW);
        long tempBefore = extract(before, AIR_TEMP);
        long capacityBefore = extract(before, AIR_CAPACITY);
        assertEquals("premise: the room must start hot: " + before, HOT_MILLI_K, tempBefore);
        assertTrue("premise: and must have a real capacity to lose: " + before, capacityBefore > 0);

        // The separator's default direction: gas out of the room and into its tank.
        placeSeparator(CX_DRAW);
        injectEnergyAt(CX_DRAW + 1, 1_000_000);
        forceTick(CX_DRAW + 1, 200);

        String after = ventInfo(CX_DRAW);
        long capacityAfter = extract(after, AIR_CAPACITY);
        long tempAfter = extract(after, AIR_TEMP);
        assertTrue("premise: the separator must actually have taken gas out (capacity before="
                        + capacityBefore + " after=" + capacityAfter + "): " + after,
                capacityAfter < capacityBefore);
        assertEquals("what is left is the same gas at the same temperature — removing part of a body "
                + "does not cool the rest: " + after, tempBefore, tempAfter);
    }

    /**
     * Air that is not there has no temperature of its own.
     *
     * <p>A zone pumped down to vacuum must report the ambient every other reader assumes, not the
     * number it was holding when it still had air in it. A stale reading here would hand the failure
     * ladder a hot compartment where there is nothing to be hot.</p>
     */
    @Test
    public void airThatIsNotThereHasNoTemperature() throws Exception {
        buildRoomWithVent(CX_VACUUM);
        setAir(CX_VACUUM, 790_000, 210_000, 0, HOT_MILLI_K);
        assertEquals("premise: the room must be hot while it still holds air: " + ventInfo(CX_VACUUM),
                HOT_MILLI_K, extract(ventInfo(CX_VACUUM), AIR_TEMP));

        setAir(CX_VACUUM, 0, 0, 0, HOT_MILLI_K);

        String empty = ventInfo(CX_VACUUM);
        int ambient = configInt("shipHeatAmbientKelvin");
        assertEquals("a zone holding nothing must read ambient, not what it was at when it still had "
                + "air: " + empty, ambient * 1000L, extract(empty, AIR_TEMP));
        assertEquals("and must hold no heat at all: " + empty, 0, extract(empty, AIR_CAPACITY));
    }

    // ─── the rig ───────────────────────────────────────────────────────

    private void buildRoomWithVent(int cx) throws Exception {
        int by = CY, bz = CZ;
        exec("artest fill 0 " + (cx - 2) + " " + (by - 1) + " " + (bz - 2)
                + " " + (cx + 2) + " " + by + " " + (bz + 2) + " minecraft:stone");
        for (int yy = by + 1; yy <= by + 2; yy++) {
            exec("artest fill 0 " + (cx - 2) + " " + yy + " " + (bz - 2)
                    + " " + (cx + 2) + " " + yy + " " + (bz + 2) + " minecraft:stone");
            exec("artest fill 0 " + (cx - 1) + " " + yy + " " + (bz - 1)
                    + " " + (cx + 1) + " " + yy + " " + (bz + 1) + " minecraft:air");
        }
        exec("artest fill 0 " + (cx - 2) + " " + (by + 3) + " " + (bz - 2)
                + " " + (cx + 2) + " " + (by + 3) + " " + (bz + 2) + " minecraft:stone");

        String vent = exec("artest place 0 " + cx + " " + CY + " " + CZ
                + " advancedrocketry:oxygenVent");
        assertTrue("vent place failed: " + vent, vent.contains("\"placed\":true"));
        injectEnergyAt(cx, 1_000_000);
        String oxygen = exec("artest fluid inject 0 " + cx + " " + CY + " " + CZ + " oxygen 16000");
        assertTrue("oxygen inject failed: " + oxygen, oxygen.contains("\"ok\":true"));
        exec("artest tile force-tick 0 " + cx + " " + CY + " " + CZ + " 1");
        exec("artest vent reseal 0 " + cx + " " + CY + " " + CZ);
        exec("artest tile force-tick 0 " + cx + " " + CY + " " + CZ + " 5");
    }

    /** A separator in combine mode, with oxygen in its tank, run long enough to empty it. */
    private void runCombinerInto(int cx) throws Exception {
        placeSeparator(cx);
        injectEnergyAt(cx + 1, 1_000_000);
        String filled = exec("artest fluid inject 0 " + (cx + 1) + " " + CY + " " + CZ
                + " oxygen 8000");
        assertTrue("could not put oxygen in the separator's tank: " + filled,
                filled.contains("\"ok\":true"));
        String flip = exec("artest block activate 0 " + (cx + 1) + " " + CY + " " + CZ + " true");
        assertTrue("sneak-click failed: " + flip, flip.contains("\"handled\":true"));
        forceTick(cx + 1, 200);
    }

    private void placeSeparator(int cx) throws Exception {
        String resp = exec("artest place 0 " + (cx + 1) + " " + CY + " " + CZ
                + " advancedrocketry:gasSeparator");
        assertTrue("separator place failed: " + resp, resp.contains("\"placed\":true"));
    }

    private void setAir(int cx, int n2, int o2, int co2, int milliK) throws Exception {
        String set = exec("artest vent setair 0 " + cx + " " + CY + " " + CZ
                + " " + n2 + " " + o2 + " " + co2 + " " + milliK);
        assertTrue("setair failed: " + set, set.contains("\"ok\":true"));
    }

    private void injectEnergyAt(int x, int amount) throws Exception {
        String resp = exec("artest energy inject 0 " + x + " " + CY + " " + CZ + " " + amount);
        assertTrue("energy inject failed at " + x + ": " + resp, resp.contains("\"ok\":true"));
    }

    /** Force-ticks a machine and CHECKS it was there: a missing tile reads exactly like inaction. */
    private void forceTick(int x, int ticks) throws Exception {
        String resp = exec("artest tile force-tick 0 " + x + " " + CY + " " + CZ + " " + ticks);
        assertTrue("force-tick found no tile at x=" + x + " — nothing below is a statement about a "
                + "machine that was not there: " + resp, !resp.contains("\"error\""));
    }

    private String ventInfo(int cx) throws Exception {
        return exec("artest vent info 0 " + cx + " " + CY + " " + CZ);
    }

    private int configInt(String key) throws Exception {
        String resp = exec("artest config get " + key);
        assertTrue("config get " + key + " failed: " + resp, resp.contains("\"ok\":true"));
        return (int) extract(resp, CONFIG_VALUE);
    }

    private static long extract(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Long.parseLong(m.group(1));
    }
}
