package zmaster587.advancedRocketry.mixin;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.DeckLook;

/**
 * Turns raw mouse-look input in the DECK frame for a crew member walking a ship.
 *
 * <p>{@code Entity.turn} adds the mouse delta straight onto world yaw/pitch. While a crew member's
 * movement is resolved aboard a deck his look is held deck-frame instead ({@link DeckLook}): the
 * delta turns the deck yaw/pitch directly - deck-relative by construction at any ship attitude -
 * and the world rotation is derived from it, so this cancels the vanilla world-frame turn. It is
 * inert (returns false) for anyone who is not the local player resolved aboard, so every other
 * {@code turn} runs untouched. Client-only, and references no physics-mod type.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityDeckLookTurn {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void advancedrocketry$deckRelativeTurn(float yaw, float pitch, CallbackInfo ci) {
        if (DeckLook.turn((Entity) (Object) this, yaw, pitch)) {
            ci.cancel();
        }
    }
}
