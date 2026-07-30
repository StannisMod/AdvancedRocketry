package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.Collections;
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
         * slot world {@code targetSlotDim}. Returns the ship's PASTE anchor in the target cell, or
         * {@code null} if the crossing failed. The ship stays parked in the paste lane until
         * {@link #settleArrivedPose} moves it onto the coordinate it was aimed at.
         */
        BlockPos arriveFromHyperspace(HyperspaceTiles.Tile tile, BlockPos hyperAnchor, int targetSlotDim);

        /**
         * Settle an arrived ship (live or restored) onto the world pose realizing its TARGET coordinate:
         * rigid-teleport it there carrying its riders, then unpark it (physics on). Returns the ship's
         * anchor at that final pose, or {@code null} while the asynchronous re-assembly is not queryable
         * yet - the caller retries next tick and never re-crosses. This is the arrival's half of the
         * paste-then-settle shape the entry on-ramp and the descent already use; without it a ship
         * arrives in the destination's BLOCK band while every reader of its address works in the POSE
         * band, so the settled coordinate lands in a neighbouring cell.
         *
         * <p>The default returns {@code pasteAnchor} unchanged - the pure state-machine tests have no
         * world to realize a pose in.</p>
         */
        default BlockPos settleArrivedPose(int targetSlotDim, BlockPos pasteAnchor,
                                           double px, double py, double pz) {
            return pasteAnchor;
        }

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

        /**
         * Capture the seated crew of the ship being departed - anchored at world-frame {@code srcAnchor} in
         * origin slot {@code srcSlotDim} - BEFORE {@link #departToHyperspace} cuts its seat blocks (a post-cut
         * capture finds nothing). The production impl stashes the full crew (keyed by {@code shipId}) for the
         * reseat at arrival and returns the aboard player UUIDs, which drive the offline-progress gate and
         * persist on the transit record. The default (pure state-machine / no-VS) captures nothing.
         */
        default List<UUID> captureCrew(int srcSlotDim, BlockPos srcAnchor, String shipId) {
            return Collections.emptyList();
        }

        /**
         * Re-seat the crew captured for {@code shipId} onto the re-assembled ship at {@code arrivalAnchor} in
         * target slot {@code targetSlotDim}. Returns {@code true} when every aboard crew member is re-seated
         * OR there is none to move - a crewless transit, a restored transit (its stash is wiped on the restart
         * it survived), or an abort that never cut - and {@code false} to retry next tick while the async
         * re-assembly's seat tiles are not up yet. Idempotent across retries (already-seated riders are not
         * double-mounted). The default returns {@code true} (nothing to reseat).
         */
        default boolean reseatCrew(int targetSlotDim, BlockPos arrivalAnchor, String shipId) {
            return true;
        }

        /**
         * Tell the aboard crew something, by translation key. Used only where the subsystem does
         * something the pilot would otherwise have to infer from his ship not behaving — an arrival that
         * had to be finished the hard way is the case that exists today. The default says nothing (the
         * pure state-machine tests have no players).
         */
        default void messageCrew(List<UUID> crew, String translationKey) {
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
        int arrivalAttempts;        // retries of a stalled arrival crossing / pose settle (async VS assembly)
        BlockPos pasteAnchor;       // the arrival paste landed here; set once, so a retried settle never re-crosses
        final List<UUID> crew = new ArrayList<>(); // aboard crew captured at depart (option A) - gate + reseat
        NBTTagCompound snapshot;    // packed ship (StorageChunk NBT), re-cut from hyperspace at save points
        boolean restored;           // recreated from a persisted TransitRecord: no live hyperspace ship / lane
        boolean lastResortReported; // the "not even the snapshot landed" line is said once, not per retry

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

    /**
     * A ship that has physically arrived (crossed + settled in the ledger) and whose crew reseat is still
     * being retried. Kept OUT of {@link #transits} on purpose: the transit's durable lifecycle ends at
     * physical arrival, so a save in the (few-tick) reseat window exports nothing for it - it cannot be
     * re-pasted as a duplicate on restart. The reseat itself is best-effort and not persisted.
     */
    private static final class PendingReseat {
        final String shipId;
        final int targetSlotDim;
        final BlockPos anchor;
        int attempts;

        PendingReseat(String shipId, int targetSlotDim, BlockPos anchor) {
            this.shipId = shipId;
            this.targetSlotDim = targetSlotDim;
            this.anchor = anchor;
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
    /** Arrived ships whose crew reseat is still retrying (best-effort, not persisted). See {@link PendingReseat}. */
    private final List<PendingReseat> reseating = new ArrayList<>();

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
        // Capture the seated crew BEFORE the depart crossing cuts the seat blocks (a post-cut capture finds
        // nothing). captureCrew stashes the full crew inside the crosser (keyed by shipId) for the reseat at
        // arrival and returns the aboard player UUIDs for the offline-progress gate + the transit record.
        List<UUID> crew = crosser.captureCrew(originSlotDim, originAnchor, shipId);
        // Floor snapshot: capture the source ship BEFORE the depart crossing cuts it, so a save fired in the
        // window before the hyperspace ship assembles (snapshotParked still empty) never persists a
        // snapshot-less record - which on restart would strand + silently delete the ship. Later saves refresh
        // it from hyperspace via snapshotParked.
        NBTTagCompound initialSnapshot = crosser.snapshotSource(originSlotDim, originAnchor);
        BlockPos hyperAnchor = crosser.departToHyperspace(originSlotDim, originAnchor, tile);
        if (hyperAnchor == null) {
            tiles.free(tile);
            // The depart cut never happened (the ship stays in the origin cell), but captureCrew already
            // dismounted the crew - re-seat them onto the still-present origin ship so an aborted jump does
            // not silently eject the pilot.
            crosser.reseatCrew(originSlotDim, originAnchor, shipId);
            // Say what DISCRIMINATES. One generic line for every null return is how a departure that
            // never found its origin world got read as a crossing that failed, and the wrong subsystem
            // was blamed for it. The origin slot and whether that dimension resolves at all separate
            // "the ship was not where we looked" from "the cut itself failed"; the origin cell tells
            // you which of the two ids is the wrong one.
            LOGGER.warn("[SPACE] transit depart crossing failed for ship {} - jump aborted"
                            + " (origin cell {}, originSlotDim {}, that world resolved: {},"
                            + " originAnchor {}, crew captured {})",
                    shipId, origin == null ? "null" : origin.cellKey(), originSlotDim,
                    net.minecraftforge.common.DimensionManager.getWorld(originSlotDim) != null,
                    originAnchor, crew.size());
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
        t.crew.addAll(crew); // the offline-progress gate + the persisted transit record read these UUIDs
        transits.put(shipId, t);
        ledgerBeginTransit(shipId, target);
        return true;
    }

    /**
     * Advance the whole subsystem one server tick: every in-flight transit, then the best-effort crew
     * reseat of any ship that has already physically arrived.
     */
    public void tick() {
        tickTransits();
        tickReseating();
    }

    /**
     * Advance every in-flight ship one tick. A ship still en route stays parked (its coordinate steps
     * logically). A ship that reaches its target performs the arrival crossing: materialize the target
     * cell (refcount handoff, half 2), cross + unpark into it, and free the hyperspace lane.
     */
    private void tickTransits() {
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
            // The paste happens EXACTLY ONCE: once it lands, only the pose settle is retried.
            if (t.pasteAnchor == null) {
                t.pasteAnchor = t.restored
                        ? crosser.completeRestored(t.snapshot, t.targetSlotDim)
                        : crosser.arriveFromHyperspace(t.tile, t.hyperAnchor, t.targetSlotDim);
            }
            // Realize the target COORDINATE as a world pose and move the pasted ship onto it. Skipping
            // this leaves the ship in the paste lane's block band, and the flight computer's first
            // self-report then inverts the pose mapping against a block-band position - settling the
            // ship's address in a neighbouring cell, where the destination's bodies are not.
            BlockPos arrivedAt = null;
            if (t.pasteAnchor != null) {
                double[] pose = CellWorldMapper.poseWorldOf(t.target);
                arrivedAt = crosser.settleArrivedPose(
                        t.targetSlotDim, t.pasteAnchor, pose[0], pose[1], pose[2]);
            }
            if (arrivedAt != null) {
                freeLane(t);
                it.remove(); // done: the ship now occupies the target cell (its refcount stays held)
                // Record the arrival in the durable ledger (no longer amnesiac) and mark the arrived cell
                // diverged so an eviction FLUSHES it rather than discarding the ship (closes ledger #79).
                ledgerSettle(entry.getKey(), t.target);
                space.markDirty(t.target);
                // Hand any aboard crew to the best-effort reseat retry. The transit is already settled and
                // removed, so a save in the reseat window exports nothing for it - the crew reseat can lag a
                // few ticks (async re-assembly) without ever risking a duplicate ship on restart.
                if (!t.crew.isEmpty()) {
                    reseating.add(new PendingReseat(entry.getKey(), t.targetSlotDim, arrivedAt));
                }
            } else if (++t.arrivalAttempts >= MAX_ARRIVAL_ATTEMPTS) {
                // ── THIS BLOCK MUST NEVER RUN. ──────────────────────────────────────────────────────
                // An arrival is a block paste into a cell; it has no right to fail, and every branch
                // below is a recovery from something that should have been impossible. Each one is
                // logged at ERROR and told to the crew for that reason: reaching here is a DEFECT
                // REPORT, not a mode of operation. If you find yourself tuning the budget above to make
                // a symptom go away, the bug is upstream of this block - the arrival is waiting on
                // something it does not need.
                if (t.pasteAnchor == null) {
                    // Last resort: finish through the SNAPSHOT the transit already carries. That path
                    // asks VS nothing - it writes blocks and finds its anchor among the blocks it just
                    // wrote - so unlike the live crossing it cannot stall. The snapshot is always
                    // present (the depart-time floor cut, and a restored transit without one is refused
                    // at import), so this is a recovery with no precondition left to fail.
                    t.pasteAnchor = crosser.completeRestored(t.snapshot, t.targetSlotDim);
                }
                if (t.pasteAnchor == null) {
                    // Not even the snapshot landed. The ONE thing that must not happen now is losing the
                    // ship: keep the transit, keep the lane, keep the ledger saying IN_TRANSIT. The
                    // record therefore keeps being persisted, and a restart resumes the jump through the
                    // same snapshot path - which is the restart behaviour the persistence design already
                    // specifies. Retry from a fresh budget; say so once, not once per tick.
                    t.arrivalAttempts = 0;
                    if (!t.lastResortReported) {
                        t.lastResortReported = true;
                        LOGGER.error("[SPACE] transit arrival for ship {} could not be completed even from "
                                + "its snapshot (target cell {}, slot {}). The ship is NOT lost: it stays "
                                + "in transit and the jump resumes on restart. This state should be "
                                + "unreachable - treat it as a bug report.",
                                entry.getKey(), t.target.cellKey(), t.targetSlotDim);
                        crosser.messageCrew(t.crew, "msg.shiptransit.arrivalstalled");
                    }
                    continue; // stays in the map: the ledger and the transit map must never disagree
                }
                // Landed, one way or the other. A live hyperspace hull may still be sitting in the lane
                // (the cut that would have removed it is exactly what failed), so the lane is RETIRED
                // rather than freed - a freed one is handed to the next departure, which would then be
                // pasted into an abandoned ship.
                tiles.retire(t.tile);
                it.remove();
                ledgerSettle(entry.getKey(), t.target);
                space.markDirty(t.target);
                if (!t.crew.isEmpty()) {
                    reseating.add(new PendingReseat(entry.getKey(), t.targetSlotDim, t.pasteAnchor));
                }
                LOGGER.error("[SPACE] transit arrival for ship {} did not complete normally after {} ticks "
                        + "and was finished the hard way - the ship is in cell {} (slot {}) at its paste "
                        + "site, NOT on its intended pose, so its address reads the paste band. This state "
                        + "should be unreachable - treat it as a bug report.",
                        entry.getKey(), MAX_ARRIVAL_ATTEMPTS, t.target.cellKey(), t.targetSlotDim);
                crosser.messageCrew(t.crew, "msg.shiptransit.arrivalrecovered");
            }
            // else: retry the arrival (paste once, then the pose settle) next tick - the target stays
            // materialized and the lane stays held.
        }
    }

    /** Whether {@code shipId} is currently in transit. */
    public boolean isInTransit(String shipId) {
        return transits.containsKey(shipId);
    }

    /**
     * The dimension a crew member of {@code shipId} belongs in while his ship is mid-jump, or
     * {@code -1} if there is nowhere to put him. Used by the login restore when a player returns
     * while his ship is still in flight.
     *
     * <p>A LIVE transit's ship is parked in the shared hyperspace world, so its crew belongs there.
     * A RESTORED transit is a different animal: it survived a restart that wiped hyperspace, so it
     * carries only a block snapshot and no physical ship exists anywhere until it arrives. There is
     * therefore no world that contains the ship, and the honest answer is "nowhere" — the caller
     * falls back to an ordinary spawn rather than dropping the player into empty hyperspace beside a
     * ship that is not there.</p>
     */
    public int crewDimensionOf(String shipId) {
        Transit t = transits.get(shipId);
        if (t == null) {
            return -1;
        }
        if (t.restored) {
            LOGGER.warn("[SPACE] crew of {} returned while its jump is mid-flight from a "
                    + "restart - no physical ship exists until arrival; placing the player at spawn", shipId);
            return -1;
        }
        return HyperspaceWorld.dimId();
    }

    /**
     * Where {@code shipId} is physically parked in the shared hyperspace world, or {@code null} when
     * it is not in flight or has no physical ship there (a restored transit carries only a snapshot).
     * Lets a crew member who returns mid-jump be placed at his ship rather than at the world origin.
     */
    public BlockPos hyperspaceAnchorOf(String shipId) {
        Transit t = transits.get(shipId);
        return t == null || t.restored ? null : t.hyperAnchor;
    }

    /** Number of ships currently in transit (hyperspace lanes in use). */
    public int inTransitCount() {
        return transits.size();
    }

    /** Number of arrived ships whose crew reseat is still retrying (0 once every jump's crew is re-seated). */
    public int reseatingCount() {
        return reseating.size();
    }

    /**
     * Retry the crew reseat of every arrived ship. Each entry drops out when {@code reseatCrew} reports the
     * crew re-seated (or nothing to seat), or after {@link #MAX_ARRIVAL_ATTEMPTS} retries of a re-assembly
     * whose seat tiles never came up (the ship is already settled - the crew is simply not re-seated).
     */
    private void tickReseating() {
        if (reseating.isEmpty()) {
            return;
        }
        Iterator<PendingReseat> it = reseating.iterator();
        while (it.hasNext()) {
            PendingReseat r = it.next();
            if (crosser.reseatCrew(r.targetSlotDim, r.anchor, r.shipId)
                    || ++r.attempts >= MAX_ARRIVAL_ATTEMPTS) {
                it.remove();
            }
        }
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

    private void ledgerSettle(String shipId, GalacticCoord coord) {
        if (ledger == null) {
            return;
        }
        UUID id = toUuid(shipId);
        if (id != null) {
            ledger.settle(id, coord);
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
