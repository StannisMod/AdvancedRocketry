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
 * axis, drifting toward the viewer, so motion is legible from a single glance out of the cockpit.
 *
 * <p><b>Why rings rather than a solid tube.</b> A filled cylinder would have to argue with the
 * ship's hull for the same pixels in third person, and it would hide it. Open rings sit around the
 * hull instead of in front of it, cost a few hundred line vertices a frame, and need no texture.
 *
 * <p><b>The axis is the SHIP's, not the camera's.</b> Taking it from the view would swing the whole
 * corridor with the mouse, which reads as the world turning rather than the ship travelling. It is
 * taken from the entity the pilot is riding, which is glued to the ship.
 *
 * <p>Drawn inside the sky renderer, so the camera is already at the origin of this frame and the
 * depth mask is already off: the corridor is a backdrop, and the ship draws over it.
 */
@SideOnly(Side.CLIENT)
public final class HyperspaceTunnel {

    private HyperspaceTunnel() {
    }

    /** Rings drawn, nearest to farthest. */
    private static final int RINGS = 24;
    /** Segments per ring. Twelve is round enough at this radius and keeps the vertex count trivial. */
    private static final int SEGMENTS = 12;
    /** Ring radius in sky units. Wide enough that the hull sits inside it at any sane ship size. */
    private static final float RADIUS = 22.0f;
    /** Distance between neighbouring rings, in the same units. */
    private static final float SPACING = 7.0f;
    /** How fast the corridor drifts toward the viewer, rings per tick. */
    private static final float DRIFT_PER_TICK = 0.12f;

    /**
     * Frames on which the corridor has actually been drawn. Read by the client e2e: whether the
     * corridor APPEARS is a render judgement and belongs to a playtest, but whether it RAN at all is
     * an observable fact, and a test that cannot tell the difference between "drawn" and "never
     * reached" is not a test.
     */
    public static volatile long framesDrawn = 0L;

    /**
     * The jump phase of the ship the local player is riding, or 0 when he is not aboard one in
     * flight. The client learns this from the seat entity it is already tracking; there is no
     * separate channel and nothing for the client to compute.
     */
    public static int localTransitPhase() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null) {
            return 0;
        }
        Entity riding = mc.player.getRidingEntity();
        return riding instanceof EntityDummy ? ((EntityDummy) riding).getTransitPhase() : 0;
    }

    /** Draw the corridor for this frame. Call from inside a sky renderer. */
    public static void render(float partialTicks, net.minecraft.world.World world) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || world == null) {
            return;
        }
        Entity riding = mc.player.getRidingEntity();
        Entity axisSource = riding != null ? riding : mc.player;

        // The ship's forward, from the entity glued to it.
        double yaw = Math.toRadians(axisSource.rotationYaw);
        double pitch = Math.toRadians(axisSource.rotationPitch);
        float ax = (float) (-Math.sin(yaw) * Math.cos(pitch));
        float ay = (float) (-Math.sin(pitch));
        float az = (float) (Math.cos(yaw) * Math.cos(pitch));

        // Any two vectors perpendicular to the axis will do for the ring plane; pick the one that
        // stays well-conditioned when the ship points straight up or down.
        float[] u = perpendicular(ax, ay, az);
        float[] v = cross(ax, ay, az, u[0], u[1], u[2]);

        float drift = ((world.getTotalWorldTime() + partialTicks) * DRIFT_PER_TICK) % 1.0f;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        GlStateManager.glLineWidth(2.0f);

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        for (int ring = 0; ring < RINGS; ring++) {
            // The nearest ring is the one that has almost arrived; fading it out as it passes is what
            // keeps rings from popping into existence at the viewer's nose.
            float along = (ring + drift) * SPACING;
            float depth = (float) ring / (float) RINGS;
            float alpha = (1.0f - depth) * (1.0f - depth) * 0.55f;
            if (ring == 0) {
                alpha *= (1.0f - drift);
            }
            buffer.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
            for (int seg = 0; seg < SEGMENTS; seg++) {
                double theta = (Math.PI * 2.0 * seg) / SEGMENTS;
                float cos = (float) Math.cos(theta);
                float sin = (float) Math.sin(theta);
                float x = ax * along + (u[0] * cos + v[0] * sin) * RADIUS;
                float y = ay * along + (u[1] * cos + v[1] * sin) * RADIUS;
                float z = az * along + (u[2] * cos + v[2] * sin) * RADIUS;
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

    /** Some unit vector perpendicular to (x,y,z), chosen to stay stable near the poles. */
    private static float[] perpendicular(float x, float y, float z) {
        float[] candidate = Math.abs(y) < 0.9f
                ? cross(x, y, z, 0.0f, 1.0f, 0.0f)
                : cross(x, y, z, 1.0f, 0.0f, 0.0f);
        return normalize(candidate);
    }

    private static float[] cross(float ax, float ay, float az, float bx, float by, float bz) {
        return new float[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    private static float[] normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1.0e-4f) {
            return new float[]{1.0f, 0.0f, 0.0f};
        }
        return new float[]{v[0] / len, v[1] / len, v[2] / len};
    }
}
