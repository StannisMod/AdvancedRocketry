package zmaster587.advancedRocketry.mixin;

import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import zmaster587.advancedRocketry.api.IPlanetaryProvider;
import zmaster587.advancedRocketry.world.weather.ARDimensionWorldInfo;

/**
 * Makes beds bring the planet's morning. Vanilla's sleep skip in
 * {@link WorldServer#tick()} rounds the new time to the next multiple of
 * 24000, but AR planets render day/night from {@code rotationalPeriod}
 * (≈ {@code (1/gravity)^3 * 24000}, ≠ 24000 for almost every planet), so the
 * vanilla rounding lands at an arbitrary phase — usually still night
 * (issue #66 / TASK-47).
 *
 * <p>We {@code @Redirect} the FIRST {@code setWorldTime} call in {@code tick()}
 * (ordinal 0 = the sleep-skip block; ordinal 1 is the per-tick +1 increment)
 * and, for {@link IPlanetaryProvider} dimensions, round to the dimension's
 * {@code rotationalPeriod} instead. The rounding math lives in
 * {@link ARDimensionWorldInfo#computeSleepWakeTime(long, int)} so it is unit
 * tested. Non-AR worlds keep vanilla behaviour untouched.</p>
 *
 * <p>The per-dimension clock this writes into is owned by the
 * {@link ARDimensionWorldInfo} wrapper (per-dim time, not the swallowed
 * {@code DerivedWorldInfo} no-op), so the skip actually takes effect.</p>
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    @Redirect(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/WorldServer;setWorldTime(J)V",
                    ordinal = 0))
    private void ar$roundSleepWakeToRotationalPeriod(WorldServer self, long vanillaRounded) {
        if (self.provider instanceof IPlanetaryProvider) {
            int rotationalPeriod = ((IPlanetaryProvider) self.provider).getRotationalPeriod(null);
            self.setWorldTime(ARDimensionWorldInfo.computeSleepWakeTime(self.getWorldTime(), rotationalPeriod));
        } else {
            self.setWorldTime(vanillaRounded);
        }
    }
}
