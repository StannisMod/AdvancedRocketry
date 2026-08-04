package zmaster587.advancedRocketry.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player never loses sight of the vehicle he is riding.
 *
 * <p>Vanilla decides whether to send a tracked entity to a player with a PROXIMITY test:
 * {@code isVisibleTo} compares the player's live position against the entity's {@code encodedPos},
 * an anchor refreshed only once every {@code updateFrequency} ticks, against a square of
 * {@code trackingRange}. That is a bandwidth heuristic, and for an ordinary entity it is a good
 * one. For the vehicle a player is SITTING IN it is a category error: the player is at the entity
 * by definition of riding, so there is nothing to approximate.</p>
 *
 * <p>The error is not theoretical. An entity glued to a moving platform advances at the platform's
 * speed while its anchor stands still between refreshes, so it outruns its own box whenever
 * {@code speed &gt; trackingRange / updateFrequency}. The pilot-seat mount is registered 16/20, i.e.
 * 0.8 blocks/tick, well under the speed a ship actually flies; past that the server evicts the
 * mount from its own rider's view for most of every refresh cycle. The client then destroys the
 * vehicle, dismounts the player (a passenger of a removed entity is dismounted on the next tick),
 * and he free-falls in place until the anchor refreshes and the passenger packet re-seats him -
 * about once a second, for the whole burn. The seat is unusable and nothing anywhere logs a
 * dismount.</p>
 *
 * <p>Raising the range or the refresh rate only moves the threshold; the entity can always be made
 * to move faster. This restores the invariant instead: while the player rides it - directly or up a
 * chain of vehicles - the entity is visible to him, at any speed, at any range setting. Everyone
 * else keeps the proximity test unchanged.</p>
 *
 * <p>There is no politer seam. {@code isSpectatedByPlayer}, the one overridable hook
 * {@code isVisibleTo} consults, is ANDed into the result, so it can only ever RESTRICT visibility;
 * {@code forceSpawn} bypasses the chunk-watch check but not the range check; and the tracking
 * events are notifications, not vetoes.</p>
 */
@Mixin(EntityTrackerEntry.class)
public abstract class MixinEntityTrackerRiderSeesVehicle {

    @Shadow @Final private Entity trackedEntity;

    // require = 1: this config declares no `injectors` block, so Mixin's defaultRequire is 0 and an
    // injector that matched nothing would be silently dropped - a fix that is present in the source,
    // registered in the config, and does absolutely nothing at runtime. Fail the load instead.
    @Inject(method = "isVisibleTo", at = @At("HEAD"), cancellable = true, require = 1)
    private void ar$aRiderNeverLosesItsVehicle(EntityPlayerMP player,
                                               CallbackInfoReturnable<Boolean> cir) {
        Entity tracked = this.trackedEntity;
        // isRidingOrBeingRiddenBy walks the RECEIVER's passenger tree, so this asks "is this player
        // riding the entity we are tracking", directly or through an intermediate vehicle.
        if (tracked != null && player != null && tracked != player
                && tracked.isRidingOrBeingRiddenBy(player)) {
            cir.setReturnValue(true);
        }
    }
}
