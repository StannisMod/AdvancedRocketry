package zmaster587.advancedRocketry.mixin;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketChangeGameState;
import net.minecraft.server.management.PlayerList;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tightens vanilla 1.12.2 {@link PlayerList#updateTimeAndWeatherForPlayer}.
 *
 * <p>The vanilla packet codes themselves are correct (a careful read of
 * {@link net.minecraft.client.network.NetHandlerPlayClient#handleChangeGameState}
 * shows code 1 → {@code setRaining(true)} and code 2 → {@code setRaining(false)};
 * the wiki/MCP docstrings have the labels swapped, but server + client are
 * consistent with each other). What vanilla DOES get wrong is the gate:
 *
 * <pre>
 * if (worldIn.isRaining()) {
 *     // World.isRaining() returns getRainStrength(1.0F) &gt; 0.2D — i.e. the
 *     // current LERPED strength, NOT the WorldInfo flag.
 *     ...
 * }
 * </pre>
 *
 * <p>So immediately after {@code /weather rain} (flag=true, strength still
 * climbing from 0), vanilla skips the entire weather-sync block: a joining /
 * dim-transitioning player sees no rain until the strength catches up
 * naturally. For AR per-dim weather this is especially visible — every
 * cross-dim teleport into a freshly-raining planet showed clear weather for
 * the first second.</p>
 *
 * <p><b>Why a redirect rather than a HEAD cancel.</b> This mixin used to
 * cancel the vanilla method and re-issue all of its packets from a copy. A
 * copy of someone else's method has to reproduce it in full, and this one did
 * not: it silently dropped vanilla's {@code SPacketSpawnPosition}, so from
 * 2026-05-31 every player's client kept {@code WorldClient}'s placeholder
 * spawn {@code (8,64,8)} after login and after every cross-dimension transfer
 * — a compass pointing at nothing. Redirecting the single call we actually
 * disagree with leaves every other packet, present and future, owned by
 * vanilla, which removes that whole class of drift. Pinned by
 * {@code SpawnPointReachesClientE2ETest}.</p>
 *
 * <p>Both injections carry {@code require = 1}: this mixin has one target
 * class and no fallback path, so a selector that silently matched nothing
 * would reintroduce the bug invisibly. The config's {@code defaultRequire} is
 * 0, so without this an injector miss is a no-op, not an error.</p>
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerList {

    /**
     * Gate the weather-sync block on the {@code WorldInfo} flag instead of the
     * lerped strength, so a freshly-set raining dim syncs even while the
     * strength is still 0.
     */
    @Redirect(
            method = "updateTimeAndWeatherForPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;isRaining()Z"),
            require = 1)
    private boolean ar$rainFlagInsteadOfLerpedStrength(WorldServer world) {
        return world.getWorldInfo().isRaining();
    }

    /**
     * Vanilla has no "else" branch: when it is not raining it sends nothing,
     * which leaves a player arriving from a raining dimension in a partial-rain
     * state. Spell it out — clear the flag and zero both strengths.
     */
    @Inject(method = "updateTimeAndWeatherForPlayer", at = @At("TAIL"), require = 1)
    private void ar$clearStaleWeatherOnArrival(EntityPlayerMP playerIn,
                                               WorldServer worldIn,
                                               CallbackInfo ci) {
        if (!worldIn.getWorldInfo().isRaining()) {
            playerIn.connection.sendPacket(new SPacketChangeGameState(2, 0.0F));
            playerIn.connection.sendPacket(new SPacketChangeGameState(7, 0.0F));
            playerIn.connection.sendPacket(new SPacketChangeGameState(8, 0.0F));
        }
    }
}
