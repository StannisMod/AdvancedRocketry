package zmaster587.advancedRocketry.mixin;

import net.minecraft.util.math.BlockPos;

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

    private int arFlightControllerPriority;

    @Override
    public void onPhysicsTick(PhysicsObject physo, PhysicsCalculations calc, double dt) {
        double[] cmd = TileAdvancedFlightComputer.debugCommandedVelocity;
        if (cmd == null || cmd.length < 3 || dt <= 0.0) {
            return;
        }
        double mass = calc.getMass();
        Vector3d v = calc.getLinearVelocity();
        double ax = (cmd[0] - v.x) / dt;
        double ay = (cmd[1] - v.y) / dt;
        double az = (cmd[2] - v.z) / dt;
        double am = Math.sqrt(ax * ax + ay * ay + az * az);
        if (am > AR_MAX_LINEAR_ACCEL && am > 1e-9) {
            double s = AR_MAX_LINEAR_ACCEL / am;
            ax *= s; ay *= s; az *= s;
        }
        calc.addForceAndTorque(new Vector3d(ax * mass, ay * mass, az * mass),
                new Vector3d(0.0, 0.0, 0.0));
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
