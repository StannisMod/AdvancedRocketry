package zmaster587.advancedRocketry.projectile;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.damage.StructureDamageEngine;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * The one place a shot changes coordinate frames.
 *
 * <h3>Why a shot has a frame at all</h3>
 * <p>A hull manoeuvres. A round drilling through one is inside a moving thing, and a position in
 * world coordinates describes where that thing <em>used to be</em>: one tick later the plate has gone
 * somewhere else and the round is either hanging in the hole's wake or buried in a part of the ship
 * it never reached. So a shot inside material is kept in that hull's own subspace, where a round
 * standing still against the plate does not drift — the same discipline every other aboard body here
 * already follows.</p>
 *
 * <h3>What is converted, and what is not</h3>
 * <p>Position converts through the full transform; velocity through the rotation ALONE plus the
 * hull's own motion at that point. The two halves matter separately: the rotation is what makes a
 * relative velocity comparable with subspace faces, and the hull's motion is what a round keeps when
 * it comes out the far side — a round that punched through a ship doing forty blocks a second and
 * left with only its drilling speed would have been robbed by the bookkeeping.</p>
 *
 * <h3>Every conversion may fail, and failure means "stay where you are"</h3>
 * <p>VS answers null for a ship that has unloaded or was never registered. A half-converted shot is
 * worse than an unconverted one, so each entry point either performs the whole change or performs
 * none of it and says so.</p>
 */
public final class ShotFrame {

    private ShotFrame() {
    }

    /** Where this shot is in the world, whichever frame it is being kept in. */
    public static Vec3d worldPosition(World world, Shot shot) {
        if (shot == null) {
            return null;
        }
        if (shot.getHullId() == null) {
            return shot.getPosition();
        }
        Vec3d local = shot.getPosition();
        double[] w = VSIntegration.toWorldFrameFor(world, shot.getHullId(), local.x, local.y, local.z);
        // A hull that stopped answering leaves the subspace triple as the only thing anybody knows.
        return w == null ? local : new Vec3d(w[0], w[1], w[2]);
    }

    /** How fast this shot is going through the WORLD — its hull's own motion included. */
    public static Vec3d worldVelocity(World world, Shot shot) {
        if (shot == null) {
            return null;
        }
        if (shot.getHullId() == null) {
            return shot.getVelocity();
        }
        Vec3d relative = shot.getVelocity();
        double[] rotated = VSIntegration.rotateToWorldFrameFor(world, shot.getHullId(), relative.x,
                relative.y, relative.z);
        if (rotated == null) {
            return relative;
        }
        Vec3d carried = new Vec3d(rotated[0], rotated[1], rotated[2]);
        Vec3d at = worldPosition(world, shot);
        double[] hull = VSIntegration.shipVelocityAtPointFor(world, shot.getHullId(), at.x, at.y, at.z);
        return hull == null ? carried : carried.addVector(hull[0], hull[1], hull[2]);
    }

    /**
     * Take this shot into {@code hullId}'s frame IF it ended up inside that hull's material, reading
     * its present world coordinates. Answers false and changes nothing otherwise — a round that came
     * out the far side, or a ship that cannot be asked, leaves a plain world-frame body, which is
     * what a shot has always been.
     *
     * <p>The material test is what keeps the frame honest: a shot rides a hull because it is stuck
     * IN one, so the moment it is not, it stops being that hull's business.</p>
     */
    static boolean embedIfInside(World world, Shot shot, String hullId) {
        if (world == null || shot == null || hullId == null || shot.getHullId() != null) {
            return false;
        }
        Vec3d worldPos = shot.getPosition();
        Vec3d worldVel = shot.getVelocity();
        double[] local = VSIntegration.toShipFrameFor(world, hullId, worldPos.x, worldPos.y, worldPos.z);
        if (local == null) {
            return false;
        }
        Vec3d subspace = new Vec3d(local[0], local[1], local[2]);
        if (!insideMaterialOf(world, hullId, subspace)) {
            return false;
        }
        // Subtract the hull's own motion BEFORE rotating: what is left is the round's motion relative
        // to the plate, which is the only part of its velocity that does any drilling.
        double[] carry = VSIntegration.shipVelocityAtPointFor(world, hullId, worldPos.x, worldPos.y,
                worldPos.z);
        double vx = worldVel.x - (carry == null ? 0.0D : carry[0]);
        double vy = worldVel.y - (carry == null ? 0.0D : carry[1]);
        double vz = worldVel.z - (carry == null ? 0.0D : carry[2]);
        double[] relative = VSIntegration.rotateToShipFrameFor(world, hullId, vx, vy, vz);
        if (relative == null) {
            return false;
        }
        shot.setPosition(subspace);
        shot.setVelocity(new Vec3d(relative[0], relative[1], relative[2]));
        shot.setHullId(hullId);
        return true;
    }

    /**
     * Put this shot back into world terms and forget the hull. Answers false when it was not in one.
     * A hull that has stopped answering still releases the shot: leaving a round addressed to a ship
     * nobody can resolve is how a body gets stranded five million blocks away.
     */
    static boolean leaveHull(World world, Shot shot) {
        if (shot == null || shot.getHullId() == null) {
            return false;
        }
        Vec3d worldPos = worldPosition(world, shot);
        Vec3d worldVel = worldVelocity(world, shot);
        shot.setPosition(worldPos);
        shot.setVelocity(worldVel);
        shot.setHullId(null);
        return true;
    }

    /**
     * Is this point inside {@code hullId}'s own material? The point is a SUBSPACE one — this is asked
     * about a shot that is already being kept in that frame, and converting it out and back would be
     * two chances to drift for no answer.
     */
    static boolean insideMaterialOf(World world, String hullId, Vec3d subspacePoint) {
        if (world == null || hullId == null || subspacePoint == null) {
            return false;
        }
        BlockPos pos = new BlockPos(Math.floor(subspacePoint.x), Math.floor(subspacePoint.y),
                Math.floor(subspacePoint.z));
        if (!world.isBlockLoaded(pos)) {
            // Nobody looked. Treating that as "out" would release the round; treating it as "in"
            // would trap it. Out is the recoverable one: it resumes as an ordinary flying body.
            return false;
        }
        return StructureDamageEngine.isStructure(world, pos, world.getBlockState(pos));
    }
}
