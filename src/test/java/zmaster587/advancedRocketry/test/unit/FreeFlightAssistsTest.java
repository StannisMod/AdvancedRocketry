package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract pinning for the Flight Assist velocity-setpoint control law
 *: {@link FreeFlightPhysics#rampSetpoint} +
 * {@link FreeFlightPhysics#faStep} + the body-frame transforms they rely on.
 * Pure math — no Minecraft types.
 *
 * The user-facing contracts:
 *  - holding a translation key RAMPS the setpoint; releasing KEEPS it;
 *  - X (cut) zeroes the whole setpoint instantly;
 *  - the setpoint lives in the BODY frame, so yawing the craft rotates the
 *    actual world velocity;
 *  - zero setpoint = hover (gravity compensated, motion driven to zero);
 *  - an under-powered craft honestly sags; no fuel = Newtonian brick;
 *  - any commanded thrust reports thrustApplied (&rarr; fuel burn), including
 *    the hover's gravity-cancelling burn.
 */
public class FreeFlightAssistsTest {

    private static final double DELTA = 1e-6;
    private static final double THRUST = 0.10;
    private static final double GRAV = 0.04;

    private static FreeFlightInput fwd(float v)  { return new FreeFlightInput(v, 0f, 0f, 0f, 0f, 0f, false); }
    private static FreeFlightInput cut()         { return new FreeFlightInput(0f, 0f, 0f, 0f, 0f, 0f, true); }

    // ===== rampSetpoint ====================================================

    @Test
    public void holdingForwardRampsTheSetpoint() {
        double[] sp = FreeFlightPhysics.rampSetpoint(0, 0, 0, fwd(1f));
        assertEquals(FreeFlightPhysics.SETPOINT_RAMP, sp[0], DELTA);
        assertEquals(0.0, sp[1], DELTA);
        assertEquals(0.0, sp[2], DELTA);
    }

    @Test
    public void releasingTheKeyKeepsTheSetpoint() {
        double[] sp = FreeFlightPhysics.rampSetpoint(1.25, -0.5, 0.75, FreeFlightInput.zero());
        assertEquals(1.25, sp[0], DELTA);
        assertEquals(-0.5, sp[1], DELTA);
        assertEquals(0.75, sp[2], DELTA);
    }

    @Test
    public void cutZeroesTheWholeSetpointInstantly() {
        double[] sp = FreeFlightPhysics.rampSetpoint(2.0, 1.0, -1.0, cut());
        assertEquals(0.0, sp[0], DELTA);
        assertEquals(0.0, sp[1], DELTA);
        assertEquals(0.0, sp[2], DELTA);
    }

    @Test
    public void rampReachesFullScaleInSixtyTicks() {
        double[] sp = {0, 0, 0};
        for (int i = 0; i < 60; i++) sp = FreeFlightPhysics.rampSetpoint(sp[0], sp[1], sp[2], fwd(1f));
        assertEquals(FreeFlightPhysics.FA_SETPOINT_MAX_SPEED, sp[0], 1e-9);
    }

    @Test
    public void setpointMagnitudeIsClampedToTheAssistCeiling() {
        double[] sp = {0, 0, 0};
        FreeFlightInput diag = new FreeFlightInput(1f, 1f, 1f, 0f, 0f, 0f, false);
        for (int i = 0; i < 300; i++) sp = FreeFlightPhysics.rampSetpoint(sp[0], sp[1], sp[2], diag);
        double mag = Math.sqrt(sp[0]*sp[0] + sp[1]*sp[1] + sp[2]*sp[2]);
        assertEquals(FreeFlightPhysics.FA_SETPOINT_MAX_SPEED, mag, 1e-9);
    }

    @Test
    public void rampIsHygienicOnNaNState() {
        double[] sp = FreeFlightPhysics.rampSetpoint(Double.NaN, 0, 0, fwd(1f));
        assertEquals(FreeFlightPhysics.SETPOINT_RAMP, sp[0], DELTA);
    }

    // ===== body frame ======================================================

    @Test
    public void bodyToWorldRoundTripsThroughWorldToBody() {
        double[] w = FreeFlightPhysics.bodyToWorld(0.8, -0.3, 0.5, 123f, -37f);
        double[] b = FreeFlightPhysics.worldToBody(w[0], w[1], w[2], 123f, -37f);
        assertEquals(0.8, b[0], DELTA);
        assertEquals(-0.3, b[1], DELTA);
        assertEquals(0.5, b[2], DELTA);
    }

    @Test
    public void forwardSetpointAtYawZeroIsPlusZ() {
        double[] w = FreeFlightPhysics.bodyToWorld(1.0, 0, 0, 0f, 0f);
        assertEquals(0.0, w[0], DELTA);
        assertEquals(0.0, w[1], DELTA);
        assertEquals(1.0, w[2], DELTA);
    }

    @Test
    public void yawingTheCraftRotatesTheWorldVelocityOfTheSameSetpoint() {
        // The user-specified contract: the setpoint is body-frame, so a 90-deg
        // yaw turns the same "forward 1.0" intent from +Z into -X.
        double[] w0  = FreeFlightPhysics.bodyToWorld(1.0, 0, 0, 0f, 0f);
        double[] w90 = FreeFlightPhysics.bodyToWorld(1.0, 0, 0, 90f, 0f);
        assertEquals(1.0, w0[2], DELTA);
        assertEquals(-1.0, w90[0], DELTA);
        assertEquals(0.0, w90[2], 1e-9);
    }

    // ===== faStep ==========================================================

    @Test
    public void zeroSetpointHoversCancellingGravity() {
        // At rest with zero setpoint: FA burns to cancel gravity; motion stays 0.
        Step s = FreeFlightPhysics.faStep(0, 0, 0, 0f, 0f, 0, 0, 0, THRUST, GRAV, true);
        assertEquals(0.0, s.motionX, DELTA);
        assertEquals(0.0, s.motionY, DELTA);
        assertEquals(0.0, s.motionZ, DELTA);
        assertTrue("hover burns fuel (gravity cancel)", s.thrustApplied);
    }

    @Test
    public void zeroSetpointDampsExistingMotionTowardHover() {
        double mx = 0.4, my = 0.2, mz = -0.4;
        for (int i = 0; i < 200; i++) {
            Step s = FreeFlightPhysics.faStep(mx, my, mz, 0f, 0f, 0, 0, 0, THRUST, GRAV, true);
            mx = s.motionX; my = s.motionY; mz = s.motionZ;
        }
        assertEquals(0.0, mx, 1e-3);
        assertEquals(0.0, my, 1e-3);
        assertEquals(0.0, mz, 1e-3);
    }

    @Test
    public void velocityConvergesToTheSetpoint() {
        // Setpoint forward 1.0 at yaw 0 -> world +Z 1.0; iterate to convergence.
        double mx = 0, my = 0, mz = 0;
        for (int i = 0; i < 200; i++) {
            Step s = FreeFlightPhysics.faStep(mx, my, mz, 0f, 0f, 1.0, 0, 0, THRUST, GRAV, true);
            mx = s.motionX; my = s.motionY; mz = s.motionZ;
        }
        assertEquals("must cruise at the setpoint", 1.0, mz, 1e-3);
        assertEquals("no vertical drift while cruising", 0.0, my, 1e-3);
    }

    @Test
    public void commandedAccelerationIsBoundedByTheThrustBudget() {
        // Huge error, small budget: one tick may change motion by at most accel.
        Step s = FreeFlightPhysics.faStep(0, 0, 0, 0f, 0f, 3.0, 0, 0, 0.02, 0.0, true);
        double dv = Math.sqrt(s.motionX*s.motionX + s.motionY*s.motionY + s.motionZ*s.motionZ);
        assertTrue("dv " + dv + " must be <= budget 0.02", dv <= 0.02 + DELTA);
    }

    @Test
    public void underPoweredCraftHonestlySags() {
        // Budget below gravity: even a zero setpoint can't hold altitude.
        Step s = FreeFlightPhysics.faStep(0, 0, 0, 0f, 0f, 0, 0, 0, 0.01, GRAV, true);
        assertTrue("must sag when budget < gravity, got " + s.motionY, s.motionY < 0);
    }

    @Test
    public void noFuelMeansNewtonianBrick() {
        Step s = FreeFlightPhysics.faStep(0.5, 0.0, 0.0, 0f, 0f, 0, 0, 1.0, THRUST, GRAV, false);
        assertEquals("coasts, no damping", 0.5, s.motionX, DELTA);
        assertEquals("gravity only", -GRAV, s.motionY, DELTA);
        assertFalse(s.thrustApplied);
    }

    @Test
    public void cruisingExactlyAtTheSetpointStillBurnsForGravity() {
        // Velocity == setpoint: the only command left is the gravity cancel.
        Step s = FreeFlightPhysics.faStep(0, 0, 1.0, 0f, 0f, 1.0, 0, 0, THRUST, GRAV, true);
        assertEquals(1.0, s.motionZ, DELTA);
        assertEquals(0.0, s.motionY, DELTA);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void faStepEchoesOrientationUntouched() {
        Step s = FreeFlightPhysics.faStep(0, 0, 0, 77f, -42f, 0, 0, 0, THRUST, GRAV, true);
        assertEquals(77f, s.yaw, DELTA);
        assertEquals(-42f, s.pitch, DELTA);
    }

    /**
     * Switching the assist ON while flying faster than it can be asked for must not rewrite the
     * craft's velocity: FA slows it down with the thrust it has, one budget per tick, like anything
     * else. The assist's ceiling binds the SETPOINT (pinned above), never the motion.
     *
     * <p>This is the leg that separates "the ceiling moved onto the setpoint" from "the ceiling is
     * still on the velocity, one call later": a clamping build brings 100 blocks/tick back to 3 in a
     * single step, which is a stop no engine paid for.</p>
     */
    @Test
    public void faDeceleratesAnOverfastCraftAtItsThrustBudget() {
        double entrySpeed = 100.0;
        double budget = 0.5;
        Step s = FreeFlightPhysics.faStep(0, 0, entrySpeed, 0f, 0f, 0, 0, 0, budget, 0.0, true);
        double speed = Math.sqrt(s.motionX*s.motionX + s.motionY*s.motionY + s.motionZ*s.motionZ);
        assertEquals("FA must shed exactly the thrust budget, not the whole overspeed",
                entrySpeed - budget, speed, DELTA);
    }
}
