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
 * zone exists at all, whether the vent stays the authority over it, and whether a machine standing
 * in a room actually finds that room, moves its air, and stops where it is told to. Those are the
 * things here — including the combiner's governor, which is a refusal and so can only be told from
 * a broken machine by watching it act first and then decline.</p>
 */
public class LifeSupportZoneTest extends AbstractSharedServerTest {

    private static final Pattern AIR_O2 = Pattern.compile("\"airO2\":(-?\\d+)");
    private static final Pattern AIR_CO2 = Pattern.compile("\"airCO2\":(-?\\d+)");
    private static final Pattern AIR_PRESSURE = Pattern.compile("\"airPressure\":(-?\\d+)");
    private static final Pattern TANK_AMOUNT = Pattern.compile("\"tankAmount\":(-?\\d+)");
    private static final Pattern CONFIG_VALUE = Pattern.compile("\"value\":(-?\\d+)");
    private static final Pattern OBSTRUCTION = Pattern.compile("\"obstruction\":(-?\\d+)");
    private static final Pattern HELD_COUNT = Pattern.compile("\"heldCount\":(-?\\d+)");
    private static final Pattern EJECTED = Pattern.compile("\"ejected\":(-?\\d+)");

    private static final int CY_BASE = 64;
    private static final int CZ_BASE = 2100;
    private static final int CX_FRESH = 2000;
    private static final int CX_UNPOWERED = 2200;
    private static final int CX_RECIRC = 2400;
    private static final int CX_SEPARATOR = 2600;
    private static final int CX_COMBINE = 2800;
    private static final int CX_GOVERNOR = 3000;
    private static final int CX_JETTISON = 3200;
    private static final int CX_JETTISON_BLOCKED = 3400;

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
        // The gases are only half the story: what damages the crew is the atmosphere the zone
        // PUBLISHES, and a room that has been regenerated must publish a breathable one.
        assertTrue("a regenerated room must read as breathable, not merely contain oxygen: " + after,
                after.contains("\"blobAtmosphere\":\"PressurizedAir\""));

        // Forge lowercases registry paths, so the id Java passes as "carbonDust" is stored — and
        // reported — as "carbondust".
        String slot = exec("artest hatch read 0 " + (CX_RECIRC + 1) + " " + CY_BASE + " " + CZ_BASE);
        assertTrue("the carbon it removed from the air must appear as dust: " + slot,
                slot.contains("advancedrocketry:carbondust"));
    }

    /** MECH-ATM-21 split: a separator standing in a stale room draws its CO2 into its own tank.
     *  The unit tests prove the arithmetic; this proves the machine finds the room at all, which
     *  is precisely what the recirculator got wrong twice. */
    @Test
    public void aSeparatorDrawsItsRoomsCarbonDioxideIntoItsTank() throws Exception {
        buildSealableRoom(CX_SEPARATOR);
        placeVent(CX_SEPARATOR);
        injectEnergy(CX_SEPARATOR, 1_000_000);
        injectOxygen(CX_SEPARATOR, 16000);
        forceTickAndReseal(CX_SEPARATOR);

        String set = exec("artest vent setair 0 " + CX_SEPARATOR + " " + CY_BASE + " " + CZ_BASE
                + " 790000 60000 150000");
        assertTrue("setair failed: " + set, set.contains("\"ok\":true"));

        placeSeparator(CX_SEPARATOR);
        injectEnergyAt(CX_SEPARATOR + 1, 1_000_000);
        exec("artest tile force-tick 0 " + (CX_SEPARATOR + 1) + " " + CY_BASE + " " + CZ_BASE + " 200");

        String after = ventInfo(CX_SEPARATOR);
        int co2After = extract(after, AIR_CO2);
        assertTrue("the separator must pull CO2 out of the room (before=150000 after="
                + co2After + "): " + after, co2After < 150_000);
        assertEquals("and must not touch the oxygen the crew are breathing: " + after,
                60_000, extract(after, AIR_O2));

        String tank = exec("artest fluid stored 0 " + (CX_SEPARATOR + 1) + " " + CY_BASE + " " + CZ_BASE);
        assertTrue("the gas it removed must be in its tank as carbon dioxide: " + tank,
                tank.contains("carbon_dioxide"));
    }

    /** MECH-ATM-21 combine: the other direction. A separator flipped to combine puts the gas in
     *  its tank back into the room — which is what makes a stripped cabin habitable again, and is
     *  the half of the machine no test had ever driven in a world. */
    @Test
    public void aSeparatorInCombineModeGivesItsOxygenBackToTheRoom() throws Exception {
        buildSealableRoom(CX_COMBINE);
        placeVent(CX_COMBINE);
        injectEnergy(CX_COMBINE, 1_000_000);
        injectOxygen(CX_COMBINE, 16000);
        forceTickAndReseal(CX_COMBINE);

        // A room whose oxygen has been stripped out: still pressurised by its nitrogen, but not
        // breathable. This is the state a split-mode separator leaves behind.
        String set = exec("artest vent setair 0 " + CX_COMBINE + " " + CY_BASE + " " + CZ_BASE
                + " 790000 60000 0");
        assertTrue("setair failed: " + set, set.contains("\"ok\":true"));
        String before = ventInfo(CX_COMBINE);
        assertTrue("premise: the room must start un-breathable: " + before,
                before.contains("\"blobAtmosphere\":\"lowO2\""));

        placeSeparator(CX_COMBINE);
        injectEnergyAt(CX_COMBINE + 1, 1_000_000);
        String filled = exec("artest fluid inject 0 " + (CX_COMBINE + 1) + " " + CY_BASE + " "
                + CZ_BASE + " oxygen 8000");
        assertTrue("could not put oxygen in the separator's tank: " + filled,
                filled.contains("\"ok\":true"));

        flipMode(CX_COMBINE + 1);
        String mode = separatorInfo(CX_COMBINE + 1);
        assertTrue("premise: the sneak-click must have put it in combine mode: " + mode,
                mode.contains("\"combining\":true"));
        assertTrue("premise: it must have found the room it stands in: " + mode,
                mode.contains("\"hasServedCell\":true"));

        exec("artest tile force-tick 0 " + (CX_COMBINE + 1) + " " + CY_BASE + " " + CZ_BASE + " 200");

        String after = ventInfo(CX_COMBINE);
        int o2After = extract(after, AIR_O2);
        assertTrue("the separator must push its oxygen into the room (before=60000 after="
                + o2After + "): " + after, o2After > 60_000);
        assertTrue("and the room must become breathable again: " + after,
                after.contains("\"blobAtmosphere\":\"PressurizedAir\""));

        String tank = separatorInfo(CX_COMBINE + 1);
        assertTrue("the oxygen it gave the room must have left its tank: " + tank,
                extract(tank, TANK_AMOUNT) < 8000);
    }

    /** MECH-ATM-21 governor — the reason the combiner exists. Oxygen is admitted only up to the
     *  configured ceiling, so a cabin cannot be enriched into a fire hazard however much gas is
     *  piped at it. Pinned as "climbs, then stops exactly at the ceiling with gas to spare": a
     *  machine that simply did nothing would satisfy "never exceeds" without governing anything. */
    @Test
    public void theCombinerRefusesToPushOxygenPastTheSafeCeiling() throws Exception {
        int ceiling = configInt("lifeSupportMaxPartialO2");
        int start = ceiling - 40_000;

        buildSealableRoom(CX_GOVERNOR);
        placeVent(CX_GOVERNOR);
        injectEnergy(CX_GOVERNOR, 1_000_000);
        injectOxygen(CX_GOVERNOR, 16000);
        forceTickAndReseal(CX_GOVERNOR);

        String set = exec("artest vent setair 0 " + CX_GOVERNOR + " " + CY_BASE + " " + CZ_BASE
                + " 790000 " + start + " 0");
        assertTrue("setair failed: " + set, set.contains("\"ok\":true"));

        placeSeparator(CX_GOVERNOR);
        injectEnergyAt(CX_GOVERNOR + 1, 1_000_000);
        String filled = exec("artest fluid inject 0 " + (CX_GOVERNOR + 1) + " " + CY_BASE + " "
                + CZ_BASE + " oxygen 8000");
        assertTrue("could not put oxygen in the separator's tank: " + filled,
                filled.contains("\"ok\":true"));

        flipMode(CX_GOVERNOR + 1);
        // Far longer than the two operations the gap needs: the machine must stop by decision,
        // not by running out of time.
        forceTick(CX_GOVERNOR + 1, 400);

        String after = ventInfo(CX_GOVERNOR);
        int o2After = extract(after, AIR_O2);
        assertEquals("oxygen must stop exactly at the ceiling — climbing from " + start
                + " and no further than " + ceiling + ": " + after, ceiling, o2After);
        assertTrue("and the room must stay breathable rather than turn oxygen-toxic: " + after,
                after.contains("\"blobAtmosphere\":\"PressurizedAir\""));

        String tank = separatorInfo(CX_GOVERNOR + 1);
        assertTrue("it must have stopped because of the ceiling, not because the tank ran dry: "
                + tank, extract(tank, TANK_AMOUNT) > 0);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    /**
     * The carbon has somewhere to go. A scrubber's output slot backs the machine up when it fills,
     * so a closed air loop is only closed if the dust can leave the ship — this is that exit.
     *
     * <p>The assertion is deliberately about the WORLD and not about the slot: an emptied slot is
     * equally consistent with a port that simply voided its cargo, and "the dust was deleted" is
     * not the contract. {@code ejected} counts loose item entities beside the port.</p>
     */
    @Test
    public void aJettisonPortThrowsItsCargoOverboard() throws Exception {
        clearAirPocket(CX_JETTISON);
        placeJettisonPort(CX_JETTISON);

        String loaded = exec("artest jettison load 0 " + CX_JETTISON + " " + CY_BASE + " " + CZ_BASE
                + " advancedrocketry:carbonDust 1");
        assertTrue("the port must accept a stack: " + loaded, loaded.contains("\"ok\":true"));

        forceTick(CX_JETTISON, 25);

        String info = exec("artest jettison info 0 " + CX_JETTISON + " " + CY_BASE + " " + CZ_BASE);
        assertEquals("a port with a clear exit must report no obstruction: " + info,
                0, extract(info, OBSTRUCTION));
        assertEquals("and its slot must be empty afterwards: " + info, 0, extract(info, HELD_COUNT));
        assertTrue("the dust must exist in the world as a jettisoned item — an empty slot alone is"
                + " what voiding it would also look like: " + info, extract(info, EJECTED) >= 1);
    }

    /**
     * The counter-test, and the one that makes the port safe to build badly: a port whose exit is
     * blocked HOLDS its cargo instead of firing into the wall or quietly voiding it.
     */
    @Test
    public void aBlockedJettisonPortHoldsItsCargo() throws Exception {
        clearAirPocket(CX_JETTISON_BLOCKED);
        placeJettisonPort(CX_JETTISON_BLOCKED);
        // Wall it in on every side, so the outcome does not depend on which way the port was placed.
        exec("artest fill 0 " + (CX_JETTISON_BLOCKED - 1) + " " + (CY_BASE - 1) + " " + (CZ_BASE - 1)
                + " " + (CX_JETTISON_BLOCKED + 1) + " " + (CY_BASE + 1) + " " + (CZ_BASE + 1)
                + " minecraft:stone");
        placeJettisonPort(CX_JETTISON_BLOCKED);

        exec("artest jettison load 0 " + CX_JETTISON_BLOCKED + " " + CY_BASE + " " + CZ_BASE
                + " advancedrocketry:carbonDust 1");
        forceTick(CX_JETTISON_BLOCKED, 25);

        String info = exec("artest jettison info 0 " + CX_JETTISON_BLOCKED + " " + CY_BASE + " "
                + CZ_BASE);
        assertTrue("a walled-in port must report where the obstruction is: " + info,
                extract(info, OBSTRUCTION) > 0);
        assertEquals("and it must still be holding the dust: " + info, 1, extract(info, HELD_COUNT));
        assertEquals("with nothing jettisoned: " + info, 0, extract(info, EJECTED));
    }

    /** Open sky around the port, on a stone floor, so its exit is clear whichever way it faces. */
    private void clearAirPocket(int cx) throws Exception {
        exec("artest fill 0 " + (cx - 4) + " " + (CY_BASE - 1) + " " + (CZ_BASE - 4)
                + " " + (cx + 4) + " " + (CY_BASE + 4) + " " + (CZ_BASE + 4) + " minecraft:air");
        exec("artest fill 0 " + (cx - 4) + " " + (CY_BASE - 2) + " " + (CZ_BASE - 4)
                + " " + (cx + 4) + " " + (CY_BASE - 2) + " " + (CZ_BASE + 4) + " minecraft:stone");
    }

    private void placeJettisonPort(int cx) throws Exception {
        String resp = exec("artest place 0 " + cx + " " + CY_BASE + " " + CZ_BASE
                + " advancedrocketry:jettisonPort");
        assertTrue("jettison port place failed: " + resp, resp.contains("\"placed\":true"));
    }

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
        injectEnergyAt(cx + 1, amount);
    }

    private void injectEnergyAt(int x, int amount) throws Exception {
        String resp = exec("artest energy inject 0 " + x + " " + CY_BASE + " " + CZ_BASE
                + " " + amount);
        assertTrue("energy inject failed at " + x + ": " + resp, resp.contains("\"ok\":true"));
    }

    private void placeSeparator(int cx) throws Exception {
        String resp = exec("artest place 0 " + (cx + 1) + " " + CY_BASE + " " + CZ_BASE
                + " advancedrocketry:gasSeparator");
        assertTrue("separator place failed: " + resp, resp.contains("\"placed\":true"));
    }

    private void injectOxygen(int cx, int amount) throws Exception {
        String resp = exec("artest fluid inject 0 " + cx + " " + CY_BASE + " " + CZ_BASE
                + " oxygen " + amount);
        assertTrue("oxygen inject failed: " + resp, resp.contains("\"ok\":true"));
    }

    /**
     * Force-ticks a machine and CHECKS that it was there to tick.
     * <p>
     * The probe answers {@code {"error":"tile not ITickable","tile":"null"}} when the chunk has gone
     * — and an unchecked force-tick makes that indistinguishable from the machine declining to act,
     * which is exactly the shape several assertions here are testing for. Measured 2026-08-16: a
     * combiner scenario read as "the machine stopped at its ceiling" when in truth nothing had
     * ticked at all.
     */
    private void forceTick(int x, int ticks) throws Exception {
        String resp = exec("artest tile force-tick 0 " + x + " " + CY_BASE + " " + CZ_BASE
                + " " + ticks);
        assertTrue("force-tick found no tile at x=" + x + " — the machine was not there to act, so"
                + " nothing below is a statement about it: " + resp, !resp.contains("\"error\""));
    }

    private void forceTickAndReseal(int cx) throws Exception {
        exec("artest tile force-tick 0 " + cx + " " + CY_BASE + " " + CZ_BASE + " 1");
        exec("artest vent reseal 0 " + cx + " " + CY_BASE + " " + CZ_BASE);
        exec("artest tile force-tick 0 " + cx + " " + CY_BASE + " " + CZ_BASE + " 5");
    }

    private String ventInfo(int cx) throws Exception {
        return exec("artest vent info 0 " + cx + " " + CY_BASE + " " + CZ_BASE);
    }

    private String separatorInfo(int x) throws Exception {
        return exec("artest separator info 0 " + x + " " + CY_BASE + " " + CZ_BASE);
    }

    /** The production toggle: a sneak-right-click on the block, through the block's own
     *  onBlockActivated. Calling toggleMode() on the tile would skip the dispatch that decides
     *  whether a click means "open me" or "flip me", which is the part a player uses. */
    private void flipMode(int x) throws Exception {
        String resp = exec("artest block activate 0 " + x + " " + CY_BASE + " " + CZ_BASE + " true");
        assertTrue("sneak-click failed: " + resp, resp.contains("\"handled\":true"));
    }

    private int configInt(String key) throws Exception {
        String resp = exec("artest config get " + key);
        assertTrue("config get " + key + " failed: " + resp, resp.contains("\"ok\":true"));
        return extract(resp, CONFIG_VALUE);
    }

    private static int extract(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Integer.parseInt(m.group(1));
    }
}
