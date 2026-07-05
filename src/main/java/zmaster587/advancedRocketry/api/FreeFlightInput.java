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
 * {@link #throttleVertical} along the nose's up axis (all derived from
 * yaw+pitch+roll in {@link FreeFlightPhysics}). Yaw/pitch/roll are
 * orientation-rate channels.
 *
 * <p>With Flight Assist on the translation channels RAMP the
 * server-held velocity setpoint instead of thrusting directly, and
 * {@link #cutActive} zeroes that setpoint (brake-to-hover). With FA off the
 * channels are direct thrust and the cut neutralises them for the tick.
 *
 * Wire format: seven 32-bit floats (fwd, vert, strafe, yaw, pitch, roll, brake),
 * big-endian, followed by one flag byte (cut).
 */
public final class FreeFlightInput {

    public static final float MIN = -1.0f;
    public static final float MAX = 1.0f;

    /** Flag bits packed into the final wire byte. */
    private static final int FLAG_CUT = 0x01;

    /** Wire size: 7 floats + 1 flag byte. */
    public static final int WIRE_SIZE = 7 * 4 + 1;

    public final float throttleForward;
    public final float throttleVertical;
    /** Lateral (strafe) thrust along the nose's right axis. +1 = right, -1 = left. */
    public final float strafeInput;
    public final float yawInput;
    public final float pitchInput;
    /** Roll (bank) rate about the nose axis. +1 = bank right, -1 = bank left. */
    public final float rollInput;
    public final float brakeInput;
    /** Throttle cut (X): FA on → zero the velocity setpoint (brake-to-hover);
     *  FA off → neutralise translation thrust while held. */
    public final boolean cutActive;

    /** Legacy 5-channel constructor (no strafe / roll / cut). */
    public FreeFlightInput(float throttleForward,
                           float throttleVertical,
                           float yawInput,
                           float pitchInput,
                           float brakeInput) {
        this(throttleForward, throttleVertical, 0f, yawInput, pitchInput, 0f, brakeInput, false);
    }

    /** 7-channel constructor (no roll) — kept for existing call sites. */
    public FreeFlightInput(float throttleForward,
                           float throttleVertical,
                           float strafeInput,
                           float yawInput,
                           float pitchInput,
                           float brakeInput,
                           boolean cutActive) {
        this(throttleForward, throttleVertical, strafeInput, yawInput, pitchInput, 0f, brakeInput, cutActive);
    }

    /** Full constructor. */
    public FreeFlightInput(float throttleForward,
                           float throttleVertical,
                           float strafeInput,
                           float yawInput,
                           float pitchInput,
                           float rollInput,
                           float brakeInput,
                           boolean cutActive) {
        this.throttleForward  = clamp(throttleForward);
        this.throttleVertical = clamp(throttleVertical);
        this.strafeInput      = clamp(strafeInput);
        this.yawInput         = clamp(yawInput);
        this.pitchInput       = clamp(pitchInput);
        this.rollInput        = clamp(rollInput);
        this.brakeInput       = clamp(brakeInput);
        this.cutActive        = cutActive;
    }

    public static FreeFlightInput zero() {
        return new FreeFlightInput(0f, 0f, 0f, 0f, 0f, 0f, 0f, false);
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
        out.writeFloat(rollInput);
        out.writeFloat(brakeInput);
        int flags = 0;
        if (cutActive) flags |= FLAG_CUT;
        out.writeByte(flags);
    }

    /** Read + clamp; tolerant of malicious clients sending out-of-range floats. */
    public static FreeFlightInput read(ByteBuf in) {
        float f = in.readFloat();
        float v = in.readFloat();
        float s = in.readFloat();
        float y = in.readFloat();
        float p = in.readFloat();
        float r = in.readFloat();
        float b = in.readFloat();
        int flags = in.readByte() & 0xFF;
        boolean cut = (flags & FLAG_CUT) != 0;
        return new FreeFlightInput(f, v, s, y, p, r, b, cut);
    }

    /** True when every channel is effectively zero (within fp epsilon) AND no cut active. */
    public boolean isIdle() {
        return Math.abs(throttleForward)  < 1e-5f
            && Math.abs(throttleVertical) < 1e-5f
            && Math.abs(strafeInput)      < 1e-5f
            && Math.abs(yawInput)         < 1e-5f
            && Math.abs(pitchInput)       < 1e-5f
            && Math.abs(rollInput)        < 1e-5f
            && Math.abs(brakeInput)       < 1e-5f
            && !cutActive;
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
            && Float.compare(rollInput,        i.rollInput)        == 0
            && Float.compare(brakeInput,       i.brakeInput)       == 0
            && cutActive == i.cutActive;
    }

    @Override
    public int hashCode() {
        int h = Float.floatToIntBits(throttleForward);
        h = 31 * h + Float.floatToIntBits(throttleVertical);
        h = 31 * h + Float.floatToIntBits(strafeInput);
        h = 31 * h + Float.floatToIntBits(yawInput);
        h = 31 * h + Float.floatToIntBits(pitchInput);
        h = 31 * h + Float.floatToIntBits(rollInput);
        h = 31 * h + Float.floatToIntBits(brakeInput);
        h = 31 * h + (cutActive ? 1 : 0);
        return h;
    }

    @Override
    public String toString() {
        return String.format("FreeFlightInput{fwd=%.3f vert=%.3f strafe=%.3f yaw=%.3f pitch=%.3f roll=%.3f brake=%.3f cut=%b}",
                throttleForward, throttleVertical, strafeInput, yawInput, pitchInput, rollInput, brakeInput, cutActive);
    }
}
