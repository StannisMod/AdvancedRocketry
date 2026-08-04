package zmaster587.advancedRocketry.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.integration.vs.ShipLocalMove;
import zmaster587.advancedRocketry.integration.vs.ShipLocalMoveControl;

/**
 * Claims {@link Entity#move} before the physics mod does, so an entity aboard a ship can have its
 * collision resolved in the ship's own frame (where the deck is axis-aligned) instead of the world
 * frame (where the entity's box is upright and the deck is not).
 *
 * <p>The physics mod injects at the same point, cancellably. Mixin emits an
 * {@code if (ci.isCancelled()) return;} guard after <em>each</em> callback at an injection point, so
 * whichever callback runs first and cancels prevents the rest — including the vanilla method body.
 * Callback order follows mixin application order, which is why this mixin declares an explicit
 * priority rather than relying on the default both configs happen to use.</p>
 *
 * <p>Inert unless armed through {@link ShipLocalMoveControl}, and then only for one entity, only on
 * the server. It references no physics-mod type, so it is safe to weave with or without that mod.</p>
 */
@Mixin(value = Entity.class, priority = 1500)
public abstract class MixinEntityShipLocalMove {

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void advancedrocketry$shipLocalMove(MoverType type, double x, double y, double z,
                                                CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        // A body whose movement ShipFrameTravel resolves must NEVER be moved through the world-frame
        // pipeline: vanilla collides its upright box against world blocks it is not standing on, and
        // the physics mod's injector (hooked behind us at this same point) collides it against hull
        // POLYGONS in world space - for a crew member whose capsule legitimately overlaps hull
        // geometry (an aboard body standing on a deck open in the SHIP frame may legally have its
        // world capsule inside hull blocks - any steep or inverted interior) that shove is a
        // constant fight against the ship-frame resolution. The live symptom: the server applies a
        // walking client's packet deltas through Entity.move, the polygon collision deflects them,
        // and the crew member is dragged around in small jerks. Apply the displacement RAW instead
        // and cancel: collision for this body is the ship-frame sweep's job, and the world position
        // is derived state.
        if (zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.isResolving(self)) {
            // Every world-frame move request against a resolved body is counted (side-local statics,
            // readable by probes/e2e) and, in test mode, traced - the discriminator for "who still
            // pushes a resolved body through the world pipeline".
            zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.noteWorldMove(
                    String.valueOf(type), x, y, z);
            if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()
                    && (x * x + y * y + z * z) > 1.0E-6) {
                zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/MOVE]"
                        + " remote=" + self.world.isRemote
                        + " id=" + self.getEntityId()
                        + " type=" + type
                        + " d=(" + x + "," + y + "," + z + ")");
            }
            self.setPosition(self.posX + x, self.posY + y, self.posZ + z);
            ci.cancel();
            return;
        }
        if (!ShipLocalMoveControl.shouldTakeOver(self)) {
            return;
        }
        ShipLocalMoveControl.markFired();
        switch (ShipLocalMoveControl.getMode()) {
            case OBSERVE:
                return; // fire only; prove the injection exists
            case CANCEL:
                ci.cancel(); // suppress everything behind us, including the physics mod
                return;
            case SHIP_FRAME:
                // Resolve in the ship's frame. If that cannot be done (aboard no loaded ship, or the
                // transform is unavailable this tick), fall through to vanilla rather than freeze.
                if (ShipLocalMove.resolve(self, x, y, z)) {
                    ci.cancel();
                }
                return;
            default:
                return;
        }
    }
}
