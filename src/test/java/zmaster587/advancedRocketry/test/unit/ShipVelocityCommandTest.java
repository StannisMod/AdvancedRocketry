package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Quat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract pinning for the tier-2 ship flight computer's decision surface:
 * {@link FreeFlightPhysics#shipRampSetpoint} + {@link FreeFlightPhysics#shipVelocityCommand}.
 * NO Minecraft types; the whole FA-on/off x throttle/cut/brake matrix runs without a server.
 *
 * <p>The player-visible contract this protects:</p>
 * <ul>
 *   <li><b>FA on is a cruise control</b>: holding a throttle ramps a velocity setpoint, and
 *       RELEASING IT KEEPS CRUISING. Only cut (X) or brake (Shift) bring the ship to a hover.
 *       (Releasing the throttle used to behave exactly like holding X - the bug this pins.)</li>
 *   <li><b>FA off is Newtonian</b>: a held throttle accelerates, brake decelerates, and releasing
 *       everything (or cut) yields a {@code null} command - no force at all, so the ship coasts.</li>
 * </ul>
 * {@code null} (coast) vs a zeroed vector (brake to a stop) are distinct commands throughout.
 */
public class ShipVelocityCommandTest {

    private static final double DELTA = 1e-9;
    private static final double MAX = 8.0;
    private static final double RAMP = MAX / 60.0; // full deflection reaches MAX in 3 s
    /** Identity attitude: body forward=+Z, right=+X, up=+Y -> world {x=right, y=up, z=forward}. */
    private static final Quat LEVEL = Quat.IDENTITY;

    private static final double[] REST = {0.0, 0.0, 0.0};

    private static FreeFlightInput input(float fwd, float vert, float strafe, float brake, boolean cut) {
        // (throttleForward, throttleVertical, strafeInput, yawInput, pitchInput, rollInput, brake, cut)
        return new FreeFlightInput(fwd, vert, strafe, 0f, 0f, 0f, brake, cut);
    }

    /** One Flight-Assist tick as the flight computer runs it: ramp the setpoint, then command it. */
    private static double[] ramp(double[] sp, FreeFlightInput in) {
        return FreeFlightPhysics.shipRampSetpoint(sp[0], sp[1], sp[2], in, MAX, RAMP);
    }

    private static double[] rampFor(int ticks, double[] sp, FreeFlightInput in) {
        for (int i = 0; i < ticks; i++) {
            sp = ramp(sp, in);
        }
        return sp;
    }

    private static double[] faCommand(double[] sp, FreeFlightInput in) {
        return FreeFlightPhysics.shipVelocityCommand(in, LEVEL, true, sp, MAX);
    }

    private static double[] newtonianCommand(FreeFlightInput in) {
        return FreeFlightPhysics.shipVelocityCommand(in, LEVEL, false, REST, MAX);
    }

    // ---- Flight Assist ON: cruise control -------------------------------------------------

    @Test
    public void faOnReleasingTheThrottleKeepsCruising() {
        // THE regression pin: accelerate, then let go. The ship must hold its speed, not stop.
        double[] sp = rampFor(60, REST, input(1f, 0f, 0f, 0f, false));
        assertEquals("full deflection reaches cruise speed", MAX, sp[0], 1e-6);

        double[] coasting = rampFor(40, sp, FreeFlightInput.zero()); // hands off the keys
        assertEquals("setpoint survives releasing the throttle", MAX, coasting[0], 1e-6);
        assertArrayEquals("ship keeps cruising forward",
                new double[]{0.0, 0.0, MAX}, faCommand(coasting, FreeFlightInput.zero()), 1e-6);
    }

    @Test
    public void faOnThrottleRampsGraduallyNotInstantly() {
        double[] sp = ramp(REST, input(1f, 0f, 0f, 0f, false));
        assertEquals(RAMP, sp[0], DELTA);
        assertTrue("one tick of throttle is far below cruise speed", sp[0] < MAX / 10.0);
    }

    @Test
    public void faOnSetpointClampsToMaxSpeed() {
        double[] sp = rampFor(500, REST, input(1f, 0f, 0f, 0f, false));
        assertEquals(MAX, sp[0], 1e-6);
    }

    @Test
    public void faOnCutZeroesTheSetpointToHover() {
        double[] sp = rampFor(60, REST, input(1f, 0f, 0f, 0f, false));
        double[] afterCut = ramp(sp, input(1f, 0f, 0f, 0f, true)); // X, throttle still held
        assertArrayEquals(REST, afterCut, DELTA);
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, faCommand(afterCut, FreeFlightInput.zero()), DELTA);
    }

    @Test
    public void faOnBrakeZeroesTheSetpointToHover() {
        double[] sp = rampFor(60, REST, input(1f, 0f, 0f, 0f, false));
        assertArrayEquals(REST, ramp(sp, input(0f, 0f, 0f, 1f, false)), DELTA);
    }

    @Test
    public void faOnIdleAtRestHovers() {
        double[] v = faCommand(REST, FreeFlightInput.zero());
        assertNotNull(v);
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, v, DELTA);
    }

    @Test
    public void faOnSetpointMapsThroughBodyAxes() {
        // vertical -> world +Y, strafe -> world +X (identity attitude).
        double[] up = rampFor(60, REST, input(0f, 1f, 0f, 0f, false));
        assertArrayEquals(new double[]{0.0, MAX, 0.0}, faCommand(up, FreeFlightInput.zero()), 1e-6);
        double[] right = rampFor(60, REST, input(0f, 0f, 1f, 0f, false));
        assertArrayEquals(new double[]{MAX, 0.0, 0.0}, faCommand(right, FreeFlightInput.zero()), 1e-6);
    }

    // ---- Flight Assist OFF: Newtonian ----------------------------------------------------

    @Test
    public void faOffIdleCoastsWithNoForce() {
        assertNull(newtonianCommand(FreeFlightInput.zero()));
    }

    @Test
    public void faOffForwardThrottleAccelerates() {
        double[] v = newtonianCommand(input(1f, 0f, 0f, 0f, false));
        assertNotNull(v);
        assertArrayEquals(new double[]{0.0, 0.0, MAX}, v, DELTA);
    }

    @Test
    public void faOffCutCoasts() {
        // Cut kills thrust; FA off does NOT auto-brake -> coast (null), even with a held throttle.
        assertNull(newtonianCommand(input(1f, 0f, 0f, 0f, true)));
    }

    @Test
    public void faOffBrakeDeceleratesEvenWithThrottle() {
        double[] v = newtonianCommand(input(1f, 0f, 0f, 1f, false));
        assertNotNull(v);
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, v, DELTA);
    }

    @Test
    public void faOffIgnoresTheCruiseSetpoint() {
        // A stale cruise setpoint must not resurrect thrust once the pilot switches FA off.
        double[] cruising = rampFor(60, REST, input(1f, 0f, 0f, 0f, false));
        assertNull(FreeFlightPhysics.shipVelocityCommand(
                FreeFlightInput.zero(), LEVEL, false, cruising, MAX));
    }

    // ---- Robustness ----------------------------------------------------------------------

    @Test
    public void nullInputIsIdle() {
        assertNull(FreeFlightPhysics.shipVelocityCommand(null, LEVEL, false, REST, MAX));
        assertArrayEquals(new double[]{0.0, 0.0, 0.0},
                FreeFlightPhysics.shipVelocityCommand(null, LEVEL, true, REST, MAX), DELTA);
        assertArrayEquals(REST, FreeFlightPhysics.shipRampSetpoint(0, 0, 0, null, MAX, RAMP), DELTA);
    }

    @Test
    public void nullSetpointUnderFlightAssistHovers() {
        assertArrayEquals(new double[]{0.0, 0.0, 0.0},
                FreeFlightPhysics.shipVelocityCommand(FreeFlightInput.zero(), LEVEL, true, null, MAX), DELTA);
    }
}
