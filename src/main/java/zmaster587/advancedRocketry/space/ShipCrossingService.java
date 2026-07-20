package zmaster587.advancedRocketry.space;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.util.math.BlockPos;

/**
 * The generalized per-ship <b>crossing</b> shared by the entry on-ramp ({@link ShipEntryController},
 * planet&rarr;cell) and the planet descent ({@link DescentController}, cell&rarr;planet). It owns the
 * momentary crossing (pin the destination, run the per-ship pack/paste) and the asynchronous
 * multi-tick <b>settle</b> (rigid-teleport the re-assembled ship to its final pose, then re-seat the
 * crew AT that pose, then unpark). The settle order is a hard invariant: the pose teleport runs
 * BEFORE the re-seat, so a rider is never mounted onto a mount that is about to move — re-seating
 * first once left a freshly-mounted pilot at the paste band while his mount teleported to the cell
 * pose, a split the client can never recover from (the new mount spawns out of tracking range, so
 * the client is never told it exists and un-seats when the old mount's destroy packet lands). The
 * DIRECTION-specific decisions — where the ship goes, the ledger/refcount bookkeeping, the
 * player-facing messages — stay in each controller and are delivered here through {@link Ops}
 * (the world seam) and a {@link Completion} callback.
 *
 * <p>{@link ShipTransitManager} keeps the hyperspace legs (it advances a coordinate logically rather
 * than crossing a boundary); this service is for a boundary crossing that physically moves a ship's
 * blocks from one world into another. World-touching operations go through {@link Ops} (production:
 * {@code VSShipCrossingOps}) so the state machine is testable without VS. Server main thread only.</p>
 */
public final class ShipCrossingService {

    /** Max ticks to retry the re-seat + pose-teleport half before giving up (async VS assembly). */
    private static final int MAX_SETTLE_ATTEMPTS = 200;

    /** The world-operation seam (production: {@code VSShipCrossingOps}); fakeable in tests. Worlds
     *  are addressed by dimension id only, so the state machine itself never touches a World type
     *  (the {@link ShipTransitManager.Crosser} discipline). */
    public interface Ops {
        /** The ship's live world position read off its managed block, or {@code null}. */
        double[] shipWorldPosition(int dimId, BlockPos afcPos);

        /** Enumerate + dismount the seated crew of the ship at {@code afcPos}. Pre-cut. */
        List<CrewTransfer.Crew> captureCrew(int dimId, BlockPos afcPos, double[] shipWorldPos);

        /** Cross the ship at {@code srcShipPos} into {@code destDim} at the paste point.
         *  Returns the re-assembly anchor, or {@code null} on failure. */
        BlockPos cross(int srcDimId, double[] srcShipPos, int destDim,
                       int pasteX, int pasteY, int pasteZ);

        /** Pin {@code dimId} loaded across the crossing (the arrival pin pattern). */
        void pinDim(int dimId);

        /** Queue every ship in the destination world loaded (async assembly may lack a player yet). */
        void loadShips(int destDim);

        /** Re-seat the captured crew on the re-assembled ship. Runs AFTER the pose teleport, so
         *  {@code anchor} is a world point on the ship at its FINAL pose (the paste anchor no
         *  longer resolves the moved ship). {@code false} = retry next tick. */
        boolean reseat(int destDim, BlockPos anchor, List<CrewTransfer.Crew> crew);

        /** Rigid-teleport the ship near {@code anchor} to the pose position, carrying riders.
         *  Runs FIRST in the settle (before the re-seat), so it owns its own proof that the
         *  asynchronous re-assembly is complete. The ship comes out PARKED. {@code false} = ship
         *  not up yet, retry. */
        boolean teleportPoseWithRiders(int destDim, BlockPos anchor, double px, double py, double pz);

        /** Re-enable physics on the ship at the (post-teleport) pose position. */
        void unparkAt(int destDim, double px, double py, double pz);

        /** Player-facing message to the captured crew (a refusal, a failure, an arrival). */
        void messageCrew(List<CrewTransfer.Crew> crew, String langKey, Object... args);
    }

    /** The direction-specific finalizer, invoked from {@link #tick()} once a queued crossing resolves.
     *  Entry settles the ship in the ledger; descent releases the source cell and drops the ledger
     *  entry. Both are called on the server main thread AFTER the pending crossing is removed. */
    public interface Completion {
        /** The ship re-assembled, re-seated and reached its final pose (unpark already done). */
        void settled(UUID shipId);

        /** The re-assembly never became workable within {@link #MAX_SETTLE_ATTEMPTS} — the blocks
         *  are at the paste site; finalize cleanly rather than spin forever. */
        void abandoned(UUID shipId);
    }

    /** One in-flight crossing (the momentary cross is done; settling over ticks). */
    private static final class Pending {
        final UUID shipId;
        final int destDim;
        final BlockPos anchor;
        final List<CrewTransfer.Crew> crew;
        final double[] finalPose;
        final Completion completion;
        boolean reseated;
        boolean poseDone;
        int attempts;

        Pending(UUID shipId, int destDim, BlockPos anchor, List<CrewTransfer.Crew> crew,
                double[] finalPose, Completion completion) {
            this.shipId = shipId;
            this.destDim = destDim;
            this.anchor = anchor;
            this.crew = crew;
            this.finalPose = finalPose;
            this.completion = completion;
        }
    }

    private final Ops ops;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    public ShipCrossingService(Ops ops) {
        this.ops = ops;
    }

    /** The world seam, so a controller can read a ship's position / capture crew / message it. */
    public Ops ops() {
        return ops;
    }

    /**
     * Run the momentary crossing NOW: pin the destination, then pack/paste the ship into it. On
     * success queue the multi-tick settle to {@code finalPose} and return the re-assembly anchor; on
     * failure return {@code null} (the caller undoes its pre-crossing bookkeeping). {@code tick()}
     * then drives re-seat &rarr; pose &rarr; unpark and finally invokes {@code completion.settled}.
     */
    public BlockPos begin(UUID shipId, int srcDim, double[] srcShipPos, int destDim,
                          int pasteX, int pasteY, int pasteZ,
                          List<CrewTransfer.Crew> crew, double[] finalPose, Completion completion) {
        // Pin the destination across the async crossing (an occupant-less pool slot auto-unloads at
        // tick end, discarding the ship VS is still assembling; a planet dim is usually loaded, but
        // the pin is dim-agnostic and harmless when the dim is already held).
        ops.pinDim(destDim);
        BlockPos anchor = ops.cross(srcDim, srcShipPos, destDim, pasteX, pasteY, pasteZ);
        if (anchor == null) {
            return null;
        }
        pending.put(shipId, new Pending(shipId, destDim, anchor, crew, finalPose, completion));
        return anchor;
    }

    /**
     * Advance every in-flight crossing one tick: keep the destination's ships load-queued,
     * rigid-teleport the pose to the final position once the re-assembly is queryable, a tick later
     * re-seat the crew AT that pose, then unpark and hand off to the controller's
     * {@link Completion}. Pose before re-seat is the split-pair invariant (class javadoc); the
     * tick between them lets the moved transform propagate so the seat lookup already maps
     * through the arrived pose.
     */
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Pending e = it.next().getValue();
            ops.loadShips(e.destDim);
            if (!e.poseDone) {
                e.poseDone = ops.teleportPoseWithRiders(
                        e.destDim, e.anchor, e.finalPose[0], e.finalPose[1], e.finalPose[2]);
            } else if (!e.reseated) {
                // The ship sits at its final pose now, so the paste anchor no longer resolves it —
                // the re-seat probes at the pose itself, and the crew's fresh mounts (and the crew)
                // are born directly there: no write ever targets a superseded position.
                e.reseated = ops.reseat(e.destDim, new BlockPos(
                        e.finalPose[0], e.finalPose[1], e.finalPose[2]), e.crew);
            } else {
                // A tick after the re-seat: unpark at the pose, then let the controller
                // settle/release. Removed from the map before the callback so a completion that
                // re-queries this service sees the crossing as done.
                ops.unparkAt(e.destDim, e.finalPose[0], e.finalPose[1], e.finalPose[2]);
                it.remove();
                e.completion.settled(e.shipId);
                continue;
            }
            if (++e.attempts >= MAX_SETTLE_ATTEMPTS) {
                it.remove();
                e.completion.abandoned(e.shipId);
            }
        }
    }

    /** Whether {@code shipId} has a crossing in flight. */
    public boolean isCrossing(UUID shipId) {
        return pending.containsKey(shipId);
    }

    /** Number of in-flight crossings. */
    public int crossingCount() {
        return pending.size();
    }
}
