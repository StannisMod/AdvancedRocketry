package zmaster587.advancedRocketry.integration.vs;

import java.util.Optional;

import com.google.common.collect.ImmutableList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.apache.logging.log4j.Logger;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.block_relocation.BlockFinder;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;
import org.valkyrienskies.mod.common.ships.ship_world.WorldServerShipManager;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;
import valkyrienwarfare.api.TransformType;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;

/**
 * The Valkyrien Skies-facing side of the integration. Every reference to an
 * {@code org.valkyrienskies.*} type lives in this package's bridge classes,
 * never in {@link VSIntegration}. The JVM loads this class only when
 * {@link VSIntegration#isAvailable()} is true, so its VS imports never need to
 * resolve on an AR install without VS.
 */
final class VSBridge {

    private VSBridge() {}

    static void onValkyrienSkiesPresent(Logger logger) {
        // Touch a stable VS API type to anchor the compile dependency and to
        // prove, at runtime, that the VS classpath actually resolved.
        logger.info("Valkyrien Skies detected — true-spaceship integration active (API root: {}).",
                ValkyrienUtils.class.getName());
    }

    /**
     * Assemble the connected structure seeded at {@code anchorPos} into a movable
     * ship. This is the player-less equivalent of VS's
     * {@code assembleShipAsOrderedByPlayer}: create a ship keyed on the anchor
     * block, then queue VS to relocate every connected block into it. Called
     * server-side; VS performs the relocation on its own physics thread, so the
     * ship does not exist synchronously when this returns.
     *
     * <p>Scope note: this queues the block relocation only. Making the resulting
     * ship pilotable (thrust, attitude) is handled by the flight-control layer;
     * runtime behaviour can only be exercised with VS actually installed, not in a
     * headless test.</p>
     */
    static void assembleTier2Ship(World world, BlockPos anchorPos, Logger logger) {
        ShipData ship = ValkyrienUtils.createNewShip(world, anchorPos);
        WorldServerShipManager manager = ValkyrienUtils.getServerShipManager(world);
        manager.queueShipSpawn(ship, anchorPos, BlockFinder.BlockFinderType.FIND_ALL_BLOCKS);
        logger.info("Queued tier-2 ship assembly at {} (ship '{}').", anchorPos, ship.getName());
    }

    /**
     * The body&rarr;world attitude of the ship managing the block at {@code pos}, as
     * an AR-core {@link FreeFlightPhysics.Quat}, or {@code null} if no ship manages
     * it yet. This is the ship's own transform (VS is the source of truth); Free
     * Flight integrates the pilot's body rates over it each tick.
     */
    static FreeFlightPhysics.Quat getShipAttitude(World world, BlockPos pos) {
        Optional<PhysicsObject> managing = ValkyrienUtils.getPhysoManagingBlock(world, pos);
        if (!managing.isPresent()) {
            return null;
        }
        Quaterniond q = managing.get().getShipData().getShipTransform()
                .rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
        return new FreeFlightPhysics.Quat(q.w, q.x, q.y, q.z);
    }

    /** Enable physics on the ship managing the block at {@code pos}, if any (a safe no-op
     *  otherwise). Lets the Advanced Flight Computer tile activate its own ship's physics. */
    static void ensureShipPhysicsEnabled(World world, BlockPos pos) {
        Optional<PhysicsObject> managing = ValkyrienUtils.getPhysoManagingBlock(world, pos);
        if (managing.isPresent()) {
            managing.get().getShipData().setPhysicsEnabled(true);
        }
    }

    /** Number of Valkyrien Skies ships currently loaded in {@code world}. */
    static int loadedShipCount(World world) {
        return ValkyrienUtils.getServerShipManager(world).getAllLoadedThreadSafe().size();
    }

    /**
     * Total Valkyrien Skies ships known in {@code world}, loaded or not — the queryable
     * ship registry, which includes a freshly-spawned ship whose shipyard chunks are
     * not yet loaded. Distinguishes "ship created but not loaded" from "never created".
     */
    static int queryableShipCount(World world) {
        return ValkyrienUtils.getQueryableData(world).getShips().size();
    }

    /**
     * Force every known ship in {@code world} loaded and physics-enabled. VS only loads a
     * ship when a player is near its wrapper; a ship freshly assembled with no player
     * nearby (e.g. an automated server) stays in the registry but unloaded — it never
     * ticks, drives, or appears in the loaded set. This queues a load and enables physics
     * for each. Returns how many ships it requested. (In real play a nearby client loads
     * the ship itself; this is the headless/no-observer equivalent.)
     */
    static int loadAllShips(World world) {
        WorldServerShipManager manager = ValkyrienUtils.getServerShipManager(world);
        int requested = 0;
        for (ShipData ship : ValkyrienUtils.getQueryableData(world).getShips()) {
            ship.setPhysicsEnabled(true);
            manager.queueShipLoad(ship.getUuid());
            requested++;
        }
        return requested;
    }

    /**
     * State of the loaded ship whose world position is nearest to {@code (x,y,z)}, as a
     * flat array {@code [posX, posY, posZ, qw, qx, qy, qz, velX, velY, velZ]} (world-frame
     * position + body&rarr;world attitude + linear velocity), or {@code null} if no ship is
     * loaded. Nearest-to-a-point (not "first") so a shared server carrying several ships
     * disambiguates by build site. Only primitive/MC types cross back to AR core.
     */
    static double[] nearestShipState(World world, double x, double y, double z) {
        PhysicsObject physo = nearestShip(world, x, y, z);
        if (physo == null) {
            return null;
        }
        ShipTransform transform = physo.getShipData().getShipTransform();
        Vec3d pos = transform.getShipPositionVec3d();
        Quaterniond q = transform.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
        Vector3dc vel = physo.getPhysicsData().getLinearVelocity();
        return new double[]{pos.x, pos.y, pos.z, q.w, q.x, q.y, q.z, vel.x(), vel.y(), vel.z()};
    }

    /**
     * Set the linear-velocity setpoint (blocks/second, world frame) of the loaded ship
     * nearest to {@code (x,y,z)}; returns false if no ship is loaded. Used by the test
     * probe to prove VS physics moves a bare AR-assembled ship (flight-control model A).
     */
    static boolean pushNearestShip(World world, double x, double y, double z,
                                   double vx, double vy, double vz) {
        PhysicsObject physo = nearestShip(world, x, y, z);
        if (physo == null) {
            return false;
        }
        // A bare assembled ship is loaded but has physics disabled by default, so a
        // velocity setpoint is ignored. Enable physics (a flag, not a load — it does not
        // trip the spawn/proximity double-load) before applying the setpoint.
        physo.getShipData().setPhysicsEnabled(true);
        physo.getPhysicsData().setLinearVelocity(new Vector3d(vx, vy, vz));
        return true;
    }

    /**
     * Command the loaded ship nearest to {@code (x,y,z)} toward a world-frame velocity. The
     * force that realizes it is applied on the PHYSICS thread by the flight-controller mixin
     * on the Advanced Flight Computer tile ({@code MixinTileAdvancedFlightComputer}) — VS
     * ignores a velocity setpoint AND a game-thread force, so the only working path is a
     * per-physics-tick force from a ship-tile controller. Here we just enable physics and
     * publish the command that controller reads. Angular args are accepted for signature
     * stability but not yet used. Returns false if no ship is loaded.
     */
    static boolean commandNearestShipVelocity(World world, double x, double y, double z,
                                              double vx, double vy, double vz,
                                              double wx, double wy, double wz) {
        PhysicsObject physo = nearestShip(world, x, y, z);
        if (physo == null) {
            return false;
        }
        physo.getShipData().setPhysicsEnabled(true);
        TileAdvancedFlightComputer.debugCommandedVelocity = new double[]{vx, vy, vz};
        TileAdvancedFlightComputer.debugCommandedAngVel = new double[]{wx, wy, wz};
        return true;
    }

    /**
     * Command the loaded ship nearest to {@code (x,y,z)} to HOLD a target body→world attitude
     * (quaternion {@code w,x,y,z}) — the controller turns the orientation error into torque —
     * while hovering (linear velocity commanded to zero). Returns false if no ship is loaded.
     */
    static boolean commandNearestShipAttitude(World world, double x, double y, double z,
                                              double qw, double qx, double qy, double qz) {
        PhysicsObject physo = nearestShip(world, x, y, z);
        if (physo == null) {
            return false;
        }
        physo.getShipData().setPhysicsEnabled(true);
        TileAdvancedFlightComputer.debugCommandedVelocity = new double[]{0.0, 0.0, 0.0};
        TileAdvancedFlightComputer.debugCommandedAngVel = null;
        TileAdvancedFlightComputer.debugTargetAttitude = new double[]{qw, qx, qy, qz};
        return true;
    }

    private static PhysicsObject nearestShip(World world, double x, double y, double z) {
        PhysicsObject best = null;
        double bestDistSq = Double.MAX_VALUE;
        ImmutableList<PhysicsObject> ships =
                ValkyrienUtils.getServerShipManager(world).getAllLoadedThreadSafe();
        for (PhysicsObject physo : ships) {
            Vec3d pos = physo.getShipData().getShipTransform().getShipPositionVec3d();
            double distSq = pos.squareDistanceTo(x, y, z);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = physo;
            }
        }
        return best;
    }
}
