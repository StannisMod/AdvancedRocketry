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
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

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
    /** Sky-frame scale the label text is drawn at, so it reads at the billboard's distance. */
    private static final float LABEL_SCALE = 0.28F;

    private static final float STAR_ALPHA = 0.9F;

    /**
     * How many body labels the last frame actually drew. A counter rather than a flag: the contract
     * is that the toggle removes the label ENTIRELY, and "zero drawn while bodies were fed" is the
     * only reading of that a test can take without looking at pixels. Client-side diagnostic state;
     * nothing in the render path branches on it.
     */
    public static volatile int labelsDrawnLastFrame;

    /**
     * Frames on which the descent-boundary ring has been drawn. It exists so "the ring is suppressed
     * in hyperspace" is a statement a test can falsify: a counter that only ever goes up cannot tell
     * a suppressed ring from a sky renderer that stopped running altogether, so the corridor's own
     * counter is read in the same breath and the pair has to move in opposite directions.
     */
    public static volatile long ringFramesDrawn = 0L;

    /**
     * Frames on which this sky renderer ran AT ALL, counted before any branch inside it.
     *
     * <p>Without it the ring counter answers two different questions with the same zero: "the ring
     * was suppressed" and "nothing rendered here". The first control leg written against that pair
     * could not tell them apart, and said so by failing on its own arrangement.</p>
     */
    public static volatile long skyFramesDrawn = 0L;

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
        skyFramesDrawn++;
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

        // In hyperspace this same provider serves the transit lanes, and the two things below are
        // both wrong there: the ring marks a descent boundary in a world nothing descends to, and
        // no cell is loaded so no body is ever synced. The corridor replaces them, and it is the
        // only thing that tells a pilot with no controls and no readout that he is moving.
        //
        // The gate is the WORLD this frame is drawn in — the same primary fact the server derives
        // the jump phase from — and not the seat the viewer happens to be on. Keyed on the seat, a
        // crew member who stood up mid-flight got a cell's descent ring in the transit corridor and
        // no corridor at all, which reads as the flight having stopped.
        if (HyperspaceTunnel.isHyperspace(world)) {
            HyperspaceTunnel.render(partialTicks, world);
            GlStateManager.enableTexture2D();
            restoreState();
            return;
        }

        // Descent boundary ring (untextured colour band).
        GlStateManager.color(0.35F, 0.65F, 1.0F, 0.35F);
        GL11.glCallList(this.glBoundaryList);
        ringFramesDrawn++;
        GlStateManager.enableTexture2D();

        // One billboard per synced body.
        int labelled = 0;
        if (bodies != null && !bodies.isEmpty()) {
            BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            boolean labels = SkyLabels.enabled();
            for (PacketSystemBodiesSync.RenderBody body : bodies) {
                labelled += drawBody(buffer, body, labels) ? 1 : 0;
            }
        }
        labelsDrawnLastFrame = labelled;

        restoreState();
    }

    /**
     * Restore a sane GL state for the rest of the world render.
     *
     * <p>One copy, because both exits of {@link #render} take it and a second copy is how the two
     * drift apart: the hyperspace path leaving blend enabled would tint every block drawn after it.
     */
    private static void restoreState() {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableFog();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /** Draw one body; returns whether a label was written for it. */
    private boolean drawBody(BufferBuilder buffer, PacketSystemBodiesSync.RenderBody body,
                             boolean labels) {
        double dx = body.localX;
        double dy = body.localY;
        double dz = body.localZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-6D)
            return false;

        float nx = (float) (dx / len);
        float ny = (float) (dy / len);
        float nz = (float) (dz / len);

        float yaw = (float) Math.toDegrees(Math.atan2(nx, nz));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1.0F, Math.min(1.0F, ny))));
        // The vector's LENGTH is the true distance to the body at the broadcast tick, so apparent
        // size follows it. A fixed size made a moon at 3 km and one at 59 km indistinguishable, and
        // left "the planet is crawling away" a thing the sky could not show at all.
        float half = ApparentSize.halfSizeFor(len);

        // The STRICT dimension lookup: the lenient one answers an unknown dimension with the
        // OVERWORLD's properties, so the star -- which has no dimension of its own -- was drawn
        // wearing Earth's texture. A body with nothing to bind is drawn as a tinted quad instead,
        // which is honest.
        DimensionProperties props =
                DimensionManager.getInstance().getDimensionPropertiesOrNull(body.dimId);
        ResourceLocation icon = props != null ? props.getPlanetIcon() : null;
        if (icon != null)
            mc.renderEngine.bindTexture(icon);
        else
            GlStateManager.disableTexture2D();

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

        if (icon == null)
            GlStateManager.enableTexture2D();

        boolean drewLabel = labels && drawLabel(body, half, len);
        GlStateManager.popMatrix();
        return drewLabel;
    }

    /**
     * Write the body's name and its current distance under the billboard, inside the already-rotated
     * body frame so the text faces the camera exactly as the quad does.
     */
    private boolean drawLabel(PacketSystemBodiesSync.RenderBody body, float half, double distance) {
        if (mc.fontRenderer == null)
            return false;
        String text = nameOf(body) + "  " + ApparentSize.formatDistance(distance);
        GlStateManager.pushMatrix();
        // The sky frame's +Y is up while the font renders DOWN its own +Y, so both axes are negated
        // here; without it every label reads upside down and mirrored.
        GlStateManager.translate(0.0F, -half - 2.0F, 0.0F);
        GlStateManager.scale(-LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.fontRenderer.drawString(text, -mc.fontRenderer.getStringWidth(text) / 2, 0,
                0xFFFFFFFF, false);
        GlStateManager.popMatrix();
        // The font renderer leaves its own texture and colour bound. The next body binds its own
        // texture, so only the colour has to be put back.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        return true;
    }

    /** What to call this body: its dimension's name, else its star's, else its kind. */
    private static String nameOf(PacketSystemBodiesSync.RenderBody body) {
        DimensionProperties props =
                DimensionManager.getInstance().getDimensionPropertiesOrNull(body.dimId);
        if (props != null && props.getName() != null && !props.getName().isEmpty())
            return props.getName();
        if (body.dimId >= Constants.STAR_ID_OFFSET) {
            StellarBody star = DimensionManager.getInstance()
                    .getStar(body.dimId - Constants.STAR_ID_OFFSET);
            if (star != null && star.getName() != null && !star.getName().isEmpty())
                return star.getName();
        }
        SystemBodyKind[] kinds = SystemBodyKind.values();
        return body.kindOrdinal >= 0 && body.kindOrdinal < kinds.length
                ? kinds[body.kindOrdinal].name() : "?";
    }
}
