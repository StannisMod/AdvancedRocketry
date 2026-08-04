package zmaster587.advancedRocketry.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * The local crew member's look, held in the DECK frame while his movement is resolved aboard a
 * ship.
 *
 * <p>Vanilla stores one look - world yaw/pitch - and every consumer reads it: the mouse turns it,
 * {@code getLook()} ray-traces by it, {@code CPacketPlayer} replicates it, the renderer faces the
 * body by it. On a rolled deck that single world aim is the wrong frame for exactly two of those
 * consumers: the MOUSE (the player moves it relative to the screen, which is levelled to the deck)
 * and the intuitive AIM (a heading "along the deck" is a rotating world direction). Patching each
 * consumer separately - rotating the mouse delta by the camera roll - approximates the feel but
 * leaves the aim world-frame, and the roll estimate it depends on flips discontinuously when the
 * deck goes vertical, because "level the horizon with the deck" is degenerate there.</p>
 *
 * <p>This class inverts the storage instead: while aboard, the SOURCE look is a deck-frame
 * yaw/pitch pair. The mouse turns it directly - deck-relative by construction, at any attitude,
 * with no singularity. The player's world {@code rotationYaw}/{@code rotationPitch} are then
 * DERIVED from it through the ship attitude every tick and every frame
 * ({@code world = shipAttitude * deckLook}), so every world consumer - {@code getLook()}, block
 * interaction, the yaw/pitch on the wire, the rendered body, the server - keeps its exact vanilla
 * semantics and never sees a deck-frame number. The camera composes the SAME two rotations
 * ({@code RocketEventHandler}), so the crosshair and the rendered view are one transform by
 * construction. Nothing changes server-side, and the wire meaning of the player's rotation stays
 * world-frame.</p>
 *
 * <p>The one seam: something else may legitimately write the world rotation while aboard - a
 * server teleport / PosLook packet (also how test harnesses aim the player). Overwriting that from
 * the held deck look would undo it, so every sync compares the fields against what this class last
 * wrote: a mismatch means an external writer, and the deck look RE-SEEDS from the new world aim
 * (the same discipline the movement capture applies to external position writes).</p>
 */
@SideOnly(Side.CLIENT)
public final class DeckLook {

    private DeckLook() {}

    static {
        // The movement half of the one-transform rule: the walk basis consumes the SAME deck
        // heading the mouse turns, instead of re-deriving it from the world-yaw projection
        // (which skews on a rolled ship and degenerates when the deck goes vertical). Installed
        // here so a dedicated server, which never loads this client class, keeps the fallback.
        ShipFrameTravel.clientDeckLookYaw = DeckLook::heldDeckYawFor;
        // The flying-aboard vertical intent: read at CALL time (during the local player's own
        // travel) so it is EXACTLY the movementInput state vanilla's world-frame fly impulse
        // consumed this tick - the resolution subtracts that impulse and re-applies it on deck
        // axes, and an off-by-one-tick sample would leave a world-frame residual.
        ShipFrameTravel.clientFlyIntent = DeckLook::flyIntentFor;
    }

    /** The held deck heading for {@code entity}, or {@code null} when this client does not own
     *  its look (not the local player, or the deck look is not engaged). */
    private static Float heldDeckYawFor(net.minecraft.entity.EntityLivingBase entity) {
        if (!active || entity == null || entity != Minecraft.getMinecraft().player) {
            return null;
        }
        return (float) deckYawDeg;
    }

    /** The local player's vertical fly intent (+1 ascend / -1 descend / 0), or {@code null} when
     *  this client does not own {@code entity}'s movement. */
    private static Integer flyIntentFor(net.minecraft.entity.EntityLivingBase entity) {
        net.minecraft.client.entity.EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (entity == null || player == null || entity != player || player.movementInput == null) {
            return null;
        }
        return (player.movementInput.jump ? 1 : 0) - (player.movementInput.sneak ? 1 : 0);
    }

    // ---- Client-observable state (read by the deck-look e2e through readStaticField). NOT
    // test-gated: harness child JVMs run without test mode, so a gated static is invisible to
    // the tests that pin this contract. ----

    /** Whether the deck-frame look currently owns the local player's aim. */
    public static volatile boolean active = false;
    /** The held look, in the DECK frame (degrees; pitch clamped to +/-90 like vanilla). */
    public static volatile double deckYawDeg = 0.0;
    public static volatile double deckPitchDeg = 0.0;
    /** The world yaw/pitch last derived from the deck look (what the fields were set to). */
    public static volatile double lastDerivedYawDeg = 0.0;
    public static volatile double lastDerivedPitchDeg = 0.0;
    /** How many times an EXTERNAL world-rotation write (teleport / PosLook) re-seeded the deck
     *  look. A steadily climbing count with no teleports names a writer fighting this class. */
    public static volatile long reseeds = 0;

    /** What this class last wrote into the player's world rotation. A mismatch on the next sync
     *  means someone else wrote the fields and the deck look must re-seed from them. NaN = never
     *  written (forces the first sync to seed). */
    private static float lastWrittenYaw = Float.NaN;
    private static float lastWrittenPitch = Float.NaN;

    /** Slack for the external-write comparison: our own writes round-trip through float exactly,
     *  so anything beyond noise is a foreign write. */
    private static final float EXTERNAL_WRITE_EPSILON = 1.0E-3F;

    /**
     * Mouse turn, in the deck frame. Applies vanilla's exact delta scaling and pitch clamp to the
     * DECK yaw/pitch, then derives the world rotation, and returns true so the caller cancels the
     * vanilla world-frame turn. Returns false - vanilla runs untouched - for anyone who is not the
     * local player resolved ABOARD a deck (a body standing on the OUTER hull keeps world-frame
     * semantics, its own world look included).
     */
    public static boolean turn(Entity player, float yawDelta, float pitchDelta) {
        if (!sync(player)) {
            return false;
        }
        deckYawDeg += yawDelta * 0.15D;
        deckPitchDeg = MathHelper.clamp((float) (deckPitchDeg - pitchDelta * 0.15D), -90.0F, 90.0F);
        derive(player, null);
        return true;
    }

    /** The anchor ship's attitude sampled this / previous client tick, for the render camera's
     *  per-frame slerp - the walking-crew analogue of the pilot's shipQuat/shipPrevQuat pair.
     *  Stepping the crew camera at the raw 20 Hz attitude instead is the tier-2 jitter (the
     *  ledgered "not smooth at 120 FPS" feel): a station-keeping ship always hunts a little, and
     *  an uninterpolated attitude shows at any frame rate. Null while not aboard. */
    private static volatile FreeFlightPhysics.Quat shipQuatCur = null;
    private static volatile FreeFlightPhysics.Quat shipQuatPrev = null;

    /** The sampled anchor attitude, slerped across the frame, or {@code null} when not aboard or
     *  not yet warmed up (first aboard tick). */
    public static FreeFlightPhysics.Quat slerpedShipQuat(float partialTicks) {
        FreeFlightPhysics.Quat cur = shipQuatCur;
        FreeFlightPhysics.Quat prev = shipQuatPrev;
        if (cur == null || prev == null) {
            return null;
        }
        return FreeFlightPhysics.slerp(prev, cur, partialTicks);
    }

    /** A fixed SUBSPACE reference point of the current capture episode (the deck spot the episode
     *  engaged at) and its per-tick world images - the "where the deck is" oracle the smoothness
     *  discriminators measure the body's RELATIVE motion against. Snapshotted once per episode so
     *  the reference itself never moves in the ship frame. */
    private static boolean refSet = false;
    private static double refSubX, refSubY, refSubZ;
    private static volatile double[] refWorldPrev = null;
    private static volatile double[] refWorldCur = null;

    /** The reference deck point's world position this frame (lerped between the tick samples), or
     *  {@code null} while not aboard / not warmed up. */
    public static double[] refWorldAt(float partialTicks) {
        double[] cur = refWorldCur;
        double[] prev = refWorldPrev;
        if (cur == null || prev == null) {
            return null;
        }
        return new double[]{
                prev[0] + (cur[0] - prev[0]) * partialTicks,
                prev[1] + (cur[1] - prev[1]) * partialTicks,
                prev[2] + (cur[2] - prev[2]) * partialTicks};
    }

    /**
     * Once per client tick: keep the world aim glued to the deck as the ship turns under a crew
     * member whose mouse is still. Runs with a GUI open too - the ship does not stop rolling while
     * he reads a chest. Also samples the anchor attitude for the camera's per-frame slerp.
     */
    public static void clientTick(Entity player) {
        if (sync(player)) {
            FreeFlightPhysics.Quat sampled = VSIntegration.shipAttitudeFor(player);
            if (sampled != null) {
                shipQuatPrev = shipQuatCur == null ? sampled : shipQuatCur;
                shipQuatCur = sampled;
            }
            String shipId = ShipFrameTravel.aboardShipId(player);
            if (!refSet) {
                double[] sub = VSIntegration.toShipFrameFor(
                        player.world, shipId, player.posX, player.posY, player.posZ);
                if (sub != null) {
                    refSubX = sub[0];
                    refSubY = sub[1];
                    refSubZ = sub[2];
                    refSet = true;
                }
            }
            if (refSet) {
                double[] refWorld = VSIntegration.toWorldFrameFor(
                        player.world, shipId, refSubX, refSubY, refSubZ);
                if (refWorld != null) {
                    refWorldPrev = refWorldCur == null ? refWorld : refWorldCur;
                    refWorldCur = refWorld;
                }
            }
            derive(player, null);
        } else {
            shipQuatPrev = null;
            shipQuatCur = null;
            refSet = false;
            refWorldPrev = null;
            refWorldCur = null;
        }
    }

    /**
     * Once per rendered frame, from the camera path, with the SAME ship attitude the camera
     * composes this frame - so the crosshair ray (world fields) and the rendered view are one
     * rotation with no sub-tick skew.
     */
    public static void frame(Entity player, FreeFlightPhysics.Quat shipQuat) {
        if (sync(player)) {
            derive(player, shipQuat);
        }
    }

    /** The held deck look as a roll-free quaternion; the camera composes the ship attitude with
     *  exactly this. */
    public static FreeFlightPhysics.Quat lookQuat() {
        return FreeFlightPhysics.lookQuat(deckYawDeg, deckPitchDeg);
    }

    /**
     * Keep the held state honest against the movement truth and against foreign rotation writes.
     * Returns true when the deck look is (now) active and safe to use; false hands the caller back
     * to vanilla.
     */
    private static boolean sync(Entity player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (player == null || player != mc.player || player.world == null
                || !ShipFrameTravel.isResolvingAboard(player)) {
            active = false;
            return false;
        }
        if (active
                && Math.abs(player.rotationYaw - lastWrittenYaw) <= EXTERNAL_WRITE_EPSILON
                && Math.abs(player.rotationPitch - lastWrittenPitch) <= EXTERNAL_WRITE_EPSILON) {
            return true;
        }
        // Seed (fresh capture), or re-seed (a teleport / PosLook re-aimed the player in WORLD
        // terms): the deck look becomes the current world aim mapped into the deck, so the
        // hand-off is invisible - the player keeps looking exactly where he was looking.
        String shipId = ShipFrameTravel.aboardShipId(player);
        double[] fwd = lookVec(player.rotationYaw, player.rotationPitch);
        double[] deck = VSIntegration.rotateToShipFrameFor(
                player.world, shipId, fwd[0], fwd[1], fwd[2]);
        if (deck == null) {
            active = false; // ship transform unavailable this instant; vanilla owns the turn
            return false;
        }
        if (active) {
            reseeds++;
        }
        // Along the deck normal the yaw is degenerate; keep the previous heading rather than
        // snapping it to an arbitrary one (vanilla keeps yaw at pitch +/-90 the same way).
        if (Math.sqrt(deck[0] * deck[0] + deck[2] * deck[2]) >= 1.0E-4) {
            deckYawDeg = FreeFlightPhysics.yawFromForwardDeg(deck[0], deck[1], deck[2]);
        }
        deckPitchDeg = Math.toDegrees(Math.asin(clampUnit(-deck[1])));
        lastWrittenYaw = player.rotationYaw;
        lastWrittenPitch = player.rotationPitch;
        active = true;
        return true;
    }

    /**
     * Express the held deck look as the player's world yaw/pitch THIS instant - through
     * {@code shipQuat} when the camera supplies the frame's quat, else through the anchor ship's
     * own transform (the movement's rotation source; the two are one rotation, pinned by the
     * transform-consistency guard). The world fields stay the single wire/interaction truth -
     * only their VALUES now follow the deck.
     */
    private static void derive(Entity player, FreeFlightPhysics.Quat shipQuat) {
        double[] fwd = lookVec((float) deckYawDeg, (float) deckPitchDeg);
        double[] w = shipQuat != null
                ? shipQuat.rotate(fwd[0], fwd[1], fwd[2])
                : VSIntegration.rotateToWorldFrameFor(player.world,
                        ShipFrameTravel.aboardShipId(player), fwd[0], fwd[1], fwd[2]);
        if (w == null) {
            return; // ship went away mid-tick; the next sync releases to vanilla
        }
        float pitch = (float) Math.toDegrees(Math.asin(clampUnit(-w[1])));
        float yaw = player.rotationYaw;
        if (Math.sqrt(w[0] * w[0] + w[2] * w[2]) >= 1.0E-4) {
            yaw = (float) Math.toDegrees(Math.atan2(-w[0], w[2]));
            // rotationYaw is continuous across full turns; land on the nearest equivalent angle
            // so the body-yaw chase and anything differencing yaw never sees a 360 jump.
            yaw += 360.0F * Math.round((player.rotationYaw - yaw) / 360.0F);
        }
        // Vanilla-turn semantics: the change is instant (prev shifted by the same delta);
        // smoothness comes from deriving every frame, not from interpolating stale fields.
        player.prevRotationYaw += yaw - player.rotationYaw;
        player.prevRotationPitch += pitch - player.rotationPitch;
        player.rotationYaw = yaw;
        player.rotationPitch = pitch;
        lastWrittenYaw = yaw;
        lastWrittenPitch = pitch;
        lastDerivedYawDeg = yaw;
        lastDerivedPitchDeg = pitch;
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

    private static double clampUnit(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < -1.0) return -1.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
