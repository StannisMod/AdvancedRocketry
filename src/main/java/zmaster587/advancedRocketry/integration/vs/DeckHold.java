package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Holds a body on the deck it belongs on while nothing else can: the server pins it every tick and
 * asks its client to capture, so it is never handed to world gravity during a window in which its
 * ship is absent, unloaded or still being re-assembled.
 *
 * <h2>Who needs holding</h2>
 *
 * <ul>
 *   <li>a player <b>returning from a logout</b> aboard a ship — {@link #onPlayerLoggedIn} arms the
 *       hold from his durable aboard record. This is the original consumer and the shape everything
 *       below reuses.</li>
 *   <li>a crew member <b>on his feet across a crossing</b> — his ship is cut out from under him at
 *       the departure and re-assembled asynchronously at the far end, so for both windows he is a
 *       body in a world with no ship under it. {@link #pinInPlace} covers the near side (there is
 *       nothing left to resolve against) and {@link #holdOnDeck} the far one (the crossing already
 *       knows which ship and which point).</li>
 * </ul>
 *
 * <p>The ABOARD capture is in-memory; what survives the relog is the durable aboard record
 * ({@link zmaster587.advancedRocketry.space.ShipAboardTag}: the ship's DURABLE id plus his deck
 * point relative to its flight computer). Without this hold the returning player is a fresh entity
 * under WORLD gravity from his first tick - on a non-upright ship world-down points away from the
 * deck and he falls off (or through) before any first-contact gate could fire.</p>
 *
 * <p><b>Why the hold resolves lazily.</b> The record names the ship by the id that outlives a
 * re-assembly, not by the physics mod's own (re-minted) one, so the deck point cannot be turned
 * into a subspace triple until that ship's flight computer is loaded - and after a restart into a
 * space cell the ship is still being rebuilt on the tick the player logs in. The hold therefore
 * starts unresolved, pins the body where it is, and re-tries until the ship answers.</p>
 *
 * <p>The hold mirrors the dismount deck hold ({@code EntityDummy}): crew movement is
 * client-authoritative, so the server cannot capture him directly - instead it pins the body
 * each tick (against vanilla gravity on both sides: the per-tick position set replicates to the
 * client) and re-sends the SUBSPACE deck point in {@code PacketDeckCapture}; the client maps it
 * through its own ship transform and seeds once its ship is loaded. The hold ends the moment the
 * server sees the capture resolving (the client seeded and the server-side follow took over), on
 * an excluded state (the player relogs into creative flight - his movement is his own), or when
 * the window expires (ship gone: clean vanilla handover, never a half-capture).</p>
 */
public final class DeckHold {

    /** How long a returning player is held for his ship to load and his client to seed, in
     *  server ticks. Ship chunk-load plus client world join comfortably fit; a missing ship
     *  simply times the hold out into vanilla. */
    private static final int HOLD_WINDOW_TICKS = 200;

    /** How often an unresolved hold re-tries to find its ship, in ticks. The lookup walks the
     *  world's loaded tile entities, which is cheap but not free; the ship it waits for takes tens
     *  of ticks to come up, so a quarter-second retry loses nothing. {@code tunable}. */
    private static final int RESOLVE_RETRY_TICKS = 5;

    /**
     * One returning player's hold. It starts as a DURABLE ship id plus a flight-computer-relative
     * deck point - the only shape that survives a re-assembly - and becomes a physics-mod ship id
     * plus a subspace point once that ship is loaded and can be asked where its computer is.
     */
    private static final class Hold {
        final UUID durableShipId;
        final double dx, dy, dz;
        int ticksLeft = HOLD_WINDOW_TICKS;
        int untilRetry;
        /** Whether the returning client has been ASKED to capture yet. The hold may not conclude
         *  before it has: see the exit rule in {@link DeckHold#onPlayerTick}. */
        boolean seedSent;

        /** Non-null once the ship has been found; the pin and the capture packet need this shape. */
        String shipId;
        double subX, subY, subZ;

        Hold(UUID durableShipId, double dx, double dy, double dz) {
            this.durableShipId = durableShipId;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        boolean resolved() {
            return shipId != null;
        }

        void resolveOn(String shipId, double subX, double subY, double subZ) {
            this.shipId = shipId;
            this.subX = subX;
            this.subY = subY;
            this.subZ = subZ;
        }

        /** A hold whose ship is already known — the displaced-pilot case, where the seat itself
         *  answered which ship it belongs to and no lookup is needed. */
        static Hold on(String shipId, double subX, double subY, double subZ) {
            Hold hold = new Hold(null, 0.0, 0.0, 0.0);
            hold.resolveOn(shipId, subX, subY, subZ);
            return hold;
        }
    }

    /**
     * The live holds, keyed by player UUID — and STATIC, because the callers that arm one are not
     * events on this handler. A crossing decides mid-tick that a body has to be held; it holds no
     * reference to the single instance Forge's event bus owns, and handing one around would be a
     * second way to reach the same map. One instance is registered ({@code AdvancedRocketry}), so
     * the tick that services these entries is the tick that would have serviced an instance field.
     */
    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    /**
     * Pin {@code player} exactly where he is, with no ship to resolve against — the shape a crew
     * member on his feet needs while his ship is being CUT out from under him.
     *
     * <p>It deliberately carries no ship id. The ship this body belongs to is, for the length of
     * this window, in no world at all: it has been cut here and not yet re-assembled there. A hold
     * that named it would spend the window pumping a ship load in the world it just left, and find
     * nothing every time. What the body needs meanwhile is only to stop falling, which is exactly
     * what an unresolved hold does. The far side re-arms it with {@link #holdOnDeck} once there is
     * a ship to be held against.</p>
     */
    public static void pinInPlace(EntityPlayerMP player) {
        if (player != null) {
            HOLDS.put(player.getUniqueID(), new Hold(null, 0.0, 0.0, 0.0));
        }
    }

    /**
     * Hold {@code player} on a KNOWN ship's deck point: pin him to the current world image of the
     * SUBSPACE point {@code (subX,subY,subZ)} on ship {@code vsShipId}, and keep asking his client
     * to capture it until it does.
     *
     * <p>This is the far side of a crossing. The caller has already resolved which ship arrived and
     * where on it the body belongs, so no lookup is needed — and re-arming an existing hold is
     * harmless: the window restarts and the pin lands on the same point.</p>
     */
    public static void holdOnDeck(EntityPlayerMP player, String vsShipId,
                                  double subX, double subY, double subZ) {
        if (player != null && vsShipId != null) {
            HOLDS.put(player.getUniqueID(), Hold.on(vsShipId, subX, subY, subZ));
        }
    }

    /** Whether a hold is currently pinning {@code player}. */
    public static boolean isHeld(EntityPlayerMP player) {
        return player != null && HOLDS.containsKey(player.getUniqueID());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP) || event.player instanceof FakePlayer
                || event.player.world == null || event.player.world.isRemote) {
            return;
        }
        // Only a crew member who was ON HIS FEET needs holding: a seated one comes back on his
        // mount (vanilla re-spawns it, and reconcileSeatMount below settles who owns the chair).
        zmaster587.advancedRocketry.space.ShipAboardTag.Aboard aboard =
                zmaster587.advancedRocketry.space.ShipAboardTag.of(event.player);
        if (aboard != null
                && aboard.posture == zmaster587.advancedRocketry.space.ShipAboardTag.Posture.STANDING) {
            HOLDS.put(event.player.getUniqueID(),
                    new Hold(aboard.shipId, aboard.standDx, aboard.standDy, aboard.standDz));
        }
        // AFTER the anchor hold: a displaced pilot's hold below must win over the (older) anchor.
        reconcileSeatMount((EntityPlayerMP) event.player);
    }

    /**
     * One seat — ONE dummy, across a relog. Vanilla persists a seated pilot's mount dummy inside
     * his own player data ({@code RootVehicle}) and re-spawns it fresh at login — it cannot know
     * the seat may have acquired a NEW bound dummy while he was offline (every mount path spawns
     * through the one-seat-one-dummy recipe, and his own dummy left the world with him). Without
     * this reconciliation the login quietly ends with TWO invisible mounts bound to one seat: the
     * empty twin clears the ship's pilot input every tick (a control tug-of-war nobody can
     * attribute), and if the seat was TAKEN while he was away, two riders both hold "the" chair.
     *
     * <p>Resolution follows who sits on the seat's RESIDENT dummy (the one that stayed with the
     * ship): empty — the duplicate is folded into it and the pilot keeps his seat (the ordinary
     * relog promise); occupied — the occupant keeps the seat, the returner is restored STANDING
     * aboard at his post (the same deck-hold that carries a standing relog) and is told, by name,
     * who took it. A plain (non-pilot) world seat carries no seat binding and is left to vanilla.</p>
     */
    private void reconcileSeatMount(EntityPlayerMP player) {
        if (!(player.getRidingEntity()
                instanceof zmaster587.advancedRocketry.entity.EntityDummy)) {
            return;
        }
        zmaster587.advancedRocketry.entity.EntityDummy respawned =
                (zmaster587.advancedRocketry.entity.EntityDummy) player.getRidingEntity();
        net.minecraft.util.math.BlockPos seatPos = respawned.getSeatPos();
        if (seatPos == null) {
            return; // an ordinary seat's dummy: no ship binding, vanilla behaviour untouched
        }
        zmaster587.advancedRocketry.entity.EntityDummy resident =
                otherBoundDummy(player.world, respawned, seatPos);
        if (resident == null) {
            return; // his re-spawned mount IS the seat's only dummy: the normal seated relog
        }
        if (resident.getPassengers().isEmpty()) {
            // The resident is EMPTY (whoever took the seat left again): fold the duplicate into
            // it — the pilot keeps his seat, the seat keeps one dummy.
            player.dismountRidingEntity();
            respawned.setDead();
            player.startRiding(resident, true);
            return;
        }
        net.minecraft.entity.Entity occupant = resident.getPassengers().get(0);
        if (occupant == player) {
            return; // defensive: he cannot occupy the resident, he just logged in
        }
        // Seat TAKEN while he was offline: the occupant keeps it. Restore the returner STANDING
        // aboard at his post and tell him who took the chair.
        player.dismountRidingEntity();
        respawned.setDead();
        String shipId = VSIntegration.shipIdManagingBlock(player.world, seatPos);
        if (shipId != null) {
            double subX = seatPos.getX() + 0.5, subY = seatPos.getY(), subZ = seatPos.getZ() + 0.5;
            double[] deck = VSIntegration.toWorldFrameFor(player.world, shipId, subX, subY, subZ);
            if (deck != null) {
                player.setPositionAndUpdate(deck[0], deck[1], deck[2]);
            }
            // The same hold a standing relog gets: pin against gravity, ask his client to
            // capture the deck point. Wins over any (older) record hold registered above.
            HOLDS.put(player.getUniqueID(), Hold.on(shipId, subX, subY, subZ));
        } else {
            // Not on a managed ship (e.g. the craft was disassembled meanwhile): stand him at
            // the seat block itself, plain world frame.
            player.setPositionAndUpdate(
                    seatPos.getX() + 0.5, seatPos.getY() + 1.0, seatPos.getZ() + 0.5);
        }
        // Delayed past the join flood — sent immediately it is overwritten before he reads it.
        zmaster587.advancedRocketry.util.DelayedActionBar.send(player,
                new net.minecraft.util.text.TextComponentTranslation(
                        "msg.pilotseat.taken", occupant.getName()), 20);
    }

    /**
     * The dummy bound to {@code seatPos} OTHER than {@code exclude}, or {@code null}. A whole-world
     * scan rather than a positional box: the freshly re-spawned duplicate still sits at its SAVED
     * coordinates (it has not ticked its seat glue yet), the resident at the seat's live world
     * position — no single box reliably covers both on a ship that moved while the pilot slept.
     * Runs once per login, only for a player who came back seated on a bound dummy.
     */
    private static zmaster587.advancedRocketry.entity.EntityDummy otherBoundDummy(
            net.minecraft.world.World world,
            zmaster587.advancedRocketry.entity.EntityDummy exclude,
            net.minecraft.util.math.BlockPos seatPos) {
        for (net.minecraft.entity.Entity e : world.loadedEntityList) {
            if (e instanceof zmaster587.advancedRocketry.entity.EntityDummy && e != exclude
                    && !e.isDead
                    && seatPos.equals(((zmaster587.advancedRocketry.entity.EntityDummy) e)
                            .getSeatPos())) {
                return (zmaster587.advancedRocketry.entity.EntityDummy) e;
            }
        }
        return null;
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player != null) {
            HOLDS.remove(event.player.getUniqueID());
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || event.side != net.minecraftforge.fml.relauncher.Side.SERVER
                || !(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        Hold hold = HOLDS.get(player.getUniqueID());
        if (hold == null) {
            return;
        }
        // The server-side follow has taken the body - but that is the SERVER's own capture, and it
        // says nothing about the client, which owns this body's movement. Concluding here before the
        // client has even been asked to capture is what let its first-contact capture keep the
        // position AND VELOCITY vanilla restored, so a crew member who logged out walking skated on
        // across his own deck. The hold therefore may not end until at least one capture request has
        // gone out; the client's own pending slot then survives its ship streaming in.
        if (ShipFrameTravel.isResolving(player) && hold.seedSent) {
            HOLDS.remove(player.getUniqueID());
            return;
        }
        // An excluded state - riding, elytra, creative flight, water/ladder, levitation - owns its
        // own movement and ends any capture; the seed would refuse anyway.
        if (ShipFrameTravel.isExcludedFromCapture(player)) {
            HOLDS.remove(player.getUniqueID());
            return;
        }
        if (--hold.ticksLeft <= 0) {
            HOLDS.remove(player.getUniqueID()); // ship never came back: clean vanilla handover
            return;
        }
        // A returning body is a FRESH entity, and the physics mod arms its own per-entity drag
        // anchor the moment that body first touches the ship. Nothing has told it about the deck
        // capture yet, so what it holds is a stale point - and its world-tick mover then pulls the
        // body toward it, steadily, past the resolver. Measured on the walking-relog e2e: the body
        // sat exactly still for ten ticks and then slid ~0.04 blocks per tick along the walk it
        // logged out on, with an external [FF-TRACE/MOVE] of (0, -0.76, +0.51) on the server. The
        // resolved commit disarms this every tick for a body it owns (the same call); a body still
        // being handed back needs it too, or the hold's own pin is what it fights.
        VSIntegration.suppressShipDrag(player);
        if (!hold.resolved() && --hold.untilRetry <= 0) {
            hold.untilRetry = RESOLVE_RETRY_TICKS;
            resolve(player, hold);
        }
        double[] world = hold.resolved() ? VSIntegration.toWorldFrameFor(
                player.world, hold.shipId, hold.subX, hold.subY, hold.subZ) : null;
        if (world == null) {
            // The ship is not loaded (yet), or has not been found: hold the body still where it is
            // so gravity cannot ratchet it off the deck spot while the ship streams in.
            player.setPositionAndUpdate(player.posX, player.posY, player.posZ);
            player.motionX = 0.0;
            player.motionY = 0.0;
            player.motionZ = 0.0;
            player.fallDistance = 0.0f;
            return;
        }
        // Pin to the CURRENT world image of the persisted deck point (the ship may sit at any
        // attitude now) and ask the owning client to capture, exactly like the dismount hold:
        // the deck point travels as a SUBSPACE triple; the client maps it through its OWN
        // transform and seeds once; re-sends no-op after that.
        player.setPositionAndUpdate(world[0], world[1], world[2]);
        player.motionX = 0.0;
        player.motionY = 0.0;
        player.motionZ = 0.0;
        player.fallDistance = 0.0f;
        // A RESTORE seed: it re-establishes what the durable record says, so it outranks whatever
        // capture the returning client made for itself out of the position and velocity vanilla
        // handed it. A dismount seed deliberately does not (contract note on PacketDeckCapture).
        zmaster587.libVulpes.network.PacketHandler.sendToPlayer(
                new zmaster587.advancedRocketry.network.PacketDeckCapture(
                        hold.shipId, hold.subX, hold.subY, hold.subZ, true),
                player);
        hold.seedSent = true;
    }

    /**
     * Try to turn a durable hold into a live one: find the flight computer carrying the recorded
     * ship id, and express the record's computer-relative deck point as a subspace triple on the
     * ship that computer belongs to.
     *
     * <p>The ships are queued for load first, the way the login re-seat does it: a headless server
     * (or one whose returning player has not streamed the ship's chunks yet) keeps a ship in the
     * registry without ticking it, and an unloaded ship carries no loaded tile entities to find.</p>
     */
    private static void resolve(EntityPlayerMP player, Hold hold) {
        if (hold.durableShipId == null) {
            return;
        }
        VSIntegration.loadAllShips(player.world);
        net.minecraft.util.math.BlockPos afcPos = zmaster587.advancedRocketry.space
                .ShipRelativePoint.flightComputerOfDurableShip(player.world, hold.durableShipId);
        if (afcPos == null) {
            return;
        }
        String shipId = VSIntegration.shipIdManagingBlock(player.world, afcPos);
        double[] sub = zmaster587.advancedRocketry.space.ShipRelativePoint.subspacePointOf(
                afcPos, hold.dx, hold.dy, hold.dz);
        if (shipId != null && sub != null) {
            hold.resolveOn(shipId, sub[0], sub[1], sub[2]);
        }
    }
}
