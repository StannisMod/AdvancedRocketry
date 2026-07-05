package zmaster587.advancedRocketry.mixin;

import net.minecraft.util.math.BlockPos;

import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.valkyrienskies.mod.common.physics.IPhysicsBlockController;
import org.valkyrienskies.mod.common.physics.PhysicsCalculations;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;

import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;

/**
 * Makes the Advanced Flight Computer tile a Valkyrien Skies force controller. VS collects a
 * ship's {@link IPhysicsBlockController} tiles (via {@code PhysicsObject.onSetTileEntity})
 * and calls {@link #onPhysicsTick} on each every physics step, ON THE PHYSICS THREAD — the
 * only place a force actually integrates into ship motion (a velocity setpoint or a
 * game-thread force are both overwritten by the solver, confirmed at runtime).
 *
 * <p>This mixin is applied ONLY when the physics mod is on the classpath (gated by
 * {@link ARMixinPlugin}); without it the interface would not resolve. So the AR tile class
 * itself never hard-references a physics-mod type — the soft dependency stays intact.</p>
 *
 * <p>Control law: a deadbeat toward the commanded world velocity, mass-cancelling so the
 * ship accelerates as commanded regardless of how heavy it is:
 * {@code force = mass · clamp((vDesired − vCurrent) / dt, authority)}. The command is read
 * from {@link TileAdvancedFlightComputer#debugCommandedVelocity} (game thread writes, this
 * physics thread reads).</p>
 */
@Mixin(TileAdvancedFlightComputer.class)
public abstract class MixinTileAdvancedFlightComputer implements IPhysicsBlockController {

    /** Linear thrust authority (blocks/s²) — caps the deadbeat acceleration. Tuned at runtime. */
    private static final double AR_MAX_LINEAR_ACCEL = 40.0;
    /** Angular thrust authority (rad/s²) — caps the deadbeat angular acceleration. Kept gentle:
     *  an aggressive torque overshoots and trips VS's "ship moving too fast" freeze. */
    private static final double AR_MAX_ANGULAR_ACCEL = 4.0;
    /** Below this commanded angular speed (rad/s) the angular channel is left alone entirely —
     *  we do NOT brake residual spin. Braking an unknown-frame residual ω was tripping the VS
     *  "too fast" freeze during pure-translation flight; "no yaw input" must mean "no torque". */
    private static final double AR_ANGULAR_CMD_EPSILON = 1.0e-4;

    private int arFlightControllerPriority;

    @Override
    public void onPhysicsTick(PhysicsObject physo, PhysicsCalculations calc, double dt) {
        if (dt <= 0.0) {
            return;
        }
        double[] vCmd = TileAdvancedFlightComputer.debugCommandedVelocity;
        double[] wCmd = TileAdvancedFlightComputer.debugCommandedAngVel;
        if ((vCmd == null || vCmd.length < 3) && (wCmd == null || wCmd.length < 3)) {
            return;
        }

        // Linear: deadbeat toward the commanded world velocity; force = mass · accel (mass
        // cancels, so the ship accelerates as commanded regardless of how heavy it is).
        double fx = 0.0, fy = 0.0, fz = 0.0;
        if (vCmd != null && vCmd.length >= 3) {
            double mass = calc.getMass();
            Vector3d v = calc.getLinearVelocity();
            double ax = (vCmd[0] - v.x) / dt;
            double ay = (vCmd[1] - v.y) / dt;
            double az = (vCmd[2] - v.z) / dt;
            double am = Math.sqrt(ax * ax + ay * ay + az * az);
            if (am > AR_MAX_LINEAR_ACCEL && am > 1e-9) {
                double s = AR_MAX_LINEAR_ACCEL / am;
                ax *= s; ay *= s; az *= s;
            }
            fx = ax * mass; fy = ay * mass; fz = az * mass;
        }

        // Angular: deadbeat toward the commanded angular velocity; torque = inertia · angAccel.
        // Only engaged when a real turn is commanded — braking residual spin toward zero is
        // NOT done here (it tripped VS's "too fast" freeze during straight flight).
        double tx = 0.0, ty = 0.0, tz = 0.0;
        if (wCmd != null && wCmd.length >= 3
                && (wCmd[0] * wCmd[0] + wCmd[1] * wCmd[1] + wCmd[2] * wCmd[2])
                        > AR_ANGULAR_CMD_EPSILON * AR_ANGULAR_CMD_EPSILON) {
            Vector3d w = calc.getAngularVelocity();
            double alx = (wCmd[0] - w.x) / dt;
            double aly = (wCmd[1] - w.y) / dt;
            double alz = (wCmd[2] - w.z) / dt;
            double alm = Math.sqrt(alx * alx + aly * aly + alz * alz);
            if (alm > AR_MAX_ANGULAR_ACCEL && alm > 1e-9) {
                double s = AR_MAX_ANGULAR_ACCEL / alm;
                alx *= s; aly *= s; alz *= s;
            }
            // torque = MOI · desiredAngularAccel (full inertia TENSOR, not a scalar): VS
            // integrates α = MOI⁻¹·τ, so this yields exactly the commanded angular accel. A
            // scalar "inertia along axis" is zero when the ship is not already spinning and
            // wrong otherwise, which either produced no torque or an over-fast one (VS's
            // "ship moving too fast" freeze).
            Matrix3dc moi = calc.getPhysMOITensor();
            Vector3d torque = new Vector3d(alx, aly, alz);
            moi.transform(torque);
            tx = torque.x; ty = torque.y; tz = torque.z;
        }

        calc.addForceAndTorque(new Vector3d(fx, fy, fz), new Vector3d(tx, ty, tz));
    }

    @Override
    public BlockPos getNodePos() {
        return ((TileAdvancedFlightComputer) (Object) this).getPos();
    }

    @Override
    public int getPriority() {
        return arFlightControllerPriority;
    }

    @Override
    public void setPriority(int priority) {
        this.arFlightControllerPriority = priority;
    }
}
