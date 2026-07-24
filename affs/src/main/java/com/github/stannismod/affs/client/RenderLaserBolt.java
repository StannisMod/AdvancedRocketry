package com.github.stannismod.affs.client;

import com.github.stannismod.affs.entity.EntityLaserBolt;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;

public class RenderLaserBolt extends Render<EntityLaserBolt> {

    public RenderLaserBolt(RenderManager renderManager) {
        super(renderManager);
        this.shadowSize = 0.0F;
    }

    @Override
    public void doRender(EntityLaserBolt entity, double x, double y, double z, float entityYaw, float partialTicks) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        GlStateManager.depthMask(false);

        float yaw = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
        float pitch = entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
        GlStateManager.rotate(180.0F - yaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-pitch, 1.0F, 0.0F, 0.0F);

        double speed = Math.sqrt(entity.motionX * entity.motionX + entity.motionY * entity.motionY + entity.motionZ * entity.motionZ);
        double length = Math.min(1.2D, 0.35D + speed * 0.35D);
        double width = 0.05D;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        drawBeam(buffer, -width, -width, 0.0D, -width, width, 0.0D, width, width, -length, width, -width, -length);
        drawBeam(buffer, -width, 0.0D, -width, -width, 0.0D, width, width, 0.0D, width, width, 0.0D, -width);
        tessellator.draw();

        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    private static void drawBeam(BufferBuilder buffer,
                                 double ax, double ay, double az,
                                 double bx, double by, double bz,
                                 double cx, double cy, double cz,
                                 double dx, double dy, double dz) {
        colorVertex(buffer, ax, ay, az, 0.65F, 0.90F, 1.0F, 0.82F);
        colorVertex(buffer, bx, by, bz, 0.45F, 0.82F, 1.0F, 0.86F);
        colorVertex(buffer, cx, cy, cz, 0.12F, 0.52F, 1.0F, 0.20F);
        colorVertex(buffer, dx, dy, dz, 0.12F, 0.52F, 1.0F, 0.20F);
    }

    private static void colorVertex(BufferBuilder buffer, double x, double y, double z, float r, float g, float b, float a) {
        buffer.pos(x, y, z).color(r, g, b, a).endVertex();
    }

    @Nullable
    @Override
    protected ResourceLocation getEntityTexture(EntityLaserBolt entity) {
        return null;
    }
}
