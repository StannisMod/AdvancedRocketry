package zmaster587.advancedRocketry.integration.vs;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Re-seats a returning player on the deck he logged out on (any-attitude crew contract C14).
 *
 * <p>The ABOARD capture is in-memory; what survives the relog is the persisted anchor
 * ({@link ShipFrameTravel#PERSISTED_ANCHOR_TAG}: ship id + subspace deck point, refreshed per
 * resolved tick). Without this hold the returning player is a fresh entity under WORLD gravity
 * from his first tick - on a non-upright ship world-down points away from the deck and he falls
 * off (or through) before any first-contact gate could fire.</p>
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
public final class RelogDeckHold {

    /** How long a returning player is held for his ship to load and his client to seed, in
     *  server ticks. Ship chunk-load plus client world join comfortably fit; a missing ship
     *  simply times the hold out into vanilla. */
    private static final int HOLD_WINDOW_TICKS = 200;

    private static final class Hold {
        final String shipId;
        final double subX, subY, subZ;
        int ticksLeft = HOLD_WINDOW_TICKS;

        Hold(String shipId, double subX, double subY, double subZ) {
            this.shipId = shipId;
            this.subX = subX;
            this.subY = subY;
            this.subZ = subZ;
        }
    }

    private final Map<UUID, Hold> holds = new HashMap<>();

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP) || event.player instanceof FakePlayer
                || event.player.world == null || event.player.world.isRemote) {
            return;
        }
        NBTTagCompound data = event.player.getEntityData();
        if (data.hasKey(ShipFrameTravel.PERSISTED_ANCHOR_TAG)) {
            NBTTagCompound tag = data.getCompoundTag(ShipFrameTravel.PERSISTED_ANCHOR_TAG);
            String shipId = tag.getString("ship");
            if (!shipId.isEmpty()) {
                holds.put(event.player.getUniqueID(),
                        new Hold(shipId, tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")));
            }
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
            // capture the deck point. Wins over any (older) anchor hold registered above.
            holds.put(player.getUniqueID(), new Hold(shipId, subX, subY, subZ));
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
            holds.remove(event.player.getUniqueID());
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || event.side != net.minecraftforge.fml.relauncher.Side.SERVER
                || !(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        Hold hold = holds.get(player.getUniqueID());
        if (hold == null) {
            return;
        }
        // The client seeded and the server-side follow took over: done.
        if (ShipFrameTravel.isResolving(player)) {
            holds.remove(player.getUniqueID());
            return;
        }
        // An excluded state owns its own movement (contract C4); the seed would refuse anyway.
        if (ShipFrameTravel.isExcludedFromCapture(player)) {
            holds.remove(player.getUniqueID());
            return;
        }
        if (--hold.ticksLeft <= 0) {
            holds.remove(player.getUniqueID()); // ship never came back: clean vanilla handover
            return;
        }
        double[] world = VSIntegration.toWorldFrameFor(
                player.world, hold.shipId, hold.subX, hold.subY, hold.subZ);
        if (world == null) {
            // The anchor ship is not loaded (yet): hold the body still where it is so gravity
            // cannot ratchet it off the deck spot while the ship streams in.
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
        zmaster587.libVulpes.network.PacketHandler.sendToPlayer(
                new zmaster587.advancedRocketry.network.PacketDeckCapture(
                        hold.shipId, hold.subX, hold.subY, hold.subZ),
                player);
    }
}
