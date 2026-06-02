package zmaster587.advancedRocketry.api;

/**
 * Pure-Java arcade physics for {@link RocketFlightMode#FREE_FLIGHT}.
 *
 * Deliberately depends on NO Minecraft types — every input is a primitive
 * and every output is captured in {@link Step}. This is the entire decision
 * surface for FF physics, so it can be unit-tested deterministically without
 * booting a server.
 *
 * Units follow EntityRocket's convention: motionX/Y/Z are block-deltas per
 * tick. Gravity is a per-tick velocity decrement (positive value drains
 * motionY). Mass is the rocket's "weight" stat (StatsRocket.getWeight()).
 *
 * Player intent enters via a {@link FreeFlightInput} normalised to [-1, +1].
 */
public final class FreeFlightPhysics {

    // -- Tunables --------------------------------------------------------

    /** Per-tick forward acceleration at full forward throttle, post mass scaling. */
    public static final double MAX_FORWARD_ACCEL  = 0.08;
    /** Per-tick vertical acceleration at full vertical throttle, post mass scaling. */
    public static final double MAX_VERTICAL_ACCEL = 0.10;
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

    /** Fuel units consumed per tick at full effective thrust. */
    public static final int    FUEL_PER_TICK_AT_FULL_THRUST = 4;

    /** Minimum rocket mass to avoid division blowups. */
    private static final double MIN_MASS = 1.0;

    /** Snapshot of post-step rocket kinematics + fuel cost. Immutable. */
    public static final class Step {
        public final double motionX, motionY, motionZ;
        public final float  yaw, pitch;
        public final int    fuelConsumed;
        public final boolean thrustApplied;

        public Step(double motionX, double motionY, double motionZ,
                    float yaw, float pitch, int fuelConsumed, boolean thrustApplied) {
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.fuelConsumed = fuelConsumed;
            this.thrustApplied = thrustApplied;
        }
    }

    private FreeFlightPhysics() {}

    /**
     * Compute one tick of free-flight physics.
     *
     * @param mx,my,mz   current motion vector
     * @param yawDeg     current yaw (degrees)
     * @param pitchDeg   current pitch (degrees)
     * @param input      pilot intent
     * @param thrust     rocket thrust stat (StatsRocket.getThrust())
     * @param mass       rocket mass stat   (StatsRocket.getWeight())
     * @param gravity    per-tick gravity drain (positive)
     * @param fuelAvail  fuel units left in primary tank
     * @return Step with new motion, yaw, pitch, fuel cost
     */
    public static Step step(double mx, double my, double mz,
                            float yawDeg, float pitchDeg,
                            FreeFlightInput input,
                            int thrust, double mass,
                            double gravity, int fuelAvail) {
        if (input == null) input = FreeFlightInput.zero();

        // Yaw/pitch rotate regardless of fuel — purely orientation, no thrust.
        float newYaw   = yawDeg   + (float) (input.yawInput   * MAX_YAW_RATE);
        float newPitch = clampPitch(pitchDeg + (float) (input.pitchInput * MAX_PITCH_RATE));

        // Thrust ratio depends on rocket spec; classic stats are integers in
        // the thousands, so normalise against mass like the classic ascent
        // formula does (thrust - weight) / 10000 → similar order of magnitude.
        double m = Math.max(MIN_MASS, mass);
        double thrustScalar = Math.max(0.0, thrust) / (m * 10000.0);
        if (thrustScalar < 1e-6) thrustScalar = 1e-6;
        // Cap scalar so a wildly over-thrusted rocket still respects caps.
        if (thrustScalar > 2.0) thrustScalar = 2.0;

        double fwdMag = MAX_FORWARD_ACCEL  * input.throttleForward  * thrustScalar;
        double vrtMag = MAX_VERTICAL_ACCEL * input.throttleVertical * thrustScalar;

        // Fuel gate: out of fuel → no thrust applied, but orientation + gravity still act.
        boolean thrustApplied = fuelAvail > 0 && (fwdMag != 0.0 || vrtMag != 0.0);
        if (!thrustApplied) {
            fwdMag = 0.0;
            vrtMag = 0.0;
        }

        double yawRad   = Math.toRadians(newYaw);
        double pitchRad = Math.toRadians(newPitch);
        // Forward vector projected through pitch — MC convention: pitch<0 = nose up.
        // Cap cos(pitch) at a small minimum so thrust doesn't fully vanish at
        // gimbal lock (PITCH_MAX = ±85°, so cos ≈ 0.087 — still gives the pilot
        // some horizontal control to recover).
        double cosPitch = Math.cos(pitchRad);
        double fx = -Math.sin(yawRad) * cosPitch;
        double fy = -Math.sin(pitchRad);
        double fz =  Math.cos(yawRad) * cosPitch;

        double newMx = mx + fwdMag * fx;
        double newMy = my + fwdMag * fy + vrtMag;
        double newMz = mz + fwdMag * fz;

        // Gravity always drains vertical motion in FF (no orbital handwave).
        newMy -= gravity;

        // Brake / idle drag.
        double brake = clamp01(input.brakeInput);
        if (brake > 0.0) {
            double retain = 1.0 - (1.0 - BRAKE_RETENTION) * brake;
            newMx *= retain;
            // Vertical brake fights gravity less aggressively (otherwise hover is impossible
            // at zero brake) — kept symmetric with horizontal for predictability.
            newMy *= retain;
            newMz *= retain;
        } else if (input.isIdle()) {
            newMx *= IDLE_DRAG;
            newMz *= IDLE_DRAG;
        }

        // Hard speed cap.
        double speed = Math.sqrt(newMx * newMx + newMy * newMy + newMz * newMz);
        if (speed > MAX_SPEED) {
            double s = MAX_SPEED / speed;
            newMx *= s;
            newMy *= s;
            newMz *= s;
        }

        // Fuel cost: proportional to absolute thrust magnitude on whichever axis applied it.
        int fuelCost = 0;
        if (thrustApplied) {
            double demand = (Math.abs(input.throttleForward) + Math.abs(input.throttleVertical)) * 0.5;
            fuelCost = (int) Math.ceil(demand * FUEL_PER_TICK_AT_FULL_THRUST);
            if (fuelCost > fuelAvail) fuelCost = fuelAvail;
        }

        return new Step(newMx, newMy, newMz, newYaw, newPitch, fuelCost, thrustApplied);
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
