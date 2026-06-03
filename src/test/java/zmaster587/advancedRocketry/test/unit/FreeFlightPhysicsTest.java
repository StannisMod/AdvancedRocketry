package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract pinning for {@link FreeFlightPhysics#step} — the pure decision surface
 * that drives FREE_FLIGHT-mode kinematics. NO Minecraft types in this test; if a
 * regression slips into the math, this file fails before any server boots.
 *
 * <p>The thrust scale is no longer invented here: the caller passes a gross
 * per-tick thrust acceleration ({@code thrustMag}) and whether thrust is allowed
 * ({@code canThrust}); fuel accounting lives in EntityRocket. The climb gate is
 * therefore purely "full vertical net = thrustMag − gravity", which EntityRocket
 * wires to the classic TWR via getAcceleration.
 *
 * Pins:
 *  - Idle input + no gravity + no motion → no motion change.
 *  - Forward thrust moves along the yaw vector by exactly thrustMag.
 *  - Vertical thrust raises motionY by exactly thrustMag (no gravity).
 *  - Climb gate: full vertical climbs iff thrustMag &gt; gravity.
 *  - Yaw/pitch rotate at MAX_*_RATE; pitch clamps to PITCH_MAX.
 *  - canThrust=false → no thrust applied; gravity + rotation still act.
 *  - Brake attenuates motion; hard speed cap clamps to MAX_SPEED.
 *  - Pitch projection lifts/lowers forward thrust; vertical is pitch-independent.
 *  - Null input is tolerated (treated as zero).
 */
public class FreeFlightPhysicsTest {

    private static final double DELTA = 1e-6;

    /** A healthy gross thrust acceleration (well above default gravity). */
    private static final double THRUST = 0.10;

    @Test
    public void idleNoGravityNoMotionIsStable() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                THRUST, 0.0, true, true);
        assertEquals(0, s.motionX, DELTA);
        assertEquals(0, s.motionY, DELTA);
        assertEquals(0, s.motionZ, DELTA);
        assertEquals(0f, s.yaw, DELTA);
        assertEquals(0f, s.pitch, DELTA);
        assertFalse(s.thrustApplied);
    }

    @Test
    public void nullInputTreatedAsZero() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f, null, THRUST, 0.0, true, true);
        assertEquals(0, s.motionX, DELTA);
        assertEquals(0, s.motionY, DELTA);
        assertEquals(0, s.motionZ, DELTA);
        assertFalse(s.thrustApplied);
    }

    @Test
    public void gravityDrainsMotionYWhenIdle() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                THRUST, 0.05, true, true);
        assertEquals(-0.05, s.motionY, DELTA);
    }

    @Test
    public void forwardThrustMovesAlongYawByThrustMag() {
        // yaw=0 → forward vector = (-sin 0, cos 0) = (0, 1) → +Z by exactly thrustMag.
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                THRUST, 0.0, true, true);
        assertEquals(THRUST, s.motionZ, DELTA);
        assertEquals(0.0, s.motionX, DELTA);
        assertEquals(0.0, s.motionY, DELTA);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void forwardThrustAt90DegYawMovesAlongNegX() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 90f, 0f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                THRUST, 0.0, true, true);
        assertTrue("expected -X motion at yaw=90, got " + s.motionX, s.motionX < 0);
        assertEquals(0.0, s.motionZ, 1e-3); // cos 90 ≈ 0
    }

    @Test
    public void verticalThrustRaisesMotionYByThrustMag() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f),
                THRUST, 0.0, true, true);
        assertEquals(THRUST, s.motionY, DELTA);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void climbGateFullVerticalClimbsWhenThrustExceedsGravity() {
        // thrustMag (0.10) > gravity (0.04) → net positive climb.
        Step climb = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f),
                0.10, 0.04, true, true);
        assertTrue("net climb expected, got motionY=" + climb.motionY, climb.motionY > 0);
        assertEquals(0.10 - 0.04, climb.motionY, DELTA);

        // thrustMag (0.02) < gravity (0.04) → underpowered, sinks even at full vertical.
        Step sink = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f),
                0.02, 0.04, true, true);
        assertTrue("underpowered must sink, got motionY=" + sink.motionY, sink.motionY < 0);
    }

    @Test
    public void yawInputRotatesYaw() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 10f, 0f,
                new FreeFlightInput(0f, 0f, 1f, 0f, 0f),
                THRUST, 0.0, true, true);
        assertEquals(10f + (float) FreeFlightPhysics.MAX_YAW_RATE, s.yaw, DELTA);
    }

    @Test
    public void pitchInputRotatesPitchClampedToMax() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, (float) (FreeFlightPhysics.PITCH_MAX - 1),
                new FreeFlightInput(0f, 0f, 0f, 1f, 0f),
                THRUST, 0.0, true, true);
        assertEquals((float) FreeFlightPhysics.PITCH_MAX, s.pitch, DELTA);
    }

    @Test
    public void pitchClampedBelowNegative() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, (float) (-FreeFlightPhysics.PITCH_MAX + 1),
                new FreeFlightInput(0f, 0f, 0f, -1f, 0f),
                THRUST, 0.0, true, true);
        assertEquals((float) -FreeFlightPhysics.PITCH_MAX, s.pitch, DELTA);
    }

    @Test
    public void cannotThrustDisablesThrustButStillRotatesAndApplyGravity() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 1f, 1f, 1f, 0f),
                THRUST, 0.05, /*canThrust=*/false, true);
        assertFalse("no fuel: no thrust", s.thrustApplied);
        assertEquals("forward thrust must not apply (motionX stayed 0)", 0.0, s.motionX, DELTA);
        assertEquals("vertical thrust must not apply, only gravity acts", -0.05, s.motionY, DELTA);
        // Yaw rotation still applies — orientation is independent of thrust.
        assertNotEquals(0f, s.yaw);
    }

    @Test
    public void brakeAttenuatesHorizontalMotion() {
        double startX = 1.0;
        Step s = FreeFlightPhysics.step(startX, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 1f),
                THRUST, 0.0, true, true);
        assertTrue("brake must shrink motionX magnitude", Math.abs(s.motionX) < startX);
    }

    @Test
    public void hardSpeedCapClampsMagnitudeToMaxSpeed() {
        Step s = FreeFlightPhysics.step(10, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                THRUST, 0.0, true, true);
        double speed = Math.sqrt(s.motionX * s.motionX
                + s.motionY * s.motionY + s.motionZ * s.motionZ);
        assertTrue("hard cap: speed must not exceed MAX_SPEED, got " + speed,
                speed <= FreeFlightPhysics.MAX_SPEED + DELTA);
    }

    @Test
    public void thrustAccelClampedToMaxThrustAccel() {
        // A wildly over-thrusted rocket (thrustMag far above the arcade ceiling)
        // is capped: forward motion can't exceed MAX_THRUST_ACCEL in one tick.
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                /*thrustMag=*/100.0, 0.0, true, true);
        assertEquals(FreeFlightPhysics.MAX_THRUST_ACCEL, s.motionZ, DELTA);
    }

    @Test
    public void shouldLandRequiresGroundAndSlowVertical() {
        assertTrue (FreeFlightPhysics.shouldLand(true,  0.0));
        assertTrue (FreeFlightPhysics.shouldLand(true,  0.04));
        assertTrue (FreeFlightPhysics.shouldLand(true, -0.04));
        assertFalse(FreeFlightPhysics.shouldLand(true,  1.0));
        assertFalse(FreeFlightPhysics.shouldLand(false, 0.0));
    }

    @Test
    public void forwardThrustAtNegativePitchProducesUpwardMotion() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, -45f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                THRUST, 0.0, true, true);
        assertTrue("pitch=-45 + forward thrust must lift, got motionY=" + s.motionY, s.motionY > 0);
        assertTrue("pitch=-45 must also push +Z, got motionZ=" + s.motionZ, s.motionZ > 0);
    }

    @Test
    public void forwardThrustAtPositivePitchProducesDownwardMotion() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 45f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                THRUST, 0.0, true, true);
        assertTrue("pitch=+45 + forward thrust must lower motionY, got " + s.motionY, s.motionY < 0);
    }

    @Test
    public void forwardThrustAtZeroPitchPreservesPureHorizontal() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                THRUST, 0.0, true, true);
        assertEquals("pitch=0 forward thrust must add zero Y", 0.0, s.motionY, DELTA);
        assertTrue("pitch=0 forward thrust must add +Z", s.motionZ > 0);
    }

    @Test
    public void verticalThrustIndependentOfPitch() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 60f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f),
                THRUST, 0.0, true, true);
        assertTrue("vertical thrust must lift independently of pitch=60°, got " + s.motionY,
                s.motionY > 0);
    }
}
