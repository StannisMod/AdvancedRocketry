package com.github.stannismod.affs.client;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.te.TileEntityContourInjector;
import com.github.stannismod.affs.world.contour.ContourFrameGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;

@Mod.EventBusSubscriber(modid = AdvancedForceFieldSystem.MODID, value = Side.CLIENT)
public final class ContourFieldWorldRenderer {

    private ContourFieldWorldRenderer() {
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null || mc.getRenderViewEntity() == null) {
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

        renderActiveContours(world);

        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
        GL11.glPopAttrib();
    }

    private static void renderActiveContours(World world) {
        for (TileEntity tileEntity : new ArrayList<>(world.loadedTileEntityList)) {
            if (!(tileEntity instanceof TileEntityContourInjector)) {
                continue;
            }

            TileEntityContourInjector injector = (TileEntityContourInjector) tileEntity;
            if (!injector.isFieldActive()) {
                continue;
            }

            ContourFrameGeometry geometry = ContourFrameGeometry.find(
                    world,
                    injector.getPos(),
                    AdvancedForceFieldSystem.BLOCK_CONTOUR_FRAME,
                    TileEntityContourInjector.MAX_SCAN_RADIUS
            );
            if (geometry == null) {
                continue;
            }

            renderBox(geometry.getFieldBox().grow(0.002D));
        }
    }

    private static void renderBox(AxisAlignedBB box) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        float r = 0.24F;
        float g = 0.92F;
        float b = 0.78F;
        float a = 0.18F;

        double minX = box.minX;
        double minY = box.minY;
        double minZ = box.minZ;
        double maxX = box.maxX;
        double maxY = box.maxY;
        double maxZ = box.maxZ;

        // Bottom
        quad(buffer, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
        // Top
        quad(buffer, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, r, g, b, a);
        // North
        quad(buffer, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, r, g, b, a);
        // South
        quad(buffer, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
        // West
        quad(buffer, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);
        // East
        quad(buffer, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, r, g, b, a);

        tessellator.draw();
    }

    private static void quad(BufferBuilder buffer,
                             double ax, double ay, double az,
                             double bx, double by, double bz,
                             double cx, double cy, double cz,
                             double dx, double dy, double dz,
                             float r, float g, float b, float a) {
        buffer.pos(ax, ay, az).color(r, g, b, a).endVertex();
        buffer.pos(bx, by, bz).color(r, g, b, a).endVertex();
        buffer.pos(cx, cy, cz).color(r, g, b, a).endVertex();
        buffer.pos(dx, dy, dz).color(r, g, b, a).endVertex();
    }
}
