package zmaster587.advancedRocketry.client.render.planet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.opengl.GL11;

import zmaster587.advancedRocketry.entity.EntityDummy;

/**
 * The corridor a ship flies down while it is in hyperspace.
 *
 * <p>A transit has no controls, no bodies in the sky and no number counting down, so without this
 * the flight is the same starfield the pilot was already looking at and nothing tells him he is
 * moving at all. The tunnel is that signal: a corridor of rings running away along the ship's own
 * axis and coming at him, so motion is legible from a single glance out of the cockpit. Which way
 * the rings travel is the whole point and not a detail: a corridor that recedes says, just as
 * clearly, that the ship is going backwards.
 *
 * <p><b>Why rings rather than a solid tube.</b> A filled cylinder would have to argue with the
 * ship's hull for the same pixels in third person, and it would hide it. Open rings sit around the
 * hull instead of in front of it, cost a few hundred line vertices a frame, and need no texture.
 *
 * <p><b>The axis is the SHIP's, not the camera's.</b> Taking it from the view would swing the whole
 * corridor with the mouse, which reads as the world turning rather than the ship travelling. It is
 * taken from the entity the pilot is riding, whose rotation {@link EntityDummy} glues to the ship's
 * attitude every tick — and interpolated across the frame, so it sweeps with the camera when the
 * ship turns instead of stepping at the tick rate.
 *
 * <p>Drawn inside the sky renderer, so the camera is already at the origin of this frame and the
 * depth mask is already off: the corridor is a backdrop, and the ship draws over it.
 */
@SideOnly(Side.CLIENT)
public final class HyperspaceTunnel {

    private HyperspaceTunnel() {
    }

    /**
     * Frames on which the corridor has actually been drawn. Read by the client e2e: whether the
     * corridor APPEARS is a render judgement and belongs to a playtest, but whether it RAN at all is
     * an observable fact, and a test that cannot tell the difference between "drawn" and "never
     * reached" is not a test.
     */
    public static volatile long framesDrawn = 0L;

    /**
     * Draw the corridor for this frame. Call from inside a sky renderer, having already established
     * that this world IS hyperspace — the caller owns that gate, and it is a question about the
     * WORLD. This class used to answer it itself by reading the jump phase off the seat entity the
     * player was riding, which made the corridor a property of sitting down: standing up returned 0
     * and emptied a sky that has nothing else in it.
     */
    public static void render(float partialTicks, net.minecraft.world.World world) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || world == null) {
            return;
        }
        Entity riding = mc.player.getRidingEntity();
        Entity axisSource = riding != null ? riding : mc.player;

        // The ship's forward, from the entity glued to it, at this FRAME rather than at the last
        // tick: the camera is interpolated, so an axis that stepped would swim against it in a turn.
        float yaw = axisSource.prevRotationYaw + net.minecraft.util.math.MathHelper.wrapDegrees(
                axisSource.rotationYaw - axisSource.prevRotationYaw) * partialTicks;
        float pitch = axisSource.prevRotationPitch
                + (axisSource.rotationPitch - axisSource.prevRotationPitch) * partialTicks;
        float[] a = CorridorGeometry.axis(yaw, pitch);
        float ax = a[0], ay = a[1], az = a[2];

        // Any two vectors perpendicular to the axis will do for the ring plane; pick the one that
        // stays well-conditioned when the ship points straight up or down.
        float[] u = CorridorGeometry.perpendicular(ax, ay, az);
        float[] v = CorridorGeometry.cross(ax, ay, az, u[0], u[1], u[2]);

        float drift = CorridorGeometry.driftAt(world.getTotalWorldTime() + partialTicks);

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        GlStateManager.glLineWidth(2.0f);

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        for (int ring = 0; ring < CorridorGeometry.RINGS; ring++) {
            float along = CorridorGeometry.ringDistance(ring, drift);
            float alpha = CorridorGeometry.ringAlpha(ring, drift);
            buffer.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
            for (int seg = 0; seg < CorridorGeometry.SEGMENTS; seg++) {
                double theta = (Math.PI * 2.0 * seg) / CorridorGeometry.SEGMENTS;
                float cos = (float) Math.cos(theta);
                float sin = (float) Math.sin(theta);
                float x = ax * along + (u[0] * cos + v[0] * sin) * CorridorGeometry.RADIUS;
                float y = ay * along + (u[1] * cos + v[1] * sin) * CorridorGeometry.RADIUS;
                float z = az * along + (u[2] * cos + v[2] * sin) * CorridorGeometry.RADIUS;
                buffer.pos(x, y, z).color(0.45f, 0.75f, 1.0f, alpha).endVertex();
            }
            Tessellator.getInstance().draw();
        }

        GlStateManager.glLineWidth(1.0f);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
        framesDrawn++;
    }
}
