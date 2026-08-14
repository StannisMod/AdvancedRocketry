package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Life support as a placed machine rather than as arithmetic.
 *
 * <p>{@code AirStateTest} already pins the gas maths in isolation. What it cannot see is whether a
 * zone exists at all, whether the vent stays the authority over it, and whether a recirculator
 * standing in a room actually moves that room's air and produces the carbon it removed. Those are
 * the three things here.</p>
 */
public class LifeSupportZoneTest extends AbstractSharedServerTest {

    private static final Pattern AIR_O2 = Pattern.compile("\"airO2\":(-?\\d+)");
    private static final Pattern AIR_CO2 = Pattern.compile("\"airCO2\":(-?\\d+)");
    private static final Pattern AIR_PRESSURE = Pattern.compile("\"airPressure\":(-?\\d+)");

    private static final int CY_BASE = 64;
    private static final int CZ_BASE = 2100;
    private static final int CX_FRESH = 2000;
    private static final int CX_UNPOWERED = 2200;
    private static final int CX_RECIRC = 2400;

    /** A maintained zone starts as sea-level air, and reports the pressure the mod has always
     *  reported for a pressurised room. This is the probe's own grounding: if it lied, the two
     *  tests below would be measuring nothing. */
    @Test
    public void aSealedPoweredRoomHoldsBreathableAir() throws Exception {
        buildSealableRoom(CX_FRESH);
        placeVent(CX_FRESH);
        injectEnergy(CX_FRESH, 1_000_000);
        injectOxygen(CX_FRESH, 16000);
        forceTickAndReseal(CX_FRESH);

        String info = ventInfo(CX_FRESH);
        assertEquals("a fresh maintained zone must hold sea-level oxygen: " + info,
                210_000, extract(info, AIR_O2));
        assertEquals("and no carbon dioxide at all: " + info, 0, extract(info, AIR_CO2));
        assertEquals("its pressure must read as one atmosphere: " + info,
                100, extract(info, AIR_PRESSURE));
    }

    /** INV-ATM-19. Without power the vent never seals, so there is no zone — and therefore nothing
     *  for life support to act on. The probe reports -1 for "no zone", which is the distinction
     *  that matters: an unmaintained room is not a room full of stale air, it is a room the system
     *  has no opinion about. */
    @Test
    public void anUnpoweredRoomHasNoZoneForLifeSupportToTouch() throws Exception {
        buildSealableRoom(CX_UNPOWERED);
        placeVent(CX_UNPOWERED);
        injectOxygen(CX_UNPOWERED, 16000);
        // Deliberately no energy.
        forceTickAndReseal(CX_UNPOWERED);

        String info = ventInfo(CX_UNPOWERED);
        assertEquals("an unpowered vent must not be maintaining a zone: " + info,
                -1, extract(info, AIR_O2));
    }

    /** MECH-ATM-20 end to end: a powered recirculator standing in a stale room turns that room's
     *  CO2 back into oxygen and leaves solid carbon in its own slot. */
    @Test
    public void aRecirculatorClearsItsRoomsCarbonDioxideAndDropsDust() throws Exception {
        buildSealableRoom(CX_RECIRC);
        placeVent(CX_RECIRC);
        injectEnergy(CX_RECIRC, 1_000_000);
        injectOxygen(CX_RECIRC, 16000);
        forceTickAndReseal(CX_RECIRC);

        // Make the room stale: most of its oxygen already breathed into CO2.
        String set = exec("artest vent setair 0 " + CX_RECIRC + " " + CY_BASE + " " + CZ_BASE
                + " 790000 60000 150000");
        assertTrue("setair failed: " + set, set.contains("\"ok\":true"));

        String before = ventInfo(CX_RECIRC);
        assertEquals("premise: the room is stale before the machine runs: " + before,
                150_000, extract(before, AIR_CO2));

        placeRecirculator(CX_RECIRC);
        injectEnergy2(CX_RECIRC, 1_000_000);
        // World time advances one second per 20 ticks and the machine acts on that cadence.
        exec("artest tile force-tick 0 " + (CX_RECIRC + 1) + " " + CY_BASE + " " + CZ_BASE + " 400");

        String after = ventInfo(CX_RECIRC);
        int co2After = extract(after, AIR_CO2);
        int o2After = extract(after, AIR_O2);
        assertTrue("the recirculator must consume its room's CO2 (before=150000 after="
                + co2After + "): " + after, co2After < 150_000);
        assertTrue("and the oxygen must come back (before=60000 after=" + o2After + "): " + after,
                o2After > 60_000);
        assertEquals("regeneration must not change the room's pressure: " + after,
                100, extract(after, AIR_PRESSURE));

        // Forge lowercases registry paths, so the id Java passes as "carbonDust" is stored — and
        // reported — as "carbondust".
        String slot = exec("artest hatch read 0 " + (CX_RECIRC + 1) + " " + CY_BASE + " " + CZ_BASE);
        assertTrue("the carbon it removed from the air must appear as dust: " + slot,
                slot.contains("advancedrocketry:carbondust"));
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private void buildSealableRoom(int cx) throws Exception {
        int by = CY_BASE, bz = CZ_BASE;
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
    }

    private void placeVent(int cx) throws Exception {
        String resp = exec("artest place 0 " + cx + " " + CY_BASE + " " + CZ_BASE
                + " advancedrocketry:oxygenVent");
        assertTrue("vent place failed: " + resp, resp.contains("\"placed\":true"));
    }

    /** Placed one block along, inside the same sealed volume as the vent. */
    private void placeRecirculator(int cx) throws Exception {
        String resp = exec("artest place 0 " + (cx + 1) + " " + CY_BASE + " " + CZ_BASE
                + " advancedrocketry:airRecirculator");
        assertTrue("recirculator place failed: " + resp, resp.contains("\"placed\":true"));
    }

    private void injectEnergy(int cx, int amount) throws Exception {
        String resp = exec("artest energy inject 0 " + cx + " " + CY_BASE + " " + CZ_BASE
                + " " + amount);
        assertTrue("energy inject failed: " + resp, resp.contains("\"ok\":true"));
    }

    private void injectEnergy2(int cx, int amount) throws Exception {
        String resp = exec("artest energy inject 0 " + (cx + 1) + " " + CY_BASE + " " + CZ_BASE
                + " " + amount);
        assertTrue("recirculator energy inject failed: " + resp, resp.contains("\"ok\":true"));
    }

    private void injectOxygen(int cx, int amount) throws Exception {
        String resp = exec("artest fluid inject 0 " + cx + " " + CY_BASE + " " + CZ_BASE
                + " oxygen " + amount);
        assertTrue("oxygen inject failed: " + resp, resp.contains("\"ok\":true"));
    }

    private void forceTickAndReseal(int cx) throws Exception {
        exec("artest tile force-tick 0 " + cx + " " + CY_BASE + " " + CZ_BASE + " 1");
        exec("artest vent reseal 0 " + cx + " " + CY_BASE + " " + CZ_BASE);
        exec("artest tile force-tick 0 " + cx + " " + CY_BASE + " " + CZ_BASE + " 5");
    }

    private String ventInfo(int cx) throws Exception {
        return exec("artest vent info 0 " + cx + " " + CY_BASE + " " + CZ_BASE);
    }

    private static int extract(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Integer.parseInt(m.group(1));
    }
}
