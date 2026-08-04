package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ServerConnectionFromClientEvent;

import zmaster587.advancedRocketry.network.PacketSlotDimSync;
import zmaster587.libVulpes.network.PacketHandler;

/**
 * Server-side event wiring for the movable-ship space subsystem: putting a returning player back
 * where he left off, and noticing when a space cell's content diverges from what can be regenerated.
 *
 * <h2>Why a login needs special handling at all</h2>
 *
 * Minecraft restores a player by the dimension id stored in his save file. That is meaningless here:
 * slot dimensions are a transient POOL. Their ids are minted in registration order at every server
 * start, and a given slot holds whichever cell was most recently bound to it — so the slot a player
 * logged out in may, on the next boot, be a different star system entirely, or nothing at all. What
 * survives a restart is his ship's identity and the ledger's record of where that ship is.
 *
 * <h2>Two phases, and why the first one is not merely defensive</h2>
 *
 * <ol>
 *   <li><b>Placement</b>, at player-file load — the only hook that runs after the save file has been
 *       read but BEFORE the world is chosen for him. It rewrites the stale slot dimension to the
 *       dimension he actually belongs in. Doing the real resolution HERE, rather than parking him
 *       somewhere neutral and teleporting afterwards, is what stops a player who logged out in orbit
 *       from materialising on a planet for a moment first: by the time the world is chosen it is
 *       already the right one, so the client is never sent anywhere else.</li>
 *   <li><b>Seating</b>, once he is in the world — mounting him back on his seat. This cannot happen
 *       in phase one because a ship re-assembles asynchronously; its seat blocks may not exist yet
 *       on the tick he logs in, so the seating retries for a few ticks. Only a crew member who was
 *       SEATED is re-seated: one who was on his feet is put back on his deck point by the deck
 *       hold, which also has to pin him against world gravity while his client re-captures.</li>
 * </ol>
 *
 * <p>Phase one only ever intervenes for a player whose saved dimension is one of the subsystem's own
 * worlds. A player saved in an ordinary world is left strictly alone — vanilla's own restore is
 * correct for him, and the least this code can do is not touch it.</p>
 *
 * <p>Because phase one can land a player in a slot dimension on his very first world-join packet,
 * the client has to know that dimension exists BEFORE it arrives. That is why the slot-dim sync is
 * also sent on the raw connection event, ahead of the player being spawned — the same pre-spawn
 * channel this mod already uses to teach a client about its planet dimensions.</p>
 *
 * <p>Server main thread only.</p>
 */
public final class SpaceEventHandler {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/space");

    /** How many ticks to keep retrying a seating before giving up and leaving the player standing. */
    private static final int MAX_SEAT_ATTEMPTS = 200;

    /**
     * How often the durable aboard records are brought into line with where players actually are.
     * One second: crashes are rare enough that a one-second staleness bound is the right price for
     * a record that is small, fixed-shape, and re-derived from scratch every pass. A clean logout
     * pays nothing at all — it refreshes on its own event. {@code tunable}.
     */
    private static final int RECORD_REFRESH_TICKS = 20;

    private int sinceRecordRefresh;

    /** A player who has been placed aboard and is waiting for his seat to exist. */
    private static final class PendingSeat {
        final UUID playerId;
        final ShipAboardTag.Aboard aboard;
        final int dimension;
        int attempts;

        PendingSeat(UUID playerId, ShipAboardTag.Aboard aboard, int dimension) {
            this.playerId = playerId;
            this.aboard = aboard;
            this.dimension = dimension;
        }
    }

    private final List<PendingSeat> pendingSeats = new ArrayList<>();

    /**
     * player -> the ship whose cell was materialized for him at login, so the occupant refcount that
     * materialize took can be handed back when he leaves. A refcount is a claim on one of a small
     * fixed pool of slot worlds; leaking one per login would exhaust the pool.
     */
    private final java.util.Map<UUID, UUID> heldCells = new java.util.HashMap<>();

    /**
     * Players who came back aboard a ship the server has no record of, waiting to be told so. Written
     * while their save file is read (no connection yet) and drained once they have actually joined.
     */
    private final java.util.Set<UUID> pendingShipLostNotices = new java.util.HashSet<>();

    // --- pre-spawn client sync -------------------------------------------------------------------

    /**
     * Teach the connecting client about the slot dimensions before its player is spawned into one.
     * The world-join packet carries a dimension id the client must already be able to resolve, and a
     * login restore can put the player straight into a slot world — so this has to precede it.
     */
    @SubscribeEvent
    public void onConnectionFromClient(ServerConnectionFromClientEvent event) {
        PacketSlotDimSync sync = PacketSlotDimSync.current();
        if (!sync.isEmpty()) {
            PacketHandler.sendToDispatcher(sync, event.getManager());
        }
    }

    // --- phase 1: placement ----------------------------------------------------------------------

    /**
     * Rewrite a returning player's stale slot dimension to the one he actually belongs in. Fires
     * after his save file has been read and before a world is chosen for him.
     */
    @SubscribeEvent
    public void onPlayerLoadFromFile(PlayerEvent.LoadFromFile event) {
        EntityPlayer player = event.getEntityPlayer();
        if (player == null) {
            return;
        }
        // A slot dimension id is TRANSIENT. The pool mints its ids from whatever dimension ids happen
        // to be free when it registers, so the id a player was saved under can belong to something
        // else - or to nothing - on the very next boot. Deciding "was he out in space?" from that id
        // alone therefore silently loses every space-borne player across a restart, which is the one
        // case this hook exists for. The aboard record is the durable evidence: it names the ship and
        // its galactic coordinate, neither of which is transient. So the record opens the restore, and
        // the dimension id is only a fallback for someone who was in a subsystem world without one.
        //
        // A record with NO cell in it opens nothing: it is ship-relative only - a crew member aboard
        // a ship parked on a planet - and says which SHIP he is on, not which world he belongs in.
        // The dimension he was saved in is already right for him, and the deck hold is what puts him
        // back on his ship once it loads.
        ShipAboardTag.Aboard aboard = ShipAboardTag.of(player);
        boolean spaceborne = (aboard != null && aboard.hasPresence())
                || isSubsystemWorld(player.dimension);
        if (!spaceborne) {
            return; // saved in an ordinary world: vanilla's own restore is correct, leave it alone
        }
        LoginRestore.Placement placement =
                LoginRestore.resolve(aboard, new SubsystemOps(player), player.getUniqueID());

        player.dimension = placement.dimension;
        player.setLocationAndAngles(placement.x, placement.y, placement.z,
                player.rotationYaw, player.rotationPitch);

        if (placement.aboard && aboard != null) {
            if (aboard.posture == ShipAboardTag.Posture.SEATED) {
                // A seat is re-taken by mounting it; a crew member who was on his feet is not
                // "seated late", he is placed on his deck point - which the deck hold owns, because
                // it must also pin him against world gravity while his client re-captures.
                pendingSeats.add(new PendingSeat(player.getUniqueID(), aboard, placement.dimension));
            }
            if (placement.reason == LoginRestore.Reason.ABOARD_SETTLED) {
                // The materialize above took an occupant refcount on his behalf; remember it so his
                // logout gives it back. Without the pairing the cell is pinned to a pool slot for the
                // rest of the server's life and the pool bleeds one slot per restored player.
                heldCells.put(player.getUniqueID(), placement.shipId);
            }
        } else if (placement.reason == LoginRestore.Reason.NO_TAG
                || placement.reason == LoginRestore.Reason.SHIP_UNKNOWN) {
            // Only clear on a PERMANENT verdict. A cell that merely could not be materialized right
            // now (a full pool) is a transient condition, and wiping the tag for it would destroy the
            // one record of which ship he belongs to — he could never be restored, on any later login.
            ShipAboardTag.clear(player);
        }
        if (placement.reason == LoginRestore.Reason.SHIP_UNKNOWN) {
            // He left aboard a ship and is being put down at his spawn point instead. That is the one
            // outcome of this hook a player has to be TOLD about: everything else either puts him back
            // where he was or is an ordinary login. He cannot be told here — his connection is not
            // assigned until later in the login sequence, and both send paths dereference it — so the
            // notice is queued for the moment he is actually on the server.
            pendingShipLostNotices.add(player.getUniqueID());
            LOGGER.warn("[SPACE] {} returned aboard ship {} but the ledger has no record of it; he is "
                    + "being placed at his spawn point and his aboard record is cleared",
                    player.getName(), aboard == null ? "?" : aboard.shipId);
        }
        LOGGER.info("[SPACE] login restore for {}: {} -> dim {} ({})",
                player.getName(), placement.reason, placement.dimension,
                placement.aboard ? "aboard" : "not aboard");
    }

    /**
     * Tell a player whose ship could not be found that it could not be found. Queued by phase 1, which
     * runs while his save file is being read — before {@code connection} exists — and drained here,
     * where he is a fully joined player.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player == null) {
            return;
        }
        // The space clock, before anything he can see depends on it. A client that has never been
        // told simply answers zero, which is indistinguishable from "the world just started".
        if (event.player instanceof EntityPlayerMP) {
            PacketHandler.sendToPlayer(
                    zmaster587.advancedRocketry.network.PacketSpaceClockSync.current(),
                    (EntityPlayerMP) event.player);
        }
        if (pendingShipLostNotices.isEmpty()) {
            return;
        }
        if (pendingShipLostNotices.remove(event.player.getUniqueID())) {
            event.player.sendMessage(
                    new net.minecraft.util.text.TextComponentTranslation(LoginRestore.MSG_SHIP_UNKNOWN));
        }
    }

    /**
     * Give back the cell claim taken for a player at login. Paired with the {@code heldCells} entry
     * written by phase 1; without it the cell stays pinned to a pool slot forever.
     */
    @SubscribeEvent
    public void onPlayerLoggedOut(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player == null) {
            return;
        }
        // Forge fires this BEFORE the player file is written, so a record refreshed here is the one
        // his next login reads back - the writer's one-second cadence never costs a clean logout.
        AboardRecord.reconcile(event.player);
        UUID playerId = event.player.getUniqueID();
        releaseHeldCell(playerId);
        // He is gone; a queued seating for him is dead work.
        Iterator<PendingSeat> it = pendingSeats.iterator();
        while (it.hasNext()) {
            if (playerId.equals(it.next().playerId)) {
                it.remove();
            }
        }
    }

    private void releaseHeldCell(UUID playerId) {
        UUID shipId = heldCells.remove(playerId);
        SpaceManager manager = SpaceSubsystem.get();
        ShipLedger ledger = SpaceSubsystem.ledger();
        if (shipId == null || manager == null || ledger == null) {
            return;
        }
        ShipLedger.Entry entry = ledger.get(shipId);
        if (entry != null) {
            manager.dematerialize(entry.coord);
        }
    }

    /**
     * How often each player's space-clock baseline is refreshed, in ticks. Sized off what the clock
     * is USED for rather than off precision for its own sake: a body moves well under a block per
     * tick and the descent trigger is hundreds of blocks wide, so even a client whose tick rate has
     * run away from a lagging server stays far inside tolerance over this interval. {@code tunable}.
     */
    private static final int CLOCK_SYNC_TICKS = 200;

    /**
     * Refresh the space-clock baseline of whichever players are due this tick.
     *
     * <p><b>Each player has his OWN phase inside the interval.</b> A shared
     * {@code tick % CLOCK_SYNC_TICKS == 0} would read the same clock for every player and open the
     * gate for all of them on the same tick — one packet per online player at once, then nothing for
     * the rest of the interval. The peak, not the average, is what a network stall is made of. The
     * phase comes from the player's own id, so it is stable across his whole session and costs no
     * state to remember.</p>
     *
     * <p>Cost, stated as the PEAK: at most one packet of one {@code long} in any single tick,
     * whatever the player count.</p>
     */
    private void syncSpaceClock(MinecraftServer server) {
        long clock = SpaceSubsystem.spaceClock();
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (Math.floorMod(clock + clockPhaseOf(player.getUniqueID()), (long) CLOCK_SYNC_TICKS) == 0L) {
                PacketHandler.sendToPlayer(
                        zmaster587.advancedRocketry.network.PacketSpaceClockSync.current(), player);
            }
        }
    }

    /** A stable, well-spread phase for one player inside the sync interval. */
    private static long clockPhaseOf(UUID playerId) {
        return Math.floorMod(playerId.hashCode() * 2654435761L, (long) CLOCK_SYNC_TICKS);
    }

    // --- phase 2: seating ------------------------------------------------------------------------

    /**
     * Drain the pending seatings. A ship re-assembles asynchronously, so the seat a player is owed
     * may not exist for several ticks after he joins; each pending entry retries until its seat
     * resolves or the budget runs out (after which he is simply left standing aboard rather than
     * being held in limbo).
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance()
                .getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        if (++sinceRecordRefresh >= RECORD_REFRESH_TICKS) {
            sinceRecordRefresh = 0;
            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                AboardRecord.reconcile(player);
            }
        }
        syncSpaceClock(server);
        if (pendingSeats.isEmpty()) {
            return;
        }
        Iterator<PendingSeat> it = pendingSeats.iterator();
        while (it.hasNext()) {
            PendingSeat pending = it.next();
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(pending.playerId);
            if (player == null) {
                if (++pending.attempts > MAX_SEAT_ATTEMPTS) {
                    it.remove(); // never showed up (login aborted) - stop tracking him
                }
                continue;
            }
            if (seat(server, player, pending)) {
                it.remove();
            } else if (++pending.attempts > MAX_SEAT_ATTEMPTS) {
                LOGGER.warn("[SPACE] gave up re-seating {} on ship {} after {} ticks - the seat never "
                        + "appeared; he stays aboard on foot", player.getName(),
                        pending.aboard.shipId, MAX_SEAT_ATTEMPTS);
                it.remove();
            }
        }
    }

    /** Mount {@code player} back on his seat. {@code false} while the ship is not up yet. */
    private boolean seat(MinecraftServer server, EntityPlayerMP player, PendingSeat pending) {
        WorldServer world = server.getWorld(pending.dimension);
        if (world == null) {
            return false;
        }
        double[] pose = shipPose(pending.aboard.shipId);
        if (pose == null) {
            return false;
        }
        BlockPos anchor = new BlockPos(pose[0], pose[1], pose[2]);
        // Queue the world's ships for load, exactly as the crossing reseat path does: the seat tiles
        // are searched over loaded tile entities, so an unloaded shipyard reads as "no seat here" and
        // the retry would spin until it gave up.
        zmaster587.advancedRocketry.integration.vs.VSIntegration.loadAllShips(world);
        // The seat is re-identified by the offset it keeps from its flight computer, which is the one
        // binding that survives a ship being re-assembled into a fresh subspace.
        CrewTransfer.Crew rider = new CrewTransfer.Crew(player,
                pending.aboard.afcDx, pending.aboard.afcDy, pending.aboard.afcDz);
        // The aboard record names the ship by its durable id — hand it to the re-seat so a
        // neighbouring ship with the same seat offset can never claim the returning pilot.
        return CrewTransfer.reseat(world, anchor, Collections.singletonList(rider),
                pending.aboard.shipId);
    }

    // --- the divergence hook ---------------------------------------------------------------------

    /**
     * A player edit inside a space cell means the cell no longer matches what its seed would
     * regenerate, so it must be flushed rather than thrown away when its slot is needed. Without
     * this, everything a player builds in orbit between two ship crossings is regenerable as far as
     * the controller knows, and an eviction would discard it.
     */
    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.PlaceEvent event) {
        markCellDirty(event.getWorld());
    }

    @SubscribeEvent
    public void onBlockBroken(BlockEvent.BreakEvent event) {
        markCellDirty(event.getWorld());
    }

    private void markCellDirty(World world) {
        if (world == null || world.isRemote || !(world.provider instanceof WorldProviderSpaceSlot)) {
            return;
        }
        SpaceManager manager = SpaceSubsystem.get();
        if (manager == null) {
            return;
        }
        // An UNBOUND slot has no cell behind it - that covers the shared hyperspace world, which is
        // deliberately ephemeral and must never be flushed as though it were someone's home cell.
        String cellKey = SpaceSlotPool.cellKeyFor(world.provider.getDimension());
        GalacticCoord coord = GalacticCoord.fromCellKey(cellKey);
        if (coord != null) {
            manager.markDirty(coord);
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** Whether {@code dimId} is one of the subsystem's own worlds (a pool slot or hyperspace). */
    private static boolean isSubsystemWorld(int dimId) {
        return SpaceSlotPool.slotDims().contains(dimId) || dimId == HyperspaceWorld.dimId();
    }

    /**
     * A settled ship's world position, derived from the coordinate it self-reports to the ledger.
     * The cell's coordinate mapping is invertible, so the ledger's coordinate IS the ship's pose.
     */
    private static double[] shipPose(UUID shipId) {
        ShipLedger ledger = SpaceSubsystem.ledger();
        if (ledger == null) {
            return null;
        }
        ShipLedger.Entry entry = ledger.get(shipId);
        if (entry == null) {
            return null;
        }
        if (entry.state == ShipLedger.State.IN_TRANSIT) {
            // Mid-jump the ledger's coordinate is the DESTINATION, which says nothing about where the
            // ship physically sits — it is parked in a hyperspace lane. Ask the transit for that.
            ShipTransitManager transit = SpaceSubsystem.transit();
            BlockPos parked = transit == null ? null : transit.hyperspaceAnchorOf(shipId.toString());
            return parked == null ? null
                    : new double[] {parked.getX() + 0.5D, parked.getY() + 1.0D, parked.getZ() + 0.5D};
        }
        return CellWorldMapper.poseWorldOf(entry.coord);
    }

    /** The production {@link LoginRestore.Ops}, reading the live subsystem. */
    private static final class SubsystemOps implements LoginRestore.Ops {

        /**
         * The player being restored. Held directly because at load-from-file time he is NOT yet in
         * the server's player list — looking him up by UUID there returns null every single time,
         * which would silently disable the bed-spawn fallback and send everyone to the world spawn.
         * His save data has already been read by this point, so the entity itself has the answer.
         */
        private final EntityPlayer player;

        SubsystemOps(EntityPlayer player) {
            this.player = player;
        }

        @Override
        public ShipLedger.Entry ledgerEntry(UUID shipId) {
            ShipLedger ledger = SpaceSubsystem.ledger();
            return ledger == null ? null : ledger.get(shipId);
        }

        @Override
        public int materialize(GalacticCoord coord) {
            SpaceManager manager = SpaceSubsystem.get();
            if (manager == null) {
                return -1;
            }
            try {
                return manager.materialize(coord);
            } catch (SpaceManager.PoolExhaustedException exhausted) {
                LOGGER.warn("[SPACE] cannot restore a player into {} - the slot pool is full",
                        coord.cellKey());
                return -1;
            }
        }

        @Override
        public int unpackTransit(UUID shipId) {
            ShipTransitManager transit = SpaceSubsystem.transit();
            return transit == null ? -1 : transit.crewDimensionOf(shipId.toString());
        }

        @Override
        public double[] shipWorldPos(int slotDim, UUID shipId) {
            return shipPose(shipId);
        }

        @Override
        public double[] personalSpawn(UUID playerId) {
            MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance()
                    .getMinecraftServerInstance();
            if (server == null || player == null) {
                return null;
            }
            BlockPos bed = player.getBedLocation(0);
            if (bed == null) {
                return null;
            }
            WorldServer overworld = server.getWorld(0);
            BlockPos safe = overworld == null
                    ? bed : EntityPlayer.getBedSpawnLocation(overworld, bed, false);
            if (safe == null) {
                return null; // his bed is gone or obstructed; fall through to the world spawn
            }
            return new double[] {0, safe.getX() + 0.5D, safe.getY() + 0.1D, safe.getZ() + 0.5D};
        }

        @Override
        public double[] overworldSpawn() {
            MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance()
                    .getMinecraftServerInstance();
            WorldServer overworld = server == null ? null : server.getWorld(0);
            if (overworld == null) {
                return null;
            }
            BlockPos spawn = overworld.provider.getRandomizedSpawnPoint();
            return new double[] {spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D};
        }
    }
}
