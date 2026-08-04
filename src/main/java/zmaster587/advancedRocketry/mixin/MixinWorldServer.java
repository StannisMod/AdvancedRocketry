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
 * (issue #66).
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
        // THE SKIP IS A POLICY BEFORE IT IS A ROUNDING. On a world whose skip is locked, the write
        // simply does not happen: vanilla's wakeAllPlayers() runs either way (it sits outside this
        // redirect), so the bed still sets a spawn point and everyone still gets up - the morning is
        // the only thing that does not come. That is the whole mechanic, and it costs one branch
        // because the vanilla sleep block already separates the conditional skip from the
        // unconditional wake.
        if (!zmaster587.advancedRocketry.world.TimeSkipPolicy.allows(self)) {
            ar$tellSleepersWhenDawnIs(self);
            return;
        }
        // Runtime belt-and-suspenders for the perDimWorldInfo master switch:
        // ARMixinPlugin weaves this mixin whenever EITHER the per-dim clock or the
        // skip policy needs it, so it can be present with the master off; defer to
        // vanilla rounding unless per-dim WorldInfo is active (so the planet's
        // per-dim clock is what we round).
        zmaster587.advancedRocketry.api.ARConfiguration cfg =
                zmaster587.advancedRocketry.api.ARConfiguration.getCurrentConfig();
        if (cfg != null && cfg.perDimWorldInfo && self.provider instanceof IPlanetaryProvider) {
            int rotationalPeriod = ((IPlanetaryProvider) self.provider).getRotationalPeriod(null);
            self.setWorldTime(ARDimensionWorldInfo.computeSleepWakeTime(self.getWorldTime(), rotationalPeriod));
        } else {
            self.setWorldTime(vanillaRounded);
        }
    }

    /**
     * Tell whoever just slept that this world's morning is not coming yet, and WHEN it is.
     *
     * <p>Vanilla says nothing here, and silence is what makes a locked bed read as a broken one.
     * The number is free: the same rounding that would have skipped the night already computes the
     * next dawn, so the wait is one subtraction from it. Sent at the sleep's completion rather than
     * at the right-click, because the rule is only worth stating to a player who actually slept.</p>
     */
    private void ar$tellSleepersWhenDawnIs(WorldServer self) {
        int rotationalPeriod = self.provider instanceof IPlanetaryProvider
                ? ((IPlanetaryProvider) self.provider).getRotationalPeriod(null) : 24000;
        long ticksToDawn = ARDimensionWorldInfo.computeSleepWakeTime(self.getWorldTime(), rotationalPeriod)
                - self.getWorldTime();
        // Real minutes, rounded up and never zero: "in 0 minutes" reads as a bug, and the player
        // asked how long to wait, not how many ticks.
        long minutes = Math.max(1L, (ticksToDawn + 1199L) / 1200L);
        for (net.minecraft.entity.player.EntityPlayer player : self.playerEntities) {
            if (player.isPlayerSleeping()) {
                player.sendMessage(new net.minecraft.util.text.TextComponentTranslation(
                        "msg.timeskip.locked", minutes));
            }
        }
    }
}
