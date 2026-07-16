package zmaster587.advancedRocketry.event;
// This code does not work - it should display the earth below rockets at start but it does not.
// The detailed map is scaled too small and it is ugly even with correct scale
// maybe just use leo as earth? 


import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.IRenderHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.armor.IFillableArmor;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.advancedRocketry.client.FreeFlightHudState;
import zmaster587.advancedRocketry.client.KeyBindings;
import zmaster587.advancedRocketry.client.render.ClientDynamicTexture;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.tile.TilePilotSeat;
import zmaster587.advancedRocketry.util.ItemAirUtils;
import zmaster587.libVulpes.api.IArmorComponent;
import zmaster587.libVulpes.api.IModularArmor;
import zmaster587.libVulpes.client.ResourceIcon;
import zmaster587.libVulpes.render.RenderHelper;

import javax.annotation.Nonnull;
import java.nio.IntBuffer;
import java.util.List;

public class RocketEventHandler extends Gui {


    private static final int numTicksToDisplay = 100;
    public static GuiBox suitPanel = new GuiBox(8, 8, 24, 24);
    public static GuiBox oxygenBar = new GuiBox(8, -57, 80, 48);
    public static GuiBox hydrogenBar = new GuiBox(8, -74, 80, 48);
    public static GuiBox atmBar = new GuiBox(8, 27, 200, 48);
    private static String displayString = "";
    private static long lastDisplayTime = -1000;
    /** Last rendered Free Flight HUD text (joined with " | "), for client e2e
     *  assertions. Empty when not riding a FF rocket. Updated each HUD frame. */
    public static volatile String lastFreeFlightHud = "";
    /** Frame-time camera-lock telemetry: worst divergence (deg)
     *  between the player camera and the craft axes seen on any rendered HUD
     *  frame of the current FF flight — i.e. what the pilot literally saw,
     *  sampled atomically on the render thread. Bounded small while flying
     *  (intra-tick mouse deflection only); a runaway means the lock broke.
     *  Reset when the flight ends. Read reflectively by client e2e. */
    public static volatile double maxCameraLockErrorDeg = 0.0;
    /** Same divergence for the MOST RECENT rendered frame (not the running
     *  max) — at rest this is what the pilot currently sees, readable in one
     *  atomic reflective call (a bot reading camera and craft separately can
     *  straddle a tracker-quantisation bleed tick and see a phantom gap). */
    public static volatile double lastCameraLockErrorDeg = 0.0;
    /** Client-rendered FF attitude readback, sampled on the
     *  render thread from the interpolated attitude quaternion the camera used —
     *  the pilot's actual view. For perception-contract client e2e:
     *  {@link #ffClientCamRoll} pins mouse-horizontal → bank; {@link #ffClientMinForwardZ}
     *  (most-negative nose Z over the flight) pins a pitch LOOP past vertical with
     *  no ±85° clamp (a clamped nose can never point backwards → Z stays ≳ 0).
     *  Reset when the flight ends. */
    public static volatile double ffClientCamPitch = 0.0;
    public static volatile double ffClientCamRoll  = 0.0;
    public static volatile double ffClientForwardZ = 1.0;
    public static volatile double ffClientMinForwardZ = 1.0;
    /** Frame counter that throttles the [FF-TRACE/CAM] deck-walking camera probe (test mode only). */
    private static int ffCamTraceFrames = 0;
    private ResourceLocation background = TextureResources.rocketHud;
    private static long suppressSuffocationWarningUntil = Long.MIN_VALUE;
    private static int lastSuffocationWarningDim = Integer.MIN_VALUE;


    /** [-1,1] clamp for HUD bar/dot geometry; NaN-safe. */
    private static double clampUnit(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(-1.0, Math.min(1.0, v));
    }

    /**
     * Render the backend-agnostic Free Flight HUD from a {@link FreeFlightHudState} snapshot: the
     * text legend (bottom-left), and while flying the velocity bars (only when the backend supplies
     * velocity — the tier-2 ship omits them), the turn-rate dot, and the centre flight cursor.
     * The same code serves a tier-1 rocket and a tier-2 ship.
     */
    private static void renderFreeFlightHud(RenderGameOverlayEvent.Post event, Minecraft mc,
                                            FreeFlightHudState state) {
        FontRenderer fr = mc.fontRenderer;
        List<String> ffLines = KeyBindings.freeFlightHudLines(state);
        // Expose the rendered text for client-side e2e assertions (read reflectively by the bridge).
        lastFreeFlightHud = String.join(" | ", ffLines);
        int lineH = fr.FONT_HEIGHT + 1;
        int scaledH2 = event.getResolution().getScaledHeight();
        // Bottom-left, to the right of the instrument panel, stacked up.
        int ffX = 22;
        int ffY = scaledH2 - 4 - ffLines.size() * lineH;
        for (int i = 0; i < ffLines.size(); i++) {
            // Title/indicator line brighter; legend lines in FF cyan.
            int color = (i == 0) ? 0x66FFE0 : 0xB0F0FF;
            fr.drawStringWithShadow(ffLines.get(i), ffX, ffY + i * lineH, color);
        }
        if (!state.inFlight) {
            return;
        }

        int barX = ffX + 150, barW = 60, barH = 4;
        int barsTop = scaledH2 - 4 - 3 * (barH + 3) - 22;
        // Graphic thrust/velocity bars. Per body axis: a bipolar bar scaled to the craft's own top
        // speed — cyan fill = actual velocity, notch = FA setpoint.
        if (state.hasVelocity) {
            double[] act = {state.bodyForward, state.bodyRight, state.bodyUp};
            double[] sp = {state.faForward, state.faRight, state.faUp};
            String[] axis = {"FWD", "LAT", "VRT"};
            double max = state.barScale;
            for (int i = 0; i < 3; i++) {
                int y = barsTop + i * (barH + 3);
                fr.drawStringWithShadow(axis[i], barX - 22, y - 2, 0xB0F0FF);
                drawRect(barX, y, barX + barW, y + barH, 0xA0202830);
                int mid = barX + barW / 2;
                drawRect(mid, y - 1, mid + 1, y + barH + 1, 0xFF607078);
                int actPx = (int) (clampUnit(act[i] / max) * (barW / 2.0));
                if (actPx >= 0) drawRect(mid, y, mid + Math.max(actPx, 0) + 1, y + barH, 0xFF40D0FF);
                else            drawRect(mid + actPx, y, mid + 1, y + barH, 0xFF40D0FF);
                if (state.flightAssistOn) {
                    int spPx = mid + (int) (clampUnit(sp[i] / max) * (barW / 2.0));
                    drawRect(spPx - 1, y - 1, spPx + 1, y + barH + 1, 0xFFFFE060);
                }
            }
        }

        // Turn-rate dot: deflection from center = commanded yaw (x) / pitch (y). Both backends
        // publish these to the shared KeyBindings fields.
        int boxC = barX + barW + 18, boxR = 8;
        int boxYc = barsTop + (3 * (barH + 3)) / 2;
        drawRect(boxC - boxR, boxYc - boxR, boxC + boxR, boxYc + boxR, 0xA0202830);
        drawRect(boxC - boxR, boxYc, boxC + boxR, boxYc + 1, 0xFF607078);
        drawRect(boxC, boxYc - boxR, boxC + 1, boxYc + boxR, 0xFF607078);
        int dx = (int) (clampUnit(KeyBindings.hudYawRate)   * (boxR - 2));
        int dy = (int) (clampUnit(KeyBindings.hudPitchRate) * (boxR - 2));
        drawRect(boxC + dx - 1, boxYc + dy - 1, boxC + dx + 2, boxYc + dy + 2, 0xFF40D0FF);

        // Elite-style flight cursor at screen centre: a square deflection zone with a dot at the
        // current (roll = X, pitch = Y) deflection. Absolute — stays where the mouse leaves it.
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int ccx = sr.getScaledWidth() / 2, ccy = sr.getScaledHeight() / 2;
        int zone = 40;
        drawRect(ccx - zone, ccy - zone, ccx + zone, ccy - zone + 1, 0x50FFFFFF);
        drawRect(ccx - zone, ccy + zone, ccx + zone, ccy + zone + 1, 0x50FFFFFF);
        drawRect(ccx - zone, ccy - zone, ccx - zone + 1, ccy + zone, 0x50FFFFFF);
        drawRect(ccx + zone, ccy - zone, ccx + zone + 1, ccy + zone, 0x50FFFFFF);
        float pt = event.getPartialTicks();
        int fcx = (int) (clampUnit(KeyBindings.flightCursorX(pt)) * zone);
        int fcy = (int) (clampUnit(KeyBindings.flightCursorY(pt)) * zone);
        drawRect(ccx + fcx - 2, ccy + fcy - 2, ccx + fcx + 3, ccy + fcy + 3, 0xFFFFE060);
    }

    @SideOnly(Side.CLIENT)
    public static void setOverlay(long endTime, String msg) {
        displayString = msg;
        lastDisplayTime = endTime;
    }

    @SubscribeEvent
    public void playerTeleportEvent(PlayerEvent.PlayerChangedDimensionEvent event) {
        //Fix O2, space elevator popup displaying after teleporting
        lastDisplayTime = -1000;
    }

    /**
     * Free Flight camera attitude. Locks the render camera to the craft's
     * yaw/pitch/roll every FRAME (interpolated), overriding the vanilla
     * mouse-driven view. This is what makes the mouse smooth while moving: the
     * mouse only feeds the deflection cursor (read per tick in KeyBindings) and
     * never leaks into the camera between ticks — plus it applies the roll
     * (bank) DOF, which vanilla has no player-camera field for.
     */
    @SubscribeEvent
    public void onFreeFlightCameraSetup(net.minecraftforge.client.event.EntityViewRenderEvent.CameraSetup event) {
        net.minecraft.entity.Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) return;
        net.minecraft.entity.Entity ridden = view.getRidingEntity();
        float p = (float) event.getRenderPartialTicks();

        if (ridden instanceof zmaster587.advancedRocketry.entity.EntityRocket) {
            zmaster587.advancedRocketry.entity.EntityRocket rocket =
                    (zmaster587.advancedRocketry.entity.EntityRocket) ridden;
            if (!(rocket.isFreeFlight() && rocket.isInFlight())) return;

            // Slerp the attitude quaternion this frame, then derive the camera Euler -
            // pole-safe through loops (see RendererRocket). The quaternion is the FF
            // attitude source of truth; deriving yaw/pitch/roll here reproduces the
            // craft basis exactly, so the view looks out the nose and banks with roll.
            zmaster587.advancedRocketry.api.FreeFlightPhysics.Quat cq =
                    zmaster587.advancedRocketry.api.FreeFlightPhysics.slerp(
                            rocket.getPrevFfQuat(), rocket.getFfQuat(), p);
            float[] e = zmaster587.advancedRocketry.api.FreeFlightPhysics.eulerFromQuat(cq);
            // +180: the vanilla camera-yaw convention faces opposite the raw
            // heading, so the ship yaw must be flipped to look out the nose.
            event.setYaw(e[0] + 180f);
            event.setPitch(e[1]);
            event.setRoll(e[2]);
            // Client-attitude readback for perception-contract e2e (see the fields).
            ffClientCamPitch = e[1];
            ffClientCamRoll  = e[2];
            double fz = cq.rotate(0, 0, 1)[2]; // client nose Z (world)
            ffClientForwardZ = fz;
            if (fz < ffClientMinForwardZ) ffClientMinForwardZ = fz;
            return;
        }

        // Tier-2 ship: the pilot rides a seat dummy, not a rocket. Lock the camera to the ship
        // attitude the client sampled this tick (KeyBindings), slerped prev->current by partialTicks
        // for a smooth per-frame view - the same nose-lock + no-free-look behaviour as the rocket.
        // Without this the ship view jitters (mouse leaks into free-look between ticks).
        TilePilotSeat seat = TilePilotSeat.forRider(ridden, Minecraft.getMinecraft().world);
        if (seat != null && seat.isLinked()) {
            zmaster587.advancedRocketry.api.FreeFlightPhysics.Quat cq =
                    zmaster587.advancedRocketry.api.FreeFlightPhysics.slerp(
                            KeyBindings.shipPrevQuat(), KeyBindings.shipQuat(), p);
            float[] e = zmaster587.advancedRocketry.api.FreeFlightPhysics.eulerFromQuat(cq);
            event.setYaw(e[0] + 180f);
            event.setPitch(e[1]);
            event.setRoll(e[2]);
            zmaster587.advancedRocketry.client.ShipFrameCamera.recordCamera(true, e[0], e[1], e[2],
                    cq.rotate(0, 1, 0),
                    zmaster587.advancedRocketry.client.ShipFrameCamera.shipCamEyeX,
                    zmaster587.advancedRocketry.client.ShipFrameCamera.shipCamEyeY,
                    zmaster587.advancedRocketry.client.ShipFrameCamera.shipCamEyeZ);
            return;
        }

        // A crew member standing on a deck: level the horizon with the deck, and nothing else. Rolling
        // the view is the ONLY degree of freedom added - his yaw and pitch come back exactly as he
        // aimed them, so getLook(), and therefore which block he mines, is untouched. Composing a full
        // ship-frame look instead would silently aim his cursor somewhere the camera is not pointing.
        //
        // Gate on the SAME "actually on a deck" truth the movement uses (ShipFrameTravel is resolving
        // this body), not on mere containment in the ship's world AABB. That box is axis-aligned and
        // overlaps a large air (and, for a grounded ship, terrain) volume around the hull; levelling the
        // view for anyone inside it hijacks the camera of a player merely flying up through the airspace
        // or standing on the ground beside the hull - he is not on the deck, so his view must be his own.
        // [FF-TRACE/CAM] test-gated, throttled (1/20 frames), only while aboard a ship's box: is the
        // deck-walking camera levelling engaged, and does the levelled view keep the player's own yaw and
        // pitch (only roll added)? A walking crew member whose view "goes where the mouse isn't" is either
        // not resolved on the deck (isResolving=false, the branch below returns his own view) or the
        // levelling is leaking into yaw/pitch. Self-records both cases, with no command to time by hand.
        // NOT test-gated: the harness child JVMs run without test mode, and this static is their
        // only window onto what the crosshair actually resolves (same pattern as the
        // ShipFrameTravel discriminator statics). One short string per frame.
        {
            net.minecraft.util.math.RayTraceResult over = Minecraft.getMinecraft().objectMouseOver;
            zmaster587.advancedRocketry.client.ShipFrameCamera.lastMouseOverBlock =
                    over == null || over.typeOfHit != net.minecraft.util.math.RayTraceResult.Type.BLOCK
                            ? "" : over.getBlockPos().getX() + "," + over.getBlockPos().getY() + ","
                                    + over.getBlockPos().getZ();
            net.minecraft.util.math.Vec3d rayEye = view.getPositionEyes(p);
            zmaster587.advancedRocketry.client.ShipFrameCamera.lastRayEyeX = rayEye.x;
            zmaster587.advancedRocketry.client.ShipFrameCamera.lastRayEyeY = rayEye.y;
            zmaster587.advancedRocketry.client.ShipFrameCamera.lastRayEyeZ = rayEye.z;
        }
        if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()
                && (ffCamTraceFrames++ % 20) == 0
                && zmaster587.advancedRocketry.integration.vs.VSIntegration.shipAttitudeAt(
                        view.world, view.posX, view.posY, view.posZ) != null) {
            boolean resolving = zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.isResolvingAboard(view);
            float inYaw = event.getYaw() - 180f;
            float inPitch = (float) event.getPitch();
            double[] up = resolving
                    ? zmaster587.advancedRocketry.client.ShipFrameCamera.shipUpFor(view, p) : null;
            float[] lvl = up == null ? null
                    : zmaster587.advancedRocketry.client.ShipFrameCamera
                            .deckLevelledCameraEuler(up, inYaw, inPitch);
            zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/CAM] walking"
                    + " resolving=" + resolving
                    + " inYaw=" + inYaw + " inPitch=" + inPitch
                    + (lvl == null ? " levelled=none"
                            : " outYaw=" + lvl[0] + " outPitch=" + lvl[1] + " roll=" + lvl[2]));
        }
        // ABOARD specifically: a HULL-STAND body (outer hull, contract C11) keeps world semantics -
        // its camera is its own, never levelled to a deck it is not standing on.
        if (!zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.isResolvingAboard(view)) {
            zmaster587.advancedRocketry.client.ShipFrameCamera.shipCamActive = false;
            return;
        }
        double[] shipUp = zmaster587.advancedRocketry.client.ShipFrameCamera.shipUpFor(view, p);
        if (shipUp == null) {
            zmaster587.advancedRocketry.client.ShipFrameCamera.shipCamActive = false;
            return;
        }
        float[] levelled = zmaster587.advancedRocketry.client.ShipFrameCamera
                .deckLevelledCameraEuler(shipUp, event.getYaw() - 180f, (float) event.getPitch());
        if (levelled == null) {
            return; // looking straight along the deck normal: roll is undefined, hold the last one
        }
        event.setYaw(levelled[0] + 180f);
        event.setPitch(levelled[1]);
        event.setRoll(levelled[2]);
        zmaster587.advancedRocketry.client.ShipFrameCamera.recordCamera(true,
                levelled[0], levelled[1], levelled[2], shipUp,
                zmaster587.advancedRocketry.client.ShipFrameCamera.shipCamEyeX,
                zmaster587.advancedRocketry.client.ShipFrameCamera.shipCamEyeY,
                zmaster587.advancedRocketry.client.ShipFrameCamera.shipCamEyeZ);
    }

    /**
     * Suppress the first-person hand/held-item render while piloting a Free
     * Flight craft. The camera is hard-locked to the craft axes every frame
     * ({@link #onFreeFlightCameraSetup}) while the held item still renders off
     * the player's own (now-overridden) rotation, so it jitters against the
     * locked view — and a block bobbing in the cockpit adds nothing anyway.
     */
    @SubscribeEvent
    public void onFreeFlightRenderHand(net.minecraftforge.client.event.RenderSpecificHandEvent event) {
        net.minecraft.entity.Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) return;
        net.minecraft.entity.Entity ridden = view.getRidingEntity();
        if (ridden instanceof zmaster587.advancedRocketry.entity.EntityRocket
                && ((zmaster587.advancedRocketry.entity.EntityRocket) ridden).isFreeFlight()
                && ((zmaster587.advancedRocketry.entity.EntityRocket) ridden).isInFlight()) {
            event.setCanceled(true);
            return;
        }
        // Same reasoning for a tier-2 ship pilot (camera hard-locked to the ship nose).
        TilePilotSeat seat = TilePilotSeat.forRider(ridden, Minecraft.getMinecraft().world);
        if (seat != null && seat.isLinked()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onScreenRender(RenderGameOverlayEvent.Post event) {
        Entity ride;
        if (event.getType() == ElementType.HOTBAR) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || mc.world == null) {
                return;
            }
            if ((ride = mc.player.getRidingEntity()) instanceof EntityRocket) {
                EntityRocket rocket = (EntityRocket) ride;

                GlStateManager.enableBlend();

                mc.renderEngine.bindTexture(background);

                //Draw BG
                this.drawTexturedModalRect(0, 0, 0, 0, 17, 252);

                //Draw altitude indicator
                float percentOrbit = MathHelper.clamp((float) ((rocket.posY - rocket.world.provider.getAverageGroundLevel()) / (float) (ARConfiguration.getCurrentConfig().orbit - rocket.world.provider.getAverageGroundLevel())), 0f, 1f);
                this.drawTexturedModalRect(3, 8 + (int) (79 * (1 - percentOrbit)), 17, 0, 6, 6); //6 to 83

                //Draw Velocity indicator
                this.drawTexturedModalRect(3, 94 + (int) (69 * (0.5 - (MathHelper.clamp((float) (rocket.motionY), -1f, 1f) / 2f))), 17, 0, 6, 6); //94 to 161

                //Draw fuel indicator
                int size = (int) (68 * rocket.getNormallizedProgress(0));
                this.drawTexturedModalRect(3, 242 - size, 17, 75 - size, 3, size); //94 to 161

                GlStateManager.disableBlend();
                String str = rocket.getTextOverlay();
                if (!str.isEmpty()) {

                    String[] strs = str.split("\n");
                    int vertPos = 0;
                    for (String strPart : strs) {

                        FontRenderer fontRenderer = mc.fontRenderer;

                        float scale = str.length() < 50 ? 1f : 0.5f;

                        int screenX = (int) ((event.getResolution().getScaledWidth() / (scale * 6) - fontRenderer.getStringWidth(strPart) / 2));
                        int screenY = (int) ((event.getResolution().getScaledHeight() / 18) / scale) + 18 * vertPos;


                        GL11.glPushMatrix();
                        GL11.glScalef(scale * 3, scale * 3, scale * 3);

                        fontRenderer.drawStringWithShadow(strPart, screenX, screenY, 0xFFFFFF);

                        GL11.glPopMatrix();

                        vertPos++;
                    }
                }
                // New bottom-right hint
                if (mc.currentScreen == null) { // no GUI open
                    FontRenderer fontRenderer = mc.fontRenderer;
                    String keyName = GameSettings.getKeyDisplayString(
                            KeyBindings.getOpenRocketUI().getKeyCode()
                    );
                    String hint = I18n.format("msg.entity.rocket.openGuiHint", keyName);

                    int scaledW = event.getResolution().getScaledWidth();
                    int scaledH = event.getResolution().getScaledHeight();
                    int textWidth = fontRenderer.getStringWidth(hint);
                    int textHeight = fontRenderer.FONT_HEIGHT;

                    float scale = 1.0F;
                    float x = (scaledW - 4 - textWidth * scale) / scale;
                    float y = (scaledH - 4 - textHeight * scale) / scale;

                    GL11.glPushMatrix();
                    GL11.glScalef(scale, scale, scale);
                    fontRenderer.drawStringWithShadow(hint, x, y, 0xFFFFFF);
                    GL11.glPopMatrix();
                }

                // Free Flight Mode HUD is rendered below (backend-agnostic — it also serves the
                // tier-2 ship), driven by a FreeFlightHudState snapshot rather than this rocket.

                // Camera-nose lock telemetry: on every rendered
                // frame of an FF flight, record the worst player-camera vs
                // craft-axes divergence. Small values = intra-tick mouse
                // deflection (by design); a runaway means the lock broke.
                if (rocket.isFreeFlight() && rocket.isInFlight()
                        && KeyBindings.isCameraPinnedThisFlight()) {
                    double yawErr = Math.abs(MathHelper.wrapDegrees(
                            mc.player.rotationYaw - rocket.rotationYaw));
                    double pitchErr = Math.abs(
                            mc.player.rotationPitch - rocket.rotationPitch);
                    double err = Math.max(yawErr, pitchErr);
                    lastCameraLockErrorDeg = err;
                    if (err > maxCameraLockErrorDeg) maxCameraLockErrorDeg = err;
                } else if (!rocket.isInFlight()) {
                    maxCameraLockErrorDeg = 0.0;
                    lastCameraLockErrorDeg = 0.0;
                    ffClientMinForwardZ = 1.0; // fresh loop witness per flight
                }

            }

            // Free Flight HUD — backend-agnostic: renders for a tier-1 rocket AND a tier-2 ship
            // (piloted from a seat), driven by one snapshot. Outside the EntityRocket block above
            // because the ship pilot rides a seat dummy, not a rocket.
            if (mc.currentScreen == null) {
                FreeFlightHudState ffState = FreeFlightHudState.forView(mc.player, mc.world);
                if (ffState != null) {
                    renderFreeFlightHud(event, mc, ffState);
                }
            }

            //Draw the O2 Bar if needed
            if (!(mc.player.capabilities.isCreativeMode || mc.player.isSpectator())) {
                ItemStack chestPiece = mc.player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);
                IFillableArmor fillable = null;
                if (!chestPiece.isEmpty() && chestPiece.getItem() instanceof IFillableArmor)
                    fillable = (IFillableArmor) chestPiece.getItem();
                else if (ItemAirUtils.INSTANCE.isStackValidAirContainer(chestPiece))
                    fillable = new ItemAirUtils.ItemAirWrapper(chestPiece);

                if (fillable != null) {
                    float size = fillable.getAirRemaining(chestPiece) / (float) fillable.getMaxAir(chestPiece);

                    GlStateManager.enableBlend();
                    mc.renderEngine.bindTexture(background);
                    GlStateManager.color(1f, 1f, 1f);
                    int width = 83;
                    int screenX = oxygenBar.getRenderX();//+ 8;
                    int screenY = oxygenBar.getRenderY();//- 57;

                    //Draw BG
                    this.drawTexturedModalRect(screenX, screenY, 23, 0, width, 17);
                    this.drawTexturedModalRect(screenX, screenY, 23, 17, (int) (width * size), 17);
                }
            }

            //Draw module icons
            if (!(mc.player.capabilities.isCreativeMode || mc.player.isSpectator()) && !mc.player.getItemStackFromSlot(EntityEquipmentSlot.HEAD).isEmpty() && mc.player.getItemStackFromSlot(EntityEquipmentSlot.HEAD).getItem() instanceof IModularArmor) {
                for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
                    renderModuleSlots(mc.player.getItemStackFromSlot(slot), 4 - slot.getIndex(), event);
                }
            }


            long worldTime = mc.world.getTotalWorldTime();

            if (mc.player.dimension != lastSuffocationWarningDim) {
                lastSuffocationWarningDim = mc.player.dimension;
                AtmosphereHandler.lastSuffocationTime = worldTime - numTicksToDisplay - 1;
                suppressSuffocationWarningUntil = worldTime + 40;
            }

            // In event of world change make sure the warning isn't displayed
            if (worldTime - AtmosphereHandler.lastSuffocationTime < 0) {
                AtmosphereHandler.lastSuffocationTime = worldTime - numTicksToDisplay - 1;
            }

            // Tell the player he's suffocating if needed
            if (worldTime >= suppressSuffocationWarningUntil &&
                    worldTime - AtmosphereHandler.lastSuffocationTime < numTicksToDisplay) {
                FontRenderer fontRenderer = mc.fontRenderer;
                String str = "";
                if (AtmosphereHandler.currentAtm != null) {
                    str = AtmosphereHandler.currentAtm.getDisplayMessage();
                }

                int screenX = event.getResolution().getScaledWidth() / 6 - fontRenderer.getStringWidth(str) / 2;
                int screenY = event.getResolution().getScaledHeight() / 18;

                GL11.glPushMatrix();
                GL11.glScalef(3, 3, 3);

                fontRenderer.drawStringWithShadow(str, screenX, screenY, 0xFF5656);
                GlStateManager.color(1f, 1f, 1f);
                mc.getTextureManager().bindTexture(TextureResources.progressBars);
                this.drawTexturedModalRect(screenX + fontRenderer.getStringWidth(str) / 2 - 8, screenY - 16, 0, 156, 16, 16);

                GL11.glPopMatrix();
            }

            //Draw arbitrary string
            if (mc.world.getTotalWorldTime() <= lastDisplayTime) {
                FontRenderer fontRenderer = mc.fontRenderer;
                GL11.glPushMatrix();
                GL11.glScalef(2, 2, 2);
                int loc = 0;
                for (String str : displayString.split("\n")) {

                    int screenX = event.getResolution().getScaledWidth() / 4 - fontRenderer.getStringWidth(str) / 2;
                    int screenY = event.getResolution().getScaledHeight() / 12 + loc * (event.getResolution().getScaledHeight()) / 12;


                    fontRenderer.drawStringWithShadow(str, screenX, screenY, 0xFF5656);
                    loc++;
                }

                GlStateManager.color(1f, 1f, 1f);
                GL11.glPopMatrix();
            }
        }
    }

    private void renderModuleSlots(@Nonnull ItemStack armorStack, int slot, RenderGameOverlayEvent event) {
        int index = 1;
        float color = 0.85f + 0.15F * MathHelper.sin(2f * (float) Math.PI * ((Minecraft.getMinecraft().world.getTotalWorldTime()) % 60) / 60f);
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        float alpha = 0.6f;


        if (!armorStack.isEmpty()) {

            boolean modularArmorFlag = armorStack.getItem() instanceof IModularArmor;

            if (modularArmorFlag || ItemAirUtils.INSTANCE.isStackValidAirContainer(armorStack)) {

                int size = 24;
                int screenY = suitPanel.getRenderY() + (slot - 1) * (size + 8);
                int screenX = suitPanel.getRenderX();

                //Draw BG
                GlStateManager.color(1f, 1f, 1f, 1f);
                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX - 4, screenY - 4, screenX + size, screenY + size + 4, 0d, 0.5d, 0d, 1d);
                Tessellator.getInstance().draw();

                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX + size, screenY - 3, screenX + 2 + size, screenY + size + 3, 0.5d, 0.5d, 0d, 0d);
                Tessellator.getInstance().draw();

                //Draw Icon
                GlStateManager.color(color, color, color, color);
                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.armorSlots[slot - 1]);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX, screenY, screenX + size, screenY + size, 0d, 1d, 1d, 0d);
                Tessellator.getInstance().draw();

                if (modularArmorFlag) {
                    List<ItemStack> stacks = ((IModularArmor) armorStack.getItem()).getComponents(armorStack);
                    for (ItemStack stack : stacks) {
                        GlStateManager.color(1f, 1f, 1f, 1f);
                        ((IArmorComponent) stack.getItem()).renderScreen(stack, stacks, event, this);

                        ResourceIcon icon = ((IArmorComponent) stack.getItem()).getComponentIcon(stack);
                        ResourceLocation texture = null;
                        if (icon != null)
                            texture = icon.getResourceLocation();

                        //if(texture != null) {

                        screenX = suitPanel.getRenderX() + 4 + index * (size + 2);

                        //Draw BG

                        Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                        RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX - 4, screenY - 4, screenX + size - 2, screenY + size + 4, 0.5d, 0.5d, 0d, 1d);
                        Tessellator.getInstance().draw();


                        if (texture != null) {
                            //Draw Icon
                            Minecraft.getMinecraft().renderEngine.bindTexture(texture);
                            GlStateManager.color(color, color, color, alpha);
                            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                            RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX, screenY, screenX + size, screenY + size, icon.getMinU(), icon.getMaxU(), icon.getMaxV(), icon.getMinV());
                            Tessellator.getInstance().draw();
                        } else {
                            GL11.glPushMatrix();
                            GlStateManager.translate(screenX, screenY, 0);
                            GlStateManager.scale(1.5f, 1.5f, 1.5f);
                            Minecraft.getMinecraft().getRenderItem().renderItemIntoGUI(stack, 0, 0);
                            GL11.glPopMatrix();
                        }

                        index++;
                        //}
                    }
                }

                screenX = (index) * (size + 2) + suitPanel.getRenderX() - 12;
                //Draw BG
                GlStateManager.color(1, 1, 1, 1f);
                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX + 12, screenY - 4, screenX + size, screenY + size + 4, 0.75d, 1d, 0d, 1d);
                Tessellator.getInstance().draw();
            }
        }

        GlStateManager.disableAlpha();
    }

    public static class GuiBox {
        int modeX = -1;
        int modeY = -1;
        int sizeX, sizeY;
        boolean isVisible = true;
        private int x;
        private int y;

        public GuiBox(int x, int y, int sizeX, int sizeY) {
            this.setRawX(x);
            this.setRawY(y);
            this.sizeX = sizeX;
            this.sizeY = sizeY;
        }

        public int getX(int scaledW) {

            if (modeX == 1)
                return scaledW - getRawX();
            else if (modeX == 0) {
                return scaledW / 2 - getRawX();
            }
            return getRawX();
        }

        public int getY(int scaledH) {

            if (modeY == 1)
                return scaledH - getRawY();
            else if (modeY == 0) {
                return scaledH / 2 - getRawY();
            }
            return getRawY();
        }

        public int getRenderX() {
            ScaledResolution scaledresolution = new ScaledResolution(Minecraft.getMinecraft());
            int i = scaledresolution.getScaledWidth();

            if (modeX == 1) {
                return i - getRawX();
            } else if (modeX == 0) {
                return i / 2 - getRawX();
            }
            return this.getRawX();
        }

        public int getRenderY() {
            ScaledResolution scaledresolution = new ScaledResolution(Minecraft.getMinecraft());
            int i = scaledresolution.getScaledHeight();

            if (modeY == 1) {
                return i - getRawY();
            } else if (modeY == 0) {
                return i / 2 - getRawY();
            }
            return this.getRawY();
        }

        public int getRawX() {
            return x;
        }

        public void setRawX(int x) {
            this.x = x;
        }

        public int getRawY() {
            return y;
        }

        public void setRawY(int y) {
            this.y = y;
        }

        public void setSizeModeX(int int1) {
            modeX = int1;
        }

        public void setSizeModeY(int int1) {
            modeY = int1;
        }
    }
}