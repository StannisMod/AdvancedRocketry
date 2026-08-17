package zmaster587.advancedRocketry.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import zmaster587.advancedRocketry.client.ClientShotTracker;

/**
 * Draws what the client has been told is in the air: a streak per round, a flash where one stopped.
 *
 * <h3>A streak, not a dot</h3>
 * <p>A round crossing sixty blocks in a tick is never in the same place two frames running, so a
 * point drawn at its position is a point nobody sees. What is drawn instead is the segment between
 * where it was and where it is, interpolated by the frame's partial tick — the same thing a tracer
 * is in life, and for the same reason.</p>
 */
@SideOnly(Side.CLIENT)
public class RenderShots {

    /** How far behind the round the streak trails, as a fraction of one tick's travel. */
    private static final double STREAK_TICKS = 1.0D;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.isGamePaused()) {
            return;
        }
        ClientShotTracker.tick();
    }

    /** Leaving a world drops every drawing: a round from the last dimension has no business here. */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() != null && event.getWorld().isRemote) {
            ClientShotTracker.clear();
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (ClientShotTracker.count() == 0 && ClientShotTracker.impacts().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            return;
        }
        float partial = event.getPartialTicks();
        double eyeX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial;
        double eyeY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial;
        double eyeZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.depthMask(false);
        GlStateManager.glLineWidth(2.0F);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);

        for (ClientShotTracker.ClientShot shot : ClientShotTracker.inFlight()) {
            Vec3d previous = shot.getPrevious();
            Vec3d current = shot.getPosition();
            Vec3d head = previous.add(current.subtract(previous).scale(partial));
            Vec3d tail = head.subtract(shot.getVelocity().scale(STREAK_TICKS));
            buffer.pos(head.x - eyeX, head.y - eyeY, head.z - eyeZ).color(1.0F, 0.85F, 0.45F, 1.0F).endVertex();
            buffer.pos(tail.x - eyeX, tail.y - eyeY, tail.z - eyeZ).color(1.0F, 0.35F, 0.1F, 0.0F).endVertex();
        }

        for (ClientShotTracker.Impact impact : ClientShotTracker.impacts()) {
            Vec3d point = impact.getPoint();
            float intensity = impact.getIntensity();
            double size = 0.6D * intensity;
            for (int axis = 0; axis < 3; axis++) {
                double dx = axis == 0 ? size : 0.0D;
                double dy = axis == 1 ? size : 0.0D;
                double dz = axis == 2 ? size : 0.0D;
                buffer.pos(point.x - dx - eyeX, point.y - dy - eyeY, point.z - dz - eyeZ)
                        .color(1.0F, 0.9F, 0.6F, intensity).endVertex();
                buffer.pos(point.x + dx - eyeX, point.y + dy - eyeY, point.z + dz - eyeZ)
                        .color(1.0F, 0.9F, 0.6F, intensity).endVertex();
            }
        }

        tessellator.draw();

        GlStateManager.glLineWidth(1.0F);
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }
}
