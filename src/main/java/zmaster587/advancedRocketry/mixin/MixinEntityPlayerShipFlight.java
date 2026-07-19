package zmaster587.advancedRocketry.mixin;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;

/**
 * Resolves a creative-FLYING aboard player's movement in his ship's frame.
 *
 * <p>The {@code EntityLivingBase.travel} hook alone cannot own this case: vanilla's
 * {@code EntityPlayer.travel} WRAPS {@code super.travel} for a flyer - it saves the pre-travel
 * {@code motionY}, and after {@code super.travel} returns it OVERWRITES {@code motionY} with that
 * saved value times {@code 0.6}. For a body whose motion the ship frame resolves, that is a
 * world-frame vertical writer landing AFTER the commit, corrupting the held-carry velocity every
 * tick (the anisotropic world-axis drag the ship-frame resolution exists to avoid). Claiming
 * {@code EntityPlayer.travel} at HEAD for the flying case skips the whole wrapper; the resolution
 * applies the same {@code 0.6} damping itself, along the deck's normal.</p>
 *
 * <p>Inert for a non-flying or riding player (the {@code EntityLivingBase} hook owns those), and
 * declines every case the ship frame does not model, so vanilla keeps world-frame flight.</p>
 */
@Mixin(EntityPlayer.class)
public abstract class MixinEntityPlayerShipFlight {

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void advancedrocketry$shipFrameFlight(float strafe, float vertical, float forward,
                                                  CallbackInfo ci) {
        EntityPlayer self = (EntityPlayer) (Object) this;
        if (self.capabilities.isFlying && !self.isRiding()) {
            // The same airborne move factor vanilla's wrapper would have set for super.travel.
            float flyFactor = self.capabilities.getFlySpeed() * (self.isSprinting() ? 2 : 1);
            if (ShipFrameTravel.travel(self, strafe, vertical, forward, flyFactor)) {
                ci.cancel();
            }
        }
    }
}
