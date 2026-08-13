package zmaster587.advancedRocketry.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * The client's view of the ship an entity is aboard: the attitude that levels the camera with the
 * deck, the offset that puts the eye where the head actually is, and the rotation that draws the body
 * standing on the deck rather than floating upright beside it.
 *
 * <p>Vanilla assumes a body's up is the world's up. It adds the eye height along world {@code +Y}
 * ({@code EntityRenderer.orientCamera}) and rotates a model by yaw alone
 * ({@code RenderLivingBase.applyRotations}). On an inverted ship the first puts the pilot's eye inside
 * the deck above his seat, so nothing renders at all; the second leaves the crew standing sideways out
 * of the hull.</p>
 *
 * <p>The physics mod corrects both, but only for entities it considers <em>mounted</em> to a ship - its
 * own seat concept, which AR's pilot dummy is not. So AR supplies them.</p>
 */
@SideOnly(Side.CLIENT)
public final class ShipFrameCamera {

    private ShipFrameCamera() {}

    // ---- Client-observable telemetry (read by the flight e2e; never by production logic) -------

    /** Whether the ship-frame camera was engaged on the last rendered frame. */
    public static volatile boolean shipCamActive = false;
    /** The block position the client's crosshair raytrace resolved this frame, as "x,y,z" (ship
     *  blocks come back in SUBSPACE coordinates), or "" when it hit no block. Written
     *  UNCONDITIONALLY - deliberately NOT gated on test mode, because the harness child JVMs run
     *  without it and this static is their only window onto what the crosshair resolves. Lets a
     *  client e2e assert WHAT the crosshair actually picks - the block outlined under the crosshair
     *  must be the block interacted with, at any ship attitude - through {@code readStaticField},
     *  with no live objectMouseOver access. */
    public static volatile String lastMouseOverBlock = "";
    /** Where the crosshair RAY actually originates ({@code getPositionEyes}) this frame — compared
     *  by the crosshair-picking e2e against {@code shipCamEye*} (what the RENDERER recorded): the
     *  two must be one point, or the crosshair picks a block the camera is not looking at. */
    public static volatile double lastRayEyeX, lastRayEyeY, lastRayEyeZ;
    /** The camera attitude actually pushed to the renderer last frame (degrees). */
    public static volatile double shipCamYaw = 0.0;
    public static volatile double shipCamPitch = 0.0;
    public static volatile double shipCamRoll = 0.0;
    /** The world-frame eye position the camera was placed at last frame. */
    public static volatile double shipCamEyeX = 0.0;
    public static volatile double shipCamEyeY = 0.0;
    public static volatile double shipCamEyeZ = 0.0;
    /** The ship's local up, in world coordinates, last frame. Identity (0,1,0) when not aboard. */
    public static volatile double shipUpX = 0.0;
    public static volatile double shipUpY = 1.0;
    public static volatile double shipUpZ = 0.0;

    // ---- Remote-body model-gate telemetry. The decision is taken per entity per FRAME and is a
    // transient: a first/last-call snapshot lands on an arbitrary body at an arbitrary moment and
    // says nothing. Cumulative counters plus a bounded trace with coordinates are what a test can
    // actually reason about - "over this window, how many remote bodies were drawn rotated, and
    // where were they". Ungated (no test-mode check): the harness's child JVMs have no test mode.
    /** Frames on which the camera-stage render hook ran. The control for {@link #modelRotationCalls}:
     *  if this advances and that does not, the render stage IS running and the model stage is the
     *  thing not reaching us; if neither advances, nothing is being drawn at all. */
    public static volatile long cameraHookCalls = 0L;
    /** Entities in the CLIENT world, sampled on the same frame as {@link #cameraHookCalls}. The
     *  other control: a draw-stage counter of zero means nothing when the subject never reached
     *  this side. {@code -1} = no client world. */
    public static volatile int clientLoadedEntities = -1;
    /** EVERY model-rotation decision, local player included. The discriminator against a silent
     *  mixin miss: {@code MixinRenderLivingBaseShipRoll} is {@code require = 0}, so a render mod
     *  that rewrites {@code applyRotations} (or an ordinal drift) disables the whole gate without
     *  a word. Zero calls here means the hook never ran; calls without remote samples means it ran
     *  but nothing but the local player was drawn. Those are different bugs and a bare
     *  "remoteModelSamples == 0" cannot tell them apart. */
    public static volatile long modelRotationCalls = 0L;
    /** Model-rotation decisions taken for a body that is NOT this client's own player. */
    public static volatile long remoteModelSamples = 0L;
    /** Of those, the ones that pushed a non-identity rotation (the body was drawn ship-aligned). */
    public static volatile long remoteModelRotatedSamples = 0L;
    /** The largest rotation angle (degrees) ever pushed for a remote body. */
    public static volatile double maxRemoteModelRotationDeg = 0.0;
    /** The most recent few remote decisions as "name@x,y,z=deg", bounded. Coordinates included so
     *  a red run names WHICH body was rotated and where it stood - the diagnosis, not just the
     *  count. */
    public static volatile String remoteModelTrace = "";

    // ---- Smoothness discriminators (ledger: "6-8 discrete points per jump"). A dead prev->pos
    // interpolation shows as consecutive frames sharing one interpolated camera position: at
    // 120 FPS / 20 TPS a healthy ratio is ~0 same-pos frames; ~5/6 of them means the camera is
    // stepping at tick rate. posLookApplies names the classic prev-collapsing writer (a server
    // PosLook echo per tick). Ungated statics - harness child JVMs have no test mode. ----

    /** Frames rendered with the aboard camera engaged. */
    public static volatile long aboardFramesRendered = 0;
    /** Of those, frames whose interpolated camera position equalled the previous frame's. */
    public static volatile long aboardFramesSamePos = 0;
    /** Server PosLook packets actually applied on the client main thread. */
    public static volatile long posLookApplies = 0;
    private static double lastFrameX = Double.NaN, lastFrameY = Double.NaN, lastFrameZ = Double.NaN;

    // Per-frame STEP statistics over a resettable window, for the ABSOLUTE body position and for
    // the body position RELATIVE to a fixed deck point (DeckLook's episode reference, itself
    // frame-lerped). Discriminates where a felt stutter lives: a smooth path has near-uniform
    // per-frame steps (max ~ mean); a tick-stepped path has zero steps within a tick and spikes
    // at tick boundaries (max >> mean). Relative-vs-absolute splits "the body jitters in the
    // world" from "the body jitters against the deck it rides".
    public static volatile double absStepMax = 0.0, absStepSum = 0.0;
    public static volatile long absStepCount = 0;
    public static volatile double relStepMax = 0.0, relStepSum = 0.0;
    public static volatile long relStepCount = 0;
    private static double lastRelX = Double.NaN, lastRelY = Double.NaN, lastRelZ = Double.NaN;

    /** Reset the step-statistics window (invoked reflectively by the smoothness e2e). */
    public static int resetStepWindow() {
        absStepMax = 0.0;
        absStepSum = 0.0;
        absStepCount = 0;
        relStepMax = 0.0;
        relStepSum = 0.0;
        relStepCount = 0;
        lastFrameX = Double.NaN;
        lastRelX = Double.NaN;
        return 0;
    }

    /** Called once per aboard frame with the camera's interpolated base position. */
    public static void recordFrameInterp(double x, double y, double z, float partialTicks) {
        aboardFramesRendered++;
        if (x == lastFrameX && y == lastFrameY && z == lastFrameZ) {
            aboardFramesSamePos++;
        }
        if (!Double.isNaN(lastFrameX)) {
            double step = Math.sqrt((x - lastFrameX) * (x - lastFrameX)
                    + (y - lastFrameY) * (y - lastFrameY) + (z - lastFrameZ) * (z - lastFrameZ));
            if (step > absStepMax) absStepMax = step;
            absStepSum += step;
            absStepCount++;
        }
        lastFrameX = x;
        lastFrameY = y;
        lastFrameZ = z;
        double[] ref = DeckLook.refWorldAt(partialTicks);
        if (ref != null) {
            double rx = x - ref[0], ry = y - ref[1], rz = z - ref[2];
            if (!Double.isNaN(lastRelX)) {
                double step = Math.sqrt((rx - lastRelX) * (rx - lastRelX)
                        + (ry - lastRelY) * (ry - lastRelY) + (rz - lastRelZ) * (rz - lastRelZ));
                if (step > relStepMax) relStepMax = step;
                relStepSum += step;
                relStepCount++;
            }
            lastRelX = rx;
            lastRelY = ry;
            lastRelZ = rz;
        } else {
            lastRelX = Double.NaN;
        }
    }

    /**
     * The attitude of the ship {@code view} is aboard, smoothed across the frame, or {@code null} when
     * it is aboard none. A piloting local player uses the per-tick attitude samples the input path
     * already keeps, slerped by {@code partialTicks} - stepping at 20 Hz instead is the tier-2 jitter.
     */
    public static FreeFlightPhysics.Quat viewShipQuat(Entity view, float partialTicks) {
        if (view == null || view.world == null) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (TilePilotSeat.forShipPilot(view.getRidingEntity(), view.world) != null && view == mc.player) {
            return FreeFlightPhysics.slerp(KeyBindings.shipPrevQuat(), KeyBindings.shipQuat(), partialTicks);
        }
        // The LOCAL player's eye/camera/model gate on the MOVEMENT truth - resolved ABOARD a deck -
        // never on containment: a body is aboard when a deck carries its movement, not when it
        // merely sits inside the hull's volume. Containment overlaps a large air volume around the
        // hull (the fly-through hijack), and a HULL-STAND body (standing on the OUTER hull, which
        // keeps world-frame semantics) is inside it too while owning a world-frame view.
        if (view == mc.player) {
            if (!zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.isResolvingAboard(view)) {
                return null;
            }
            // Slerp the per-tick attitude samples across the frame, exactly as the pilot path
            // above does - the raw attitude steps at 20 Hz and a station-keeping ship's hunting
            // then shows as jitter at any frame rate.
            FreeFlightPhysics.Quat slerped = DeckLook.slerpedShipQuat(partialTicks);
            if (slerped != null) {
                return slerped;
            }
            return VSIntegration.shipAttitudeFor(view);
        }
        // A REMOTE body has no capture state on this side (the client resolves only its own
        // player's movement), so the movement truth is unavailable and containment is not a
        // substitute: it is true across the whole air volume around the hull, which drew any mob
        // merely standing on the ground beside a tilted ship lying on its side. Ask the same
        // SPATIAL question first contact asks instead - standing support in the ship's subspace -
        // which needs only the ship's blocks, and those this side has.
        String supporting = recentlySupportingShipId(view);
        return supporting == null ? null
                : VSIntegration.shipAttitudeForId(view.world, supporting);
    }

    /** How long a remote body keeps its supporting ship after standing support is last measured.
     *  A jump on the deck is unsupported for its whole arc, and a probe with no memory would pop
     *  the model upright mid-jump; the window only has to outlast a jump, not a walk-off. */
    private static final int SUPPORT_MEMORY_TICKS = 20;

    /** Per-body memory of the last ship measured to carry it, with the tick it was measured on.
     *  Weak keys: an entity that despawns must not be held alive by this. */
    private static final java.util.Map<EntityLivingBase, SupportMemo> SUPPORT_MEMO =
            new java.util.WeakHashMap<EntityLivingBase, SupportMemo>();

    private static final class SupportMemo {
        String shipId;
        long probedTick;
        long supportedTick;
    }

    /**
     * The ship carrying {@code view} by STANDING support in its subspace, kept for
     * {@link #SUPPORT_MEMORY_TICKS} after support was last seen; {@code null} when no loaded ship
     * has carried it recently.
     *
     * <p>The probe runs at most once per body per TICK - {@code applyRotations} asks twice per body
     * per FRAME (rotation and deck yaw), and a collision query at frame rate for every rendered mob
     * is not a cost this may pay.</p>
     */
    private static String recentlySupportingShipId(Entity view) {
        if (!(view instanceof EntityLivingBase) || view.world == null) {
            return null;
        }
        EntityLivingBase body = (EntityLivingBase) view;
        long tick = view.world.getTotalWorldTime();
        SupportMemo memo = SUPPORT_MEMO.get(body);
        if (memo == null) {
            memo = new SupportMemo();
            memo.supportedTick = Long.MIN_VALUE;
            SUPPORT_MEMO.put(body, memo);
        }
        if (memo.probedTick != tick) {
            memo.probedTick = tick;
            String carrying =
                    zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.standingOnShipIdFor(body);
            if (carrying != null) {
                memo.shipId = carrying;
                memo.supportedTick = tick;
            }
        }
        if (memo.shipId == null || tick - memo.supportedTick > SUPPORT_MEMORY_TICKS) {
            return null;
        }
        return memo.shipId;
    }

    /** The ship's local up in world coordinates for {@code view}, or {@code null} when not aboard. */
    public static double[] shipUpFor(Entity view, float partialTicks) {
        FreeFlightPhysics.Quat q = viewShipQuat(view, partialTicks);
        return q == null ? null : q.rotate(0.0, 1.0, 0.0);
    }

    /**
     * The camera attitude for a body standing on a deck whose look this client does NOT hold in the
     * deck frame (spectating an aboard body): its own world look, levelled to the ship's horizon.
     * Only the roll degree of freedom is added - yaw and pitch come back unchanged - so the view
     * still points exactly where that body aims. The LOCAL player's walking camera does not use
     * this any more: his look is held deck-frame ({@link DeckLook}) and the camera composes the
     * ship attitude with it directly, which has no singular attitude - this levelling is
     * undefined when the deck goes vertical (returns {@code null} along the deck normal).
     *
     * @return {yaw, pitch, roll} in degrees, or {@code null} to leave the camera alone
     */
    public static float[] deckLevelledCameraEuler(double[] shipUp, float yawDeg, float pitchDeg) {
        if (shipUp == null) {
            return null;
        }
        double[] forward = lookVec(yawDeg, pitchDeg);
        FreeFlightPhysics.Quat cam = FreeFlightPhysics.deckLevelledCameraQuat(forward, shipUp);
        return cam == null ? null : FreeFlightPhysics.eulerFromQuat(cam);
    }

    /**
     * The model rotation that stands {@code entity} on its deck: the ship attitude as an axis-angle
     * {@code {degrees, ax, ay, az}}, or {@code null} when it is aboard no ship (or the ship is upright,
     * where the rotation is the identity and pushing it would be pure cost).
     */
    public static double[] modelRotationFor(EntityLivingBase entity, float partialTicks) {
        modelRotationCalls++;
        FreeFlightPhysics.Quat q = viewShipQuat(entity, partialTicks);
        double[] rotation = axisAngleOf(q);
        recordRemoteModelDecision(entity, rotation);
        return rotation;
    }

    /** {@code q} as {@code {degrees, ax, ay, az}}, or {@code null} for no/identity rotation
     *  (an upright ship: pushing it would be pure cost). */
    private static double[] axisAngleOf(FreeFlightPhysics.Quat q) {
        if (q == null) {
            return null;
        }
        double w = q.w;
        if (w > 1.0) w = 1.0;
        if (w < -1.0) w = -1.0;
        double angle = 2.0 * Math.acos(w);
        double s = Math.sqrt(1.0 - w * w);
        if (Double.isNaN(angle) || angle < 1.0E-4 || s < 1.0E-9) {
            return null;
        }
        return new double[]{Math.toDegrees(angle), q.x / s, q.y / s, q.z / s};
    }

    /**
     * Whether the model-roll hook is actually WOVEN into {@code RenderLivingBase} right now:
     * {@code 1} installed, {@code 0} absent.
     *
     * <p>{@code MixinRenderLivingBaseShipRoll} is declared {@code require = 0} so that a render mod
     * which rewrites {@code applyRotations} cannot abort the whole mixin config. The price is that
     * a miss - an ordinal drift, a competing transformer, a mapping change - is completely SILENT:
     * the feature is simply gone and every symptom looks like "nothing was drawn". This asks the
     * transformed class itself, so the answer survives a deleted harness log (a client log is not
     * available post-run) and a test can separate "the gate decided not to rotate" from "there is
     * no gate".</p>
     */
    public static final int modelGateInstalledFlag = modelGateInstalled();

    /** @see #modelGateInstalledFlag */
    public static int modelGateInstalled() {
        try {
            for (java.lang.reflect.Method m
                    : net.minecraft.client.renderer.entity.RenderLivingBase.class.getDeclaredMethods()) {
                if (m.getName().contains("rollWithShip")) {
                    return 1;
                }
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Telemetry only - see the {@code remoteModel*} fields. Never influences the decision. */
    private static void recordRemoteModelDecision(EntityLivingBase entity, double[] rotation) {
        if (entity == null || entity == Minecraft.getMinecraft().player) {
            return;
        }
        remoteModelSamples++;
        if (rotation == null) {
            return; // the common (and correct) case: no allocation on the render path
        }
        remoteModelRotatedSamples++;
        if (rotation[0] > maxRemoteModelRotationDeg) {
            maxRemoteModelRotationDeg = rotation[0];
        }
        // Only rotated decisions are traced, and only until the bound: this is the FAILING case,
        // and its first few occurrences carry the diagnosis (which body, standing where). A green
        // run never allocates here; a red one names its subject without unbounded churn.
        String trace = remoteModelTrace;
        if (trace.length() < 400) {
            remoteModelTrace = trace + String.format(java.util.Locale.ROOT,
                    "[%s@%.1f,%.1f,%.1f=%.0fdeg]",
                    entity.getName(), entity.posX, entity.posY, entity.posZ, rotation[0]);
        }
    }

    /**
     * A world yaw, re-expressed in the ship's frame. The model's own yaw is a world heading; once the
     * ship rotation is applied around it, the yaw vanilla adds must be the deck-plane heading instead,
     * or the body's facing is counted in two frames at once.
     */
    public static float deckYawDeg(Entity entity, float worldYawDeg, float partialTicks) {
        FreeFlightPhysics.Quat q = viewShipQuat(entity, partialTicks);
        if (q == null) {
            return worldYawDeg;
        }
        double[] forward = lookVec(worldYawDeg, 0f);
        // world -> ship is the inverse rotation; for a unit quaternion that is its conjugate.
        FreeFlightPhysics.Quat inv = new FreeFlightPhysics.Quat(q.w, -q.x, -q.y, -q.z);
        double[] deckForward = inv.rotate(forward[0], forward[1], forward[2]);
        return FreeFlightPhysics.yawFromForwardDeg(deckForward[0], deckForward[1], deckForward[2]);
    }

    /** Minecraft's look vector for a yaw/pitch pair (degrees). */
    private static double[] lookVec(float yawDeg, float pitchDeg) {
        float yaw = yawDeg * 0.017453292F;
        float pitch = pitchDeg * 0.017453292F;
        float cosPitch = MathHelper.cos(pitch);
        return new double[]{
                -MathHelper.sin(yaw) * cosPitch,
                -MathHelper.sin(pitch),
                MathHelper.cos(yaw) * cosPitch
        };
    }

    /** Record what the renderer was actually handed this frame, for the flight e2e to read back. */
    public static void recordCamera(boolean active, double yaw, double pitch, double roll,
                                    double[] shipUp, double eyeX, double eyeY, double eyeZ) {
        shipCamActive = active;
        shipCamYaw = yaw;
        shipCamPitch = pitch;
        shipCamRoll = roll;
        shipCamEyeX = eyeX;
        shipCamEyeY = eyeY;
        shipCamEyeZ = eyeZ;
        if (shipUp != null) {
            shipUpX = shipUp[0];
            shipUpY = shipUp[1];
            shipUpZ = shipUp[2];
        }
    }
}
