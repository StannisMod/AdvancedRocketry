package zmaster587.advancedRocketry.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;

/**
 * Notifies the per-world {@link AtmosphereHandler} after every successful
 * {@link World#setBlockState(BlockPos, IBlockState, int)} so atmosphere
 * volumes recompute when an air-tight boundary block is placed/broken.
 *
 * <p>Hook fires on RETURN (after vanilla has updated the chunk + lighting),
 * matching the original ASM injection that was placed immediately before
 * the {@code IRETURN}.</p>
 *
 * <p>Replaces the equivalent {@code IClassTransformer} hook formerly in
 * {@code asm/ClassTransformer.java}.</p>
 *
 * <p><b>{@code require = 1} is deliberate.</b> This is the sole caller of
 * {@link AtmosphereHandler#onBlockChange} and there is no fallback path: if the
 * injector silently matched nothing, sealed rooms would simply stop recomputing
 * and players would suffocate in a base that looks intact. The config's
 * {@code defaultRequire} is 0, so without this a missed selector is a no-op
 * rather than an error — failing loudly at load is far better than that.</p>
 */
@Mixin(World.class)
public abstract class MixinWorldSetBlockState {

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;"
            + "Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At("RETURN"),
            require = 1)
    private void ar$notifyAtmosphere(BlockPos pos, IBlockState newState, int flags,
                                     CallbackInfoReturnable<Boolean> cir) {
        AtmosphereHandler.onBlockChange((World) (Object) this, pos);
    }
}
