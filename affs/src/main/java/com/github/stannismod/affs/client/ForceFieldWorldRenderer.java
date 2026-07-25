package com.github.stannismod.affs.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.opengl.GL11;
import zmaster587.advancedRocketry.api.Constants;

@Mod.EventBusSubscriber(modid = Constants.modId, value = Side.CLIENT)
public final class ForceFieldWorldRenderer {

    private ForceFieldWorldRenderer() {
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null || mc.getRenderViewEntity() == null) {
            return;
        }

        ClientForceFieldRenderCache.RenderMesh mesh = ClientForceFieldRenderCache.getMesh(world);
        if (mesh == null || mesh == ClientForceFieldRenderCache.RenderMesh.EMPTY || mesh.getTriangles().isEmpty()) {
            return;
        }

        double camX = mc.getRenderManager().viewerPosX;
        double camY = mc.getRenderManager().viewerPosY;
        double camZ = mc.getRenderManager().viewerPosZ;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.depthMask(false);
        GlStateManager.translate(-camX, -camY, -camZ);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);

        for (ClientForceFieldRenderCache.Triangle triangle : mesh.getTriangles()) {
            writeTriangle(buffer, triangle.a.x, triangle.a.y, triangle.a.z, triangle.b.x, triangle.b.y, triangle.b.z, triangle.c.x, triangle.c.y, triangle.c.z);
        }

        tessellator.draw();

        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
        GL11.glPopAttrib();

        ClientFieldTouchEffectCache.render(world, event.getPartialTicks(), camX, camY, camZ);
    }

    private static void writeTriangle(BufferBuilder buffer,
                                      double ax, double ay, double az,
                                      double bx, double by, double bz,
                                      double cx, double cy, double cz) {
        buffer.pos(ax, ay, az).color(0.16F, 0.72F, 1.0F, 0.26F).endVertex();
        buffer.pos(bx, by, bz).color(0.16F, 0.72F, 1.0F, 0.26F).endVertex();
        buffer.pos(cx, cy, cz).color(0.16F, 0.72F, 1.0F, 0.26F).endVertex();
    }
}
