package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.nbt.NBTTagCompound;
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

        /**
         * Re-cut the parked ship in hyperspace (lane {@code tile}, anchor {@code hyperAnchor}) as a
         * {@code StorageChunk} NBT snapshot, non-destructively, so an in-flight jump survives a restart
         * (the hyperspace world is ephemeral - wiped on restart). Returns {@code null} if VS is absent or
         * the ship is gone. Called at save points; the default no-ops for the pure state-machine tests.
         */
        default NBTTagCompound snapshotParked(HyperspaceTiles.Tile tile, BlockPos hyperAnchor) {
            return null;
        }

        /**
         * Snapshot the SOURCE ship (still in its origin cell, BEFORE {@link #departToHyperspace} cuts it) as
         * a {@code StorageChunk} NBT - the depart-time FLOOR snapshot. Without it, a jump saved in the window
         * before its hyperspace ship has assembled (when {@link #snapshotParked} is still empty) would persist
         * a snapshot-less record and, on restart, strand + silently DELETE the ship. Later save points refresh
         * it via {@link #snapshotParked}. Returns {@code null} if VS is absent (the pure state-machine tests).
         */
        default NBTTagCompound snapshotSource(int srcSlotDim, BlockPos srcAnchor) {
            return null;
        }

        /**
         * Complete a RESTORED transit, which has no live hyperspace ship (that world was wiped on the
         * restart it survived): paste its {@code snapshot} into the target cell's slot world
         * {@code targetSlotDim} and re-assemble it there. Returns the ship's anchor in the target cell, or
         * {@code null} if the paste/assembly is not up yet (retried next tick) or VS is absent. The
         * live-ship counterpart is {@link #arriveFromHyperspace}.
         */
        default BlockPos completeRestored(NBTTagCompound snapshot, int targetSlotDim) {
            return null;
        }
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
        final List<UUID> crew = new ArrayList<>(); // aboard crew captured at depart (option A) - gate + reseat
        NBTTagCompound snapshot;    // packed ship (StorageChunk NBT), re-cut from hyperspace at save points
        boolean restored;           // recreated from a persisted TransitRecord: no live hyperspace ship / lane

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
    /** Offline-progress gate; {@code null} = always advance (state-machine unit tests). */
    private OfflineProgress offlineProgress;
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
        // Floor snapshot: capture the source ship BEFORE the depart crossing cuts it, so a save fired in the
        // window before the hyperspace ship assembles (snapshotParked still empty) never persists a
        // snapshot-less record - which on restart would strand + silently delete the ship. Later saves refresh
        // it from hyperspace via snapshotParked.
        NBTTagCompound initialSnapshot = crosser.snapshotSource(originSlotDim, originAnchor);
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
        Transit t = new Transit(origin, target, tile, hyperAnchor, speed, arrivalTick, now,
                new ShipTransit(origin, target));
        t.snapshot = initialSnapshot;
        transits.put(shipId, t);
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
            if (offlineProgress != null && !offlineProgress.advances(t.crew)) {
                continue; // crew-online mode, no aboard crew online: the flight is paused this tick
            }
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
            // A live transit crosses its parked hyperspace ship into the target; a RESTORED transit has no
            // hyperspace ship (that world is wiped on restart) - it pastes its persisted snapshot in.
            BlockPos arrivedAt = t.restored
                    ? crosser.completeRestored(t.snapshot, t.targetSlotDim)
                    : crosser.arriveFromHyperspace(t.tile, t.hyperAnchor, t.targetSlotDim);
            if (arrivedAt != null) {
                freeLane(t);
                it.remove(); // done: the ship now occupies the target cell (its refcount stays held)
                // Record the arrival in the durable ledger (no longer amnesiac) and mark the arrived cell
                // diverged so an eviction FLUSHES it rather than discarding the ship (closes ledger #46).
                ledgerSettle(entry.getKey(), t.target, t.targetSlotDim);
                space.markDirty(t.target);
            } else if (++t.arrivalAttempts >= MAX_ARRIVAL_ATTEMPTS) {
                // The hyperspace ship never became crossable (should not happen - assembly is async but
                // completes in a few ticks), or a restored snapshot could not be pasted. Give up: undo the
                // target materialization, free any lane.
                space.dematerialize(t.target);
                freeLane(t);
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

    /** Install the offline-progress gate (config mode + online check). {@code null} restores always-advance. */
    public void setOfflineProgress(OfflineProgress policy) {
        this.offlineProgress = policy;
    }

    /** Record the aboard crew captured at depart (option A) on an in-flight ship — for the gate + reseat. */
    public void setTransitCrew(String shipId, List<UUID> crew) {
        Transit t = transits.get(shipId);
        if (t != null) {
            t.crew.clear();
            if (crew != null) {
                t.crew.addAll(crew);
            }
        }
    }

    /**
     * Snapshot every in-flight transit as a durable {@link TransitRecord} (for the save point). Re-cuts a
     * live parked ship's block snapshot from hyperspace first (a restored transit keeps the snapshot it was
     * imported with); a null re-cut - VS hiccup - keeps the last good snapshot rather than dropping it.
     */
    public List<TransitRecord> exportTransits() {
        List<TransitRecord> out = new ArrayList<>();
        for (Map.Entry<String, Transit> e : transits.entrySet()) {
            Transit t = e.getValue();
            if (!t.restored && t.tile != null && t.hyperAnchor != null) {
                NBTTagCompound fresh = crosser.snapshotParked(t.tile, t.hyperAnchor);
                if (fresh != null) {
                    t.snapshot = fresh;
                }
            }
            out.add(new TransitRecord(e.getKey(), t.integrator.position(), t.target, t.arrivalTick,
                    t.lastTicked, t.speed, t.crew, t.snapshot));
        }
        return out;
    }

    /**
     * Recreate an in-flight transit from a persisted {@link TransitRecord} at restore (server start). The
     * restored transit is LOGICAL - it holds no hyperspace lane and no live parked ship (that world is
     * ephemeral); it advances its coordinate from where it was persisted and, on arrival, PASTES its
     * {@link TransitRecord#snapshot} into the target cell ({@link Crosser#completeRestored}) rather than
     * crossing a live hyperspace ship. Idempotent: a no-op if the ship is already in transit. The durable
     * ledger is re-marked {@code IN_TRANSIT} (it persists SETTLED entries only, so an in-flight ship is
     * absent from the restored ledger until this runs).
     */
    public void importTransit(TransitRecord record) {
        if (record == null || record.shipId == null || record.shipId.isEmpty()
                || transits.containsKey(record.shipId)) {
            return; // absent / blank / corrupt id, or already flying (idempotent restore)
        }
        // A snapshot-less record cannot rematerialize the ship's blocks (origin was cut, hyperspace is
        // ephemeral) - a restored transit for it would only spin to MAX_ARRIVAL_ATTEMPTS then silently delete
        // it. The depart-time floor snapshot makes this unreachable in normal operation; drop a corrupt one.
        if (record.snapshot == null) {
            LOGGER.error("[SPACE] persisted transit for ship {} has no block snapshot - cannot restore the "
                    + "ship; dropping the record", record.shipId);
            return;
        }
        Transit t = new Transit(record.position, record.target, null, null, record.speed,
                record.arrivalTick, record.lastTicked, new ShipTransit(record.position, record.target));
        t.restored = true;
        t.snapshot = record.snapshot;
        if (record.crew != null) {
            t.crew.addAll(record.crew);
        }
        transits.put(record.shipId, t);
        ledgerBeginTransit(record.shipId, record.target);
    }

    /** Free a transit's hyperspace lane; a restored transit holds none ({@code tile == null}). */
    private void freeLane(Transit t) {
        if (t.tile != null) {
            tiles.free(t.tile);
        }
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
