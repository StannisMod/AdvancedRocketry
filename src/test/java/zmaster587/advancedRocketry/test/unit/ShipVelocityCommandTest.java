package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Quat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Contract pinning for {@link FreeFlightPhysics#shipVelocityCommand} - the tier-2 ship flight
 * computer's decision surface. NO Minecraft types; the whole FA-on/off + cut/brake matrix is
 * exercised without booting a server.
 *
 * <p>The player-visible contract this protects:</p>
 * <ul>
 *   <li><b>FA on brakes to a hover on release</b> (throttle released -> zero-velocity command),
 *       and cut / brake also stop.</li>
 *   <li><b>FA off coasts on release</b> (a {@code null} command -> the controller applies no force),
 *       while a held throttle accelerates and brake actively decelerates.</li>
 * </ul>
 * The distinction that makes Flight Assist observable is exactly {@code null} (coast) vs a zeroed
 * vector (brake): those are the two assertions repeated below.
 */
public class ShipVelocityCommandTest {

    private static final double DELTA = 1e-9;
    private static final double MAX = 8.0;
    /** Identity attitude: body forward=+Z, right=+X, up=+Y -> world {x=right, y=up, z=forward}. */
    private static final Quat LEVEL = Quat.IDENTITY;

    private static FreeFlightInput input(float fwd, float vert, float strafe, float brake, boolean cut) {
        // (throttleForward, throttleVertical, strafeInput, yawInput, pitchInput, rollInput, brake, cut)
        return new FreeFlightInput(fwd, vert, strafe, 0f, 0f, 0f, brake, cut);
    }

    // ---- Flight Assist ON: velocity hold -------------------------------------------------

    @Test
    public void faOnForwardThrottleCommandsForwardVelocity() {
        double[] v = FreeFlightPhysics.shipVelocityCommand(input(1f, 0f, 0f, 0f, false), LEVEL, true, MAX);
        assertNotNull(v);
        assertArrayEquals(new double[]{0.0, 0.0, MAX}, v, DELTA);
    }

    @Test
    public void faOnIdleCommandsZeroHover() {
        // Released throttle under FA -> a zero-velocity setpoint (brake to hover), NOT a coast.
        double[] v = FreeFlightPhysics.shipVelocityCommand(FreeFlightInput.zero(), LEVEL, true, MAX);
        assertNotNull(v);
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, v, DELTA);
    }

    @Test
    public void faOnCutOverridesThrottleToStop() {
        double[] v = FreeFlightPhysics.shipVelocityCommand(input(1f, 0f, 0f, 0f, true), LEVEL, true, MAX);
        assertNotNull(v);
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, v, DELTA);
    }

    @Test
    public void faOnBrakeCommandsStop() {
        double[] v = FreeFlightPhysics.shipVelocityCommand(input(1f, 0f, 0f, 1f, false), LEVEL, true, MAX);
        assertNotNull(v);
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, v, DELTA);
    }

    @Test
    public void faOnThrottleMapsThroughBodyAxes() {
        // vertical -> world +Y, strafe -> world +X (identity attitude).
        assertArrayEquals(new double[]{0.0, MAX, 0.0},
                FreeFlightPhysics.shipVelocityCommand(input(0f, 1f, 0f, 0f, false), LEVEL, true, MAX), DELTA);
        assertArrayEquals(new double[]{MAX, 0.0, 0.0},
                FreeFlightPhysics.shipVelocityCommand(input(0f, 0f, 1f, 0f, false), LEVEL, true, MAX), DELTA);
    }

    // ---- Flight Assist OFF: Newtonian ----------------------------------------------------

    @Test
    public void faOffIdleCoastsWithNoForce() {
        // The defining FA-off behaviour: release everything -> null command -> no force -> coast.
        assertNull(FreeFlightPhysics.shipVelocityCommand(FreeFlightInput.zero(), LEVEL, false, MAX));
    }

    @Test
    public void faOffForwardThrottleAccelerates() {
        double[] v = FreeFlightPhysics.shipVelocityCommand(input(1f, 0f, 0f, 0f, false), LEVEL, false, MAX);
        assertNotNull(v);
        assertArrayEquals(new double[]{0.0, 0.0, MAX}, v, DELTA);
    }

    @Test
    public void faOffCutCoasts() {
        // Cut kills thrust; FA off does NOT auto-brake -> coast (null), even with a held throttle.
        assertNull(FreeFlightPhysics.shipVelocityCommand(input(1f, 0f, 0f, 0f, true), LEVEL, false, MAX));
    }

    @Test
    public void faOffBrakeDeceleratesEvenWithThrottle() {
        double[] v = FreeFlightPhysics.shipVelocityCommand(input(1f, 0f, 0f, 1f, false), LEVEL, false, MAX);
        assertNotNull(v);
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, v, DELTA);
    }

    // ---- Robustness ----------------------------------------------------------------------

    @Test
    public void nullInputIsIdle() {
        // FA off + no input -> coast; FA on + no input -> hover. Never throws.
        assertNull(FreeFlightPhysics.shipVelocityCommand(null, LEVEL, false, MAX));
        assertArrayEquals(new double[]{0.0, 0.0, 0.0},
                FreeFlightPhysics.shipVelocityCommand(null, LEVEL, true, MAX), DELTA);
    }
}
