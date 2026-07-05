package zmaster587.advancedRocketry.entity;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;

/**
 * Pluggable backend that realizes a piloted craft's per-tick FREE-FLIGHT movement
 * — the seam that lets the same pilot intent drive either the craft's own entity
 * transform or a rigid-body ship physics engine.
 *
 * <h2>Why this exists</h2>
 * Free Flight computes a desired kinematic state from pilot input in its pure,
 * side-effect-free {@link FreeFlightPhysics} layer — a body&rarr;world attitude
 * {@link FreeFlightPhysics.Quat} plus a translation {@link FreeFlightPhysics.Step}
 * (resulting linear velocity in blocks/tick) — then <em>applies</em> it. The
 * application step is exactly what fights an external physics engine that wants to
 * own the craft's transform: the attitude commit, any replication, the
 * {@code motion*} write and the {@code Entity.move()} displacement.
 *
 * <p>A backend is bound to the specific craft it drives (the enclosing entity, or a
 * physics ship handle), so this contract carries only the desired state — not the
 * craft. It abstracts the application step so the same desired state can drive
 * either:</p>
 * <ul>
 *   <li>the <b>legacy</b> backend — commit the attitude, replicate it, write entity
 *       {@code motion*} and call {@code Entity.move()} (today's rocket behaviour); or</li>
 *   <li>a <b>ship-physics</b> backend — feed the desired linear velocity (the
 *       {@code Step}) and orientation (the {@code Quat}) into the craft's ship as a
 *       setpoint and let the physics engine own the displacement.</li>
 * </ul>
 *
 * <p>The contract carries the FULL body-frame attitude (a quaternion — no gimbal
 * lock, so loops and inversions work) and the resulting {@code Step}, not a scalar
 * yaw/pitch, because that is what Free Flight actually produces each tick.</p>
 */
public interface IFlightBackend {

    /**
     * Realize one tick of desired free-flight state on the craft this backend is
     * bound to.
     *
     * @param attitude     desired body&rarr;world attitude for this tick
     *                     (already integrated from the pilot's body rates)
     * @param step         resulting translation for this tick — its
     *                     {@code motion*} are the desired linear velocity in
     *                     blocks/tick, and {@code thrustApplied} drives fuel + FX
     * @param enginePower  engine power level [0,1] for this tick (replicated so the
     *                     client engine sound tracks actual thrust)
     */
    void applyFlightState(FreeFlightPhysics.Quat attitude,
                          FreeFlightPhysics.Step step,
                          float enginePower);

    /**
     * Whether this backend OWNS the craft's world transform. When {@code true},
     * Free Flight must NOT also write {@code motion*} / call {@code Entity.move()}
     * / run its client dead-reckoning, and the camera-nose lock must read the craft
     * orientation from the backend (i.e. from the ship physics) rather than from the
     * entity's replicated attitude. The legacy backend returns {@code false}.
     */
    boolean ownsTransform();
}
