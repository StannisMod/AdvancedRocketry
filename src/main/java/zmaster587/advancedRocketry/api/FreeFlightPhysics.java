package zmaster587.advancedRocketry.api;

/**
 * Pure-Java arcade physics for {@link RocketFlightMode#FREE_FLIGHT}.
 *
 * Deliberately depends on NO Minecraft types — every input is a primitive
 * and every output is captured in {@link Step}. This is the entire decision
 * surface for FF kinematics, so it can be unit-tested deterministically
 * without booting a server.
 *
 * <p>Units follow EntityRocket's convention: motionX/Y/Z are block-deltas per
 * tick. Gravity is a per-tick velocity decrement (positive value drains
 * motionY).
 *
 * <p><b>Thrust authority.</b> The class no longer invents a thrust scale of its
 * own. The caller passes {@code thrustMag} — the <em>gross per-tick thrust
 * acceleration</em> (blocks/tick²) derived from the rocket's classic stats
 * (thrust-to-weight, config-aware). EntityRocket computes it as
 * {@code StatsRocket.getAcceleration(g) + gravity}, so at full vertical throttle
 * the net (thrust − gravity) equals the classic ascent acceleration and the FF
 * climb gate is exactly the classic thrust-to-weight gate (TWR &gt; 1). See
 * {@code EntityRocket.tickFreeFlight}.
 *
 * <p><b>Fuel.</b> This class does NOT account fuel — that lives in
 * EntityRocket so it mirrors the classic burn (getFuelConsumptionRate, the
 * {@code rocketRequireFuel} config flag, bipropellant oxidizer). The pure layer
 * only reports, via {@link Step#thrustApplied}, whether thrust was applied this
 * tick; the caller drains fuel when it was. Whether thrust is permitted at all
 * is passed in as {@code canThrust} (fuel present, or fuel not required).
 *
 * <p>Player intent enters via a {@link FreeFlightInput} normalised to [-1, +1].
 */
public final class FreeFlightPhysics {

    // -- Tunables --------------------------------------------------------

    /** Per-tick yaw delta (degrees) at full yaw input. */
    public static final double MAX_YAW_RATE       = 6.0;
    /** Per-tick pitch delta (degrees) at full pitch input. */
    public static final double MAX_PITCH_RATE     = 4.0;
    /** Max scalar speed (blocks/tick) — hard cap. */
    public static final double MAX_SPEED          = 3.0;
    /** Brake retention factor at full brake (0..1, lower = more aggressive). */
    public static final double BRAKE_RETENTION    = 0.85;
    /** Idle drag retention factor when no input (each tick). */
    public static final double IDLE_DRAG          = 0.99;
    /** Pitch clamp (degrees). */
    public static final double PITCH_MAX          = 85.0;
    /**
     * Arcade ceiling on per-tick thrust acceleration (blocks/tick²). Bounds an
     * extremely high thrust-to-weight rocket so motion stays smooth; velocity is
     * still bounded independently by {@link #MAX_SPEED}. Normal rockets sit far
     * below this (e.g. TWR 2 → ~0.1), so the cap only bites on absurd builds.
     */
    public static final double MAX_THRUST_ACCEL   = 0.5;

    /** Speed below which the Stop assist snaps motion to exactly zero. */
    private static final double STOP_SNAP = 0.01;

    /** Snapshot of post-step rocket kinematics. Immutable. */
    public static final class Step {
        public final double motionX, motionY, motionZ;
        public final float  yaw, pitch;
        public final boolean thrustApplied;

        public Step(double motionX, double motionY, double motionZ,
                    float yaw, float pitch, boolean thrustApplied) {
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.thrustApplied = thrustApplied;
        }
    }

    private FreeFlightPhysics() {}

    /**
     * Compute one tick of free-flight physics.
     *
     * @param mx            current motion X
     * @param my            current motion Y
     * @param mz            current motion Z
     * @param yawDeg        current yaw (degrees)
     * @param pitchDeg      current pitch (degrees)
     * @param input         pilot intent
     * @param thrustMag     gross per-tick thrust acceleration (blocks/tick²),
     *                      derived from the rocket's classic stats; clamped to
     *                      {@code [0, MAX_THRUST_ACCEL]} internally
     * @param gravity       per-tick gravity drain (positive)
     * @param canThrust     whether thrust may be applied (fuel present, or fuel
     *                      not required)
     * @param flightAssistOn whether Flight Assist (idle drag) is engaged
     * @return Step with new motion, yaw, pitch, and whether thrust was applied
     */
    public static Step step(double mx, double my, double mz,
                            float yawDeg, float pitchDeg,
                            FreeFlightInput input,
                            double thrustMag, double gravity,
                            boolean canThrust, boolean flightAssistOn) {
        if (input == null) input = FreeFlightInput.zero();

        // Yaw/pitch rotate regardless of thrust — purely orientation.
        float newYaw   = yawDeg   + (float) (input.yawInput   * MAX_YAW_RATE);
        float newPitch = clampPitch(pitchDeg + (float) (input.pitchInput * MAX_PITCH_RATE));

        // Clamp the supplied thrust acceleration into the arcade range.
        double accel = thrustMag;
        if (accel < 0.0) accel = 0.0;
        if (accel > MAX_THRUST_ACCEL) accel = MAX_THRUST_ACCEL;

        boolean thrustApplied;
        double newMx, newMy, newMz;

        if (input.stopActive) {
            // -------- STOP ASSIST -----------------------------------------
            // Counter-thrust along -motion until magnitude is ~0. Ignores
            // forward/vertical channels (Stop overrides). Orientation still applied.
            double speed = Math.sqrt(mx * mx + my * my + mz * mz);
            if (speed > 1e-5 && canThrust && accel > 0.0) {
                double counterMag = Math.min(accel, speed); // don't overshoot
                double inv = counterMag / speed;
                newMx = mx - mx * inv;
                newMy = my - my * inv;
                newMz = mz - mz * inv;
                thrustApplied = true;
            } else {
                newMx = mx;
                newMy = my;
                newMz = mz;
                thrustApplied = false;
            }
            if (Math.abs(newMx) < STOP_SNAP && Math.abs(newMy) < STOP_SNAP && Math.abs(newMz) < STOP_SNAP) {
                newMx = 0; newMy = 0; newMz = 0;
            }
            // Gravity still acts during Stop unless Hover is also held (and thrust allowed).
            if (!input.hoverActive || !canThrust) {
                newMy -= gravity;
            } else {
                newMy = 0; // Hover during Stop: pin Y instead of fighting gravity.
                thrustApplied = true;
            }
        } else {
            // -------- REGULAR THRUST PATH ---------------------------------
            boolean wantsThrust = (input.throttleForward != 0.0 || input.throttleVertical != 0.0);
            thrustApplied = canThrust && wantsThrust;

            double fwdMag = thrustApplied ? accel * input.throttleForward  : 0.0;
            double vrtMag = thrustApplied ? accel * input.throttleVertical : 0.0;

            double yawRad   = Math.toRadians(newYaw);
            double pitchRad = Math.toRadians(newPitch);
            // Forward vector projected through pitch — MC convention: pitch<0 = nose up.
            double cosPitch = Math.cos(pitchRad);
            double fx = -Math.sin(yawRad) * cosPitch;
            double fy = -Math.sin(pitchRad);
            double fz =  Math.cos(yawRad) * cosPitch;

            newMx = mx + fwdMag * fx;
            newMy = my + fwdMag * fy + vrtMag;
            newMz = mz + fwdMag * fz;

            // Hover hold: cancel gravity for the tick when active AND thrust allowed.
            boolean hoverApplied = input.hoverActive && canThrust;
            if (hoverApplied) {
                thrustApplied = true; // no -gravity term this tick
            } else {
                newMy -= gravity;
            }

            // Brake / idle drag (FA-gated).
            double brake = clamp01(input.brakeInput);
            if (brake > 0.0) {
                double retain = 1.0 - (1.0 - BRAKE_RETENTION) * brake;
                newMx *= retain;
                newMy *= retain;
                newMz *= retain;
            } else if (flightAssistOn && input.isIdle()) {
                // FA on + idle → bleed horizontal drift. FA off → Newtonian coast.
                newMx *= IDLE_DRAG;
                newMz *= IDLE_DRAG;
            }
        }

        // Hard speed cap (always — safety, regardless of FA).
        double speed = Math.sqrt(newMx * newMx + newMy * newMy + newMz * newMz);
        if (speed > MAX_SPEED) {
            double s = MAX_SPEED / speed;
            newMx *= s;
            newMy *= s;
            newMz *= s;
        }

        return new Step(newMx, newMy, newMz, newYaw, newPitch, thrustApplied);
    }

    /**
     * Landing detector: small vertical motion + ground contact = landed.
     *
     * @param onGround   from Entity.onGround
     * @param motionY    current vertical motion
     * @return true if the rocket should transition to LANDED/IDLE
     */
    public static boolean shouldLand(boolean onGround, double motionY) {
        return onGround && Math.abs(motionY) < 0.05;
    }

    static float clampPitch(float p) {
        if (p > PITCH_MAX) return (float) PITCH_MAX;
        if (p < -PITCH_MAX) return (float) -PITCH_MAX;
        return p;
    }

    static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
