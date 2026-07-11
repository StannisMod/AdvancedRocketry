package zmaster587.advancedRocketry.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import zmaster587.advancedRocketry.client.ShipFrameCamera;

/**
 * Puts the camera where an aboard entity's head actually is.
 *
 * <p>{@code EntityRenderer.orientCamera} ends with {@code GlStateManager.translate(0, -eyeHeight, 0)}.
 * Being the last transform pushed it is the first one a world vertex meets, so the eye is always
 * {@code entityPosition + (0, eyeHeight, 0)} - along the WORLD up, whatever the body is standing on.
 * Roll a ship past vertical and the pilot's eye moves into the deck hanging above his seat: the camera
 * is inside a solid block and nothing renders.</p>
 *
 * <p>Redirecting that one call to offset along the SHIP's up instead fixes it for the pilot and for any
 * crew member. Everything else about the camera - the third-person boom, the sleeping case, the
 * rotations - is untouched.</p>
 *
 * <p>{@code require = 0}: OptiFine and other render mods rewrite {@code orientCamera}. If the call is
 * not there we silently keep vanilla's eye rather than aborting the whole (required) mixin config.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererShipEye {

    /** The eye-height translate is the 5th {@code translate(FFF)} in {@code orientCamera}; the four
     *  before it belong to the sleeping, debug-cam, third-person and first-person branches. */
    @Redirect(
            method = "orientCamera",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V",
                    ordinal = 4),
            require = 0)
    private void advancedrocketry$shipEyeOffset(float x, float y, float z) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        float partialTicks = mc.getRenderPartialTicks();
        double[] up = view == null ? null : ShipFrameCamera.shipUpFor(view, partialTicks);
        if (up == null) {
            GlStateManager.translate(x, y, z);
            return;
        }
        // y is -eyeHeight: the scene is pushed down so the camera sits eyeHeight above the feet.
        float eyeHeight = -y;
        GlStateManager.translate(
                (float) (-eyeHeight * up[0]),
                (float) (-eyeHeight * up[1]),
                (float) (-eyeHeight * up[2]));

        double px = view.prevPosX + (view.posX - view.prevPosX) * partialTicks;
        double py = view.prevPosY + (view.posY - view.prevPosY) * partialTicks;
        double pz = view.prevPosZ + (view.posZ - view.prevPosZ) * partialTicks;
        ShipFrameCamera.recordCamera(true,
                ShipFrameCamera.shipCamYaw, ShipFrameCamera.shipCamPitch, ShipFrameCamera.shipCamRoll,
                up,
                px + eyeHeight * up[0], py + eyeHeight * up[1], pz + eyeHeight * up[2]);
    }
}
