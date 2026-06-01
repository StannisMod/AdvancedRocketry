package zmaster587.advancedRocketry.api;

import io.netty.buffer.ByteBuf;

/**
 * Per-tick pilot intent for a rocket in {@link RocketFlightMode#FREE_FLIGHT}.
 *
 * All channels are normalised to [-1.0, +1.0]. The server clamps on read and
 * is the source of truth — client values are treated as intent, never as
 * authoritative state.
 *
 * Wire format: five 32-bit floats, big-endian (matches netty ByteBuf default).
 */
public final class FreeFlightInput {

    public static final float MIN = -1.0f;
    public static final float MAX = 1.0f;

    /** Number of bytes a FreeFlightInput occupies on the wire. */
    public static final int WIRE_SIZE = 5 * 4;

    public final float throttleForward;
    public final float throttleVertical;
    public final float yawInput;
    public final float pitchInput;
    public final float brakeInput;

    public FreeFlightInput(float throttleForward,
                           float throttleVertical,
                           float yawInput,
                           float pitchInput,
                           float brakeInput) {
        this.throttleForward  = clamp(throttleForward);
        this.throttleVertical = clamp(throttleVertical);
        this.yawInput         = clamp(yawInput);
        this.pitchInput       = clamp(pitchInput);
        this.brakeInput       = clamp(brakeInput);
    }

    public static FreeFlightInput zero() {
        return new FreeFlightInput(0f, 0f, 0f, 0f, 0f);
    }

    /** Clamp a single channel to [MIN, MAX]; NaN/Inf collapse to 0. */
    public static float clamp(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0f;
        if (v < MIN) return MIN;
        if (v > MAX) return MAX;
        return v;
    }

    public void write(ByteBuf out) {
        out.writeFloat(throttleForward);
        out.writeFloat(throttleVertical);
        out.writeFloat(yawInput);
        out.writeFloat(pitchInput);
        out.writeFloat(brakeInput);
    }

    /** Read + clamp; tolerant of malicious clients sending out-of-range floats. */
    public static FreeFlightInput read(ByteBuf in) {
        float f = in.readFloat();
        float v = in.readFloat();
        float y = in.readFloat();
        float p = in.readFloat();
        float b = in.readFloat();
        return new FreeFlightInput(f, v, y, p, b);
    }

    /** True when every channel is effectively zero (within fp epsilon). */
    public boolean isIdle() {
        return Math.abs(throttleForward)  < 1e-5f
            && Math.abs(throttleVertical) < 1e-5f
            && Math.abs(yawInput)         < 1e-5f
            && Math.abs(pitchInput)       < 1e-5f
            && Math.abs(brakeInput)       < 1e-5f;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FreeFlightInput)) return false;
        FreeFlightInput i = (FreeFlightInput) o;
        return Float.compare(throttleForward,  i.throttleForward)  == 0
            && Float.compare(throttleVertical, i.throttleVertical) == 0
            && Float.compare(yawInput,         i.yawInput)         == 0
            && Float.compare(pitchInput,       i.pitchInput)       == 0
            && Float.compare(brakeInput,       i.brakeInput)       == 0;
    }

    @Override
    public int hashCode() {
        int h = Float.floatToIntBits(throttleForward);
        h = 31 * h + Float.floatToIntBits(throttleVertical);
        h = 31 * h + Float.floatToIntBits(yawInput);
        h = 31 * h + Float.floatToIntBits(pitchInput);
        h = 31 * h + Float.floatToIntBits(brakeInput);
        return h;
    }

    @Override
    public String toString() {
        return String.format("FreeFlightInput{fwd=%.3f vert=%.3f yaw=%.3f pitch=%.3f brake=%.3f}",
                throttleForward, throttleVertical, yawInput, pitchInput, brakeInput);
    }
}
