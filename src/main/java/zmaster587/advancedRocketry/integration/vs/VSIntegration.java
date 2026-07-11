package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.entity.IFlightBackend;

/**
 * Soft-dependency gate for Valkyrien Skies.
 *
 * <p>AR depends on VS <em>optionally</em>: we compile against the VS API
 * ({@code compileOnly}) but never bundle or require it. The whole true-spaceship
 * feature lights up only when the user also installs VS; without VS, AR must boot
 * and behave exactly as before.</p>
 *
 * <p><b>Boundary rule — do not break:</b> this class MUST NOT import or reference
 * any {@code org.valkyrienskies.*} type, so it is always safe for the JVM to
 * load. Every VS-touching call goes through {@link VSBridge}, which is reached
 * only behind {@link #isAvailable()} — so a VS-importing class is never loaded on
 * an AR install without VS, and there is no {@code NoClassDefFoundError}. The
 * unit test {@code VSIntegrationTest} pins this contract. AR compiles against VS
 * but never requires it (a soft, optional dependency).</p>
 */
public final class VSIntegration {

    /** Valkyrien Skies Core mod id (the 1.12.2 line). */
    public static final String MODID = "valkyrienskies";

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/vs");

    private static Boolean available;

    private VSIntegration() {}

    /**
     * Whether Valkyrien Skies is installed. Defensive: any failure to consult
     * Forge's {@link Loader} (e.g. a non-FML test environment) is treated as
     * "VS absent" rather than propagating. The result is cached after the first
     * successful query.
     */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached == null) {
            try {
                cached = Loader.isModLoaded(MODID);
            } catch (Throwable t) {
                cached = Boolean.FALSE;
            }
            available = cached;
        }
        return cached;
    }

    /**
     * Initialise the VS integration. A safe no-op when VS is absent. Call once
     * during AR init.
     */
    public static void init() {
        if (!isAvailable()) {
            LOGGER.info("Valkyrien Skies not present — true-spaceship features disabled.");
            return;
        }
        // Only here, behind the gate, do we touch a VS-importing class.
        VSBridge.onValkyrienSkiesPresent(LOGGER);
    }

    /**
     * Assemble the structure anchored at {@code anchorPos} into a movable ship.
     * A safe no-op when Valkyrien Skies is absent. Only vanilla/AR types appear in
     * this signature — every VS-importing call stays inside {@link VSBridge}, which
     * is reached only past the {@link #isAvailable()} gate, so no VS class is
     * loaded on an AR install without VS.
     */
    public static void assembleTier2Ship(World world, BlockPos anchorPos) {
        if (!isAvailable()) {
            return;
        }
        VSBridge.assembleTier2Ship(world, anchorPos, LOGGER);
    }

    /**
     * Create a flight backend that drives the Valkyrien Skies ship anchored at
     * {@code anchorPos} as a velocity setpoint (model A), or {@code null} when VS is
     * absent. The return type is the AR-core {@link IFlightBackend}, so a caller in
     * AR core (e.g. the Advanced Flight Computer tile) never references a VS type —
     * the VS-importing {@code VSFlightBackend} is loaded only past this gate.
     */
    public static IFlightBackend createShipFlightBackend(World world, BlockPos anchorPos) {
        if (!isAvailable()) {
            return null;
        }
        return new VSFlightBackend(world, anchorPos);
    }

    /**
     * The body&rarr;world attitude of the Valkyrien Skies ship managing the block at
     * {@code pos}, or {@code null} when VS is absent or no ship manages it. Returns
     * the AR-core {@link FreeFlightPhysics.Quat} so a caller in AR core never sees a
     * VS type. Free Flight integrates the pilot's body rates over this each tick.
     */
    public static FreeFlightPhysics.Quat getShipAttitude(World world, BlockPos pos) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.getShipAttitude(world, pos);
    }

    /**
     * Move a point or a direction between the world frame and the frame of the ship {@code entity}
     * is aboard. In the ship's own frame the deck is axis-aligned and "down" is plain {@code -Y}, so
     * an aboard entity's movement can be resolved there with ordinary rules and mapped back. Each
     * returns {@code null} when VS is absent or the entity is aboard no loaded ship, so callers fall
     * back to vanilla movement. Only AR-core/MC types cross the gate.
     */
    public static double[] toShipFrame(net.minecraft.entity.Entity e, double x, double y, double z) {
        return (!isAvailable() || e == null) ? null : VSBridge.toShipFrame(e, x, y, z);
    }

    /** Ship-frame point to world point. See {@link #toShipFrame}. */
    public static double[] toWorldFrame(net.minecraft.entity.Entity e, double x, double y, double z) {
        return (!isAvailable() || e == null) ? null : VSBridge.toWorldFrame(e, x, y, z);
    }

    /** World direction to ship-frame direction (rotation only). See {@link #toShipFrame}. */
    public static double[] rotateToShipFrame(net.minecraft.entity.Entity e, double x, double y, double z) {
        return (!isAvailable() || e == null) ? null : VSBridge.rotateToShipFrame(e, x, y, z);
    }

    /** Ship-frame direction to world direction (rotation only). See {@link #toShipFrame}. */
    public static double[] rotateToWorldFrame(net.minecraft.entity.Entity e, double x, double y, double z) {
        return (!isAvailable() || e == null) ? null : VSBridge.rotateToWorldFrame(e, x, y, z);
    }

    /**
     * Read-only diagnostic of what Valkyrien Skies already knows about {@code entity}'s relationship
     * to a ship: its last-touched ship, whether VS counts it as standing on that ship
     * ({@code ticksPartOfGround}), the motion VS imparts to it, whether VS considers it mounted, and
     * its position mapped into the ship's subspace. Returns a plain JDK map, or {@code null} when VS
     * is absent or cannot be consulted. Used to decide how much of a ship-local movement frame VS
     * already supplies before AR builds its own. Only AR-core/MC types cross the gate.
     */
    public static java.util.Map<String, Object> getEntityShipMovementData(net.minecraft.entity.Entity entity) {
        if (!isAvailable() || entity == null) {
            return null;
        }
        return VSBridge.entityShipMovementData(entity);
    }

    /**
     * The world-frame linear velocity {@code [x,y,z]} (blocks/second) of the ship managing the block
     * at {@code pos}, or {@code null} when VS is absent or no ship manages it. Used to capture the
     * live velocity as a Flight-Assist setpoint on re-enable. Only AR-core/MC types cross the gate.
     */
    public static double[] getShipVelocity(World world, BlockPos pos) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipLinearVelocity(world, pos);
    }

    /**
     * The unit world-frame direction toward the floor of the loaded ship the point {@code (x,y,z)}
     * is aboard, or {@code null} when VS is absent or the point is aboard no ship. Lets AR apply
     * gravity toward a ship's deck (the ship's local down, rotated by its attitude) for entities
     * standing on it; on an upright ship this is {@code (0,-1,0)}, so gravity is unchanged. Only
     * AR-core/MC types cross the gate.
     */
    public static double[] shipDownDirectionFor(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.shipDownDirection(world, x, y, z);
    }

    /**
     * The body&rarr;world attitude of the loaded ship the point {@code (x,y,z)} is aboard, or
     * {@code null} when VS is absent or the point is aboard no ship. Located by containment, so it
     * answers for a crew member standing anywhere on the deck, not only for a block on the ship.
     * Resolves on both sides. Only AR-core/MC types cross the gate.
     */
    public static FreeFlightPhysics.Quat shipAttitudeAt(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        double[] q = VSBridge.shipAttitudeAt(world, x, y, z);
        return q == null ? null : new FreeFlightPhysics.Quat(q[0], q[1], q[2], q[3]);
    }

    /** The attitude of the ship {@code entity} is aboard, or {@code null}. See {@link #shipAttitudeAt}. */
    public static FreeFlightPhysics.Quat shipAttitudeFor(net.minecraft.entity.Entity entity) {
        if (entity == null || entity.world == null) {
            return null;
        }
        return shipAttitudeAt(entity.world, entity.posX, entity.posY, entity.posZ);
    }

    /**
     * The world-frame angular velocity {@code [x,y,z]} (rad/s) of the loaded ship nearest to
     * {@code (x,y,z)}, or {@code null} when VS is absent or no ship is loaded. Only AR-core/MC types
     * cross the gate.
     */
    public static double[] nearestShipAngularVelocity(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.nearestShipAngularVelocity(world, x, y, z);
    }

    /**
     * The world position {@code [x, y, z]} of the pilot seat at ship-subspace {@code seatPos},
     * or {@code null} when VS is absent or no ship manages the seat. Lets a seated rider be glued
     * to its ship's live world location every tick (the seat block itself lives in a distant,
     * stationary shipyard subspace). Only AR-core/MC types cross the gate.
     */
    public static double[] getSeatWorldPosition(World world, BlockPos seatPos) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.seatWorldPosition(world, seatPos);
    }

    /**
     * Whether Valkyrien Skies ship support (its per-world ship manager) is attached to
     * {@code world}. Used by the space slot-pool spike to confirm VS lights up on a
     * dynamically-created pool world, not just the vanilla/AR dimensions. {@code false} when VS
     * is absent or its manager is not present. Only AR-core/MC types cross the gate.
     */
    public static boolean hasShipSupport(World world) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.hasShipSupport(world);
    }

    /**
     * Enable physics on the ship managing the block at {@code pos} (a safe no-op when VS is
     * absent or no ship manages it). Only AR-core/MC types cross the gate.
     */
    public static void ensureShipPhysicsEnabled(World world, BlockPos pos) {
        if (!isAvailable()) {
            return;
        }
        VSBridge.ensureShipPhysicsEnabled(world, pos);
    }

    /** Number of Valkyrien Skies ships loaded in {@code world}, or -1 when VS is absent. */
    public static int loadedShipCount(World world) {
        if (!isAvailable()) {
            return -1;
        }
        return VSBridge.loadedShipCount(world);
    }

    /** Total ships in {@code world} loaded or not (queryable registry), or -1 when VS absent. */
    public static int queryableShipCount(World world) {
        if (!isAvailable()) {
            return -1;
        }
        return VSBridge.queryableShipCount(world);
    }

    /**
     * Force every known ship in {@code world} loaded and physics-enabled (headless/no-observer
     * equivalent of a nearby player loading it); returns the number requested, or -1 when VS
     * is absent.
     */
    public static int loadAllShips(World world) {
        if (!isAvailable()) {
            return -1;
        }
        return VSBridge.loadAllShips(world);
    }

    /**
     * State of the loaded ship nearest to {@code (x,y,z)} as
     * {@code [posX,posY,posZ, qw,qx,qy,qz, velX,velY,velZ]}, or {@code null} when VS is
     * absent or no ship is loaded. Only AR-core/MC types cross the gate.
     */
    public static double[] nearestShipState(World world, double x, double y, double z) {
        if (!isAvailable()) {
            return null;
        }
        return VSBridge.nearestShipState(world, x, y, z);
    }

    /**
     * Set the linear-velocity setpoint (blocks/second, world frame) of the loaded ship
     * nearest to {@code (x,y,z)}; a safe no-op returning false when VS is absent or no
     * ship is loaded.
     */
    public static boolean pushNearestShip(World world, double x, double y, double z,
                                          double vx, double vy, double vz) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.pushNearestShip(world, x, y, z, vx, vy, vz);
    }

    /**
     * Command the loaded ship nearest to {@code (x,y,z)} toward a world-frame linear +
     * angular velocity, realized as FORCE through a per-physics-tick controller (the working
     * flight path); a safe no-op returning false when VS is absent or no ship is loaded.
     */
    public static boolean commandNearestShipVelocity(World world, double x, double y, double z,
                                                     double vx, double vy, double vz,
                                                     double wx, double wy, double wz) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.commandNearestShipVelocity(world, x, y, z, vx, vy, vz, wx, wy, wz);
    }

    /**
     * Command the loaded ship nearest to {@code (x,y,z)} to hold a target attitude (quaternion
     * {@code w,x,y,z}) via torque while hovering; a safe no-op returning false when VS is absent
     * or no ship is loaded.
     */
    public static boolean commandNearestShipAttitude(World world, double x, double y, double z,
                                                     double qw, double qx, double qy, double qz) {
        if (!isAvailable()) {
            return false;
        }
        return VSBridge.commandNearestShipAttitude(world, x, y, z, qw, qx, qy, qz);
    }
}
