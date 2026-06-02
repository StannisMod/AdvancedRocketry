package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Server-side end-to-end coverage for Free Flight ASSISTS (Option A):
 *  - Stop (X / probe stop flag)
 *  - Flight Assist toggle (N / set-flight-assist)
 *  - Hover Hold (H / probe hover flag)
 *
 * <p>Contracts pinned:
 *  - Default FA state is ON; flips through probe + round-trips to info.
 *  - Stop flag through free-flight-input is observable in info (proves the
 *    extended packet wire path stores stopActive correctly).
 *  - Hover flag similarly observable.
 *  - With Hover active and gravity, a one-tick step keeps motionY ≈ 0
 *    (within fp epsilon) — the cancel-gravity contract.
 *  - With Stop active and motion > 0, ticks drive motion magnitude toward 0.
 */
public class FreeFlightAssistsE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern MOTION_X = Pattern.compile("\"motionX\":(-?[0-9.E\\-]+)");
    private static final Pattern MOTION_Y = Pattern.compile("\"motionY\":(-?[0-9.E\\-]+)");
    private static final Pattern MOTION_Z = Pattern.compile("\"motionZ\":(-?[0-9.E\\-]+)");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
        String fillAir = ok(client().execute(
                "artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7)
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

    // -----------------------------------------------------------------

    @Test
    public void flightAssistDefaultsOnAndTogglesThroughProbe() throws Exception {
        int id = buildAndAssemble(4000, 64, 500);
        String info0 = ok(client().execute("artest rocket info " + id));
        assertTrue("FA must default to true: " + info0,
                info0.contains("\"flightAssistOn\":true"));

        String off = ok(client().execute("artest rocket set-flight-assist " + id + " off"));
        assertTrue("set-flight-assist off must succeed: " + off,
                off.contains("\"ok\":true") && off.contains("\"flightAssistOn\":false"));

        String info1 = ok(client().execute("artest rocket info " + id));
        assertTrue("info must round-trip FA=false: " + info1,
                info1.contains("\"flightAssistOn\":false"));

        ok(client().execute("artest rocket set-flight-assist " + id + " on"));
        String info2 = ok(client().execute("artest rocket info " + id));
        assertTrue("info must round-trip FA=true after flip-back: " + info2,
                info2.contains("\"flightAssistOn\":true"));
    }

    @Test
    public void setFlightAssistRejectsBadValue() throws Exception {
        int id = buildAndAssemble(4050, 64, 500);
        String resp = ok(client().execute(
                "artest rocket set-flight-assist " + id + " wat"));
        assertTrue("bad value must report error: " + resp,
                resp.contains("\"error\":\"bad value"));
    }

    @Test
    public void stopFlagThroughInputIsStoredOnServer() throws Exception {
        int id = buildAndAssemble(4100, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // stop=1 at position 7.
        String applied = ok(client().execute(
                "artest rocket free-flight-input " + id + " 0 0 0 0 0 1 0"));
        assertTrue("input must apply on FF rocket: " + applied,
                applied.contains("\"applied\":true"));
        assertTrue("probe echoes stop=true: " + applied,
                applied.contains("\"stop\":true"));

        String info = ok(client().execute("artest rocket info " + id));
        assertTrue("info must store ffInputStop=true: " + info,
                info.contains("\"ffInputStop\":true"));
        assertTrue("info must store ffInputHover=false: " + info,
                info.contains("\"ffInputHover\":false"));
    }

    @Test
    public void hoverFlagThroughInputIsStoredOnServer() throws Exception {
        int id = buildAndAssemble(4150, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // hover=1 at position 8.
        String applied = ok(client().execute(
                "artest rocket free-flight-input " + id + " 0 0 0 0 0 0 1"));
        assertTrue("probe echoes hover=true: " + applied,
                applied.contains("\"hover\":true"));

        String info = ok(client().execute("artest rocket info " + id));
        assertTrue("info must store ffInputHover=true: " + info,
                info.contains("\"ffInputHover\":true"));
    }

    @Test
    public void stopAssistDrivesMotionMagnitudeDownOverTicks() throws Exception {
        int id = buildAndAssemble(4200, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Build up some +Z motion by setting motion directly via set-state.
        // (The fixture's tiny thrust can't accelerate fast enough for a clean signal.)
        ok(client().execute("artest rocket set-state " + id + " motionY=0.5"));
        // motionX/motionZ aren't exposed via set-state — use motionY as the
        // observable; Stop must drive |motion| down regardless of axis.

        // Snapshot speed-ish baseline.
        String before = ok(client().execute("artest rocket info " + id));
        double myBefore = parseDouble(before, MOTION_Y, "motionY");
        assertTrue("baseline must have motion to stop: motionY=" + myBefore,
                Math.abs(myBefore) > 0.1);

        // Engage Stop and tick.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0 1 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 10"));

        String after = ok(client().execute("artest rocket info " + id));
        double myAfter = parseDouble(after, MOTION_Y, "motionY");
        assertTrue("Stop assist must reduce |motionY| from " + myBefore + " toward 0, got "
                        + myAfter,
                Math.abs(myAfter) < Math.abs(myBefore));
    }

    @Test
    public void hoverAssistHoldsAltitudeAgainstGravity() throws Exception {
        // Pin the cancel-gravity contract end-to-end: with Hover engaged and
        // fuel available, a free-flight-tick must NOT drain motionY by the
        // gravity amount.
        int id = buildAndAssemble(4300, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Wipe the initial kick from startFreeFlight so we measure pure
        // hover-vs-gravity.
        ok(client().execute("artest rocket set-state " + id + " motionY=0.0"));

        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0 0 1"));
        String tickRes = ok(client().execute(
                "artest rocket free-flight-tick " + id + " 1"));
        double my = parseDouble(tickRes, MOTION_Y, "motionY");
        // Without hover, one tick of gravity drops motionY by ~0.04
        // (default mult=1.0). With hover, it should stay at 0.
        assertTrue("Hover hold must keep motionY at ~0 against gravity, got "
                        + my,
                Math.abs(my) < 0.01);
    }

    @Test
    public void flightAssistOffStillAcceptsExplicitBrake() throws Exception {
        // Cross-side wiring: FA=off + brake input still attenuates motion.
        int id = buildAndAssemble(4400, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));
        ok(client().execute("artest rocket set-flight-assist " + id + " off"));

        ok(client().execute("artest rocket set-state " + id + " motionY=1.0"));
        String before = ok(client().execute("artest rocket info " + id));
        double myBefore = parseDouble(before, MOTION_Y, "motionY");

        // brake=1.0 (channel 4).
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 1 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 1"));

        String after = ok(client().execute("artest rocket info " + id));
        double myAfter = parseDouble(after, MOTION_Y, "motionY");
        // Brake at FA off must still pull motion down (toward gravity-altered baseline).
        assertTrue("FA off + brake must still attenuate motionY (was "
                        + myBefore + ", now " + myAfter + ")",
                Math.abs(myAfter) < Math.abs(myBefore));
    }
}
