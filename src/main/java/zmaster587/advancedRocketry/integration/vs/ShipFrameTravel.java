package zmaster587.advancedRocketry.integration.vs;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;

/**
 * Resolves one tick of an aboard living entity's movement in its SHIP's frame instead of the world's.
 *
 * <p>Minecraft moves a body on world axes: gravity is {@code -Y}, the vertical drag (0.98) differs from
 * the horizontal friction (0.91), the walking basis comes from yaw alone, "on the ground" means a
 * blocked {@code -Y} motion, and the collision box is axis-aligned by definition. Rotate the floor and
 * every one of those is wrong. Worst is the drag: the two constants give a 49x vertical and a 10x
 * horizontal terminal-velocity gain, so a deck-down pull with real world X/Z components is bent steeply
 * toward world {@code +Y} - the crew member is flung up a wall instead of settling on the deck.</p>
 *
 * <p>The ship's blocks, however, also exist unrotated and axis-aligned in the ship's own subspace. Map
 * the entity there and the deck is flat, "down" is plain {@code -Y}, and the box is deck-aligned for
 * free. Apply the ordinary rules, map the result back, and the world sees a body that stands, walks and
 * falls on a tilted floor.</p>
 *
 * <p>The ship-frame position is AUTHORITATIVE; the world position is derived from it through the ship
 * transform every tick. That single choice is what makes an entity ride a moving, rotating ship with no
 * separate "drag" step: when the transform changes, the derived world position follows. It is also
 * forced. The physics mod carries aboard entities itself, but only those it has associated with a ship
 * inside {@code Entity.move} - and an entity whose movement AR resolves never reaches that method, so
 * that carry is not available to us. Deriving the ship-frame position from the world position instead
 * would leave the body standing still in the world while the deck rotated out from under it.</p>

 * <p>The stored position is abandoned and re-seeded whenever something OTHER than this class moved the
 * entity in the world - a teleport, or the server applying a client's own movement packet.</p>
 *
 * <p>Deliberately narrow. {@link #handles} refuses water, lava, ladders, elytra, levitation, creative
 * flight and passengers; those keep world-frame semantics, and the caller must let vanilla run.
 * {@code doBlockCollisions} (cactus, cobweb, portals) is not replicated inside the deck frame.</p>
 */
public final class ShipFrameTravel {

    private ShipFrameTravel() {}

    /** Vanilla's living gravity, exactly ({@code EntityLivingBase.travel}). Using the true constant is
     *  what keeps deck gravity from leaking a world-down residual: AR's own 0.0755f offset does not
     *  cancel it, and the difference becomes a pure along-deck force on a rolled ship. */
    private static final double LIVING_GRAVITY = 0.08D;
    /** Vanilla's drag along the gravity axis, exactly. */
    private static final double GRAVITY_AXIS_DRAG = 0.9800000190734863D;
    /** Vanilla's in-plane friction while airborne, exactly. */
    private static final float AIR_FRICTION = 0.91F;
    /** Vanilla's magic normalisation of the friction-compensated move speed. */
    private static final float SPEED_NORMALISER = 0.16277136F;

    // ---- Diagnostics. A mixin that silently fails to apply looks exactly like a mixin that applied
    // and decided to do nothing, so the two must be told apart from outside the JVM.

    /** Ticks resolved in a ship frame since the game started. */
    public static volatile long resolvedTicks = 0L;
    /** Ticks where the hook ran, an entity was aboard, but the frame could not be resolved. */
    public static volatile long declinedTicks = 0L;
    /** How many times the external-move guard has dropped a capture. On a ROTATING ship the deck carries an
     *  aboard body faster than a tight guard tolerates, so it drops the capture every tick and the body
     *  loses the deck (the tier-2 fall-through). A rotating ship that does NOT thrash keeps this ~flat. */
    public static volatile long externalMoveDrops = 0L;
    /** Ship-frame obstacles the last resolved sweep saw. Zero on every tick means the deck's blocks
     *  are not being found, and an aboard body falls straight through it. */
    public static volatile int lastObstacleCount = -1;
    /** Whether the last resolved entity ended the tick standing on its deck. */
    public static volatile boolean lastOnDeck = false;
    /** Diagnostic: the last measured disagreement between the MOVEMENT frame (VS
     *  {@code ShipTransform.rotate}, what this class uses) and the CAMERA frame (the attitude quaternion) for
     *  the ship the last-resolved body is aboard. ~0 => movement and camera are one rotation (so "keys
     *  inverted" is NOT a frame-source split); a non-trivial value at a rolled attitude => they diverge.
     *  {@code -1} until first measured. */
    public static volatile double lastTcUpDisagreement = -1.0;
    public static volatile double lastTcFwdDisagreement = -1.0;
    /** Diagnostic: the WORLD Y of the last-resolved body's ship up-vector - i.e. how
     *  inverted its deck is (+1 upright, 0 on its side, -1 fully inverted). Lets a spin-to-inversion repro
     *  poll the attitude server-side and stop the spin at a target roll. {@code 2} until first measured. */
    public static volatile double lastShipUpY = 2.0;

    /**
     * Each aboard entity's authoritative position in its ship's frame, plus the world position this
     * class last derived from it. Weak keys: an entity that goes away takes its entry with it. The two
     * logical sides tick on different threads but hold different entity objects, so one map serves both;
     * it is synchronized only against that concurrency, never contended.
     */
    private static final Map<Entity, ShipFrameState> STATE =
            Collections.synchronizedMap(new WeakHashMap<Entity, ShipFrameState>());

    /** An aboard entity's authoritative position in its ship's frame (subspace). The world position is
     *  derived from it every tick and is not stored: the held/external-move check is done in the ship frame
     *  ({@link #heldShipFramePos}), where a body carried by a moving deck does not drift. */
    private static final class ShipFrameState {
        double localX, localY, localZ;
        /** The exact WORLD position this class last committed for the body (the value handed to
         *  {@code setPosition} / {@code setPositionAndUpdate}). Diagnostic-only input for the #32
         *  discriminator: the world distance the body has since moved FROM this point localises whether an
         *  external agent (a VS carry, or the server player's own travel) moved it - a carry-attitude
         *  mismatch, #32 candidate C - or it merely lagged AR's own transform by a tick (a converter-only
         *  residual a committed-world guard would absorb). Not read by the guard decision. */
        double worldX, worldY, worldZ;
    }

    /** How far (squared, in blocks, IN THE SHIP FRAME) the body may have drifted from the deck point we
     *  hold before we treat it as moved by someone ELSE - a real teleport - and re-derive. The comparison
     *  is done in SUBSPACE, not the world. A body standing still on a deck the ship is rotating or
     *  translating keeps the same subspace position while its WORLD position changes every tick as the deck
     *  carries it; measuring the world delta instead read that honest ship motion as an external teleport
     *  and dropped the capture every tick on a steeply-rolled ship, thrashing drop/re-capture until the body
     *  ratcheted off the deck and fell through it. The subspace delta is invariant under ship motion, so
     *  only a genuine teleport (or a server-applied movement packet ACROSS the deck) trips it. Travel
     *  rewrites the held point every tick, so a body this class owns never drifts on its own; the slack only
     *  absorbs the sub-block client/server reconciliation - which in subspace is about one tick of ship
     *  motion at the body's radius from the rotation centre, tiny at ordinary roll rates, so a far-from-centre
     *  pilot on a violently spinning ship is the one case where it could still approach the slack. 1e-6
     *  (~1mm) was too tight (ordinary reconciliation read as an external move); 0.2 block holds a
     *  freshly-captured dismounted pilot while still releasing on a genuine multi-tenth teleport. */
    private static final double EXTERNAL_MOVE_EPSILON_SQ = 0.04;
    /** {@code sqrt(EXTERNAL_MOVE_EPSILON_SQ)} - the static-reconciliation slack, in blocks. */
    private static final double EXTERNAL_MOVE_EPSILON = 0.2;
    /** One tick, in seconds - turns the deck's carry velocity into a per-tick displacement. */
    private static final double TICK_SECONDS = 0.05;
    /** How many ticks of the deck's own carry to tolerate ON TOP of the static epsilon before treating a
     *  move as external. On a ROTATING ship the deck carries an aboard body every tick, and the
     *  main-thread/physics-thread transform discrepancy makes that carry read as a subspace drift; without
     *  this the guard drops the capture every tick and the body loses the deck (the inverted/spinning
     *  fall-through). Generous, because the discrepancy can span a couple of ticks; a genuine teleport is
     *  far larger AND not explained by the deck's rotation, so it still trips. */
    private static final double DECK_CARRY_MARGIN = 3.0;

    /**
     * Whether this entity's movement should be resolved in its ship's frame this tick. Kept as one
     * function because two callers must agree exactly: {@code travel} (which then owns gravity) and
     * {@link zmaster587.advancedRocketry.util.GravityHandler} (which must NOT also apply a world-frame
     * deck-gravity delta to the same entity, or the pull is counted twice).
     */
    public static boolean handles(EntityLivingBase entity) {
        if (entity == null || entity.world == null || !VSIntegration.isAvailable()) {
            return false;
        }
        // Vanilla's own gate on travel(): an entity whose movement this side does not simulate (a mob
        // the client only interpolates) must be left alone. The gravity hook consults this method too,
        // so it has to know - otherwise gravity is handed over for a tick that never resolves.
        if (!entity.isServerWorld() && !entity.canPassengerSteer()) {
            return false;
        }
        if (entity.hasNoGravity() || entity.isRiding() || entity.isElytraFlying()) {
            return false;
        }
        if (entity.isInWater() || entity.isInLava() || entity.isOnLadder()) {
            return false;
        }
        if (entity.isPotionActive(MobEffects.LEVITATION)) {
            return false;
        }
        if (entity instanceof EntityPlayer && ((EntityPlayer) entity).capabilities.isFlying) {
            return false;
        }
        if (VSIntegration.shipAttitudeAt(entity.world, entity.posX, entity.posY, entity.posZ) == null) {
            if (STATE.remove(entity) != null) { // left every ship's box - definitely not aboard any more
                logDrop(entity, "leftShipBox");
            }
            return false;
        }
        // Aboard by containment - but "inside the ship's box" and "standing ON the ship" are different.
        // A ship's world bounding box is axis-aligned and, for a ship resting on the ground, it OVERLAPS
        // the terrain around AND beneath it, and even its own deck sits close above that terrain. The
        // question is not "is there world ground nearby" - near a grounded ship there always is, both
        // under the deck and beside it. The question is what the entity is standing ON:
        //
        //   - on a SHIP block (the deck): resolve in the ship frame, where that deck is axis-aligned;
        //   - on WORLD terrain (the ground beside/under the ship): leave it to vanilla, which collides
        //     that terrain correctly;
        //   - in the air over the ship: keep whichever frame already owns it.
        //
        // The earlier "decline if world ground is under the feet" got the grounded case exactly wrong:
        // a body on the deck of a ship sitting on the ground has world ground close below, so it was
        // handed to vanilla, which does not see the subspace deck and dropped the body through it (the
        // playtest report: "fell through the deck I'm standing on"). Test support against the SHIP, not
        // the absence of world ground.
        if (STATE.containsKey(entity)) {
            // Already resolving in the ship frame. Stay - a body mid-jump or mid-step is momentarily
            // unsupported yet has not left the ship - UNLESS it is now standing on real world ground and
            // not on the ship: it stepped off the deck onto terrain, so hand it straight back to vanilla.
            if (isSupportedByWorldTerrain(entity) && !isSupportedByShip(entity)) {
                if (STATE.remove(entity) != null) {
                    logDrop(entity, "steppedOntoTerrain");
                }
                return false;
            }
            return true;
        }
        // First contact: capture only a body actually standing on the ship - a ship block directly under
        // its feet in the ship's own frame.
        return isSupportedByShip(entity);
    }

    /**
     * Force a ship-frame capture for {@code entity} onto an explicit SHIP-FRAME (subspace) deck point,
     * snapping the body there and holding it. MUST be called on the side that OWNS the body's movement -
     * for a player that is the CLIENT (its own {@code EntityPlayerSP.travel}). The world position the body
     * is snapped to and the stored subspace anchor are both computed HERE, on this side, from the same
     * subspace point through this side's own ship transform, so the body sits exactly on its held deck point
     * and {@link #heldShipFramePos}'s external-move guard - measured in the ship frame - reads no drift. The
     * deck point travels as a SUBSPACE triple in a packet, never a world position: the client maps it
     * through its OWN transform, keeping the snapped body and its stored anchor consistent on the side that
     * owns the movement. The travel then keeps the body on the deck across ticks. Returns false off a loaded
     * ship. Idempotent enough to re-send: pair with an {@link #isResolving} check at the call site so a
     * re-seed after the capture already took is skipped (no repeated teleport).
     */
    public static boolean seedShipFrameCapture(Entity entity, double subX, double subY, double subZ) {
        if (entity == null) {
            return false;
        }
        double[] world = VSIntegration.toWorldFrame(entity, subX, subY, subZ);
        if (world == null) {
            // Playtest trace ([FF-TRACE/CAP], -Dadvancedrocketry.tests=true): the deck point could not be
            // mapped to the world because the entity is aboard no loaded ship by containment - i.e. it was
            // ejected off the ship's world AABB (the inverted-deck fall-through). No-op in normal play.
            if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()) {
                zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/CAP] seed FAILED "
                        + "(toWorldFrame null - not aboard a loaded ship) sub=(" + subX + "," + subY + ","
                        + subZ + ") entityPos=(" + entity.posX + "," + entity.posY + "," + entity.posZ + ")");
            }
            return false;
        }
        if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()) {
            zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/CAP] seed OK world=("
                    + world[0] + "," + world[1] + "," + world[2] + ")");
        }
        ShipFrameState state = new ShipFrameState();
        state.localX = subX;
        state.localY = subY;
        state.localZ = subZ;
        state.worldX = world[0];
        state.worldY = world[1];
        state.worldZ = world[2];
        STATE.put(entity, state);
        entity.setPositionAndUpdate(world[0], world[1], world[2]);
        entity.motionX = 0.0;
        entity.motionY = 0.0;
        entity.motionZ = 0.0;
        entity.fallDistance = 0.0f;
        return true;
    }

    /**
     * Whether this class is currently resolving {@code entity}'s movement in a ship frame - i.e. it is
     * captured and standing on a deck (its ship-frame position is held across ticks). Read-only.
     *
     * <p>This is the single "is on a deck" truth. The client deck camera gates on it so the view is
     * levelled to the deck ONLY for a body actually resolved on it - the same gate the movement uses -
     * rather than for any body merely inside the ship's world AABB. A ship's axis-aligned world box
     * overlaps a large air (and, when grounded, terrain) volume around the hull; gating the camera on
     * containment hijacks the view of anyone flying THROUGH that airspace without standing on the deck.</p>
     */
    public static boolean isResolving(Entity entity) {
        return entity != null && STATE.containsKey(entity);
    }

    /**
     * A read-only breakdown of the {@link #handles} decision for {@code entity} - every gate, the
     * ship-frame support obstacle count under the feet, and the final verdict - WITHOUT the state
     * re-seeding {@code handles} performs as a side effect. Fed to the {@code /artest vs deck-capture}
     * probe so a live playtest can see exactly WHY a body standing on a deck is or is not resolved in
     * the ship frame: not aboard at all, aboard-by-containment but with no solid block under the feet
     * in the ship's subspace (a partial capture that drops the body through the deck), or captured.
     */
    public static Map<String, Object> explainHandles(EntityLivingBase entity) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (entity == null || entity.world == null) {
            m.put("verdict", false);
            m.put("reason", "no entity/world");
            return m;
        }
        boolean available = VSIntegration.isAvailable();
        m.put("vsAvailable", available);
        m.put("isRemote", entity.world.isRemote);
        m.put("isServerWorld", entity.isServerWorld());
        m.put("canPassengerSteer", entity.canPassengerSteer());
        m.put("isRiding", entity.isRiding());
        m.put("isElytraFlying", entity.isElytraFlying());
        m.put("isFlying", entity instanceof EntityPlayer && ((EntityPlayer) entity).capabilities.isFlying);
        boolean aboard = VSIntegration.shipAttitudeAt(
                entity.world, entity.posX, entity.posY, entity.posZ) != null;
        m.put("aboardByContainment", aboard);
        boolean tracked = STATE.containsKey(entity);
        m.put("alreadyTracked", tracked);
        int shipObstacles = shipSupportObstacleCount(entity);
        m.put("shipFrameResolved", shipObstacles >= 0);
        m.put("shipSupportObstacles", shipObstacles);
        m.put("supportedByShip", shipObstacles > 0);
        m.put("supportedByWorldTerrain", isSupportedByWorldTerrain(entity));
        // The handles() verdict, replicated WITHOUT its STATE.remove side effects.
        boolean verdict;
        if (!available || (!entity.isServerWorld() && !entity.canPassengerSteer())
                || entity.hasNoGravity() || entity.isRiding() || entity.isElytraFlying()
                || entity.isInWater() || entity.isInLava() || entity.isOnLadder()
                || entity.isPotionActive(MobEffects.LEVITATION)
                || (entity instanceof EntityPlayer && ((EntityPlayer) entity).capabilities.isFlying)
                || !aboard) {
            verdict = false;
        } else if (tracked) {
            verdict = !(isSupportedByWorldTerrain(entity) && shipObstacles <= 0);
        } else {
            verdict = shipObstacles > 0;
        }
        m.put("verdict", verdict);
        return m;
    }

    /** Whether a SHIP block sits directly beneath the entity's feet, tested in the ship's frame (where
     *  the deck is axis-aligned). The probe reaches further for a fast faller so it is caught before it
     *  can tunnel through a thin deck in one tick. Ship blocks live in a subspace never at the entity's
     *  world position, so this is what tells "standing on the deck" from "standing on the ground". */
    private static boolean isSupportedByShip(Entity entity) {
        return shipSupportObstacleCount(entity) > 0;
    }

    /** How many SHIP-frame collision boxes sit directly beneath the entity's feet - the exact query
     *  {@link #isSupportedByShip} decides on - or {@code -1} when the entity maps to no loaded ship
     *  frame at all. Read-only. Split out so the deck-capture diagnostic can tell "no ship here"
     *  ({@code -1}) from "ship here but nothing solid under the feet" ({@code 0}, a partial capture
     *  that would drop the body through the deck) from "supported" ({@code > 0}). */
    private static int shipSupportObstacleCount(Entity entity) {
        double[] local = VSIntegration.toShipFrame(entity, entity.posX, entity.posY, entity.posZ);
        if (local == null) {
            return -1;
        }
        double reach = SUPPORT_PROBE;
        double[] motion = VSIntegration.rotateToShipFrame(entity,
                entity.motionX, entity.motionY, entity.motionZ);
        if (motion != null && motion[1] < 0.0) {
            reach += -motion[1];
        }
        double half = entity.width / 2.0;
        AxisAlignedBB underFeet = new AxisAlignedBB(
                local[0] - half, local[1] - reach, local[2] - half,
                local[0] + half, local[1], local[2] + half);
        return entity.world.getCollisionBoxes(entity, underFeet).size();
    }

    /** Whether solid WORLD collision sits directly beneath the entity's feet - it stepped off the deck
     *  onto real ground. Only a release condition now, never a capture one. */
    private static boolean isSupportedByWorldTerrain(Entity entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        AxisAlignedBB underFeet = new AxisAlignedBB(
                box.minX, box.minY - SUPPORT_PROBE, box.minZ,
                box.maxX, box.minY, box.maxZ);
        return !entity.world.getCollisionBoxes(entity, underFeet).isEmpty();
    }

    /** How far below the feet to look for a supporting block. Small, so a body genuinely airborne over a
     *  deck still resolves in the ship frame; extended by the fall speed for a fast faller. */
    private static final double SUPPORT_PROBE = 0.30;

    /** Deck tilt in degrees (deck up vs world up) for an already-resolved ship attitude, or {@code "n/a"}
     *  when the point maps to no loaded ship. The discriminator for whether a drop is attitude-dependent. */
    private static String tiltFrom(FreeFlightPhysics.Quat att) {
        if (att == null) {
            return "n/a";
        }
        double uy = att.rotate(0.0, 1.0, 0.0)[1];
        uy = uy < -1.0 ? -1.0 : (uy > 1.0 ? 1.0 : uy);
        return String.format(java.util.Locale.ROOT, "%.1f", Math.toDegrees(Math.acos(uy)));
    }

    /**
     * Emit one line when a body enters the ship frame by WALKING onto the deck (first-contact capture),
     * as opposed to the dismount packet path which logs its own {@code seed OK}/{@code seed FAILED}. Fired
     * once, on the untracked-&gt;tracked transition in {@link #remember}. Without it the walking-capture
     * path is untraced, so "seeded then lost" reads identically to "never seeded" in the log - the gap the
     * camera-while-walking symptom needs closed. Test-gated ({@code -Dadvancedrocketry.tests=true}).
     */
    private static void logCapture(Entity entity, double localX, double localY, double localZ) {
        if (!zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()
                || entity == null || entity.world == null) {
            return;
        }
        zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/CAP] auto-capture"
                + " remote=" + entity.world.isRemote
                + " shipObstacles=" + shipSupportObstacleCount(entity)
                + " tiltDeg=" + tiltFrom(VSIntegration.shipAttitudeAt(
                        entity.world, entity.posX, entity.posY, entity.posZ))
                + " local=(" + localX + "," + localY + "," + localZ + ")"
                + " pos=(" + entity.posX + "," + entity.posY + "," + entity.posZ + ")");
    }

    /**
     * Emit one line when a body that WAS resolved in the ship frame loses that capture. That drop is what
     * precedes an inverted-deck fall-through: once the ship frame stops owning the body, vanilla runs,
     * cannot see the subspace deck, and drops the body through it. Test-gated
     * ({@code -Dadvancedrocketry.tests=true}); fires at most once per capture episode - the callers guard
     * it on an actual {@code STATE} removal - so a live fall SELF-records WHICH gate dropped it and at what
     * attitude, with no {@code /artest} command to time by hand. The fields mirror the {@code vs
     * deck-capture} probe: {@code aboardByContainment}/{@code shipObstacles} localise the gate,
     * {@code tiltDeg} (deck up vs world up) confirms whether the drop is attitude-dependent.
     */
    private static void logDrop(Entity entity, String reason) {
        if (!zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()
                || entity == null || entity.world == null) {
            return;
        }
        FreeFlightPhysics.Quat att = VSIntegration.shipAttitudeAt(
                entity.world, entity.posX, entity.posY, entity.posZ);
        zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/DROP] " + reason
                + " remote=" + entity.world.isRemote
                + " aboardByContainment=" + (att != null)
                + " shipObstacles=" + shipSupportObstacleCount(entity)
                + " tiltDeg=" + tiltFrom(att)
                + " pos=(" + entity.posX + "," + entity.posY + "," + entity.posZ + ")"
                + " motionY=" + entity.motionY);
    }

    /**
     * Resolve {@code travel(strafe, vertical, forward)} in the entity's ship frame.
     *
     * @param jumpMovementFactor the entity's airborne move factor (protected in vanilla; the mixin
     *                           shadows it and passes it in)
     * @return true if the movement was fully handled and the vanilla body must be skipped
     */
    public static boolean travel(EntityLivingBase entity, float strafe, float vertical, float forward,
                                 float jumpMovementFactor) {
        if (!handles(entity)) {
            return false;
        }
        World world = entity.world;

        // The deck frame. Held across ticks, so the ship can rotate under a body that is standing
        // still ON it; re-seeded from the world whenever anything else has moved the entity there.
        double[] local = heldShipFramePos(entity);
        if (local == null) {
            local = VSIntegration.toShipFrame(entity, entity.posX, entity.posY, entity.posZ);
        }
        double[] motion = VSIntegration.rotateToShipFrame(entity,
                entity.motionX, entity.motionY, entity.motionZ);
        if (local == null || motion == null) {
            declinedTicks++;
            return false;
        }

        // "Standing" is the deck contact this class established last tick; nothing else writes
        // onGround for an entity whose move we own.
        boolean wasOnDeck = entity.onGround;

        // Friction of the block under the feet, sampled ALONG THE DECK NORMAL rather than world -Y.
        float friction = AIR_FRICTION;
        if (wasOnDeck) {
            BlockPos under = new BlockPos(local[0], local[1] - 1.0D, local[2]);
            IBlockState underState = world.getBlockState(under);
            friction = underState.getBlock().getSlipperiness(underState, world, under, entity) * AIR_FRICTION;
        }
        float speedFactor = SPEED_NORMALISER / (friction * friction * friction);
        float moveFactor = wasOnDeck ? entity.getAIMoveSpeed() * speedFactor : jumpMovementFactor;

        // Walking input, in the deck plane. The entity's yaw is a WORLD yaw; the direction he is
        // actually facing along the deck is his world look mapped into the ship frame.
        float deckYaw = deckYawDeg(entity);
        moveRelative(motion, strafe, vertical, forward, moveFactor, deckYaw);

        // Gravity toward the deck: plain -Y here, at vanilla's exact magnitude, BEFORE the sweep. This
        // is a deliberate deviation from vanilla's after-move ordering. Because this class re-derives
        // the ship-frame VELOCITY from the world velocity each tick, applying gravity after the sweep
        // leaves the deck-normal residual to be re-projected through a rotating transform, and during a
        // roll it briefly changes sign and drops the entity off the deck. Applying it first keeps the
        // motion fed into the sweep unambiguously deck-downward, which holds crew on a rolling deck.
        // The cost is a jump that rises one gravity step short of vanilla's - a fair trade
        // for a body that does not slide off when the ship turns.
        motion[1] -= LIVING_GRAVITY;

        // Sweep the deck-aligned box through the deck-aligned blocks.
        Sweep sweep = sweepShipFrame(world, entity, local, motion[0], motion[1], motion[2], wasOnDeck);

        boolean onDeck = sweep.collidedVertically && sweep.wantY < 0.0;
        if (sweep.collidedX) motion[0] = 0.0;
        if (sweep.collidedY) motion[1] = 0.0;
        if (sweep.collidedZ) motion[2] = 0.0;

        // Drag, in the deck frame: 0.98 along the deck normal, `friction` in the deck plane - the same
        // two constants vanilla uses, now applied to the axes they were meant for. `friction` is the
        // PRE-move value, as in vanilla.
        motion[1] *= GRAVITY_AXIS_DRAG;
        motion[0] *= friction;
        motion[2] *= friction;

        // Commit: the deck-frame result, expressed back on world axes.
        double[] worldPos = VSIntegration.toWorldFrame(entity, sweep.x, sweep.y, sweep.z);
        double[] worldMotion = VSIntegration.rotateToWorldFrame(entity, motion[0], motion[1], motion[2]);
        if (worldPos == null || worldMotion == null) {
            declinedTicks++;
            return false; // the ship went away mid-tick; leave the entity untouched for vanilla
        }
        resolvedTicks++;
        lastObstacleCount = sweep.obstacleCount;
        lastOnDeck = onDeck;
        // Frame-consistency measurement: is the frame this class MOVES in (VS ShipTransform.rotate) the same rotation
        // the camera LEVELS to (the attitude quaternion)? Recorded from a body that is genuinely resolved on
        // the deck, so it is not confounded by "aboard by containment" edge cases. Diagnostic only.
        java.util.Map<String, Object> tc = VSIntegration.transformConsistency(entity);
        if (tc != null) {
            Object up = tc.get("upDisagreement");
            Object fw = tc.get("fwdDisagreement");
            if (up instanceof Number) lastTcUpDisagreement = ((Number) up).doubleValue();
            if (fw instanceof Number) lastTcFwdDisagreement = ((Number) fw).doubleValue();
            Object qw = tc.get("qw"), qx = tc.get("qx"), qy = tc.get("qy"), qz = tc.get("qz");
            if (qw instanceof Number && qx instanceof Number && qy instanceof Number && qz instanceof Number) {
                lastShipUpY = new FreeFlightPhysics.Quat(((Number) qw).doubleValue(),
                        ((Number) qx).doubleValue(), ((Number) qy).doubleValue(),
                        ((Number) qz).doubleValue()).rotate(0.0, 1.0, 0.0)[1];
            }
        }
        remember(entity, sweep.x, sweep.y, sweep.z, worldPos[0], worldPos[1], worldPos[2]);
        double fallenAlongDeck = sweep.wantY < 0.0 ? -(sweep.y - (sweep.startY)) : 0.0;
        entity.setPosition(worldPos[0], worldPos[1], worldPos[2]);
        entity.motionX = worldMotion[0];
        entity.motionY = worldMotion[1];
        entity.motionZ = worldMotion[2];
        entity.onGround = onDeck;
        entity.collidedHorizontally = sweep.collidedX || sweep.collidedZ;
        entity.collidedVertically = sweep.collidedVertically;
        entity.collided = entity.collidedHorizontally || entity.collidedVertically;

        updateFallState(world, entity, sweep, fallenAlongDeck, onDeck);
        updateLimbSwing(entity, sweep.x - local[0], sweep.z - local[2]);
        return true;
    }

    /** One tick of jump, along the deck's up rather than the world's. */
    public static boolean jump(EntityLivingBase entity, double jumpUpwardsMotion, double jumpBoost) {
        if (!handles(entity)) {
            return false;
        }
        double up = jumpUpwardsMotion + jumpBoost;
        double[] motion = VSIntegration.rotateToShipFrame(entity,
                entity.motionX, entity.motionY, entity.motionZ);
        if (motion == null) {
            return false;
        }
        motion[1] = up;
        if (entity.isSprinting()) {
            float rad = deckYawDeg(entity) * 0.017453292F;
            motion[0] -= MathHelper.sin(rad) * 0.2F;
            motion[2] += MathHelper.cos(rad) * 0.2F;
        }
        double[] worldMotion = VSIntegration.rotateToWorldFrame(entity, motion[0], motion[1], motion[2]);
        if (worldMotion == null) {
            return false;
        }
        entity.motionX = worldMotion[0];
        entity.motionY = worldMotion[1];
        entity.motionZ = worldMotion[2];
        entity.isAirBorne = true;
        net.minecraftforge.common.ForgeHooks.onLivingJump(entity);
        return true;
    }

    /**
     * The entity's held ship-frame position, or {@code null} when there is none to trust - either it has
     * never been aboard, or it has moved OFF its held deck point in the SHIP frame, which means someone else
     * moved it (a teleport, or the server applying a movement packet) and the frame must be re-derived from
     * where it now is. The drift is measured in subspace, not the world, so the deck carrying the body as
     * the ship rotates/translates is not mistaken for an external move.
     */
    private static double[] heldShipFramePos(Entity entity) {
        ShipFrameState state = STATE.get(entity);
        if (state == null) {
            return null;
        }
        double[] local = VSIntegration.toShipFrame(entity, entity.posX, entity.posY, entity.posZ);
        if (local == null) {
            // Near-unreachable defensive branch: handles() already dropped and returned false this tick if
            // the body maps to no loaded ship (shipAttitudeAt uses the SAME containment as toShipFrame), so
            // handles() - not this - is the ship-unloaded gate. Reached only on an async ship-unload between
            // handles() and here; hand back the held point and let travel() decline (rotateToShipFrame null).
            return new double[]{state.localX, state.localY, state.localZ};
        }
        double dx = local[0] - state.localX;
        double dy = local[1] - state.localY;
        double dz = local[2] - state.localZ;
        // Widen the guard by the deck's OWN carry at the body's point (the ship's world velocity there,
        // over one tick): on a rotating ship that carry can exceed the tight static epsilon and read as a
        // teleport, dropping the capture every tick until the body loses the deck. A static ship carries at
        // ~0, so this stays the tight epsilon; a genuine teleport is far beyond the deck's carry, so it
        // still trips.
        double allowed = EXTERNAL_MOVE_EPSILON;
        double[] shipVel = VSIntegration.shipVelocityAtPoint(
                entity.world, entity.posX, entity.posY, entity.posZ);
        if (shipVel != null) {
            double carry = Math.sqrt(shipVel[0] * shipVel[0] + shipVel[1] * shipVel[1]
                    + shipVel[2] * shipVel[2]) * TICK_SECONDS;
            allowed += DECK_CARRY_MARGIN * carry;
        }
        if (dx * dx + dy * dy + dz * dz > allowed * allowed) {
            externalMoveDrops++;
            if (STATE.remove(entity) != null) {
                // [FF-TRACE/DROP] #32 discriminator: worldMiss = how far an external agent moved the body in
                // the WORLD since our last commit. worldMiss ~0 while d2 is large => the body sat where we
                // left it and only AR's own transform lagged a tick (a committed-world guard would absorb
                // it, no VS-boundary change). worldMiss ~= sqrt(d2) => an external carry (VS / server travel)
                // moved it off its deck point at a different attitude (candidate C). Diagnostic only.
                double wmx = entity.posX - state.worldX;
                double wmy = entity.posY - state.worldY;
                double wmz = entity.posZ - state.worldZ;
                double worldMiss = Math.sqrt(wmx * wmx + wmy * wmy + wmz * wmz);
                logDrop(entity, "externalMove(sub) d2=" + (dx * dx + dy * dy + dz * dz)
                        + " worldMiss=" + worldMiss);
            }
            return null;
        }
        return new double[]{state.localX, state.localY, state.localZ};
    }

    private static void remember(Entity entity, double localX, double localY, double localZ,
                                 double worldX, double worldY, double worldZ) {
        boolean firstContact = !STATE.containsKey(entity);
        ShipFrameState state = new ShipFrameState();
        state.localX = localX;
        state.localY = localY;
        state.localZ = localZ;
        state.worldX = worldX;
        state.worldY = worldY;
        state.worldZ = worldZ;
        STATE.put(entity, state);
        if (firstContact) {
            logCapture(entity, localX, localY, localZ);
        }
    }

    /** The entity's facing, as a yaw in the ship frame: his world heading, rotated into that frame.
     *  YAW-ONLY (look pitch zeroed), exactly as the render body-yaw path does
     *  ({@code ShipFrameCamera.deckYawDeg}). A walk basis must not swing with look pitch: on a tilted deck
     *  the FULL look vector's ship-frame XZ heading DOES depend on pitch (world {@code +Y} leaks into ship
     *  X/Z under the rotation), so using {@code getLookVec()} the basis swung as the crew looked up/down and
     *  collapsed to one fixed heading when he looked along the deck normal - the natural pose walking an
     *  inverted deck, which read as inverted/rotated WASD. Vanilla walks by yaw alone for the same reason. */
    private static float deckYawDeg(EntityLivingBase entity) {
        float yawRad = entity.rotationYaw * 0.017453292F;
        double fx = -MathHelper.sin(yawRad);
        double fz = MathHelper.cos(yawRad);
        double[] deckLook = VSIntegration.rotateToShipFrame(entity, fx, 0.0, fz);
        if (deckLook == null) {
            return entity.rotationYaw;
        }
        return FreeFlightPhysics.yawFromForwardDeg(deckLook[0], deckLook[1], deckLook[2]);
    }

    /** Vanilla's moveRelative, about {@code yawDeg} instead of {@code rotationYaw}, in place. */
    private static void moveRelative(double[] motion, float strafe, float up, float forward,
                                     float friction, float yawDeg) {
        float mag = strafe * strafe + up * up + forward * forward;
        if (mag < 1.0E-4F) {
            return;
        }
        mag = MathHelper.sqrt(mag);
        if (mag < 1.0F) mag = 1.0F;
        mag = friction / mag;
        strafe *= mag;
        up *= mag;
        forward *= mag;
        float sin = MathHelper.sin(yawDeg * 0.017453292F);
        float cos = MathHelper.cos(yawDeg * 0.017453292F);
        motion[0] += strafe * cos - forward * sin;
        motion[1] += up;
        motion[2] += forward * cos + strafe * sin;
    }

    /** Result of a deck-frame collision sweep: the resolved feet position and what blocked it. */
    private static final class Sweep {
        double x, y, z;
        double startY;
        double wantY;
        int obstacleCount;
        boolean collidedX, collidedY, collidedZ, collidedVertically;
    }

    /**
     * Vanilla's axis-by-axis box sweep, run on the ship's blocks in the ship's frame - including the
     * step-up assist, without which a crew member could not walk over a single raised block on his own
     * deck. {@code World.getCollisionBoxes} takes the box as a parameter, independent of where the
     * entity actually is, which is what makes resolving in a foreign frame possible at all.
     */
    private static Sweep sweepShipFrame(World world, EntityLivingBase entity, double[] local,
                                        double wantX, double wantY, double wantZ, boolean wasOnDeck) {
        double halfWidth = entity.width / 2.0;
        AxisAlignedBB box = new AxisAlignedBB(
                local[0] - halfWidth, local[1], local[2] - halfWidth,
                local[0] + halfWidth, local[1] + entity.height, local[2] + halfWidth);

        List<AxisAlignedBB> obstacles = world.getCollisionBoxes(entity,
                box.expand(wantX, wantY, wantZ));

        double gotY = wantY;
        for (AxisAlignedBB obstacle : obstacles) {
            gotY = obstacle.calculateYOffset(box, gotY);
        }
        box = box.offset(0.0, gotY, 0.0);

        double gotX = wantX;
        for (AxisAlignedBB obstacle : obstacles) {
            gotX = obstacle.calculateXOffset(box, gotX);
        }
        box = box.offset(gotX, 0.0, 0.0);

        double gotZ = wantZ;
        for (AxisAlignedBB obstacle : obstacles) {
            gotZ = obstacle.calculateZOffset(box, gotZ);
        }
        box = box.offset(0.0, 0.0, gotZ);

        // Step assist: retry the horizontal move lifted by stepHeight and keep it if it gets further.
        boolean grounded = wasOnDeck || (gotY != wantY && wantY < 0.0);
        if (entity.stepHeight > 0.0F && grounded && (gotX != wantX || gotZ != wantZ)) {
            AxisAlignedBB stepped = new AxisAlignedBB(
                    local[0] - halfWidth, local[1], local[2] - halfWidth,
                    local[0] + halfWidth, local[1] + entity.height, local[2] + halfWidth);
            double stepY = entity.stepHeight;
            List<AxisAlignedBB> stepObstacles = world.getCollisionBoxes(entity,
                    stepped.expand(wantX, stepY, wantZ));

            for (AxisAlignedBB obstacle : stepObstacles) {
                stepY = obstacle.calculateYOffset(stepped, stepY);
            }
            stepped = stepped.offset(0.0, stepY, 0.0);

            double stepX = wantX;
            for (AxisAlignedBB obstacle : stepObstacles) {
                stepX = obstacle.calculateXOffset(stepped, stepX);
            }
            stepped = stepped.offset(stepX, 0.0, 0.0);

            double stepZ = wantZ;
            for (AxisAlignedBB obstacle : stepObstacles) {
                stepZ = obstacle.calculateZOffset(stepped, stepZ);
            }
            stepped = stepped.offset(0.0, 0.0, stepZ);

            // Settle back down onto whatever we stepped onto.
            double settle = -stepY;
            for (AxisAlignedBB obstacle : stepObstacles) {
                settle = obstacle.calculateYOffset(stepped, settle);
            }
            stepped = stepped.offset(0.0, settle, 0.0);

            if (stepX * stepX + stepZ * stepZ > gotX * gotX + gotZ * gotZ) {
                box = stepped;
                gotX = stepX;
                gotZ = stepZ;
                gotY = stepY + settle;
            }
        }

        Sweep out = new Sweep();
        out.obstacleCount = obstacles.size();
        out.startY = local[1];
        out.wantY = wantY;
        out.x = box.minX + halfWidth;
        out.y = box.minY;
        out.z = box.minZ + halfWidth;
        out.collidedX = gotX != wantX;
        out.collidedY = gotY != wantY;
        out.collidedZ = gotZ != wantZ;
        out.collidedVertically = out.collidedY;
        return out;
    }

    /**
     * Fall distance accumulates along the DECK normal, and landing is dispatched to the block that was
     * landed ON - sampled, like everything else here, in the ship's frame. Vanilla does this inside
     * {@code Entity.move}; a deck of hay must break a crew member's fall exactly as one on the ground
     * does, and farmland must be trampled.
     *
     * <p>{@code Block.onLanded} is deliberately NOT dispatched. Its default zeroes {@code motionY} and
     * a slime block negates it - both on WORLD axes, which on a rolled deck would push a body sideways.
     * The deck-frame sweep has already stopped the fall correctly.</p>
     */
    private static void updateFallState(World world, EntityLivingBase entity, Sweep sweep,
                                        double fallenAlongDeck, boolean onDeck) {
        if (onDeck) {
            if (entity.fallDistance > 0.0F) {
                BlockPos landedOn = new BlockPos(sweep.x, sweep.y - 0.20000000298023224D, sweep.z);
                world.getBlockState(landedOn).getBlock()
                        .onFallenUpon(world, landedOn, entity, entity.fallDistance);
            }
            entity.fallDistance = 0.0F;
        } else if (fallenAlongDeck > 0.0) {
            entity.fallDistance += (float) fallenAlongDeck;
        }
    }

    /** Vanilla's walk-animation bookkeeping, which lives outside the branch we cancelled. Driven by the
     *  along-DECK displacement (ship frame), not the world delta: a body held still on a moving/rotating
     *  deck has a non-zero world delta every tick (the deck carries it) but a zero deck displacement, so
     *  measuring in the world would run its legs while it stands still. */
    private static void updateLimbSwing(EntityLivingBase entity, double deckDx, double deckDz) {
        entity.prevLimbSwingAmount = entity.limbSwingAmount;
        float swing = MathHelper.sqrt(deckDx * deckDx + deckDz * deckDz) * 4.0F;
        if (swing > 1.0F) {
            swing = 1.0F;
        }
        entity.limbSwingAmount += (swing - entity.limbSwingAmount) * 0.4F;
        entity.limbSwing += entity.limbSwingAmount;
    }
}
