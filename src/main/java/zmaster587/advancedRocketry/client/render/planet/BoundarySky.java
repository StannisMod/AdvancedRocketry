package zmaster587.advancedRocketry.client.render.planet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.IRenderHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync;

import java.util.List;

/**
 * Slot-world sky renderer for a settled tier-2 ship: draws the descent boundary ring plus a
 * billboard for every nearby system body the server has synced for THIS slot dimension.
 *
 * <p>The body data comes from {@link PacketSystemBodiesSync#bodiesForDim(int)} (the shared
 * server-&gt;client render channel), keyed on {@code world.provider.getDimension()}. Bodies flagged
 * {@link PacketSystemBodiesSync.RenderBody#descendTarget} are highlighted so the pilot can see which
 * body the ship will descend into once inside its proximity radius.</p>
 *
 * <p>Appearance is playtest-verified; this class only needs to be structurally correct. The static
 * boundary ring is baked into a display list in the constructor -- it is never rebuilt per frame.
 * Body billboards are cheap (a handful of quads) and are streamed inline, exactly as
 * {@link RenderSpaceSky} streams its planet quads.</p>
 */
@SideOnly(Side.CLIENT)
public class BoundarySky extends IRenderHandler {

    // Render tunables (appearance-only; never pinned by a test).
    private static final float BOUNDARY_RADIUS = 100.0F;
    private static final float BOUNDARY_HEIGHT = 6.0F;
    private static final int BOUNDARY_SEGMENTS = 48;
    private static final float BODY_DISTANCE = 90.0F;
    private static final float BODY_HALF_SIZE = 6.0F;
    private static final float TARGET_HALF_SIZE = 10.0F;

    private final Minecraft mc = Minecraft.getMinecraft();

    // Cached static geometry: the descent boundary ring (position-only; colour set at call time).
    private final int glBoundaryList;

    public BoundarySky() {
        this.glBoundaryList = GLAllocation.generateDisplayLists(1);

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        GL11.glNewList(this.glBoundaryList, GL11.GL_COMPILE);
        buffer.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION);
        for (int i = 0; i <= BOUNDARY_SEGMENTS; i++) {
            double ang = (Math.PI * 2.0D * i) / BOUNDARY_SEGMENTS;
            float x = (float) (Math.cos(ang) * BOUNDARY_RADIUS);
            float z = (float) (Math.sin(ang) * BOUNDARY_RADIUS);
            buffer.pos(x, -BOUNDARY_HEIGHT, z).endVertex();
            buffer.pos(x, BOUNDARY_HEIGHT, z).endVertex();
        }
        Tessellator.getInstance().draw();
        GL11.glEndList();
    }

    @Override
    public void render(float partialTicks, WorldClient world, Minecraft mc) {
        List<PacketSystemBodiesSync.RenderBody> bodies =
                PacketSystemBodiesSync.bodiesForDim(world.provider.getDimension());

        GlStateManager.pushMatrix();
        GlStateManager.disableFog();
        GlStateManager.disableAlpha();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

        // Descent boundary ring (untextured colour band).
        GlStateManager.disableTexture2D();
        GlStateManager.color(0.35F, 0.65F, 1.0F, 0.35F);
        GL11.glCallList(this.glBoundaryList);
        GlStateManager.enableTexture2D();

        // One billboard per synced body.
        if (bodies != null && !bodies.isEmpty()) {
            BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            for (PacketSystemBodiesSync.RenderBody body : bodies) {
                drawBody(buffer, body);
            }
        }

        // Restore a sane GL state for the rest of the world render.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableFog();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private void drawBody(BufferBuilder buffer, PacketSystemBodiesSync.RenderBody body) {
        double dx = body.localX;
        double dy = body.localY;
        double dz = body.localZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-6D)
            return;

        float nx = (float) (dx / len);
        float ny = (float) (dy / len);
        float nz = (float) (dz / len);

        float yaw = (float) Math.toDegrees(Math.atan2(nx, nz));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1.0F, Math.min(1.0F, ny))));
        float half = body.descendTarget ? TARGET_HALF_SIZE : BODY_HALF_SIZE;

        // Bind the body's already-synced planet texture; a minimal colour tint is the v1 fallback.
        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(body.dimId);
        ResourceLocation icon = props != null ? props.getPlanetIcon() : null;
        if (icon != null)
            mc.renderEngine.bindTexture(icon);

        if (body.descendTarget)
            GlStateManager.color(0.6F, 1.0F, 0.6F, 1.0F);
        else
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GlStateManager.pushMatrix();
        GlStateManager.rotate(yaw, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-pitch, 1.0F, 0.0F, 0.0F);
        GlStateManager.translate(0.0F, 0.0F, BODY_DISTANCE);

        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(-half, -half, 0.0D).tex(0.0D, 1.0D).endVertex();
        buffer.pos(half, -half, 0.0D).tex(1.0D, 1.0D).endVertex();
        buffer.pos(half, half, 0.0D).tex(1.0D, 0.0D).endVertex();
        buffer.pos(-half, half, 0.0D).tex(0.0D, 0.0D).endVertex();
        Tessellator.getInstance().draw();

        GlStateManager.popMatrix();
    }
}
