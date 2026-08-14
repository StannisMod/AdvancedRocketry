package zmaster587.advancedRocketry.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.world.provider.WorldProviderPlanet;

/**
 * Tints falling precipitation a sickly green on AR planets whose rain is
 * acidic ({@code acidicRain=true}). The visual cue matches the
 * {@link zmaster587.advancedRocketry.event.AcidRainHandler} damage so players
 * can see the danger before they feel it.
 *
 * <p>{@code EntityRenderer.renderRainSnow} sets the global GL colour to white
 * once, before drawing every rain/snow quad (whose per-vertex colour is white
 * too). Redirecting that single call multiplies the whole pass by the acid
 * tint, so the rain renders green without touching the per-vertex loop. The
 * {@code acidicRain} flag reaches the client through {@code PacketDimInfo}.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class MixinAcidRainRender {

    // Sickly yellow-green. Multiplied onto the white rain texture.
    private static final float ACID_R = 0.45F;
    private static final float ACID_G = 0.95F;
    private static final float ACID_B = 0.30F;

    @Redirect(
            method = "renderRainSnow",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;color(FFFF)V"),
            // OptiFine and other render mods rewrite renderRainSnow; if the target
            // call isn't present we silently skip. NB this is the config's own
            // default (no "injectors" block => defaultRequire = 0), so the
            // annotation is documentation, not protection — and "required": true
            // does NOT change it: that flag governs mixin APPLICATION failure,
            // not injector misses, and it crashes rather than silently disabling.
            require = 0)
    private void ar$tintAcidRain(float red, float green, float blue, float alpha) {
        if (ar$isAcidicRainHere()) {
            GlStateManager.color(red * ACID_R, green * ACID_G, blue * ACID_B, alpha);
        } else {
            GlStateManager.color(red, green, blue, alpha);
        }
    }

    private static boolean ar$isAcidicRainHere() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || !(mc.world.provider instanceof WorldProviderPlanet)) {
            return false;
        }
        DimensionProperties props = DimensionManager.getInstance()
                .getDimensionProperties(mc.world.provider.getDimension());
        return props != null && props.isAcidicRain();
    }
}
