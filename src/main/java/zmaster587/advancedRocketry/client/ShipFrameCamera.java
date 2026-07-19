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
     *  blocks come back in SUBSPACE coordinates), or "" when it hit no block. Test-mode only -
     *  lets a client e2e assert WHAT the crosshair actually picks (contract C10) through
     *  {@code readStaticField}, with no live objectMouseOver access. */
    public static volatile String lastMouseOverBlock = "";
    /** Where the crosshair RAY actually originates ({@code getPositionEyes}) this frame — compared
     *  by the C10 e2e against {@code shipCamEye*} (what the RENDERER recorded): the two must be one
     *  point, or the crosshair picks a block the camera is not looking at. */
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
        TilePilotSeat seat = TilePilotSeat.forRider(view.getRidingEntity(), view.world);
        if (seat != null && seat.isLinked() && view == mc.player) {
            return FreeFlightPhysics.slerp(KeyBindings.shipPrevQuat(), KeyBindings.shipQuat(), partialTicks);
        }
        // The LOCAL player's eye/camera/model gate on the MOVEMENT truth - resolved ABOARD a deck -
        // never on containment (contract C7). Containment overlaps a large air volume around the
        // hull (the fly-through hijack), and a HULL-STAND body (outer hull, C11) is inside it too
        // while owning a world-frame view. Remote bodies keep the containment gate for now: their
        // movement is never resolved on this side, and un-rotating a remote crew member's model on
        // a rolled deck is the worse artefact until the spatial deck gate lands.
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
        }
        return VSIntegration.shipAttitudeFor(view);
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
        FreeFlightPhysics.Quat q = viewShipQuat(entity, partialTicks);
        if (q == null) {
            return null;
        }
        double w = q.w;
        if (w > 1.0) w = 1.0;
        if (w < -1.0) w = -1.0;
        double angle = 2.0 * Math.acos(w);
        double s = Math.sqrt(1.0 - w * w);
        if (Double.isNaN(angle) || angle < 1.0E-4 || s < 1.0E-9) {
            return null; // upright ship: identity rotation
        }
        return new double[]{Math.toDegrees(angle), q.x / s, q.y / s, q.z / s};
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
