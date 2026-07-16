package zmaster587.advancedRocketry.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;

/**
 * Puts an ABOARD body's EYE where its camera actually renders from.
 *
 * <p>{@code Entity.getPositionEyes} is {@code position + (0, eyeHeight, 0)} - along the WORLD up,
 * whatever the body stands on. The renderer already offsets an aboard body's camera along the
 * SHIP's up instead ({@code MixinEntityRendererShipEye}); every raytrace that starts from
 * {@code getPositionEyes} - the crosshair's {@code getMouseOver} above all - then originates from
 * a DIFFERENT point than the camera. On a rolled deck the two diverge by up to an eye height
 * (~1.6 blocks): the block outlined under the crosshair is not the block the ray hits, and
 * mining/placing goes somewhere the player is not looking.</p>
 *
 * <p>Gated on the movement truth (ABOARD specifically): a hull-stand body's semantics - eye
 * included - are the world's, and a bystander is never touched. Applies on BOTH sides so the
 * server's own eye-based checks agree with the client's ray. Falls back to vanilla whenever the
 * ship's transform is unavailable.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityShipEyes {

    @Inject(method = "getPositionEyes", at = @At("HEAD"), cancellable = true)
    private void advancedrocketry$aboardEyeAlongShipUp(float partialTicks,
                                                       CallbackInfoReturnable<Vec3d> cir) {
        Entity self = (Entity) (Object) this;
        double[] up = ShipFrameTravel.aboardShipUpWorld(self);
        if (up == null) {
            return;
        }
        double eye = self.getEyeHeight();
        double x, y, z;
        if (partialTicks == 1.0F) {
            x = self.posX;
            y = self.posY;
            z = self.posZ;
        } else {
            x = self.prevPosX + (self.posX - self.prevPosX) * partialTicks;
            y = self.prevPosY + (self.posY - self.prevPosY) * partialTicks;
            z = self.prevPosZ + (self.posZ - self.prevPosZ) * partialTicks;
        }
        cir.setReturnValue(new Vec3d(x + eye * up[0], y + eye * up[1], z + eye * up[2]));
    }
}
