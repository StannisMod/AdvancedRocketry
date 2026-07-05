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
 *  - Vertical thrust at level flight raises motionY by exactly thrustMag (no gravity).
 *  - Climb gate: full vertical climbs iff thrustMag &gt; gravity.
 *  - Yaw/pitch rotate at MAX_*_RATE; pitch clamps to PITCH_MAX.
 *  - canThrust=false → no thrust applied; gravity + rotation still act.
 *  - Brake attenuates motion; hard speed cap clamps to MAX_SPEED.
 *  - Translation is body-relative: forward along the nose, strafe along the
 *    horizontal right axis, vertical along the nose's up axis (tilts with pitch).
 *  - Null input is tolerated (treated as zero).
 */
public class FreeFlightPhysicsTest {

    private static final double DELTA = 1e-6;

    /** A healthy gross thrust acceleration (well above default gravity). */
    private static final double THRUST = 0.10;

    @Test
    public void idleNoGravityNoMotionIsStable() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                THRUST, 0.0, true);
        assertEquals(0, s.motionX, DELTA);
        assertEquals(0, s.motionY, DELTA);
        assertEquals(0, s.motionZ, DELTA);
        assertEquals(0f, s.yaw, DELTA);
        assertEquals(0f, s.pitch, DELTA);
        assertFalse(s.thrustApplied);
    }

    @Test
    public void nullInputTreatedAsZero() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f, null, THRUST, 0.0, true);
        assertEquals(0, s.motionX, DELTA);
        assertEquals(0, s.motionY, DELTA);
        assertEquals(0, s.motionZ, DELTA);
        assertFalse(s.thrustApplied);
    }

    @Test
    public void gravityDrainsMotionYWhenIdle() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                THRUST, 0.05, true);
        assertEquals(-0.05, s.motionY, DELTA);
    }

    @Test
    public void forwardThrustMovesAlongYawByThrustMag() {
        // yaw=0 → forward vector = (-sin 0, cos 0) = (0, 1) → +Z by exactly thrustMag.
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                THRUST, 0.0, true);
        assertEquals(THRUST, s.motionZ, DELTA);
        assertEquals(0.0, s.motionX, DELTA);
        assertEquals(0.0, s.motionY, DELTA);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void forwardThrustAt90DegYawMovesAlongNegX() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 90f, 0f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                THRUST, 0.0, true);
        assertTrue("expected -X motion at yaw=90, got " + s.motionX, s.motionX < 0);
        assertEquals(0.0, s.motionZ, 1e-3); // cos 90 ≈ 0
    }

    @Test
    public void verticalThrustRaisesMotionYByThrustMag() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f),
                THRUST, 0.0, true);
        assertEquals(THRUST, s.motionY, DELTA);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void climbGateFullVerticalClimbsWhenThrustExceedsGravity() {
        // thrustMag (0.10) > gravity (0.04) → net positive climb.
        Step climb = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f),
                0.10, 0.04, true);
        assertTrue("net climb expected, got motionY=" + climb.motionY, climb.motionY > 0);
        assertEquals(0.10 - 0.04, climb.motionY, DELTA);

        // thrustMag (0.02) < gravity (0.04) → underpowered, sinks even at full vertical.
        Step sink = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f),
                0.02, 0.04, true);
        assertTrue("underpowered must sink, got motionY=" + sink.motionY, sink.motionY < 0);
    }

    @Test
    public void yawInputRotatesYaw() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 10f, 0f,
                new FreeFlightInput(0f, 0f, 1f, 0f, 0f),
                THRUST, 0.0, true);
        assertEquals(10f + (float) FreeFlightPhysics.MAX_YAW_RATE, s.yaw, DELTA);
    }

    @Test
    public void pitchInputRotatesPitchClampedToMax() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, (float) (FreeFlightPhysics.PITCH_MAX - 1),
                new FreeFlightInput(0f, 0f, 0f, 1f, 0f),
                THRUST, 0.0, true);
        assertEquals((float) FreeFlightPhysics.PITCH_MAX, s.pitch, DELTA);
    }

    @Test
    public void pitchClampedBelowNegative() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, (float) (-FreeFlightPhysics.PITCH_MAX + 1),
                new FreeFlightInput(0f, 0f, 0f, -1f, 0f),
                THRUST, 0.0, true);
        assertEquals((float) -FreeFlightPhysics.PITCH_MAX, s.pitch, DELTA);
    }

    @Test
    public void cannotThrustDisablesThrustButStillRotatesAndApplyGravity() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 1f, 1f, 1f, 0f),
                THRUST, 0.05, /*canThrust=*/false);
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
                THRUST, 0.0, true);
        assertTrue("brake must shrink motionX magnitude", Math.abs(s.motionX) < startX);
    }

    @Test
    public void hardSpeedCapClampsMagnitudeToMaxSpeed() {
        Step s = FreeFlightPhysics.step(10, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                THRUST, 0.0, true);
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
                /*thrustMag=*/100.0, 0.0, true);
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
                THRUST, 0.0, true);
        assertTrue("pitch=-45 + forward thrust must lift, got motionY=" + s.motionY, s.motionY > 0);
        assertTrue("pitch=-45 must also push +Z, got motionZ=" + s.motionZ, s.motionZ > 0);
    }

    @Test
    public void forwardThrustAtPositivePitchProducesDownwardMotion() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 45f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                THRUST, 0.0, true);
        assertTrue("pitch=+45 + forward thrust must lower motionY, got " + s.motionY, s.motionY < 0);
    }

    @Test
    public void forwardThrustAtZeroPitchPreservesPureHorizontal() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(1f, 0f, 0f, 0f, 0f),
                THRUST, 0.0, true);
        assertEquals("pitch=0 forward thrust must add zero Y", 0.0, s.motionY, DELTA);
        assertTrue("pitch=0 forward thrust must add +Z", s.motionZ > 0);
    }

    @Test
    public void verticalThrustAtZeroPitchIsPureWorldUp() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f),
                THRUST, 0.0, true);
        assertEquals("level: up-thrust is pure +Y", THRUST, s.motionY, DELTA);
        assertEquals("level: no Z from up-thrust", 0.0, s.motionZ, DELTA);
        assertEquals("level: no X from up-thrust", 0.0, s.motionX, DELTA);
    }

    @Test
    public void verticalThrustFollowsCraftUpAxisWhenPitched() {
        // Vertical is body-relative: along the nose's up axis, which tilts with
        // pitch. At pitch=60° (yaw=0) the up axis splits into world-up + forward.
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 60f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f),
                THRUST, 0.0, true);
        assertEquals("up-axis Y = thrust*cos(pitch)",
                THRUST * Math.cos(Math.toRadians(60)), s.motionY, DELTA);
        assertEquals("up-axis Z (forward lean) = thrust*sin(pitch)",
                THRUST * Math.sin(Math.toRadians(60)), s.motionZ, DELTA);
        assertTrue("tilted up-thrust lifts less than full vertical", s.motionY < THRUST);
    }

    @Test
    public void strafeThrustPushesAlongHorizontalRightAxis() {
        // yaw=0 → right axis is +X. Strafe is the 3rd float in the full constructor.
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 1f, 0f, 0f, 0f, false),
                THRUST, 0.0, true);
        assertEquals("strafe+ at yaw=0 → +X", THRUST, s.motionX, DELTA);
        assertEquals("strafe adds no Z at yaw=0", 0.0, s.motionZ, DELTA);
        assertEquals("strafe adds no Y", 0.0, s.motionY, DELTA);
    }

    @Test
    public void strafeStaysHorizontalIndependentOfPitch() {
        Step level = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 1f, 0f, 0f, 0f, false),
                THRUST, 0.0, true);
        Step pitched = FreeFlightPhysics.step(0, 0, 0, 0f, 70f,
                new FreeFlightInput(0f, 0f, 1f, 0f, 0f, 0f, false),
                THRUST, 0.0, true);
        assertEquals("strafe X must not change with pitch", level.motionX, pitched.motionX, DELTA);
        assertEquals("strafe stays horizontal (no Y) when pitched", 0.0, pitched.motionY, DELTA);
    }

    // ===== Mouse-as-rate steering ===========================

    @Test
    public void mouseRateIsOneToOneBelowTheTurnRate() {
        // A swipe smaller than the per-tick turn rate must come out as exactly
        // delta/max — integrated by the physics as rate*max, i.e. the nose turns
        // by exactly the swiped degrees (slip-free 1:1 feel below the cap).
        assertEquals(0.5, FreeFlightPhysics.rateFromMouseDelta(3.0, 6.0), DELTA);
        assertEquals(-0.25, FreeFlightPhysics.rateFromMouseDelta(-1.0, 4.0), DELTA);
        assertEquals(0.0, FreeFlightPhysics.rateFromMouseDelta(0.0, 6.0), DELTA);
    }

    @Test
    public void mouseRateSaturatesAtTheTurnRate() {
        // Faster swipes saturate at ±1 (the craft's max turn rate); the excess
        // is discarded, not queued — Elite-style rate limit.
        assertEquals(1.0, FreeFlightPhysics.rateFromMouseDelta(90.0, 6.0), DELTA);
        assertEquals(-1.0, FreeFlightPhysics.rateFromMouseDelta(-720.0, 4.0), DELTA);
        assertEquals(1.0, FreeFlightPhysics.rateFromMouseDelta(6.0 + 1e-9, 6.0), DELTA);
    }

    @Test
    public void mouseRateIsHygienicOnDegenerateInput() {
        assertEquals(0.0, FreeFlightPhysics.rateFromMouseDelta(Double.NaN, 6.0), DELTA);
        assertEquals(0.0, FreeFlightPhysics.rateFromMouseDelta(10.0, 0.0), DELTA);
        assertEquals(0.0, FreeFlightPhysics.rateFromMouseDelta(10.0, -1.0), DELTA);
    }

    // ===== Engine-start liftoff / hover assist ==============

    @Test
    public void liftoffClimbsGentlyTowardTheTarget() {
        // From rest on the pad, 1 block below the target: climb starts at the
        // capped gentle rate, never the raw thrust budget.
        Step s = FreeFlightPhysics.liftoffStep(64.0, 65.0, 0, 0, 0, 0f, 0f, THRUST);
        assertTrue("liftoff must climb (got " + s.motionY + ")", s.motionY > 0);
        assertTrue("climb must be gentle, capped at LIFTOFF_CLIMB_RATE",
                s.motionY <= FreeFlightPhysics.LIFTOFF_CLIMB_RATE + DELTA);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void liftoffSettlesAtTheTarget() {
        // Iterate position+velocity to convergence: the craft must end up
        // hovering at the target (no overshoot oscillation, near-zero motion).
        double y = 64.0, my = 0.0;
        for (int i = 0; i < 200; i++) {
            Step s = FreeFlightPhysics.liftoffStep(y, 65.0, 0, my, 0, 0f, 0f, THRUST);
            my = s.motionY;
            y += my;
        }
        assertEquals("must settle at the hover target", 65.0, y, 0.05);
        assertEquals("must hover with ~zero vertical speed", 0.0, my, 0.01);
    }

    @Test
    public void liftoffDescendsWhenAboveTheTarget() {
        Step s = FreeFlightPhysics.liftoffStep(70.0, 65.0, 0, 0, 0, 0f, 0f, THRUST);
        assertTrue("must ease DOWN toward the target (got " + s.motionY + ")",
                s.motionY < 0);
        assertTrue("descent capped at the gentle rate",
                s.motionY >= -FreeFlightPhysics.LIFTOFF_CLIMB_RATE - DELTA);
    }

    @Test
    public void liftoffDampsHorizontalDriftAndKeepsOrientation() {
        Step s = FreeFlightPhysics.liftoffStep(65.0, 65.0, 0.5, 0, -0.5, 33f, -12f, THRUST);
        assertTrue("X drift must shrink", Math.abs(s.motionX) < 0.5);
        assertTrue("Z drift must shrink", Math.abs(s.motionZ) < 0.5);
        assertEquals("yaw untouched by the assist", 33f, s.yaw, DELTA);
        assertEquals("pitch untouched by the assist", -12f, s.pitch, DELTA);
    }

    @Test
    public void liftoffVelocityChangeIsBoundedByThrustBudget() {
        // A feeble thrust budget bounds how fast the assist can change motion.
        double tiny = 0.01;
        Step s = FreeFlightPhysics.liftoffStep(64.0, 65.0, 0, 0, 0, 0f, 0f, tiny);
        assertTrue("dv per tick must be bounded by the thrust budget",
                s.motionY <= tiny + DELTA);
    }
}
