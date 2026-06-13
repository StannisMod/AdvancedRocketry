package zmaster587.advancedRocketry.entity;

/**
 * Pluggable backend that realizes a rocket's per-tick FREE-FLIGHT movement — the
 * seam for integrating Free Flight (branch {@code feature/true_rcs}) with
 * Valkyrien Skies rigid-body ship physics (branch
 * {@code feature/true_spaceships}). See {@code .agent/tasks/TASK-48-*}.
 *
 * <h2>Why this exists</h2>
 * Free Flight computes a desired kinematic state from pilot input in its pure,
 * side-effect-free {@code FreeFlightPhysics} layer (a {@code Step} of motion in
 * blocks/tick + yaw/pitch in degrees), then <em>applies</em> it. Today that
 * application is hardcoded in {@code EntityRocket.tickFreeFlight()}: it writes
 * {@code motionX/Y/Z} + {@code rotationYaw/Pitch} and calls {@code Entity.move()}.
 * That is exactly the ~6 lines that would fight Valkyrien Skies, which wants to
 * own the craft's transform and integrate it with its own physics.
 *
 * <p>This interface abstracts the application step so the same pilot intent can
 * drive either:</p>
 * <ul>
 *   <li>the <b>legacy</b> backend — write entity motion/rotation + call
 *       {@code Entity.move()} (today's behaviour, used when VS is absent); or</li>
 *   <li>the <b>VS</b> backend — feed the desired motion/orientation into the
 *       rocket's Valkyrien Skies ship (as a velocity/orientation setpoint or a
 *       force/torque) and let VS own the displacement.</li>
 * </ul>
 *
 * <h2>Contract notes</h2>
 * <ul>
 *   <li>Expressed in <b>primitives</b>, not Free Flight's {@code Step} type, so
 *       this seam can live on the VS branch <em>before</em> the FF branch is
 *       merged. When FF is present it adapts its {@code Step} to this call.</li>
 *   <li>This is a <b>PROPOSED</b> contract — the exact shape (setpoint vs force,
 *       rotation ownership, client-sync handoff) is to be finalized during the
 *       integration work; see TASK-48 for the open questions.</li>
 * </ul>
 */
public interface IRocketFlightBackend {

    /**
     * Realize one tick of desired free-flight state on {@code rocket}.
     *
     * @param rocket        the rocket being flown
     * @param motionX       desired X velocity, blocks/tick (FreeFlightPhysics output)
     * @param motionY       desired Y velocity, blocks/tick
     * @param motionZ       desired Z velocity, blocks/tick
     * @param yaw           desired heading, degrees
     * @param pitch         desired pitch, degrees
     * @param thrustApplied whether the pilot is burning this tick (drives fuel + FX)
     */
    void applyFlightStep(EntityRocket rocket,
                         double motionX, double motionY, double motionZ,
                         float yaw, float pitch, boolean thrustApplied);

    /**
     * Whether this backend OWNS the craft's world transform. When {@code true},
     * Free Flight must NOT also write {@code motion*} / call {@code Entity.move()}
     * / run its client dead-reckoning, and the camera-nose lock must read the
     * craft orientation from the backend (i.e. from VS) rather than from the
     * entity's {@code rotationYaw/Pitch}. The legacy backend returns
     * {@code false}; the VS backend returns {@code true}.
     */
    boolean ownsTransform();
}
