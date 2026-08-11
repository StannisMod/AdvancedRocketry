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
import zmaster587.advancedRocketry.space.HyperspaceWorld;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

import java.util.List;

/**
 * Slot-world sky renderer for a settled tier-2 ship: draws a billboard for every nearby system body
 * the server has synced for THIS slot dimension.
 *
 * <p><b>There is no fixed horizon band here, and there must not be one.</b> This renderer used to
 * draw a "descent boundary ring" at a constant radius in the CAMERA's frame — a band the viewpoint
 * could never leave, identical at ten blocks from a planet and at ten million, with no coupling to
 * the descent radius it was named after. It asserted "a descent boundary is here" in every cell,
 * including cells with nothing to descend to. A boundary belongs to a BODY: it is drawn around the
 * body's own bearing, at the angle that body's shell actually subtends.</p>
 *
 * <p>The body data comes from {@link PacketSystemBodiesSync#bodiesForDim(int)} (the shared
 * server-&gt;client render channel), keyed on {@code world.provider.getDimension()}. Bodies flagged
 * {@link PacketSystemBodiesSync.RenderBody#descendTarget} are highlighted so the pilot can see which
 * body the ship will descend into once inside its proximity radius.</p>
 *
 * <p>This provider replaces the ENTIRE sky rather than adding to one, so whatever is not drawn here is
 * not drawn at all -- hence the starfield alongside the billboards.</p>
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
    private static final float BODY_DISTANCE = 90.0F;
    /** Sky-frame radius the boundary circle is emitted on. Inside the starfield, around the body. */
    private static final float BOUNDARY_SKY_RADIUS = 95.0F;
    /** Samples around one boundary circle. Enough that a great circle does not read as a polygon. */
    private static final int BOUNDARY_SEGMENTS = 64;
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
     * How many atmosphere boundaries the client's LAST FRAME drew. Same shape as the label counter
     * and for the same reason: "a boundary is drawn for a descend target and for nothing else" is
     * a claim a test can only take without looking at pixels if the renderer counts what it drew.
     *
     * <p>Read it beside {@link #skyFramesDrawn}, never alone — a zero here means "no boundary was
     * drawn" only if the renderer ran at all, and the two are separate questions.</p>
     */
    public static volatile int boundariesDrawnLastFrame;

    /**
     * Frames on which this sky renderer ran AT ALL, counted before any branch inside it.
     *
     * <p>It is what makes "X was not drawn" falsifiable: a per-feature counter that stays at zero
     * cannot tell "the feature was suppressed" from "nothing rendered here", and the first control
     * leg written without this pair could not tell them apart and said so by failing on its own
     * arrangement.</p>
     */
    public static volatile long skyFramesDrawn = 0L;

    private final Minecraft mc = Minecraft.getMinecraft();

    // Cached static geometry: the shared starfield, so empty space is not a black void.
    private final int glStarList;

    public BoundarySky() {
        this.glStarList = GLAllocation.generateDisplayLists(1);

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

        // Stars first: the billboards are meant to sit in front of them.
        GlStateManager.color(1.0F, 1.0F, 1.0F, STAR_ALPHA);
        GL11.glCallList(this.glStarList);

        // In hyperspace this same provider serves the transit lanes, and nothing below belongs
        // there: no cell is loaded, so no body is ever synced. The corridor replaces them, and it
        // is the only thing that tells a pilot with no controls and no readout that he is moving.
        //
        // Gated on the WORLD, which is the primary fact and true of everyone in it. The gate used to
        // be the jump phase published on the seat entity, so it answered 0 for anybody riding
        // nothing: a crew member who stood up mid-flight lost the corridor, and hyperspace has
        // nothing else in its sky, so the sky went empty and still. Being aboard is not the same as
        // sitting down, and the backdrop of a world is not a property of the chair.
        if (HyperspaceWorld.isHyperspaceOnClient(world.provider.getDimension())) {
            HyperspaceTunnel.render(partialTicks, world);
            GlStateManager.enableTexture2D();
            restoreState();
            return;
        }

        // The atmosphere boundaries FIRST, untextured, while the texture unit is still off: they
        // belong behind the bodies they surround, and drawing them here saves toggling the texture
        // unit twice per frame.
        int boundaries = 0;
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        if (bodies != null) {
            GlStateManager.color(0.35F, 0.65F, 1.0F, 0.55F);
            for (PacketSystemBodiesSync.RenderBody body : bodies) {
                boundaries += drawBoundary(buffer, body) ? 1 : 0;
            }
        }
        boundariesDrawnLastFrame = boundaries;

        GlStateManager.enableTexture2D();

        // One billboard per synced body.
        int labelled = 0;
        if (bodies != null && !bodies.isEmpty()) {
            boolean labels = SkyLabels.enabled();
            for (PacketSystemBodiesSync.RenderBody body : bodies) {
                labelled += drawBody(buffer, body, labels) ? 1 : 0;
            }
        }
        labelsDrawnLastFrame = labelled;

        restoreState();
    }

    /**
     * Draw {@code body}'s atmosphere boundary: the circle on the sky where its shell meets the
     * viewer's line of sight. Returns whether anything was emitted.
     *
     * <p><b>Why a circle around the body and not a band around the camera.</b> The boundary is a
     * sphere of radius R about the body, so from a distance d it subtends a half-angle
     * {@code asin(R/d)} about the body's own bearing. That makes it a thing in the world: it OPENS
     * as the ship closes, and at the crossing it is a great circle — the boundary is all around
     * you, because you are on it. A fixed band at the camera's horizon can express none of that; it
     * is identical at every distance, which is why the one this replaced said nothing.</p>
     *
     * <p><b>No singularity, deliberately.</b> Points are sampled ON the sky sphere as
     * {@code cosθ·n + sinθ·(cosφ·u + sinφ·v)} rather than projected onto the billboard plane, where
     * the radius would go as {@code tan θ} and blow up exactly at the crossing. The ratio is
     * clamped at 1, so at or inside the shell θ is a right angle and the great circle is the honest
     * limit rather than a NaN.</p>
     *
     * <p>Emitted as a LINE LOOP: the sky pass runs with {@code GL_CULL_FACE}/{@code GL_BACK} on
     * (see the class note), and a filled band would have to be wound correctly for a viewpoint that
     * moves through it — the failure mode being silent invisibility. A line has no facing.</p>
     */
    private boolean drawBoundary(BufferBuilder buffer, PacketSystemBodiesSync.RenderBody body) {
        // A body with no shell has no boundary to draw. Read off the number the SERVER sent rather
        // than off the kind, so the renderer never has to know which kinds have one.
        if (!body.descendTarget || body.boundaryRadius <= 0L)
            return false;

        double dx = body.localX;
        double dy = body.localY;
        double dz = body.localZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-6D)
            return false;

        double nx = dx / len, ny = dy / len, nz = dz / len;
        // The angle comes from the same place the range beside the body does, so the circle and the
        // number can never describe two different surfaces.
        double theta = zmaster587.advancedRocketry.space.DescentShell
                .boundaryHalfAngle(len, body.boundaryRadius);
        double ct = Math.cos(theta), st = Math.sin(theta);

        // Any axis not parallel to n spans the perpendicular plane with it. Take the world axis n is
        // LEAST aligned with: a fixed choice degenerates to a zero-length cross product for a body
        // that happens to lie along it, and that body is precisely the one dead ahead.
        double hx = 0.0D, hy = 0.0D, hz = 0.0D;
        double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ax <= ay && ax <= az) {
            hx = 1.0D;
        } else if (ay <= az) {
            hy = 1.0D;
        } else {
            hz = 1.0D;
        }
        double ux = ny * hz - nz * hy, uy = nz * hx - nx * hz, uz = nx * hy - ny * hx;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (ul < 1.0E-9D)
            return false;
        ux /= ul; uy /= ul; uz /= ul;
        double vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;

        buffer.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION);
        for (int i = 0; i < BOUNDARY_SEGMENTS; i++) {
            double phi = (Math.PI * 2.0D * i) / BOUNDARY_SEGMENTS;
            double cp = Math.cos(phi), sp = Math.sin(phi);
            buffer.pos((ct * nx + st * (cp * ux + sp * vx)) * BOUNDARY_SKY_RADIUS,
                    (ct * ny + st * (cp * uy + sp * vy)) * BOUNDARY_SKY_RADIUS,
                    (ct * nz + st * (cp * uz + sp * vz)) * BOUNDARY_SKY_RADIUS).endVertex();
        }
        Tessellator.getInstance().draw();
        return true;
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
     * Write the body's name and the range still to fly under the billboard, inside the
     * already-rotated body frame so the text faces the camera exactly as the quad does.
     *
     * <p><b>The number is the distance to the body's ATMOSPHERE, not to the body.</b> Crossing that
     * surface is what puts the ship on the planet, so it is the only range a pilot on approach can
     * act on: a body-centre range tells him to cover a distance he does not have to cover, and
     * still reads a whole shell's worth of blocks at the very instant he crosses. A body with no
     * shell to cross — a star, anything not a descend target — carries a zero radius and is
     * therefore labelled with its plain distance by the same arithmetic, with no special case
     * here.</p>
     *
     * <p>The billboard's SIZE keeps using the distance to the body itself
     * ({@link ApparentSize#halfSizeFor}, at the call site): how big a thing looks is a property of
     * the thing, how far there is left to fly is a property of the approach. They are easy to
     * conflate because both are "distance to that body", and conflating them would shrink a planet
     * to nothing exactly as you arrive.</p>
     */
    private boolean drawLabel(PacketSystemBodiesSync.RenderBody body, float half, double distance) {
        if (mc.fontRenderer == null)
            return false;
        String text = nameOf(body) + "  "
                + ApparentSize.formatDistance(
                        zmaster587.advancedRocketry.space.DescentShell.distanceToShell(
                                distance, body.boundaryRadius));
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
