package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Production {@link ShipTransitManager.Crosser}: carries the transit state machine's depart/arrive
 * decisions out against live worlds, using the proven per-ship crossing ({@link VSIntegration#crossShip})
 * plus {@link VSIntegration#parkShipAt}/{@link VSIntegration#unparkShipAt}. Both crossings paste into a
 * clear void column so the flood-fill re-assembly grabs only the ship. A safe no-op
 * (returns {@code null} - the transit aborts cleanly) when VS is absent or a world is missing.
 */
public final class VSShipCrosser implements ShipTransitManager.Crosser {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("advancedrocketry/space");

    /** Clear-sky Y the target-cell arrival pastes at (cells are void; a high column avoids any floor). */
    private static final int ARRIVAL_Y = 200;
    /** Per-lane X offset for arrivals, so ships arriving into one cell from different lanes never overlap. */
    private static final int ARRIVAL_LANE_STRIDE = 64;

    /** The shared crossing primitives (readiness-gated pose teleport, rider carry, unpark) the entry
     *  on-ramp and the descent already settle through. Stateless. */
    private final VSShipCrossingOps ops = new VSShipCrossingOps();

    /** Monotonic per-boot counter for RESTORED arrivals (imported only at server start), spreading them
     *  across the NEGATIVE-X paste band so they never overlap each other or a live arrival. */
    private int restoredLane;

    /** The full crew captured at depart, keyed by ship id, held until the arrival reseat succeeds. In-memory
     *  only (the transit record persists the crew UUIDs, not the AFC-link offsets a reseat needs), so a
     *  restored transit's stash is empty after a restart - its reseat is a no-op, deferred to login-restore. */
    private final Map<String, List<CrewTransfer.Crew>> crewStash = new HashMap<>();

    /**
     * Target slot dim &rarr; the arrival-guard cause last reported for it. An arrival is retried every
     * tick, so an un-deduplicated line would be 200 copies of itself; keeping the last cause still
     * reports a SECOND, different cause for the same slot, which is the case where repetition carries
     * information. Cleared when that slot's arrival gets past the guard.
     */
    private final Map<Integer, String> arrivalGuardWarned = new HashMap<>();

    /** Report an arrival that stopped at its own guard - once per target slot, per distinct cause. */
    private void warnArrivalGuardOnce(int targetSlotDim, String cause) {
        if (cause.equals(arrivalGuardWarned.put(targetSlotDim, cause))) {
            return;
        }
        LOGGER.warn("[SPACE] arrival into slot dim {} stopped BEFORE the crossing was attempted: {}. "
                        + "Nothing has moved; the arrival retries next tick. If the transit later gives "
                        + "up on this ship, this is the cause of it.",
                targetSlotDim, cause);
    }

    @Override
    public BlockPos departToHyperspace(int srcSlotDim, BlockPos srcAnchor, HyperspaceTiles.Tile tile) {
        WorldServer src = DimensionManager.getWorld(srcSlotDim);
        WorldServer hyper = HyperspaceWorld.getOrCreate();
        // Three different reasons a departure never even starts, told apart. Rolled into one null they
        // are indistinguishable from a crossing that ran and failed, and the caller's log then blames
        // the cut for something that happened before it.
        if (src == null) {
            LOGGER.warn("[SPACE] depart aborted: no world for origin slot dim {} (the ship's cell is "
                    + "not bound to a live slot, or the id is from another session)", srcSlotDim);
            return null;
        }
        if (hyper == null) {
            LOGGER.warn("[SPACE] depart aborted: the shared hyperspace world could not be created "
                    + "(origin slot dim {})", srcSlotDim);
            return null;
        }
        if (srcAnchor == null) {
            LOGGER.warn("[SPACE] depart aborted: no origin anchor for the ship in slot dim {}",
                    srcSlotDim);
            return null;
        }
        VSIntegration.CrossResult res = VSIntegration.crossShip(
                src, srcAnchor.getX() + 0.5, srcAnchor.getY() + 0.5, srcAnchor.getZ() + 0.5,
                hyper, tile.pos.getX(), tile.pos.getY(), tile.pos.getZ());
        if (!res.ok()) {
            LOGGER.warn("[SPACE] depart aborted: the crossing out of slot dim {} at anchor {} produced "
                    + "no ship in hyperspace (source deregistered: {})",
                    srcSlotDim, srcAnchor, res.removed);
            return null;
        }
        // Park the just-assembled ship so it holds its lane while ShipTransit advances its coord logically.
        VSIntegration.parkShipAt(hyper, res.anchor.getX() + 0.5, res.anchor.getY() + 0.5, res.anchor.getZ() + 0.5);
        return res.anchor;
    }

    @Override
    public BlockPos arriveFromHyperspace(HyperspaceTiles.Tile tile, BlockPos hyperAnchor, int targetSlotDim) {
        WorldServer hyper = HyperspaceWorld.getOrCreate();
        WorldServer dst = DimensionManager.getWorld(targetSlotDim);
        if (hyper == null || dst == null || hyperAnchor == null) {
            // An arrival that never even reaches the crossing used to be a bare null, repeated once per
            // tick until the state machine gave up. crossShip logs each of ITS four failures, so the
            // absence of any line meant the crossing had not been attempted - but nothing said so, and
            // the only surviving evidence was "arrival never succeeded" 200 ticks later, which names no
            // cause at all. Three separate causes hid behind that silence; say which.
            warnArrivalGuardOnce(targetSlotDim,
                    hyper == null ? "the shared hyperspace world could not be created"
                            : dst == null ? "the target cell is bound to this slot but the slot has no "
                                    + "world - nothing was crossed and nothing was lost"
                            : "the ship has no anchor in hyperspace");
            return null;
        }
        arrivalGuardWarned.remove(targetSlotDim);
        // Redundant since the pool took to holding every slot a cell is bound to, and kept anyway: this is
        // the call site that can least afford to lose the world, because VS is still assembling the ship
        // here and an unload would discard it mid-flight. Stating the hold locally costs nothing and does
        // not rely on the caller having materialized the cell through the pool.
        DimensionManager.keepDimensionLoaded(targetSlotDim, true);
        int dstX = tile.index * ARRIVAL_LANE_STRIDE;
        VSIntegration.CrossResult res = VSIntegration.crossShip(
                hyper, hyperAnchor.getX() + 0.5, hyperAnchor.getY() + 0.5, hyperAnchor.getZ() + 0.5,
                dst, dstX, ARRIVAL_Y, 0);
        // The paste lands in the destination's BLOCK band; the ship is moved onto its real pose (and
        // unparked there) by the settle step, once the asynchronous re-assembly is queryable.
        return res.ok() ? res.anchor : null;
    }

    @Override
    public BlockPos settleArrivedPose(int targetSlotDim, BlockPos pasteAnchor,
                                      double px, double py, double pz) {
        // Keep the target cell's ships load-queued for as long as the settle is retried. VS only loads
        // a ship when a player is near it, and a jump arrives with nobody aboard into a cell world that
        // was materialized for it — so the freshly-pasted ship stays registered-but-unloaded, the pose
        // teleport's "ship is loaded" readiness gate never opens, and the arrival gives up in the paste
        // lane's block band. Every reader of the ship's address then inverts a block-band position
        // through the pose mapping and places it in the cell below. The entry/descent settle pumps the
        // same load queue for the same reason; an arrival is the third crossing, not a different kind
        // of move.
        ops.loadShips(targetSlotDim);
        // The same recipe the entry/descent settle uses (readiness gates, rider carry, unpark last).
        if (!ops.teleportPoseWithRiders(targetSlotDim, pasteAnchor, px, py, pz)) {
            return null; // re-assembly not queryable yet: retry next tick, the ship stays pasted
        }
        ops.unparkAt(targetSlotDim, px, py, pz);
        return new BlockPos(px, py, pz);
    }

    @Override
    public net.minecraft.nbt.NBTTagCompound snapshotParked(HyperspaceTiles.Tile tile, BlockPos hyperAnchor) {
        WorldServer hyper = HyperspaceWorld.getOrCreate();
        if (hyper == null || hyperAnchor == null) {
            return null;
        }
        // Non-destructive re-cut of the parked ship from its subspace shipyard (the ship stays in flight).
        return VSIntegration.snapshotShipAt(hyper,
                hyperAnchor.getX() + 0.5, hyperAnchor.getY() + 0.5, hyperAnchor.getZ() + 0.5);
    }

    @Override
    public net.minecraft.nbt.NBTTagCompound snapshotSource(int srcSlotDim, BlockPos srcAnchor) {
        WorldServer src = DimensionManager.getWorld(srcSlotDim);
        if (src == null || srcAnchor == null) {
            return null;
        }
        // The depart-time floor: snapshot the ship in its origin cell, non-destructively, BEFORE the depart
        // crossing cuts it. Same subspace-shipyard cut as snapshotParked, just against the source world.
        return VSIntegration.snapshotShipAt(src,
                srcAnchor.getX() + 0.5, srcAnchor.getY() + 0.5, srcAnchor.getZ() + 0.5);
    }

    @Override
    public BlockPos completeRestored(net.minecraft.nbt.NBTTagCompound snapshot, int targetSlotDim) {
        WorldServer dst = DimensionManager.getWorld(targetSlotDim);
        if (dst == null || snapshot == null) {
            // Same silence as the live arrival's guard, and the same retry-per-tick shape: discriminate,
            // once. A restored transit has no hyperspace ship to blame, so the two causes are the target
            // world and the snapshot the restore was supposed to carry.
            warnArrivalGuardOnce(targetSlotDim,
                    dst == null ? "the target cell is bound to this slot but the slot has no world"
                            : "the restored transit carries no block snapshot, so there is no ship to "
                                    + "paste");
            return null;
        }
        arrivalGuardWarned.remove(targetSlotDim);
        // Same local hold, same reason, as the live arrival above.
        DimensionManager.keepDimensionLoaded(targetSlotDim, true);
        // A restored transit holds no hyperspace lane. Paste it in the NEGATIVE-X band, DISJOINT from live
        // arrivals (which use tile.index*STRIDE, always >= 0), so a restored ship can never collide with a
        // live-crossing ship pasting into the same cell. Monotonic per boot (restored transits are imported
        // only at server start, a small set) so restored ships never overlap each other either - no wrap.
        // The snapshot source is always present (no async wait), so this pastes exactly once - a non-null
        // anchor on the first call, no retry - never a duplicate paste.
        int dstX = -ARRIVAL_LANE_STRIDE * (restoredLane++ + 1);
        return VSIntegration.pasteAndAssemble(dst, snapshot, dstX, ARRIVAL_Y, 0);
    }

    @Override
    public List<UUID> captureCrew(int srcSlotDim, BlockPos srcAnchor, String shipId) {
        WorldServer src = DimensionManager.getWorld(srcSlotDim);
        if (src == null || srcAnchor == null) {
            return Collections.emptyList();
        }
        // Locate the AFC FIRST: flightComputerAt force-loads the ship's far subspace shipyard, which is what
        // also makes CrewTransfer.capture's per-seat getTileEntity(seatPos) resolve (the aboard pilot only
        // chunk-loaded the ship's RENDER region, not its subspace shipyard). The AFC block is what capture
        // filters the ship's seats against.
        BlockPos afcPos = VSIntegration.flightComputerAt(src,
                srcAnchor.getX() + 0.5, srcAnchor.getY() + 0.5, srcAnchor.getZ() + 0.5);
        if (afcPos == null) {
            return Collections.emptyList(); // no flight computer on this ship - carry no crew
        }
        // The ship's live WORLD position, keyed by the AFC's SUBSPACE block: getShipWorldPosition takes a
        // managed subspace block (as entry/descent pass their afcPos), NOT the world anchor - passing the world
        // anchor returns null (it is not a block the ship manages).
        double[] shipWorldPos = VSIntegration.getShipWorldPosition(src, afcPos);
        if (shipWorldPos == null) {
            return Collections.emptyList();
        }
        List<CrewTransfer.Crew> crew = CrewTransfer.capture(src, afcPos, shipWorldPos);
        if (!crew.isEmpty()) {
            crewStash.put(shipId, crew);
        }
        List<UUID> ids = new ArrayList<>();
        for (CrewTransfer.Crew c : crew) {
            ids.add(c.player.getUniqueID());
        }
        return ids;
    }

    @Override
    public boolean reseatCrew(int targetSlotDim, BlockPos arrivalAnchor, String shipId) {
        List<CrewTransfer.Crew> stash = crewStash.get(shipId);
        if (stash == null || stash.isEmpty()) {
            return true; // crewless, restored (stash wiped on restart), or already reseated - nothing to do
        }
        WorldServer dst = DimensionManager.getWorld(targetSlotDim);
        if (dst == null || arrivalAnchor == null) {
            return false; // target world not up yet - retry next tick
        }
        // Keep the destination's ships load-queued so the re-assembled seat tiles become resolvable (the
        // proven entry/descent reseat path, VSShipCrossingOps.loadShips).
        VSIntegration.loadAllShips(dst);
        if (CrewTransfer.reseat(dst, arrivalAnchor, stash, toUuid(shipId))) {
            crewStash.remove(shipId);
            return true;
        }
        return false;
    }

    /** The transit keys ships by the AR ship UUID string; a non-UUID key (test fixtures) carries
     *  no durable identity, so the re-seat runs without the wrong-ship filter there. */
    private static java.util.UUID toUuid(String shipId) {
        try {
            return java.util.UUID.fromString(shipId);
        } catch (Exception e) {
            return null;
        }
    }
}
