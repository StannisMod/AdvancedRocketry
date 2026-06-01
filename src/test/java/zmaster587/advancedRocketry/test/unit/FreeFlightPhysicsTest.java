package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Contract pinning for {@link FreeFlightPhysics#step} — the pure decision surface
 * that drives FREE_FLIGHT-mode kinematics. NO Minecraft types in this test; if a
 * regression slips into the math, this file fails before any server boots.
 *
 * Pins:
 *  - Idle input + no gravity + no motion → no motion change.
 *  - Pure forward thrust moves the rocket along its yaw vector (no Y change beyond gravity).
 *  - Vertical thrust deltas motionY by a positive amount.
 *  - Yaw input rotates yaw at MAX_YAW_RATE × input.
 *  - Out-of-fuel + thrust input → no thrust applied; gravity still decreases motionY.
 *  - Brake input attenuates motion magnitude.
 *  - Max-speed hard cap clamps |motion| to MAX_SPEED.
 *  - Pitch clamp at PITCH_MAX.
 *  - shouldLand() requires ground contact AND small motionY.
 *  - Null input is tolerated (treated as zero).
 */
public class FreeFlightPhysicsTest {

    private static final double DELTA = 1e-6;

    /** Convenient default thrust/mass that produces a non-trivial thrustScalar. */
    private static final int DEFAULT_THRUST = 50000;
    private static final double DEFAULT_MASS = 10000.0;

    @Test
    public void idleInputNoGravityNoMotionIsStable() {
        Step s = FreeFlightPhysics.step(
                0, 0, 0,                       // motion
                0f, 0f,                        // yaw, pitch
                FreeFlightInput.zero(),
                DEFAULT_THRUST, DEFAULT_MASS,
                0.0,                           // gravity
                1000);                         // fuel
        assertEquals(0, s.motionX, DELTA);
        assertEquals(0, s.motionY, DELTA);
        assertEquals(0, s.motionZ, DELTA);
        assertEquals(0f, s.yaw, DELTA);
        assertEquals(0f, s.pitch, DELTA);
        assertFalse(s.thrustApplied);
        assertEquals(0, s.fuelConsumed);
    }

    @Test
    public void nullInputTreatedAsZero() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f, null,
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertEquals(0, s.motionX, DELTA);
        assertEquals(0, s.motionY, DELTA);
        assertEquals(0, s.motionZ, DELTA);
        assertFalse(s.thrustApplied);
    }

    @Test
    public void gravityDrainsMotionYWhenIdle() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                DEFAULT_THRUST, DEFAULT_MASS, 0.05, 1000);
        // gravity is +0.05 → motionY drops by exactly 0.05 with idle drag NOT applied to Y
        // (idle drag only scales X/Z per implementation).
        assertEquals(-0.05, s.motionY, DELTA);
    }

    @Test
    public void forwardThrustMovesAlongYaw() {
        // yaw=0 → forward vector = (-sin 0, cos 0) = (0, 1) → +Z direction.
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertTrue("expected +Z motion from forward thrust at yaw=0, got " + s.motionZ,
                s.motionZ > 0);
        assertEquals("no X motion when yaw=0", 0.0, s.motionX, DELTA);
        // gravity=0 in this test, so motionY stays zero.
        assertEquals(0.0, s.motionY, DELTA);
        assertTrue(s.thrustApplied);
        assertTrue(s.fuelConsumed > 0);
    }

    @Test
    public void forwardThrustAt90DegYawMovesAlongNegX() {
        // yaw=90 → forward = (-sin 90°, cos 90°) = (-1, 0).
        Step s = FreeFlightPhysics.step(0, 0, 0, 90f, 0f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertTrue("expected -X motion at yaw=90, got " + s.motionX, s.motionX < 0);
        assertEquals(0.0, s.motionZ, 1e-3); // cos 90 ≈ 0
    }

    @Test
    public void verticalThrustIncreasesMotionY() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertTrue("vertical+ thrust must raise motionY, got " + s.motionY, s.motionY > 0);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void yawInputRotatesYaw() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 10f, 0f,
                new FreeFlightInput(0f, 0f, 1f, 0f, 0f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertEquals(10f + (float) FreeFlightPhysics.MAX_YAW_RATE, s.yaw, DELTA);
    }

    @Test
    public void pitchInputRotatesPitchClampedToMax() {
        // Pitch rate per tick is MAX_PITCH_RATE — at +1 input, pitch grows by exactly
        // MAX_PITCH_RATE until the clamp at PITCH_MAX kicks in.
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, (float)(FreeFlightPhysics.PITCH_MAX - 1),
                new FreeFlightInput(0f, 0f, 0f, 1f, 0f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertEquals((float) FreeFlightPhysics.PITCH_MAX, s.pitch, DELTA);
    }

    @Test
    public void pitchClampedBelowNegative() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, (float)(-FreeFlightPhysics.PITCH_MAX + 1),
                new FreeFlightInput(0f, 0f, 0f, -1f, 0f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertEquals((float) -FreeFlightPhysics.PITCH_MAX, s.pitch, DELTA);
    }

    @Test
    public void noFuelDisablesThrustButStillRotatesAndApplyGravity() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 1f, 1f, 1f, 0f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.05, /*fuel=*/0);
        assertFalse("out of fuel: no thrust", s.thrustApplied);
        assertEquals("out of fuel: no fuel consumed", 0, s.fuelConsumed);
        assertEquals("forward thrust must not apply (motionX stayed 0)", 0.0, s.motionX, DELTA);
        assertEquals("vertical thrust must not apply, only gravity acts",
                -0.05, s.motionY, DELTA);
        // Yaw rotation still applies — input drives orientation independently of fuel.
        assertNotEquals(0f, s.yaw);
    }

    @Test
    public void brakeAttenuatesHorizontalMotion() {
        // start moving in +X; full brake should pull magnitude down (× BRAKE_RETENTION).
        double startX = 1.0;
        Step s = FreeFlightPhysics.step(startX, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 1f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertTrue("brake must shrink motionX magnitude", Math.abs(s.motionX) < startX);
    }

    @Test
    public void hardSpeedCapClampsMagnitudeToMaxSpeed() {
        // initial motion already at 10 blocks/tick — far above MAX_SPEED.
        Step s = FreeFlightPhysics.step(10, 0, 0, 0f, 0f,
                FreeFlightInput.zero(),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        double speed = Math.sqrt(s.motionX * s.motionX
                + s.motionY * s.motionY + s.motionZ * s.motionZ);
        assertTrue("hard cap: speed must not exceed MAX_SPEED, got " + speed,
                speed <= FreeFlightPhysics.MAX_SPEED + DELTA);
    }

    @Test
    public void fuelConsumedScalesWithThrottle() {
        // Full forward+vertical → full demand → ceil(1.0 * 4) = 4
        Step full = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 1f, 0f, 0f, 0f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertEquals(FreeFlightPhysics.FUEL_PER_TICK_AT_FULL_THRUST, full.fuelConsumed);

        // Half forward + zero vertical → 0.25 demand → ceil(0.25*4) = 1
        Step half = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0.5f, 0f, 0f, 0f, 0f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertEquals(1, half.fuelConsumed);
    }

    @Test
    public void fuelConsumedCappedByFuelAvailable() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 1f, 0f, 0f, 0f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, /*fuel=*/2);
        assertTrue("fuel consumed must not exceed available", s.fuelConsumed <= 2);
        assertTrue("fuel consumed must be positive when thrust applied", s.fuelConsumed > 0);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void shouldLandRequiresGroundAndSlowVertical() {
        assertTrue (FreeFlightPhysics.shouldLand(true,  0.0));
        assertTrue (FreeFlightPhysics.shouldLand(true,  0.04));
        assertTrue (FreeFlightPhysics.shouldLand(true, -0.04));
        assertFalse(FreeFlightPhysics.shouldLand(true,  1.0));   // ground but going fast → not landing
        assertFalse(FreeFlightPhysics.shouldLand(false, 0.0));   // airborne idle → not landing
    }

    @Test
    public void minimalMassDoesNotBlowUp() {
        // mass=0 mustn't NaN/Infinity the motion; physics floors mass internally.
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                DEFAULT_THRUST, 0.0, 0.0, 1000);
        assertTrue("motion must be finite even at mass=0",
                Double.isFinite(s.motionX) && Double.isFinite(s.motionZ));
    }
}
