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
    /** Diagnostics for the sideways-drag discriminator: what the last resolved tick received - the
     *  walk inputs, the deck yaw the walk basis used, and the ship-frame lateral motion BEFORE the
     *  input was added. Lateral motion at zero input = an external motion writer; correct-magnitude
     *  motion at nonzero input off the look direction = a wrong walk basis. Read on either side's
     *  own JVM (a client e2e reads the CLIENT's values via the bot). */
    public static volatile float lastInStrafe = 0f;
    public static volatile float lastInForward = 0f;
    public static volatile float lastDeckYawDeg = 0f;
    public static volatile double lastMotionShipX = 0.0;
    public static volatile double lastMotionShipY = 0.0;
    public static volatile double lastMotionShipZ = 0.0;
    /** Throttle for the [FF-TRACE/WALK] line (test mode only). */
    private static int walkTraceTicks = 0;
    /** The reason of the most recent capture release on THIS side, or "" — lets a probe/e2e name
     *  which gate ended an episode without needing the (side-local) log stream. */
    public static volatile String lastDropReason = "";
    /** World-frame {@code Entity.move} requests applied raw to a resolved body on THIS side (the
     *  move-suppression path), and the shape of the most recent one ("type dx,dy,dz") — names who
     *  still pushes a resolved body through the world pipeline. */
    public static volatile long worldMoveApplies = 0L;
    public static volatile String lastWorldMove = "";
    /** Guard discriminators, updated every guard pass and frozen into {@code lastDrop*} at a drop.
     *  {@code frameMoved} = where the anchor transform NOW maps the held deck point minus where the
     *  last commit put it: the deck stepping under an UNMOVED body (a client transform snap, a
     *  hunting/freefalling ship) — drift the carry-widening was supposed to absorb. {@code entityMoved}
     *  = the body's world position minus the committed point: a genuine external mover (a teleport, a
     *  packet apply). World-frame VECTORS, so the direction names the writer (world-down = gravity-like;
     *  rotating = a transform hunt). {@code lastGuardAllowed}/{@code lastGuardCarry} expose what the
     *  widening actually computed — 0.2 with carry 0 on a visibly-moving ship means the velocity feed
     *  ({@code shipVelocityAtPointFor}) is blind on this side. */
    public static volatile double lastGuardFrameStep = 0.0;
    public static volatile double lastGuardAllowed = -1.0;
    public static volatile double lastGuardCarry = -1.0;
    public static volatile double lastDropFrameMovedX, lastDropFrameMovedY, lastDropFrameMovedZ;
    public static volatile double lastDropEntityMovedX, lastDropEntityMovedY, lastDropEntityMovedZ;
    public static volatile double lastDropAllowed = -1.0;
    /** How many times a resolved tick actually CLEARED the physics mod's own entity-to-ship
     *  association (its drag anchor) on this side. Nonzero proves the drag suppression engaged -
     *  i.e. the mod HAD armed its own mover on a body AR resolves (a boarding fall, a flight
     *  contact) and it was disarmed before it could fight the resolution. */
    public static volatile long dragSuppressions = 0L;

    /** Called by the move-suppression hook: a world-frame mover asked to displace a resolved body. */
    public static void noteWorldMove(String type, double x, double y, double z) {
        worldMoveApplies++;
        if (x * x + y * y + z * z > 1.0E-6) {
            lastWorldMove = type + " " + x + "," + y + "," + z;
        }
    }

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
        /** UUID string of the ANCHOR ship — the ship this capture episode was established on. Every
         *  transform of the episode resolves through it (any-attitude crew contract C2). Re-picking the
         *  ship by world-AABB containment mid-episode is forbidden: with several loaded ships whose
         *  grown boxes overlap, first-match flips between ships tick to tick and the held subspace
         *  anchor is then read through the WRONG transform. */
        String shipId;
        double localX, localY, localZ;
        /** The exact WORLD position this class last committed for the body (the value handed to
         *  {@code setPosition} / {@code setPositionAndUpdate}). Diagnostic-only input for the #32
         *  discriminator: the world distance the body has since moved FROM this point localises whether an
         *  external agent (a VS carry, or the server player's own travel) moved it - a carry-attitude
         *  mismatch, #32 candidate C - or it merely lagged AR's own transform by a tick (a converter-only
         *  residual a committed-world guard would absorb). Not read by the guard decision. */
        double worldX, worldY, worldZ;
        /** The deck-carry velocity (per tick, world frame) this class ADDED into the body's world
         *  motion at its last commit. The next tick subtracts EXACTLY this value to recover the
         *  ship-relative motion - subtracting a freshly-sampled carry instead leaks the frame's
         *  ACCELERATION (the per-tick carry delta) into the relative motion, and a violently
         *  slewing deck then slides its crew off by "inertia" the deck-static model must not have. */
        double carryX, carryY, carryZ;
        /** Capture mode (contract C11). {@code false} = ABOARD: deck semantics - gravity along the
         *  ship's down, the walk basis in the deck plane, the deck-levelled camera. {@code true} =
         *  HULL-STAND: the body is on the ship's OUTER (world-facing) surface, where no subspace
         *  floor exists beneath it - WORLD semantics (world gravity, world walk basis, own camera),
         *  with only the COLLISION resolved against the ship's subspace geometry so it stands on
         *  the hull as on terrain, rides the moving ship, and never tunnels. */
        boolean hullStand;
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
    /** How far (blocks) beyond the anchored ship's subspace claim/hull an aboard body may travel before
     *  the capture is released (contract C3/C4). Measured in SUBSPACE, so it is attitude-invariant and a
     *  jump/fall ABOVE the deck never exits it the way the old grown-world-AABB gate (`leftShipBox`)
     *  released a jumping body mid-air; and because a rigid transform preserves distances, a region-exit
     *  release always happens at least this far from every hull block, so vanilla never inherits a body
     *  overlapping subspace geometry it cannot see (the fall-through tunnel). Comfortably above a jump
     *  apex (~1.25) and ordinary knockback. */
    private static final double STAY_REGION_MARGIN = 4.0;

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
            release(entity, "notSimulated");
            return false;
        }
        // Excluded states keep world-frame semantics (contract C4). Each RELEASES an existing capture
        // explicitly: the old silent `return false` left stale STATE behind, so isResolving (the gate
        // for the deck camera, the FF HUD and the deck-frame mouse look) kept answering true through a
        // whole creative-flight/riding episode, and the capture eventually died mid-air far from where
        // the gate first disengaged.
        String excluded = excludedStateOf(entity);
        if (excluded != null) {
            release(entity, excluded);
            return false;
        }
        ShipFrameState state = STATE.get(entity);
        if (state != null) {
            // Anchored stay/release (contract C2-C4): the episode keeps talking to ITS ship. A body
            // mid-jump or mid-fall over the deck is momentarily unsupported yet has NOT left the ship -
            // the stay region is the ship's own subspace block region grown by STAY_REGION_MARGIN, so
            // vertical excursions above the deck never release the way the old grown-world-AABB
            // containment gate did.
            double[] local = VSIntegration.toShipFrameFor(
                    entity.world, state.shipId, entity.posX, entity.posY, entity.posZ);
            if (local == null) {
                release(entity, "shipUnloaded");
                return false;
            }
            AxisAlignedBB stay = VSIntegration.subspaceStayRegion(
                    entity.world, state.shipId, STAY_REGION_MARGIN);
            if (stay == null
                    || !stay.contains(new net.minecraft.util.math.Vec3d(local[0], local[1], local[2]))) {
                // Genuinely left the ship. By the margin, this release point is at least
                // STAY_REGION_MARGIN from every hull block (a rigid transform preserves distances), so
                // vanilla inherits a body clear of the subspace geometry it cannot see.
                release(entity, "leftShipRegion");
                return false;
            }
            // Stepped off the deck onto real world ground: hand it straight back to vanilla, which
            // collides that terrain correctly. (Deliberate world-frame release-to-vanilla test.)
            if (isSupportedByWorldTerrain(entity) && !isSupportedByShipFor(entity, state.shipId)) {
                release(entity, "steppedOntoTerrain");
                return false;
            }
            if (state.hullStand) {
                // HULL-STAND liveness (C11). A standing deck below means the body reached a surface
                // that IS a deck in the ship frame (a hatch entry, or a hull region that reads as a
                // subspace top face at this attitude): hand over to ABOARD semantics - deck gravity,
                // deck camera. Losing hull contact (walked off the hull edge, the ship rotated away)
                // hands the body back to vanilla mid-air.
                if (shipSupportObstacleCountFor(entity, state.shipId) > 0) {
                    state.hullStand = false;
                    logCapture(entity, state.shipId, state.localX, state.localY, state.localZ);
                    return true;
                }
                if (!hullContactFor(entity, state.shipId)) {
                    release(entity, "noHullContact");
                    return false;
                }
                return true;
            }
            // C11: no subspace floor within reach below the body means ship-frame gravity can never
            // seat it on a deck - it is on the OUTER hull (the world-facing surface of a
            // non-upright ship) or past the underside. World semantics own it there: transition to
            // HULL-STAND while the body still touches the hull, or release to vanilla when it does
            // not. A jump/fall over a deck always keeps its floor within reach and never trips
            // this; a hatch entry re-captures by first contact the moment a real deck is below.
            if (!hasDeckBelowFor(entity, state.shipId)) {
                if (hullContactFor(entity, state.shipId)) {
                    state.hullStand = true;
                    clearPersistedAnchor(entity); // hull-stand is world semantics; only ABOARD relogs
                    logCapture(entity, state.shipId, state.localX, state.localY, state.localZ);
                    return true;
                }
                release(entity, "noDeckBelow");
                return false;
            }
            return true;
        }
        // First contact (contract C1b): capture only a body actually standing on a ship's deck in that
        // ship's OWN frame - and NEVER one standing on world terrain. A ground position mapped through
        // a parked ship's transform can alias onto a subspace block (a walker beside a docked hull was
        // captured into a tilted derelict's frame in the round-9 playtest), so ship-support alone is
        // not a boarding test. The terrain veto costs only the sliver of a deck lying within the 0.3
        // probe of real ground (a carpet-thin grounded hull), where VS's own world collision holds the
        // body anyway.
        if (isSupportedByWorldTerrain(entity)) {
            return false;
        }
        String candidate = firstContactCandidate(entity);
        boolean hullStand = false;
        if (candidate == null) {
            // No deck under the body in any candidate's frame - but its box may still be meeting a
            // ship's OUTER hull (contract C11: the world-facing surface of a non-upright ship, or
            // any hull face a falling body is about to hit). Capture in HULL-STAND mode: world
            // kinematics, ship-geometry collision - the body lands on the hull instead of the
            // physics mod bouncing it off and dropping it through the skin.
            for (String shipId : VSIntegration.shipIdsAt(
                    entity.world, entity.posX, entity.posY, entity.posZ)) {
                if (hullContactFor(entity, shipId)) {
                    candidate = shipId;
                    hullStand = true;
                    break;
                }
            }
        }
        if (candidate == null) {
            return false;
        }
        double[] local = VSIntegration.toShipFrameFor(
                entity.world, candidate, entity.posX, entity.posY, entity.posZ);
        double[] world = local == null ? null : VSIntegration.toWorldFrameFor(
                entity.world, candidate, local[0], local[1], local[2]);
        if (local == null || world == null) {
            return false;
        }
        // A first-contact body arrives with REAL world motion (a fall, a walk-on); its ship-relative
        // motion is that minus the deck's current carry.
        double[] shipVel = VSIntegration.shipVelocityAtPointFor(
                entity.world, candidate, entity.posX, entity.posY, entity.posZ);
        captureState(entity, candidate, local[0], local[1], local[2], world[0], world[1], world[2],
                shipVel == null ? 0.0 : shipVel[0] * TICK_SECONDS,
                shipVel == null ? 0.0 : shipVel[1] * TICK_SECONDS,
                shipVel == null ? 0.0 : shipVel[2] * TICK_SECONDS);
        STATE.get(entity).hullStand = hullStand;
        logCapture(entity, candidate, local[0], local[1], local[2]);
        return true;
    }

    /** Whether {@code entity} is in an excluded state (contract C4) — the public face of
     *  {@link #excludedStateOf} for seed SENDERS (the dismount deck-hold), which should stop
     *  re-sending a seed the receiving side will refuse. */
    public static boolean isExcludedFromCapture(EntityLivingBase entity) {
        return entity == null || excludedStateOf(entity) != null;
    }

    /** The excluded state keeping this body on world-frame semantics (contract C4), or {@code null}
     *  when none. ONE predicate for every consumer — {@link #handles} (which releases on it) and
     *  {@link #seedShipFrameCapture} (which must REFUSE to force-capture an excluded body: a seed
     *  that ignored creative flight snapped a flying player to the deck point every window tick,
     *  freezing him mid-air while handles() released him right back each tick — a per-tick war). */
    private static String excludedStateOf(EntityLivingBase entity) {
        if (entity.hasNoGravity() || entity.isRiding() || entity.isElytraFlying()) {
            return "excludedState";
        }
        if (entity.isInWater() || entity.isInLava() || entity.isOnLadder()) {
            return "excludedMedium";
        }
        if (entity.isPotionActive(MobEffects.LEVITATION)) {
            return "excludedLevitation";
        }
        if (entity instanceof EntityPlayer && ((EntityPlayer) entity).capabilities.isFlying) {
            return "creativeFlight";
        }
        return null;
    }

    /** The ship this body is standing on RIGHT NOW, chosen among every loaded ship whose grown world
     *  box contains it by testing deck support in each candidate's OWN frame - not by first-match
     *  containment, which flips between overlapping parked ships. Null when no candidate supports it. */
    private static String firstContactCandidate(EntityLivingBase entity) {
        for (String shipId : VSIntegration.shipIdsAt(
                entity.world, entity.posX, entity.posY, entity.posZ)) {
            if (shipSupportObstacleCountFor(entity, shipId) > 0) {
                return shipId;
            }
        }
        return null;
    }

    /** Install a fresh anchored capture for {@code entity} on ship {@code shipId}. The carry triple
     *  is the per-tick deck velocity the body's CURRENT world motion is considered to contain (0 for
     *  a seed, whose motion is zeroed; a fresh sample for a first contact, whose motion is real). */
    private static void captureState(Entity entity, String shipId, double localX, double localY,
                                     double localZ, double worldX, double worldY, double worldZ,
                                     double carryX, double carryY, double carryZ) {
        ShipFrameState state = new ShipFrameState();
        state.shipId = shipId;
        state.localX = localX;
        state.localY = localY;
        state.localZ = localZ;
        state.worldX = worldX;
        state.worldY = worldY;
        state.worldZ = worldZ;
        state.carryX = carryX;
        state.carryY = carryY;
        state.carryZ = carryZ;
        STATE.put(entity, state);
    }

    /** Remove the capture with an explicit, logged reason (contract C4). Every path that stops
     *  resolving a tracked body goes through here - a silent gate leaves stale STATE behind and the
     *  camera/HUD keep acting on it. No-op for an untracked body. */
    private static void release(Entity entity, String reason) {
        if (STATE.remove(entity) != null) {
            lastDropReason = reason;
            clearPersistedAnchor(entity);
            logDrop(entity, reason);
        }
    }

    /** NBT key of the persisted ABOARD anchor on a server player ({@code getEntityData}, which
     *  Forge saves with the player): the capture itself is in-memory only, so without this a
     *  relog hands the returning player to world gravity - on a non-upright ship world-down
     *  points away from the deck and he falls off before any first-contact gate can fire
     *  (any-attitude crew contract C14). Written per resolved aboard tick (the anchor must be
     *  where he STOOD at save time, not where the episode began); cleared on release and on the
     *  hull-stand transition. Read back by the login deck hold. */
    public static final String PERSISTED_ANCHOR_TAG = "advrocketry_deck_anchor";

    /** Refresh the persisted ABOARD anchor for a real server player. */
    private static void persistAnchor(Entity entity, String shipId,
                                      double localX, double localY, double localZ) {
        if (entity.world == null || entity.world.isRemote
                || !(entity instanceof net.minecraft.entity.player.EntityPlayerMP)
                || entity instanceof net.minecraftforge.common.util.FakePlayer) {
            return;
        }
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        tag.setString("ship", shipId);
        tag.setDouble("x", localX);
        tag.setDouble("y", localY);
        tag.setDouble("z", localZ);
        entity.getEntityData().setTag(PERSISTED_ANCHOR_TAG, tag);
    }

    /** Drop the persisted ABOARD anchor (release / hull-stand transition). */
    private static void clearPersistedAnchor(Entity entity) {
        if (entity.world == null || entity.world.isRemote
                || !(entity instanceof net.minecraft.entity.player.EntityPlayerMP)) {
            return;
        }
        entity.getEntityData().removeTag(PERSISTED_ANCHOR_TAG);
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
    public static boolean seedShipFrameCapture(Entity entity, String shipId,
                                               double subX, double subY, double subZ) {
        if (entity == null || shipId == null) {
            return false;
        }
        // NEVER force-capture a body in an excluded state (contract C4): handles() would release it
        // right back next tick, and the re-sent seed then snaps it to the deck point again - a
        // per-tick teleport war that froze a creative-FLYING ex-pilot mid-air at the seat column.
        // Refuse; the sender's window keeps trying and expires harmlessly if the state persists.
        if (entity instanceof EntityLivingBase) {
            String excluded = excludedStateOf((EntityLivingBase) entity);
            if (excluded != null) {
                if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()) {
                    zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/CAP] seed "
                            + "REFUSED (" + excluded + ") remote=" + entity.world.isRemote
                            + " id=" + entity.getEntityId() + " ship=" + shipId);
                }
                return false;
            }
        }
        // Anchored (contract C2): the seed names its ship explicitly - the server resolved it
        // unambiguously from the SUBSPACE seat block (claims of distinct ships never overlap), so the
        // client never has to guess by containment among overlapping world boxes.
        double[] world = VSIntegration.toWorldFrameFor(entity.world, shipId, subX, subY, subZ);
        if (world == null) {
            // Playtest trace ([FF-TRACE/CAP], -Dadvancedrocketry.tests=true): the anchor ship is not
            // loaded on this side (yet). No-op; the dismount window re-sends.
            if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()) {
                zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/CAP] seed FAILED "
                        + "(anchor ship not loaded) ship=" + shipId + " sub=(" + subX + "," + subY + ","
                        + subZ + ") entityPos=(" + entity.posX + "," + entity.posY + "," + entity.posZ + ")");
            }
            return false;
        }
        if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()) {
            zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/CAP] seed OK ship="
                    + shipId + " world=(" + world[0] + "," + world[1] + "," + world[2] + ")");
        }
        // Motion is zeroed below = "at rest RELATIVE TO THE DECK"; the carry the zeroed motion is
        // considered to contain is therefore zero too.
        captureState(entity, shipId, subX, subY, subZ, world[0], world[1], world[2], 0.0, 0.0, 0.0);
        entity.setPositionAndUpdate(world[0], world[1], world[2]);
        entity.motionX = 0.0;
        entity.motionY = 0.0;
        entity.motionZ = 0.0;
        entity.fallDistance = 0.0f;
        // The capture supersedes the physics mod's own drag anchor (often freshly armed by the very
        // contact that led here); disarm it or it fights the resolution from a stale point.
        if (VSIntegration.suppressShipDrag(entity)) {
            dragSuppressions++;
        }
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

    /** The anchored ship's UP axis in world coordinates for an ABOARD body, or {@code null} when
     *  the body is not aboard (never captured, or held in HULL-STAND mode - whose semantics,
     *  including the eye, are the world's). This is the axis the aboard EYE sits along: the
     *  renderer already offsets the camera along it ({@code MixinEntityRendererShipEye}), and the
     *  raytrace must originate from the SAME point or the crosshair picks a block the camera is
     *  not looking at (contract C10). */
    public static double[] aboardShipUpWorld(Entity entity) {
        if (entity == null) {
            return null;
        }
        ShipFrameState state = STATE.get(entity);
        if (state == null || state.hullStand) {
            return null;
        }
        return VSIntegration.rotateToWorldFrameFor(entity.world, state.shipId, 0.0, 1.0, 0.0);
    }

    /** Whether this class resolves {@code entity} in ABOARD (deck) mode specifically. The
     *  deck-levelled camera, the deck mouse basis and every other "this body lives in the deck's
     *  frame" consumer gate on THIS - a HULL-STAND body (contract C11) keeps its own world-frame
     *  view and look while only its collision is resolved against the ship. Movement-ownership
     *  consumers (the move-suppression hook, gravity) keep gating on {@link #isResolving}. */
    public static boolean isResolvingAboard(Entity entity) {
        if (entity == null) {
            return false;
        }
        ShipFrameState state = STATE.get(entity);
        return state != null && !state.hullStand;
    }

    /** The ANCHOR ship id this class resolves {@code entity} against in ABOARD (deck) mode, or
     *  {@code null} when it is not aboard (never captured, or held in HULL-STAND mode). The
     *  deck-frame look derives the crew member's world aim through THIS ship - the capture
     *  anchor - never by re-picking a ship from world-AABB containment mid-episode. */
    public static String aboardShipId(Entity entity) {
        if (entity == null) {
            return null;
        }
        ShipFrameState state = STATE.get(entity);
        return state == null || state.hullStand ? null : state.shipId;
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
        ShipFrameState state = STATE.get(entity);
        boolean tracked = state != null;
        m.put("alreadyTracked", tracked);
        m.put("anchorShipId", tracked ? state.shipId : null);
        m.put("hullStand", tracked && state.hullStand);
        boolean terrain = isSupportedByWorldTerrain(entity);
        m.put("supportedByWorldTerrain", terrain);
        // The handles() verdict, replicated WITHOUT its capture/release side effects.
        boolean gated = !available || (!entity.isServerWorld() && !entity.canPassengerSteer())
                || entity.hasNoGravity() || entity.isRiding() || entity.isElytraFlying()
                || entity.isInWater() || entity.isInLava() || entity.isOnLadder()
                || entity.isPotionActive(MobEffects.LEVITATION)
                || (entity instanceof EntityPlayer && ((EntityPlayer) entity).capabilities.isFlying);
        boolean verdict;
        if (gated) {
            int shipObstacles = shipSupportObstacleCount(entity);
            m.put("shipFrameResolved", shipObstacles >= 0);
            m.put("shipSupportObstacles", shipObstacles);
            m.put("supportedByShip", shipObstacles > 0);
            verdict = false;
        } else if (tracked) {
            int shipObstacles = shipSupportObstacleCountFor(entity, state.shipId);
            m.put("shipFrameResolved", shipObstacles >= 0);
            m.put("shipSupportObstacles", shipObstacles);
            m.put("supportedByShip", shipObstacles > 0);
            double[] local = VSIntegration.toShipFrameFor(
                    entity.world, state.shipId, entity.posX, entity.posY, entity.posZ);
            AxisAlignedBB stay = VSIntegration.subspaceStayRegion(
                    entity.world, state.shipId, STAY_REGION_MARGIN);
            boolean inRegion = local != null && stay != null
                    && stay.contains(new net.minecraft.util.math.Vec3d(local[0], local[1], local[2]));
            m.put("inStayRegion", inRegion);
            verdict = inRegion && !(terrain && shipObstacles <= 0);
        } else {
            String candidate = terrain ? null : firstContactCandidate(entity);
            m.put("firstContactCandidate", candidate);
            int shipObstacles = candidate != null
                    ? shipSupportObstacleCountFor(entity, candidate)
                    : shipSupportObstacleCount(entity);
            m.put("shipFrameResolved", shipObstacles >= 0);
            m.put("shipSupportObstacles", shipObstacles);
            m.put("supportedByShip", shipObstacles > 0);
            verdict = candidate != null;
        }
        m.put("verdict", verdict);
        return m;
    }

    /** Whether a SHIP block sits directly beneath the entity's feet, tested in the ANCHORED ship
     *  {@code shipId}'s frame (where the deck is axis-aligned) — the form every decision about a
     *  tracked body uses (contract C2). The probe reaches further for a fast faller so it is caught
     *  before it can tunnel through a thin deck in one tick. Ship blocks live in a subspace never at
     *  the entity's world position, so this is what tells "standing on the deck" from "standing on
     *  the ground". */
    private static boolean isSupportedByShipFor(Entity entity, String shipId) {
        return shipSupportObstacleCountFor(entity, shipId) > 0;
    }

    /** {@link #shipSupportObstacleCount}, resolved through the ship {@code shipId} instead of a
     *  containment lookup. {@code -1} when that ship is not loaded on this side.
     *
     *  <p>Counts only STANDING support - boxes whose TOP face is at/below the feet. A body that
     *  punched INTO the hull from outside (the world-top of an inverted ship, contract C11)
     *  intersects the probe with boxes whose top is ABOVE its feet; counting those as "support"
     *  captured the hull-top stander into a frame that can never seat him (no floor under him in
     *  subspace), and ship-frame gravity then flung him world-up off the hull - the #49 thrash. */
    private static int shipSupportObstacleCountFor(Entity entity, String shipId) {
        double[] local = VSIntegration.toShipFrameFor(
                entity.world, shipId, entity.posX, entity.posY, entity.posZ);
        if (local == null) {
            return -1;
        }
        double reach = SUPPORT_PROBE;
        double[] motion = VSIntegration.rotateToShipFrameFor(entity.world, shipId,
                entity.motionX, entity.motionY, entity.motionZ);
        if (motion != null && motion[1] < 0.0) {
            reach += -motion[1];
        }
        double half = entity.width / 2.0;
        AxisAlignedBB underFeet = new AxisAlignedBB(
                local[0] - half, local[1] - reach, local[2] - half,
                local[0] + half, local[1], local[2] + half);
        int standing = 0;
        for (AxisAlignedBB box : entity.world.getCollisionBoxes(entity, underFeet)) {
            if (box.maxY <= local[1] + STANDING_TOLERANCE) {
                standing++;
            }
        }
        return standing;
    }

    /** A support box's top may sit this far above the mapped feet and still count as STANDING on it
     *  - absorbs the ~1e-8 world<->subspace round-trip noise plus a de-penetration hair. Anything
     *  higher is the body INTERSECTING geometry, not standing on it. */
    private static final double STANDING_TOLERANCE = 0.05;
    /** How far below the feet (in the ship frame) a floor must exist for a capture to make sense.
     *  Comfortably above a jump apex (~1.25) and interior drops; a body with NO floor within this
     *  reach can never be seated on a deck by ship-frame gravity - it is on the outer hull or past
     *  the underside, where world-frame semantics own it (contract C11). */
    private static final double FLOOR_PROBE_DEPTH = 6.0;

    /** WORLD-down expressed in ship {@code shipId}'s frame, unit length - the gravity direction a
     *  HULL-STAND body falls along inside the subspace. Null when the transform is unavailable. */
    private static double[] worldDownInShipFrame(World world, String shipId) {
        double[] g = VSIntegration.rotateToShipFrameFor(world, shipId, 0.0, -1.0, 0.0);
        if (g == null) {
            return null;
        }
        double m = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        if (m < 1.0E-9) {
            return null;
        }
        return new double[]{g[0] / m, g[1] / m, g[2] / m};
    }

    /** Whether the body's box, moved by its RELATIVE motion this tick (plus a hair of slack),
     *  touches ship {@code shipId}'s subspace geometry - the world-frame analogue of the deck
     *  support probe: "is world gravity about to seat this body on the hull". Penetrating overlap
     *  counts: a fast faller a face deep into the hull is exactly who must be caught (the sweep
     *  then resolves the contact instead of the physics mod's bounce-and-tunnel). */
    private static boolean hullContactFor(Entity entity, String shipId) {
        double[] local = VSIntegration.toShipFrameFor(
                entity.world, shipId, entity.posX, entity.posY, entity.posZ);
        if (local == null) {
            return false;
        }
        double[] motion = VSIntegration.rotateToShipFrameFor(entity.world, shipId,
                entity.motionX, entity.motionY, entity.motionZ);
        double half = entity.width / 2.0;
        AxisAlignedBB box = new AxisAlignedBB(
                local[0] - half, local[1], local[2] - half,
                local[0] + half, local[1] + entity.height, local[2] + half);
        if (motion != null) {
            box = box.expand(motion[0], motion[1], motion[2]);
        }
        return !entity.world.getCollisionBoxes(entity, box.grow(0.05)).isEmpty();
    }

    /** Whether ANY standing floor exists within {@link #FLOOR_PROBE_DEPTH} below the body's feet in
     *  the anchored ship's frame. Returns true on a failed lookup - the unloaded-ship release is
     *  {@code handles()}'s own gate, not this one's. */
    private static boolean hasDeckBelowFor(Entity entity, String shipId) {
        double[] local = VSIntegration.toShipFrameFor(
                entity.world, shipId, entity.posX, entity.posY, entity.posZ);
        if (local == null) {
            return true;
        }
        double half = entity.width / 2.0;
        AxisAlignedBB column = new AxisAlignedBB(
                local[0] - half, local[1] - FLOOR_PROBE_DEPTH, local[2] - half,
                local[0] + half, local[1] + STANDING_TOLERANCE, local[2] + half);
        for (AxisAlignedBB box : entity.world.getCollisionBoxes(entity, column)) {
            if (box.maxY <= local[1] + STANDING_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /** How many SHIP-frame collision boxes sit directly beneath the entity's feet, resolved by
     *  world-AABB CONTAINMENT (first match) - or {@code -1} when the entity maps to no loaded ship
     *  frame at all. DIAGNOSTIC-ONLY since the anchored rework: every real decision goes through
     *  {@link #shipSupportObstacleCountFor}; this remains for the deck-capture probe and drop logs,
     *  where "no ship here" ({@code -1}) vs "ship here but nothing under the feet" ({@code 0}) vs
     *  "supported" ({@code > 0}) localises a failure. */
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
    private static void logCapture(Entity entity, String shipId, double localX, double localY,
                                   double localZ) {
        if (!zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()
                || entity == null || entity.world == null) {
            return;
        }
        zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/CAP] auto-capture"
                + " remote=" + entity.world.isRemote
                + " id=" + entity.getEntityId()
                + " ship=" + shipId
                + " shipObstacles=" + shipSupportObstacleCountFor(entity, shipId)
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
                + " id=" + entity.getEntityId()
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
        ShipFrameState anchored = STATE.get(entity);
        if (anchored == null) {
            declinedTicks++;
            return false; // heldShipFramePos may release below; the anchor itself must exist here
        }
        String shipId = anchored.shipId;

        if (anchored.hullStand) {
            return hullStandTravel(entity, anchored, strafe, vertical, forward, jumpMovementFactor);
        }

        // The deck frame. Held across ticks, so the ship can rotate under a body that is standing
        // still ON it; re-seeded from the world whenever anything else has moved the entity there.
        double[] local = heldShipFramePos(entity);
        if (local == null) {
            local = VSIntegration.toShipFrameFor(world, shipId, entity.posX, entity.posY, entity.posZ);
        }
        // The body's velocity RELATIVE to the ship. The world position of a resolved body is
        // derived from its ship-frame position every tick, so the ship's own carry is applied by
        // the transform - a ship-frame velocity that still CONTAINS the carry counts it twice. On a
        // static ship the two agree and the error is invisible (every early test); on a MOVING ship
        // an airborne body rockets away at the ship's own velocity (a jump on a climbing ship flung
        // the crew member out of the stay region), and a station-keeping ship's residual creep is a
        // constant no-input drag on the crew. Subtract EXACTLY the carry the last commit added
        // (held in STATE - a fresh sample would leak the frame's acceleration as inertia and slide
        // crew off a hard-slewing deck), and add a fresh carry back at this tick's commit.
        double[] motion = VSIntegration.rotateToShipFrameFor(world, shipId,
                entity.motionX - anchored.carryX,
                entity.motionY - anchored.carryY,
                entity.motionZ - anchored.carryZ);
        if (local == null || motion == null) {
            declinedTicks++;
            // A declined tick hands this body to VANILLA travel while the capture stays held:
            // vanilla applies world-frame gravity and moves the body world-down, and the NEXT
            // tick's guard then reads that as an external move (entityMoved = world-down). Trace
            // it (test-gated): an externalMove drop right after a DECLINE line names this path,
            // not a foreign mover, as the writer.
            if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()) {
                zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/DECLINE]"
                        + " remote=" + world.isRemote
                        + " id=" + entity.getEntityId()
                        + " ship=" + shipId
                        + " local=" + (local != null)
                        + " motion=" + (motion != null)
                        + " pos=(" + entity.posX + "," + entity.posY + "," + entity.posZ + ")");
            }
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
        float deckYaw = deckYawDeg(entity, shipId);
        // The sideways-drag discriminator: record what came INTO this tick (the walk inputs, the
        // deck yaw the walk basis uses, and the ship-frame motion BEFORE the input is added). A
        // constant lateral ship-frame motion at ZERO input names an external motion writer; a
        // correct-magnitude motion at NONZERO input pointing off the look direction names a wrong
        // walk basis. Statics so a client e2e reads them on the CLIENT JVM; the trace line
        // self-records a live playtest (test-gated, throttled).
        lastInStrafe = strafe;
        lastInForward = forward;
        lastDeckYawDeg = deckYaw;
        lastMotionShipX = motion[0];
        lastMotionShipY = motion[1];
        lastMotionShipZ = motion[2];
        if (zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration.isTestMode()
                && (walkTraceTicks++ % 10) == 0
                && (strafe != 0f || forward != 0f
                        || Math.abs(motion[0]) > 0.05 || Math.abs(motion[2]) > 0.05)) {
            zmaster587.advancedRocketry.AdvancedRocketry.logger.info("[FF-TRACE/WALK]"
                    + " remote=" + world.isRemote
                    + " id=" + entity.getEntityId()
                    + " strafe=" + strafe + " forward=" + forward
                    + " deckYaw=" + deckYaw + " worldYaw=" + entity.rotationYaw
                    + " motionShip=(" + motion[0] + "," + motion[1] + "," + motion[2] + ")"
                    + " worldMotion=(" + entity.motionX + "," + entity.motionY + ","
                    + entity.motionZ + ")");
        }
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

        // Commit: the deck-frame result, expressed back on world axes (through the ANCHOR ship).
        double[] worldPos = VSIntegration.toWorldFrameFor(world, shipId, sweep.x, sweep.y, sweep.z);
        double[] worldMotion = VSIntegration.rotateToWorldFrameFor(world, shipId,
                motion[0], motion[1], motion[2]);
        if (worldPos == null || worldMotion == null) {
            declinedTicks++;
            return false; // the ship went away mid-tick; leave the entity untouched for vanilla
        }
        resolvedTicks++;
        lastObstacleCount = sweep.obstacleCount;
        lastOnDeck = onDeck;
        // Re-add the deck's carry (freshly sampled for THIS commit; the value is remembered so the
        // next tick can subtract exactly it): entity.motion is a WORLD velocity, and the ship-frame
        // value above was ship-RELATIVE.
        double[] shipVel = VSIntegration.shipVelocityAtPointFor(
                world, shipId, worldPos[0], worldPos[1], worldPos[2]);
        double carryX = shipVel == null ? 0.0 : shipVel[0] * TICK_SECONDS;
        double carryY = shipVel == null ? 0.0 : shipVel[1] * TICK_SECONDS;
        double carryZ = shipVel == null ? 0.0 : shipVel[2] * TICK_SECONDS;
        worldMotion[0] += carryX;
        worldMotion[1] += carryY;
        worldMotion[2] += carryZ;
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
        remember(entity, shipId, sweep.x, sweep.y, sweep.z,
                worldPos[0], worldPos[1], worldPos[2], carryX, carryY, carryZ);
        // The relog anchor follows the body: persisted per resolved ABOARD tick so a save catches
        // the deck spot he is standing on NOW (contract C14), not the episode's first contact.
        persistAnchor(entity, shipId, sweep.x, sweep.y, sweep.z);
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
        // A resolved body must be invisible to the physics mod's own entity-drag: its anchor is fed
        // by the (suppressed) collision injector, so whatever it holds is stale, and its world-tick
        // mover otherwise undoes this commit (live: a constant pull toward a stale point, and the
        // walking thrash whose entityMoved exactly negated this commit's motion). Cleared every
        // resolved tick; a release hands the body back and the mod re-arms naturally on contact.
        if (VSIntegration.suppressShipDrag(entity)) {
            dragSuppressions++;
        }
        return true;
    }

    /**
     * One tick of HULL-STAND movement (contract C11): vanilla's OWN kinematics on world axes -
     * world gravity, world walk basis (the entity's own yaw), vanilla's drag constants on the
     * world's axes - with only the COLLISION resolved by the ship-frame sweep against the ship's
     * subspace geometry. The position stays subspace-authoritative (the body rides the moving
     * ship); the velocity follows the same held-carry rule as the aboard path, applied to the
     * world-frame relative motion.
     */
    private static boolean hullStandTravel(EntityLivingBase entity, ShipFrameState anchored,
                                           float strafe, float vertical, float forward,
                                           float jumpMovementFactor) {
        World world = entity.world;
        String shipId = anchored.shipId;
        double[] local = heldShipFramePos(entity);
        if (local == null) {
            local = VSIntegration.toShipFrameFor(world, shipId, entity.posX, entity.posY, entity.posZ);
        }
        double[] g = worldDownInShipFrame(world, shipId);
        if (local == null || g == null) {
            declinedTicks++;
            return false;
        }
        boolean wasGrounded = entity.onGround;

        // Friction of the block the body stands on - sampled one step along WORLD-down from the
        // feet, in the ship frame, because that is where its supporting hull block sits.
        float friction = AIR_FRICTION;
        if (wasGrounded) {
            BlockPos under = new BlockPos(local[0] + g[0] * 0.5, local[1] + g[1] * 0.5 - 0.5,
                    local[2] + g[2] * 0.5);
            IBlockState underState = world.getBlockState(under);
            friction = underState.getBlock().getSlipperiness(underState, world, under, entity)
                    * AIR_FRICTION;
        }
        float speedFactor = SPEED_NORMALISER / (friction * friction * friction);
        float moveFactor = wasGrounded ? entity.getAIMoveSpeed() * speedFactor : jumpMovementFactor;

        // World-frame RELATIVE kinematics: vanilla's own math on world axes (walk basis from the
        // entity's own world yaw), on the motion minus the HELD carry.
        double[] vWorld = {
                entity.motionX - anchored.carryX,
                entity.motionY - anchored.carryY,
                entity.motionZ - anchored.carryZ};
        moveRelative(vWorld, strafe, vertical, forward, moveFactor, entity.rotationYaw);
        vWorld[1] -= LIVING_GRAVITY; // world gravity, before the sweep (same ordering as aboard)

        double[] want = VSIntegration.rotateToShipFrameFor(world, shipId,
                vWorld[0], vWorld[1], vWorld[2]);
        if (want == null) {
            declinedTicks++;
            return false;
        }
        Sweep sweep = sweepShipFrame(world, entity, local, want[0], want[1], want[2], wasGrounded);
        double gotX = sweep.x - local[0], gotY = sweep.y - local[1], gotZ = sweep.z - local[2];
        // Grounded = the body wanted to move INTO world gravity and the hull clipped that component.
        double wantAlongG = want[0] * g[0] + want[1] * g[1] + want[2] * g[2];
        double gotAlongG = gotX * g[0] + gotY * g[1] + gotZ * g[2];
        boolean grounded = wantAlongG > 1.0E-7 && gotAlongG < wantAlongG - 1.0E-7;

        double[] clipped = {want[0], want[1], want[2]};
        if (sweep.collidedX) clipped[0] = 0.0;
        if (sweep.collidedY) clipped[1] = 0.0;
        if (sweep.collidedZ) clipped[2] = 0.0;
        double[] worldMotion = VSIntegration.rotateToWorldFrameFor(world, shipId,
                clipped[0], clipped[1], clipped[2]);
        double[] worldPos = VSIntegration.toWorldFrameFor(world, shipId, sweep.x, sweep.y, sweep.z);
        if (worldMotion == null || worldPos == null) {
            declinedTicks++;
            return false;
        }
        // Vanilla's drag, on the axes it was written for - the world's.
        worldMotion[1] *= GRAVITY_AXIS_DRAG;
        worldMotion[0] *= friction;
        worldMotion[2] *= friction;

        resolvedTicks++;
        lastObstacleCount = sweep.obstacleCount;
        lastOnDeck = grounded;
        double[] shipVel = VSIntegration.shipVelocityAtPointFor(
                world, shipId, worldPos[0], worldPos[1], worldPos[2]);
        double carryX = shipVel == null ? 0.0 : shipVel[0] * TICK_SECONDS;
        double carryY = shipVel == null ? 0.0 : shipVel[1] * TICK_SECONDS;
        double carryZ = shipVel == null ? 0.0 : shipVel[2] * TICK_SECONDS;
        remember(entity, shipId, sweep.x, sweep.y, sweep.z,
                worldPos[0], worldPos[1], worldPos[2], carryX, carryY, carryZ);
        ShipFrameState refreshed = STATE.get(entity);
        if (refreshed != null) {
            refreshed.hullStand = true; // remember() rebuilds the state; keep the mode
        }
        entity.setPosition(worldPos[0], worldPos[1], worldPos[2]);
        entity.motionX = worldMotion[0] + carryX;
        entity.motionY = worldMotion[1] + carryY;
        entity.motionZ = worldMotion[2] + carryZ;
        entity.onGround = grounded;
        entity.collidedHorizontally = sweep.collidedX || sweep.collidedZ;
        entity.collidedVertically = sweep.collidedY;
        entity.collided = entity.collidedHorizontally || entity.collidedVertically;

        // Fall accounting along WORLD-down; the landed-on block sits one step along it.
        if (grounded) {
            if (entity.fallDistance > 0.0F) {
                BlockPos landedOn = new BlockPos(
                        sweep.x + g[0] * 0.2, sweep.y + g[1] * 0.2 - 0.2, sweep.z + g[2] * 0.2);
                world.getBlockState(landedOn).getBlock()
                        .onFallenUpon(world, landedOn, entity, entity.fallDistance);
            }
            entity.fallDistance = 0.0F;
        } else if (gotAlongG > 0.0) {
            entity.fallDistance += (float) gotAlongG;
        }
        updateLimbSwing(entity, gotX, gotZ);
        if (VSIntegration.suppressShipDrag(entity)) {
            dragSuppressions++;
        }
        return true;
    }

    /** One tick of jump, along the deck's up rather than the world's. */
    public static boolean jump(EntityLivingBase entity, double jumpUpwardsMotion, double jumpBoost) {
        if (!handles(entity)) {
            return false;
        }
        ShipFrameState anchored = STATE.get(entity);
        if (anchored == null) {
            return false;
        }
        String shipId = anchored.shipId;
        double up = jumpUpwardsMotion + jumpBoost;
        if (anchored.hullStand) {
            // HULL-STAND (C11): the jump is vanilla's own - WORLD-up, sprint boost along the WORLD
            // yaw - applied to the relative motion under the same held-carry rule.
            double relX = entity.motionX - anchored.carryX;
            double relZ = entity.motionZ - anchored.carryZ;
            if (entity.isSprinting()) {
                float rad = entity.rotationYaw * 0.017453292F;
                relX -= MathHelper.sin(rad) * 0.2F;
                relZ += MathHelper.cos(rad) * 0.2F;
            }
            entity.motionX = relX + anchored.carryX;
            entity.motionY = up + anchored.carryY;
            entity.motionZ = relZ + anchored.carryZ;
            entity.isAirBorne = true;
            net.minecraftforge.common.ForgeHooks.onLivingJump(entity);
            return true;
        }
        // Ship-RELATIVE velocity, exactly as travel(): a jump is "up 0.42 relative to the deck".
        // Subtract and re-add the SAME held carry (state), leaving it for the next travel tick to
        // subtract again - a fresh sample here would double-book the carry against travel's.
        double[] motion = VSIntegration.rotateToShipFrameFor(entity.world, shipId,
                entity.motionX - anchored.carryX,
                entity.motionY - anchored.carryY,
                entity.motionZ - anchored.carryZ);
        if (motion == null) {
            return false;
        }
        motion[1] = up;
        if (entity.isSprinting()) {
            float rad = deckYawDeg(entity, shipId) * 0.017453292F;
            motion[0] -= MathHelper.sin(rad) * 0.2F;
            motion[2] += MathHelper.cos(rad) * 0.2F;
        }
        double[] worldMotion = VSIntegration.rotateToWorldFrameFor(entity.world, shipId,
                motion[0], motion[1], motion[2]);
        if (worldMotion == null) {
            return false;
        }
        entity.motionX = worldMotion[0] + anchored.carryX;
        entity.motionY = worldMotion[1] + anchored.carryY;
        entity.motionZ = worldMotion[2] + anchored.carryZ;
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
        double[] local = VSIntegration.toShipFrameFor(
                entity.world, state.shipId, entity.posX, entity.posY, entity.posZ);
        if (local == null) {
            // Near-unreachable defensive branch: handles() already released ("shipUnloaded") and returned
            // false this tick if the anchor ship is not loaded. Reached only on an async ship-unload
            // between handles() and here; hand back the held point and let travel() decline.
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
        double carrySeen = 0.0;
        double[] shipVel = VSIntegration.shipVelocityAtPointFor(
                entity.world, state.shipId, entity.posX, entity.posY, entity.posZ);
        if (shipVel != null) {
            carrySeen = Math.sqrt(shipVel[0] * shipVel[0] + shipVel[1] * shipVel[1]
                    + shipVel[2] * shipVel[2]) * TICK_SECONDS;
            allowed += DECK_CARRY_MARGIN * carrySeen;
        }
        // Discriminators (diagnostic only, no guard effect): split the measured drift into the two
        // possible writers. frameMoved = the CURRENT transform's image of the held deck point vs the
        // committed point — the deck stepped under an unmoved body (a network transform snap on the
        // client, a hunting or freefalling ship) and the widening above should have covered it.
        // entityMoved = the body's actual world position vs the committed point — someone moved the
        // BODY (a teleport, a packet apply, a stray world mover). Vectors, so direction names the writer.
        double[] heldWorldNow = VSIntegration.toWorldFrameFor(
                entity.world, state.shipId, state.localX, state.localY, state.localZ);
        double fmx = heldWorldNow == null ? 0.0 : heldWorldNow[0] - state.worldX;
        double fmy = heldWorldNow == null ? 0.0 : heldWorldNow[1] - state.worldY;
        double fmz = heldWorldNow == null ? 0.0 : heldWorldNow[2] - state.worldZ;
        double emx = entity.posX - state.worldX;
        double emy = entity.posY - state.worldY;
        double emz = entity.posZ - state.worldZ;
        lastGuardFrameStep = Math.sqrt(fmx * fmx + fmy * fmy + fmz * fmz);
        lastGuardAllowed = allowed;
        lastGuardCarry = carrySeen;
        if (dx * dx + dy * dy + dz * dz > allowed * allowed) {
            // A REAL player's movement is CLIENT-authoritative: the position the server sees each tick
            // IS the client's honest resolution arriving by packet, not a foreign teleport. Fighting it
            // (release + re-capture at the server's own point) locks the two sides' anchors a step apart
            // and wars over the body - vanilla then reads the server's losing ticks as airborne (the
            // rolled-deck onGround flap). The server-side resolution must FOLLOW the client: REBASE the
            // anchor onto the client's point and keep resolving there. Gating the server resolution OFF
            // entirely was tried and regressed (the server player fell by vanilla and dragged the client
            // down); following is the middle way - the server still resolves in the ship frame, it just
            // never argues with the packet stream about WHERE. Genuine leave-the-ship still releases via
            // the stay region / terrain gates in handles(). Non-player bodies (mobs, stands) keep the
            // guard: the resolving side OWNS their movement, so a large drift there really is an
            // external mover.
            if (!entity.world.isRemote
                    && entity instanceof net.minecraft.entity.player.EntityPlayerMP
                    && !(entity instanceof net.minecraftforge.common.util.FakePlayer)) {
                state.localX = local[0];
                state.localY = local[1];
                state.localZ = local[2];
                state.worldX = entity.posX;
                state.worldY = entity.posY;
                state.worldZ = entity.posZ;
                return local;
            }
            externalMoveDrops++;
            lastDropFrameMovedX = fmx;
            lastDropFrameMovedY = fmy;
            lastDropFrameMovedZ = fmz;
            lastDropEntityMovedX = emx;
            lastDropEntityMovedY = emy;
            lastDropEntityMovedZ = emz;
            lastDropAllowed = allowed;
            double worldMiss = Math.sqrt(emx * emx + emy * emy + emz * emz);
            release(entity, "externalMove(sub) d2=" + (dx * dx + dy * dy + dz * dz)
                    + " dSub=(" + dx + "," + dy + "," + dz + ")"
                    + " held=(" + state.localX + "," + state.localY + "," + state.localZ + ")"
                    + " worldMiss=" + worldMiss
                    + " frameMoved=(" + fmx + "," + fmy + "," + fmz + ")"
                    + " entityMoved=(" + emx + "," + emy + "," + emz + ")"
                    + " allowed=" + allowed + " carrySeen=" + carrySeen);
            return null;
        }
        return new double[]{state.localX, state.localY, state.localZ};
    }

    private static void remember(Entity entity, String shipId, double localX, double localY,
                                 double localZ, double worldX, double worldY, double worldZ,
                                 double carryX, double carryY, double carryZ) {
        boolean firstContact = !STATE.containsKey(entity);
        captureState(entity, shipId, localX, localY, localZ, worldX, worldY, worldZ,
                carryX, carryY, carryZ);
        if (firstContact) {
            // Normally the capture is installed by handles()/seed; reached only when heldShipFramePos
            // released mid-tick (externalMove) and this commit re-captures on the same anchor.
            logCapture(entity, shipId, localX, localY, localZ);
        }
    }

    /** Client-installed provider of the LOCAL player's held deck-frame heading (degrees), or
     *  {@code null} for a body whose look this client does not hold. A real player's aboard
     *  movement is client-authoritative, so the walk basis may consume the client's deck look
     *  directly; everything else (mobs, a missing deck look) falls back to the world->deck
     *  mapping below. Installed once from the client (the deck-look class); stays {@code null}
     *  on a dedicated server. */
    public static volatile java.util.function.Function<EntityLivingBase, Float> clientDeckLookYaw = null;

    /** The entity's facing, as a yaw in the ship frame: the held deck heading when this client
     *  owns the look, else his world heading rotated into that frame.
     *  YAW-ONLY (look pitch zeroed), exactly as the render body-yaw path does
     *  ({@code ShipFrameCamera.deckYawDeg}). A walk basis must not swing with look pitch: on a tilted deck
     *  the FULL look vector's ship-frame XZ heading DOES depend on pitch (world {@code +Y} leaks into ship
     *  X/Z under the rotation), so using {@code getLookVec()} the basis swung as the crew looked up/down and
     *  collapsed to one fixed heading when he looked along the deck normal - the natural pose walking an
     *  inverted deck, which read as inverted/rotated WASD. Vanilla walks by yaw alone for the same reason. */
    private static float deckYawDeg(EntityLivingBase entity, String shipId) {
        // One transform for input, aim and movement: when this client HOLDS the body's look in
        // the deck frame, that stored deck yaw IS the heading the player steers by. The derived
        // world yaw is only a projection of it - skewed on a rolled ship, and DEGENERATE when
        // the deck goes vertical (the world look is near the pole, its yaw frozen or swinging),
        // where mapping it back decoupled walking from the keys entirely.
        java.util.function.Function<EntityLivingBase, Float> held = clientDeckLookYaw;
        if (held != null) {
            Float deckYaw = held.apply(entity);
            if (deckYaw != null) {
                return deckYaw;
            }
        }
        float yawRad = entity.rotationYaw * 0.017453292F;
        double fx = -MathHelper.sin(yawRad);
        double fz = MathHelper.cos(yawRad);
        double[] deckLook = VSIntegration.rotateToShipFrameFor(entity.world, shipId, fx, 0.0, fz);
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

        // De-penetrate the START box. The subspace position comes through a world<->subspace round
        // trip that carries ~1e-8 of float noise, so a captured anchor can land a hair INSIDE the
        // deck plane. Vanilla's axis sweep only prevents CROSSING a box - it cannot resolve one that
        // already overlaps - so a sunk-by-epsilon box lets gravity through and the body never reads
        // on-deck (an onGround coin flip per capture). Lift onto the highest shallowly-overlapping
        // top first; a deep embed (a real wall/teleport-into-block) is left for the sweep to treat
        // as it always did.
        double lift = 0.0;
        for (AxisAlignedBB startObstacle : world.getCollisionBoxes(entity, box)) {
            double pen = startObstacle.maxY - box.minY;
            if (pen > 0.0 && pen <= 0.1 && pen > lift) {
                lift = pen;
            }
        }
        if (lift > 0.0) {
            box = box.offset(0.0, lift, 0.0);
        }

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
