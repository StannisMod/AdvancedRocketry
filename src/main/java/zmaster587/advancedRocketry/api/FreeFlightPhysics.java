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

    /** Per-tick yaw delta (degrees) at full yaw input (A/D keyboard steering). */
    public static final double MAX_YAW_RATE       = 3.0;
    /** Per-tick pitch delta (degrees) at full pitch input. */
    public static final double MAX_PITCH_RATE     = 4.0;
    /** Per-tick roll (bank) delta (degrees) at full roll input. */
    public static final double MAX_ROLL_RATE      = 5.0;
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
        public final float  yaw, pitch, roll;
        public final boolean thrustApplied;

        /** Legacy constructor (no roll) — roll echoes 0. */
        public Step(double motionX, double motionY, double motionZ,
                    float yaw, float pitch, boolean thrustApplied) {
            this(motionX, motionY, motionZ, yaw, pitch, 0f, thrustApplied);
        }

        public Step(double motionX, double motionY, double motionZ,
                    float yaw, float pitch, float roll, boolean thrustApplied) {
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.thrustApplied = thrustApplied;
        }
    }

    private FreeFlightPhysics() {}

    // -- Body frame --------------------------------------------------------

    /** Roll-free basis (delegates with roll = 0). */
    public static double[] bodyBasis(float yawDeg, float pitchDeg) {
        return bodyBasis(yawDeg, pitchDeg, 0f);
    }

    /**
     * Orthonormal body basis from yaw+pitch+roll, MC conventions
     * (pitch&lt;0 = nose up). Returns 9 doubles: rows = forward, right, up.
     *
     * <p>Roll banks the craft about its nose: the roll-free right/up axes are
     * rotated around the (roll-invariant) forward axis by {@code rollDeg}
     * (+roll = bank right). Forward is unchanged, so roll never alters heading.
     */
    public static double[] bodyBasis(float yawDeg, float pitchDeg, float rollDeg) {
        double yawRad   = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);
        double sinYaw = Math.sin(yawRad), cosYaw = Math.cos(yawRad);
        double sinPit = Math.sin(pitchRad), cosPit = Math.cos(pitchRad);
        // Roll-free axes.
        double fx = -sinYaw * cosPit, fy = -sinPit, fz = cosYaw * cosPit; // forward
        double rx =  cosYaw,          ry =  0.0,    rz = sinYaw;          // right
        double ux = -sinYaw * sinPit, uy =  cosPit, uz = cosYaw * sinPit; // up
        // Bank right/up about forward by roll.
        double rollRad = Math.toRadians(rollDeg);
        double cr = Math.cos(rollRad), sr = Math.sin(rollRad);
        double rrx = rx * cr + ux * sr, rry = ry * cr + uy * sr, rrz = rz * cr + uz * sr;
        double urx = ux * cr - rx * sr, ury = uy * cr - ry * sr, urz = uz * cr - rz * sr;
        return new double[] {
                fx,  fy,  fz,
                rrx, rry, rrz,
                urx, ury, urz
        };
    }

    /** Roll-free body→world (delegates with roll = 0). */
    public static double[] bodyToWorld(double fwd, double right, double up,
                                       float yawDeg, float pitchDeg) {
        return bodyToWorld(fwd, right, up, yawDeg, pitchDeg, 0f);
    }

    /** Body-frame vector (forward, right, up) → world (x, y, z). */
    public static double[] bodyToWorld(double fwd, double right, double up,
                                       float yawDeg, float pitchDeg, float rollDeg) {
        double[] b = bodyBasis(yawDeg, pitchDeg, rollDeg);
        return new double[] {
                fwd * b[0] + right * b[3] + up * b[6],
                fwd * b[1] + right * b[4] + up * b[7],
                fwd * b[2] + right * b[5] + up * b[8]
        };
    }

    /** Roll-free world→body (delegates with roll = 0). */
    public static double[] worldToBody(double x, double y, double z,
                                       float yawDeg, float pitchDeg) {
        return worldToBody(x, y, z, yawDeg, pitchDeg, 0f);
    }

    /** World vector (x, y, z) → body frame (forward, right, up). The basis is
     *  orthonormal, so the inverse is the transpose. */
    public static double[] worldToBody(double x, double y, double z,
                                       float yawDeg, float pitchDeg, float rollDeg) {
        double[] b = bodyBasis(yawDeg, pitchDeg, rollDeg);
        return new double[] {
                x * b[0] + y * b[1] + z * b[2],
                x * b[3] + y * b[4] + z * b[5],
                x * b[6] + y * b[7] + z * b[8]
        };
    }

    // -- Body-frame attitude (quaternion) ----------------------------------

    /**
     * Unit quaternion orientation, body→world (w, x, y, z). The craft body frame
     * is X = right, Y = up, Z = forward (nose); at {@link #IDENTITY} those map to
     * world +X/+Y/+Z, matching {@link #bodyBasis} at (0,0,0).
     *
     * <p>This is the FF attitude SOURCE OF TRUTH (TASK-53 Phase 7). Integrating
     * orientation as a quaternion by BODY rates — pitch about the craft's right
     * axis, yaw about its up axis, roll about its nose — has no gimbal lock, so
     * loops work and the controls never invert relative to the pilot the way a
     * world-frame Euler triple does. Euler yaw/pitch/roll are DERIVED from this
     * for the camera/replication, never integrated.
     */
    public static final class Quat {
        public final double w, x, y, z;

        public Quat(double w, double x, double y, double z) {
            this.w = w; this.x = x; this.y = y; this.z = z;
        }

        public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

        /** Renormalise to unit length (guards against per-tick drift); a
         *  degenerate (zero-norm / NaN) quaternion collapses to identity. */
        public Quat normalized() {
            double n = Math.sqrt(w * w + x * x + y * y + z * z);
            if (n < 1e-9 || Double.isNaN(n)) return IDENTITY;
            double s = 1.0 / n;
            return new Quat(w * s, x * s, y * s, z * s);
        }

        /** Hamilton product {@code this ⊗ o}. */
        public Quat mul(Quat o) {
            return new Quat(
                    w * o.w - x * o.x - y * o.y - z * o.z,
                    w * o.x + x * o.w + y * o.z - z * o.y,
                    w * o.y - x * o.z + y * o.w + z * o.x,
                    w * o.z + x * o.y - y * o.x + z * o.w);
        }

        /** Rotate a world/body vector by this quaternion (body→world). Returns
         *  {x, y, z}. */
        public double[] rotate(double vx, double vy, double vz) {
            // v' = R·v, R built from the quaternion (body→world).
            double xx = x * x, yy = y * y, zz = z * z;
            double xy = x * y, xz = x * z, yz = y * z;
            double wx = w * x, wy = w * y, wz = w * z;
            return new double[] {
                    vx * (1 - 2 * (yy + zz)) + vy * 2 * (xy - wz)     + vz * 2 * (xz + wy),
                    vx * 2 * (xy + wz)       + vy * (1 - 2 * (xx + zz)) + vz * 2 * (yz - wx),
                    vx * 2 * (xz - wy)       + vy * 2 * (yz + wx)     + vz * (1 - 2 * (xx + yy))
            };
        }

        /** Quaternion for a rotation of {@code deg} degrees about a UNIT axis. */
        public static Quat fromAxisAngle(double ax, double ay, double az, double deg) {
            double half = Math.toRadians(deg) * 0.5;
            double s = Math.sin(half);
            return new Quat(Math.cos(half), ax * s, ay * s, az * s);
        }
    }

    /**
     * Advance an attitude by one tick of BODY-frame rotation rates (degrees).
     * The three inputs rotate about the craft's own axes — pitch about right (+X),
     * yaw about up (+Y), roll about nose (+Z) — composed as a single small delta
     * and post-multiplied ({@code q ⊗ dq}) so they act in the body frame. The sign
     * convention reproduces {@link #bodyBasis} near identity (pinned by tests): a
     * positive pitch rate drops the nose, positive yaw matches Euler yaw, positive
     * roll banks like {@code bodyBasis}'s roll. No clamp — attitude is free to loop.
     */
    public static Quat integrateBodyRates(Quat q, double pitchRateDeg,
                                          double yawRateDeg, double rollRateDeg) {
        if (q == null) q = Quat.IDENTITY;
        Quat dq = Quat.fromAxisAngle(1, 0, 0,  pitchRateDeg)
                .mul(Quat.fromAxisAngle(0, 1, 0, -yawRateDeg))
                .mul(Quat.fromAxisAngle(0, 0, 1,  rollRateDeg));
        return q.mul(dq).normalized();
    }

    /**
     * Orthonormal body basis from a quaternion, same layout as
     * {@link #bodyBasis(float, float, float)}: 9 doubles, rows forward, right, up
     * (world coords). {@code forward = right × up} (right-handed).
     */
    public static double[] bodyBasisFromQuat(Quat q) {
        if (q == null) q = Quat.IDENTITY;
        double[] fwd   = q.rotate(0, 0, 1);
        double[] right = q.rotate(1, 0, 0);
        double[] up    = q.rotate(0, 1, 0);
        return new double[] {
                fwd[0],   fwd[1],   fwd[2],
                right[0], right[1], right[2],
                up[0],    up[1],    up[2]
        };
    }

    /**
     * Derive Euler yaw/pitch/roll (degrees) from a quaternion in the
     * {@link #bodyBasis} convention, i.e. {@code bodyBasis(yaw, pitch, roll)}
     * reproduces this attitude (away from the ±90° pitch poles, where yaw/roll
     * gimbal-lock — harmless for the camera, which composes them back into a
     * continuous basis). Used only to feed the Euler-only MC camera / renderer.
     *
     * @return {yawDeg, pitchDeg, rollDeg}
     */
    public static float[] eulerFromQuat(Quat q) {
        double[] b = bodyBasisFromQuat(q);
        double fx = b[0], fy = b[1], fz = b[2];
        double rx = b[3], ry = b[4], rz = b[5];
        // pitch: forward.y = -sin(pitch); yaw: forward = (-sinYaw cosPitch, *, cosYaw cosPitch).
        double pitch = Math.asin(clampUnitD(-fy));
        double yaw   = Math.atan2(-fx, fz);
        // roll: actual right vs the roll-free right/up at this yaw+pitch.
        double sinY = Math.sin(yaw), cosY = Math.cos(yaw);
        double sinP = Math.sin(pitch), cosP = Math.cos(pitch);
        double r0x = cosY,          r0y = 0.0,  r0z = sinY;          // roll-free right
        double u0x = -sinY * sinP,  u0y = cosP, u0z = cosY * sinP;   // roll-free up
        double sinRoll = rx * u0x + ry * u0y + rz * u0z;
        double cosRoll = rx * r0x + ry * r0y + rz * r0z;
        double roll = Math.atan2(sinRoll, cosRoll);
        return new float[] {
                (float) Math.toDegrees(yaw),
                (float) Math.toDegrees(pitch),
                (float) Math.toDegrees(roll)
        };
    }

    /**
     * Spherical linear interpolation for client render/correction smoothing.
     * Takes the shortest arc (negates an endpoint on a negative dot) and falls
     * back to normalised lerp for nearly-parallel inputs.
     */
    public static Quat slerp(Quat a, Quat b, double t) {
        if (a == null) a = Quat.IDENTITY;
        if (b == null) b = Quat.IDENTITY;
        double dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z;
        double bw = b.w, bx = b.x, by = b.y, bz = b.z;
        if (dot < 0.0) { dot = -dot; bw = -bw; bx = -bx; by = -by; bz = -bz; }
        if (dot > 0.9995) {
            // Near-parallel: nlerp (avoids sin(θ)→0 blowup).
            return new Quat(a.w + (bw - a.w) * t, a.x + (bx - a.x) * t,
                    a.y + (by - a.y) * t, a.z + (bz - a.z) * t).normalized();
        }
        double theta0 = Math.acos(dot);
        double theta = theta0 * t;
        double sin0 = Math.sin(theta0);
        double s0 = Math.sin(theta0 - theta) / sin0;
        double s1 = Math.sin(theta) / sin0;
        return new Quat(a.w * s0 + bw * s1, a.x * s0 + bx * s1,
                a.y * s0 + by * s1, a.z * s0 + bz * s1);
    }

    private static double clampUnitD(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < -1.0) return -1.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    /** Body-frame vector (forward, right, up) → world, via a quaternion basis. */
    public static double[] bodyToWorldQ(double fwd, double right, double up, Quat q) {
        double[] b = bodyBasisFromQuat(q);
        return new double[] {
                fwd * b[0] + right * b[3] + up * b[6],
                fwd * b[1] + right * b[4] + up * b[7],
                fwd * b[2] + right * b[5] + up * b[8]
        };
    }

    /** World vector → body frame (forward, right, up), via a quaternion basis
     *  (orthonormal → inverse is the transpose). Used by FA re-enable to capture
     *  the current velocity as a body-frame setpoint. */
    public static double[] worldToBodyQ(double x, double y, double z, Quat q) {
        double[] b = bodyBasisFromQuat(q);
        return new double[] {
                x * b[0] + y * b[1] + z * b[2],
                x * b[3] + y * b[4] + z * b[5],
                x * b[6] + y * b[7] + z * b[8]
        };
    }

    private static double clampAccel(double a) {
        if (a < 0.0) return 0.0;
        if (a > MAX_THRUST_ACCEL) return MAX_THRUST_ACCEL;
        return a;
    }

    // -- Quaternion translation (TASK-53 Phase 7) --------------------------
    // Same control laws as the Euler faStep/step below, but the body→world basis
    // comes from the attitude quaternion so they are loop/pole-safe. Rotation is
    // NOT integrated here — the caller advances the quaternion by body rates
    // (integrateBodyRates) first; these only translate. The returned Step echoes
    // the derived Euler (eulerFromQuat) for legacy/HUD readers of yaw/pitch/roll.

    /** Flight-Assist velocity-setpoint translation with a quaternion attitude. */
    public static Step faStep(double mx, double my, double mz, Quat q,
                              double spFwd, double spRight, double spUp,
                              double thrustMag, double gravity, boolean canThrust) {
        double accel = clampAccel(thrustMag);
        float[] e = eulerFromQuat(q);

        if (!canThrust || accel <= 0.0) {
            return new Step(mx, my - gravity, mz, e[0], e[1], e[2], false);
        }

        double[] desired = bodyToWorldQ(sane(spFwd), sane(spRight), sane(spUp), q);
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

        double speed = Math.sqrt(newMx * newMx + newMy * newMy + newMz * newMz);
        if (speed > MAX_SPEED) {
            double s = MAX_SPEED / speed;
            newMx *= s; newMy *= s; newMz *= s;
        }
        return new Step(newMx, newMy, newMz, e[0], e[1], e[2], thrustApplied);
    }

    /** Newtonian (FA off) direct body-frame thrust translation with a quaternion
     *  attitude — the Euler {@link #step} translation half (thrust / brake / speed
     *  cap), minus the rotation integration the caller now owns. */
    public static Step translateNewtonian(double mx, double my, double mz, Quat q,
                                          FreeFlightInput input,
                                          double thrustMag, double gravity,
                                          boolean canThrust) {
        if (input == null) input = FreeFlightInput.zero();
        double accel = clampAccel(thrustMag);
        float[] e = eulerFromQuat(q);

        float fwdIn = input.cutActive ? 0f : input.throttleForward;
        float vrtIn = input.cutActive ? 0f : input.throttleVertical;
        float strIn = input.cutActive ? 0f : input.strafeInput;

        boolean wantsThrust = (fwdIn != 0.0f || vrtIn != 0.0f || strIn != 0.0f);
        boolean thrustApplied = canThrust && wantsThrust;

        double fwdMag = thrustApplied ? accel * fwdIn : 0.0;
        double vrtMag = thrustApplied ? accel * vrtIn : 0.0;
        double strMag = thrustApplied ? accel * strIn : 0.0;

        double[] t = bodyToWorldQ(fwdMag, strMag, vrtMag, q);
        double newMx = mx + t[0];
        double newMy = my + t[1] - gravity;
        double newMz = mz + t[2];

        double brake = clamp01(input.brakeInput);
        if (brake > 0.0) {
            double retain = 1.0 - (1.0 - BRAKE_RETENTION) * brake;
            newMx *= retain; newMy *= retain; newMz *= retain;
        }

        double speed = Math.sqrt(newMx * newMx + newMy * newMy + newMz * newMz);
        if (speed > MAX_SPEED) {
            double s = MAX_SPEED / speed;
            newMx *= s; newMy *= s; newMz *= s;
        }
        return new Step(newMx, newMy, newMz, e[0], e[1], e[2], thrustApplied);
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
    /** Roll-free faStep (delegates with roll = 0). */
    public static Step faStep(double mx, double my, double mz,
                              float yawDeg, float pitchDeg,
                              double spFwd, double spRight, double spUp,
                              double thrustMag, double gravity, boolean canThrust) {
        return faStep(mx, my, mz, yawDeg, pitchDeg, 0f, spFwd, spRight, spUp,
                thrustMag, gravity, canThrust);
    }

    public static Step faStep(double mx, double my, double mz,
                              float yawDeg, float pitchDeg, float rollDeg,
                              double spFwd, double spRight, double spUp,
                              double thrustMag, double gravity, boolean canThrust) {
        double accel = thrustMag;
        if (accel < 0.0) accel = 0.0;
        if (accel > MAX_THRUST_ACCEL) accel = MAX_THRUST_ACCEL;

        if (!canThrust || accel <= 0.0) {
            // Newtonian brick: gravity only.
            return new Step(mx, my - gravity, mz, yawDeg, pitchDeg, rollDeg, false);
        }

        double[] desired = bodyToWorld(sane(spFwd), sane(spRight), sane(spUp),
                yawDeg, pitchDeg, rollDeg);
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

        return new Step(newMx, newMy, newMz, yawDeg, pitchDeg, rollDeg, thrustApplied);
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
    /** Roll-free step (delegates with roll = 0) — for callers that don't bank. */
    public static Step step(double mx, double my, double mz,
                            float yawDeg, float pitchDeg,
                            FreeFlightInput input,
                            double thrustMag, double gravity,
                            boolean canThrust) {
        return step(mx, my, mz, yawDeg, pitchDeg, 0f, input, thrustMag, gravity, canThrust);
    }

    public static Step step(double mx, double my, double mz,
                            float yawDeg, float pitchDeg, float rollDeg,
                            FreeFlightInput input,
                            double thrustMag, double gravity,
                            boolean canThrust) {
        if (input == null) input = FreeFlightInput.zero();

        // Yaw/pitch/roll rotate regardless of thrust — purely orientation. Roll
        // wraps (no clamp); pitch is clamped to the envelope.
        float newYaw   = yawDeg   + (float) (input.yawInput   * MAX_YAW_RATE);
        float newPitch = clampPitch(pitchDeg + (float) (input.pitchInput * MAX_PITCH_RATE));
        float newRoll  = wrapDeg(rollDeg + (float) (input.rollInput * MAX_ROLL_RATE));

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

        double[] t = bodyToWorld(fwdMag, strMag, vrtMag, newYaw, newPitch, newRoll);
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

        return new Step(newMx, newMy, newMz, newYaw, newPitch, newRoll, thrustApplied);
    }

    /** Wrap an angle to [-180, 180) so roll accumulates without unbounded growth. */
    public static float wrapDeg(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
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
