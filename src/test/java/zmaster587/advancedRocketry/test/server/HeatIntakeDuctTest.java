package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * The air-intake duct: the block that finally connects the part of a ship people live in to the part
 * that throws heat overboard.
 *
 * <p>A chiller alone talks only to coolant. Bolt a duct to its cold face and it breathes a room
 * instead — taking heat out of that room's air, paying electricity, and delivering both into the hot
 * loop where the radiators are. So the two assertions are a pair: the room must actually get colder,
 * and the loop must receive exactly what left the room PLUS the work that moved it. Either one alone
 * would pass on a machine that invented energy or on one that quietly destroyed it.</p>
 *
 * <p>An unpowered chiller is the control, and it is the honest one: it is the same rig in the same
 * place, differing only in the thing under test.</p>
 */
public class HeatIntakeDuctTest extends AbstractSharedServerTest {

    private static final Pattern AIR_TEMP = Pattern.compile("\"airTempMilliK\":(-?\\d+)");
    private static final Pattern AIR_CAPACITY = Pattern.compile("\"airHeatCapacity\":(-?\\d+)");
    private static final Pattern HEAT_STORED = Pattern.compile("\"heatStored\":(-?\\d+)");
    private static final Pattern WORK = Pattern.compile("\"work\":(-?\\d+)");
    private static final Pattern PUMPED_IN = Pattern.compile("\"pumpedIn\":(-?\\d+)");
    private static final Pattern CHARGED = Pattern.compile("\"charged\":(-?\\d+)");
    /** Heat taken out of the room's air on the SAME tick the delivery and the work were measured. */
    private static final Pattern AIR_TAKEN = Pattern.compile("\"airTaken\":(-?\\d+)");

    private static final int CY = 64;
    private static final int CZ = 3040;
    private static final int CX_COOLS = 2000;
    private static final int CX_UNPOWERED = 2200;

    /** Hot enough that a tick of chilling is unmistakable against integer rounding. */
    private static final int HOT_MILLI_K = 400_000;

    /**
     * A powered chiller breathing a room cools it, and its hot loop gains what the room lost plus the
     * work that moved it.
     */
    @Test
    public void aChillerBreathingARoomCoolsItAndHeatsItsLoop() throws Exception {
        buildRoomWithVent(CX_COOLS);
        setAir(CX_COOLS, 790_000, 210_000, 0, HOT_MILLI_K);
        buildChillerWithDuctAndLoop(CX_COOLS);

        String roomBefore = ventInfo(CX_COOLS);
        long tempBefore = extract(roomBefore, AIR_TEMP);
        long capacity = extract(roomBefore, AIR_CAPACITY);
        assertEquals("premise: the room must start hot: " + roomBefore, HOT_MILLI_K, tempBefore);
        assertTrue("premise: and must hold heat to give up: " + roomBefore, capacity > 0);

        injectEnergyAt(CX_COOLS + 3, CY + 1, 1_000_000);
        String cycled = cycleHotLoop(CX_COOLS, 0L);

        String roomAfter = ventInfo(CX_COOLS);
        long tempAfter = extract(roomAfter, AIR_TEMP);
        long gained = extract(cycled, HEAT_STORED);
        long work = extract(cycled, WORK);
        long takenFromAir = extract(cycled, AIR_TAKEN);

        assertTrue("the room must actually get colder — that is the whole point of the duct (before="
                + tempBefore + " after=" + tempAfter + "): " + roomAfter, tempAfter < tempBefore);
        assertTrue("premise: and the hot loop must have received something: " + cycled, gained > 0);
        assertTrue("premise: the chiller must have paid for it, or there is no work term to check: "
                + cycled, work > 0);
        assertTrue("premise: and must have actually taken heat out of the air: " + cycled,
                takenFromAir > 0);
        assertEquals("what the loop received must be reported as arriving from a pump: " + cycled,
                gained, extract(cycled, PUMPED_IN));

        // Conservation across the air/coolant boundary. All three figures come from ONE tick of the
        // hot loop, which is what makes the equality checkable at all: read across probe calls, the
        // room's temperature would also carry whatever the natural ticks in the gap did to it, and
        // this loop's energy would not — measured 2026-08-17, the room read 18000 lost against 6240
        // delivered, and nothing was wrong with the machine.
        assertEquals("the hot loop must receive what left the room PLUS the work (air=" + takenFromAir
                        + " work=" + work + " gained=" + gained + "). A gap means the machine invented "
                        + "energy or quietly destroyed it: " + cycled,
                takenFromAir + work, gained);
    }

    /**
     * The control. The same rig with no electricity in the chiller must leave the room exactly where
     * it was — a duct is a mouth, not a hole, and heat does not walk out of a room on its own.
     */
    @Test
    public void anUnpoweredChillerLeavesTheRoomAlone() throws Exception {
        buildRoomWithVent(CX_UNPOWERED);
        setAir(CX_UNPOWERED, 790_000, 210_000, 0, HOT_MILLI_K);
        buildChillerWithDuctAndLoop(CX_UNPOWERED);

        long tempBefore = extract(ventInfo(CX_UNPOWERED), AIR_TEMP);
        String cycled = cycleHotLoop(CX_UNPOWERED, 0L);
        long tempAfter = extract(ventInfo(CX_UNPOWERED), AIR_TEMP);

        assertEquals("an unpowered chiller must move nothing at all: " + cycled,
                tempBefore, tempAfter);
        assertEquals("and its loop must receive nothing: " + cycled, 0, extract(cycled, HEAT_STORED));

        // And the same rig, powered, must then work — without this the assertions above would also
        // pass on a rig that was never able to cool anything in the first place.
        injectEnergyAt(CX_UNPOWERED + 3, CY + 1, 1_000_000);
        cycleHotLoop(CX_UNPOWERED, 0L);
        assertTrue("the same rig with power must cool the room, or nothing above was measured: "
                        + ventInfo(CX_UNPOWERED),
                extract(ventInfo(CX_UNPOWERED), AIR_TEMP) < tempBefore);
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /**
     * The chiller sits just outside the sealed room, its cold face carrying a duct that reaches
     * INTO the room's air, and its hot face against a short run of coolant pipe.
     */
    private void buildChillerWithDuctAndLoop(int cx) throws Exception {
        // The duct replaces a wall block, so one of its faces is a cell of the room's air.
        place(cx + 2, CY + 1, "advancedrocketry:heatIntakeDuct", null);
        // The chiller stands beyond it, facing away from the room: cold face on the duct, hot face
        // out into the pipe run.
        // meta 5 = EAST on libVulpes' horizontal FACING, so the hot face points along +X at the
        // pipe run and the COLD face lands on the duct. Placed without it the block defaults to
        // NORTH and the machine would be breathing a wall.
        place(cx + 3, CY + 1, "advancedrocketry:heatChiller", "5");
        for (int i = 4; i < 8; i++) {
            place(cx + i, CY + 1, "advancedrocketry:heatPipe", null);
        }
        String solved = exec("artest subnet solve heat 0 1");
        assertTrue("solve failed: " + solved, solved.contains("\"ticksSolved\":1"));
    }

    private String cycleHotLoop(int cx, long charge) throws Exception {
        String cycled = exec("artest heat cycle 0 " + (cx + 4) + " " + (CY + 1) + " " + CZ
                + " " + charge + " 1");
        assertTrue("heat cycle failed: " + cycled, cycled.contains("\"inLoop\":true"));
        assertEquals("premise: the loop must have been charged with exactly what was asked: " + cycled,
                charge, extract(cycled, CHARGED));
        return cycled;
    }

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
        injectEnergyAt(cx, CY, 1_000_000);
        String oxygen = exec("artest fluid inject 0 " + cx + " " + CY + " " + CZ + " oxygen 16000");
        assertTrue("oxygen inject failed: " + oxygen, oxygen.contains("\"ok\":true"));
        exec("artest tile force-tick 0 " + cx + " " + CY + " " + CZ + " 1");
        exec("artest vent reseal 0 " + cx + " " + CY + " " + CZ);
        exec("artest tile force-tick 0 " + cx + " " + CY + " " + CZ + " 5");
    }

    private void place(int x, int y, String block, String meta) throws Exception {
        String resp = exec("artest place 0 " + x + " " + y + " " + CZ + " " + block
                + (meta == null ? "" : " " + meta));
        assertTrue(block + " place failed at " + x + "," + y + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private void setAir(int cx, int n2, int o2, int co2, int milliK) throws Exception {
        String set = exec("artest vent setair 0 " + cx + " " + CY + " " + CZ
                + " " + n2 + " " + o2 + " " + co2 + " " + milliK);
        assertTrue("setair failed: " + set, set.contains("\"ok\":true"));
    }

    private void injectEnergyAt(int x, int y, int amount) throws Exception {
        String resp = exec("artest energy inject 0 " + x + " " + y + " " + CZ + " " + amount);
        assertTrue("energy inject failed at " + x + "," + y + ": " + resp, resp.contains("\"ok\":true"));
    }

    private String ventInfo(int cx) throws Exception {
        return exec("artest vent info 0 " + cx + " " + CY + " " + CZ);
    }

    private static long extract(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Long.parseLong(m.group(1));
    }
}
