package zmaster587.advancedRocketry.integration.vs;

import java.util.Optional;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.valkyrienskies.mod.common.ships.physics_data.ShipPhysicsData;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;
import valkyrienwarfare.api.TransformType;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.entity.IFlightBackend;

/**
 * Model-A flight backend for a Valkyrien Skies ship: it realizes Free Flight's
 * per-tick desired state as a VELOCITY SETPOINT on the ship's rigid body, so VS
 * owns the transform and no AR entity is moved. This is the tier-2 counterpart of
 * {@code EntityRocket.LegacyFlightBackend}; both implement {@link IFlightBackend},
 * so the same {@link FreeFlightPhysics} decision layer drives either craft.
 *
 * <p>Bound to a ship by the world position of its anchor block (the Advanced Flight
 * Computer). The managing {@code PhysicsObject} is resolved each tick and the call
 * is a safe no-op until the ship exists (VS assembles asynchronously).</p>
 *
 * <p><b>Untuned first cut.</b> The numeric conventions below — the per-tick&rarr;
 * per-second velocity scale, whether {@code setAngularVelocity} is world- or
 * body-frame, the attitude-tracking gain, and quaternion handedness — can only be
 * confirmed against a live ship in {@code runClient -PwithVS}. They are written to
 * the most likely convention and marked so; expect to tune them during the runtime
 * bring-up. Nothing here is exercised until the Advanced Flight Computer tile drives
 * it, so a wrong value cannot affect existing gameplay.</p>
 */
final class VSFlightBackend implements IFlightBackend {

    /** Minecraft ticks per second — Step motion is per-tick, VS velocity is per-second. */
    private static final double TICKS_PER_SECOND = 20.0;

    private final World world;
    private final BlockPos anchorPos;

    VSFlightBackend(World world, BlockPos anchorPos) {
        this.world = world;
        this.anchorPos = anchorPos;
    }

    @Override
    public void applyFlightState(FreeFlightPhysics.Quat attitude,
                                 FreeFlightPhysics.Step step,
                                 float enginePower) {
        Optional<PhysicsObject> managing = ValkyrienUtils.getPhysoManagingBlock(world, anchorPos);
        if (!managing.isPresent()) {
            return; // ship not assembled / not loaded yet — nothing to drive
        }
        PhysicsObject physo = managing.get();
        ShipPhysicsData data = physo.getPhysicsData();

        // Linear: Free Flight already outputs the desired WORLD-frame velocity in
        // blocks/tick; VS expects blocks/second, so scale by the tick rate.
        data.setLinearVelocity(new Vector3d(step.motionX, step.motionY, step.motionZ)
                .mul(TICKS_PER_SECOND));

        // Angular: command the angular velocity that rotates the ship's CURRENT
        // orientation toward FF's target attitude within ~one tick (proportional
        // tracking). SUBSPACE_TO_GLOBAL is the ship's body->world rotation, matching
        // FF's body->world Quat.
        Quaterniond current = physo.getShipData().getShipTransform()
                .rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
        Quaterniond target = new Quaterniond(attitude.x, attitude.y, attitude.z, attitude.w);
        // delta = target * current^-1  (world-frame rotation from current to target)
        Quaterniond delta = new Quaterniond(target).mul(new Quaterniond(current).conjugate());
        AxisAngle4d aa = new AxisAngle4d().set(delta.normalize());
        // Shortest path: fold angles > pi to the negative side.
        double angle = aa.angle > Math.PI ? aa.angle - 2.0 * Math.PI : aa.angle;
        data.setAngularVelocity(new Vector3d(aa.x, aa.y, aa.z).mul(angle * TICKS_PER_SECOND));
    }

    @Override
    public boolean ownsTransform() {
        // VS owns the ship's displacement and rotation; Free Flight must NOT also
        // write entity motion / run its client dead-reckoning for this craft.
        return true;
    }
}
