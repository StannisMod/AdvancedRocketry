package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * The coolant loop as a physical body: a machine's waste heat goes into the pipes touching it, and
 * how hot they get is decided by how much pipe there is.
 *
 * <p>Both scenarios run the same rig — a life-support plant serving a stale room, with a run of
 * coolant pipe welded to it — and differ only in the LENGTH of that run. That is the point: the
 * second scenario is the first one's discriminator, because "the ship got warm" is also what a loop
 * with no thermal mass at all would report, and only a loop whose capacity is real answers
 * differently when you build more of it.</p>
 */
public class HeatLoopTest extends AbstractSharedServerTest {

    private static final Pattern MEMBERS = Pattern.compile("\"members\":(-?\\d+)");
    private static final Pattern CABLES = Pattern.compile("\"cables\":(-?\\d+)");
    private static final Pattern HEAT_STORED = Pattern.compile("\"heatStored\":(-?\\d+)");
    private static final Pattern HEAT_CAPACITY = Pattern.compile("\"heatCapacity\":(-?\\d+)");
    private static final Pattern TEMPERATURE = Pattern.compile("\"temperatureMilliK\":(-?\\d+)");

    private static final int CY = 64;
    private static final int CZ = 2500;
    /** Three pipes on the short loop, six on the long one — the ratio the second scenario reads. */
    private static final int SHORT_LOOP_PIPES = 3;
    private static final int LONG_LOOP_PIPES = 6;
    private static final int CX_LATE = 800;
    private static final int CX_OFF = 1200;
    private static final int CX_SOLO = 1600;
    private static final int CX_SHORT = 2000;
    private static final int CX_LONG = 2400;
    /** Long enough for the plant to spend a visible amount of power; well inside the room's CO2. */
    private static final int SOLVE_TICKS = 300;

    /**
     * A machine cooled by a loop heats it. Nothing rejects heat yet, so the only place the energy
     * can be is in the pipes — which is exactly what a ship with no radiators should experience.
     */
    @Test
    public void aMachineOnACoolantLoopWarmsIt() throws Exception {
        buildRig(CX_SOLO, SHORT_LOOP_PIPES);

        // One tick to let the loop find itself, taken while the plant still has no power: an
        // unpowered plant does no work, so this baseline is a cold loop and not a slightly warm one.
        solve(1);
        String cold = loopInfo(CX_SOLO + 5);
        assertEquals("premise: the pipes must have formed one loop: " + cold,
                SHORT_LOOP_PIPES, extract(cold, MEMBERS));
        assertEquals("premise: every one of them is transport for the loop: " + cold,
                SHORT_LOOP_PIPES, extract(cold, CABLES));
        assertEquals("premise: a loop whose machine has not run holds nothing: " + cold,
                0, extract(cold, HEAT_STORED));
        long ambient = extract(cold, TEMPERATURE);
        assertTrue("premise: a cold loop still has a temperature — ambient: " + cold, ambient > 0);

        powerPlant(CX_SOLO);
        solve(SOLVE_TICKS);

        String warm = loopInfo(CX_SOLO + 5);
        assertTrue("the plant's waste heat must end up in the loop it touches (stored="
                + extract(warm, HEAT_STORED) + "): " + warm, extract(warm, HEAT_STORED) > 0);
        assertTrue("and the loop must therefore be hotter than it started (ambient=" + ambient
                + " now=" + extract(warm, TEMPERATURE) + "): " + warm,
                extract(warm, TEMPERATURE) > ambient);
    }

    /**
     * The same machine into twice the pipe. The long loop must take at least as much heat as the
     * short one and still be COLDER — which cannot happen unless capacity is a real quantity rather
     * than a decoration on the readout.
     *
     * <p>The heat each loop actually received is asserted as a PREMISE, not read past: a longer
     * loop that simply collected less would also come out colder, and that would say nothing about
     * capacity at all.</p>
     */
    @Test
    public void theSameHeatInALongerLoopIsALowerTemperature() throws Exception {
        buildRig(CX_SHORT, SHORT_LOOP_PIPES);
        buildRig(CX_LONG, LONG_LOOP_PIPES);

        solve(1);
        String coldShort = loopInfo(CX_SHORT + 5);
        String coldLong = loopInfo(CX_LONG + 5);
        long ambient = extract(coldShort, TEMPERATURE);
        assertEquals("premise: the two loops must sit at the same ambient before anything runs: "
                + coldShort + " | " + coldLong, ambient, extract(coldLong, TEMPERATURE));
        assertEquals("premise: the long loop is built to hold exactly twice as much: "
                + coldShort + " | " + coldLong,
                2 * extract(coldShort, HEAT_CAPACITY), extract(coldLong, HEAT_CAPACITY));

        powerPlant(CX_SHORT);
        powerPlant(CX_LONG);
        solve(SOLVE_TICKS);

        String warmShort = loopInfo(CX_SHORT + 5);
        String warmLong = loopInfo(CX_LONG + 5);
        long storedShort = extract(warmShort, HEAT_STORED);
        long storedLong = extract(warmLong, HEAT_STORED);
        assertTrue("premise: both loops must have picked heat up at all (short=" + storedShort
                + " long=" + storedLong + "): " + warmShort + " | " + warmLong,
                storedShort > 0 && storedLong > 0);
        assertTrue("premise: the long loop must not be colder merely because it was given less "
                + "heat (short=" + storedShort + " long=" + storedLong + "): "
                + warmShort + " | " + warmLong, storedLong >= storedShort);

        long riseShort = extract(warmShort, TEMPERATURE) - ambient;
        long riseLong = extract(warmLong, TEMPERATURE) - ambient;
        assertTrue("a loop with twice the thermal mass must warm markedly less on the same heat "
                + "(short rose " + riseShort + " milliK, long rose " + riseLong + "): "
                + warmShort + " | " + warmLong, riseLong < riseShort);
    }

    /**
     * The mechanic's flag, off. Every block is still there and the plant still works — what must
     * stop is the heat: nothing stored, and the loop sitting at ambient. An assertion that the loop
     * reads zero would pass on a rig that simply never ran, so the same rig is driven again with
     * the flag back on and required to warm up.
     */
    @Test
    public void withTheThermalSystemOffNothingHeats() throws Exception {
        buildRig(CX_OFF, SHORT_LOOP_PIPES);
        solve(1);
        long ambient = extract(loopInfo(CX_OFF + 5), TEMPERATURE);

        setConfig("shipHeat", "false");
        try {
            powerPlant(CX_OFF);
            solve(SOLVE_TICKS);

            String off = loopInfo(CX_OFF + 5);
            assertEquals("with the thermal system off a loop must store no heat: " + off,
                    0, extract(off, HEAT_STORED));
            assertEquals("and must report no capacity to store it in: " + off,
                    0, extract(off, HEAT_CAPACITY));
            assertEquals("and must sit at ambient: " + off, ambient, extract(off, TEMPERATURE));
        } finally {
            setConfig("shipHeat", "true");
        }

        // The control: the very same rig, flag on. Without this the assertions above would also
        // pass on a rig that was never driven, or on a plant that had run out of power.
        solve(SOLVE_TICKS);
        String on = loopInfo(CX_OFF + 5);
        assertTrue("the same rig with the flag back on must heat, or the assertions above measured "
                + "nothing: " + on, extract(on, HEAT_STORED) > 0);
        assertTrue("and must be above ambient (" + ambient + "): " + on,
                extract(on, TEMPERATURE) > ambient);
    }

    /**
     * A machine built AFTER the loop is still cooled by it.
     *
     * <p>This is the contract the whole caching design has to earn. A machine is not a network
     * node, so placing one against a finished pipe run marks nothing dirty on its own — a loop that
     * worked out its neighbours once and never again would go on believing it is cooling nothing.
     * The loop block's own neighbour notification is what closes it, and this is what would fail if
     * that were removed.</p>
     */
    @Test
    public void aMachineBuiltAfterTheLoopIsStillPickedUp() throws Exception {
        buildStaleRoom(CX_LATE);
        placeDuct(CX_LATE + 1);
        placeDuct(CX_LATE + 2);
        placeDuct(CX_LATE + 3);
        for (int i = 0; i < SHORT_LOOP_PIPES; i++) {
            placePipe(CX_LATE + 5 + i);
        }

        // The loop settles with nothing beside it, and works its neighbours out while that is true.
        solve(1);
        String alone = loopInfo(CX_LATE + 5);
        assertEquals("premise: the loop must exist before the machine does: " + alone,
                SHORT_LOOP_PIPES, extract(alone, MEMBERS));
        long ambient = extract(alone, TEMPERATURE);

        placePlant(CX_LATE + 4);
        powerPlant(CX_LATE);
        solve(SOLVE_TICKS);

        String after = loopInfo(CX_LATE + 5);
        assertTrue("a machine placed against a finished loop must be found by it (stored="
                + extract(after, HEAT_STORED) + "): " + after, extract(after, HEAT_STORED) > 0);
        assertTrue("and must warm it (ambient=" + ambient + " now=" + extract(after, TEMPERATURE)
                + "): " + after, extract(after, TEMPERATURE) > ambient);
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /**
     * A sealed stale room with a vent, ducts out to a life-support plant, and a run of coolant pipe
     * welded to the plant. The plant is the heat source because it is the machine this tier already
     * has: it spends real power doing real work, and a share of what it spends comes back as heat.
     */
    private void buildRig(int cx, int pipes) throws Exception {
        buildStaleRoom(cx);
        placeDuct(cx + 1);
        placeDuct(cx + 2);
        placeDuct(cx + 3);
        placePlant(cx + 4);
        for (int i = 0; i < pipes; i++) {
            placePipe(cx + 5 + i);
        }
    }

    /** Powering the plant is what starts the heat, so it is deliberately separate from building. */
    private void powerPlant(int cx) throws Exception {
        injectEnergyAt(cx + 4, 1_000_000);
    }

    /**
     * Every network, once per tick, the way the game does it. Solving one domain to completion and
     * then the other would not reproduce this rig at all: the plant holds only a moment's waste
     * heat and sheds the rest to the room, so a loop that turns up 300 ticks late finds almost
     * nothing.
     */
    private void solve(int ticks) throws Exception {
        String solved = exec("artest subnet solve all 0 " + ticks);
        assertTrue("solve failed: " + solved, solved.contains("\"ticksSolved\":" + ticks));
    }

    private void buildStaleRoom(int cx) throws Exception {
        exec("artest fill 0 " + (cx - 2) + " " + (CY - 1) + " " + (CZ - 2)
                + " " + (cx + 2) + " " + CY + " " + (CZ + 2) + " minecraft:stone");
        for (int yy = CY + 1; yy <= CY + 2; yy++) {
            exec("artest fill 0 " + (cx - 2) + " " + yy + " " + (CZ - 2)
                    + " " + (cx + 2) + " " + yy + " " + (CZ + 2) + " minecraft:stone");
            exec("artest fill 0 " + (cx - 1) + " " + yy + " " + (CZ - 1)
                    + " " + (cx + 1) + " " + yy + " " + (CZ + 1) + " minecraft:air");
        }
        exec("artest fill 0 " + (cx - 2) + " " + (CY + 3) + " " + (CZ - 2)
                + " " + (cx + 2) + " " + (CY + 3) + " " + (CZ + 2) + " minecraft:stone");

        String vent = exec("artest place 0 " + cx + " " + CY + " " + CZ + " advancedrocketry:oxygenVent");
        assertTrue("vent place failed: " + vent, vent.contains("\"placed\":true"));
        injectEnergyAt(cx, 1_000_000);
        String oxygen = exec("artest fluid inject 0 " + cx + " " + CY + " " + CZ + " oxygen 16000");
        assertTrue("oxygen inject failed: " + oxygen, oxygen.contains("\"ok\":true"));

        exec("artest tile force-tick 0 " + cx + " " + CY + " " + CZ + " 1");
        exec("artest vent reseal 0 " + cx + " " + CY + " " + CZ);
        exec("artest tile force-tick 0 " + cx + " " + CY + " " + CZ + " 5");

        String set = exec("artest vent setair 0 " + cx + " " + CY + " " + CZ + " 790000 60000 150000");
        assertTrue("setair failed: " + set, set.contains("\"ok\":true"));
    }

    private void placeDuct(int x) throws Exception {
        String resp = exec("artest place 0 " + x + " " + CY + " " + CZ + " advancedrocketry:ventilationDuct");
        assertTrue("duct place failed at " + x + ": " + resp, resp.contains("\"placed\":true"));
    }

    private void placePlant(int x) throws Exception {
        String resp = exec("artest place 0 " + x + " " + CY + " " + CZ + " advancedrocketry:lifeSupportPlant");
        assertTrue("plant place failed at " + x + ": " + resp, resp.contains("\"placed\":true"));
    }

    private void placePipe(int x) throws Exception {
        String resp = exec("artest place 0 " + x + " " + CY + " " + CZ + " advancedrocketry:heatPipe");
        assertTrue("pipe place failed at " + x + ": " + resp, resp.contains("\"placed\":true"));
    }

    private void injectEnergyAt(int x, int amount) throws Exception {
        String resp = exec("artest energy inject 0 " + x + " " + CY + " " + CZ + " " + amount);
        assertTrue("energy inject failed at " + x + ": " + resp, resp.contains("\"ok\":true"));
    }

    private void setConfig(String key, String value) throws Exception {
        String resp = exec("artest config set " + key + " " + value);
        assertTrue("config set " + key + "=" + value + " failed: " + resp, resp.contains("\"ok\":true"));
    }

    private String loopInfo(int x) throws Exception {
        return exec("artest subnet info heat 0 " + x + " " + CY + " " + CZ);
    }

    private static long extract(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Long.parseLong(m.group(1));
    }
}
