package zmaster587.advancedRocketry.test.unit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.FreeFlightPhysics.Step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Flight-assist contracts (Option A): Stop, Flight Assist toggle, Hover Hold.
 *
 * Wire format:
 *  - FreeFlightInput grows from 20 → 21 bytes (5 floats + 1 flag byte).
 *  - Flag bits: 0x01 = stopActive, 0x02 = hoverActive.
 *
 * Physics:
 *  - Stop overrides forward/vertical thrust with counter-thrust along -motion.
 *  - Hover hold cancels gravity for the tick when fuel available.
 *  - FA off skips idle-drag (motion persists across ticks for coast).
 *  - FA off does NOT skip gravity, brake, or speed cap (those are safety / pilot intent).
 */
public class FreeFlightAssistsTest {

    private static final double DELTA = 1e-6;
    private static final int    DEFAULT_THRUST = 50000;
    private static final double DEFAULT_MASS = 10000.0;

    // ===== Wire =========================================================

    @Test
    public void wireSizeGrewToTwentyOneBytesForFlagByte() {
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
        // Assists count as non-idle so idle-drag doesn't fire while pilot is
        // actively requesting stop / hover.
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
    public void stopApplyCounterThrustAndDrainsFuel() {
        // Rocket moving at (1, 0, 0); Stop drives motion toward 0.
        Step s = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, true, false),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertTrue("Stop must reduce horizontal magnitude, got mx=" + s.motionX,
                Math.abs(s.motionX) < 1.0);
        assertTrue("Stop must consume fuel when applying counter-thrust",
                s.fuelConsumed > 0);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void stopOverridesForwardAndVerticalChannels() {
        // Pilot pressed Stop AND held forward+vertical → Stop wins, motion
        // shrinks rather than grows.
        Step s = FreeFlightPhysics.step(1.0, 1.0, 0, 0f, 0f,
                new FreeFlightInput(1f, 1f, 0f, 0f, 0f, true, false),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        double speedAfter = Math.sqrt(s.motionX * s.motionX + s.motionY * s.motionY);
        assertTrue("Stop with conflicting fwd/vert input must still shrink motion, got speed="
                + speedAfter, speedAfter < Math.sqrt(2.0));
    }

    @Test
    public void stopSnapToZeroAtVerySlowMotion() {
        // Just above the snap threshold — Stop must round to absolute zero.
        Step s = FreeFlightPhysics.step(0.005, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, true, false),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertEquals(0, s.motionX, DELTA);
        assertEquals(0, s.motionY, DELTA);
        assertEquals(0, s.motionZ, DELTA);
    }

    @Test
    public void stopWithoutFuelDoesNotApplyCounterThrust() {
        Step s = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, true, false),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, /*fuel=*/0);
        // Motion unchanged (gravity also 0 in this test).
        assertEquals(1.0, s.motionX, DELTA);
        assertFalse(s.thrustApplied);
        assertEquals(0, s.fuelConsumed);
    }

    @Test
    public void stopDoesNotOvershootIntoReverseMotion() {
        // Very slow motion + Stop assist; counter-thrust capped at speed itself.
        // The post-Stop motion must be at or near zero in the same direction,
        // never negative.
        Step s = FreeFlightPhysics.step(0.05, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, true, false),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        assertTrue("Stop must not flip motionX into reverse, got mx=" + s.motionX,
                s.motionX >= -DELTA);
    }

    // ===== Hover ========================================================

    @Test
    public void hoverHoldCancelsGravityWhenFuelAvailable() {
        // No vertical throttle + hover active + gravity → motionY stays at the
        // initial value (hover cancels gravity exactly).
        Step s = FreeFlightPhysics.step(0, 0.0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, false, true),
                DEFAULT_THRUST, DEFAULT_MASS, 0.04, 1000);
        assertEquals("hover hold must zero out gravity tick", 0.0, s.motionY, DELTA);
        assertTrue("hover hold must consume fuel", s.fuelConsumed > 0);
        assertTrue(s.thrustApplied);
    }

    @Test
    public void hoverHoldWithoutFuelLetsGravityActNormally() {
        Step s = FreeFlightPhysics.step(0, 0.0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 0f, false, true),
                DEFAULT_THRUST, DEFAULT_MASS, 0.04, /*fuel=*/0);
        assertEquals("no fuel: hover silently fails, gravity drains motionY",
                -0.04, s.motionY, DELTA);
        assertFalse(s.thrustApplied);
        assertEquals(0, s.fuelConsumed);
    }

    @Test
    public void hoverAndVerticalThrustStack() {
        // Hover cancels gravity; vert input still climbs additionally.
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 1f, 0f, 0f, 0f, false, true),
                DEFAULT_THRUST, DEFAULT_MASS, 0.04, 1000);
        assertTrue("hover + vert+1 must rise: motionY > 0, got " + s.motionY,
                s.motionY > 0);
    }

    // ===== Flight Assist toggle =========================================

    @Test
    public void flightAssistOnAppliesIdleDrag() {
        // No input, FA on → idle drag attenuates horizontal motion.
        Step s = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000, /*FA=*/true);
        assertTrue("FA on + idle: motionX must attenuate below initial 1.0",
                s.motionX < 1.0);
    }

    @Test
    public void flightAssistOffSkipsIdleDragSoMotionPersists() {
        // No input, FA off → motion persists (Newtonian coast).
        Step s = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000, /*FA=*/false);
        assertEquals("FA off + idle: motionX must NOT attenuate (Newtonian)",
                1.0, s.motionX, DELTA);
    }

    @Test
    public void flightAssistOffStillRespectsBrakeInput() {
        // FA off but pilot presses brake → brake STILL works (explicit pilot intent).
        Step s = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f,
                new FreeFlightInput(0f, 0f, 0f, 0f, 1f),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000, /*FA=*/false);
        assertTrue("FA off must NOT disable explicit brake input, got mx=" + s.motionX,
                s.motionX < 1.0);
    }

    @Test
    public void flightAssistOffStillAppliesGravity() {
        // Safety: FA off doesn't make the rocket immune to gravity (otherwise
        // landing detection breaks and the rocket floats forever).
        Step s = FreeFlightPhysics.step(0, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                DEFAULT_THRUST, DEFAULT_MASS, 0.05, 1000, /*FA=*/false);
        assertEquals("FA off must still apply gravity", -0.05, s.motionY, DELTA);
    }

    @Test
    public void flightAssistOffStillRespectsSpeedCap() {
        // Safety cap is always on, FA off or on — protects from physics blow-up.
        Step s = FreeFlightPhysics.step(10, 0, 0, 0f, 0f, FreeFlightInput.zero(),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000, /*FA=*/false);
        double speed = Math.sqrt(s.motionX * s.motionX
                + s.motionY * s.motionY + s.motionZ * s.motionZ);
        assertTrue("FA off must still respect MAX_SPEED cap, got " + speed,
                speed <= FreeFlightPhysics.MAX_SPEED + DELTA);
    }

    // ===== Back-compat overload =========================================

    @Test
    public void stepOverloadWithoutFlightAssistDefaultsToOn() {
        // The 10-arg overload (no FA param) is back-compat for existing call
        // sites and unit tests. It MUST behave as if FA=true.
        Step withDefault = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f,
                FreeFlightInput.zero(),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000);
        Step withExplicitOn = FreeFlightPhysics.step(1.0, 0, 0, 0f, 0f,
                FreeFlightInput.zero(),
                DEFAULT_THRUST, DEFAULT_MASS, 0.0, 1000, /*FA=*/true);
        assertEquals(withDefault.motionX, withExplicitOn.motionX, DELTA);
        assertEquals(withDefault.motionY, withExplicitOn.motionY, DELTA);
        assertEquals(withDefault.motionZ, withExplicitOn.motionZ, DELTA);
    }
}
