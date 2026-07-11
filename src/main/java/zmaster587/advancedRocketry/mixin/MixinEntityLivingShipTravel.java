package zmaster587.advancedRocketry.mixin;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;

/**
 * Resolves an aboard living entity's movement in its ship's frame, where the deck is axis-aligned,
 * instead of the world's, where it is not.
 *
 * <p>Vanilla {@code travel} applies gravity, drag, friction and the walking basis on world axes, and
 * its two drag constants differ (0.98 along Y, 0.91 across X/Z). Rotate the floor and that anisotropy
 * bends a deck-down pull toward world {@code +Y} hard enough to fling a crew member up a wall. Claiming
 * {@code travel} at HEAD lets AR apply the same rules about the deck's axes instead.</p>
 *
 * <p>Inert unless the entity is actually standing inside a loaded ship, and it declines every case it
 * does not model ({@link ShipFrameTravel#handles}), so vanilla keeps every branch AR does not own.
 * References no physics-mod type, so it is safe to weave with or without that mod.</p>
 */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingShipTravel {

    /** Vanilla's airborne move factor. Protected, so the helper cannot read it - shadow and pass it. */
    @Shadow protected float jumpMovementFactor;

    @Shadow protected abstract float getJumpUpwardsMotion();

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void advancedrocketry$shipFrameTravel(float strafe, float vertical, float forward,
                                                  CallbackInfo ci) {
        EntityLivingBase self = (EntityLivingBase) (Object) this;
        if (ShipFrameTravel.travel(self, strafe, vertical, forward, jumpMovementFactor)) {
            ci.cancel();
        }
    }

    @Inject(method = "jump", at = @At("HEAD"), cancellable = true)
    private void advancedrocketry$shipFrameJump(CallbackInfo ci) {
        EntityLivingBase self = (EntityLivingBase) (Object) this;
        double boost = 0.0;
        if (self.isPotionActive(MobEffects.JUMP_BOOST)) {
            boost = (self.getActivePotionEffect(MobEffects.JUMP_BOOST).getAmplifier() + 1) * 0.1F;
        }
        if (ShipFrameTravel.jump(self, getJumpUpwardsMotion(), boost)) {
            ci.cancel();
        }
    }
}
