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
import net.minecraft.util.math.Vec3d;
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
    /** Ship-frame obstacles the last resolved sweep saw. Zero on every tick means the deck's blocks
     *  are not being found, and an aboard body falls straight through it. */
    public static volatile int lastObstacleCount = -1;
    /** Whether the last resolved entity ended the tick standing on its deck. */
    public static volatile boolean lastOnDeck = false;

    /**
     * Each aboard entity's authoritative position in its ship's frame, plus the world position this
     * class last derived from it. Weak keys: an entity that goes away takes its entry with it. The two
     * logical sides tick on different threads but hold different entity objects, so one map serves both;
     * it is synchronized only against that concurrency, never contended.
     */
    private static final Map<Entity, ShipFrameState> STATE =
            Collections.synchronizedMap(new WeakHashMap<Entity, ShipFrameState>());

    /** An aboard entity's ship-frame position, and the world position last derived from it. */
    private static final class ShipFrameState {
        double localX, localY, localZ;
        double worldX, worldY, worldZ;
    }

    /** How far (squared, in blocks) the world may have drifted from what we last wrote before we treat
     *  the entity as having been moved by someone ELSE - a real teleport - and re-derive from it. Travel
     *  writes the position it derived every tick, so a body this class owns never drifts on its own; the
     *  slack only has to absorb the sub-block client/server position reconciliation that rides on a moving
     *  ship (a captured crew member's own client resolves and reports his position, and the server accepts
     *  it a tick later slightly transformed). Set too tight (it was 1e-6 ~ 1mm) that ordinary
     *  reconciliation reads as an external move and drops the capture; 0.2 block keeps a freshly-captured
     *  dismounted pilot held across it while still releasing on a genuine multi-tenth teleport. */
    private static final double EXTERNAL_MOVE_EPSILON_SQ = 0.04;

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
            STATE.remove(entity); // left every ship's box - definitely not aboard any more
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
                STATE.remove(entity);
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
     * for a player that is the CLIENT (its own {@code EntityPlayerSP.travel}). Both the world position the
     * body is snapped to and the stored {@code state.world} are computed HERE, on this side, from the same
     * subspace point through this side's own ship transform, so {@code entity.pos == state.world} exactly
     * and {@link #heldShipFramePos}'s external-move guard holds instead of dropping the capture. This is
     * why the deck point travels as a SUBSPACE triple in a packet, never a world position: a world
     * position computed on the server and re-derived here would differ by more than the guard's ~1 mm and
     * drop instantly. The travel then keeps the body on the deck across ticks. Returns false off a loaded
     * ship. Idempotent enough to re-send: pair with an {@link #isResolving} check at the call site so a
     * re-seed after the capture already took is skipped (no repeated teleport).
     */
    public static boolean seedShipFrameCapture(Entity entity, double subX, double subY, double subZ) {
        if (entity == null) {
            return false;
        }
        double[] world = VSIntegration.toWorldFrame(entity, subX, subY, subZ);
        if (world == null) {
            return false;
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
        // The cost is a jump that rises one gravity step short of vanilla's (ledgered) - a fair trade
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
        remember(entity, sweep.x, sweep.y, sweep.z, worldPos);
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
        updateLimbSwing(entity);
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
     * never been aboard, or its world position is no longer the one this class last wrote there, which
     * means someone else moved it and the ship frame must be re-derived from where it now is.
     */
    private static double[] heldShipFramePos(Entity entity) {
        ShipFrameState state = STATE.get(entity);
        if (state == null) {
            return null;
        }
        double dx = entity.posX - state.worldX;
        double dy = entity.posY - state.worldY;
        double dz = entity.posZ - state.worldZ;
        if (dx * dx + dy * dy + dz * dz > EXTERNAL_MOVE_EPSILON_SQ) {
            STATE.remove(entity);
            return null;
        }
        return new double[]{state.localX, state.localY, state.localZ};
    }

    private static void remember(Entity entity, double localX, double localY, double localZ,
                                 double[] worldPos) {
        ShipFrameState state = new ShipFrameState();
        state.localX = localX;
        state.localY = localY;
        state.localZ = localZ;
        state.worldX = worldPos[0];
        state.worldY = worldPos[1];
        state.worldZ = worldPos[2];
        STATE.put(entity, state);
    }

    /** The entity's facing, as a yaw in the ship frame: his world look, rotated into that frame. */
    private static float deckYawDeg(EntityLivingBase entity) {
        Vec3d look = entity.getLookVec();
        double[] deckLook = VSIntegration.rotateToShipFrame(entity, look.x, look.y, look.z);
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

    /** Vanilla's walk-animation bookkeeping, which lives outside the branch we cancelled. */
    private static void updateLimbSwing(EntityLivingBase entity) {
        entity.prevLimbSwingAmount = entity.limbSwingAmount;
        double dx = entity.posX - entity.prevPosX;
        double dz = entity.posZ - entity.prevPosZ;
        float swing = MathHelper.sqrt(dx * dx + dz * dz) * 4.0F;
        if (swing > 1.0F) {
            swing = 1.0F;
        }
        entity.limbSwingAmount += (swing - entity.limbSwingAmount) * 0.4F;
        entity.limbSwing += entity.limbSwingAmount;
    }
}
