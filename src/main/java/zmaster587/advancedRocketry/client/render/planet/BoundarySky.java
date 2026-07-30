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
 * <p>This provider replaces the ENTIRE sky rather than adding to one, so whatever is not drawn here is
 * not drawn at all -- hence the starfield alongside the ring and the billboards.</p>
 *
 * <p>Everything emitted here is wound to face the camera and drawn with vanilla's back-face culling
 * left on, matching {@link RenderPlanetarySky}. That is a hard requirement, not a style choice: the sky
 * pass runs immediately after {@code EntityRenderer.renderWorldPass} enables {@code GL_CULL_FACE} with
 * {@code GL_BACK}, so a primitive wound the other way is silently discarded and the pilot sees an empty
 * sky with no error anywhere. The static geometry is baked into display lists in the constructor -- it
 * is never rebuilt per frame.
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

    private static final float STAR_ALPHA = 0.9F;

    private final Minecraft mc = Minecraft.getMinecraft();

    // Cached static geometry: the descent boundary ring (position-only; colour set at call time).
    private final int glBoundaryList;
    // Cached static geometry: the shared starfield, so empty space is not a black void.
    private final int glStarList;

    public BoundarySky() {
        int lists = GLAllocation.generateDisplayLists(2);
        this.glBoundaryList = lists;
        this.glStarList = lists + 1;

        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        GL11.glNewList(this.glBoundaryList, GL11.GL_COMPILE);
        buffer.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION);
        for (int i = 0; i <= BOUNDARY_SEGMENTS; i++) {
            double ang = (Math.PI * 2.0D * i) / BOUNDARY_SEGMENTS;
            float x = (float) (Math.cos(ang) * BOUNDARY_RADIUS);
            float z = (float) (Math.sin(ang) * BOUNDARY_RADIUS);
            // TOP vertex before BOTTOM. The strip advances anticlockwise around +Y, so this pairing is
            // what makes each quad wind anticlockwise -- i.e. front-facing -- as seen from INSIDE the
            // cylinder. The camera is always inside it: the ring is drawn in the camera-centred sky
            // frame, at a radius no viewpoint can leave. Emitting bottom-first faces the ring outwards
            // and vanilla's GL_CULL_FACE/GL_BACK (enabled in EntityRenderer.renderWorldPass just before
            // the sky pass, and never turned off here) discards every quad of it.
            buffer.pos(x, BOUNDARY_HEIGHT, z).endVertex();
            buffer.pos(x, -BOUNDARY_HEIGHT, z).endVertex();
        }
        Tessellator.getInstance().draw();
        GL11.glEndList();

        // The starfield is the mod's existing one, compiled into a list of our own rather than
        // duplicated: same seed, same 2000 quads, same radius as every other AR sky. Without it a slot
        // cell has no sky at all -- this provider replaces the whole sky renderer rather than adding to
        // it, so nothing else here draws stars or a sun.
        GL11.glNewList(this.glStarList, GL11.GL_COMPILE);
        RenderPlanetarySky.emitBaselineStars();
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

        GlStateManager.disableTexture2D();

        // Stars first: the ring and the billboards are meant to sit in front of them.
        GlStateManager.color(1.0F, 1.0F, 1.0F, STAR_ALPHA);
        GL11.glCallList(this.glStarList);

        // Descent boundary ring (untextured colour band).
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

        // The billboard is pushed out to +Z and must face back down -Z, at the camera. The four corners
        // and their UVs are unchanged; only the traversal order is reversed, which flips the winding
        // without touching the texture mapping. Emitting them the other way round points the normal
        // along +Z, away from the viewer, and vanilla's back-face culling drops the quad -- the same
        // defect the ring had. RenderPlanetarySky.renderPlanetPubHelper draws its planet quads
        // viewer-facing with culling on for exactly this reason; this now matches it.
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(-half, half, 0.0D).tex(0.0D, 0.0D).endVertex();
        buffer.pos(half, half, 0.0D).tex(1.0D, 0.0D).endVertex();
        buffer.pos(half, -half, 0.0D).tex(1.0D, 1.0D).endVertex();
        buffer.pos(-half, -half, 0.0D).tex(0.0D, 1.0D).endVertex();
        Tessellator.getInstance().draw();

        GlStateManager.popMatrix();
    }
}
