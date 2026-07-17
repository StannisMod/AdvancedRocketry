package zmaster587.advancedRocketry.space;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.util.math.BlockPos;

/**
 * The tier-2 <b>entry on-ramp</b>: how a ship first enters space. Entry is ASCENT — a pilot climbs
 * past the launch dimension's {@code getOrbitHeight()} ceiling — a phase distinct from the
 * hyperjump; ascent is the SAFE exit, not the only one. The flight computer's tick detects the
 * crossing and calls {@link #requestEntry}; this controller then:
 *
 * <ol>
 *   <li>resolves the launch planet's galactic address through the universe registry (its OWN zone
 *       cell), falling back to the configured home-system anchor;</li>
 *   <li>places the ship on a spawn RING outside the descent radius — the hysteresis contract with
 *       the descent trigger, so an entry can never immediately re-descend;</li>
 *   <li>materializes the cell (an exhausted pool REFUSES entry — the ship simply stays below the
 *       ceiling), pins the slot world, and runs the per-ship crossing into it;</li>
 *   <li>re-seats the crew on the re-assembled ship, rigid-teleports the pose to the honest-3D
 *       realization of the entry coordinate (world Y &asymp; local Y + HALF_CELL + band), and
 *       settles the ship in the {@link ShipLedger}.</li>
 * </ol>
 *
 * <p>Crossing + re-assembly are asynchronous, so steps 4 run over several ticks with retries (the
 * transit-arrival pattern). World-touching operations go through the injected {@link Ops} seam
 * (production: {@code VSShipEntryOps}) so the state machine is testable without VS. Server main
 * thread only.</p>
 */
public final class ShipEntryController {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/space");

    /**
     * Descent proximity radius R (blocks, cell-local) around a body's POI position — the SINGLE
     * owner of R: the descent trigger reads THIS constant. {@code tunable}.
     */
    public static final long DESCENT_RADIUS_BLOCKS = 512L;

    /**
     * Entry spawn-ring distance from the launch body's POI (blocks, cell-local). MUST stay
     * strictly greater than {@link #DESCENT_RADIUS_BLOCKS} — the entry&harr;descent hysteresis
     * contract (an entering ship never spawns inside the descent trigger). {@code tunable}.
     */
    public static final long ENTRY_RING_BLOCKS = DESCENT_RADIUS_BLOCKS * 2L;

    /** Max ticks to retry the re-seat + pose-teleport half before giving up (async VS assembly). */
    private static final int MAX_SETTLE_ATTEMPTS = 200;

    /** Ticks a ship waits after a refused/failed entry before the ceiling check may re-trigger. */
    private static final int RETRY_COOLDOWN_TICKS = 100;

    /** Paste-lane geometry inside a slot world: entries paste along their own -Z row so they can
     *  never overlap a transit ARRIVAL lane (those paste along +X at z = 0). */
    private static final int ENTRY_PASTE_Z = -1024;
    private static final int ENTRY_PASTE_Y = 200;
    private static final int ENTRY_LANE_STRIDE = 64;
    private static final int ENTRY_LANE_COUNT = 8;

    /** The world-operation seam (production: {@code VSShipEntryOps}); fakeable in tests. Worlds
     *  are addressed by dimension id only, so the state machine itself never touches a World
     *  type (the {@link ShipTransitManager.Crosser} discipline). */
    public interface Ops {
        /** The ship's live world position read off its managed block, or {@code null}. */
        double[] shipWorldPosition(int dimId, BlockPos afcPos);

        /** Enumerate + dismount the seated crew of the ship at {@code afcPos}. Pre-cut. */
        List<CrewTransfer.Crew> captureCrew(int dimId, BlockPos afcPos, double[] shipWorldPos);

        /** Cross the ship at {@code srcShipPos} into slot {@code slotDim} at the paste point.
         *  Returns the re-assembly anchor, or {@code null} on failure. */
        BlockPos cross(int srcDimId, double[] srcShipPos, int slotDim,
                       int pasteX, int pasteY, int pasteZ);

        /** Pin {@code dimId} loaded across the crossing (the arrival pin pattern). */
        void pinDim(int dimId);

        /** Queue every ship in the slot world loaded (async assembly may not have a player yet). */
        void loadShips(int slotDim);

        /** Re-seat the captured crew on the re-assembled ship. {@code false} = retry next tick. */
        boolean reseat(int slotDim, BlockPos anchor, List<CrewTransfer.Crew> crew);

        /** Rigid-teleport the ship near {@code anchor} to the pose position, carrying riders.
         *  The ship comes out PARKED. {@code false} = ship not up yet, retry. */
        boolean teleportPoseWithRiders(int slotDim, BlockPos anchor, double px, double py, double pz);

        /** Re-enable physics on the ship at the (post-teleport) pose position. */
        void unparkAt(int slotDim, double px, double py, double pz);

        /** Player-facing message to the captured crew (a refusal, a failure, an arrival). */
        void messageCrew(List<CrewTransfer.Crew> crew, String langKey, Object... args);
    }

    /** Resolves a launch dimension id to the launch BODY's full galactic address (cell + local
     *  offset), or {@code null} for "no placement" (the config home anchor is used). Production
     *  wires the universe registry's {@code coordForPlanet} + zone-body match. */
    @FunctionalInterface
    public interface LaunchCoordResolver {
        GalacticCoord launchBodyAddress(int dimId);
    }

    /** One in-flight entry (crossing done; settling over ticks). */
    private static final class PendingEntry {
        final UUID shipId;
        final int slotDim;
        final GalacticCoord entryCoord;
        final List<CrewTransfer.Crew> crew;
        BlockPos anchor;
        boolean reseated;
        boolean poseDone;
        int attempts;

        PendingEntry(UUID shipId, int slotDim, GalacticCoord entryCoord,
                     List<CrewTransfer.Crew> crew, BlockPos anchor) {
            this.shipId = shipId;
            this.slotDim = slotDim;
            this.entryCoord = entryCoord;
            this.crew = crew;
            this.anchor = anchor;
        }
    }

    private final SpaceManager space;
    private final ShipLedger ledger;
    private final Ops ops;
    private final LaunchCoordResolver coordResolver;
    private final LongSupplier clock;

    private final Map<UUID, PendingEntry> pending = new LinkedHashMap<>();
    /** shipId -> earliest tick a refused/failed entry may re-trigger. */
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private int laneCounter;

    public ShipEntryController(SpaceManager space, ShipLedger ledger, Ops ops,
                               LaunchCoordResolver coordResolver, LongSupplier clock) {
        this.space = space;
        this.ledger = ledger;
        this.ops = ops;
        this.coordResolver = coordResolver;
        this.clock = clock;
    }

    /**
     * Pure trigger predicate for the flight computer's ceiling check: entry fires only from a
     * planet-side dimension (never a slot/hyperspace world), only with a pilot actually flying,
     * and only once the ship's pose has climbed past the dimension's orbit line.
     */
    public static boolean shouldTriggerEntry(boolean isSpaceSubsystemWorld, boolean pilotPresent,
                                             double shipWorldY, int orbitHeight) {
        return !isSpaceSubsystemWorld && pilotPresent && shipWorldY > orbitHeight;
    }

    /**
     * Begin an entry for the ship whose flight computer sits at {@code afcPos} in dimension
     * {@code launchDimId}: resolve the target coordinate, materialize its cell, run the crossing,
     * and queue the multi-tick settle. Returns {@code true} if the crossing was started (the ship
     * has left the launch world). Refusals (exhausted pool) and failures message the crew and arm
     * a retry cooldown — the ship stays below the ceiling and the check may fire again later.
     */
    public boolean requestEntry(int launchDimId, BlockPos afcPos, UUID shipId) {
        if (shipId == null || pending.containsKey(shipId) || ledger.get(shipId) != null) {
            return false; // already entering / already in space
        }
        long now = clock.getAsLong();
        Long cooldown = retryAfter.get(shipId);
        if (cooldown != null && now < cooldown) {
            return false;
        }

        double[] shipPos = ops.shipWorldPosition(launchDimId, afcPos);
        if (shipPos == null) {
            return false; // not on a physics ship (or it unloaded mid-check)
        }
        GalacticCoord entryCoord = resolveEntryCoord(launchDimId, shipId);

        // Crew first: the crossing cuts the seat blocks; a post-cut capture finds nothing.
        List<CrewTransfer.Crew> crew = ops.captureCrew(launchDimId, afcPos, shipPos);

        int slotDim;
        try {
            slotDim = space.materialize(entryCoord);
        } catch (SpaceManager.PoolExhaustedException full) {
            // Refuse entry: a normal, surfaced outcome — the ship stays below the ceiling.
            LOGGER.warn("[SPACE] entry refused for ship {}: {}", shipId, full.getMessage());
            ops.messageCrew(crew, "msg.shipentry.refused");
            retryAfter.put(shipId, now + RETRY_COOLDOWN_TICKS);
            return false;
        }
        // Pin the freshly-materialized slot across the async crossing (the arrival pin pattern:
        // an occupant-less slot auto-unloads at tick end, discarding the ship VS is assembling).
        ops.pinDim(slotDim);

        int lane = (laneCounter++ % ENTRY_LANE_COUNT);
        BlockPos anchor = ops.cross(launchDimId, shipPos, slotDim,
                lane * ENTRY_LANE_STRIDE, ENTRY_PASTE_Y, ENTRY_PASTE_Z);
        if (anchor == null) {
            LOGGER.error("[SPACE] entry crossing failed for ship {} from dim {}", shipId, launchDimId);
            space.dematerialize(entryCoord);
            ops.messageCrew(crew, "msg.shipentry.failed");
            retryAfter.put(shipId, now + RETRY_COOLDOWN_TICKS);
            return false;
        }
        // The paste diverged the cell from its procedural seed — eviction must flush, not discard.
        space.markDirty(entryCoord);
        pending.put(shipId, new PendingEntry(shipId, slotDim, entryCoord, crew, anchor));
        LOGGER.info("[SPACE] entry crossing started: ship {} -> cell {} (slot {})",
                shipId, entryCoord.cellKey(), slotDim);
        return true;
    }

    /** The launch body's address + spawn ring, or the config home anchor when unplaced. The ring
     *  direction is derived from the ship id, so simultaneous entries at one body spread out. */
    private GalacticCoord resolveEntryCoord(int launchDimId, UUID shipId) {
        GalacticCoord body = coordResolver.launchBodyAddress(launchDimId);
        if (body == null) {
            body = GalacticCoord.ORIGIN;
        }
        double angle = ((shipId.hashCode() & 0xFF) / 256.0) * Math.PI * 2.0;
        long dx = Math.round(Math.cos(angle) * ENTRY_RING_BLOCKS);
        long dz = Math.round(Math.sin(angle) * ENTRY_RING_BLOCKS);
        return body.plusLocal(dx, 0L, dz);
    }

    /**
     * Advance every in-flight entry one tick: keep the slot's ships load-queued, re-seat the crew
     * once the re-assembled seats exist, rigid-teleport the pose to the honest realization of the
     * entry coordinate, then unpark a tick later and settle the ship in the ledger.
     */
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, PendingEntry>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            PendingEntry e = it.next().getValue();
            ops.loadShips(e.slotDim);
            if (!e.reseated) {
                e.reseated = ops.reseat(e.slotDim, e.anchor, e.crew);
            }
            double[] pose = CellWorldMapper.poseWorldOf(e.entryCoord);
            if (e.reseated && !e.poseDone) {
                e.poseDone = ops.teleportPoseWithRiders(e.slotDim, e.anchor, pose[0], pose[1], pose[2]);
            } else if (e.poseDone) {
                // A tick after the pose write: the transform adoption has propagated — unpark and
                // settle. The pilot flies out on the spawn ring.
                ops.unparkAt(e.slotDim, pose[0], pose[1], pose[2]);
                ledger.settle(e.shipId, e.entryCoord, e.slotDim);
                ops.messageCrew(e.crew, "msg.shipentry.arrived");
                LOGGER.info("[SPACE] entry settled: ship {} at {} (slot {})",
                        e.shipId, e.entryCoord, e.slotDim);
                it.remove();
                continue;
            }
            if (++e.attempts >= MAX_SETTLE_ATTEMPTS) {
                // The re-assembled ship never became workable. The blocks are in the slot world
                // (the cell is dirty, so it flushes); give up cleanly rather than spin forever.
                LOGGER.error("[SPACE] entry settle never completed for ship {} after {} ticks - "
                        + "ship left at the paste site in slot {}", e.shipId, MAX_SETTLE_ATTEMPTS,
                        e.slotDim);
                ledger.settle(e.shipId, e.entryCoord, e.slotDim);
                ops.messageCrew(e.crew, "msg.shipentry.failed");
                it.remove();
            }
        }
    }

    /** Whether {@code shipId} has an entry in flight. */
    public boolean isEntering(UUID shipId) {
        return pending.containsKey(shipId);
    }

    /** Number of in-flight entries. */
    public int enteringCount() {
        return pending.size();
    }
}
