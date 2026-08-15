package zmaster587.advancedRocketry.space;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync.RenderBody;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync.RenderNebula;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.libVulpes.network.PacketHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side producer for the {@link PacketSystemBodiesSync} render channel: turns the set of
 * MATERIALIZED CELLS into the per-slot-dim list of bodies the client draws in the slot-world sky
 * ({@code BoundarySky}). Each body is carried as a DIRECTION from the observer point in that cell,
 * measured through both cells' frames at the broadcast tick (see {@link #buildByDim}). Sent to a
 * player at login and rebroadcast on a throttle so the boundary/bodies track a ship as it flies
 * within the cell — and so the SYSTEM moves around it while it sits still.
 *
 * <p><b>The sky shows the SYSTEM, not the cell.</b> A pilot parked in interplanetary void still sees
 * his star and his system's planets; what he sees of each is where it is right now relative to him.
 * The feed is the system's bodies UNIONED with whatever is keyed at the observer's
 * own cell — the union is not tidiness, since the system read answers empty for a cell no anchor
 * attributes and aggregates POIs of body cells only, so a straight swap would erase an orbital
 * station standing in the observer's own void cell.</p>
 *
 * <p><b>The sky belongs to the cell, not to a ship.</b> The feed is keyed off the cell&rarr;slot
 * bindings ({@link SpaceManager#loadedCells}) and never off a ship's lifecycle state. A world that is
 * a live cell has that cell's surroundings in its sky for everyone in it: a pilot whose ship is
 * mid-jump, a passenger, a crew member who walked off the hull, or someone left behind by a ship that
 * departed. Deriving the feed from settled ships instead made all of those skies blank &mdash; and the
 * blank was indistinguishable from an empty cell.</p>
 *
 * <p>No discovery / {@code isSystemKnown} gate (by design, presence is the gate). Each player is sent
 * ONLY the dimension he is in: see {@link #broadcastTo}. Server main thread only.</p>
 */
public final class SystemBodiesProducer {

    /** Rebroadcast cadence in server ticks (~1 s at 20 tps): tracks the ship's within-cell motion. tunable. */
    private static final int BROADCAST_INTERVAL_TICKS = 20;

    private static int tickCounter;

    private SystemBodiesProducer() {
    }

    /** The per-cell body source — the seam that lets {@link #buildByDim} be unit-tested without a server. */
    public interface BodyLookup {
        /** What the sky of {@code cell} shows: the system's bodies unioned with that cell's own. */
        List<SystemBody> skyBodiesAt(GalacticCoord cell);
    }

    /**
     * The STATIC reading of {@link #buildByDim} — every cell sits where its name says forever. What a
     * caller with no registry has, and the honest fixture for the keying contracts (which cell is
     * fed, from which observer), none of which depend on a frame moving.
     */
    public static Map<Integer, List<RenderBody>> buildByDim(Map<String, Integer> loadedCells,
                                                           Map<UUID, ShipLedger.Entry> snapshot,
                                                           BodyLookup lookup) {
        return buildByDim(loadedCells, snapshot, lookup, CellFrames.STATIC, 0L);
    }

    /**
     * Pure builder: map every materialized cell's slot dim to the render bodies of that cell. A live
     * cell that holds no body still gets a (present, empty) entry, so the client clears any stale
     * bodies for that dim and draws just the ring; a cell bound to no slot keys nothing, because there
     * is no world whose sky it would be.
     *
     * <p>Each body's {@code localX/Y/Z} is the observer&rarr;body vector evaluated at
     * {@code worldTick} through BOTH frame origins, so its DIRECTION is where to draw the body and its
     * MAGNITUDE is the true distance to it at that moment. Computing it over the static grid
     * instead — as this did — gives a body in another cell a distance that is a function of its
     * cell's name rather than of where it is, and a planet that is really receding never moves on the
     * sky at all.</p>
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
     * @param frames      where each cell is at {@code worldTick}
     */
    public static Map<Integer, List<RenderBody>> buildByDim(Map<String, Integer> loadedCells,
                                                            Map<UUID, ShipLedger.Entry> snapshot,
                                                            BodyLookup lookup,
                                                            CellFrames frames,
                                                            long worldTick) {
        Map<Integer, List<RenderBody>> byDim = new LinkedHashMap<>();
        if (loadedCells == null || lookup == null) {
            return byDim;
        }
        CellFrames geometry = frames == null ? CellFrames.STATIC : frames;
        for (Map.Entry<String, Integer> bound : loadedCells.entrySet()) {
            Integer slotDim = bound.getValue();
            GalacticCoord cell = GalacticCoord.fromCellKey(bound.getKey());
            if (slotDim == null || slotDim == SpaceManager.UNBOUND_SLOT || cell == null) {
                continue;
            }
            AbsolutePos observer = geometry.absoluteOf(observerIn(cell, snapshot), worldTick);
            List<RenderBody> bodies = new ArrayList<>();
            List<SystemBody> found = lookup.skyBodiesAt(cell);
            if (found != null) {
                for (SystemBody b : found) {
                    BlockDelta dir = b.absoluteAt(worldTick).minus(observer);
                    // "Can a ship land here", not "does a world already exist". A procedural planet has
                    // no dimension until a descent mints one, so highlighting only realized bodies
                    // would hide the descent boundary of every world nobody has visited — which is
                    // precisely the set a pilot is out there looking for. The flag is a render hint;
                    // the logic that needs a real dimension still asks isDescendTarget().
                    boolean descendable = b.kind().canDescend();
                    // The shell is sent per body, from the ONE place that sizes it. A body that
                    // cannot be descended to has none, and zero is what says so: the client must
                    // not have to know which kinds have a shell to render a range correctly. It is
                    // gated on the SAME predicate as the flag beside it — a body advertised as
                    // descendable while carrying a zero shell would draw a boundary of no radius,
                    // which is the unvisited-planet bug above coming back through the other field.
                    long shell = descendable ? DescentShell.radiusAround(b) : 0L;
                    bodies.add(new RenderBody(b.kind().ordinal(), dir.dx(), dir.dy(), dir.dz(),
                            renderDimIdOf(b), descendable, shell));
                }
            }
            byDim.put(slotDim, bodies);
        }
        return byDim;
    }

    /**
     * The dimension id the CLIENT should look this body's appearance up under.
     *
     * <p>A star has no dimension of its own and carries {@link Constants#INVALID_PLANET}, which the
     * client's lenient lookup answers with the OVERWORLD's properties — so the star in the sky wore
     * Earth's texture. An authored star does have a proxy dimension at
     * {@code STAR_ID_OFFSET + starId}; a PROCEDURAL one does not (its synthetic star id is negative
     * and no proxy is registered for it), so it keeps {@code INVALID_PLANET} and the renderer draws
     * it untextured rather than as somebody else's planet.</p>
     */
    static int renderDimIdOf(SystemBody body) {
        if (body.kind() == SystemBodyKind.STAR && body.dimId() == Constants.INVALID_PLANET
                && body.starId() >= 0) {
            return Constants.STAR_ID_OFFSET + body.starId();
        }
        return body.dimId();
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

    /** The live per-slot-dim render bodies from the production bindings + universe registry. */
    public static Map<Integer, List<RenderBody>> currentByDim(MinecraftServer server) {
        ShipLedger ledger = SpaceSubsystem.ledger();
        UniverseRegistry reg = UniverseRegistry.get(server);
        SpaceManager space = SpaceSubsystem.space();
        if (reg == null || space == null) {
            return new LinkedHashMap<>();
        }
        return buildByDim(space.loadedCells(), ledger == null ? null : ledger.snapshot(),
                reg::skyBodiesAt, reg, SpaceSubsystem.spaceClock());
    }

    /** Build the live packet from the production cell bindings + universe registry, or an empty packet. */
    public static PacketSystemBodiesSync currentPacket(MinecraftServer server) {
        return PacketSystemBodiesSync.forDims(currentByDim(server),
                SkyNebulaeProducer.currentByDim(server));
    }

    /**
     * Send {@code player} the bodies of the dimension he is actually in, and nothing else.
     *
     * <p>The payload is per-DIMENSION but the broadcast used to be {@code sendToAll}, so every player
     * received every live cell's sky — the whole pool, once a second, to everyone. That was already
     * wasteful when a cell's entry was its own occupants; now that an entry is a whole system it is
     * roughly an order of magnitude worse. A player can only ever see one sky, so he is sent one.</p>
     *
     * <p>A player standing in a slot world with no entry is sent a present-and-EMPTY one: "present and
     * empty" and "absent" are different states — the empty one is what tells the client to clear a
     * stale sky, where an absent one would leave it standing. A player who is not in a slot world at
     * all is sent nothing.</p>
     */
    private static void broadcastTo(EntityPlayerMP player, Map<Integer, List<RenderBody>> byDim,
                                    Map<Integer, List<RenderNebula>> nebulaeByDim) {
        if (player == null) {
            return;
        }
        int dim = player.world == null ? player.dimension : player.world.provider.getDimension();
        List<RenderBody> bodies = byDim.get(dim);
        if (bodies == null) {
            if (!SpaceSlotPool.slotDims().contains(dim)) {
                return; // not a cell world: this channel has nothing to say about it
            }
            bodies = Collections.emptyList();
        }
        // The clouds follow the bodies through the same gate: a cell that is his gets both halves of
        // its sky, and a cell that is not gets neither. Absent here means the same as it does above —
        // the cell has no cloud, and the empty list is what clears a stale one.
        List<RenderNebula> clouds = nebulaeByDim == null ? null : nebulaeByDim.get(dim);
        Map<Integer, List<RenderBody>> one = new LinkedHashMap<>();
        one.put(dim, bodies);
        Map<Integer, List<RenderNebula>> oneSky = new LinkedHashMap<>();
        oneSky.put(dim, clouds == null ? Collections.<RenderNebula>emptyList() : clouds);
        PacketHandler.sendToPlayer(PacketSystemBodiesSync.forDims(one, oneSky), player);
    }

    /** Login send: give a joining player the sky of the dimension he arrived in. */
    public static void sendToPlayer(EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        try {
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            broadcastTo(player, currentByDim(server), SkyNebulaeProducer.currentByDim(server));
        } catch (Throwable t) {
            AdvancedRocketry.logger.warn("[SPACE] system-bodies login send failed", t);
        }
    }

    /**
     * Throttled rebroadcast tick: every {@link #BROADCAST_INTERVAL_TICKS}, push each player the bodies
     * of his own dimension, so the boundary/bodies track both his ship's motion and the system's.
     */
    public static void onBroadcastTick(MinecraftServer server) {
        if (++tickCounter < BROADCAST_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        if (server == null) {
            return;
        }
        try {
            Map<Integer, List<RenderBody>> byDim = currentByDim(server);
            Map<Integer, List<RenderNebula>> nebulaeByDim = SkyNebulaeProducer.currentByDim(server);
            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                broadcastTo(player, byDim, nebulaeByDim);
            }
        } catch (Throwable t) {
            AdvancedRocketry.logger.warn("[SPACE] system-bodies broadcast failed", t);
        }
    }

    /** Reset the broadcast cadence and the sky's derived caches (server stop). */
    public static void reset() {
        tickCounter = 0;
        SkyNebulaeProducer.reset();
    }
}
