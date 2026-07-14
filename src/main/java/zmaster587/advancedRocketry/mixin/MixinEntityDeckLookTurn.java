package zmaster587.advancedRocketry.mixin;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.client.DeckMouseInput;

/**
 * Turns raw mouse-look input into the deck's frame for a crew member walking a rolled deck.
 *
 * <p>{@code Entity.turn} adds the mouse delta straight onto world yaw/pitch. While the deck camera
 * rolls the view to level it with a tilted deck, that leaves the input feeling rotated relative to the
 * screen (inverted on an upside-down deck). {@link DeckMouseInput#applyDeckRelativeTurn} rotates the
 * delta by the camera roll and applies it, then this cancels the vanilla turn; it is inert (returns
 * false) for anyone who is not the local player being resolved on a deck, so every other {@code turn}
 * runs untouched. Client-only, and references no physics-mod type.</p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityDeckLookTurn {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void advancedrocketry$deckRelativeTurn(float yaw, float pitch, CallbackInfo ci) {
        if (DeckMouseInput.applyDeckRelativeTurn((Entity) (Object) this, yaw, pitch)) {
            ci.cancel();
        }
    }
}
