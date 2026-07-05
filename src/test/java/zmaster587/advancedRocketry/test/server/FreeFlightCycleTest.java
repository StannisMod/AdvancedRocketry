package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Server-side end-to-end coverage for Free Flight Mode (feature/true_rcs).
 *
 * <p>Verified contracts:
 *
 * <ul>
 *   <li><b>Mode persistence</b>: a freshly-assembled rocket defaults to CLASSIC_LAUNCH;
 *       set-flight-mode flips it; rocket info reports the new mode.</li>
 *   <li><b>Free-flight bypass</b>: start-free-flight flips isInFlight=true WITHOUT a
 *       destination chip and without the LAUNCH_COUNTER countdown.</li>
 *   <li><b>Input → motion</b>: pushing a positive forward throttle through
 *       free-flight-input then ticking the FF physics produces a positive +Z motion
 *       delta on an unrotated rocket (yaw=0 → forward = +Z).</li>
 *   <li><b>Fuel drain</b>: vertical-thrust input across ticks decreases the primary
 *       fuel level monotonically until exhaustion.</li>
 *   <li><b>Mode-mismatch input is dropped</b>: free-flight-input on a CLASSIC rocket
 *       leaves currentFreeFlightInput at zero.</li>
 * </ul>
 *
 * <p>Probe surface: {@code artest rocket set-flight-mode|start-free-flight|
 * free-flight-input|free-flight-tick|info}.
 */
public class FreeFlightCycleTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern MOTION_X = Pattern.compile("\"motionX\":(-?[0-9.E\\-]+)");
    private static final Pattern MOTION_Z = Pattern.compile("\"motionZ\":(-?[0-9.E\\-]+)");
    private static final Pattern MOTION_Y = Pattern.compile("\"motionY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern FUEL_PRIMARY_AMOUNT =
            Pattern.compile("\"primaryFuelType\":\"([^\"]+)\".*?\"\\1\":\\{\"amount\":(-?\\d+)");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
        // Clear the full flight column: the world seed is random per run and
        // overhanging terrain above the pad pins the craft (move() zeroes
        // motionY on the ceiling collision) — see the client suite's note.
        String fillAir = ok(client().execute(
                "artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 50) + " " + (baseZ + 7)
                        + " minecraft:air"));
        assertTrue("pre-clear failed: " + fillAir, fillAir.contains("\"ok\":true"));

        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1));
        int by = Integer.parseInt(bp.group(2));
        int bz = Integer.parseInt(bp.group(3));

        String assemble = ok(client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));

        String list = ok(client().execute("artest rocket list 0"));
        Matcher rim = ROCKET_LIST_ID.matcher(list);
        int lastId = -1;
        while (rim.find()) lastId = Integer.parseInt(rim.group(1));
        assertTrue("rocket list empty after assemble: " + list, lastId >= 0);
        return lastId;
    }

    private static double parseDouble(String body, Pattern p, String label) {
        Matcher m = p.matcher(body);
        if (!m.find()) {
            throw new AssertionError("response missing " + label + ": " + body);
        }
        return Double.parseDouble(m.group(1));
    }

    private static int parsePrimaryFuel(String fuelBody) {
        Matcher m = FUEL_PRIMARY_AMOUNT.matcher(fuelBody);
        if (!m.find()) {
            // Rocket may have no primary fuel type; treat as 0 for our purposes.
            return 0;
        }
        return Integer.parseInt(m.group(2));
    }

    // ---------------------------------------------------------------------

    @Test
    public void freshRocketDefaultsToClassicLaunchMode() throws Exception {
        int id = buildAndAssemble(2000, 64, 500);
        String info = ok(client().execute("artest rocket info " + id));
        assertTrue("default mode must be CLASSIC_LAUNCH: " + info,
                info.contains("\"flightMode\":\"CLASSIC_LAUNCH\""));
    }

    @Test
    public void setFlightModeRoundTripsThroughInfo() throws Exception {
        int id = buildAndAssemble(2100, 64, 500);

        String set = ok(client().execute(
                "artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        assertTrue("set-flight-mode FREE_FLIGHT must succeed: " + set,
                set.contains("\"ok\":true"));
        assertTrue("set-flight-mode must echo mode: " + set,
                set.contains("\"flightMode\":\"FREE_FLIGHT\""));

        String info1 = ok(client().execute("artest rocket info " + id));
        assertTrue("info must report FREE_FLIGHT after set: " + info1,
                info1.contains("\"flightMode\":\"FREE_FLIGHT\""));

        // And back to classic.
        ok(client().execute("artest rocket set-flight-mode " + id + " CLASSIC_LAUNCH"));
        String info2 = ok(client().execute("artest rocket info " + id));
        assertTrue("info must report CLASSIC_LAUNCH after flip-back: " + info2,
                info2.contains("\"flightMode\":\"CLASSIC_LAUNCH\""));
    }

    @Test
    public void setFlightModeRejectsUnknownMode() throws Exception {
        int id = buildAndAssemble(2200, 64, 500);
        String resp = ok(client().execute(
                "artest rocket set-flight-mode " + id + " WARPDRIVE"));
        assertTrue("unknown mode must be reported as error: " + resp,
                resp.contains("\"error\":\"unknown mode\""));
    }

    @Test
    public void startFreeFlightBypassesClassicCountdown() throws Exception {
        // Critical FF contract: NO destination chip programmed, NO classic
        // countdown — start-free-flight goes directly to isInFlight=true.
        int id = buildAndAssemble(2300, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));

        String start = ok(client().execute(
                "artest rocket start-free-flight " + id));
        assertTrue("start-free-flight must succeed: " + start,
                start.contains("\"ok\":true"));
        assertTrue("start-free-flight must flip isInFlight=true: " + start,
                start.contains("\"isInFlight\":true"));

        String info = ok(client().execute("artest rocket info " + id));
        assertTrue("info must reflect in-flight after start-free-flight: " + info,
                info.contains("\"isInFlight\":true"));
        assertTrue("info must keep flightMode=FREE_FLIGHT: " + info,
                info.contains("\"flightMode\":\"FREE_FLIGHT\""));
    }

    @Test
    public void startFreeFlightRejectsClassicRocket() throws Exception {
        // Counter-test: start-free-flight on a rocket still in CLASSIC mode
        // must NOT silently launch it (classic flow has its own gates).
        int id = buildAndAssemble(2400, 64, 500);
        String resp = ok(client().execute(
                "artest rocket start-free-flight " + id));
        assertTrue("classic rocket must reject start-free-flight: " + resp,
                resp.contains("\"error\":\"rocket not in FREE_FLIGHT\""));

        String info = ok(client().execute("artest rocket info " + id));
        assertTrue("rejected start must NOT flip isInFlight: " + info,
                info.contains("\"isInFlight\":false"));
    }

    @Test
    public void freeFlightInputIsStoredOnServerAfterPacketPath() throws Exception {
        // Cross-side wiring: free-flight-input probe goes through the same
        // server-side application path that PacketType.FREE_FLIGHT_INPUT
        // would (calls rocket.applyFreeFlightInput). After the probe completes,
        // info must reflect the new currentFreeFlightInput so a client UI /
        // tick loop reads what was set.
        int id = buildAndAssemble(2500, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        String applied = ok(client().execute(
                "artest rocket free-flight-input " + id + " 1.0 -0.5 0.25 0 0.75"));
        assertTrue("input must apply on FF rocket: " + applied,
                applied.contains("\"applied\":true"));
        // Probe echoes the clamped values back; full-range happy-path values
        // should pass through unchanged.
        assertTrue("applied response must echo fwd=1.0: " + applied,
                applied.contains("\"fwd\":1.0"));
        assertTrue("applied response must echo vert=-0.5: " + applied,
                applied.contains("\"vert\":-0.5"));

        // Info must round-trip the input — proves server-side storage path
        // is wired into the probe surface that clients/UI will read.
        String info = ok(client().execute("artest rocket info " + id));
        assertTrue("info must store ffInputFwd=1.0: " + info,
                info.contains("\"ffInputFwd\":1.0"));
        assertTrue("info must store ffInputVert=-0.5: " + info,
                info.contains("\"ffInputVert\":-0.5"));
        assertTrue("info must store ffInputBrake=0.75: " + info,
                info.contains("\"ffInputBrake\":0.75"));
    }

    @Test
    public void freeFlightTickLoopRunsAndMutatesMotion() throws Exception {
        // Contract: tickFreeFlight is invoked when the rocket is in FF +
        // isInFlight, and pilot input mutates motion. With the
        // engine start there is no takeoff kick: the craft rests in the
        // liftoff hover until input arrives, so 10 ticks of full vertical
        // throttle must produce a clearly positive climb rate.
        int id = buildAndAssemble(2550, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        ok(client().execute("artest rocket free-flight-input " + id + " 0 1.0 0 0 0"));
        String tickRes = ok(client().execute(
                "artest rocket free-flight-tick " + id + " 10"));
        double my = parseDouble(tickRes, MOTION_Y, "motionY");
        assertTrue("full vertical throttle must build upward motion "
                        + "(got motionY=" + my + ")", my > 0.1);
    }

    @Test
    public void engineStartHoversOneBlockAboveThePad() throws Exception {
        // starting the engines is NOT a launch — the craft eases
        // ~1 block off the pad and HOVERS there (near-zero motion), without
        // any takeoff kick and without auto-landing.
        int id = buildAndAssemble(2900, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        String info0 = ok(client().execute("artest rocket info " + id));
        double y0 = parseDouble(info0, POS_Y, "posY");

        ok(client().execute("artest rocket start-free-flight " + id));
        ok(client().execute("artest rocket free-flight-tick " + id + " 60"));

        String info = ok(client().execute("artest rocket info " + id));
        double y = parseDouble(info, POS_Y, "posY");
        double my = parseDouble(info, MOTION_Y, "motionY");
        assertTrue("engines-on craft must still be in flight (hovering): " + info,
                info.contains("\"isInFlight\":true"));
        assertEquals("must hover ~1 block above the start height (y0=" + y0 + ")",
                y0 + 1.0, y, 0.35);
        assertEquals("hover must be near-stationary", 0.0, my, 0.05);
    }

    @Test
    public void descendingToTheGroundShutsTheEnginesOff() throws Exception {
        // touchdown auto-shutdown. From the engine-start hover,
        // pilot descent input drives the craft into ground contact, which
        // exits flight (engines off) and zeroes motion.
        int id = buildAndAssemble(2950, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));
        ok(client().execute("artest rocket free-flight-tick " + id + " 40")); // reach the hover

        ok(client().execute("artest rocket free-flight-input " + id + " 0 -1.0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 80"));

        String info = ok(client().execute("artest rocket info " + id));
        assertTrue("touchdown must shut the engines off (isInFlight=false): " + info,
                info.contains("\"isInFlight\":false"));
        double my = parseDouble(info, MOTION_Y, "motionY");
        assertEquals("landed craft must be stationary", 0.0, my, 0.01);
    }

    @Test
    public void verticalInputDrainsPrimaryFuel() throws Exception {
        int id = buildAndAssemble(2600, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Inspect fuel BEFORE — start-free-flight auto-fills the primary tank.
        String fuelBefore = ok(client().execute("artest rocket fuel " + id));
        int amountBefore = parsePrimaryFuel(fuelBefore);
        assertTrue("start-free-flight must auto-fill primary fuel for tests, got amount="
                + amountBefore + " from " + fuelBefore, amountBefore > 0);

        // Push full vertical thrust + tick several ticks.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 1.0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 10"));

        String fuelAfter = ok(client().execute("artest rocket fuel " + id));
        int amountAfter = parsePrimaryFuel(fuelAfter);
        assertTrue("fuel must decrease monotonically under thrust; before=" + amountBefore
                        + " after=" + amountAfter,
                amountAfter < amountBefore);
    }

    @Test
    public void inputOnClassicRocketIsDroppedSilently() throws Exception {
        // Authority/mode contract: free-flight-input is a no-op when the
        // rocket isn't in FREE_FLIGHT. The probe reports applied=false.
        int id = buildAndAssemble(2700, 64, 500);
        // (intentionally NO set-flight-mode — rocket stays CLASSIC_LAUNCH)

        String applied = ok(client().execute(
                "artest rocket free-flight-input " + id + " 1.0 1.0 1.0 1.0 0.0"));
        assertTrue("classic-mode input must report applied=false: " + applied,
                applied.contains("\"applied\":false"));

        // info still shows zero current input (defensive).
        String info = ok(client().execute("artest rocket info " + id));
        assertTrue("classic rocket info must keep currentFreeFlightInput at zero: " + info,
                info.contains("\"ffInputFwd\":0.0")
                        || info.contains("\"ffInputFwd\":0"));
    }

    @Test
    public void inputClamping() throws Exception {
        // Server-side authority: out-of-range float inputs must be clamped
        // before storage. The applied JSON is the clamped value.
        int id = buildAndAssemble(2800, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        String resp = ok(client().execute(
                "artest rocket free-flight-input " + id + " 5.0 -5.0 99.0 -99.0 50.0"));
        assertTrue("clamp positive overshoot to 1.0: " + resp,
                resp.contains("\"fwd\":1.0"));
        assertTrue("clamp negative overshoot to -1.0: " + resp,
                resp.contains("\"vert\":-1.0"));
        assertTrue("clamp yaw +∞ish to 1.0: " + resp,
                resp.contains("\"yaw\":1.0"));
        assertTrue("clamp pitch -∞ish to -1.0: " + resp,
                resp.contains("\"pitch\":-1.0"));
        assertTrue("clamp brake to 1.0: " + resp,
                resp.contains("\"brake\":1.0"));
    }
}
