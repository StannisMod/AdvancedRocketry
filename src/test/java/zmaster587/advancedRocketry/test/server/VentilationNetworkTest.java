package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Tier 4: a central plant regenerating a room it does not stand in, through ducts.
 *
 * <p>This is also the subsystem-network primitive's first test as a PRIMITIVE. Until ventilation
 * existed the solver was exercised only through the shield domain, so "domains do not merge" was an
 * assumption about code nobody had run twice. The second scenario here is that assumption made
 * falsifiable: it swaps one duct for a shield cable and requires the air to stop moving.</p>
 */
public class VentilationNetworkTest extends AbstractSharedServerTest {

    private static final Pattern AIR_O2 = Pattern.compile("\"airO2\":(-?\\d+)");
    private static final Pattern AIR_CO2 = Pattern.compile("\"airCO2\":(-?\\d+)");
    private static final Pattern SOURCES = Pattern.compile("\"sources\":(-?\\d+)");
    private static final Pattern SINKS = Pattern.compile("\"sinks\":(-?\\d+)");
    private static final Pattern CABLES = Pattern.compile("\"cables\":(-?\\d+)");

    private static final int CY = 64;
    private static final int CZ = 2300;
    private static final int CX_PLANT = 2000;
    private static final int CX_ISOLATION = 2400;
    private static final int CX_PRIORITY = 2800;

    private static final Pattern CONFIG_VALUE = Pattern.compile("\"value\":(-?\\d+)");

    /** Regeneration arrives from three blocks away, over ducts the plant never has to know about. */
    @Test
    public void aCentralPlantRegeneratesARoomItDoesNotStandIn() throws Exception {
        buildStaleRoom(CX_PLANT);

        // Duct run leaving the vent, then the plant at the far end: the plant touches no zone.
        placeDuct(CX_PLANT + 1);
        placeDuct(CX_PLANT + 2);
        placeDuct(CX_PLANT + 3);
        placePlant(CX_PLANT + 4);
        injectEnergyAt(CX_PLANT + 4, 1_000_000);

        String net = subnetInfo(CX_PLANT + 2);
        assertEquals("premise: the vent must have joined the ventilation network as its zone's sink: "
                + net, 1, extract(net, SINKS));
        assertEquals("premise: the plant must be its source: " + net, 1, extract(net, SOURCES));
        assertEquals("premise: three ducts between them: " + net, 3, extract(net, CABLES));

        // Drive the network's own tick. Waiting on wall-clock does NOT work here: a probe runs on
        // the server thread and holds the tick loop while it waits, so 300 ticks of waiting bought
        // four solves. Long enough for a whole dust: the DUCT is the bottleneck by design (6000 a
        // tick against the plant's 12000), so carbon accrues at the rate the pipe allows.
        String solved = exec("artest subnet solve lifesupport 0 300");
        assertTrue("solve failed: " + solved, solved.contains("\"ticksSolved\":300"));

        String after = ventInfo(CX_PLANT);
        int co2 = extract(after, AIR_CO2);
        int o2 = extract(after, AIR_O2);
        assertTrue("the plant must clear the room's CO2 through the ducts (before=150000 after="
                + co2 + "): " + after, co2 < 150_000);
        assertTrue("and the oxygen must come back (before=60000 after=" + o2 + "): " + after,
                o2 > 60_000);

        String slot = exec("artest hatch read 0 " + (CX_PLANT + 4) + " " + CY + " " + CZ);
        assertTrue("the carbon it took out of that room must appear in the PLANT's slot, not the "
                + "room's: " + slot + " | air=" + after + " | network=" + subnetInfo(CX_PLANT + 2),
                slot.contains("advancedrocketry:carbondust"));
    }

    /**
     * INV-NET-01, made falsifiable. The same layout with the middle duct replaced by a shield cable:
     * the two subsystems are laid through one another and must not conduct for each other. The test
     * above is this one's positive control — without it, "no air moved" would also be what a broken
     * rig looks like.
     */
    @Test
    public void aShieldCableIsNotADuctAndCarriesNoAir() throws Exception {
        buildStaleRoom(CX_ISOLATION);

        placeDuct(CX_ISOLATION + 1);
        String cable = exec("artest place 0 " + (CX_ISOLATION + 2) + " " + CY + " " + CZ
                + " affs:shield_cable");
        assertTrue("shield cable place failed: " + cable, cable.contains("\"placed\":true"));
        placeDuct(CX_ISOLATION + 3);
        placePlant(CX_ISOLATION + 4);
        injectEnergyAt(CX_ISOLATION + 4, 1_000_000);

        String net = subnetInfo(CX_ISOLATION + 1);
        assertEquals("the vent's ventilation network must end at the shield cable, with no source "
                + "on its side: " + net, 0, extract(net, SOURCES));

        exec("artest subnet solve lifesupport 0 300");

        String after = ventInfo(CX_ISOLATION);
        assertEquals("no regeneration may cross a cable belonging to another subsystem: " + after,
                150_000, extract(after, AIR_CO2));
        assertEquals("and the oxygen must be untouched: " + after, 60_000, extract(after, AIR_O2));
    }

    /**
     * The ratified priority mechanic (maintainer, 2026-08-15: assignment through the vent's own
     * screen, every zone equal by default). Two rooms on one plant, and deliberately less supply
     * than either of them alone could absorb: under a real deficit the high-priority room must be
     * served and the normal one must not, rather than both getting half.
     */
    @Test
    public void underADeficitTheHigherPriorityZoneIsServedFirst() throws Exception {
        int roomA = CX_PRIORITY;
        int roomB = CX_PRIORITY + 8;
        String plantRateBefore = configValue("lifeSupportPlantRate");
        try {
            buildStaleRoom(roomA);
            buildStaleRoom(roomB);

            // Duct the two vents together UNDER the floor, so the run never touches either sealed
            // volume, with the plant in the middle of it.
            for (int x = roomA; x <= roomB; x++) {
                if (x == roomA + 4) {
                    placePlantAt(x, CY - 1);
                } else {
                    placeDuctAt(x, CY - 1);
                }
            }
            injectEnergyAt(roomA + 4, CY - 1, 1_000_000);

            // Less than one room can take: 3000 a tick against a duct that would pass 6000.
            String cfg = exec("artest config set lifeSupportPlantRate 60000");
            assertTrue("config set failed: " + cfg, cfg.contains("\"ok\":true"));

            String high = exec("artest vent priority 0 " + roomA + " " + CY + " " + CZ + " 1");
            assertTrue("priority set failed: " + high, high.contains("\"priority\":1"));

            // Measure from a snapshot taken HERE, not from the value setair wrote: the server ticks
            // between commands and solves the network as it goes, so anything asserted against the
            // authored figure is really asserting how long the setup took.
            int baseA = extract(ventInfo(roomA), AIR_CO2);
            int baseB = extract(ventInfo(roomB), AIR_CO2);

            exec("artest subnet solve lifesupport 0 300");

            String a = ventInfo(roomA);
            String b = ventInfo(roomB);
            assertTrue("the prioritised room must be served (before=" + baseA + " after="
                    + extract(a, AIR_CO2) + "): " + a, extract(a, AIR_CO2) < baseA);
            assertEquals("and under a deficit the normal-priority room must get nothing, not a "
                    + "share: " + b, baseB, extract(b, AIR_CO2));
        } finally {
            exec("artest config set lifeSupportPlantRate " + plantRateBefore);
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────

    /** A sealed, maintained room whose air has been breathed down. */
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
        placeDuctAt(x, CY);
    }

    private void placeDuctAt(int x, int y) throws Exception {
        String resp = exec("artest place 0 " + x + " " + y + " " + CZ + " advancedrocketry:ventilationDuct");
        assertTrue("duct place failed at " + x + "," + y + ": " + resp, resp.contains("\"placed\":true"));
    }

    private void placePlant(int x) throws Exception {
        placePlantAt(x, CY);
    }

    private void placePlantAt(int x, int y) throws Exception {
        String resp = exec("artest place 0 " + x + " " + y + " " + CZ + " advancedrocketry:lifeSupportPlant");
        assertTrue("plant place failed at " + x + "," + y + ": " + resp, resp.contains("\"placed\":true"));
    }

    private void injectEnergyAt(int x, int amount) throws Exception {
        injectEnergyAt(x, CY, amount);
    }

    private void injectEnergyAt(int x, int y, int amount) throws Exception {
        String resp = exec("artest energy inject 0 " + x + " " + y + " " + CZ + " " + amount);
        assertTrue("energy inject failed at " + x + "," + y + ": " + resp, resp.contains("\"ok\":true"));
    }

    private String configValue(String key) throws Exception {
        String resp = exec("artest config get " + key);
        Matcher m = CONFIG_VALUE.matcher(resp);
        assertTrue("config get " + key + " failed: " + resp, m.find());
        return m.group(1);
    }

    private String ventInfo(int cx) throws Exception {
        return exec("artest vent info 0 " + cx + " " + CY + " " + CZ);
    }

    private String subnetInfo(int x) throws Exception {
        return exec("artest subnet info lifesupport 0 " + x + " " + CY + " " + CZ);
    }

    private static int extract(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Integer.parseInt(m.group(1));
    }
}
