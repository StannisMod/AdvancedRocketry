package zmaster587.advancedRocketry.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.entity.EntityRocket;

/**
 * Free Flight camera bank (roll).
 *
 * <p>Vanilla MC gives the player camera yaw + pitch only. Free Flight adds a
 * roll (bank) DOF; the camera must roll with the craft for a true cockpit view.
 * {@code orientCamera} applies the view pitch/yaw rotations, so a roll about the
 * eye-space Z axis injected at its HEAD (specified first ⇒ applied outermost)
 * banks the whole rendered view. Interpolated by partialTicks off the craft's
 * full-precision replicated roll. A no-op unless the render-view entity is
 * riding a Free-Flight craft in flight.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererFFCameraRoll {

    @Inject(method = "orientCamera", at = @At("HEAD"))
    private void advancedrocketry$bankFreeFlightCamera(float partialTicks, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        Entity ridden = view.getRidingEntity();
        if (!(ridden instanceof EntityRocket)) return;
        EntityRocket rocket = (EntityRocket) ridden;
        if (!(rocket.isFreeFlight() && rocket.isInFlight())) return;

        float rollDelta = FreeFlightPhysics.wrapDeg(
                rocket.getFreeFlightRoll() - rocket.getPrevFreeFlightRoll());
        float roll = rocket.getPrevFreeFlightRoll() + rollDelta * partialTicks;
        GlStateManager.rotate(roll, 0.0F, 0.0F, 1.0F);
    }
}
