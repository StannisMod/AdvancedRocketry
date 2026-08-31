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
import zmaster587.advancedRocketry.client.ClientBeamTracker;

/**
 * Draws the beams the client has been told are burning.
 *
 * <h3>A ribbon that faces you, not a line</h3>
 * <p>A beam is the one weapon a player is supposed to watch rather than glimpse, so it is drawn with
 * width: two billboarded ribbons about the same axis — a wide dim halo and a narrow white-hot core —
 * plus a spot where it lands. A one-pixel {@code GL_LINES} streak is right for a tracer, which is
 * gone in a tick; held for seconds it reads as a scratch on the screen rather than as power going
 * somewhere.</p>
 *
 * <p>The ribbon is turned to face the camera each frame: the beam has an axis but no natural "up",
 * so its width is taken across the axis and the view direction, which is what keeps it from
 * vanishing when looked at edge-on.</p>
 */
@SideOnly(Side.CLIENT)
public class RenderBeams {

    /** Half-width of the white-hot core, in blocks. */
    private static final double CORE_HALF_WIDTH = 0.045D;

    /** Half-width of the surrounding glow. */
    private static final double HALO_HALF_WIDTH = 0.16D;

    /** Half-size of the spot drawn where the beam lands. */
    private static final double SPOT_HALF_SIZE = 0.45D;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.isGamePaused()) {
            return;
        }
        ClientBeamTracker.tick();
    }

    /** Leaving a world drops every drawing: a beam from the last dimension has no business here. */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() != null && event.getWorld().isRemote) {
            ClientBeamTracker.clear();
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (ClientBeamTracker.count() == 0) {
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
        Vec3d eye = new Vec3d(eyeX, eyeY, eyeZ);

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.depthMask(false);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        for (ClientBeamTracker.ClientBeam beam : ClientBeamTracker.burning()) {
            // A beam is a PATH: one leg for the ordinary one, more where a mirror turned it. Each
            // leg is a ribbon of its own because each faces the camera differently, and only the
            // LAST one ends in a spot — the corners are places the beam went on from, not places it
            // landed, and a glow at a corner would read as a hit that never happened.
            java.util.List<Vec3d> path = beam.getPath();
            for (int leg = 0; leg + 1 < path.size(); leg++) {
                Vec3d from = path.get(leg);
                Vec3d to = path.get(leg + 1);
                if (from == null || to == null) {
                    continue;
                }
                Vec3d axis = to.subtract(from);
                if (axis.lengthVector() < 1.0E-6D) {
                    continue;
                }
                axis = axis.normalize();
                Vec3d across = across(axis, from, to, eye);
                if (across == null) {
                    continue;
                }
                ribbon(buffer, from, to, across.scale(HALO_HALF_WIDTH), eye,
                        1.0F, 0.32F, 0.16F, 0.35F);
                ribbon(buffer, from, to, across.scale(CORE_HALF_WIDTH), eye,
                        1.0F, 0.93F, 0.85F, 1.0F);
                if (leg + 2 == path.size()) {
                    spot(buffer, to, axis, eye);
                }
            }
        }

        tessellator.draw();

        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /**
     * The direction the ribbon's width runs in: across both the beam and the line of sight to it, so
     * the flat side always points at the camera. Null when the beam is aimed straight at the eye —
     * there is no "across" then, and a beam pointed at your face is a dot rather than a ribbon.
     */
    private static Vec3d across(Vec3d axis, Vec3d from, Vec3d to, Vec3d eye) {
        Vec3d midpoint = from.add(to).scale(0.5D);
        Vec3d toEye = eye.subtract(midpoint);
        if (toEye.lengthVector() < 1.0E-6D) {
            return null;
        }
        Vec3d cross = axis.crossProduct(toEye.normalize());
        return cross.lengthVector() < 1.0E-6D ? null : cross.normalize();
    }

    private static void ribbon(BufferBuilder buffer, Vec3d from, Vec3d to, Vec3d halfWidth, Vec3d eye,
                               float r, float g, float b, float alpha) {
        vertex(buffer, from.subtract(halfWidth), eye, r, g, b, alpha);
        vertex(buffer, to.subtract(halfWidth), eye, r, g, b, alpha);
        vertex(buffer, to.add(halfWidth), eye, r, g, b, alpha);
        vertex(buffer, from.add(halfWidth), eye, r, g, b, alpha);
    }

    /** The glow where the beam lands, drawn square to the beam so it reads as a burning spot. */
    private static void spot(BufferBuilder buffer, Vec3d at, Vec3d axis, Vec3d eye) {
        Vec3d any = Math.abs(axis.y) > 0.9D ? new Vec3d(1.0D, 0.0D, 0.0D) : new Vec3d(0.0D, 1.0D, 0.0D);
        Vec3d u = axis.crossProduct(any).normalize().scale(SPOT_HALF_SIZE);
        Vec3d v = axis.crossProduct(u).normalize().scale(SPOT_HALF_SIZE);
        // Lifted off the surface it is burning into, or it fights the block face for the same pixels.
        Vec3d centre = at.subtract(axis.scale(0.02D));
        vertex(buffer, centre.subtract(u).subtract(v), eye, 1.0F, 0.75F, 0.35F, 0.55F);
        vertex(buffer, centre.add(u).subtract(v), eye, 1.0F, 0.75F, 0.35F, 0.55F);
        vertex(buffer, centre.add(u).add(v), eye, 1.0F, 0.75F, 0.35F, 0.55F);
        vertex(buffer, centre.subtract(u).add(v), eye, 1.0F, 0.75F, 0.35F, 0.55F);
    }

    private static void vertex(BufferBuilder buffer, Vec3d point, Vec3d eye,
                               float r, float g, float b, float alpha) {
        buffer.pos(point.x - eye.x, point.y - eye.y, point.z - eye.z).color(r, g, b, alpha).endVertex();
    }
}
