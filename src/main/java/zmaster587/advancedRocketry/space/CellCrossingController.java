package zmaster587.advancedRocketry.space;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.util.math.BlockPos;

/**
 * Moves a settled ship from one cell into another in a SINGLE crossing.
 *
 * <p>Two things ask for that, and they differ only in how the destination is chosen:</p>
 *
 * <ul>
 *   <li><b>A seam carry</b> — the ship flew out through its cell's face, and the neighbour it left
 *       through is where it belongs ({@link #requestCarry}). Before this existed, a ship past its cell
 *       face was neither stopped nor carried: its pose kept going while the ledger report SATURATED at
 *       the boundary, so the ship was in one place and named in another. Everything keyed on the name
 *       then answered about the wrong cell — it could not descend (the named cell holds no bodies), its
 *       jumps were refused, and the cell it was really in lost the ledger's garbage-collection
 *       protection.</li>
 *   <li><b>A short jump</b> — the drive was fired at a destination the ship reaches in less time than
 *       the flight would take to present itself ({@link #requestDirectJump}). Routing that through
 *       hyperspace is pure overhead: two crossings and a park for a flight that is over before it
 *       starts. {@link ShipTransitManager} owns the decision; this owns the move.</li>
 * </ul>
 *
 * <p>The arithmetic of the seam — when a pose counts as having left, and where in the neighbour the
 * ship belongs — is {@link CellSeam}'s, and has no Minecraft in it. What lives here is the world half:
 * acquiring the destination cell, capturing the crew, driving the shared {@link ShipCrossingService},
 * and the refcount handoff.</p>
 *
 * <h3>The handoff order, and why it is not the other one</h3>
 *
 * <p>The destination is materialized <b>before</b> the source is released. The reverse order leaves a
 * window in which the ship holds no cell at all, and a garbage collection landing in that window
 * collects the very cell the ship is being pasted into. The cost of this order is that a refused
 * crossing must hand the destination back, which is what the failure paths below do.</p>
 *
 * <p>A refusal is a normal outcome, not an error: the pool can be full. A refused seam carry keeps
 * flying with its report saturated at the boundary — the old behaviour, now the fallback rather than
 * the rule — and is retried after a cooldown. A refused jump is reported to its caller, which has
 * already charged the pilot for the attempt.</p>
 */
public final class CellCrossingController {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/space");

    /** Ticks before a refused seam carry may be attempted again. */
    private static final int RETRY_COOLDOWN_TICKS = 100;

    /** The arrival paste band in the destination slot world — the entry crossing's geometry, because
     *  it is the same kind of destination: an empty slot world with nothing at its origin. */
    private static final int SEAM_PASTE_Z = -1024;
    private static final int SEAM_PASTE_Y = 200;
    private static final int SEAM_LANE_STRIDE = 64;
    private static final int SEAM_LANE_COUNT = 8;

    /**
     * What the crew is told, and what the log calls the move. The two callers differ in nothing else,
     * and a crossing that reported "carried into the next neighbourhood" for a jump across a system
     * would be lying to the only person who can see it.
     */
    private enum Kind {
        SEAM("cell-seam carry", "msg.shipseam.arrived", "msg.shipseam.failed"),
        JUMP("direct jump", "msg.shiptransit.arrived", "msg.shiptransit.directfailed");

        final String label;
        final String arrivedKey;
        final String failedKey;

        Kind(String label, String arrivedKey, String failedKey) {
            this.label = label;
            this.arrivedKey = arrivedKey;
            this.failedKey = failedKey;
        }
    }

    private final SpaceManager space;
    private final ShipLedger ledger;
    private final ShipCrossingService crossing;
    private final LongSupplier clock;
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private int laneCounter;

    public CellCrossingController(SpaceManager space, ShipLedger ledger, ShipCrossingService.Ops ops,
                                  LongSupplier clock) {
        this.space = space;
        this.ledger = ledger;
        this.crossing = new ShipCrossingService(ops);
        this.clock = clock;
    }

    /**
     * Carry the SETTLED ship at {@code afcPos} out of {@code cell} and into the neighbour its pose has
     * left through. Returns {@code true} when the crossing started, in which case the ship has been
     * cut out of this world and the caller must stop touching it this tick.
     *
     * <p>{@code shipPos} is passed in rather than re-read: the decision and the arrival must be
     * computed from the SAME pose. Re-reading it here would let a fast ship be judged on one position
     * and placed by another, and at these speeds the two can be thousands of blocks apart.</p>
     */
    public boolean requestCarry(int slotDim, BlockPos afcPos, UUID shipId, GalacticCoord cell,
                                double[] shipPos) {
        if (shipId == null || cell == null || shipPos == null || crossing.isCrossing(shipId)) {
            return false;
        }
        if (!isSettled(shipId)) {
            // Only a ship genuinely settled in a cell can leave one by flying. A ship mid-arrival sits
            // in the paste band, which is far outside its cell's pose range and would otherwise read as
            // an escape on every single crossing.
            return false;
        }
        if (!CellSeam.shouldCarry(shipPos[0], shipPos[1], shipPos[2])) {
            return false;
        }
        long now = clock.getAsLong();
        Long cooldown = retryAfter.get(shipId);
        if (cooldown != null && now < cooldown) {
            return false;
        }
        GalacticCoord destCoord = CellSeam.carriedCoord(cell, shipPos[0], shipPos[1], shipPos[2]);
        return cross(slotDim, afcPos, shipId, ledger.get(shipId).coord, destCoord, shipPos, Kind.SEAM);
    }

    /**
     * Cross the SETTLED ship at {@code afcPos} from {@code cell} straight into {@code target}, with no
     * hyperspace leg. Returns {@code true} when the crossing started.
     *
     * <p>Unlike a seam carry this has no cooldown and no refusal fallback: the caller has already
     * charged the drive for the attempt, so a {@code false} here is a failed jump that must be
     * reported, not a condition to be retried quietly next tick.</p>
     *
     * <p>It also READS the ship's pose rather than being handed one, which the seam may not do. The
     * seam's decision is <em>about</em> the pose — judged on one position and placed by another, a fast
     * ship lands thousands of blocks from where it was measured — while a jump's destination comes from
     * the pilot's target and does not depend on where in the cell the ship happens to be.</p>
     */
    public boolean requestDirectJump(int slotDim, BlockPos afcPos, UUID shipId, GalacticCoord cell,
                                     GalacticCoord target) {
        if (shipId == null || cell == null || target == null || crossing.isCrossing(shipId)) {
            return false;
        }
        if (!isSettled(shipId)) {
            return false;
        }
        double[] shipPos = crossing.ops().shipWorldPosition(slotDim, afcPos);
        if (shipPos == null) {
            LOGGER.warn("[SPACE] direct jump refused for ship {}: no ship resolves at {} in slot {}",
                    shipId, afcPos, slotDim);
            return false;
        }
        return cross(slotDim, afcPos, shipId, cell, target, shipPos, Kind.JUMP);
    }

    /** Whether the ledger has this ship SETTLED somewhere — the precondition both entries share. */
    private boolean isSettled(UUID shipId) {
        ShipLedger.Entry entry = ledger.get(shipId);
        return entry != null && entry.state == ShipLedger.State.SETTLED;
    }

    /** The move itself: acquire the destination, capture, cut, release the source, name the result. */
    private boolean cross(int slotDim, BlockPos afcPos, UUID shipId, GalacticCoord sourceCell,
                          GalacticCoord destCoord, double[] shipPos, Kind kind) {
        long now = clock.getAsLong();
        final int destSlotDim;
        try {
            destSlotDim = space.materialize(destCoord);
        } catch (SpaceManager.PoolExhaustedException full) {
            // No slot for the destination. The ship stays where it is. The crew is only READ here, so
            // nobody is dismounted by a refusal.
            List<CrewTransfer.Crew> told = crossing.ops().peekCrew(slotDim, afcPos, shipPos);
            LOGGER.warn("[SPACE] {} refused for ship {} leaving {}: {} (told {} aboard)",
                    kind.label, shipId, sourceCell.cellKey(), full.getMessage(),
                    told == null ? 0 : told.size());
            crossing.ops().messageCrew(told, "msg.shipseam.refused");
            if (kind == Kind.SEAM) {
                retryAfter.put(shipId, now + RETRY_COOLDOWN_TICKS);
            }
            return false;
        }

        // Capture only now, with the destination GRANTED — the last refusal is behind — and still
        // before the cut: the crossing cuts the seat blocks, and a post-cut capture finds nothing.
        final List<CrewTransfer.Crew> crew = crossing.ops().captureCrew(slotDim, afcPos, shipPos);

        int lane = (laneCounter++ % SEAM_LANE_COUNT);
        double[] pose = CellWorldMapper.poseWorldOf(destCoord);
        final GalacticCoord arrivalCoord = destCoord;
        final Kind arrivalKind = kind;
        BlockPos anchor = crossing.begin(shipId, slotDim, shipPos, destSlotDim,
                lane * SEAM_LANE_STRIDE, SEAM_PASTE_Y, SEAM_PASTE_Z, crew, pose,
                new ShipCrossingService.Completion() {
                    @Override
                    public void settled(UUID id) {
                        ledger.settle(id, arrivalCoord);
                        crossing.ops().messageCrew(crew, arrivalKind.arrivedKey);
                        LOGGER.info("[SPACE] {} settled: ship {} now in cell {} (slot {})",
                                arrivalKind.label, id, arrivalCoord.cellKey(), destSlotDim);
                    }

                    @Override
                    public void abandoned(UUID id) {
                        // The arrival never finished. The ship is somewhere in the destination slot
                        // world — which place depends on the half that stalled, and the crossing's own
                        // give-up line names it; do not claim one here. Settle it in the destination
                        // anyway: that IS the cell it is in, and leaving the row IN_TRANSIT would strand
                        // a real ship in a state nothing else advances.
                        ledger.settle(id, arrivalCoord);
                        crossing.ops().messageCrew(crew, arrivalKind.failedKey);
                        LOGGER.error("[SPACE] {} settle never completed for ship {} arriving in "
                                + "cell {} (slot {}) - see the crossing give-up line above for which "
                                + "half stalled", arrivalKind.label, id, arrivalCoord.cellKey(),
                                destSlotDim);
                    }
                });
        if (anchor == null) {
            LOGGER.error("[SPACE] {} crossing failed for ship {} leaving cell {}",
                    kind.label, shipId, sourceCell.cellKey());
            // The cut never produced a paste, so the ship is (best-effort) still intact where it was:
            // hand the destination back, re-seat the crew we already captured, and let it keep flying.
            space.dematerialize(destCoord);
            crossing.ops().reseat(slotDim,
                    new BlockPos(shipPos[0], shipPos[1], shipPos[2]), crew, shipId, null);
            crossing.ops().messageCrew(crew, kind.failedKey);
            if (kind == Kind.SEAM) {
                retryAfter.put(shipId, now + RETRY_COOLDOWN_TICKS);
            }
            return false;
        }

        // The ship is physically out of the source cell now, so the source is released NOW and not on
        // settle — the settle only completes the arrival on the far side. The destination refcount was
        // taken above, so the ship is never between cells.
        space.markDirty(sourceCell);
        space.dematerialize(sourceCell);
        space.markDirty(destCoord);
        // SETTLED at the destination, from the cut — deliberately NOT `beginTransit`. IN_TRANSIT is
        // not a generic "crossing" state: `LoginRestore` reads it as "parked in the shared hyperspace
        // world" and resolves the player through the transit dim, so a crossing ship wearing it
        // would orphan anyone who logged in during the few ticks of re-assembly. The row names the
        // cell the ship's blocks are actually in, which is also the cell whose refcount is held.
        //
        // For a jump this is also the whole saving: a crossing that never enters IN_TRANSIT has no
        // mid-flight for a restart to resume, so it needs no snapshot and cannot strand a ship.
        ledger.settle(shipId, destCoord);
        LOGGER.info("[SPACE] {} started: ship {} {} -> {} (slot {})",
                kind.label, shipId, sourceCell.cellKey(), destCoord.cellKey(), destSlotDim);
        return true;
    }

    /** Advance every in-flight crossing one tick (the shared crossing settle loop). */
    public void tick() {
        crossing.tick();
    }

    /** Whether {@code shipId} is being moved between cells right now — by either entry point. */
    public boolean isCarrying(UUID shipId) {
        return crossing.isCrossing(shipId);
    }
}
