package zmaster587.advancedRocketry.mixin;

import net.minecraft.util.math.BlockPos;

import org.joml.AxisAngle4d;
import org.joml.Matrix3dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.valkyrienskies.mod.common.config.VSConfig;
import org.valkyrienskies.mod.common.physics.IPhysicsBlockController;
import org.valkyrienskies.mod.common.physics.PhysicsCalculations;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;
import valkyrienwarfare.api.TransformType;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
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
 * from {@link TileAdvancedFlightComputer#commandedVelocity} — this tile's own, written on the
 * game thread and read here on the physics thread.</p>
 */
@Mixin(TileAdvancedFlightComputer.class)
public abstract class MixinTileAdvancedFlightComputer implements IPhysicsBlockController {

    /** Linear thrust authority (blocks/s²) — caps the deadbeat acceleration. Tuned at runtime. */
    private static final double AR_MAX_LINEAR_ACCEL = 40.0;
    /** Angular thrust authority (rad/s²) — caps the deadbeat angular acceleration. */
    private static final double AR_MAX_ANGULAR_ACCEL = 4.0;
    /** Below this commanded angular speed (rad/s) a RAW angular-velocity command counts as absent.
     *  Only the probe channel uses that command; the attitude-hold path below brakes residual spin. */
    private static final double AR_ANGULAR_CMD_EPSILON = 1.0e-4;
    /** Attitude-hold P gain: desired angular speed per radian of orientation error (1/s). */
    private static final double AR_ATTITUDE_GAIN = 2.0;
    /** Cap on the attitude-hold desired angular speed (rad/s). Also the ceiling on how fast the ship
     *  slews toward a large commanded change, so a hard flick of the mouse is a sweep, not a snap. Sits
     *  just above the fastest rate the pilot can command (5 deg/tick of roll = 1.75 rad/s), so a ship at
     *  full deflection tracks its attitude reference instead of forever lagging it. Two orders of
     *  magnitude below the physics mod's ~223 rad/s sanity freeze. */
    private static final double AR_MAX_ANGULAR_SPEED = 2.0;

    private int arFlightControllerPriority;

    @Override
    public void onPhysicsTick(PhysicsObject physo, PhysicsCalculations calc, double dt) {
        if (dt <= 0.0) {
            return;
        }
        // This computer's own command, and nobody else's. A bring-up probe aimed at THIS tile takes
        // the whole triple (all-or-nothing: a per-channel mix would blend a fresh probe pose with a
        // stale probe rate); otherwise the seated pilot's, published by this tile's own server tick.
        TileAdvancedFlightComputer self = (TileAdvancedFlightComputer) (Object) this;
        boolean probe = self.probeCommandActive;
        double[] vCmd = probe ? self.probeVelocity : self.commandedVelocity;
        double[] wCmd = probe ? self.probeAngVel : self.commandedAngVel;
        double[] attCmd = probe ? self.probeAttitude : self.targetAttitude;
        if ((vCmd == null || vCmd.length < 3) && (wCmd == null || wCmd.length < 3)
                && (attCmd == null || attCmd.length < 4)) {
            return;
        }

        // Linear: deadbeat toward the commanded world velocity, PLUS a gravity feed-forward; force =
        // mass · accel (mass cancels, so the ship accelerates as commanded regardless of how heavy it
        // is). VS runs applyGravity() before this controller in the same physics step and reads the
        // velocity BEFORE the controller's force is integrated, so it adds gravity*dt to the ship's
        // velocity every tick no matter what we command. A bare deadbeat therefore settles at
        // vCmd + gravity*dt — a ship told to hover sinks at a steady -g*dt (~0.16 blk/s at 9.8/60, the
        // -0.01/tick HUD residual). Feeding gravity forward cancels the velocity the solver is about to
        // add, so vCmd is truly held and a zero command is a real hover.
        double fx = 0.0, fy = 0.0, fz = 0.0;
        if (vCmd != null && vCmd.length >= 3) {
            double mass = calc.getMass();
            Vector3d v = calc.getLinearVelocity();
            double gx = 0.0, gy = 0.0, gz = 0.0;
            if (VSConfig.doGravity) {
                Vector3dc g = VSConfig.gravity();
                gx = g.x(); gy = g.y(); gz = g.z();
            }
            double[] a = FreeFlightPhysics.shipControlAccel(vCmd[0], vCmd[1], vCmd[2],
                    v.x, v.y, v.z, dt, gx, gy, gz, AR_MAX_LINEAR_ACCEL);
            fx = a[0] * mass; fy = a[1] * mass; fz = a[2] * mass;
        }

        // Angular: a PD law. An attitude-hold target wins — read the ship's current orientation, turn
        // the shortest-arc error into a desired rate (P, capped), then deadbeat toward that rate. When
        // the error is already null the desired rate is ZERO and the deadbeat becomes -w/dt: it BRAKES
        // residual spin. That braking is the point. A rocket's attitude is kinematic state, so a
        // centred cursor freezes it; a ship is a rigid body carrying angular momentum, so a centred
        // cursor must actively stop it, or it keeps turning until something else bleeds the spin.
        //
        // An earlier revision disengaged inside a 0.03 rad dead-band, believing the brake tripped VS's
        // "ship moving too fast" guard. It does not: that guard trips at |w|^2 > 50000 (|w| > ~223
        // rad/s), some 150x above AR_MAX_ANGULAR_SPEED, or on a non-finite velocity. The real historical
        // cause was a NaN torque from a scalar inertia, which the MOI tensor below already fixed.
        Vector3d w = calc.getAngularVelocity();
        double[] angAccel = null;
        if (attCmd != null && attCmd.length >= 4) {
            Quaterniond current = physo.getShipTransform().rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
            Quaterniond target = new Quaterniond(attCmd[1], attCmd[2], attCmd[3], attCmd[0]); // JOML x,y,z,w
            Quaterniond err = new Quaterniond(target).mul(new Quaterniond(current).conjugate()).normalize();
            AxisAngle4d aa = new AxisAngle4d().set(err);
            double angle = aa.angle > Math.PI ? aa.angle - 2.0 * Math.PI : aa.angle; // shortest arc
            // With an attitude target, an angular-velocity command is the rate that target is TURNING
            // at - the feed-forward, not an independent command.
            double ffX = 0.0, ffY = 0.0, ffZ = 0.0;
            if (wCmd != null && wCmd.length >= 3) {
                ffX = wCmd[0]; ffY = wCmd[1]; ffZ = wCmd[2];
            }
            angAccel = FreeFlightPhysics.attitudeHoldAngAccel(aa.x, aa.y, aa.z, angle,
                    ffX, ffY, ffZ, w.x, w.y, w.z, dt,
                    AR_ATTITUDE_GAIN, AR_MAX_ANGULAR_SPEED, AR_MAX_ANGULAR_ACCEL);
        } else if (wCmd != null && wCmd.length >= 3
                && (wCmd[0] * wCmd[0] + wCmd[1] * wCmd[1] + wCmd[2] * wCmd[2])
                        > AR_ANGULAR_CMD_EPSILON * AR_ANGULAR_CMD_EPSILON) {
            // Raw rate command (probe channel only): deadbeat toward it, no attitude reference.
            double alx = (wCmd[0] - w.x) / dt;
            double aly = (wCmd[1] - w.y) / dt;
            double alz = (wCmd[2] - w.z) / dt;
            double alm = Math.sqrt(alx * alx + aly * aly + alz * alz);
            if (alm > AR_MAX_ANGULAR_ACCEL && alm > 1e-9) {
                double s = AR_MAX_ANGULAR_ACCEL / alm;
                alx *= s; aly *= s; alz *= s;
            }
            angAccel = new double[]{alx, aly, alz};
        }

        // torque = MOI · desiredAngAccel, with the full inertia TENSOR — VS integrates
        // alpha = MOI^-1 · tau, so this yields exactly the commanded angular accel. A scalar "inertia
        // along the rotation axis" is zero when the ship is not already spinning (no torque) and wrong
        // otherwise.
        double tx = 0.0, ty = 0.0, tz = 0.0;
        if (angAccel != null) {
            Matrix3dc moi = calc.getPhysMOITensor();
            Vector3d torque = new Vector3d(angAccel[0], angAccel[1], angAccel[2]);
            moi.transform(torque);
            tx = torque.x; ty = torque.y; tz = torque.z;
        }

        TileAdvancedFlightComputer.debugControllerState = new double[]{
                dt,
                angAccel == null ? 0.0 : angAccel[0],
                angAccel == null ? 0.0 : angAccel[1],
                angAccel == null ? 0.0 : angAccel[2],
                w.x, w.y, w.z,
                angAccel == null ? -1.0 : 1.0};

        // Flight recorder, physics-thread channel: one sample per physics step. This is the clock
        // the ship's velocity actually integrates on, and it is NOT the game tick — an interval
        // that wanders here is the physics loop failing to hold its rate, which no server-side tick
        // measurement can see. Costs a handful of stores into a preallocated ring.
        // The PHYSICS transform, not the game-tick one. They are different objects and only one of
        // them advances on this clock: sampling `getShipTransform()` from a 60 Hz hook gives a pose
        // that only changes 20 times a second, so two thirds of the samples read as "did not move"
        // and the rest as a triple step — a metronomic ship reported as a stuttering one, by the
        // instrument alone. Measured on the first calibration run: median step 0.0, p95 2.0.
        org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform pose =
                physo.getShipTransformationManager().getCurrentPhysicsTransform();
        Vector3d vNow = calc.getLinearVelocity();
        double cmdSpeed = vCmd == null || vCmd.length < 3 ? 0.0
                : Math.sqrt(vCmd[0] * vCmd[0] + vCmd[1] * vCmd[1] + vCmd[2] * vCmd[2]);
        double accelMag = calc.getMass() <= 0.0 ? 0.0
                : Math.sqrt(fx * fx + fy * fy + fz * fz) / calc.getMass();
        BlockPos self2 = self.getPos();
        zmaster587.advancedRocketry.util.MotionTrace.phys(
                zmaster587.advancedRocketry.util.MotionTrace.keyOf(
                        physo.getWorld().provider.getDimension(),
                        self2.getX(), self2.getY(), self2.getZ()),
                // WHO drove this step, and on WHICH ship. A block's flight computer is one object on
                // one ship, so a window carrying two of either is a state to go and look at rather
                // than a number to average — and the two cases have different causes: two
                // controllers on one ship is a stale tile instance that outlived its replacement in
                // the ship's controller set, two ships is two craft claiming one computer.
                System.identityHashCode(self),
                physo.getUuid() == null ? 0.0 : physo.getUuid().hashCode(),
                dt, pose.getPosX(), pose.getPosY(), pose.getPosZ(),
                Math.sqrt(vNow.x * vNow.x + vNow.y * vNow.y + vNow.z * vNow.z),
                cmdSpeed, calc.getMass(),
                // "Clamped" is read back off the acceleration that was actually applied rather than
                // reported by the clamp itself: at the authority ceiling the controller is no longer
                // tracking its command, and that is the observable fact worth recording.
                accelMag >= AR_MAX_LINEAR_ACCEL - 1.0e-6);

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
