package zmaster587.advancedRocketry.space;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync.RenderBody;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.libVulpes.network.PacketHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side producer for the {@link PacketSystemBodiesSync} render channel: turns the set of
 * MATERIALIZED CELLS into the per-slot-dim list of bodies the client draws in the slot-world sky
 * ({@code BoundarySky}). Every live cell contributes its own contents
 * ({@link UniverseRegistry#bodiesAt}) under the slot dim it is bound to; each body is carried as a
 * DIRECTION from the observer point in that cell (see {@link #buildByDim}). Sent to a player at login
 * and rebroadcast on a throttle so the boundary/bodies track a ship as it flies within the cell.
 *
 * <p><b>The sky belongs to the cell, not to a ship.</b> The feed is keyed off the cell&rarr;slot
 * bindings ({@link SpaceManager#loadedCells}) and never off a ship's lifecycle state. A world that is
 * a live cell has that cell's surroundings in its sky for everyone in it: a pilot whose ship is
 * mid-jump, a passenger, a crew member who walked off the hull, or someone left behind by a ship that
 * departed. Deriving the feed from settled ships instead made all of those skies blank &mdash; and the
 * blank was indistinguishable from an empty cell.</p>
 *
 * <p>No discovery / {@code isSystemKnown} gate (by design, presence is the gate); the same
 * payload goes to everyone, so the rebroadcast is a single {@link PacketHandler#sendToAll}.
 * Server main thread only.</p>
 */
public final class SystemBodiesProducer {

    /** Rebroadcast cadence in server ticks (~1 s at 20 tps): tracks the ship's within-cell motion. tunable. */
    private static final int BROADCAST_INTERVAL_TICKS = 20;

    private static int tickCounter;
    /** True once a non-empty payload has been sent; drives ONE clearing broadcast when it later empties. */
    private static boolean lastBroadcastNonEmpty;

    private SystemBodiesProducer() {
    }

    /** The per-cell body source — the seam that lets {@link #buildByDim} be unit-tested without a server. */
    public interface BodyLookup {
        List<SystemBody> bodiesAt(GalacticCoord cell);
    }

    /**
     * Pure builder: map every materialized cell's slot dim to the render bodies of that cell. A live
     * cell that holds no body still gets a (present, empty) entry, so the client clears any stale
     * bodies for that dim and draws just the ring; a cell bound to no slot keys nothing, because there
     * is no world whose sky it would be.
     *
     * <p>Each body's {@code localX/Y/Z} is the observer&rarr;body vector (body absolute minus observer
     * absolute, component-wise exactly like {@link GalacticCoord#distanceSqTo}); {@code BoundarySky}
     * reads it as a direction, so a body sitting at its OWN cell centre (a planet, local
     * {@code 0,0,0}) still points away from an observer parked off-centre.
     * {@link UniverseRegistry#bodiesAt} only returns same-cell bodies, so the sector term is normally
     * zero, but the full delta stays correct if a cross-cell POI ever surfaces.</p>
     *
     * <p>The observer point is {@link #observerIn}: the position of a ship the ledger places in that
     * cell when there is one, else the cell centre. The bearing to a body only a few thousand blocks
     * away swings by tens of degrees across a cell, and the descent trigger needs the pilot to be able
     * to FLY at it, so the feed follows the ship that is there rather than the geometric centre. It is
     * one direction set per dimension either way &mdash; the sky is camera-centred, so every viewer in
     * the cell shares it.</p>
     *
     * @param loadedCells {@code cellKey -> slot dim} for the cells that are live right now
     *                    ({@link SpaceManager#loadedCells})
     * @param snapshot    the ship ledger, used ONLY to refine the observer point inside a cell
     */
    public static Map<Integer, List<RenderBody>> buildByDim(Map<String, Integer> loadedCells,
                                                           Map<UUID, ShipLedger.Entry> snapshot,
                                                           BodyLookup lookup) {
        Map<Integer, List<RenderBody>> byDim = new LinkedHashMap<>();
        if (loadedCells == null || lookup == null) {
            return byDim;
        }
        for (Map.Entry<String, Integer> bound : loadedCells.entrySet()) {
            Integer slotDim = bound.getValue();
            GalacticCoord cell = GalacticCoord.fromCellKey(bound.getKey());
            if (slotDim == null || slotDim == SpaceManager.UNBOUND_SLOT || cell == null) {
                continue;
            }
            GalacticCoord observer = observerIn(cell, snapshot);
            List<RenderBody> bodies = new ArrayList<>();
            List<SystemBody> found = lookup.bodiesAt(cell);
            if (found != null) {
                for (SystemBody b : found) {
                    GalacticCoord a = b.address();
                    long dx = (a.sectorX() - observer.sectorX()) * GalacticCoord.CELL
                            + (a.localX() - observer.localX());
                    long dy = (a.sectorY() - observer.sectorY()) * GalacticCoord.CELL
                            + (a.localY() - observer.localY());
                    long dz = (a.sectorZ() - observer.sectorZ()) * GalacticCoord.CELL
                            + (a.localZ() - observer.localZ());
                    bodies.add(new RenderBody(b.kind().ordinal(), dx, dy, dz, b.dimId(), b.isDescendTarget()));
                }
            }
            byDim.put(slotDim, bodies);
        }
        return byDim;
    }

    /**
     * Where the bodies of {@code cell} are seen FROM: a ship the ledger places in that cell, preferring
     * a {@link ShipLedger.State#SETTLED} one (it is the one that is really parked there), else the cell
     * centre. A ship whose state is anything else still beats the centre when it is the only thing
     * known to be in the cell &mdash; its coordinate is a real point in that cell, and the alternative
     * is a bearing measured from up to half a cell away.
     */
    private static GalacticCoord observerIn(GalacticCoord cell, Map<UUID, ShipLedger.Entry> snapshot) {
        if (snapshot == null) {
            return cell;
        }
        GalacticCoord fallback = null;
        for (ShipLedger.Entry e : snapshot.values()) {
            if (e == null || e.coord == null || !e.coord.sameCell(cell)) {
                continue;
            }
            if (e.state == ShipLedger.State.SETTLED) {
                return e.coord;
            }
            if (fallback == null) {
                fallback = e.coord;
            }
        }
        return fallback == null ? cell : fallback;
    }

    /** Build the live packet from the production cell bindings + universe registry, or an empty packet. */
    public static PacketSystemBodiesSync currentPacket(MinecraftServer server) {
        ShipLedger ledger = SpaceSubsystem.ledger();
        UniverseRegistry reg = UniverseRegistry.get(server);
        SpaceManager space = SpaceSubsystem.get();
        if (reg == null || space == null) {
            return PacketSystemBodiesSync.forDims(null);
        }
        return PacketSystemBodiesSync.forDims(buildByDim(space.loadedCells(),
                ledger == null ? null : ledger.snapshot(), reg::bodiesAt));
    }

    /** Login send: give a joining player the current bodies (skip when there is nothing to render). */
    public static void sendToPlayer(EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        try {
            PacketSystemBodiesSync pkt =
                    currentPacket(FMLCommonHandler.instance().getMinecraftServerInstance());
            if (!pkt.isEmpty()) {
                PacketHandler.sendToPlayer(pkt, player);
            }
        } catch (Throwable t) {
            AdvancedRocketry.logger.warn("[SPACE] system-bodies login send failed", t);
        }
    }

    /**
     * Throttled rebroadcast tick: every {@link #BROADCAST_INTERVAL_TICKS}, push the current bodies to
     * all players so the boundary/bodies track ship motion. Stays silent while no cell is live at all,
     * apart from ONE clearing broadcast the tick the last cell goes away.
     */
    public static void onBroadcastTick(MinecraftServer server) {
        if (++tickCounter < BROADCAST_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        try {
            PacketSystemBodiesSync pkt = currentPacket(server);
            if (!pkt.isEmpty()) {
                PacketHandler.sendToAll(pkt);
                lastBroadcastNonEmpty = true;
            } else if (lastBroadcastNonEmpty) {
                PacketHandler.sendToAll(pkt); // final clear: the last ship just left
                lastBroadcastNonEmpty = false;
            }
        } catch (Throwable t) {
            AdvancedRocketry.logger.warn("[SPACE] system-bodies broadcast failed", t);
        }
    }

    /** Reset the broadcast cadence + active flag (server stop). */
    public static void reset() {
        tickCounter = 0;
        lastBroadcastNonEmpty = false;
    }
}
