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
 * Carries a ship that has flown out of its cell into the neighbouring cell it left through.
 *
 * <p>Before this existed, a ship past its cell face was neither stopped nor carried: its pose kept
 * going while the ledger report SATURATED at the boundary, so the ship was in one place and named in
 * another. Everything keyed on the name then answered about the wrong cell — it could not descend
 * (the named cell holds no bodies), its jumps were refused, and the cell it was really in lost the
 * ledger's garbage-collection protection.</p>
 *
 * <p>The arithmetic — when a pose counts as having left, and where in the neighbour the ship belongs
 * — is {@link CellSeam}'s, and has no Minecraft in it. What lives here is the world half: acquiring
 * the destination cell, capturing the crew, driving the shared {@link ShipCrossingService}, and the
 * refcount handoff.</p>
 *
 * <h3>The handoff order, and why it is not the other one</h3>
 *
 * <p>The destination is materialized <b>before</b> the source is released. The reverse order leaves a
 * window in which the ship holds no cell at all, and a garbage collection landing in that window
 * collects the very cell the ship is being pasted into. The cost of this order is that a refused
 * carry must hand the destination back, which is what the failure paths below do.</p>
 *
 * <p>A refusal is a normal outcome, not an error: the pool can be full. A refused ship keeps flying
 * with its report saturated at the boundary — the old behaviour, now the fallback rather than the
 * rule — and the carry is retried after a cooldown.</p>
 */
public final class CellSeamController {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/space");

    /** Ticks before a refused carry may be attempted again. */
    private static final int RETRY_COOLDOWN_TICKS = 100;

    /** The arrival paste band in the destination slot world — the entry crossing's geometry, because
     *  it is the same kind of destination: an empty slot world with nothing at its origin. */
    private static final int SEAM_PASTE_Z = -1024;
    private static final int SEAM_PASTE_Y = 200;
    private static final int SEAM_LANE_STRIDE = 64;
    private static final int SEAM_LANE_COUNT = 8;

    private final SpaceManager space;
    private final ShipLedger ledger;
    private final ShipCrossingService crossing;
    private final LongSupplier clock;
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private int laneCounter;

    public CellSeamController(SpaceManager space, ShipLedger ledger, ShipCrossingService.Ops ops,
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
        ShipLedger.Entry entry = ledger.get(shipId);
        if (entry == null || entry.state != ShipLedger.State.SETTLED) {
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

        final GalacticCoord sourceCell = entry.coord;
        final GalacticCoord destCoord = CellSeam.carriedCoord(cell, shipPos[0], shipPos[1], shipPos[2]);

        final int destSlotDim;
        try {
            destSlotDim = space.materialize(destCoord);
        } catch (SpaceManager.PoolExhaustedException full) {
            // No slot for the neighbour. The ship stays where it is, keeps flying, and keeps reporting
            // saturated at the face — wrong by the overshoot, but pointing at a cell that exists. The
            // crew is only READ here, so nobody is dismounted by a refusal.
            List<CrewTransfer.Crew> told = crossing.ops().peekCrew(slotDim, afcPos, shipPos);
            LOGGER.warn("[SPACE] cell-seam carry refused for ship {} leaving {}: {} (told {} aboard)",
                    shipId, sourceCell.cellKey(), full.getMessage(), told == null ? 0 : told.size());
            crossing.ops().messageCrew(told, "msg.shipseam.refused");
            retryAfter.put(shipId, now + RETRY_COOLDOWN_TICKS);
            return false;
        }

        // Capture only now, with the destination GRANTED — the last refusal is behind — and still
        // before the cut: the crossing cuts the seat blocks, and a post-cut capture finds nothing.
        final List<CrewTransfer.Crew> crew = crossing.ops().captureCrew(slotDim, afcPos, shipPos);

        int lane = (laneCounter++ % SEAM_LANE_COUNT);
        double[] pose = CellWorldMapper.poseWorldOf(destCoord);
        BlockPos anchor = crossing.begin(shipId, slotDim, shipPos, destSlotDim,
                lane * SEAM_LANE_STRIDE, SEAM_PASTE_Y, SEAM_PASTE_Z, crew, pose,
                new ShipCrossingService.Completion() {
                    @Override
                    public void settled(UUID id) {
                        ledger.settle(id, destCoord);
                        crossing.ops().messageCrew(crew, "msg.shipseam.arrived");
                        LOGGER.info("[SPACE] cell-seam carry settled: ship {} now in cell {} (slot {})",
                                id, destCoord.cellKey(), destSlotDim);
                    }

                    @Override
                    public void abandoned(UUID id) {
                        // The arrival never finished. The ship is somewhere in the destination slot
                        // world — which place depends on the half that stalled, and the crossing's own
                        // give-up line names it; do not claim one here. Settle it in the destination
                        // anyway: that IS the cell it is in, and leaving the row IN_TRANSIT would strand
                        // a real ship in a state nothing else advances.
                        ledger.settle(id, destCoord);
                        crossing.ops().messageCrew(crew, "msg.shipseam.failed");
                        LOGGER.error("[SPACE] cell-seam settle never completed for ship {} arriving in "
                                + "cell {} (slot {}) - see the crossing give-up line above for which "
                                + "half stalled", id, destCoord.cellKey(), destSlotDim);
                    }
                });
        if (anchor == null) {
            LOGGER.error("[SPACE] cell-seam crossing failed for ship {} leaving cell {}",
                    shipId, sourceCell.cellKey());
            // The cut never produced a paste, so the ship is (best-effort) still intact where it was:
            // hand the destination back, re-seat the crew we already captured, and let it keep flying.
            space.dematerialize(destCoord);
            crossing.ops().reseat(slotDim,
                    new BlockPos(shipPos[0], shipPos[1], shipPos[2]), crew, shipId, null);
            crossing.ops().messageCrew(crew, "msg.shipseam.failed");
            retryAfter.put(shipId, now + RETRY_COOLDOWN_TICKS);
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
        // world" and resolves the player through the transit dim, so a seam-crossing ship wearing it
        // would orphan anyone who logged in during the few ticks of re-assembly. The row names the
        // cell the ship's blocks are actually in, which is also the cell whose refcount is held.
        ledger.settle(shipId, destCoord);
        LOGGER.info("[SPACE] cell-seam carry started: ship {} {} -> {} (slot {})",
                shipId, sourceCell.cellKey(), destCoord.cellKey(), destSlotDim);
        return true;
    }

    /** Advance every in-flight seam carry one tick (the shared crossing settle loop). */
    public void tick() {
        crossing.tick();
    }

    /** Whether {@code shipId} is being carried across a cell face right now. */
    public boolean isCarrying(UUID shipId) {
        return crossing.isCrossing(shipId);
    }
}
