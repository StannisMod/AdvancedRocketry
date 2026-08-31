package zmaster587.advancedRocketry.weapon;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.projectile.ShotEnvironment;
import zmaster587.advancedRocketry.api.projectile.ShotSpec;
import zmaster587.advancedRocketry.api.weapon.GunSpec;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.projectile.ShotSubstrate;
import zmaster587.advancedRocketry.projectile.StructureCrossing;

import java.util.Random;
import java.util.UUID;

/**
 * The seam between a gun that sits somewhere and a round that flies through the world.
 *
 * <h3>Two frames, and the gun only ever knows one</h3>
 * <p>A turret bolted to a ship has a position in that ship's SUBSPACE and turns with the hull; its
 * bearing is therefore held in the ship's frame, and a hull that rolls carries the barrel with it
 * exactly as it carries the deck. A turret on the ground has no such frame and holds its bearing in
 * the world's. Both are the same code: the ship id is either there or null, and every conversion
 * below is a no-op in the second case.</p>
 *
 * <h3>The round leaves in world coordinates, always</h3>
 * <p>The projectile substrate integrates in the world frame — that is where the target, the shields
 * and everybody watching are. So the last thing this class does before handing a shot over is
 * convert, and it converts the muzzle POINT and the aim DIRECTION separately, because a rotation
 * applied to a position is one of the two mistakes this seam exists to prevent. The other is
 * forgetting that a gun on a moving ship inherits its motion: a round fired from a hull doing 40
 * blocks a tick and given only its own muzzle speed is a round fired backwards.</p>
 */
public final class TurretFireControl {

    /** Vanilla's own surface projectile gravity, the number every thrown thing in the game falls by. */
    private static final double SURFACE_GRAVITY_PER_TICK_SQUARED = 0.03D;

    /**
     * How far past the muzzle the line of fire must be clear, in blocks. Short on purpose: it is a
     * check for the shooter's OWN structure sitting immediately in front of the barrel, not a
     * clear-shot guarantee — a target behind a wall is a miss, which is a legitimate outcome, while a
     * hull one block in front of the muzzle is a build that shells itself.
     */
    private static final double LINE_OF_FIRE_BLOCKS = 3.0D;

    private TurretFireControl() {
    }

    /**
     * The ship whose subspace holds this block, or null for a turret standing on the ground. Asked
     * of the REGISTRY rather than of the live physics, because a gun does not stop being part of its
     * ship when no player happens to be near enough for it to be simulated.
     */
    public static String shipIdAt(World world, BlockPos pos) {
        return VSIntegration.registeredShipIdManagingBlock(world, pos);
    }

    /**
     * Turn a world-frame target into the direction the mount must take, in the mount's OWN frame.
     * Null when the ship is not loaded and its transform therefore cannot be trusted — the caller
     * holds its last bearing rather than swinging to a bearing computed from a stale pose.
     */
    public static Vec3d aimDirection(World world, BlockPos mountPos, String shipId, Vec3d worldTarget) {
        if (world == null || mountPos == null || worldTarget == null) {
            return null;
        }
        Vec3d mount = center(mountPos);
        if (shipId == null) {
            return worldTarget.subtract(mount);
        }
        double[] localTarget = VSIntegration.toShipFrameFor(world, shipId, worldTarget.x, worldTarget.y,
                worldTarget.z);
        if (localTarget == null) {
            return null;
        }
        return new Vec3d(localTarget[0], localTarget[1], localTarget[2]).subtract(mount);
    }

    /**
     * Where a block of a gun's installation actually is, in WORLD coordinates. The block's own
     * position is its ship's if it has one, and a distance measured between a subspace address and a
     * world one is a number with no meaning — so anything comparing a mount against something out in
     * the world converts first. Null when the ship's transform is unavailable, which is the caller's
     * cue to do nothing rather than to fall back on the unconverted position.
     */
    public static Vec3d worldPositionOf(World world, BlockPos pos, String shipId) {
        if (world == null || pos == null) {
            return null;
        }
        if (shipId == null) {
            return center(pos);
        }
        Vec3d local = center(pos);
        double[] point = VSIntegration.toWorldFrameFor(world, shipId, local.x, local.y, local.z);
        return point == null ? null : new Vec3d(point[0], point[1], point[2]);
    }

    /**
     * Where to point so that the round and the target arrive together.
     *
     * <p>A gun handed a POINT misses a moving target by however far it moves during the round's
     * flight, and no amount of tracking fixes that — by the time the mount has followed the target,
     * the round is still going where the target was. The fix needs one thing a gun cannot know on
     * its own: how fast the target is going. That comes from the sensor, and this is where it is
     * spent.</p>
     *
     * <p>Solved by iteration rather than by the quadratic: four passes converge to well inside a
     * block at any speed a gun is worth firing, and a target moving faster than the round simply
     * fails to converge — which is the truth, and better than a closed form that returns a confident
     * aim point at an interception that cannot happen.</p>
     *
     * @param targetVelocity the target's velocity RELATIVE to the shooter, blocks per tick
     * @param projectileSpeed the round's own muzzle speed, blocks per tick
     */
    public static Vec3d interceptPoint(Vec3d muzzle, Vec3d targetPosition, Vec3d targetVelocity,
                                       double projectileSpeed) {
        if (muzzle == null || targetPosition == null) {
            return targetPosition;
        }
        if (targetVelocity == null || projectileSpeed <= 0.0D
                || targetVelocity.lengthVector() < 1.0E-6D) {
            // A target that is not moving is its own intercept point. Said explicitly so that a
            // still target is aimed at exactly, rather than at the result of four rounds of
            // arithmetic on a zero.
            return targetPosition;
        }
        Vec3d aim = targetPosition;
        for (int pass = 0; pass < 4; pass++) {
            double flightTicks = aim.distanceTo(muzzle) / projectileSpeed;
            aim = targetPosition.add(targetVelocity.scale(flightTicks));
        }
        return aim;
    }

    /**
     * Fire one round along {@code localAim} and answer the shot id, or {@code -1} if the substrate
     * refused it. {@code localAim} is in the mount's own frame — the same frame
     * {@link #aimDirection} answers in.
     *
     * @param reach how many blocks of gun sit between the controller and open space
     */
    public static long fire(World world, BlockPos mountPos, String shipId, Vec3d localAim, GunSpec spec,
                            int reach, UUID owner, String faction, Random random) {
        Muzzle muzzle = muzzleOf(world, mountPos, shipId, localAim, spec, reach, random);
        if (muzzle == null) {
            return -1L;
        }
        Vec3d velocity = muzzle.direction.scale(spec.getMuzzleSpeed()).add(muzzle.carried);
        ShotSpec shot = new ShotSpec(muzzle.point, velocity, spec.getProjectileRadius(),
                spec.getProjectileMass(), spec.getLifetimeTicks(), spec.getImpactEnergy(),
                spec.getKind(), owner, faction, environmentOf(world), null);
        return ShotSubstrate.launch(world, shot);
    }

    /**
     * Where a body actually leaves this gun, along what, and what motion it inherits — or {@code null}
     * when this gun may not fire at all.
     *
     * <h3>Every weapon family asks this, and it must have ONE answer</h3>
     * <p>A round and a held beam leave the same gun from the same place. The standoff below is not a
     * detail of the projectile substrate: a body born inside the barrel resolves a structure crossing
     * against the gun's own blocks, and the weapon takes itself apart. That is not hypothetical — a
     * beam written without this did exactly that, on its first run, and the symptom was a probe
     * answering "no turret there".</p>
     *
     * <p>So is the line-of-fire refusal: a gun recessed into a hull, or one whose arc crosses its own
     * superstructure, HOLDS rather than demolishing it. A build that cannot fire safely is a problem
     * the player can see; a gun that shells its own deck is a mystery.</p>
     */
    public static Muzzle muzzleOf(World world, BlockPos mountPos, String shipId, Vec3d localAim,
                                  GunSpec spec, int reach, Random random) {
        if (world == null || world.isRemote || mountPos == null || localAim == null || spec == null
                || !spec.isOperable() || localAim.lengthVector() < 1.0E-9D) {
            return null;
        }

        if (shipId == null && VSIntegration.isBlockInShipyard(mountPos)) {
            // Depth, not the primary guard: a gun aboard an unnamed ship should never have reached a
            // tick at all (its tile waits on VSIntegration.isOnUnnamedShip). This is here because
            // this method is callable from anywhere, and the failure it prevents is severe out of all
            // proportion to the check — treating a shipyard address as world coordinates puts a live
            // round in the middle of the region every parked hull in the world sits in.
            return null;
        }

        Vec3d direction = spread(localAim.normalize(), spec.getSpreadDegrees(), random);
        // Clear of the gun's own blocks: a round born inside the barrel resolves a structure
        // crossing on its first tick and the weapon shoots itself apart.
        double standoff = reach + 1.5D;
        Vec3d localMuzzle = center(mountPos).add(direction.scale(standoff));

        Vec3d worldMuzzle;
        Vec3d worldDirection;
        Vec3d carried = Vec3d.ZERO;
        if (shipId == null) {
            worldMuzzle = localMuzzle;
            worldDirection = direction;
        } else {
            double[] point = VSIntegration.toWorldFrameFor(world, shipId, localMuzzle.x, localMuzzle.y,
                    localMuzzle.z);
            double[] dir = VSIntegration.rotateToWorldFrameFor(world, shipId, direction.x, direction.y,
                    direction.z);
            if (point == null || dir == null) {
                return null;
            }
            worldMuzzle = new Vec3d(point[0], point[1], point[2]);
            worldDirection = new Vec3d(dir[0], dir[1], dir[2]).normalize();
            double[] shipVelocity = VSIntegration.shipVelocityAtPointFor(world, shipId, worldMuzzle.x,
                    worldMuzzle.y, worldMuzzle.z);
            if (shipVelocity != null) {
                carried = new Vec3d(shipVelocity[0], shipVelocity[1], shipVelocity[2]);
            }
        }

        if (StructureCrossing.isBlocked(world, worldMuzzle,
                worldMuzzle.add(worldDirection.scale(LINE_OF_FIRE_BLOCKS)))) {
            // Something of the shooter's own is in the way — a hull the turret is recessed into, a
            // superstructure its arc crosses, the wall a ground battery was mounted behind. The gun
            // holds rather than demolishing it: a build that cannot fire safely is a problem the
            // player can see, and a gun that shells its own deck is a mystery.
            return null;
        }

        return new Muzzle(worldMuzzle, worldDirection, carried);
    }

    /** Where a body leaves a gun, in WORLD terms, and the motion the gun's own hull lends it. */
    public static final class Muzzle {
        public final Vec3d point;
        public final Vec3d direction;
        public final Vec3d carried;

        Muzzle(Vec3d point, Vec3d direction, Vec3d carried) {
            this.point = point;
            this.direction = direction;
            this.carried = carried;
        }
    }

    /**
     * What acts on a round fired in this world. Read once, at the muzzle, because the shot carries
     * its environment rather than looking one up while it flies.
     */
    public static ShotEnvironment environmentOf(World world) {
        if (world == null) {
            return ShotEnvironment.VACUUM;
        }
        int dimension = world.provider.getDimension();
        if (!DimensionManager.getInstance().isDimensionCreated(dimension)) {
            // Not one of ours: a vanilla world, where things fall at vanilla's rate.
            return ShotEnvironment.gravity(SURFACE_GRAVITY_PER_TICK_SQUARED);
        }
        DimensionProperties properties = DimensionManager.getInstance().getDimensionProperties(dimension);
        if (properties == null) {
            return ShotEnvironment.gravity(SURFACE_GRAVITY_PER_TICK_SQUARED);
        }
        return ShotEnvironment.gravity(SURFACE_GRAVITY_PER_TICK_SQUARED
                * properties.getGravitationalMultiplier());
    }

    /** The block's middle, which is where a gun's axis runs — not its lower north-west corner. */
    public static Vec3d center(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /**
     * Scatter a direction inside a cone of the given half-angle. A zero spread returns the direction
     * untouched — a true barrel is exact, not "very nearly exact", so a test of the aim path is not
     * fighting a random number.
     */
    static Vec3d spread(Vec3d direction, double halfAngleDegrees, Random random) {
        if (halfAngleDegrees <= 0.0D || random == null) {
            return direction;
        }
        // Two small rotations about axes perpendicular to the shot are indistinguishable from a
        // proper cone sample at the angles a barrel actually scatters by, and cost no trigonometry
        // beyond what is already here.
        Vec3d reference = Math.abs(direction.y) > 0.9D ? new Vec3d(1.0D, 0.0D, 0.0D)
                : new Vec3d(0.0D, 1.0D, 0.0D);
        Vec3d right = direction.crossProduct(reference).normalize();
        Vec3d up = right.crossProduct(direction).normalize();
        double radians = Math.toRadians(halfAngleDegrees);
        double a = (random.nextDouble() * 2.0D - 1.0D) * radians;
        double b = (random.nextDouble() * 2.0D - 1.0D) * radians;
        return direction.add(right.scale(Math.tan(a))).add(up.scale(Math.tan(b))).normalize();
    }
}
