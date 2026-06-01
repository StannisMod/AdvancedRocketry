package zmaster587.advancedRocketry.test.unit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import zmaster587.advancedRocketry.api.FreeFlightInput;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link FreeFlightInput} contract: clamping at construction, ByteBuf wire round-trip,
 * defensive handling of malicious / corrupt inputs.
 *
 * Contracts pinned:
 *  - Per-channel clamp to [-1, +1].
 *  - NaN / Infinity collapse to 0 (defensive against client cheats).
 *  - Wire size is fixed: WIRE_SIZE = 20 bytes (5 floats × 4).
 *  - Round-trip via write→read preserves all channels exactly.
 *  - Out-of-range values from the wire are re-clamped on read (server is source of truth).
 *  - isIdle() true only when every channel ≈ 0.
 */
public class FreeFlightInputTest {

    private static final float EPS = 1e-6f;

    @Test
    public void zeroFactoryYieldsIdle() {
        FreeFlightInput z = FreeFlightInput.zero();
        assertTrue(z.isIdle());
        assertEquals(0f, z.throttleForward,  EPS);
        assertEquals(0f, z.throttleVertical, EPS);
        assertEquals(0f, z.yawInput,         EPS);
        assertEquals(0f, z.pitchInput,       EPS);
        assertEquals(0f, z.brakeInput,       EPS);
    }

    @Test
    public void constructorClampsAboveOne() {
        FreeFlightInput in = new FreeFlightInput(5f, 2.5f, 1.000001f, 100f, 7f);
        assertEquals( 1f, in.throttleForward,  EPS);
        assertEquals( 1f, in.throttleVertical, EPS);
        assertEquals( 1f, in.yawInput,         EPS);
        assertEquals( 1f, in.pitchInput,       EPS);
        assertEquals( 1f, in.brakeInput,       EPS);
    }

    @Test
    public void constructorClampsBelowMinusOne() {
        FreeFlightInput in = new FreeFlightInput(-5f, -2.5f, -1.000001f, -100f, -7f);
        assertEquals(-1f, in.throttleForward,  EPS);
        assertEquals(-1f, in.throttleVertical, EPS);
        assertEquals(-1f, in.yawInput,         EPS);
        assertEquals(-1f, in.pitchInput,       EPS);
        assertEquals(-1f, in.brakeInput,       EPS);
    }

    @Test
    public void constructorCollapsesNanAndInfinityToZero() {
        FreeFlightInput in = new FreeFlightInput(
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.NaN, Float.POSITIVE_INFINITY);
        assertTrue(in.isIdle());
    }

    @Test
    public void clampStaticHelperBehaviour() {
        assertEquals(  0f, FreeFlightInput.clamp(Float.NaN),               EPS);
        assertEquals(  0f, FreeFlightInput.clamp(Float.POSITIVE_INFINITY), EPS);
        assertEquals( -1f, FreeFlightInput.clamp(-50f),                    EPS);
        assertEquals(  1f, FreeFlightInput.clamp(50f),                     EPS);
        assertEquals(0.5f, FreeFlightInput.clamp(0.5f),                    EPS);
    }

    @Test
    public void wireSizeIs20Bytes() {
        assertEquals(20, FreeFlightInput.WIRE_SIZE);
        ByteBuf buf = Unpooled.buffer();
        new FreeFlightInput(0.5f, -0.5f, 0.25f, -0.25f, 1f).write(buf);
        assertEquals(FreeFlightInput.WIRE_SIZE, buf.writerIndex());
    }

    @Test
    public void wireRoundTripPreservesAllChannels() {
        FreeFlightInput orig = new FreeFlightInput(0.7f, -0.3f, 0.1f, -0.8f, 0.5f);
        ByteBuf buf = Unpooled.buffer();
        orig.write(buf);
        FreeFlightInput rt = FreeFlightInput.read(buf);
        assertEquals(orig, rt);
    }

    @Test
    public void readReclampsOutOfRangeWireValues() {
        // A malicious / buggy client could write floats > 1.0 directly. Server-side
        // read must re-clamp because clients are not authoritative.
        ByteBuf buf = Unpooled.buffer();
        buf.writeFloat(5.0f);
        buf.writeFloat(-5.0f);
        buf.writeFloat(Float.NaN);
        buf.writeFloat(Float.POSITIVE_INFINITY);
        buf.writeFloat(2.0f);
        FreeFlightInput in = FreeFlightInput.read(buf);
        assertEquals( 1f, in.throttleForward,  EPS);
        assertEquals(-1f, in.throttleVertical, EPS);
        assertEquals( 0f, in.yawInput,         EPS); // NaN → 0
        assertEquals( 0f, in.pitchInput,       EPS); // Inf → 0
        assertEquals( 1f, in.brakeInput,       EPS);
    }

    @Test
    public void isIdleFalseWhenAnyChannelNonZero() {
        assertFalse(new FreeFlightInput(0.01f, 0f, 0f, 0f, 0f).isIdle());
        assertFalse(new FreeFlightInput(0f, 0.01f, 0f, 0f, 0f).isIdle());
        assertFalse(new FreeFlightInput(0f, 0f, 0.01f, 0f, 0f).isIdle());
        assertFalse(new FreeFlightInput(0f, 0f, 0f, 0.01f, 0f).isIdle());
        assertFalse(new FreeFlightInput(0f, 0f, 0f, 0f, 0.01f).isIdle());
    }

    @Test
    public void equalsAndHashCodeAlignWithFields() {
        FreeFlightInput a = new FreeFlightInput(0.5f, -0.5f, 0.25f, -0.25f, 0f);
        FreeFlightInput b = new FreeFlightInput(0.5f, -0.5f, 0.25f, -0.25f, 0f);
        FreeFlightInput c = new FreeFlightInput(0.5f, -0.5f, 0.25f, -0.25f, 0.1f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
