package zmaster587.advancedRocketry.integration.vs;

import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * Resolves one tick of an aboard entity's movement in its SHIP's frame instead of the world's.
 *
 * <p>The problem it solves: Minecraft's collision box is axis-aligned by definition and an entity
 * has no roll, so an upright box can never be oriented to a rolled deck. But the ship's blocks also
 * exist, unrotated and axis-aligned, in the ship's own subspace. Map the entity there and the deck is
 * flat, "down" is plain {@code -Y}, and the entity's box is deck-aligned for free - ordinary
 * collision then does the right thing. Map the result back and the world sees a body that stands on
 * a tilted floor.</p>
 *
 * <p>The entity's ship-frame position is the AUTHORITATIVE one; its world position is derived from
 * it through the ship transform on every tick. That single choice is what makes the entity ride a
 * moving, rotating ship: when the transform changes, the derived world position follows, with no
 * separate "drag" step.</p>
 *
 * <p>Deliberately incomplete (this is the mechanism, not the finished feature): no step assist, no
 * block-collision callbacks, no fall damage, no walking-frame input. It returns {@code false}
 * whenever it cannot do the job, and the caller must then let vanilla movement run.</p>
 */
public final class ShipLocalMove {

    private ShipLocalMove() {}

    /**
     * Resolve {@code (dx,dy,dz)} of intended world-frame movement in the entity's ship frame,
     * writing back the entity's position, its {@code onGround} flag and its velocity.
     *
     * @return true if the movement was fully handled and the vanilla move must be skipped
     */
    public static boolean resolve(Entity entity, double dx, double dy, double dz) {
        World world = entity.world;

        // The authoritative ship-frame position; seeded from the world position on the first tick.
        double[] local = ShipLocalMoveControl.getShipFramePos();
        if (local == null) {
            local = VSIntegration.toShipFrame(entity, entity.posX, entity.posY, entity.posZ);
            if (local == null) {
                return false; // aboard no loaded ship - let vanilla have it
            }
        }
        // The intended displacement is a direction, so only the rotation applies.
        double[] delta = VSIntegration.rotateToShipFrame(entity, dx, dy, dz);
        if (delta == null) {
            return false;
        }

        // In the ship frame the entity's box is deck-aligned. Sweep it exactly as vanilla does:
        // resolve Y first (so a falling body lands before it is pushed sideways), then X, then Z.
        double halfWidth = entity.width / 2.0;
        AxisAlignedBB box = new AxisAlignedBB(
                local[0] - halfWidth, local[1], local[2] - halfWidth,
                local[0] + halfWidth, local[1] + entity.height, local[2] + halfWidth);

        double wantX = delta[0], wantY = delta[1], wantZ = delta[2];
        List<AxisAlignedBB> obstacles = world.getCollisionBoxes(entity, box.expand(wantX, wantY, wantZ));

        double gotY = wantY;
        for (AxisAlignedBB obstacle : obstacles) {
            gotY = obstacle.calculateYOffset(box, gotY);
        }
        box = box.offset(0.0, gotY, 0.0);

        double gotX = wantX;
        for (AxisAlignedBB obstacle : obstacles) {
            gotX = obstacle.calculateXOffset(box, gotX);
        }
        box = box.offset(gotX, 0.0, 0.0);

        double gotZ = wantZ;
        for (AxisAlignedBB obstacle : obstacles) {
            gotZ = obstacle.calculateZOffset(box, gotZ);
        }
        box = box.offset(0.0, 0.0, gotZ);

        boolean standingOnDeck = wantY < 0.0 && gotY != wantY;

        double newLocalX = box.minX + halfWidth;
        double newLocalY = box.minY;
        double newLocalZ = box.minZ + halfWidth;

        double[] worldPos = VSIntegration.toWorldFrame(entity, newLocalX, newLocalY, newLocalZ);
        if (worldPos == null) {
            return false;
        }
        ShipLocalMoveControl.setShipFramePos(newLocalX, newLocalY, newLocalZ);
        entity.setPosition(worldPos[0], worldPos[1], worldPos[2]);
        entity.onGround = standingOnDeck;

        // Kill the velocity components the deck blocked - in the SHIP frame, where "blocked
        // downwards" is a plain -Y clip - then map the survivor back to world axes.
        double[] velocity = VSIntegration.rotateToShipFrame(entity, entity.motionX, entity.motionY, entity.motionZ);
        if (velocity != null) {
            if (gotX != wantX) velocity[0] = 0.0;
            if (gotY != wantY) velocity[1] = 0.0;
            if (gotZ != wantZ) velocity[2] = 0.0;
            double[] worldVelocity = VSIntegration.rotateToWorldFrame(entity, velocity[0], velocity[1], velocity[2]);
            if (worldVelocity != null) {
                entity.motionX = worldVelocity[0];
                entity.motionY = worldVelocity[1];
                entity.motionZ = worldVelocity[2];
            }
        }
        return true;
    }
}
