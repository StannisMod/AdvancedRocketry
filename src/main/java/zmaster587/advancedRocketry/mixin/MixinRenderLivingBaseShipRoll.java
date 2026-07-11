package zmaster587.advancedRocketry.mixin;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.ShipFrameCamera;

/**
 * Stands an aboard entity's rendered model on the deck it is actually standing on.
 *
 * <p>{@code RenderLivingBase.applyRotations} turns a model by its yaw and nothing else, because vanilla
 * has no roll for a body. At its HEAD the modelview has already been translated to the entity's own
 * render origin, so a rotation pushed there turns the whole model about its feet - exactly what a body
 * standing on a tilted deck needs.</p>
 *
 * <p>Two hooks, because the yaw must follow the roll. Once the ship attitude is applied, vanilla's own
 * {@code rotate(180 - rotationYaw)} turns the body about the DECK normal rather than the world's, so
 * the angle it is given has to be the entity's heading measured in the deck plane. Handing it the
 * unconverted world yaw would point the body somewhere the player is not looking.</p>
 *
 * <p>The physics mod transforms the model itself, but only for entities it counts as mounted to a ship;
 * AR's pilot dummy is not one, and a walking crew member is not one either, so nothing fights this.
 * {@code require = 0} keeps a render mod that rewrites the method from aborting the mixin config.</p>
 */
@Mixin(RenderLivingBase.class)
public abstract class MixinRenderLivingBaseShipRoll {

    @Inject(method = "applyRotations", at = @At("HEAD"), require = 0)
    private void advancedrocketry$rollWithShip(EntityLivingBase entity, float ageInTicks,
                                               float rotationYaw, float partialTicks, CallbackInfo ci) {
        double[] rotation = ShipFrameCamera.modelRotationFor(entity, partialTicks);
        if (rotation != null) {
            GlStateManager.rotate((float) rotation[0],
                    (float) rotation[1], (float) rotation[2], (float) rotation[3]);
        }
    }

    /** {@code ordinal = 1} is {@code rotationYaw} among the method's float arguments
     *  (ageInTicks, rotationYaw, partialTicks). */
    @ModifyVariable(method = "applyRotations", at = @At("HEAD"), argsOnly = true, ordinal = 1,
            require = 0)
    private float advancedrocketry$deckYaw(float value, EntityLivingBase entity, float ageInTicks,
                                           float rotationYaw, float partialTicks) {
        return ShipFrameCamera.deckYawDeg(entity, value, partialTicks);
    }
}
