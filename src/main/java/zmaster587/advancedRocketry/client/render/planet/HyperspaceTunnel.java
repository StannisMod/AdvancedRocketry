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
import zmaster587.advancedRocketry.space.HyperspaceWorld;

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
 * ship turns instead of stepping at the tick rate. A crew member on his feet rides nothing, so for
 * him the same attitude is read off the ship he is standing on; see {@link #shipHeading}.
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
     * Which way the SHIP is pointing, as {@code {yaw, pitch}} in degrees — the corridor's axis.
     *
     * <p>Three sources, in order of how directly each knows the ship:</p>
     * <ol>
     *   <li><b>The seat the viewer is riding.</b> {@link EntityDummy} glues its rotation to the
     *       ship's attitude every tick, and vanilla has already copied that tick's value into
     *       {@code prev*}, so this one can be INTERPOLATED across the frame — it sweeps with the
     *       camera in a turn instead of stepping at the tick rate.</li>
     *   <li><b>The ship the viewer is standing on.</b> A crew member on his feet rides nothing, so
     *       the same attitude is asked of the physics integration directly, by containment. It has
     *       no previous-frame value to interpolate against; a ship parked in its transit lane is not
     *       turning, so there is nothing for the interpolation to smooth.</li>
     *   <li><b>The viewer's own look.</b> Last resort only, for a body in hyperspace that is aboard
     *       no ship at all. It swings the corridor with the mouse, which reads as the world turning
     *       rather than the ship travelling — which is why it is never used while a ship can be
     *       found, and why anyone in that state is about to be taken by the void anyway.</li>
     * </ol>
     */
    private static float[] shipHeading(Minecraft mc, net.minecraft.world.World world,
                                       float partialTicks) {
        Entity riding = mc.player.getRidingEntity();
        if (riding == null) {
            zmaster587.advancedRocketry.api.FreeFlightPhysics.Quat attitude =
                    zmaster587.advancedRocketry.integration.vs.VSIntegration.shipAttitudeAt(
                            world, mc.player.posX, mc.player.posY, mc.player.posZ);
            if (attitude != null) {
                float[] euler = zmaster587.advancedRocketry.api.FreeFlightPhysics
                        .eulerFromQuat(attitude);
                return new float[] {euler[0], euler[1]};
            }
        }
        Entity axisSource = riding != null ? riding : mc.player;
        return new float[] {
                axisSource.prevRotationYaw + net.minecraft.util.math.MathHelper.wrapDegrees(
                        axisSource.rotationYaw - axisSource.prevRotationYaw) * partialTicks,
                axisSource.prevRotationPitch
                        + (axisSource.rotationPitch - axisSource.prevRotationPitch) * partialTicks
        };
    }

    /** Draw the corridor for this frame. Call from inside a sky renderer. */
    public static void render(float partialTicks, net.minecraft.world.World world) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || world == null) {
            return;
        }
        float[] heading = shipHeading(mc, world, partialTicks);
        float[] a = CorridorGeometry.axis(heading[0], heading[1]);
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
