package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Server-side e2e for the Flight Assist velocity-setpoint law (TASK-46 D4)
 * through the real probe surface:
 *  - FA defaults ON and round-trips through set-flight-assist;
 *  - the cut flag (X) travels the input wire and is stored;
 *  - holding forward RAMPS the setpoint, releasing KEEPS the cruise;
 *  - cut eases the craft into a gravity-cancelled hover (not a fall);
 *  - the setpoint is body-frame: yawing rotates the world velocity;
 *  - re-enabling FA captures the current velocity (no jerk);
 *  - FA off remains raw Newtonian, with the manual brake still honoured.
 */
public class FreeFlightAssistsE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern MOTION_X = Pattern.compile("\"motionX\":(-?[0-9.E\\-]+)");
    private static final Pattern MOTION_Y = Pattern.compile("\"motionY\":(-?[0-9.E\\-]+)");
    private static final Pattern MOTION_Z = Pattern.compile("\"motionZ\":(-?[0-9.E\\-]+)");
    private static final Pattern ENGINE_POWER = Pattern.compile("\"enginePower\":(-?[0-9.E\\-]+)");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
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
    public void cutFlagThroughInputIsStoredOnServer() throws Exception {
        int id = buildAndAssemble(4100, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // cut=1 at position 7.
        String applied = ok(client().execute(
                "artest rocket free-flight-input " + id + " 0 0 0 0 0 1"));
        assertTrue("input must apply on FF rocket: " + applied,
                applied.contains("\"applied\":true"));
        assertTrue("probe echoes cut=true: " + applied,
                applied.contains("\"cut\":true"));

        String info = ok(client().execute("artest rocket info " + id));
        assertTrue("info must store ffInputCut=true: " + info,
                info.contains("\"ffInputCut\":true"));
    }

    @Test
    public void setpointPersistsAfterReleasingTheKey() throws Exception {
        // THE Flight Assist feature (TASK-46 D4): holding forward RAMPS the
        // velocity setpoint; releasing the key KEEPS it — the craft cruises
        // hands-off instead of coasting down.
        int id = buildAndAssemble(4150, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Hold forward+up for 20 ticks: the setpoint ramps to ~1.0 blocks/tick
        // on both axes. The upward component keeps the cruise climbing away
        // from terrain — the world outside the cleared pad column is random.
        ok(client().execute("artest rocket free-flight-input " + id + " 1 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 20"));

        // Release (all-zero input) and keep flying.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 40"));

        String info = ok(client().execute("artest rocket info " + id));
        double mz = parseDouble(info, Pattern.compile("\"motionZ\":(-?[0-9.E\\-]+)"), "motionZ");
        assertTrue("released key must NOT bleed the cruise (motionZ=" + mz
                + ", expected to keep cruising +Z): " + info, mz > 0.5);
        assertTrue("setpoint must persist on the server: " + info,
                info.contains("\"faSetpointFwd\""));
    }

    @Test
    public void cutEasesTheCraftIntoAGravityCancelledHover() throws Exception {
        // X (cut): zero the setpoint → FA damps motion to zero AND holds
        // altitude (gravity cancelled) — brake-to-hover, not brake-to-fall.
        int id = buildAndAssemble(4200, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Build a climbing cruise first (also cancels the liftoff assist; the
        // upward component keeps it clear of un-cleared terrain).
        ok(client().execute("artest rocket free-flight-input " + id + " 1 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 20"));

        // Cut.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0 1"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 60"));

        String info = ok(client().execute("artest rocket info " + id));
        double my = parseDouble(info, MOTION_Y, "motionY");
        double mz = parseDouble(info, Pattern.compile("\"motionZ\":(-?[0-9.E\\-]+)"), "motionZ");
        assertTrue("cut must ease the cruise to a stop (motionZ=" + mz + ")",
                Math.abs(mz) < 0.05);
        assertTrue("cut must HOLD ALTITUDE, not drop the craft (motionY=" + my + ")",
                Math.abs(my) < 0.05);
        assertTrue("hovering craft must still be in flight: " + info,
                info.contains("\"isInFlight\":true"));
    }

    @Test
    public void yawingTheCraftRotatesTheCruiseVelocity() throws Exception {
        // The setpoint is body-frame: cruise forward, then yaw ~90° — the
        // WORLD velocity must rotate with the nose (from +Z toward -X).
        int id = buildAndAssemble(4300, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        ok(client().execute("artest rocket free-flight-input " + id + " 1 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 20"));
        // Release forward, hold yaw for 30 ticks (= 90° at MAX_YAW_RATE 3°/tick).
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 1 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 30"));
        // Let FA re-align the velocity to the rotated setpoint.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 40"));

        String info = ok(client().execute("artest rocket info " + id));
        double mx = parseDouble(info, Pattern.compile("\"motionX\":(-?[0-9.E\\-]+)"), "motionX");
        double mz = parseDouble(info, Pattern.compile("\"motionZ\":(-?[0-9.E\\-]+)"), "motionZ");
        assertTrue("after a 90° yaw the cruise must point -X (mx=" + mx + " mz=" + mz + ")",
                mx < -0.5 && Math.abs(mz) < 0.35);
    }

    @Test
    public void reEnablingFlightAssistCapturesTheCurrentVelocity() throws Exception {
        // Toggling FA back on mid-flight must NOT jerk the craft: the setpoint
        // initialises to the current velocity (Elite behaviour).
        int id = buildAndAssemble(4350, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Climb away from the ground first: the Newtonian phase sheds altitude
        // under gravity, and starting from the 1-block engine hover it would
        // touch down (engines off) before the capture could be observed.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 25"));
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0 1")); // cut -> hover
        ok(client().execute("artest rocket free-flight-tick " + id + " 30"));

        // FA off, build a Newtonian cruise with direct thrust, then coast.
        ok(client().execute("artest rocket set-flight-assist " + id + " off"));
        ok(client().execute("artest rocket free-flight-input " + id + " 1 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 8"));
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 2"));
        double mzBefore = parseDouble(ok(client().execute("artest rocket info " + id)),
                Pattern.compile("\"motionZ\":(-?[0-9.E\\-]+)"), "motionZ");
        assertTrue("precondition: must be coasting (+Z), got " + mzBefore, mzBefore > 0.2);

        // FA back on → setpoint captured → cruise continues, no jerk.
        ok(client().execute("artest rocket set-flight-assist " + id + " on"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 20"));
        double mzAfter = parseDouble(ok(client().execute("artest rocket info " + id)),
                Pattern.compile("\"motionZ\":(-?[0-9.E\\-]+)"), "motionZ");
        assertTrue("FA re-enable must keep the cruise (was " + mzBefore + ", now "
                + mzAfter + ")", Math.abs(mzAfter - mzBefore) < 0.25);
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

    @Test
    public void engineSoundPowerTracksThrustNotJustClimb() throws Exception {
        // The client engine sound is driven by getEnginePower(); in FF that must be
        // non-zero for thrust in ANY direction — not only motionY>0 (the classic
        // areEnginesRunning gate that made the sound cut out in cruise/hover).
        int id = buildAndAssemble(4250, 64, 500);
        ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        ok(client().execute("artest rocket start-free-flight " + id));

        // Climb clear of the pad, then CUT to a gravity-cancelled hover: FA fires
        // the engines to hold altitude, so motionY settles ~0 while thrust is still
        // being produced — exactly the case the old motionY>0 gate silenced.
        ok(client().execute("artest rocket free-flight-input " + id + " 0 1 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 30"));
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0 1")); // cut → hover
        ok(client().execute("artest rocket free-flight-tick " + id + " 25"));
        String hover = ok(client().execute("artest rocket info " + id));
        double myHover  = parseDouble(hover, MOTION_Y, "motionY");
        double powHover = parseDouble(hover, ENGINE_POWER, "enginePower");
        assertTrue("hover thrust (no climb) must still register engine power for the sound "
                + "(motionY=" + myHover + " enginePower=" + powHover + ")", powHover > 0.0);

        // FA off + no input: pure coast under gravity, no thrust → engines silent.
        ok(client().execute("artest rocket set-flight-assist " + id + " off"));
        ok(client().execute("artest rocket free-flight-input " + id + " 0 0 0 0 0"));
        ok(client().execute("artest rocket free-flight-tick " + id + " 3"));
        double powCoast = parseDouble(ok(client().execute("artest rocket info " + id)),
                ENGINE_POWER, "enginePower");
        assertTrue("coasting with no thrust must be silent (enginePower=" + powCoast + ")",
                powCoast < 1e-3);
    }
}
