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
 * <p><b>Thrust authority.</b> The class does not invent a thrust scale of its
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
 * <p><b>Two control laws</b> (TASK-46 D4):
 * <ul>
 *   <li>{@link #faStep} — Flight Assist ON (default): the pilot edits a
 *       body-frame <em>velocity setpoint</em> (see {@link #rampSetpoint});
 *       FA computes the thrust that tracks it, cancelling gravity. Zero
 *       setpoint = hover.</li>
 *   <li>{@link #step} — Flight Assist OFF: raw Newtonian. Translation
 *       channels are direct thrust while held; release = coast under
 *       gravity. The manual brake (Shift) lives here only.</li>
 * </ul>
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
    /** Pitch clamp (degrees). */
    public static final double PITCH_MAX          = 85.0;
    /**
     * Arcade ceiling on per-tick thrust acceleration (blocks/tick²). Bounds an
     * extremely high thrust-to-weight rocket so motion stays smooth; velocity is
     * still bounded independently by {@link #MAX_SPEED}. Normal rockets sit far
     * below this (e.g. TWR 2 → ~0.1), so the cap only bites on absurd builds.
     */
    public static final double MAX_THRUST_ACCEL   = 0.5;

    /**
     * Per-tick velocity retention used by the liftoff/hover assist to bleed
     * horizontal drift (0..1; ≈0.88 → settles in ~25–30 ticks).
     */
    public static final double HOVER_RETENTION    = 0.88;

    /** Speed below which assisted damping snaps motion to exactly zero. */
    private static final double STOP_SNAP = 0.01;

    // -- Engine-start liftoff (TASK-46 D3) --------------------------------

    /** Max climb rate (blocks/tick) of the liftoff/hover assist. Gentle by
     *  design — the engine-start ritual lifts the craft ~1 block, it is not
     *  an ascent. */
    public static final double LIFTOFF_CLIMB_RATE = 0.12;
    /** Proportional gain: desired climb = altitude error × this (then
     *  clamped to ±LIFTOFF_CLIMB_RATE), so the craft eases onto the target
     *  instead of overshooting. */
    public static final double LIFTOFF_GAIN = 0.25;

    // -- Flight Assist setpoint (TASK-46 D4) -------------------------------

    /**
     * Per-held-tick change of the velocity setpoint (blocks/tick per tick) at
     * full channel deflection: holding a key sweeps one axis from 0 to
     * {@link #MAX_SPEED} in ~{@code MAX_SPEED/SETPOINT_RAMP} = 60 ticks (3 s).
     */
    public static final double SETPOINT_RAMP = 0.05;

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

    // -- Body frame --------------------------------------------------------

    /**
     * Orthonormal body basis from yaw+pitch (no roll), MC conventions
     * (pitch&lt;0 = nose up). Returns 9 doubles: rows = forward, right, up.
     */
    public static double[] bodyBasis(float yawDeg, float pitchDeg) {
        double yawRad   = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);
        double sinYaw = Math.sin(yawRad), cosYaw = Math.cos(yawRad);
        double sinPit = Math.sin(pitchRad), cosPit = Math.cos(pitchRad);
        return new double[] {
                // Nose (forward)
                -sinYaw * cosPit, -sinPit,  cosYaw * cosPit,
                // Right axis (horizontal, perpendicular to the heading)
                 cosYaw,           0.0,     sinYaw,
                // Up axis (= forward × right) — tilts with pitch
                -sinYaw * sinPit,  cosPit,  cosYaw * sinPit
        };
    }

    /** Body-frame vector (forward, right, up) → world (x, y, z). */
    public static double[] bodyToWorld(double fwd, double right, double up,
                                       float yawDeg, float pitchDeg) {
        double[] b = bodyBasis(yawDeg, pitchDeg);
        return new double[] {
                fwd * b[0] + right * b[3] + up * b[6],
                fwd * b[1] + right * b[4] + up * b[7],
                fwd * b[2] + right * b[5] + up * b[8]
        };
    }

    /** World vector (x, y, z) → body frame (forward, right, up). The basis is
     *  orthonormal, so the inverse is the transpose. */
    public static double[] worldToBody(double x, double y, double z,
                                       float yawDeg, float pitchDeg) {
        double[] b = bodyBasis(yawDeg, pitchDeg);
        return new double[] {
                x * b[0] + y * b[1] + z * b[2],
                x * b[3] + y * b[4] + z * b[5],
                x * b[6] + y * b[7] + z * b[8]
        };
    }

    // -- Flight Assist (velocity setpoint) ---------------------------------

    /**
     * Advance the body-frame velocity setpoint by one tick of pilot input
     * (TASK-46 D4). Holding a translation key RAMPS the matching axis by
     * {@link #SETPOINT_RAMP} per tick; releasing leaves the setpoint where it
     * is; {@code input.cutActive} (X) zeroes the whole vector instantly. The
     * result is clamped to {@link #MAX_SPEED} in magnitude.
     *
     * @return new setpoint as {forward, right, up}
     */
    public static double[] rampSetpoint(double spFwd, double spRight, double spUp,
                                        FreeFlightInput input) {
        if (input == null) input = FreeFlightInput.zero();
        if (input.cutActive) return new double[] {0, 0, 0};

        double f = sane(spFwd)   + input.throttleForward  * SETPOINT_RAMP;
        double r = sane(spRight) + input.strafeInput      * SETPOINT_RAMP;
        double u = sane(spUp)    + input.throttleVertical * SETPOINT_RAMP;

        double mag = Math.sqrt(f * f + r * r + u * u);
        if (mag > MAX_SPEED) {
            double s = MAX_SPEED / mag;
            f *= s; r *= s; u *= s;
        }
        return new double[] {f, r, u};
    }

    /**
     * One tick of Flight Assist velocity-setpoint control (TASK-46 D4).
     *
     * The pilot's setpoint lives in the BODY frame, so rotating the craft
     * rotates the actual world velocity. Each tick FA computes the world-space
     * velocity error plus a gravity-compensation term, clamps the commanded
     * acceleration to the thrust budget, and applies it. Zero setpoint = the
     * craft strives to hover. An under-powered craft (budget &lt; gravity)
     * honestly sags; with no thrust permitted it is a Newtonian brick.
     *
     * <p>Orientation is passed through untouched — callers integrate yaw/pitch
     * via {@link #step} or their own rate handling before calling this.
     *
     * @return Step with new motion (yaw/pitch echoed back) and whether thrust
     *         was commanded this tick (→ fuel burn)
     */
    public static Step faStep(double mx, double my, double mz,
                              float yawDeg, float pitchDeg,
                              double spFwd, double spRight, double spUp,
                              double thrustMag, double gravity, boolean canThrust) {
        double accel = thrustMag;
        if (accel < 0.0) accel = 0.0;
        if (accel > MAX_THRUST_ACCEL) accel = MAX_THRUST_ACCEL;

        if (!canThrust || accel <= 0.0) {
            // Newtonian brick: gravity only.
            return new Step(mx, my - gravity, mz, yawDeg, pitchDeg, false);
        }

        double[] desired = bodyToWorld(sane(spFwd), sane(spRight), sane(spUp),
                yawDeg, pitchDeg);
        // Commanded acceleration = velocity error + gravity compensation.
        double cx = desired[0] - mx;
        double cy = desired[1] - my + gravity;
        double cz = desired[2] - mz;
        double cmdMag = Math.sqrt(cx * cx + cy * cy + cz * cz);
        boolean thrustApplied = cmdMag > 1e-9;
        if (cmdMag > accel) {
            double s = accel / cmdMag;
            cx *= s; cy *= s; cz *= s;
        }

        double newMx = mx + cx;
        double newMy = my + cy - gravity;
        double newMz = mz + cz;

        // Hard speed cap (always — safety).
        double speed = Math.sqrt(newMx * newMx + newMy * newMy + newMz * newMz);
        if (speed > MAX_SPEED) {
            double s = MAX_SPEED / speed;
            newMx *= s; newMy *= s; newMz *= s;
        }

        return new Step(newMx, newMy, newMz, yawDeg, pitchDeg, thrustApplied);
    }

    // -- Newtonian (Flight Assist off) --------------------------------------

    /**
     * Compute one tick of raw Newtonian free-flight physics (Flight Assist
     * OFF — TASK-46 D4). Translation channels are DIRECT thrust while held;
     * releasing them means coasting under gravity. {@code input.cutActive}
     * neutralises translation for the tick; the manual brake (Shift)
     * attenuates motion. Orientation (yaw/pitch rates) always integrates —
     * this is also the orientation path used while FA is on.
     *
     * @param mx,my,mz   current motion
     * @param yawDeg     current yaw (degrees)
     * @param pitchDeg   current pitch (degrees)
     * @param input      pilot intent
     * @param thrustMag  gross per-tick thrust acceleration (blocks/tick²),
     *                   clamped to {@code [0, MAX_THRUST_ACCEL]} internally
     * @param gravity    per-tick gravity drain (positive)
     * @param canThrust  whether thrust may be applied (fuel present, or fuel
     *                   not required)
     * @return Step with new motion, yaw, pitch, and whether thrust was applied
     */
    public static Step step(double mx, double my, double mz,
                            float yawDeg, float pitchDeg,
                            FreeFlightInput input,
                            double thrustMag, double gravity,
                            boolean canThrust) {
        if (input == null) input = FreeFlightInput.zero();

        // Yaw/pitch rotate regardless of thrust — purely orientation.
        float newYaw   = yawDeg   + (float) (input.yawInput   * MAX_YAW_RATE);
        float newPitch = clampPitch(pitchDeg + (float) (input.pitchInput * MAX_PITCH_RATE));

        // Clamp the supplied thrust acceleration into the arcade range.
        double accel = thrustMag;
        if (accel < 0.0) accel = 0.0;
        if (accel > MAX_THRUST_ACCEL) accel = MAX_THRUST_ACCEL;

        // Translation is body-relative; the cut neutralises it for this tick.
        float fwdIn = input.cutActive ? 0f : input.throttleForward;
        float vrtIn = input.cutActive ? 0f : input.throttleVertical;
        float strIn = input.cutActive ? 0f : input.strafeInput;

        boolean wantsThrust = (fwdIn != 0.0f || vrtIn != 0.0f || strIn != 0.0f);
        boolean thrustApplied = canThrust && wantsThrust;

        double fwdMag = thrustApplied ? accel * fwdIn : 0.0;
        double vrtMag = thrustApplied ? accel * vrtIn : 0.0;
        double strMag = thrustApplied ? accel * strIn : 0.0;

        double[] t = bodyToWorld(fwdMag, strMag, vrtMag, newYaw, newPitch);
        double newMx = mx + t[0];
        double newMy = my + t[1] - gravity;
        double newMz = mz + t[2];

        // Manual brake (Shift) — attenuates everything, Newtonian mode's only assist.
        double brake = clamp01(input.brakeInput);
        if (brake > 0.0) {
            double retain = 1.0 - (1.0 - BRAKE_RETENTION) * brake;
            newMx *= retain;
            newMy *= retain;
            newMz *= retain;
        }

        // Hard speed cap (always — safety).
        double speed = Math.sqrt(newMx * newMx + newMy * newMy + newMz * newMz);
        if (speed > MAX_SPEED) {
            double s = MAX_SPEED / speed;
            newMx *= s;
            newMy *= s;
            newMz *= s;
        }

        return new Step(newMx, newMy, newMz, newYaw, newPitch, thrustApplied);
    }

    // -- Engine-start liftoff ------------------------------------------------

    /**
     * One tick of the engine-start liftoff / hover assist (TASK-46 D3).
     *
     * Active right after the engines start, while the pilot gives no
     * translation input: eases the craft from the pad to {@code targetY}
     * (≈ launch height + 1) and then holds there — gravity is cancelled,
     * horizontal drift is damped, and the vertical speed approaches a
     * gentle proportional climb, all bounded by the craft's thrust budget.
     * Yaw/pitch are NOT touched here; the caller keeps steering through
     * {@link #step}'s orientation handling or applies rates itself.
     *
     * @param posY      current altitude
     * @param targetY   hover altitude to ease onto
     * @param mx,my,mz  current motion
     * @param thrustMag gross per-tick thrust budget (blocks/tick²), same
     *                  authority as {@link #step}; clamped to
     *                  {@code [0, MAX_THRUST_ACCEL]}
     * @return new motion (thrust is always applied while this runs —
     *         hovering burns fuel like the classic hover would)
     */
    public static Step liftoffStep(double posY, double targetY,
                                   double mx, double my, double mz,
                                   float yawDeg, float pitchDeg,
                                   double thrustMag) {
        double accel = thrustMag;
        if (accel < 0.0) accel = 0.0;
        if (accel > MAX_THRUST_ACCEL) accel = MAX_THRUST_ACCEL;

        double err = targetY - posY;
        double desiredVy = err * LIFTOFF_GAIN;
        if (desiredVy >  LIFTOFF_CLIMB_RATE) desiredVy =  LIFTOFF_CLIMB_RATE;
        if (desiredVy < -LIFTOFF_CLIMB_RATE) desiredVy = -LIFTOFF_CLIMB_RATE;

        // Approach the desired climb, bounded by the thrust budget (gravity
        // is treated as cancelled by the same budget — the assist exists only
        // for craft that passed the classic TWR>1 start gate).
        double dv = desiredVy - my;
        if (dv >  accel) dv =  accel;
        if (dv < -accel) dv = -accel;
        double newMy = my + dv;

        double newMx = mx * HOVER_RETENTION;
        double newMz = mz * HOVER_RETENTION;
        if (Math.abs(newMx) < STOP_SNAP) newMx = 0;
        if (Math.abs(newMz) < STOP_SNAP) newMz = 0;

        return new Step(newMx, newMy, newMz, yawDeg, pitchDeg, true);
    }

    /**
     * Mouse-as-rate steering (TASK-46 D1): convert the look delta the mouse
     * accumulated over one tick into a normalised rate command in [-1, 1].
     *
     * <p>Below the craft's turn rate the response is 1:1 — a {@code deltaDeg}
     * swipe turns the nose by exactly {@code deltaDeg} on the next tick
     * (rate = delta/max, integrated as rate*max). Faster swipes saturate at
     * the craft's max turn rate and the excess is discarded ("mouse slip"),
     * which is the Elite-style rate limit rather than a queued turn.
     *
     * @param deltaDeg       look degrees accumulated since the last camera pin
     * @param maxRatePerTick the craft's max turn rate for this axis (deg/tick)
     */
    public static double rateFromMouseDelta(double deltaDeg, double maxRatePerTick) {
        if (Double.isNaN(deltaDeg) || maxRatePerTick <= 0.0) return 0.0;
        double rate = deltaDeg / maxRatePerTick;
        if (rate > 1.0) return 1.0;
        if (rate < -1.0) return -1.0;
        return rate;
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

    /** Clamp a pitch angle to the FF envelope (±{@link #PITCH_MAX}). */
    public static float clampPitch(float p) {
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

    /** NaN/Inf hygiene for persisted setpoint components. */
    private static double sane(double v) {
        return (Double.isNaN(v) || Double.isInfinite(v)) ? 0.0 : v;
    }
}
