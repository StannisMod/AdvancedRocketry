package zmaster587.advancedRocketry.space;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.util.math.BlockPos;

/**
 * The transit state machine for tier-2 ships. A ship jumping between bubble
 * cells is never frozen for the whole trip: it is <b>parked in a shared hyperspace world</b> (a live,
 * ticking bubble) while {@link ShipTransit} advances its {@link GalacticCoord} <i>logically</i>, and it
 * makes exactly <b>two momentary pack/paste crossings</b> - depart (origin cell &rarr; hyperspace) and
 * arrive (hyperspace &rarr; target cell). Passengers walk the whole transit; only the two crossings are
 * sub-second freezes.
 *
 * <p>This class owns the wiring - the per-ship lifecycle, the hyperspace lane allocation, the transit
 * integration, and the <b>refcount handoff</b> from the origin cell to the target - but not the world
 * operations. The actual VS crossing + park/unpark goes through the injected {@link Crosser} seam (the
 * production impl calls {@code VSIntegration}; tests substitute a recording fake), so the state machine
 * is exercised deterministically without a live server or VS.</p>
 *
 * <p>Refcount handoff: a ship in a bubble holds one occupant refcount on its cell. On <b>depart</b> the
 * ship leaves the origin cell &rarr; {@link SpaceManager#dematerialize}. On <b>arrive</b> it enters the
 * target cell &rarr; {@link SpaceManager#materialize}. The shared hyperspace world is a permanent
 * singleton (not pool-managed), so it holds no cell refcount - only a hyperspace lane. Server main
 * thread only.</p>
 */
public final class ShipTransitManager {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/space");

    /**
     * Max ticks to retry a stalled arrival crossing before giving up. VS assembles a crossed ship
     * asynchronously (physics thread), so the ship is not registered in the hyperspace world for a few
     * ticks after departure; the arrival crossing retries until it is. ~10 s at 20 tps.
     */
    private static final int MAX_ARRIVAL_ATTEMPTS = 200;

    /**
     * The world-operation seam: perform the two per-ship crossings and park/unpark the ship. Kept out of
     * the pure state machine so it can be faked in tests. The production implementation drives
     * {@code VSIntegration.crossShip} + {@code parkShipAt}/{@code unparkShipAt}.
     */
    public interface Crosser {
        /**
         * Depart: cross the ship anchored at {@code srcAnchor} in the origin cell's slot world
         * {@code srcSlotDim} into the shared hyperspace world at {@code tile}, and PARK it (physics off).
         * Returns the ship's new anchor in hyperspace, or {@code null} if the crossing failed.
         */
        BlockPos departToHyperspace(int srcSlotDim, BlockPos srcAnchor, HyperspaceTiles.Tile tile);

        /**
         * Arrive: cross the parked ship at {@code hyperAnchor} (lane {@code tile}) into the target cell's
         * slot world {@code targetSlotDim}, and UNPARK it (physics on). Returns the ship's anchor in the
         * target cell, or {@code null} if the crossing failed.
         */
        BlockPos arriveFromHyperspace(HyperspaceTiles.Tile tile, BlockPos hyperAnchor, int targetSlotDim);
    }

    /** Per-ship in-flight state. */
    private static final class Transit {
        final GalacticCoord origin;
        final GalacticCoord target;
        final HyperspaceTiles.Tile tile;
        final BlockPos hyperAnchor;
        final long speed;
        final long arrivalTick;     // world-time tick the flight is expected to complete (linear estimate)
        long lastTicked;            // world-time of the last advance (drives the offline-progress Δ)
        ShipTransit integrator;
        boolean targetMaterialized; // the target cell has been loaded (refcount handoff, half 2, done once)
        int targetSlotDim;          // the slot the target cell is bound to (valid once targetMaterialized)
        int arrivalAttempts;        // retries of a stalled arrival crossing (async VS assembly)

        Transit(GalacticCoord origin, GalacticCoord target, HyperspaceTiles.Tile tile, BlockPos hyperAnchor,
                long speed, long arrivalTick, long nowTick, ShipTransit integrator) {
            this.origin = origin;
            this.target = target;
            this.tile = tile;
            this.hyperAnchor = hyperAnchor;
            this.speed = speed;
            this.arrivalTick = arrivalTick;
            this.lastTicked = nowTick;
            this.integrator = integrator;
        }
    }

    private final SpaceManager space;
    private final HyperspaceTiles tiles;
    private final Crosser crosser;
    /** The durable ledger to keep in sync (IN_TRANSIT on depart, SETTLED on arrival). Null in state-machine unit tests. */
    private final ShipLedger ledger;
    /** Persist-safe world-time clock, stamping {@code arrivalTick}/{@code lastTicked}. */
    private final LongSupplier clock;
    private final Map<String, Transit> transits = new LinkedHashMap<>();

    /** State-machine only: no ledger sync, a zero clock. Used by the transit-wiring unit tests. */
    public ShipTransitManager(SpaceManager space, HyperspaceTiles tiles, Crosser crosser) {
        this(space, tiles, crosser, null, () -> 0L);
    }

    public ShipTransitManager(SpaceManager space, HyperspaceTiles tiles, Crosser crosser,
                              ShipLedger ledger, LongSupplier clock) {
        this.space = space;
        this.tiles = tiles;
        this.crosser = crosser;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Begin a jump. The ship (identified by {@code shipId}) must currently be materialized in
     * {@code origin} (slot {@code originSlotDim}, world anchor {@code originAnchor}). Allocates a
     * hyperspace lane, performs the departure crossing + park, releases the origin cell, and starts
     * integrating toward {@code target}. Returns {@code true} if the departure crossing succeeded (the
     * ship is now in transit); {@code false} if it was already in transit or the crossing failed (no
     * state changed, no cell released).
     */
    public boolean beginTransit(String shipId, GalacticCoord origin, int originSlotDim, BlockPos originAnchor,
                                GalacticCoord target, long speedBlocksPerTick) {
        if (transits.containsKey(shipId)) {
            return false; // already in transit
        }
        HyperspaceTiles.Tile tile = tiles.allocate();
        BlockPos hyperAnchor = crosser.departToHyperspace(originSlotDim, originAnchor, tile);
        if (hyperAnchor == null) {
            tiles.free(tile);
            LOGGER.warn("[SPACE] transit depart crossing failed for ship {} - jump aborted", shipId);
            return false;
        }
        // Refcount handoff, half 1: the ship has left the origin cell.
        space.dematerialize(origin);
        long speed = Math.max(1L, speedBlocksPerTick);
        long now = clock.getAsLong();
        // Linear ETA: the integrator steps `speed` blocks/tick until the final within-reach snap.
        long arrivalTick = now + (long) Math.ceil(origin.distanceTo(target) / (double) speed);
        transits.put(shipId, new Transit(origin, target, tile, hyperAnchor, speed, arrivalTick, now,
                new ShipTransit(origin, target)));
        ledgerBeginTransit(shipId, target);
        return true;
    }

    /**
     * Advance every in-flight ship one tick. A ship still en route stays parked (its coordinate steps
     * logically). A ship that reaches its target performs the arrival crossing: materialize the target
     * cell (refcount handoff, half 2), cross + unpark into it, and free the hyperspace lane.
     */
    public void tick() {
        if (transits.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Transit>> it = transits.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Transit> entry = it.next();
            Transit t = entry.getValue();
            t.integrator = t.integrator.advance(t.speed);
            t.lastTicked = now;
            if (!t.integrator.arrived()) {
                continue; // still en route - parked, coordinate advanced logically
            }
            // Refcount handoff, half 2: load the target cell once (kept live for the arrived ship).
            if (!t.targetMaterialized) {
                t.targetSlotDim = space.materialize(t.target);
                t.targetMaterialized = true;
            }
            BlockPos arrivedAt = crosser.arriveFromHyperspace(t.tile, t.hyperAnchor, t.targetSlotDim);
            if (arrivedAt != null) {
                tiles.free(t.tile);
                it.remove(); // done: the ship now occupies the target cell (its refcount stays held)
                // Record the arrival in the durable ledger (no longer amnesiac) and mark the arrived cell
                // diverged so an eviction FLUSHES it rather than discarding the ship (closes ledger #46).
                ledgerSettle(entry.getKey(), t.target, t.targetSlotDim);
                space.markDirty(t.target);
            } else if (++t.arrivalAttempts >= MAX_ARRIVAL_ATTEMPTS) {
                // The hyperspace ship never became crossable (should not happen - assembly is async but
                // completes in a few ticks). Give up: undo the target materialization, free the lane.
                space.dematerialize(t.target);
                tiles.free(t.tile);
                it.remove();
                LOGGER.error("[SPACE] transit arrival crossing never succeeded for ship {} after {} ticks - "
                        + "ship stranded in hyperspace", entry.getKey(), MAX_ARRIVAL_ATTEMPTS);
            }
            // else: retry the arrival crossing next tick (target stays materialized, lane stays held).
        }
    }

    /** Whether {@code shipId} is currently in transit. */
    public boolean isInTransit(String shipId) {
        return transits.containsKey(shipId);
    }

    /** Number of ships currently in transit (hyperspace lanes in use). */
    public int inTransitCount() {
        return transits.size();
    }

    /** Remaining transit distance (blocks) for {@code shipId}, or {@code -1} if it is not in transit. */
    public double remainingDistance(String shipId) {
        Transit t = transits.get(shipId);
        return t == null ? -1.0 : t.integrator.remainingDistance();
    }

    /** Estimated arrival tick (world-time) for {@code shipId}, or {@code -1} if not in transit. Tunable ETA. */
    public long arrivalTick(String shipId) {
        Transit t = transits.get(shipId);
        return t == null ? -1L : t.arrivalTick;
    }

    // ── Durable-ledger sync (a no-op when no ledger is wired, e.g. the state-machine unit tests) ──

    private void ledgerBeginTransit(String shipId, GalacticCoord target) {
        if (ledger == null) {
            return;
        }
        UUID id = toUuid(shipId);
        if (id != null) {
            ledger.beginTransit(id, target);
        }
    }

    private void ledgerSettle(String shipId, GalacticCoord coord, int slotDim) {
        if (ledger == null) {
            return;
        }
        UUID id = toUuid(shipId);
        if (id != null) {
            ledger.settle(id, coord, slotDim);
        }
    }

    /** The transit map is keyed by the AR ship UUID string; a non-UUID key (test fixtures) skips the sync. */
    private static UUID toUuid(String shipId) {
        try {
            return UUID.fromString(shipId);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
