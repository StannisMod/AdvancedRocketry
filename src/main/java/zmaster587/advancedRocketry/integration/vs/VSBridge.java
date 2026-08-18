package zmaster587.advancedRocketry.integration.vs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
import zmaster587.advancedRocketry.api.event.ShipLifecycleEvent;

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
     * server-side; the ship does not exist when this returns, but NOT because the
     * work happens on another thread — it does not. The queue is drained
     * synchronously on the game thread, in the physics mod's own world tick at
     * {@code WorldTickEvent} phase END, so the relocation lands on the NEXT tick of
     * this world and never later: a spawn that is refused there is dropped, not
     * retried. Anything waiting on the assembly should therefore expect it within a
     * tick or two, and treat a longer wait as a failure rather than as slowness.
     *
     * <p>Scope note: this queues the block relocation only. Making the resulting
     * ship pilotable (thrust, attitude) is handled by the flight-control layer;
     * runtime behaviour can only be exercised with VS actually installed, not in a
     * headless test.</p>
     */
    static UUID assembleTier2Ship(World world, BlockPos anchorPos, Logger logger) {
        return assembleTier2Ship(world, anchorPos, logger, null);
    }

    /**
     * The same, for a craft that ALREADY EXISTED and is being re-registered around blocks pasted here
     * — a crossing, a transit, a reposition. Nothing at the assembly can tell this from a new build,
     * so the caller who knows says so, and the naming event carries the answer.
     */
    static UUID pasteTier2Ship(World world, BlockPos anchorPos, Logger logger, UUID keepUuid,
                               UUID keepDurableId) {
        return assembleTier2Ship(world, anchorPos, logger, keepUuid, keepDurableId,
                ShipLifecycleEvent.Cause.PASTED);
    }

    /**
     * The same assembly, KEEPING an identity the caller already holds ({@code keepUuid}), so a ship
     * that crosses from one world to another comes out the other side as the same ship rather than as
     * a stranger that has to be re-found by position. {@code null} means "mint a fresh one", which is
     * what a genuinely new build wants.
     *
     * <p>The identity is only kept if it is FREE in {@code world}, and the one thing that can hold it
     * is this ship's own remnant: a crossing cuts the blocks out of the source world and the physics
     * mod's registry entry can outlive them, blockless. That remnant IS this ship, so it is adopted —
     * dropped here so the assembly below re-registers the identity around the blocks that actually
     * arrived. See {@link #adoptOwnRemnant}.</p>
     */
    static UUID assembleTier2Ship(World world, BlockPos anchorPos, Logger logger, UUID keepUuid) {
        return assembleTier2Ship(world, anchorPos, logger, keepUuid, null);
    }

    /** @see #assembleTier2Ship(World, BlockPos, Logger, UUID, UUID, ShipLifecycleEvent.Cause) */
    static UUID assembleTier2Ship(World world, BlockPos anchorPos, Logger logger, UUID keepUuid,
                                  UUID keepDurableId) {
        return assembleTier2Ship(world, anchorPos, logger, keepUuid, keepDurableId,
                ShipLifecycleEvent.Cause.ASSEMBLED);
    }

    /**
     * The same assembly, also carrying Advanced Rocketry's DURABLE name for the craft onto the record
     * it creates.
     *
     * <p>Without this the name is lost at every crossing and can only be re-established by the ship's
     * own flight computer on a tick - which a craft nobody is standing near does not get: a hull
     * parked in the shared hyperspace world sits in the world's ticking set and is never ticked
     * (measured: zero ticks over a whole jump). Everything that resolves a ship BY its durable name
     * then falls back to "whichever craft is nearest", in the one world built to hold many at once.
     * The name belongs to the ship, so it travels with the ship.</p>
     */
    static UUID assembleTier2Ship(World world, BlockPos anchorPos, Logger logger, UUID keepUuid,
                                  UUID keepDurableId, ShipLifecycleEvent.Cause cause) {
        UUID identity = adoptOwnRemnant(world, keepUuid, logger);
        ShipData ship = identity == null
                ? ValkyrienUtils.createNewShip(world, anchorPos)
                : ValkyrienUtils.createNewShip(world, anchorPos, identity);
        if (keepDurableId != null) {
            // Set WITHOUT touching the index: this record is not in the collection yet, and the
            // indexing setter would put it there - registering a ship whose blocks have not been
            // moved in. It is indexed with everything else when the spawn is drained.
            ship.setArDurableIdBeforeRegistration(keepDurableId);
        }
        WorldServerShipManager manager = ValkyrienUtils.getServerShipManager(world);
        manager.queueShipSpawn(ship, anchorPos, BlockFinder.BlockFinderType.FIND_ALL_BLOCKS, cause);
        logger.info("Queued tier-2 ship assembly at {} (ship '{}', {}{}).", anchorPos, ship.getName(),
                ship.getUuid(), identity == null && keepUuid != null ? ", identity NOT kept" : "");
        return ship.getUuid();
    }

    /**
     * Decide whether {@code wanted} may be used as the identity of a ship about to be assembled in
     * {@code world}, clearing this ship's own remnant if one is in the way. Returns the identity to
     * assemble with, or {@code null} to mint a fresh one.
     *
     * <p>What may hold the identity, and what each case means:</p>
     * <ul>
     *   <li><b>Nothing</b> — the ordinary case for a crossing into another world. Keep it.</li>
     *   <li><b>A BLOCKLESS registry entry</b> — this same ship's remnant, left registered after its
     *       blocks were cut out of this world. It owns nothing and still answers position lookups,
     *       which is the state that misdirects an arrival. Drop it and keep the identity; the
     *       assembly re-registers it around the blocks that actually arrived. A physics object may
     *       still be loaded for such a remnant on a SAME-WORLD crossing: that one is not touched
     *       here, because the physics mod collects a blockless ship in its own destroy pass, which
     *       runs immediately before the spawn queue is drained.</li>
     *   <li><b>A ship with BLOCKS, or a loaded physics object with no registry entry</b> — a real,
     *       live ship (or a stranded object), and re-using its identity would put two ships under one
     *       name; the physics mod throws on the second, out of the world tick. Refuse: log loudly,
     *       naming both sides, and assemble under a fresh identity instead. A crossing that reaches
     *       this point has ALREADY cut its source, so a throw here would leave the ship as loose
     *       blocks; losing the identity keeps the craft flying and keeps the fault visible.</li>
     * </ul>
     */
    private static UUID adoptOwnRemnant(World world, UUID wanted, Logger logger) {
        if (wanted == null) {
            return null;
        }
        ShipData existing = shipByUuid(world, wanted);
        PhysicsObject loaded = ValkyrienUtils.getServerShipManager(world).getPhysObjectFromUUID(wanted);
        if (existing == null && loaded == null) {
            return wanted;
        }
        int blocks = existing == null || existing.getBlockPositions() == null
                ? -1 : existing.getBlockPositions().size();
        if (blocks == 0) {
            ValkyrienUtils.getQueryableData(world).removeShip(wanted);
            logger.info("[SPACE] adopted this ship's own blockless remnant in dim {} ({} '{}',{} still "
                            + "loaded) - the arriving ship keeps its identity",
                    world.provider.getDimension(), wanted, existing.getName(),
                    loaded == null ? " not" : "");
            return wanted;
        }
        logger.error("[SPACE] refusing to keep ship identity {} in dim {}: it is held by a LIVE ship "
                        + "('{}', {} blocks, {} loaded) - the arriving ship gets a fresh identity and "
                        + "everything keyed on the old one now names the wrong craft",
                wanted, world.provider.getDimension(),
                existing == null ? "not registered" : existing.getName(), blocks,
                loaded == null ? "not" : "IS");
        return null;
    }

    /**
     * The registered ship with this {@code uuid}, or {@code null}. Identity, not proximity: the
     * caller that knows WHICH ship it means must never go through
     * {@link #nearestQueryableShip} — that one answers for whatever craft happens to be closest,
     * including a stranger parked in the same world.
     */
    private static ShipData shipByUuid(World world, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return ValkyrienUtils.getQueryableData(world).getShip(uuid).orElse(null);
    }

    /**
     * The same identity lookup, for the one collaborator inside this package that needs the physics
     * record itself rather than a copy of some field from it: {@link ShipInertiaWriter}. Package-
     * private on purpose — a physics-engine type must not travel further than this package, which is
     * the whole point of keeping the boundary here.
     */
    static ShipData shipDataByUuid(World world, UUID uuid) {
        return world == null ? null : shipByUuid(world, uuid);
    }

    /**
     * The physics record of the ship NAMED by {@code uuid}, as
     * {@code [mass, comX, comY, comZ]} in the ship's own frame, or {@code null} when this world holds
     * no such ship.
     *
     * <p>Exists so a test can see the number the mass model wrote. Without it the substitution of AR's
     * per-block table for the engine's flat one is invisible to every tier: the whole server suite went
     * green before and after that change, because nothing in it ever asked a ship what it weighed.</p>
     */
    static double[] shipInertiaById(World world, String shipId) {
        if (world == null || shipId == null) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(shipId);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
        ShipData ship = shipByUuid(world, uuid);
        if (ship == null) {
            return null;
        }
        return new double[] {
                ship.getInertiaData().getGameTickMass(),
                ship.getInertiaData().getGameTickCenterOfMass().x(),
                ship.getInertiaData().getGameTickCenterOfMass().y(),
                ship.getInertiaData().getGameTickCenterOfMass().z()
        };
    }

    /**
     * The subspace shipyard box of the ship NAMED by {@code uuid}, or {@code null} when this world
     * holds no such ship. The identity-keyed twin of {@link #shipyardBoundsAt}: same box, but it
     * cannot answer for a neighbour's craft.
     */
    static AxisAlignedBB shipyardBoundsOf(World world, UUID uuid) {
        return claimBounds(shipByUuid(world, uuid));
    }

    /**
     * The physics mod's uuid for the ship carrying our DURABLE id {@code durableId}, or {@code null}
     * when this world holds no such ship.
     *
     * <p>The translation between the two identities this mod pair keeps: ours is minted by a flight
     * computer and persisted so it survives a re-assembly (the transit, the ledger and every aboard
     * tag are keyed by it), theirs is the ship record's own uuid. A caller holding ours and needing
     * theirs had no way across and fell back to "which ship is nearest this point", which is exact
     * with one craft in the world and silently wrong with two.</p>
     *
     * <p>Indexed on their side, so this is a hash probe rather than a walk. See
     * {@code QueryableShipData#getShipFromArDurableId}.</p>
     */
    static UUID shipUuidOfDurableId(World world, UUID durableId) {
        if (world == null || durableId == null) {
            return null;
        }
        ShipData ship = ValkyrienUtils.getQueryableData(world)
                .getShipFromArDurableId(durableId).orElse(null);
        return ship == null ? null : ship.getUuid();
    }

    /**
     * The durable id written on the RECORD of the ship {@code vsShipUuid}, read straight off the
     * field, or {@code null}. The twin of {@link #shipUuidOfDurableId}, which asks the INDEX the same
     * question from the other side - so a disagreement between the two separates "nothing was ever
     * bound here" from "something was bound and the lookup cannot find it", which is otherwise one
     * null covering both.
     */
    static UUID durableIdOf(World world, UUID vsShipUuid) {
        ShipData ship = shipByUuid(world, vsShipUuid);
        return ship == null ? null : ship.getArDurableId();
    }

    /**
     * Record that the ship {@code vsShipUuid} is the craft our durable id {@code durableId} names, so
     * later lookups can go straight from one to the other. Idempotent; a {@code null} durable id
     * clears the binding.
     *
     * @return {@code true} when a ship was found and bound
     */
    static boolean bindDurableId(World world, UUID vsShipUuid, UUID durableId) {
        ShipData ship = shipByUuid(world, vsShipUuid);
        if (ship == null) {
            return false;
        }
        ship.setArDurableId(durableId);
        return true;
    }

    /** Human-readable identity of the ship a POSITION lookup resolves to, for diagnostics only. */
    static String describeNearestShip(World world, double x, double y, double z) {
        ShipData ship = nearestQueryableShip(world, x, y, z);
        if (ship == null) {
            return "none";
        }
        Vec3d p = ship.getShipTransform().getShipPositionVec3d();
        AxisAlignedBB yard = claimBounds(ship);
        return ship.getUuid() + " '" + ship.getName() + "' at ("
                + (int) p.x + "," + (int) p.y + "," + (int) p.z + ")"
                + (yard == null ? " yard=NONE"
                        : " yard=[" + (int) yard.minX + ".." + (int) yard.maxX + "]x["
                                + (int) yard.minZ + ".." + (int) yard.maxZ + "]");
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
        return managing.isPresent()
                ? seatWorldFrom(managing.get().getShipData().getShipTransform(), seatPos)
                : null;
    }

    /**
     * The WORLD position of the seat block at ship-subspace {@code seatPos} on a ship the REGISTRY knows —
     * loaded or not — or {@code null} if no registered ship owns that block. It gives the same answer as
     * {@link #seatWorldPosition} whenever that one answers at all; only the lookup differs, because a
     * ship's pose lives on its {@code ShipData} and outlives every load and unload.
     *
     * <p>It exists because a ship's LOADED state is not a fact about the ship: VS re-decides it every tick
     * from player proximity. A crew re-seat on arrival is exactly the case with nobody near yet — the crew
     * are carried across BY the re-seat — so keying the seat's position on a loaded physics object made
     * the re-seat depend on AR force-loading the ship against VS's own unload of it. It does not need to.
     *
     * <p>NOT a drop-in replacement: a caller that reads {@code null} as "this rider is no longer on a LIVE
     * ship" (the dismount hold) is asking the physo-keyed question and must keep asking it. What both
     * share — and what the "a seat still lying at the paste site must not pass" discrimination actually
     * rests on — is that neither lookup resolves anything outside the shipyard region.
     */
    static double[] registeredSeatWorldPosition(World world, BlockPos seatPos) {
        Optional<ShipData> ship = ValkyrienUtils.getShipManagingBlock(world, seatPos);
        return ship.isPresent() ? seatWorldFrom(ship.get().getShipTransform(), seatPos) : null;
    }

    /**
     * The WORLD position of an arbitrary SUBSPACE point {@code (sx,sy,sz)} on the ship that manages
     * {@code managedBlock}, taken off the REGISTRY — loaded or not — or {@code null} when no
     * registered ship owns that block.
     *
     * <p>{@link #registeredSeatWorldPosition}'s continuous twin, and it exists for the same reason:
     * a crew re-seat on arrival is carrying the only bodies that would make the ship load, so it
     * cannot be gated on the ship being loaded. A seat lands on a block and gets the mount-point
     * fudge; a crew member on his feet stands at a continuous deck point and gets none — the point
     * handed in here is already exactly where he belongs.</p>
     */
    static double[] registeredSubspacePointToWorld(World world, BlockPos managedBlock,
                                                   double sx, double sy, double sz) {
        Optional<ShipData> ship = ValkyrienUtils.getShipManagingBlock(world, managedBlock);
        if (!ship.isPresent()) {
            return null;
        }
        Vec3d w = ship.get().getShipTransform()
                .transform(new Vec3d(sx, sy, sz), TransformType.SUBSPACE_TO_GLOBAL);
        return new double[]{w.x, w.y, w.z};
    }

    /** The one place a seat's subspace block is mapped through a ship transform. The {@code +0.2}
     *  vertical offset mirrors the mount point {@code BlockPilotSeat} spawns at. */
    private static double[] seatWorldFrom(ShipTransform transform, BlockPos seatPos) {
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
    /**
     * The three gates Valkyrien Skies applies before it ticks a ship's physics at all, plus the size
     * of that ship's force-controller set, as
     * {@code [physicsReady, physicsEnabled, hasChunkCache, controllerCount]} (booleans as 0/1), or
     * {@code null} when the id names no ship loaded here.
     *
     * <p>Diagnostic. A ship that ignores every command has several readings that need opposite
     * fixes — its physics never ticks, it ticks but carries no controller, or it ticks with a
     * controller whose force is overwritten — and the gate values tell them apart directly instead of
     * by inference. The gates are read from {@code VSWorldPhysicsLoop}'s own selection test, so this
     * reports what that loop decides, not a restatement of it.</p>
     */
    static int[] shipPhysicsGatesById(World world, String shipId) {
        PhysicsObject physo = shipById(world, shipId);
        if (physo == null) {
            return null;
        }
        return new int[]{
                physo.isPhysicsReady() ? 1 : 0,
                physo.isPhysicsEnabled() ? 1 : 0,
                physo.getCachedSurroundingChunks() != null ? 1 : 0,
                physo.getPhysicsControllersInShip().size()};
    }

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
     * DIAGNOSTIC: the identity of the ship-registry object {@code world} answers with, as the same
     * hex the physics mod's own save/load log lines print. A count says how many ships a registry
     * holds; only the identity says whether the registry a reader is being answered from is the one
     * that gets written to disk.
     */
    static String queryableIdentity(World world) {
        return Integer.toHexString(
                System.identityHashCode(ValkyrienUtils.getQueryableData(world)));
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
        return claimBounds(nearestQueryableShip(world, x, y, z));
    }

    /** The XZ box of a ship's chunk claim across the full {@code y 0..256} column, or {@code null}. */
    private static AxisAlignedBB claimBounds(ShipData ship) {
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
     * Identity of the queryable ship nearest to {@code (x,y,z)}, or {@code null} if this world holds
     * none. Taken BEFORE a per-ship crossing cuts the ship away, because after the cut the ship is
     * still registered but has no blocks, and a position lookup is no longer a safe way to name it.
     */
    static UUID queryableShipUuidAt(World world, double x, double y, double z) {
        ShipData ship = nearestQueryableShip(world, x, y, z);
        return ship == null ? null : ship.getUuid();
    }

    /**
     * Release {@code uuid}'s registry entry, but ONLY when nothing is loaded that would collect it.
     *
     * <p>Valkyrien Skies collects a ship whose blocks have all gone to air by itself: the destroy pass
     * at the top of its world tick finds the empty block set, deconstructs the ship and deregisters it.
     * That pass walks the LOADED ship objects, though — so it never runs for a ship whose blocks were cut
     * while nothing held it loaded, and that ship's entry would sit in the registry with no blocks and no
     * object behind it, answering every later position lookup in the world and being written to disk with
     * it.</p>
     *
     * <p>So this is the fallback for exactly that case and no other. When a physics object IS loaded, it
     * does nothing and lets VS's own pass do the work — deregistering there would take the ship out from
     * under the accounting that empties its block set, which is the whole reason a crossing must not
     * deregister up front. Returns whether it released anything.</p>
     */
    /**
     * Every ship the REGISTRY knows in {@code world}, as uuid -> its transform position. Loaded or
     * not: the boot-time hyperspace reconciliation runs with nobody near any of these ships, which
     * is exactly the state the loaded set is empty in.
     */
    static java.util.Map<UUID, double[]> registeredShipPoses(World world) {
        java.util.Map<UUID, double[]> out = new java.util.LinkedHashMap<>();
        for (ShipData ship : ValkyrienUtils.getQueryableData(world).getShips()) {
            Vec3d p = ship.getShipTransform().getShipPositionVec3d();
            out.put(ship.getUuid(), new double[]{p.x, p.y, p.z});
        }
        return out;
    }

    /**
     * The uuid of the registered ship whose SHIPYARD claim owns the blocks at world point
     * {@code (x,y,z)}... which is not a question the claim can answer, so this asks the one that is
     * both answerable and right for a PARKED ship: which registered ship's transform sits within
     * {@code radius} of the point. A parked ship does not move, and hyperspace lanes are 2048 blocks
     * apart, so a radius well under half that spacing cannot admit a neighbour.
     */
    static UUID shipUuidNear(World world, double x, double y, double z, double radius) {
        double bestSq = radius * radius;
        UUID best = null;
        for (ShipData ship : ValkyrienUtils.getQueryableData(world).getShips()) {
            Vec3d p = ship.getShipTransform().getShipPositionVec3d();
            double dx = p.x - x, dy = p.y - y, dz = p.z - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestSq) {
                bestSq = distSq;
                best = ship.getUuid();
            }
        }
        return best;
    }

    /**
     * Deregister {@code uuid} unless the physics mod is still holding it. "Holding" is asked of the
     * MANAGER as one question, because a ship can be in its hands without being loaded: while its
     * chunks stream in it has no physics object yet, and deregistering it in that window throws out of
     * the world tick on the next chunk-provider pass and takes the dedicated server with it. Asking
     * only "is a physics object loaded" is what leaves that window open.
     */
    static boolean releaseShipIfNothingLoaded(World world, UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (ValkyrienUtils.getServerShipManager(world).isShipInUse(uuid)) {
            return false;
        }
        ValkyrienUtils.getQueryableData(world).removeShip(uuid);
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

    /** UNPARK the ship NAMED by {@code uuid}. The identity-keyed twin of {@link #unparkShipAt}. */
    static boolean unparkShip(World world, UUID uuid) {
        ShipData ship = shipByUuid(world, uuid);
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
        return teleportShip(world, nearestQueryableShip(world, x, y, z), dstX, dstY, dstZ);
    }

    /**
     * RIGID-TELEPORT the ship NAMED by {@code uuid}. The identity-keyed twin of
     * {@link #teleportShipTo}: same write, but a caller that knows which ship it crossed can never
     * move a stranger that happens to be parked nearer to the probe point.
     */
    static boolean teleportShipToByUuid(World world, UUID uuid,
                                        double dstX, double dstY, double dstZ) {
        return teleportShip(world, shipByUuid(world, uuid), dstX, dstY, dstZ);
    }

    private static boolean teleportShip(World world, ShipData ship,
                                        double dstX, double dstY, double dstZ) {
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
     * loaded. Only primitive/MC types cross back to AR core.
     *
     * <p>{@code maxDist} bounds the search: when the nearest loaded ship is farther than that from
     * the query point the answer is {@code null} — "no ship here" — rather than a distant one.
     * Pass {@link Double#POSITIVE_INFINITY} for the unbounded query. A world holding several ships
     * cannot attribute an unbounded answer to the ship the caller meant: the moment that ship
     * unloads or flies off, the lookup silently starts describing its neighbour instead, and
     * nothing in the answer says so.</p>
     *
     * <p><b>A bound is a mitigation, not an identity.</b> The distance it compares is the FULL 3-D
     * one ({@link #nearestShip}), so a bound sized against how far apart two ships are BUILT says
     * nothing about how far one of them then FLIES: a caller that means one particular ship and
     * lets it move should capture {@link #nearestShipId} once, while its ship is provably the only
     * candidate, and use {@link #shipStateById} afterwards.</p>
     */
    static double[] nearestShipState(World world, double x, double y, double z, double maxDist) {
        PhysicsObject physo = nearestShip(world, x, y, z, maxDist);
        if (physo == null) {
            return null;
        }
        return stateOf(physo);
    }

    /**
     * The IDENTITY of the loaded ship nearest to {@code (x,y,z)} within {@code maxDist} — its VS
     * ship uuid, as a string — or {@code null} when there is none.
     *
     * <p>This is the one call in this family that a caller is meant to make at a moment it can
     * defend: right after its own assembly, when the queried spot provably holds its ship and no
     * other. Everything afterwards goes through {@link #shipStateById}, which has no distance term
     * to be wrong about.</p>
     */
    static String nearestShipId(World world, double x, double y, double z, double maxDist) {
        PhysicsObject physo = nearestShip(world, x, y, z, maxDist);
        return physo == null ? null : physo.getShipData().getUuid().toString();
    }

    /**
     * State of the loaded ship with this uuid, in the same layout as {@link #nearestShipState}, or
     * {@code null} when the id names no ship that is loaded here (unloaded, deleted, another world,
     * or not a uuid at all). Position-independent: the ship may be anywhere.
     */
    static double[] shipStateById(World world, String shipId) {
        PhysicsObject physo = shipById(world, shipId);
        return physo == null ? null : stateOf(physo);
    }

    /**
     * The world-frame angular velocity {@code [x,y,z]} (rad/s) of the loaded ship with this uuid,
     * or {@code null} when the id names no loaded ship here.
     */
    static double[] shipAngularVelocityById(World world, String shipId) {
        PhysicsObject physo = shipById(world, shipId);
        if (physo == null) {
            return null;
        }
        Vector3dc w = physo.getPhysicsData().getAngularVelocity();
        return new double[]{w.x(), w.y(), w.z()};
    }

    private static double[] stateOf(PhysicsObject physo) {
        ShipTransform transform = physo.getShipData().getShipTransform();
        Vec3d pos = transform.getShipPositionVec3d();
        Quaterniond q = transform.rotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
        Vector3dc vel = physo.getPhysicsData().getLinearVelocity();
        return new double[]{pos.x, pos.y, pos.z, q.w, q.x, q.y, q.z, vel.x(), vel.y(), vel.z()};
    }

    /** The loaded ship this uuid names, or null — including when the string is not a uuid. */
    private static PhysicsObject shipById(World world, String shipId) {
        if (shipId == null || shipId.isEmpty()) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(shipId);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
        return ValkyrienUtils.getServerShipManager(world).getPhysObjectFromUUID(uuid);
    }

    /**
     * Set the linear-velocity setpoint (blocks/second, world frame) of the loaded ship named by
     * {@code shipId}; returns false when that id names no ship loaded here. Used by the test probe
     * to prove VS physics moves a bare AR-assembled ship (flight-control model A).
     *
     * <p>Keyed by identity rather than by proximity: a nearest-ship twin of this call answers for
     * whatever craft happens to be closest, so on a world holding more than one it pushes a
     * stranger's ship and reports success.</p>
     */
    static boolean pushShipById(World world, String shipId, double vx, double vy, double vz) {
        PhysicsObject physo = shipById(world, shipId);
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
     * TEST-ONLY: set the world-frame angular velocity (rad/s) of the ship named by {@code shipId}
     * directly, bypassing the flight controller. Lets a test spin a ship to a truly inverted
     * attitude via free VS physics (a fresh, never-piloted ship has no controller torque, so it
     * coasts) rather than via the attitude-hold, which stalls short of a full flip. Returns false
     * when that id names no ship loaded here.
     */
    static boolean spinShipById(World world, String shipId, double wx, double wy, double wz) {
        PhysicsObject physo = shipById(world, shipId);
        if (physo == null) {
            return false;
        }
        physo.getShipData().setPhysicsEnabled(true);
        physo.getPhysicsData().setAngularVelocity(new Vector3d(wx, wy, wz));
        return true;
    }

    /**
     * Enable physics on the ship named by {@code shipId}; false when that id names no ship loaded
     * here. A flag, not a load — it does not trip the spawn/proximity double-load.
     *
     * <p>What the force probes need before they command anything: a bare assembled ship is loaded
     * with physics OFF, so its flight computer's controller is never stepped and a command to it
     * would be inert.</p>
     */
    static boolean enableShipPhysicsById(World world, String shipId) {
        PhysicsObject physo = shipById(world, shipId);
        if (physo == null) {
            return false;
        }
        physo.getShipData().setPhysicsEnabled(true);
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
     * The same claim lookup, answered off the ship's REGISTRY RECORD instead of its loaded physics
     * object: the uuid of the ship whose subspace claim owns {@code pos}, whether or not that ship is
     * currently simulated.
     *
     * <p>The two are not interchangeable and the difference is not a detail. A ship is given a
     * physics object only while a player stands within the physics mod's load distance of it (or on
     * the tick it is first assembled); everywhere else its chunks may be loaded and ticking with no
     * physics object at all. Asking the physics object therefore answers "is anybody near this
     * ship", which is the right question for anything that touches its MOTION and the wrong one for
     * anything that touches its IDENTITY - identity lives on the record.</p>
     *
     * <p>Deliberately a separate method: {@link #shipIdManagingBlock}'s callers use its null as
     * "this ship is not live", and widening that under them would change what they gate on.</p>
     */
    static String registeredShipIdManagingBlock(World world, BlockPos pos) {
        try {
            Optional<ShipData> managing = ValkyrienUtils.getShipManagingBlock(world, pos);
            return managing.isPresent() ? managing.get().getUuid().toString() : null;
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
     *  that already knows WHICH ship it means must not re-derive one by containment: an aboard body
     *  is anchored to ONE ship for the whole episode, and that anchor is the ship's identity, never
     *  whatever box the body currently sits inside. */
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
    static double[] nearestShipAngularVelocity(World world, double x, double y, double z,
                                               double maxDist) {
        PhysicsObject physo = nearestShip(world, x, y, z, maxDist);
        if (physo == null) {
            return null;
        }
        Vector3dc w = physo.getPhysicsData().getAngularVelocity();
        return new double[]{w.x(), w.y(), w.z()};
    }

    private static PhysicsObject nearestShip(World world, double x, double y, double z) {
        return nearestShip(world, x, y, z, Double.POSITIVE_INFINITY);
    }

    private static PhysicsObject nearestShip(World world, double x, double y, double z,
                                             double maxDist) {
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
        if (best != null && Double.isFinite(maxDist) && bestDistSq > maxDist * maxDist) {
            return null;
        }
        return best;
    }
}
