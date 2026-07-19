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
        if (!data.hasKey(ShipFrameTravel.PERSISTED_ANCHOR_TAG)) {
            return;
        }
        NBTTagCompound tag = data.getCompoundTag(ShipFrameTravel.PERSISTED_ANCHOR_TAG);
        String shipId = tag.getString("ship");
        if (shipId.isEmpty()) {
            return;
        }
        holds.put(event.player.getUniqueID(),
                new Hold(shipId, tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")));
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
