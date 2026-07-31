package zmaster587.advancedRocketry.integration.vs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.google.common.collect.ImmutableList;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.apache.logging.log4j.Logger;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.mod.common.entity.EntityShipMovementData;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.block_relocation.BlockFinder;
import org.valkyrienskies.mod.common.ships.entity_interaction.EntityShipMountData;
import org.valkyrienskies.mod.common.ships.entity_interaction.IDraggable;
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

    /**
     * The world-frame POSITION {@code [x,y,z]} of the ship managing the block at {@code pos}
     * (its transform position — where the ship's pose actually is right now), or {@code null}
     * if no ship manages it. Managed-block-keyed like {@link #getShipAttitude}, so on a shared
     * server each flight computer reads its OWN ship's position — a nearest-ship read could
     * answer for a neighbour's craft. The entry ceiling check compares this against the launch
     * dimension's orbit height each tick.
     */
    static double[] shipWorldPosition(World world, BlockPos pos) {
        Optional<PhysicsObject> managing = ValkyrienUtils.getPhysoManagingBlock(world, pos);
        if (!managing.isPresent()) {
            return null;
        }
        Vec3d p = managing.get().getShipData().getShipTransform().getShipPositionVec3d();
        return new double[]{p.x, p.y, p.z};
    }

    /**
     * The physics mod's hard ceiling for a ship's world-frame altitude ("Ship Y Position
     * Maximum"): VS clamps every ship's pose to this Y each physics step, so no ship can climb
     * above it under any thrust, whatever AR believes about orbit heights.
     */
    static double shipYPositionMaximum() {
        return org.valkyrienskies.mod.common.config.VSConfig.shipUpperLimit;
    }

    /**
     * Raise the physics mod's ship altitude ceiling to AT LEAST {@code required}. The clamp is a
     * global static applied per physics step; the space cells realize ship poses megablocks above
     * the stock value, and a ship's own thrust can never carry it past the clamp - so the ceiling
     * must cover the whole pose band BEFORE the first ship arrives, deterministically, not be
     * ratcheted up teleport-by-teleport. Never lowers a value the user configured higher; the
     * raise is per-session (the VS config file is not written back).
     */
    static void raiseShipCeilingTo(double required, Logger logger) {
        if (org.valkyrienskies.mod.common.config.VSConfig.shipUpperLimit < required) {
            logger.info("Raising the physics ship altitude ceiling {} -> {} to cover the space cells.",
                    org.valkyrienskies.mod.common.config.VSConfig.shipUpperLimit, required);
            org.valkyrienskies.mod.common.config.VSConfig.shipUpperLimit = required;
        }
    }

    /**
     * The WORLD position of the seat block at ship-subspace {@code seatPos}, as
     * {@code [x, y, z]}, or {@code null} if no ship manages it. The seat block lives in the
     * ship's subspace (a fixed shipyard region) but is rendered — and must be occupied by its
     * seated pilot — at the ship's live world location; this maps its subspace centre through the
     * ship transform ({@code SUBSPACE_TO_GLOBAL}) so a rider can be glued to the moving ship.
     * The {@code +0.2} vertical offset mirrors the mount point {@code BlockPilotSeat} spawns at.
     * Only primitive/MC types cross back to AR core.
     */
    static double[] seatWorldPosition(World world, BlockPos seatPos) {
        Optional<PhysicsObject> managing = ValkyrienUtils.getPhysoManagingBlock(world, seatPos);
        if (!managing.isPresent()) {
            return null;
        }
        ShipTransform transform = managing.get().getShipData().getShipTransform();
        Vec3d subspaceSeat = new Vec3d(seatPos.getX() + 0.5, seatPos.getY() + 0.2, seatPos.getZ() + 0.5);
        Vec3d worldSeat = transform.transform(subspaceSeat, TransformType.SUBSPACE_TO_GLOBAL);
        return new double[]{worldSeat.x, worldSeat.y, worldSeat.z};
    }

    /**
     * The world-frame linear velocity {@code [x,y,z]} (blocks/second) of the ship managing the
     * block at {@code pos}, or {@code null} if no ship manages it. Lets the flight computer capture
     * the ship's live velocity as a body-frame setpoint when the pilot re-enables Flight Assist, so
     * the cruise control engages at the current speed instead of jerking to a stop.
     */
    static double[] shipLinearVelocity(World world, BlockPos pos) {
        Optional<PhysicsObject> managing = ValkyrienUtils.getPhysoManagingBlock(world, pos);
        if (!managing.isPresent()) {
            return null;
        }
        Vector3dc v = managing.get().getPhysicsData().getLinearVelocity();
        return new double[]{v.x(), v.y(), v.z()};
    }

    /**
     * The world-frame velocity {@code [x,y,z]} (blocks/second) of the ship AT the point {@code (x,y,z)} -
     * the ship's linear velocity PLUS the tangential velocity of its rotation there ({@code omega x r}),
     * or {@code null} if the point is aboard no loaded ship. This is how fast the DECK is carrying an
     * aboard body at that point; the aboard-body external-move guard widens by one tick of it so a
     * rotating deck is not mistaken for a teleport. The ship transform's position is used as the rotation
     * centre - an approximation good enough for a guard tolerance. Only primitive/MC types cross the gate.
     */
    static double[] shipVelocityAtPoint(World world, double x, double y, double z) {
        try {
            PhysicsObject physo = physoAt(world, x, y, z);
            if (physo == null) {
                return null;
            }
            Vector3dc vLin = physo.getPhysicsData().getLinearVelocity();
            Vector3dc w = physo.getPhysicsData().getAngularVelocity();
            Vec3d c = physo.getShipData().getShipTransform().getShipPositionVec3d();
            double rx = x - c.x, ry = y - c.y, rz = z - c.z;
            // v = vLin + (omega x r)
            return new double[]{
                    vLin.x() + (w.y() * rz - w.z() * ry),
                    vLin.y() + (w.z() * rx - w.x() * rz),
                    vLin.z() + (w.x() * ry - w.y() * rx)
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether VS's per-world ship manager is attached to {@code world} (i.e. VS ships can live
     *  there). Defensive: any failure to consult VS is treated as "no support". */
    static boolean hasShipSupport(World world) {
        try {
            return ValkyrienUtils.getServerShipManager(world) != null;
        } catch (Throwable t) {
            return false;
        }
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
     * The subspace SHIPYARD bounding box (world coordinates, in VS's far-off shipyard region) of the
     * loaded ship whose world BB contains {@code (x,y,z)}, or {@code null} if no ship is there. VS stores
     * a ship's blocks in a fixed shipyard keyed by its chunk claim, NOT at the ship's rendered position;
     * to snapshot a ship's actual blocks you must cut THIS region, not the visible AABB. Spans the claim's
     * chunks over the full Y column. Only MC types cross back to AR core.
     */
    static AxisAlignedBB shipyardBoundsAt(World world, double x, double y, double z) {
        ShipData ship = nearestQueryableShip(world, x, y, z);
        if (ship == null) {
            return null;
        }
        int minCx = Integer.MAX_VALUE, minCz = Integer.MAX_VALUE;
        int maxCx = Integer.MIN_VALUE, maxCz = Integer.MIN_VALUE;
        for (ChunkPos cp : ship.getChunkClaim()) {
            if (cp.x < minCx) minCx = cp.x;
            if (cp.z < minCz) minCz = cp.z;
            if (cp.x > maxCx) maxCx = cp.x;
            if (cp.z > maxCz) maxCz = cp.z;
        }
        if (minCx == Integer.MAX_VALUE) {
            return null;
        }
        return new AxisAlignedBB(minCx * 16, 0, minCz * 16,
                (maxCx + 1) * 16, 256, (maxCz + 1) * 16);
    }

    /**
     * Deregister the ship nearest to {@code (x,y,z)} from VS's per-world queryable ship registry (by
     * UUID), or return false if none exists. The per-ship "crossing" calls this after snapshotting the
     * ship's shipyard blocks, so cutting those blocks to air leaves no dangling ship in the registry.
     * Does not delete the shipyard chunks — the caller cuts those.
     */
    static boolean removeShipRegistrationAt(World world, double x, double y, double z) {
        ShipData ship = nearestQueryableShip(world, x, y, z);
        if (ship == null) {
            return false;
        }
        ValkyrienUtils.getQueryableData(world).removeShip(ship.getUuid());
        return true;
    }

    /**
     * DIAGNOSTIC: every queryable ship in {@code world} as {@code "x,y,z"} transform positions joined by
     * {@code ";"}. Deliberately asks about NO point — a lookup keyed on a position cannot tell "the ship
     * is not where I asked" from "the lookup cannot see it", and breaking that ambiguity is the whole
     * job. It earned its place immediately: it showed a ship sitting EXACTLY on the pose that a
     * containment-matched lookup had just failed to resolve, which is how that lookup was found wrong.
     * Empty string when VS holds no ships here.
     */
    static String queryableShipPositions(World world) {
        StringBuilder out = new StringBuilder();
        for (ShipData ship : ValkyrienUtils.getQueryableData(world).getShips()) {
            Vec3d p = ship.getShipTransform().getShipPositionVec3d();
            if (out.length() > 0) {
                out.append(';');
            }
            out.append((long) p.x).append(',').append((long) p.y).append(',').append((long) p.z);
        }
        return out.toString();
    }

    /**
     * The ship in {@code world}'s QUERYABLE registry (loaded OR not) whose transform position is nearest
     * to {@code (x,y,z)}, or {@code null} if the registry is empty. The crossing works off the queryable
     * registry, not the loaded-physo set, so it does not race VS's headless auto-unload: a ship's chunk
     * claim, UUID and transform live in {@link ShipData} whether or not a physics object is loaded.
     */
    // NOTE: this lookup is UNBOUNDED and that is a live defect - it returns the globally nearest ship
    // for any point in the world, so four callers documented as "the ship at this point" answer for any
    // ship anywhere. Two fixes were tried and both measured wrong, so it stands as it is:
    //  - containment against getShipBB(): that box is seeded as a DEGENERATE POINT by
    //    ValkyrienUtils.createNewShip (new AxisAlignedBB(pos,pos)) and nothing grows it to the hull, so
    //    a ship sitting exactly on the queried pose matched nothing;
    //  - a distance bound (512 blocks): it broke the departure, because callers reach here with SUBSPACE
    //    positions (a flight computer's block) while a ship's transform position is WORLD-frame, and the
    //    two are megablocks apart. The unbounded scan was masking that frame mismatch entirely.
    // So the real prerequisite is frame discipline at the CALLERS, not a cleverer match here.
    private static ShipData nearestQueryableShip(World world, double x, double y, double z) {
        ShipData best = null;
        double bestSq = Double.MAX_VALUE;
        for (ShipData ship : ValkyrienUtils.getQueryableData(world).getShips()) {
            Vec3d p = ship.getShipTransform().getShipPositionVec3d();
            double distSq = p.squareDistanceTo(x, y, z);
            if (distSq < bestSq) {
                bestSq = distSq;
                best = ship;
            }
        }
        return best;
    }

    /**
     * TEST/HEADLESS: set VS's "ships permanently loaded" flag. Without a player nearby VS unloads a
     * freshly assembled ship within a tick, so its physics object drops out of the loaded set between
     * probe calls; enabling this keeps ships loaded so a headless server test can observe them across
     * calls. (This is the {@code VSConfig.SHIP_LOADING_SETTINGS.permanentlyLoaded} lever.)
     */
    static void setShipsPermanentlyLoaded(boolean value) {
        org.valkyrienskies.mod.common.config.VSConfig.SHIP_LOADING_SETTINGS.permanentlyLoaded = value;
    }

    /**
     * PARK the ship nearest to {@code (x,y,z)} in the queryable registry: disable its physics so it
     * holds position while {@code ShipTransit} advances its coordinate logically (a physically-flying
     * parked ship in a shared hyperspace world would drift lanes into each other). Works off the
     * queryable registry (loaded or not). Returns false if no ship is there. Unpark = the inverse.
     */
    static boolean parkShipAt(World world, double x, double y, double z) {
        ShipData ship = nearestQueryableShip(world, x, y, z);
        if (ship == null) {
            return false;
        }
        ship.setPhysicsEnabled(false);
        return true;
    }

    /** UNPARK: re-enable physics on the ship nearest to {@code (x,y,z)}. See {@link #parkShipAt}. */
    static boolean unparkShipAt(World world, double x, double y, double z) {
        ShipData ship = nearestQueryableShip(world, x, y, z);
        if (ship == null) {
            return false;
        }
        ship.setPhysicsEnabled(true);
        return true;
    }

    /**
     * RIGID-TELEPORT the ship nearest to {@code (x,y,z)}: rewrite its transform position to
     * {@code (dstX,dstY,dstZ)} — rotation and subspace centre kept — shift its world AABB by the same
     * delta, and mirror the transform into the loaded physics object when there is one. The subspace
     * shipyard blocks do not move; only the world-frame pose does (entities are NOT capped by the 256
     * build height — vanilla's only hard Y line is the void-kill below −64). VS's per-tick world-Y
     * clamps ({@code VSConfig.shipUpperLimit}/{@code shipLowerLimit}) are widened when the destination
     * lies outside them, or the physics tick would immediately drag the ship back. The ship should be
     * PARKED across the write ({@link #parkShipAt}) so the physics thread is not concurrently
     * rewriting the transform; unpark after. Returns false when no ship is near the source.
     */
    static boolean teleportShipTo(World world, double x, double y, double z,
                                  double dstX, double dstY, double dstZ) {
        ShipData ship = nearestQueryableShip(world, x, y, z);
        if (ship == null) {
            return false;
        }
        // Safety net only: production space cells get their whole pose band covered ONCE at
        // subsystem registration (raiseShipCeilingTo), so for them this never fires. It remains
        // for destinations outside any pre-raised range (probe teleports to arbitrary Y, and
        // deployments where the subsystem never registered) - without it the next physics step
        // would clamp the ship straight back out of the teleport.
        if (dstY + 100d > org.valkyrienskies.mod.common.config.VSConfig.shipUpperLimit) {
            org.valkyrienskies.mod.common.config.VSConfig.shipUpperLimit = dstY + 1_000d;
        }
        if (dstY - 100d < org.valkyrienskies.mod.common.config.VSConfig.shipLowerLimit) {
            org.valkyrienskies.mod.common.config.VSConfig.shipLowerLimit = dstY - 1_000d;
        }
        ShipTransform old = ship.getShipTransform();
        // Rotation-preserving variant of VS's own teleport recipe (its /vs teleport command resets the
        // rotation to identity via the 2-arg ShipTransform ctor; a production relocation must not).
        ShipTransform moved = new ShipTransform(dstX, dstY, dstZ,
                old.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL), old.getCenterCoord());
        double dx = dstX - old.getPosX();
        double dy = dstY - old.getPosY();
        double dz = dstZ - old.getPosZ();
        try {
            PhysicsObject physo = ValkyrienUtils.getServerShipManager(world)
                    .getPhysObjectFromUUID(ship.getUuid());
            if (physo != null) {
                // The loaded physics pipeline re-integrates the pose from its OWN state each tick and
                // overwrites plain transform writes. These are the three flags VS's teleport uses to
                // make both sides ADOPT the game-side transform written below.
                physo.getPhysicsCalculations().setForceToUseGameTransform(true);
                physo.setForceToUseShipDataTransform(true);
                physo.setTicksSinceShipTeleport(0);
            }
        } catch (Exception ignored) {
            // unloaded physo: the ShipData transform is the durable truth already
        }
        // Mirror VS's teleport: the ship comes out PARKED (physics disabled) — the caller re-enables
        // once the adoption has propagated (a tick later), or keeps it parked (transit semantics).
        ship.setPhysicsEnabled(false);
        ship.setPrevTickShipTransform(moved);
        ship.setShipTransform(moved);
        AxisAlignedBB bb = ship.getShipBB();
        if (bb != null) {
            ship.setShipBB(bb.offset(dx, dy, dz));
        }
        return true;
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
     * TEST-ONLY: set the world-frame angular velocity (rad/s) of the loaded ship nearest to
     * {@code (x,y,z)} directly, bypassing the flight controller. Lets a test spin a ship to a truly
     * inverted attitude via free VS physics (a fresh, never-piloted ship has no controller torque, so it
     * coasts) rather than via the attitude-hold, which stalls short of a full flip. Returns false if no
     * ship is loaded.
     */
    static boolean spinNearestShip(World world, double x, double y, double z,
                                   double wx, double wy, double wz) {
        PhysicsObject physo = nearestShip(world, x, y, z);
        if (physo == null) {
            return false;
        }
        physo.getShipData().setPhysicsEnabled(true);
        physo.getPhysicsData().setAngularVelocity(new Vector3d(wx, wy, wz));
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
        // These static channels persist for the life of the JVM. An attitude target left behind by an
        // earlier probe outranks a raw rate command, so a velocity probe would silently run
        // attitude-hold against a stale orientation. Clear it: this probe commands rates, not a pose.
        TileAdvancedFlightComputer.debugTargetAttitude = null;
        return true;
    }

    /**
     * Command the loaded ship nearest to {@code (x,y,z)} to HOLD a target body&rarr;world attitude
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

    /**
     * Read-only diagnostic: what Valkyrien Skies already believes about {@code entity}'s relationship
     * to a ship. VS tracks this itself (it even ships a {@code PlayerMovementData} record with the
     * player's SHIP-LOCAL position over the movement packet), so before AR builds its own "is this
     * entity aboard, and where is it on the deck" machinery, this reports what VS supplies for free:
     *
     * <ul>
     *   <li>{@code lastTouchedShip} / {@code ticksSinceTouchedShip} / {@code ticksPartOfGround} -
     *       VS's own entity-to-ship association and "is standing on it" counter;</li>
     *   <li>{@code addedVel*} / {@code addedYawVelocity} - the motion VS imparts to the entity;</li>
     *   <li>{@code mounted} - whether VS considers the entity fixed to a ship (its own seat concept,
     *       which AR's pilot dummy is NOT);</li>
     *   <li>{@code local*} - the entity's position mapped into the ship's subspace by the ship
     *       transform. This is the coordinate a deck-aligned collision frame would treat as
     *       authoritative, so it is the number to watch while the ship rolls.</li>
     * </ul>
     *
     * Returns a plain JDK map (no VS types cross back to AR core), or {@code null} if VS cannot be
     * consulted. Defensive: any VS-side failure degrades to {@code null} rather than throwing.
     */
    /**
     * Clear the physics mod's own entity-to-ship association (and the drag velocity riding on it)
     * for a body AR resolves ship-locally. Returns true when something was actually cleared.
     *
     * <p>The physics mod's {@code EntityDraggable} ticks every loaded entity once per world tick and,
     * while {@code lastTouchedShip} is fresh, moves the body from ITS OWN ship anchor - a second
     * mover fighting AR's ship-frame resolution (live symptom: the client commit was undone every
     * tick by exactly the drag's move, a constant pull toward a stale point). The association is fed
     * by the mod's collision injector during UNRESOLVED moves (a creative flight into the hull, the
     * boarding fall) and, because AR cancels that injector for resolved bodies, it can never refresh
     * honestly - it goes stale and keeps dragging. Clearing it every resolved tick makes the physics
     * mod see the body as ship-free; after AR releases the body, the mod's own collision re-arms it
     * naturally on first contact.</p>
     */
    static boolean clearEntityShipAssociation(Entity entity) {
        try {
            if (!(entity instanceof IDraggable)) {
                return false;
            }
            IDraggable draggable = (IDraggable) entity;
            EntityShipMovementData data = draggable.getEntityShipMovementData();
            if (data == null) {
                return false;
            }
            Vector3dc added = data.getAddedLinearVelocity();
            boolean dirty = data.getLastTouchedShip() != null
                    || (added != null && (added.x() != 0.0 || added.y() != 0.0 || added.z() != 0.0))
                    || data.getAddedYawVelocity() != 0.0;
            if (!dirty) {
                return false;
            }
            // A large-but-not-MAX tick count: the mod increments it per tick, so MAX_VALUE would
            // overflow negative and re-arm the drag.
            draggable.setEntityShipMovementData(data
                    .withLastTouchedShip(null)
                    .withTicksSinceTouchedShip(1000000)
                    .withAddedLinearVelocity(new Vector3d())
                    .withAddedYawVelocity(0.0));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Map<String, Object> entityShipMovementData(Entity entity) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            EntityShipMovementData data = ValkyrienUtils.getEntityShipMovementDataFor(entity);
            ShipData lastTouched = data == null ? null : data.getLastTouchedShip();
            out.put("lastTouchedShip", lastTouched == null ? null : lastTouched.getUuid().toString());
            out.put("ticksSinceTouchedShip", data == null ? -1 : data.getTicksSinceTouchedShip());
            out.put("ticksPartOfGround", data == null ? -1 : data.getTicksPartOfGround());
            Vector3dc added = data == null ? null : data.getAddedLinearVelocity();
            out.put("addedVelX", added == null ? 0.0 : added.x());
            out.put("addedVelY", added == null ? 0.0 : added.y());
            out.put("addedVelZ", added == null ? 0.0 : added.z());
            out.put("addedYawVelocity", data == null ? 0.0 : data.getAddedYawVelocity());

            EntityShipMountData mount = ValkyrienUtils.getMountedShipAndPos(entity);
            out.put("mounted", mount != null && mount.isMounted());

            // The entity's position in the ship's subspace. Located by CONTAINMENT, not by the
            // physics mod's own association: when AR resolves an entity's movement itself, that
            // association is never set, and we still need to report where the entity is on the deck.
            PhysicsObject physo = physoAt(entity.world, entity.posX, entity.posY, entity.posZ);
            if (physo == null && lastTouched != null) {
                physo = loadedPhysoByUuid(entity.world, lastTouched);
            }
            out.put("shipLoaded", physo != null);
            if (physo != null) {
                Vec3d local = physo.getShipData().getShipTransform().transform(
                        new Vec3d(entity.posX, entity.posY, entity.posZ), TransformType.GLOBAL_TO_SUBSPACE);
                out.put("localX", local.x);
                out.put("localY", local.y);
                out.put("localZ", local.z);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Read-only transform-consistency diagnostic for the ship {@code entity} is aboard. The MOVEMENT
     * frame ({@link #rotateToShipFrame}/{@link #toShipFrame}) uses VS's {@code ShipTransform.rotate}
     * /{@code transform}; the CAMERA and gravity use the attitude quaternion
     * ({@code rotationQuaternion}, via {@link #shipAttitudeAt}). AR ASSUMES those two describe the same
     * rotation. This measures whether they actually agree - the world image of the ship's local up (+Y)
     * and nose (+Z) computed BOTH ways, plus the world<->subspace position round-trip error. A large
     * disagreement at a non-trivial attitude means movement and camera use inconsistent frames, which
     * would drag a body through a deck the camera does not level. Returns primitives only; null off-ship.
     */
    static Map<String, Object> transformConsistency(Entity entity) {
        try {
            ShipTransform t = transformFor(entity);
            if (t == null) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            Quaterniond q = t.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
            out.put("qw", q.w); out.put("qx", q.x); out.put("qy", q.y); out.put("qz", q.z);
            FreeFlightPhysics.Quat arq = new FreeFlightPhysics.Quat(q.w, q.x, q.y, q.z);

            double[] upQuat = arq.rotate(0.0, 1.0, 0.0);
            Vec3d upRot = t.rotate(new Vec3d(0.0, 1.0, 0.0), TransformType.SUBSPACE_TO_GLOBAL);
            out.put("upQuatX", upQuat[0]); out.put("upQuatY", upQuat[1]); out.put("upQuatZ", upQuat[2]);
            out.put("upRotX", upRot.x); out.put("upRotY", upRot.y); out.put("upRotZ", upRot.z);
            out.put("upDisagreement", dist3(upQuat[0], upQuat[1], upQuat[2], upRot.x, upRot.y, upRot.z));

            double[] fwdQuat = arq.rotate(0.0, 0.0, 1.0);
            Vec3d fwdRot = t.rotate(new Vec3d(0.0, 0.0, 1.0), TransformType.SUBSPACE_TO_GLOBAL);
            out.put("fwdDisagreement", dist3(fwdQuat[0], fwdQuat[1], fwdQuat[2], fwdRot.x, fwdRot.y, fwdRot.z));

            Vec3d p = new Vec3d(entity.posX, entity.posY, entity.posZ);
            Vec3d sub = t.transform(p, TransformType.GLOBAL_TO_SUBSPACE);
            Vec3d back = t.transform(sub, TransformType.SUBSPACE_TO_GLOBAL);
            out.put("posRoundTripErr", back.distanceTo(p));

            // Rotation round-trip on a world vector via the two VS rotate directions.
            Vec3d wv = new Vec3d(1.0, 0.0, 0.0);
            Vec3d toSub = t.rotate(wv, TransformType.GLOBAL_TO_SUBSPACE);
            Vec3d backW = t.rotate(toSub, TransformType.SUBSPACE_TO_GLOBAL);
            out.put("rotRoundTripErr", backW.distanceTo(wv));
            return out;
        } catch (Throwable tt) {
            return null;
        }
    }

    private static double dist3(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---- Ship-frame transforms ------------------------------------------------------------
    // A crew member on a rotated deck cannot be collided correctly in the world frame: his box is
    // upright and the deck is not. But the ship's blocks also exist, unrotated and axis-aligned, in
    // its subspace. These four calls move a point or a direction between the two frames so movement
    // can be resolved where the deck is flat. All take the entity only to locate its ship; each
    // returns null when the entity is aboard no loaded ship, so callers fall back to vanilla.

    /** The loaded ship whose world bounding box contains {@code (x,y,z)}, or null. */
    private static PhysicsObject physoAt(World world, double x, double y, double z) {
        Vec3d point = new Vec3d(x, y, z);
        for (PhysicsObject physo : ValkyrienUtils.getPhysosLoadedInWorld(world)) {
            AxisAlignedBB bb = physo.getShipBB();
            if (bb != null && bb.grow(ABOARD_MARGIN).contains(point)) {
                return physo;
            }
        }
        return null;
    }

    /** The ship this entity is aboard, located by its own world position. */
    private static ShipTransform transformFor(Entity entity) {
        PhysicsObject physo = physoAt(entity.world, entity.posX, entity.posY, entity.posZ);
        return physo == null ? null : physo.getShipData().getShipTransform();
    }

    /** World point -> ship-frame point. */
    static double[] toShipFrame(Entity entity, double x, double y, double z) {
        try {
            ShipTransform t = transformFor(entity);
            if (t == null) return null;
            Vec3d v = t.transform(new Vec3d(x, y, z), TransformType.GLOBAL_TO_SUBSPACE);
            return new double[]{v.x, v.y, v.z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Ship-frame point -> world point. */
    static double[] toWorldFrame(Entity entity, double x, double y, double z) {
        try {
            ShipTransform t = transformFor(entity);
            if (t == null) return null;
            Vec3d v = t.transform(new Vec3d(x, y, z), TransformType.SUBSPACE_TO_GLOBAL);
            return new double[]{v.x, v.y, v.z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** World direction -> ship-frame direction (rotation only). */
    static double[] rotateToShipFrame(Entity entity, double x, double y, double z) {
        try {
            ShipTransform t = transformFor(entity);
            if (t == null) return null;
            Vec3d v = t.rotate(new Vec3d(x, y, z), TransformType.GLOBAL_TO_SUBSPACE);
            return new double[]{v.x, v.y, v.z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Ship-frame direction -> world direction (rotation only). */
    static double[] rotateToWorldFrame(Entity entity, double x, double y, double z) {
        try {
            ShipTransform t = transformFor(entity);
            if (t == null) return null;
            Vec3d v = t.rotate(new Vec3d(x, y, z), TransformType.SUBSPACE_TO_GLOBAL);
            return new double[]{v.x, v.y, v.z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---- Anchored (by-ship-id) frame access -------------------------------------------------
    // A capture EPISODE must keep talking to the ship it was captured on. Resolving the ship by
    // world-AABB containment every call re-picks it, and with several loaded ships whose grown
    // boxes overlap, first-match can flip mid-episode (ledger #36/#45). These variants take the
    // ship's UUID string (its ShipData identity) and answer for THAT ship or not at all.

    /** The loaded ship whose {@code ShipData} UUID string equals {@code shipId}, or null. */
    private static PhysicsObject physoById(World world, String shipId) {
        if (shipId == null) {
            return null;
        }
        try {
            for (PhysicsObject physo : ValkyrienUtils.getPhysosLoadedInWorld(world)) {
                if (shipId.equals(physo.getShipData().getUuid().toString())) {
                    return physo;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * UUID string of the ship whose SUBSPACE claim manages the block at {@code pos}, or {@code null}.
     * Subspace claims of distinct ships never overlap ({@code ShipChunkAllocator} spaces them), so —
     * unlike world-AABB containment — this resolution is unambiguous. The seed/anchor resolver for a
     * capture that starts from a ship block (the pilot seat).
     */
    static String shipIdManagingBlock(World world, BlockPos pos) {
        try {
            Optional<PhysicsObject> managing = ValkyrienUtils.getPhysoManagingBlock(world, pos);
            return managing.isPresent()
                    ? managing.get().getShipData().getUuid().toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * UUID strings of EVERY loaded ship whose grown world AABB contains {@code (x,y,z)} — the
     * first-contact CANDIDATE list. The caller disambiguates by testing deck support in each
     * candidate's own frame; returning all matches (not first-match) is what makes that possible.
     */
    static java.util.List<String> shipIdsAt(World world, double x, double y, double z) {
        java.util.List<String> ids = new java.util.ArrayList<>(2);
        try {
            Vec3d point = new Vec3d(x, y, z);
            for (PhysicsObject physo : ValkyrienUtils.getPhysosLoadedInWorld(world)) {
                AxisAlignedBB bb = physo.getShipBB();
                if (bb != null && bb.grow(ABOARD_MARGIN).contains(point)) {
                    ids.add(physo.getShipData().getUuid().toString());
                }
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    /** World point -> ship-frame point, for the ship {@code shipId}. Null when it is not loaded. */
    static double[] toShipFrameFor(World world, String shipId, double x, double y, double z) {
        try {
            PhysicsObject physo = physoById(world, shipId);
            if (physo == null) return null;
            Vec3d v = physo.getShipData().getShipTransform()
                    .transform(new Vec3d(x, y, z), TransformType.GLOBAL_TO_SUBSPACE);
            return new double[]{v.x, v.y, v.z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The body-&gt;world attitude of the ship {@code shipId} as {@code {w,x,y,z}}, or {@code null}
     *  when it is not loaded on this side. The by-ID sibling of {@link #shipAttitudeAt}: a consumer
     *  that already knows WHICH ship it means must not re-derive one by containment (contract C2 -
     *  the anchor is the ship, not whatever box the body currently sits inside). */
    static double[] shipAttitudeForId(World world, String shipId) {
        try {
            PhysicsObject physo = physoById(world, shipId);
            if (physo == null) {
                return null;
            }
            Quaterniond q = physo.getShipData().getShipTransform()
                    .rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
            return new double[]{q.w, q.x, q.y, q.z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Ship-frame point -> world point, for the ship {@code shipId}. Null when it is not loaded. */
    static double[] toWorldFrameFor(World world, String shipId, double x, double y, double z) {
        try {
            PhysicsObject physo = physoById(world, shipId);
            if (physo == null) return null;
            Vec3d v = physo.getShipData().getShipTransform()
                    .transform(new Vec3d(x, y, z), TransformType.SUBSPACE_TO_GLOBAL);
            return new double[]{v.x, v.y, v.z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Ship-frame point -> world point through the pose the ship is DRAWN at, for the ship
     *  {@code shipId}. The renderer does not draw the game-tick transform: the client interpolates
     *  its own render transform between the transform updates it receives, so on a moving ship the
     *  drawn pose and the tick pose genuinely differ. Meaningful on the CLIENT only (a dedicated
     *  server never advances a render transform); null when the ship is not loaded or its render
     *  transform does not exist yet. */
    static double[] renderToWorldFrameFor(World world, String shipId, double x, double y, double z) {
        try {
            PhysicsObject physo = physoById(world, shipId);
            if (physo == null) return null;
            ShipTransform drawn = physo.getShipTransformationManager().getRenderTransform();
            if (drawn == null) return null;
            Vec3d v = drawn.transform(new Vec3d(x, y, z), TransformType.SUBSPACE_TO_GLOBAL);
            return new double[]{v.x, v.y, v.z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** World direction -> ship-frame direction (rotation only), for the ship {@code shipId}. */
    static double[] rotateToShipFrameFor(World world, String shipId, double x, double y, double z) {
        try {
            PhysicsObject physo = physoById(world, shipId);
            if (physo == null) return null;
            Vec3d v = physo.getShipData().getShipTransform()
                    .rotate(new Vec3d(x, y, z), TransformType.GLOBAL_TO_SUBSPACE);
            return new double[]{v.x, v.y, v.z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Ship-frame direction -> world direction (rotation only), for the ship {@code shipId}. */
    static double[] rotateToWorldFrameFor(World world, String shipId, double x, double y, double z) {
        try {
            PhysicsObject physo = physoById(world, shipId);
            if (physo == null) return null;
            Vec3d v = physo.getShipData().getShipTransform()
                    .rotate(new Vec3d(x, y, z), TransformType.SUBSPACE_TO_GLOBAL);
            return new double[]{v.x, v.y, v.z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@link #shipVelocityAtPoint}, but for the anchored ship {@code shipId} instead of a
     *  containment lookup — the guard of an anchored capture must widen by ITS ship's carry.
     *
     *  <p>On the CLIENT the physics feed does not exist: {@code getPhysicsData()}'s velocities live
     *  on the server's physics thread and read ZERO here even while the ship's transform visibly
     *  steps between ticks (network transform updates). Everything built on this value — the
     *  external-move guard's carry-widening AND the held-carry velocity subtraction — was therefore
     *  blind client-side, and the client capture thrashed on any fast-moving ship (drop+re-capture
     *  every tick once the per-tick step crossed the bare 0.2 epsilon; reproduced in-harness on a
     *  level fast climb, ledger #47). The client instead derives the velocity the only honest way
     *  it can: MEASURING the observed transform's per-tick delta. */
    static double[] shipVelocityAtPointFor(World world, String shipId, double x, double y, double z) {
        try {
            PhysicsObject physo = physoById(world, shipId);
            if (physo == null) {
                return null;
            }
            if (world.isRemote) {
                return measuredVelocityAtPoint(world, physo, x, y, z);
            }
            Vector3dc vLin = physo.getPhysicsData().getLinearVelocity();
            Vector3dc w = physo.getPhysicsData().getAngularVelocity();
            Vec3d c = physo.getShipData().getShipTransform().getShipPositionVec3d();
            double rx = x - c.x, ry = y - c.y, rz = z - c.z;
            return new double[]{
                    vLin.x() + (w.y() * rz - w.z() * ry),
                    vLin.y() + (w.z() * rx - w.x() * rz),
                    vLin.z() + (w.x() * ry - w.y() * rx)
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /** Per-side cache of each ship's last OBSERVED transform and the rates derived from its delta:
     *  {@code [tick, posXYZ, quatWXYZ, vLinXYZ, omegaXYZ]}. Weak keys: an unloading ship takes its
     *  entry with it. Synchronized only against the two logical sides' threads; entries are
     *  side-local because each side holds its own {@link PhysicsObject} instances. */
    private static final Map<PhysicsObject, double[]> OBSERVED_TRANSFORM =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<PhysicsObject, double[]>());

    /** The MEASURED world-frame velocity (blocks/second) of {@code physo}'s transform at the point
     *  {@code (x,y,z)}: linear rate from the ship position's per-tick delta, angular rate from the
     *  rotation quaternion's per-tick delta, combined as {@code v + omega x r}. This is the speed
     *  the deck is ACTUALLY carrying that point as observed on this side — exactly the quantity the
     *  external-move guard must tolerate — independent of any physics feed. Null until two distinct
     *  ticks have been observed (one tick of warm-up per ship per side). */
    private static double[] measuredVelocityAtPoint(World world, PhysicsObject physo,
                                                    double x, double y, double z) {
        ShipTransform t = physo.getShipData().getShipTransform();
        Vec3d c = t.getShipPositionVec3d();
        Quaterniond q = t.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
        long now = world.getTotalWorldTime();
        double[] prev = OBSERVED_TRANSFORM.get(physo);
        double[] cur;
        if (prev != null && (long) prev[0] == now) {
            cur = prev; // second caller this tick (guard + commit): reuse the derived rates
        } else {
            cur = new double[14];
            cur[0] = now;
            cur[1] = c.x; cur[2] = c.y; cur[3] = c.z;
            cur[4] = q.w; cur[5] = q.x; cur[6] = q.y; cur[7] = q.z;
            if (prev == null || now < (long) prev[0]) {
                OBSERVED_TRANSFORM.put(physo, cur);
                return null; // first observation of this ship on this side: no rate yet
            }
            double dt = (now - (long) prev[0]) * 0.05;
            cur[8] = (c.x - prev[1]) / dt;
            cur[9] = (c.y - prev[2]) / dt;
            cur[10] = (c.z - prev[3]) / dt;
            // omega from the rotation delta dq = q * conj(prevQ) (world-frame, left-multiplied)
            double pw = prev[4], px = prev[5], py = prev[6], pz = prev[7];
            double dw = q.w * pw + q.x * px + q.y * py + q.z * pz;
            double dx = -q.w * px + q.x * pw - q.y * pz + q.z * py;
            double dy = -q.w * py + q.x * pz + q.y * pw - q.z * px;
            double dz = -q.w * pz - q.x * py + q.y * px + q.z * pw;
            double s = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (s > 1.0E-12) {
                if (dw < 0) { dx = -dx; dy = -dy; dz = -dz; } // shortest arc
                double angle = 2.0 * Math.atan2(s, Math.abs(dw));
                double k = angle / (s * dt);
                cur[11] = dx * k; cur[12] = dy * k; cur[13] = dz * k;
            }
            OBSERVED_TRANSFORM.put(physo, cur);
        }
        double rx = x - c.x, ry = y - c.y, rz = z - c.z;
        return new double[]{
                cur[8] + (cur[12] * rz - cur[13] * ry),
                cur[9] + (cur[13] * rx - cur[11] * rz),
                cur[10] + (cur[11] * ry - cur[12] * rx)
        };
    }

    /**
     * The ship's STAY region, in SUBSPACE coordinates, grown by {@code margin}: the region an
     * anchored aboard body may occupy without being released. Derived from the subspace image of the
     * ship's world AABB corners — the world box bounds the hull in world space, so its subspace image
     * bounds the hull in subspace (over-including by at most the hull diagonal, acceptable for a
     * release-hysteresis bound whose only contract is "boundary at least {@code margin} away from
     * every hull block"). Deliberately NOT built from the chunk claim: the claim is a server-side
     * allocation detail and this region must resolve identically on the CLIENT, which owns a
     * player's movement. Measured in subspace so a jump/fall above the deck NEVER exits it sideways
     * through a grown WORLD box the way the old {@code leftShipBox} gate did. Null when unloaded.
     */
    static AxisAlignedBB subspaceStayRegion(World world, String shipId, double margin) {
        try {
            PhysicsObject physo = physoById(world, shipId);
            if (physo == null) {
                return null;
            }
            AxisAlignedBB worldBB = physo.getShipBB();
            if (worldBB == null) {
                return null;
            }
            ShipTransform t = physo.getShipData().getShipTransform();
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                Vec3d corner = new Vec3d(
                        (i & 1) == 0 ? worldBB.minX : worldBB.maxX,
                        (i & 2) == 0 ? worldBB.minY : worldBB.maxY,
                        (i & 4) == 0 ? worldBB.minZ : worldBB.maxZ);
                Vec3d s = t.transform(corner, TransformType.GLOBAL_TO_SUBSPACE);
                if (s.x < minX) minX = s.x;
                if (s.y < minY) minY = s.y;
                if (s.z < minZ) minZ = s.z;
                if (s.x > maxX) maxX = s.x;
                if (s.y > maxY) maxY = s.y;
                if (s.z > maxZ) maxZ = s.z;
            }
            return new AxisAlignedBB(
                    minX - margin, minY - margin, minZ - margin,
                    maxX + margin, maxY + margin, maxZ + margin);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code ShipData.blockPositions.size()} for the loaded ship, or -1. */
    static int shipBlockCount(World world, String shipId) {
        try {
            PhysicsObject physo = physoById(world, shipId);
            return physo == null ? -1 : physo.getShipData().getBlockPositions().size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The loaded ship in {@code world} whose data matches {@code target}, or null. */
    private static PhysicsObject loadedPhysoByUuid(World world, ShipData target) {
        for (PhysicsObject physo : ValkyrienUtils.getPhysosLoadedInWorld(world)) {
            if (physo.getShipData().getUuid().equals(target.getUuid())) {
                return physo;
            }
        }
        return null;
    }

    /** How far (blocks) to grow a ship's world AABB when testing whether an entity is "aboard",
     *  so an entity resting on the top deck (feet at the box's max face) still counts. */
    private static final double ABOARD_MARGIN = 1.0;

    /**
     * The unit world-frame direction toward the FLOOR of the loaded ship whose world bounding box
     * contains {@code (x,y,z)}, or {@code null} if the point is aboard no loaded ship. "Floor-down"
     * is the ship's local {@code -Y} axis rotated into world space by its attitude; on an upright
     * ship this is {@code (0,-1,0)} (so gravity is unchanged), and it tilts with the ship. Only
     * primitive/MC types cross back to AR core. The ship BB is axis-aligned in world space, so a
     * tilted ship over-includes its corners slightly - acceptable for a gravity hint.
     */
    static double[] shipDownDirection(World world, double x, double y, double z) {
        // Called per entity per tick; be defensive so a VS-side hiccup (e.g. querying loaded ships
        // on a side that has none) degrades to "no ship gravity" rather than spamming exceptions.
        try {
            Vec3d point = new Vec3d(x, y, z);
            for (PhysicsObject physo : ValkyrienUtils.getPhysosLoadedInWorld(world)) {
                AxisAlignedBB bb = physo.getShipBB();
                if (bb == null || !bb.grow(ABOARD_MARGIN).contains(point)) {
                    continue;
                }
                Quaterniond q = physo.getShipData().getShipTransform()
                        .rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
                // World-frame image of the ship's local down (-Y), via the AR-core quaternion helper.
                double[] d = new FreeFlightPhysics.Quat(q.w, q.x, q.y, q.z).rotate(0.0, -1.0, 0.0);
                double n = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                if (n < 1e-9) {
                    return null;
                }
                return new double[]{d[0] / n, d[1] / n, d[2] / n};
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /**
     * The body&rarr;world attitude {@code [w,x,y,z]} of the loaded ship whose world bounding box
     * contains {@code (x,y,z)}, or {@code null} if the point is aboard no loaded ship. Located by
     * CONTAINMENT (the same test the gravity hint uses), not by a block lookup, so it answers for a
     * crew member standing anywhere on the deck as well as for a seated pilot.
     *
     * <p>Works on both sides: the ship transform is replicated, and {@code getPhysosLoadedInWorld}
     * resolves through the side-agnostic {@code IPhysObjectWorld}. The render camera and the client's
     * movement prediction both need it, so a client-side answer is not optional.</p>
     */
    static double[] shipAttitudeAt(World world, double x, double y, double z) {
        try {
            PhysicsObject physo = physoAt(world, x, y, z);
            if (physo == null) {
                return null;
            }
            Quaterniond q = physo.getShipData().getShipTransform()
                    .rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
            return new double[]{q.w, q.x, q.y, q.z};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The world-frame angular velocity {@code [x,y,z]} (rad/s) of the loaded ship nearest to
     * {@code (x,y,z)}, or {@code null} if no ship is loaded. Read-only; used by the flight HUD and by
     * the test probe that pins "a centred flight cursor brings the ship's spin to rest".
     */
    static double[] nearestShipAngularVelocity(World world, double x, double y, double z) {
        PhysicsObject physo = nearestShip(world, x, y, z);
        if (physo == null) {
            return null;
        }
        Vector3dc w = physo.getPhysicsData().getAngularVelocity();
        return new double[]{w.x(), w.y(), w.z()};
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
