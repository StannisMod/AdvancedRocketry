package zmaster587.advancedRocketry.test.unit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Flight-assist contracts (Option A): Stop, Flight Assist toggle, Hover Hold.
 *
 * Wire format:
 *  - FreeFlightInput is 21 bytes (5 floats + 1 flag byte).
 *  - Flag bits: 0x01 = stopActive, 0x02 = hoverActive.
 *
 * Physics (post TASK: thrust magnitude + canThrust passed in, fuel accounted by
 * the caller):
 *  - Stop overrides forward/vertical thrust with counter-thrust along -motion.
 *  - Hover hold cancels gravity for the tick when thrust is available.
 *  - FA off skips idle-drag (motion persists across ticks for coast).
 *  - FA off does NOT skip gravity, brake, or speed cap (safety / pilot intent).
 */
public class FreeFlightAssistsTest {

    private static final double DELTA = 1e-6;
    /** A healthy gross thrust acceleration. */
    private static final double THRUST = 0.10;

    // ===== Wire =========================================================

    @Test
    public void wireSizeIsTwentyOneBytesForFlagByte() {
        assertEquals(21, FreeFlightInput.WIRE_SIZE);
        ByteBuf buf = Unpooled.buffer();
        new FreeFlightInput(0.5f, -0.5f, 0.25f, -0.25f, 1f, true, true).write(buf);
        assertEquals(FreeFlightInput.WIRE_SIZE, buf.writerIndex());
    }

    @Test
    public void wireRoundTripPreservesStopAndHoverFlags() {
        FreeFlightInput orig = new FreeFlightInput(0.7f, -0.3f, 0.1f, -0.8f, 0.5f, true, false);
        ByteBuf buf = Unpooled.buffer();
        orig.write(buf);
        FreeFlightInput rt = FreeFlightInput.read(buf);
        assertEquals(orig, rt);
        assertTrue(rt.stopActive);
        assertFalse(rt.hoverActive);

        FreeFlightInput orig2 = new FreeFlightInput(0f, 0f, 0f, 0f, 0f, false, true);
        ByteBuf buf2 = Unpooled.buffer();
        orig2.write(buf2);
        FreeFlightInput rt2 = FreeFlightInput.read(buf2);
        assertFalse(rt2.stopActive);
        assertTrue(rt2.hoverActive);
    }

    @Test
    public void zeroFactoryHasNoAssistsActive() {
        FreeFlightInput z = FreeFlightInput.zero();
        assertFalse(z.stopActive);
        assertFalse(z.hoverActive);
        assertTrue(z.isIdle());
    }

    @Test
    public void isIdleFalseWhenAnyAssistActive() {
        assertFalse(new FreeFlightInput(0f, 0f, 0f, 0f, 0f, true,  false).isIdle());
        assertFalse(new FreeFlightInput(0f, 0f, 0f, 0f, 0f, false, true ).isIdle());
    }

    @Test
    public void backCompatConstructorDefaultsAssistsOff() {
        FreeFlightInput legacy = new FreeFlightInput(1f, 0f, 0f, 0f, 0f);
        assertFalse(legacy.stopActive);
        assertFalse(legacy.hoverActive);
    }

    // ===== Stop =========================================================

    @Test
    public void stopAppliesCounterThrust() {
        Step s = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, true, false),
                THRUST, 0.0, true, true);
        assertTrue("Stop must reduce horizontal magnitude, got mx=" + s.motionX,
                Math.abs(s.motionX) < 1.0);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void stopOverridesForwardAndVerticalChannels() {
        Step s = FreeFlightPhysics.step(1.0, 1.0, 0, 0f, 0f,
                new FreeFlightInput(1f, 1f, 0f, 0f, 0f, true, false),
                THRUST, 0.0, true, true);
        double speedAfter = Math.sqrt(s.motionX * s.motionX + s.motionY * s.motionY);
        assertTrue("Stop must still shrink motion despite fwd/vert input, got speed="
                + speedAfter, speedAfter < Math.sqrt(2.0));
    }

    @Test
    public void stopSnapToZeroAtVerySlowMotion() {
        Step s = FreeFlightPhysics.step(0.005, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, true, false),
                THRUST, 0.0, true, true);
        assertEquals(0, s.motionX, DELTA);
        assertEquals(0, s.motionY, DELTA);
        assertEquals(0, s.motionZ, DELTA);
    }

    @Test
    public void stopWithoutThrustDoesNotApplyCounterThrust() {
        Step s = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, true, false),
                THRUST, 0.0, /*canThrust=*/false, true);
        assertEquals(1.0, s.motionX, DELTA);
        assertFalse(s.thrustApplied);
    }

    @Test
    public void stopDoesNotOvershootIntoReverseMotion() {
        Step s = FreeFlightPhysics.step(0.05, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, true, false),
                THRUST, 0.0, true, true);
        assertTrue("Stop must not flip motionX into reverse, got mx=" + s.motionX,
                s.motionX >= -DELTA);
    }

    // ===== Hover ========================================================

    @Test
    public void hoverHoldCancelsGravityWhenThrustAvailable() {
        Step s = FreeFlightPhysics.step(0, 0.0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, false, true),
                THRUST, 0.04, true, true);
        assertEquals("hover hold must zero out gravity tick", 0.0, s.motionY, DELTA);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void hoverHoldWithoutThrustLetsGravityActNormally() {
        Step s = FreeFlightPhysics.step(0, 0.0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, false, true),
                THRUST, 0.04, /*canThrust=*/false, true);
        assertEquals("no fuel: hover silently fails, gravity drains motionY",
                -0.04, s.motionY, DELTA);
        assertFalse(s.thrustApplied);
    }

    @Test
    public void hoverAndVerticalThrustStack() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f, false, true),
                THRUST, 0.04, true, true);
        assertTrue("hover + vert+1 must rise: motionY > 0, got " + s.motionY, s.motionY > 0);
    }

    // ===== Flight Assist toggle =========================================

    @Test
    public void flightAssistOnAppliesIdleDrag() {
        Step s = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                THRUST, 0.0, true, /*FA=*/true);
        assertTrue("FA on + idle: motionX must attenuate below initial 1.0", s.motionX < 1.0);
    }

    @Test
    public void flightAssistOffSkipsIdleDragSoMotionPersists() {
        Step s = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                THRUST, 0.0, true, /*FA=*/false);
        assertEquals("FA off + idle: motionX must NOT attenuate (Newtonian)",
                1.0, s.motionX, DELTA);
    }

    @Test
    public void flightAssistOffStillRespectsBrakeInput() {
        Step s = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 1f),
                THRUST, 0.0, true, /*FA=*/false);
        assertTrue("FA off must NOT disable explicit brake input, got mx=" + s.motionX,
                s.motionX < 1.0);
    }

    @Test
    public void flightAssistOffStillAppliesGravity() {
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                THRUST, 0.05, true, /*FA=*/false);
        assertEquals("FA off must still apply gravity", -0.05, s.motionY, DELTA);
    }

    @Test
    public void flightAssistOffStillRespectsSpeedCap() {
        Step s = FreeFlightPhysics.step(10, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                THRUST, 0.0, true, /*FA=*/false);
        double speed = Math.sqrt(s.motionX * s.motionX
                + s.motionY * s.motionY + s.motionZ * s.motionZ);
        assertTrue("FA off must still respect MAX_SPEED cap, got " + speed,
                speed <= FreeFlightPhysics.MAX_SPEED + DELTA);
    }
}
