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
     * How often a parked ship's durable block snapshot is re-cut from hyperspace, in server ticks
     * (tunable). It bounds how much of an in-flight ship's own change a crash can roll back, so it is
     * kept at the same order as the world autosave it used to ride on; the clean-stop case is exact
     * regardless, because the server-stopping hook takes a final cut.
     */
    private static final int SNAPSHOT_REFRESH_TICKS = 600;

    /**
     * The world-operation seam: perform the two per-ship crossings and park/unpark the ship. Kept out of
     * the pure state machine so it can be faked in tests. The production implementation drives
     * {@code VSIntegration.crossShip} + {@code parkShipAt}/{@code unparkShipAt}.
     */
    public interface Crosser {
        /**
         * Depart: cross the ship anchored at {@code srcAnchor} in the origin cell's slot world
         * {@code srcSlotDim} into the shared hyperspace world at {@code tile}, and PARK it (physics off).
         * Returns the ship's new anchor in hyperspace together with the identity it is now registered
         * under there, or {@code null} if the crossing failed.
         *
         * <p>A crossing KEEPS the ship's identity, so the uuid this returns is the same one the ship
         * had in its origin cell and the same one it will have at the far end. That is what lets one
         * jump carry ONE identity instead of re-finding the ship by position at each leg.</p>
         */
        ShipCrossingService.Crossed departToHyperspace(int srcSlotDim, BlockPos srcAnchor,
                                                       HyperspaceTiles.Tile tile);

        /**
         * Arrive: cross the parked ship at {@code hyperAnchor} (lane {@code tile}) into the target cell's
         * slot world {@code targetSlotDim}. Returns the ship's PASTE anchor in the target cell plus the
         * identity it carries, or {@code null} if the crossing failed. The ship stays parked in the
         * paste lane until {@link #settleArrivedPose} moves it onto the coordinate it was aimed at.
         */
        ShipCrossingService.Crossed arriveFromHyperspace(String shipId, HyperspaceTiles.Tile tile,
                                                        BlockPos hyperAnchor,
                                                         int targetSlotDim);

        /**
         * Settle an arrived ship (live or restored) onto the world pose realizing its TARGET coordinate:
         * rigid-teleport it there carrying its riders, then unpark it (physics on). Returns the ship's
         * anchor at that final pose, or {@code null} while the asynchronous re-assembly is not queryable
         * yet - the caller retries next tick and never re-crosses. This is the arrival's half of the
         * paste-then-settle shape the entry on-ramp and the descent already use; without it a ship
         * arrives in the destination's BLOCK band while every reader of its address works in the POSE
         * band, so the settled coordinate lands in a neighbouring cell.
         *
         * <p>{@code vsShipUuid} names WHICH ship to settle. A destination that holds a second craft
         * near the arrival point is the ordinary case, not an exotic one, and a settle that picks the
         * nearest ship instead moves a stranger and then looks for its own crew's seats aboard that
         * stranger. {@code null} falls back to the position lookup.</p>
         *
         * <p>The default returns {@code pasteAnchor} unchanged - the pure state-machine tests have no
         * world to realize a pose in.</p>
         */
        default BlockPos settleArrivedPose(int targetSlotDim, BlockPos pasteAnchor, UUID vsShipUuid,
                                           double px, double py, double pz) {
            return pasteAnchor;
        }

        /**
         * Re-cut the parked ship in hyperspace (lane {@code tile}, anchor {@code hyperAnchor}) as a
         * {@code StorageChunk} NBT snapshot, non-destructively, so an in-flight jump survives a restart
         * (the hyperspace world is ephemeral - wiped on restart). Returns {@code null} if VS is absent or
         * the ship is gone. Called from the server tick on a cadence, never from a save handler; the
         * default no-ops for the pure state-machine tests.
         */
        default NBTTagCompound snapshotParked(HyperspaceTiles.Tile tile, BlockPos hyperAnchor) {
            return null;
        }

        /**
         * Snapshot the SOURCE ship (still in its origin cell, BEFORE {@link #departToHyperspace} cuts it) as
         * a {@code StorageChunk} NBT - the depart-time FLOOR snapshot. Without it, a jump saved in the window
         * before its hyperspace ship has assembled (when {@link #snapshotParked} is still empty) would persist
         * a snapshot-less record and, on restart, strand + silently DELETE the ship. The periodic re-cut
         * refreshes it via {@link #snapshotParked}. Returns {@code null} if VS is absent (the pure
         * state-machine tests).
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
         *
         * <p>This is the ONE arrival that cannot keep the ship's identity: it pastes stored blocks,
         * with no live ship anywhere to take an identity from, so the returned uuid is a fresh one and
         * the transit adopts it. Everything downstream keys on what this returns rather than on what
         * the jump departed with.</p>
         */
        default ShipCrossingService.Crossed completeRestored(NBTTagCompound snapshot, int targetSlotDim) {
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
         *
         * <p>{@code vsShipUuid} names the arrived ship, so the seat scan searches ITS shipyard rather
         * than the nearest one's; {@code null} falls back to the position lookup.</p>
         */
        default boolean reseatCrew(int targetSlotDim, BlockPos arrivalAnchor, String shipId,
                                   UUID vsShipUuid) {
            return true;
        }

        /**
         * Seat the crew captured for {@code shipId} onto the ship parked at {@code anchor} in
         * {@code parkedDim}, WITHOUT releasing the capture. Same retry contract as
         * {@link #reseatCrew}: {@code true} once everyone is aboard or there is nobody to move,
         * {@code false} to try again next tick.
         *
         * <p>The capture is deliberately kept: this seats the crew for the LEG, and the same records
         * seat them again at the far end. Consuming it here would leave the arrival with nobody to
         * re-seat and no way to find them, because the far-side ship is a fresh re-assembly whose
         * seat positions only the capture's link offsets can re-identify.</p>
         *
         * <p>{@code vsShipUuid} names the parked ship, for the same reason the arrival re-seat takes
         * one: the hyperspace world holds every ship in flight, so "the ship at this anchor" is a
         * question with neighbours. {@code null} falls back to the position lookup.</p>
         *
         * <p>The default returns {@code true} (nothing to seat).</p>
         */
        default boolean boardCrew(int parkedDim, BlockPos anchor, String shipId, UUID vsShipUuid) {
            return true;
        }

        /**
         * The dimension in-flight ships are parked in, or {@link Integer#MIN_VALUE} where there is no
         * such world (the pure state-machine tests, and any build without the ship integration). A
         * caller that gets {@code MIN_VALUE} must not try to put anything there.
         */
        /**
         * Whether a ship is actually registered at {@code hyperAnchor} in the parked world — the
         * evidence a restored transit needs before it treats itself as still having a physical ship.
         *
         * <p>Hyperspace outlives the server now, so a jump interrupted by a restart usually comes
         * back with its hull still standing in its lane. "Usually" is not "always": the folder can be
         * deleted, an older save may predate durability, and a crash can leave a lane empty. The
         * record is what says a ship BELONGS there; this says whether one IS there, and a transit
         * that gets {@code false} falls back to rebuilding from its block snapshot, which is what
         * that snapshot has always been for.</p>
         *
         * <p>Defaults to {@code false}: a pure state-machine test has no world, and answering "yes"
         * would make every restored transit wait for a ship nothing can produce.</p>
         */
        /**
         * Whether the crossing stowed anything for {@code shipId} that still has to be put back — the
         * bodies aboard that are not crew. The state machine cannot see them (they are the crossing's
         * business, not the flight's), and it must not queue a placement leg for a jump that has
         * nothing to place: measured 2026-08-08, queuing one unconditionally reddened a NEIGHBOURING
         * subsystem's e2e on a matched pair, and a leg with no work is exactly the leg to not create.
         */
        default boolean hasStowedBodies(String shipId) {
            return false;
        }

        default boolean parkedShipPresent(BlockPos hyperAnchor) {
            return false;
        }

        /**
         * The hyperspace lanes that currently hold a registered ship, searching lane indices below
         * {@code laneSearchLimit}. The raw material of the boot-time reconciliation: everything in
         * here that no restored transit claims is a ship nothing is flying.
         */
        default List<Integer> parkedShipLanes(int laneSearchLimit) {
            return Collections.emptyList();
        }

        /**
         * Dispose of the ship parked in lane {@code laneIndex} — a hull no record claims. Returns
         * whether anything was disposed of.
         */
        default boolean disposeParkedLane(int laneIndex) {
            return false;
        }

        default int parkedDim() {
            return Integer.MIN_VALUE;
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

    /**
     * Where inside its target cell an arrival actually puts the ship.
     *
     * <p>Production stands the ship off the cell's descend-target bodies, because a jump aimed at a
     * planet is aimed at that planet's ADDRESS, which is its cell centre &mdash; arriving exactly
     * there puts the ship at distance zero from the body, well inside the descent trigger, so the
     * pilot's first control input drops him onto the surface he had just flown to orbit. The default
     * is the identity: arrive exactly on the coordinate the jump was aimed at.</p>
     *
     * <p>The tick is part of the seam even though the first implementation ignores it: a flight can
     * be paused indefinitely by the offline-progress gate, so the stamped {@code arrivalTick} is not
     * the tick the ship actually settles, and a placement computed against anything that moves must
     * be told which moment it is answering for.</p>
     */
    @FunctionalInterface
    public interface ArrivalPlacement {
        GalacticCoord arrivalCoordFor(String shipId, GalacticCoord target, long worldTick);
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
        /**
         * The ship's PHYSICS identity - one value for the whole jump, because a crossing keeps it:
         * the ship registered under this uuid in the origin cell is registered under it in hyperspace
         * and again in the target cell. Set at depart, re-read from each crossing (a crossing that had
         * to refuse the identity says so by returning a different one), and replaced outright by a
         * RESTORED arrival, which pastes stored blocks and can keep nothing.
         *
         * <p>In memory only, and that is the right lifetime: it is exactly the lifetime of
         * {@link #pasteAnchor}, which a restart also re-derives rather than reloads. A restored transit
         * completes through the snapshot path, which mints its own identity.</p>
         */
        UUID vsShipUuid;
        GalacticCoord arrivalCoord; // where in the target cell the ship is actually put down; resolved ONCE
                                    // (a re-rolled ring would hand each settle retry a different point)
        final List<UUID> crew = new ArrayList<>(); // aboard crew captured at depart (option A) - gate + reseat
        NBTTagCompound snapshot;    // packed ship (StorageChunk NBT), re-cut from hyperspace on a cadence
        boolean restored;           // recreated from a persisted TransitRecord: no live hyperspace ship / lane
        boolean lastResortReported; // the "not even the snapshot landed" line is said once, not per retry
        boolean snapshotFailureReported; // likewise for a re-cut that keeps failing

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
        /**
         * {@code true} for the DEPARTURE-side seating onto the parked hull, which keeps the capture
         * for the far end; {@code false} for the arrival, which is the end of the line and releases
         * it. One retry loop serves both because the reason for retrying is the same on both sides:
         * the blocks are placed synchronously but the ship is re-assembled a tick or more later, and
         * a seat tile cannot be found before that.
         */
        final boolean boarding;
        /** The ship's physics identity, so the seat scan searches ITS shipyard and not a neighbour's. */
        final UUID vsShipUuid;
        int attempts;

        PendingReseat(String shipId, int targetSlotDim, BlockPos anchor, boolean boarding,
                      UUID vsShipUuid) {
            this.shipId = shipId;
            this.targetSlotDim = targetSlotDim;
            this.anchor = anchor;
            this.boarding = boarding;
            this.vsShipUuid = vsShipUuid;
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
    /** Arrival placement policy; {@code null} = arrive exactly on the aimed coordinate. */
    private ArrivalPlacement arrivalPlacement;
    /**
     * How a cell name resolves to a position at a tick. A jump is priced across two cells whose
     * frames both move, so the departure distance is only meaningful read through both of them at a
     * stated tick — and it is that live geometry the flight's cost and duration follow.
     * {@code null} = the static reading, which is what the pure state-machine tests want and what a
     * subsystem with no registry has.
     */
    private CellFrames frames;
    private final Map<String, Transit> transits = new LinkedHashMap<>();
    /** Arrived ships whose crew reseat is still retrying (best-effort, not persisted). See {@link PendingReseat}. */
    private final List<PendingReseat> reseating = new ArrayList<>();
    /** Ticks since the last snapshot re-cut pass; counts only while something is in flight. */
    private int snapshotTicks;

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
        ShipCrossingService.Crossed departed = crosser.departToHyperspace(originSlotDim, originAnchor, tile);
        BlockPos hyperAnchor = departed == null ? null : departed.anchor;
        if (hyperAnchor == null) {
            tiles.free(tile);
            // The depart cut never happened (the ship stays in the origin cell), but captureCrew already
            // dismounted the crew - re-seat them onto the still-present origin ship so an aborted jump does
            // not silently eject the pilot. No identity to name it by: the ship never crossed, so the only
            // handle on it is the anchor it is still sitting at.
            crosser.reseatCrew(originSlotDim, originAnchor, shipId, null);
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
        // The flight is priced ONCE, here, through both cells' frames as they stand at departure.
        // A jump is a commitment: the pilot saw a forecast at the console and the drive spent its
        // burst against it, so re-pricing mid-flight because the destination kept orbiting would
        // charge him for a decision he could not have made differently.
        double distance = (frames == null ? CellFrames.STATIC : frames)
                .distanceBetween(origin, target, now);
        long distanceBlocks = (long) Math.ceil(distance);
        // The ETA goes through the same law the console's forecast quotes, so the flight the pilot
        // was shown is the flight he gets.
        long arrivalTick = now + zmaster587.advancedRocketry.hyperdrive.JumpSpeed
                .transitTicks(distance, speed);
        Transit t = new Transit(origin, target, tile, hyperAnchor, speed, arrivalTick, now,
                new ShipTransit(origin, target, distanceBlocks));
        t.snapshot = initialSnapshot;
        t.vsShipUuid = departed.vsShipUuid; // one identity for the whole jump - the crossing kept it
        t.crew.addAll(crew); // the offline-progress gate + the persisted transit record read these UUIDs
        transits.put(shipId, t);
        ledgerBeginTransit(shipId, target);
        // The crew flies WITH its ship. Capturing them unseated them and the departure cut took the
        // world they were standing in out from under them, so leaving it there would strand every
        // passenger in the cell the ship just left for the whole flight. Seat them on the parked hull
        // instead - the flight is a place, not an interval.
        //
        // It cannot happen on this tick: the blocks are pasted synchronously but the ship is
        // re-assembled later, so no seat tile resolves yet. Hand it to the same retry loop the arrival
        // uses. Ordering is safe in a way the arrival's is not - the parked ship never moves again
        // before the arrival crossing cuts it - so there is no seat-then-move window here.
        int parkedDim = crosser.parkedDim();
        // Queued when there is something to put back — the crew, or the bodies the crossing stowed
        // off the deck. The leg is "put back what was aboard" and the crew is only its best-known
        // member; but a leg for a jump carrying NOTHING is work that does not exist, and creating one
        // is not free (it reddened a neighbouring subsystem's e2e on a matched pair).
        if ((!crew.isEmpty() || crosser.hasStowedBodies(shipId)) && parkedDim != Integer.MIN_VALUE) {
            reseating.add(new PendingReseat(shipId, parkedDim, hyperAnchor, true, t.vsShipUuid));
        }
        // The crew is told it has departed, on the same channel and in the same voice as everything
        // else this subsystem says. Said here rather than at the key press: the press only starts a
        // spool, and a jump that is refused above this line must not have announced itself first.
        if (!crew.isEmpty()) {
            crosser.messageCrew(crew, "msg.shiptransit.departed");
        }
        return true;
    }

    /**
     * Advance the whole subsystem one server tick: every in-flight transit, then the best-effort crew
     * reseat of any ship that has already physically arrived, then — periodically — the re-cut of every
     * parked ship's durable block snapshot.
     */
    public void tick() {
        tickTransits();
        tickReseating();
        // Nothing to re-cut with no ship in flight, and the counter must not run while it is idle or the
        // first jump of a long session would be cut the instant it departs and then not again for a full
        // period. Arrivals are advanced first, so a transit that finished this tick is already gone.
        if (!transits.isEmpty() && ++snapshotTicks >= SNAPSHOT_REFRESH_TICKS) {
            snapshotTicks = 0;
            refreshSnapshots();
        }
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
                ShipCrossingService.Crossed arrived = t.restored
                        ? crosser.completeRestored(t.snapshot, t.targetSlotDim)
                        : crosser.arriveFromHyperspace(entry.getKey(), t.tile, t.hyperAnchor,
                                t.targetSlotDim);
                if (arrived != null) {
                    t.pasteAnchor = arrived.anchor;
                    // The arrival's identity WINS over the one the jump departed with. Normally they are
                    // the same value - a crossing keeps the identity - and the two differ in exactly two
                    // cases, both of which make the new one the true one: a restored arrival, which pastes
                    // stored blocks and can keep nothing, and a destination where something live already
                    // held the identity, which the crossing refuses rather than collide with. Keeping the
                    // departure's value in either case would name a ship that is not the one that landed.
                    if (arrived.vsShipUuid != null && !arrived.vsShipUuid.equals(t.vsShipUuid)) {
                        LOGGER.info("[SPACE] transit arrival for ship {} came out under identity {} "
                                        + "(it departed as {}) - the settle and re-seat follow the new one",
                                entry.getKey(), arrived.vsShipUuid, t.vsShipUuid);
                        t.vsShipUuid = arrived.vsShipUuid;
                    }
                }
            }
            // Realize the target COORDINATE as a world pose and move the pasted ship onto it. Skipping
            // this leaves the ship in the paste lane's block band, and the flight computer's first
            // self-report then inverts the pose mapping against a block-band position - settling the
            // ship's address in a neighbouring cell, where the destination's bodies are not.
            // Where in the target cell the ship is actually put down. Resolved ONCE and kept: the
            // settle below is retried for up to MAX_ARRIVAL_ATTEMPTS ticks, and a placement re-rolled
            // per retry would hand each attempt a different point to aim the same ship at.
            if (t.arrivalCoord == null) {
                t.arrivalCoord = arrivalPlacement == null ? t.target
                        : arrivalPlacement.arrivalCoordFor(entry.getKey(), t.target, now);
                if (t.arrivalCoord == null) {
                    t.arrivalCoord = t.target; // a policy that cannot answer never loses the ship
                }
            }
            BlockPos arrivedAt = null;
            if (t.pasteAnchor != null) {
                double[] pose = CellWorldMapper.poseWorldOf(t.arrivalCoord);
                arrivedAt = crosser.settleArrivedPose(
                        t.targetSlotDim, t.pasteAnchor, t.vsShipUuid, pose[0], pose[1], pose[2]);
            }
            if (arrivedAt != null) {
                freeLane(t);
                it.remove(); // done: the ship now occupies the target cell (its refcount stays held)
                // Record the arrival in the durable ledger (no longer amnesiac) and mark the arrived cell
                // diverged so an eviction FLUSHES it rather than discarding the ship (closes ledger #79).
                // The ledger takes the PLACED coordinate, not the aimed one: the descent trigger reads
                // the ledger (not the pose), so a ring applied to the pose alone would leave the
                // trigger measuring from the cell centre and firing anyway.
                ledgerSettle(entry.getKey(), t.arrivalCoord);
                space.markDirty(t.target);
                // Hand whatever was aboard - crew and stowed bodies alike - to the best-effort
                // placement retry. The transit is already settled and removed, so a save in that
                // window exports nothing for it: the placement can lag a few ticks (async
                // re-assembly) without ever risking a duplicate ship on restart.
                if (!t.crew.isEmpty() || crosser.hasStowedBodies(entry.getKey())) {
                    reseating.add(new PendingReseat(entry.getKey(), t.targetSlotDim, arrivedAt, false,
                            t.vsShipUuid));
                }
                // Say so. A jump that ends in silence is indistinguishable from a jump that hung:
                // the crew has no control in flight and no number to watch, so arriving is the one
                // moment the flight has to announce itself. Said on the NORMAL path only - the two
                // recovery branches below have their own, louder message, and a crew that got both
                // would read the failure as routine. A crewless jump tells nobody, which is what an
                // empty crew list means.
                crosser.messageCrew(t.crew, "msg.shiptransit.arrived");
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
                    // at import), so this is a recovery with no precondition left to fail. It builds a
                    // ship out of stored blocks, so the identity it comes back with REPLACES the one the
                    // jump carried: the ship that existed under that identity is not the one landing here.
                    ShipCrossingService.Crossed rebuilt = crosser.completeRestored(t.snapshot, t.targetSlotDim);
                    if (rebuilt != null) {
                        t.pasteAnchor = rebuilt.anchor;
                        t.vsShipUuid = rebuilt.vsShipUuid;
                    }
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
                ledgerSettle(entry.getKey(), t.arrivalCoord); // same coordinate as the normal path
                space.markDirty(t.target);
                if (!t.crew.isEmpty() || crosser.hasStowedBodies(entry.getKey())) {
                    reseating.add(new PendingReseat(entry.getKey(), t.targetSlotDim, t.pasteAnchor,
                            false, t.vsShipUuid));
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

    /**
     * How many jumps in flight are carrying a real HULL parked in hyperspace, as opposed to a block
     * snapshot they will paste at the far end.
     *
     * <p>The difference is what a restart is survivable BY: a jump restored with its hull resumes as
     * the same ship, keeping whatever is standing on its deck, while one restored from a snapshot
     * rebuilds a copy at the destination and nothing that was aboard comes with it. Both count as "in
     * transit", so {@link #inTransitCount()} cannot tell them apart — and the fallback is exactly what
     * a jump silently degrades to when hyperspace does not come back.</p>
     */
    public int parkedTransitCount() {
        int parked = 0;
        for (Transit t : transits.values()) {
            if (t.tile != null) {
                parked++;
            }
        }
        return parked;
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
            boolean done = r.boarding
                    ? crosser.boardCrew(r.targetSlotDim, r.anchor, r.shipId, r.vsShipUuid)
                    : crosser.reseatCrew(r.targetSlotDim, r.anchor, r.shipId, r.vsShipUuid);
            if (done || ++r.attempts >= MAX_ARRIVAL_ATTEMPTS) {
                if (!done && r.boarding) {
                    // The crew is NOT lost - the capture is still held and the arrival will seat them
                    // at the far end - but they spend the flight where the departure left them instead
                    // of aboard, which is the whole point of the leg. Say so once, with the anchor,
                    // because the only other symptom is a pilot who quietly did not travel.
                    LOGGER.error("[SPACE] crew of ship {} could not be seated on its parked hull in dim "
                            + "{} at {} after {} ticks - they stay where the departure left them for the "
                            + "flight and are seated at arrival. Treat this as a bug report.",
                            r.shipId, r.targetSlotDim, r.anchor, MAX_ARRIVAL_ATTEMPTS);
                }
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

    /**
     * How far along its flight a ship is, as a COARSE phase rather than a number.
     *
     * <p>The crew is told departing / in flight / arriving and never a countdown: a phase needs no
     * tick-by-tick agreement between server and client, so it cannot show the pilot a number that
     * stutters, and a jump does not turn into a progress bar. Both boundaries are derived from the
     * ship's OWN speed rather than from a fraction of the trip, so a short hop and a long crossing
     * both get a recognisable departure and run-in instead of one being all "arriving".</p>
     *
     * <p>Both windows are tunable. Arriving is tested first: near the end of a hop short enough for
     * the two windows to overlap, the run-in is the half worth naming.</p>
     */
    public Phase phaseOf(String shipId) {
        Transit t = transits.get(shipId);
        if (t == null || t.integrator == null) {
            return Phase.NONE;
        }
        long speed = Math.max(1L, t.speed);
        if (t.integrator.remainingDistance() <= speed * ARRIVING_TICKS) {
            return Phase.ARRIVING;
        }
        if (t.integrator.travelledBlocks() < speed * DEPARTING_TICKS) {
            return Phase.DEPARTING;
        }
        return Phase.CRUISING;
    }

    /** The coarse phases of a flight, in the order a crew meets them. Ordinals cross the wire. */
    public enum Phase {
        NONE, DEPARTING, CRUISING, ARRIVING
    }

    /** How long a flight reads as "departing" / "arriving", in ticks of its own travel (tunable). */
    private static final long DEPARTING_TICKS = 60L;
    private static final long ARRIVING_TICKS = 100L;

    /** Install the offline-progress gate (config mode + online check). {@code null} restores always-advance. */
    public void setOfflineProgress(OfflineProgress policy) {
        this.offlineProgress = policy;
    }

    /**
     * Install the {@link ArrivalPlacement} policy. {@code null} restores "arrive exactly on the
     * coordinate the jump was aimed at" — which is also what every state-machine unit test wants,
     * and is why this is set after construction rather than taken as a constructor argument.
     */
    public void setArrivalPlacement(ArrivalPlacement placement) {
        this.arrivalPlacement = placement;
    }

    /**
     * Install the frame lookup used to price a departure. {@code null} restores the static reading —
     * the distance two cell names would be apart if nothing moved.
     */
    public void setFrames(CellFrames lookup) {
        this.frames = lookup;
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
     * Snapshot every in-flight transit as a durable {@link TransitRecord}, for the save point.
     *
     * <p><b>This does no world work, and that is the point.</b> It reads the block snapshot each transit
     * is already carrying — {@link #refreshSnapshots()} is what keeps that current — and builds records
     * out of numbers. A save handler runs inside the server's save pass, where a throw does not merely
     * fail: it aborts the pass for every remaining world. Re-cutting a live ship from a live physics
     * world is the least predictable call this subsystem makes, and it has no business being on that
     * path.</p>
     */
    public List<TransitRecord> exportTransits() {
        List<TransitRecord> out = new ArrayList<>();
        for (Map.Entry<String, Transit> e : transits.entrySet()) {
            Transit t = e.getValue();
            out.add(new TransitRecord(e.getKey(), t.integrator.origin(), t.target,
                    t.integrator.distanceBlocks(), t.integrator.travelledBlocks(), t.arrivalTick,
                    t.lastTicked, t.speed, t.crew, t.snapshot,
                    t.tile == null ? -1 : t.tile.index, t.hyperAnchor));
        }
        return out;
    }

    /**
     * Re-cut the block snapshot of every ship parked in hyperspace, so what a restart resumes is the ship
     * as it is NOW rather than as it left. Driven on a cadence from {@link #tick()} and once more when the
     * server is stopping; returns how many transits actually got a fresh cut, which is the only honest way
     * for a caller to know a re-cut happened at all (a snapshot is non-null from the depart-time floor
     * onwards, so its mere presence proves nothing).
     *
     * <p>Three transits are skipped, each for its own reason. A RESTORED one has no hyperspace ship to cut
     * — that world was wiped by the restart it survived. One that has already PASTED into its target and is
     * only retrying the pose settle has had its hyperspace hull cut away by the arrival crossing, and the
     * ship lookup underneath the cut is unbounded, so it would answer with a NEIGHBOURING lane's ship and
     * overwrite this transit's snapshot with the wrong hull. And a lane-less transit has no anchor to cut
     * at.</p>
     *
     * <p>A failed cut keeps the last good snapshot: the ship is mid-jump and the snapshot it already
     * carries is the only durable copy of it, so a hiccup must never be allowed to trade a stale ship for
     * no ship. Reported once per transit rather than once per attempt.</p>
     */
    public int refreshSnapshots() {
        int refreshed = 0;
        for (Map.Entry<String, Transit> e : transits.entrySet()) {
            Transit t = e.getValue();
            if (t.restored || t.tile == null || t.hyperAnchor == null || t.pasteAnchor != null) {
                continue;
            }
            try {
                NBTTagCompound fresh = crosser.snapshotParked(t.tile, t.hyperAnchor);
                if (fresh != null) {
                    t.snapshot = fresh;
                    t.snapshotFailureReported = false;
                    refreshed++;
                }
            } catch (Exception bad) {
                // PRECAUTIONARY, and worth saying so: no throw has ever been observed out of the
                // physics world's cut. If one ever is, that mod is compiled from this repository - the
                // honest fix is there, not a wider net here. Errors are not caught: they are not a
                // hiccup a jump can carry on past.
                if (!t.snapshotFailureReported) {
                    t.snapshotFailureReported = true;
                    LOGGER.error("[SPACE] could not re-cut the parked ship {} in hyperspace; the jump keeps "
                            + "the snapshot it already carries, so a restart would resume it as it was at "
                            + "the last successful cut", e.getKey(), bad);
                }
            }
        }
        return refreshed;
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
        // Reclaim the LANE first, whatever else is true of this record: the index is taken by a hull
        // that is standing there right now, and an allocator that does not know would hand it to the
        // next departure and paste a second ship into the first one. Reclaiming a lane whose ship
        // turns out to be gone costs one index out of a supply the tiles class calls unbounded.
        HyperspaceTiles.Tile tile = null;
        if (record.laneIndex >= 0) {
            tiles.reserve(record.laneIndex);
            tile = HyperspaceTiles.tile(record.laneIndex);
        }
        // Is the ship still there? Hyperspace persists, so the ordinary answer is yes and the jump
        // resumes as the ship it has always been — same lane, same hull, same crew placement. The
        // snapshot stays the fallback for the record whose lane came back empty.
        boolean parked = record.hyperAnchor != null && crosser.parkedShipPresent(record.hyperAnchor);
        // Neither a hull nor a snapshot is nothing to restore. The check is here, AFTER the lane has
        // been reclaimed and the ship looked for, because a parked ship makes a snapshot-less record
        // perfectly restorable — and it used to be rejected out of hand, back when the only way to
        // rebuild a jump was to paste its blocks.
        if (!parked && record.snapshot == null) {
            LOGGER.error("[SPACE] persisted transit for ship {} has neither a ship parked in hyperspace "
                    + "nor a block snapshot - there is nothing to restore; dropping the record",
                    record.shipId);
            return;
        }
        Transit t = new Transit(record.origin, record.target, parked ? tile : null,
                parked ? record.hyperAnchor : null, record.speed,
                record.arrivalTick, record.lastTicked,
                new ShipTransit(record.origin, record.target, record.distanceBlocks,
                        record.travelledBlocks));
        t.restored = !parked;
        t.snapshot = record.snapshot;
        if (record.crew != null) {
            t.crew.addAll(record.crew);
        }
        transits.put(record.shipId, t);
        ledgerBeginTransit(record.shipId, record.target);
    }

    /**
     * Match every ship found in hyperspace against the transits that claim one, and dispose of the
     * rest. Returns how many were disposed of.
     *
     * <p>The obligation a durable hyperspace creates. While the world was wiped on every boot the
     * question could not arise; now a hull can outlive the record that put it there — a save copied
     * without its data file, a crash between the block write and the record write, a jump whose
     * record was dropped as corrupt. Such a hull is a ship nobody is flying, parked forever in a
     * world that is force-kept-loaded, and it holds a lane that would otherwise be reused.</p>
     *
     * <p>Run ONCE at boot, after every record has been imported — before that the claimed set is
     * incomplete and a perfectly good ship would look unclaimed. Matching is by LANE, not by
     * identity: a lane is the address a record stores and a ship parks at, and reading a hull's own
     * durable id would mean force-loading each shipyard to find its flight computer.</p>
     *
     * <p>"Disposed of" is deregistration plus retirement of the lane, not a block wipe: a hull the
     * physics mod no longer knows about is inert, and its blocks sit in a far subspace shipyard that
     * nothing loads. Retiring the lane is what stops a later departure being pasted into it.</p>
     */
    public int reconcileParkedShips() {
        java.util.Set<Integer> claimed = new java.util.HashSet<>();
        for (Transit t : transits.values()) {
            if (t.tile != null) {
                claimed.add(t.tile.index);
            }
        }
        int disposed = 0;
        for (Integer lane : crosser.parkedShipLanes(tiles.allocatedLaneCount() + 1)) {
            if (lane == null || claimed.contains(lane)) {
                continue;
            }
            if (crosser.disposeParkedLane(lane)) {
                tiles.reserve(lane); // never hand this lane out: its blocks are still lying in it
                disposed++;
                LOGGER.warn("[SPACE] hyperspace lane {} held a ship no transit record claims - disposed "
                        + "of it. A hull nobody is flying would otherwise sit in a permanently loaded "
                        + "world for the life of the save.", lane);
            }
        }
        return disposed;
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
