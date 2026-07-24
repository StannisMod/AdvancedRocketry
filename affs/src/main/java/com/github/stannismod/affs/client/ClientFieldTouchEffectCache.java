package com.github.stannismod.affs.client;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientFieldTouchEffectCache {

    private static final Map<Integer, List<TouchEffect>> EFFECTS_BY_DIMENSION = new ConcurrentHashMap<>();
    private static final int MAX_AGE_TICKS = 20;
    private static final int SEGMENTS = 72;

    private ClientFieldTouchEffectCache() {
    }

    public static void addEffect(int dimension, BlockPos generatorPos, Vec3d contactPoint, long spawnTick) {
        if (spawnTick < 0L) {
            return;
        }
        List<TouchEffect> effects = EFFECTS_BY_DIMENSION.computeIfAbsent(dimension, key -> Collections.synchronizedList(new ArrayList<>()));
        effects.add(new TouchEffect(generatorPos, contactPoint, spawnTick, MAX_AGE_TICKS));
        if (effects.size() > 48) {
            effects.remove(0);
        }
    }

    public static void clearAll() {
        EFFECTS_BY_DIMENSION.clear();
    }

    public static void render(World world, float partialTicks, double camX, double camY, double camZ) {
        if (world == null) {
            return;
        }

        List<TouchEffect> effects = EFFECTS_BY_DIMENSION.get(world.provider.getDimension());
        if (effects == null || effects.isEmpty()) {
            return;
        }

        long currentTime = world.getTotalWorldTime();
        GL11.glLineWidth(3.5F);
        for (int i = 0; i < effects.size(); i++) {
            TouchEffect effect = effects.get(i);
            float age = (float) (currentTime - effect.spawnTick) + partialTicks;
            if (age >= effect.maxAge) {
                effects.remove(i--);
                continue;
            }
            renderEffect(effect, age, camX, camY, camZ);
        }
    }

    private static void renderEffect(TouchEffect effect, float age, double camX, double camY, double camZ) {
        Vec3d center = effect.contactPoint;
        Vec3d generatorCenter = new Vec3d(effect.generatorPos.getX() + 0.5D, effect.generatorPos.getY() + 0.5D, effect.generatorPos.getZ() + 0.5D);
        Vec3d normal = new Vec3d(center.x - generatorCenter.x, center.y - generatorCenter.y, center.z - generatorCenter.z);
        double normalLenSq = normal.x * normal.x + normal.y * normal.y + normal.z * normal.z;
        if (normalLenSq < 1.0E-6D) {
            normal = new Vec3d(0.0D, 1.0D, 0.0D);
        } else {
            normal = normal.normalize();
        }

        Vec3d tangent = Math.abs(normal.y) < 0.95D ? new Vec3d(0.0D, 1.0D, 0.0D) : new Vec3d(1.0D, 0.0D, 0.0D);
        Vec3d u = normal.crossProduct(tangent).normalize();
        Vec3d v = normal.crossProduct(u).normalize();

        float progress = Math.min(1.0F, age / (float) effect.maxAge);
        double baseRadius = 0.22D + progress * 1.35D;
        float fade = 1.0F - progress;

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        GL11.glPushMatrix();
        GL11.glTranslated(-camX, -camY, -camZ);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableCull();
        GlStateManager.disableLighting();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.depthMask(false);

        for (int ring = 0; ring < 3; ring++) {
            double ringRadius = baseRadius + ring * 0.28D;
            float alpha = Math.max(0.0F, fade * (0.72F - ring * 0.18F));
            float red = 0.30F + ring * 0.04F;
            float green = 0.92F + ring * 0.02F;
            float blue = 1.0F;
            buffer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int segment = 0; segment <= SEGMENTS; segment++) {
                double angle = (Math.PI * 2.0D * segment) / SEGMENTS;
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                Vec3d point = new Vec3d(
                    center.x + u.x * cos * ringRadius + v.x * sin * ringRadius,
                    center.y + u.y * cos * ringRadius + v.y * sin * ringRadius,
                    center.z + u.z * cos * ringRadius + v.z * sin * ringRadius
                );
                buffer.pos(point.x, point.y, point.z).color(red, green, blue, alpha).endVertex();
            }
            tessellator.draw();
        }

        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GL11.glPopMatrix();
    }

    private static final class TouchEffect {
        private final BlockPos generatorPos;
        private final Vec3d contactPoint;
        private final long spawnTick;
        private final int maxAge;

        private TouchEffect(BlockPos generatorPos, Vec3d contactPoint, long spawnTick, int maxAge) {
            this.generatorPos = generatorPos;
            this.contactPoint = contactPoint;
            this.maxAge = maxAge;
            this.spawnTick = spawnTick;
        }
    }
}
