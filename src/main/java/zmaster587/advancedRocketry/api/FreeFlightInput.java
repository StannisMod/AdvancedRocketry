package zmaster587.advancedRocketry.api;

import io.netty.buffer.ByteBuf;

/**
 * Per-tick pilot intent for a rocket in {@link RocketFlightMode#FREE_FLIGHT}.
 *
 * All channels are normalised to [-1.0, +1.0]. The server clamps on read and
 * is the source of truth — client values are treated as intent, never as
 * authoritative state.
 *
 * <p>Translation is expressed in the craft's body frame: {@link #throttleForward}
 * along the nose, {@link #strafeInput} along the nose's right axis, and
 * {@link #throttleVertical} along the nose's up axis (all derived from yaw+pitch
 * in {@link FreeFlightPhysics}). Yaw/pitch are orientation-rate channels.
 *
 * Wire format: six 32-bit floats (fwd, vert, strafe, yaw, pitch, brake),
 * big-endian, followed by one flag byte (stop, hover).
 */
public final class FreeFlightInput {

    public static final float MIN = -1.0f;
    public static final float MAX = 1.0f;

    /** Flight-assist bit-flags packed into the final wire byte (Option A). */
    private static final int FLAG_STOP  = 0x01;
    private static final int FLAG_HOVER = 0x02;

    /** Wire size: 6 floats + 1 flag byte. */
    public static final int WIRE_SIZE = 6 * 4 + 1;

    public final float throttleForward;
    public final float throttleVertical;
    /** Lateral (strafe) thrust along the nose's right axis. +1 = right, -1 = left. */
    public final float strafeInput;
    public final float yawInput;
    public final float pitchInput;
    public final float brakeInput;
    /** Stop assist — server applies counter-thrust along -motion until |v|≈0. */
    public final boolean stopActive;
    /** Hover hold — server gradually damps motion to zero and holds altitude. */
    public final boolean hoverActive;

    /** Legacy 5-channel constructor (no strafe / assists). */
    public FreeFlightInput(float throttleForward,
                           float throttleVertical,
                           float yawInput,
                           float pitchInput,
                           float brakeInput) {
        this(throttleForward, throttleVertical, 0f, yawInput, pitchInput, brakeInput, false, false);
    }

    /** Legacy 5-channel + assists constructor (no strafe). */
    public FreeFlightInput(float throttleForward,
                           float throttleVertical,
                           float yawInput,
                           float pitchInput,
                           float brakeInput,
                           boolean stopActive,
                           boolean hoverActive) {
        this(throttleForward, throttleVertical, 0f, yawInput, pitchInput, brakeInput, stopActive, hoverActive);
    }

    /** Full constructor including the strafe channel. */
    public FreeFlightInput(float throttleForward,
                           float throttleVertical,
                           float strafeInput,
                           float yawInput,
                           float pitchInput,
                           float brakeInput,
                           boolean stopActive,
                           boolean hoverActive) {
        this.throttleForward  = clamp(throttleForward);
        this.throttleVertical = clamp(throttleVertical);
        this.strafeInput      = clamp(strafeInput);
        this.yawInput         = clamp(yawInput);
        this.pitchInput       = clamp(pitchInput);
        this.brakeInput       = clamp(brakeInput);
        this.stopActive       = stopActive;
        this.hoverActive      = hoverActive;
    }

    public static FreeFlightInput zero() {
        return new FreeFlightInput(0f, 0f, 0f, 0f, 0f, 0f, false, false);
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
        out.writeFloat(strafeInput);
        out.writeFloat(yawInput);
        out.writeFloat(pitchInput);
        out.writeFloat(brakeInput);
        int flags = 0;
        if (stopActive)  flags |= FLAG_STOP;
        if (hoverActive) flags |= FLAG_HOVER;
        out.writeByte(flags);
    }

    /** Read + clamp; tolerant of malicious clients sending out-of-range floats. */
    public static FreeFlightInput read(ByteBuf in) {
        float f = in.readFloat();
        float v = in.readFloat();
        float s = in.readFloat();
        float y = in.readFloat();
        float p = in.readFloat();
        float b = in.readFloat();
        int flags = in.readByte() & 0xFF;
        boolean stop  = (flags & FLAG_STOP)  != 0;
        boolean hover = (flags & FLAG_HOVER) != 0;
        return new FreeFlightInput(f, v, s, y, p, b, stop, hover);
    }

    /** True when every channel is effectively zero (within fp epsilon) AND no assist active. */
    public boolean isIdle() {
        return Math.abs(throttleForward)  < 1e-5f
            && Math.abs(throttleVertical) < 1e-5f
            && Math.abs(strafeInput)      < 1e-5f
            && Math.abs(yawInput)         < 1e-5f
            && Math.abs(pitchInput)       < 1e-5f
            && Math.abs(brakeInput)       < 1e-5f
            && !stopActive
            && !hoverActive;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FreeFlightInput)) return false;
        FreeFlightInput i = (FreeFlightInput) o;
        return Float.compare(throttleForward,  i.throttleForward)  == 0
            && Float.compare(throttleVertical, i.throttleVertical) == 0
            && Float.compare(strafeInput,      i.strafeInput)      == 0
            && Float.compare(yawInput,         i.yawInput)         == 0
            && Float.compare(pitchInput,       i.pitchInput)       == 0
            && Float.compare(brakeInput,       i.brakeInput)       == 0
            && stopActive  == i.stopActive
            && hoverActive == i.hoverActive;
    }

    @Override
    public int hashCode() {
        int h = Float.floatToIntBits(throttleForward);
        h = 31 * h + Float.floatToIntBits(throttleVertical);
        h = 31 * h + Float.floatToIntBits(strafeInput);
        h = 31 * h + Float.floatToIntBits(yawInput);
        h = 31 * h + Float.floatToIntBits(pitchInput);
        h = 31 * h + Float.floatToIntBits(brakeInput);
        h = 31 * h + (stopActive  ? 1 : 0);
        h = 31 * h + (hoverActive ? 1 : 0);
        return h;
    }

    @Override
    public String toString() {
        return String.format("FreeFlightInput{fwd=%.3f vert=%.3f strafe=%.3f yaw=%.3f pitch=%.3f brake=%.3f stop=%b hover=%b}",
                throttleForward, throttleVertical, strafeInput, yawInput, pitchInput, brakeInput,
                stopActive, hoverActive);
    }
}
