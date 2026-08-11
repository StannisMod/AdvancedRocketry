package zmaster587.advancedRocketry.mixin;

import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zmaster587.advancedRocketry.world.ARPlanetWorldInfo;

/**
 * Installs Advanced Rocketry's per-dimension {@link net.minecraft.world.storage.WorldInfo} on an AR
 * dimension, at the only moment early enough to matter.
 *
 * <p>{@code WorldProvider.setWorld} is where vanilla caches this world's terrain type and generator
 * options into private fields ({@code WorldProvider:52-53}) and then calls {@code init()}; the
 * enclosing {@code WorldServer} constructor builds the chunk provider on the very next line. Anything
 * that swaps the {@code WorldInfo} later — a {@code WorldEvent.Load} handler, say — arrives after the
 * biome provider and the chunk generator have already been built from the OVERWORLD's values.
 * Injecting at HEAD puts the right info in place before any of that reads it.</p>
 *
 * <p>Deliberately NOT gated by the {@code perDimWorldInfo} config flag: that flag governs per-planet
 * weather and time, and which terrain a planet generates is not weather's business. The guard lives
 * in {@link ARPlanetWorldInfo#installIfNeeded(World)} instead, which touches only server-side AR
 * dimensions whose info is still vanilla's shared-overworld one.</p>
 */
@Mixin(WorldProvider.class)
public abstract class MixinWorldProvider {

    @Inject(method = "setWorld", at = @At("HEAD"))
    private void ar$installPerDimensionWorldInfo(World worldIn, CallbackInfo ci) {
        ARPlanetWorldInfo.installIfNeeded(worldIn);
    }
}
