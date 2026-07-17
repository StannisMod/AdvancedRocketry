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
 * Server-side producer for the {@link PacketSystemBodiesSync} render channel: turns the live
 * {@link ShipLedger} into the per-slot-dim list of bodies the client draws in the slot-world sky
 * ({@code BoundarySky}). One settled ship contributes the bodies of its OWN cell
 * ({@link UniverseRegistry#bodiesAt}) under its slot dim id; each body is carried as the
 * ship&rarr;body DIRECTION (see {@link #buildByDim}). Sent to a player at login and rebroadcast on a
 * throttle so the boundary/bodies track the ship as it flies within the cell.
 *
 * <p>No discovery / {@code isSystemKnown} gate (TASK-95 design: presence is the gate); the same
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
     * Pure builder: map every SETTLED ship's slot dim to the render bodies of its cell. IN_TRANSIT
     * ships are skipped (parked in hyperspace, no slot). A settled ship in a void cell still gets a
     * (present, empty) entry so the client clears any stale bodies for that dim and draws just the ring.
     *
     * <p>Each body's {@code localX/Y/Z} is the ship&rarr;body vector (body absolute minus ship
     * absolute, component-wise exactly like {@link GalacticCoord#distanceSqTo}); {@code BoundarySky}
     * reads it as a direction, so a body sitting at its OWN cell centre (a planet, local {@code 0,0,0})
     * still points away from a ship parked off-centre. {@link UniverseRegistry#bodiesAt} only returns
     * same-cell bodies, so the sector term is normally zero, but the full delta stays correct if a
     * cross-cell POI ever surfaces.</p>
     */
    public static Map<Integer, List<RenderBody>> buildByDim(Map<UUID, ShipLedger.Entry> snapshot,
                                                            BodyLookup lookup) {
        Map<Integer, List<RenderBody>> byDim = new LinkedHashMap<>();
        if (snapshot == null || lookup == null) {
            return byDim;
        }
        for (ShipLedger.Entry e : snapshot.values()) {
            if (e == null || e.state != ShipLedger.State.SETTLED) {
                continue;
            }
            GalacticCoord ship = e.coord;
            List<RenderBody> bodies = new ArrayList<>();
            List<SystemBody> found = lookup.bodiesAt(ship);
            if (found != null) {
                for (SystemBody b : found) {
                    GalacticCoord a = b.address();
                    long dx = (a.sectorX() - ship.sectorX()) * GalacticCoord.CELL + (a.localX() - ship.localX());
                    long dy = (a.sectorY() - ship.sectorY()) * GalacticCoord.CELL + (a.localY() - ship.localY());
                    long dz = (a.sectorZ() - ship.sectorZ()) * GalacticCoord.CELL + (a.localZ() - ship.localZ());
                    bodies.add(new RenderBody(b.kind().ordinal(), dx, dy, dz, b.dimId(), b.isDescendTarget()));
                }
            }
            byDim.put(e.slotDim, bodies);
        }
        return byDim;
    }

    /** Build the live packet from the production ledger + universe registry, or an empty packet. */
    public static PacketSystemBodiesSync currentPacket(MinecraftServer server) {
        ShipLedger ledger = SpaceSubsystem.ledger();
        UniverseRegistry reg = UniverseRegistry.get(server);
        if (ledger == null || reg == null) {
            return PacketSystemBodiesSync.forDims(null);
        }
        return PacketSystemBodiesSync.forDims(buildByDim(ledger.snapshot(), reg::bodiesAt));
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
     * all players so the boundary/bodies track ship motion. Stays silent while nothing is settled,
     * apart from ONE clearing broadcast the tick the last ship leaves.
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
