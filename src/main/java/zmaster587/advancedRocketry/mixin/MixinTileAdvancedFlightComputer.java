package zmaster587.advancedRocketry.mixin;

import net.minecraft.util.math.BlockPos;

import org.joml.AxisAngle4d;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.valkyrienskies.mod.common.physics.IPhysicsBlockController;
import org.valkyrienskies.mod.common.physics.PhysicsCalculations;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;
import valkyrienwarfare.api.TransformType;

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
    /** Attitude-hold P gain: desired angular speed per radian of orientation error (1/s). */
    private static final double AR_ATTITUDE_GAIN = 2.0;
    /** Cap on the attitude-hold desired angular speed (rad/s) — gentle, to stay under VS's
     *  "too fast" freeze while a large orientation error is being nulled. */
    private static final double AR_MAX_ANGULAR_SPEED = 1.5;
    /** Attitude-hold dead-band (rad, ~1.7°): once within it the angular channel disengages and
     *  the ship coasts. Actively braking residual spin at the target tripped VS's "too fast"
     *  freeze — so "already pointed" must mean "no torque", like "no input" does. */
    private static final double AR_ATTITUDE_DEADBAND = 0.03;

    private int arFlightControllerPriority;

    @Override
    public void onPhysicsTick(PhysicsObject physo, PhysicsCalculations calc, double dt) {
        if (dt <= 0.0) {
            return;
        }
        // Prefer this computer's PER-TILE command (the seated pilot's, published by its own
        // server tick); fall back to the static bring-up channels only for the test probes.
        TileAdvancedFlightComputer self = (TileAdvancedFlightComputer) (Object) this;
        double[] vCmd = self.commandedVelocity != null
                ? self.commandedVelocity : TileAdvancedFlightComputer.debugCommandedVelocity;
        double[] wCmd = self.commandedAngVel != null
                ? self.commandedAngVel : TileAdvancedFlightComputer.debugCommandedAngVel;
        double[] attCmd = self.targetAttitude != null
                ? self.targetAttitude : TileAdvancedFlightComputer.debugTargetAttitude;
        if ((vCmd == null || vCmd.length < 3) && (wCmd == null || wCmd.length < 3)
                && (attCmd == null || attCmd.length < 4)) {
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

        // Decide the DESIRED world-frame angular velocity. An attitude-hold target wins: read
        // the ship's current orientation and turn the shortest-arc error into a rate that nulls
        // it (P control, capped) — this is the interface Free Flight feeds. Otherwise a raw
        // angular-velocity command, engaged only for a real turn (braking residual spin toward
        // zero tripped VS's "too fast" freeze during straight flight).
        double wDesX = 0.0, wDesY = 0.0, wDesZ = 0.0;
        boolean haveAngular = false;
        if (attCmd != null && attCmd.length >= 4) {
            Quaterniond current = physo.getShipTransform().rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
            Quaterniond target = new Quaterniond(attCmd[1], attCmd[2], attCmd[3], attCmd[0]); // JOML x,y,z,w
            Quaterniond err = new Quaterniond(target).mul(new Quaterniond(current).conjugate()).normalize();
            AxisAngle4d aa = new AxisAngle4d().set(err);
            double angle = aa.angle > Math.PI ? aa.angle - 2.0 * Math.PI : aa.angle; // shortest arc
            // Within the dead-band the ship is "pointed": disengage so we don't brake residual
            // spin into the "too fast" freeze. Outside it, drive the error out at a capped rate.
            if (Math.abs(angle) >= AR_ATTITUDE_DEADBAND) {
                double speed = angle * AR_ATTITUDE_GAIN;
                if (speed > AR_MAX_ANGULAR_SPEED) speed = AR_MAX_ANGULAR_SPEED;
                if (speed < -AR_MAX_ANGULAR_SPEED) speed = -AR_MAX_ANGULAR_SPEED;
                wDesX = aa.x * speed; wDesY = aa.y * speed; wDesZ = aa.z * speed;
                haveAngular = true;
            }
        } else if (wCmd != null && wCmd.length >= 3
                && (wCmd[0] * wCmd[0] + wCmd[1] * wCmd[1] + wCmd[2] * wCmd[2])
                        > AR_ANGULAR_CMD_EPSILON * AR_ANGULAR_CMD_EPSILON) {
            wDesX = wCmd[0]; wDesY = wCmd[1]; wDesZ = wCmd[2];
            haveAngular = true;
        }

        // Angular: deadbeat toward the desired angular velocity; torque = MOI · desiredAngAccel
        // (full inertia TENSOR, not a scalar — VS integrates α = MOI⁻¹·τ, so this yields exactly
        // the commanded angular accel; a scalar "inertia along axis" is zero when the ship isn't
        // already spinning and wrong otherwise, giving no torque or the "too fast" freeze).
        double tx = 0.0, ty = 0.0, tz = 0.0;
        if (haveAngular) {
            Vector3d w = calc.getAngularVelocity();
            double alx = (wDesX - w.x) / dt;
            double aly = (wDesY - w.y) / dt;
            double alz = (wDesZ - w.z) / dt;
            double alm = Math.sqrt(alx * alx + aly * aly + alz * alz);
            if (alm > AR_MAX_ANGULAR_ACCEL && alm > 1e-9) {
                double s = AR_MAX_ANGULAR_ACCEL / alm;
                alx *= s; aly *= s; alz *= s;
            }
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
