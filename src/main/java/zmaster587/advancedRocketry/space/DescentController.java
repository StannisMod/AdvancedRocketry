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
 * The tier-2 <b>planet descent</b>: how a ship in space drops onto a planet — the inverse of the
 * {@link ShipEntryController} ascent on-ramp. Descent is by PROXIMITY: the flight computer's tick
 * detects that a SETTLED slot-world ship whose pilot is flying has closed within
 * {@link ShipEntryController#DESCENT_RADIUS_BLOCKS} of a descend-target body's POI and calls
 * {@link #requestDescent}; this controller then:
 *
 * <ol>
 *   <li>guards that the ship is genuinely in space (a SETTLED ledger entry — the INVERSE of entry's
 *       "not already in space" guard);</li>
 *   <li>resolves a terrain-aware paste + landing in the target planet dimension through the injected
 *       {@link PasteResolver} (an unfittable ship — too tall for the terrain — is REFUSED cleanly);</li>
 *   <li>hands the momentary crossing + async settle to the shared {@link ShipCrossingService}, then,
 *       once the ship is physically cut from its space cell, releases that cell (dirty + dematerialize)
 *       and drops the ledger entry — the ship has left the subsystem;</li>
 *   <li>on settle, the crew is told they arrived; the pilot flies down from the landing height.</li>
 * </ol>
 *
 * <p>World-touching operations go through the shared {@link ShipCrossingService.Ops} seam
 * (production: {@code VSShipCrossingOps}) and the {@link PasteResolver} (production:
 * {@code VSDescentPasteResolver}) so the state machine is testable without VS. Server main thread
 * only. Ascent (entry) and descent hold their own {@link ShipCrossingService} instances.</p>
 */
public final class DescentController {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/space");

    /** Ticks a ship waits after a refused/failed descent before the proximity check may re-fire. */
    private static final int RETRY_COOLDOWN_TICKS = 100;

    /** Number of paste lanes: simultaneous descents onto one planet spread across them. */
    private static final int DESCENT_LANE_COUNT = 8;

    /** Resolves where to paste + land a descending ship in the target planet dimension, or {@code null}
     *  when the ship cannot fit above the terrain there / the world is missing. Production wires the
     *  terrain height finder + the VS ship-geometry read; fakeable in tests. */
    public interface PasteResolver {
        Landing resolve(int slotDim, double[] shipWorldPos, int destPlanetDim, int laneIndex);
    }

    /** A resolved descent target: the block paste corner (clear sky above terrain) and the world pose
     *  the settle rigid-teleports the re-assembled ship to. For descent the two coincide (the ship
     *  arrives above the terrain it was pasted over), unlike entry's void-slot paste + far cell pose. */
    public static final class Landing {
        public final int pasteX;
        public final int pasteY;
        public final int pasteZ;
        public final double[] landingPose;

        public Landing(int pasteX, int pasteY, int pasteZ, double[] landingPose) {
            this.pasteX = pasteX;
            this.pasteY = pasteY;
            this.pasteZ = pasteZ;
            this.landingPose = landingPose;
        }
    }

    private final SpaceManager space;
    private final ShipLedger ledger;
    private final ShipCrossingService crossing;
    private final PasteResolver pasteResolver;
    private final LongSupplier clock;

    /** shipId -> earliest tick a refused/failed descent may re-trigger. */
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private int laneCounter;

    public DescentController(SpaceManager space, ShipLedger ledger, ShipCrossingService.Ops ops,
                             PasteResolver pasteResolver, LongSupplier clock) {
        this.space = space;
        this.ledger = ledger;
        this.crossing = new ShipCrossingService(ops);
        this.pasteResolver = pasteResolver;
        this.clock = clock;
    }

    /**
     * Pure trigger predicate for the flight computer's proximity check: descent fires only from a
     * space-subsystem (slot) world — the INVERSE of {@code shouldTriggerEntry} — only with a pilot
     * actually flying, and only once the ship has closed within the descent radius of a body.
     */
    public static boolean shouldTriggerDescent(boolean isSpaceSubsystemWorld, boolean pilotPresent,
                                               double shipDistanceToBody, long radiusBlocks) {
        return isSpaceSubsystemWorld && pilotPresent && shipDistanceToBody <= radiusBlocks;
    }

    /**
     * Begin a descent for the SETTLED ship whose flight computer sits at {@code afcPos} in slot
     * dimension {@code slotDim}, onto {@code targetPlanetDim}. Returns {@code true} if the crossing
     * was started (the ship has left its space cell). A ship not currently in space, or one already
     * crossing, or one that cannot fit above the target terrain, is refused (message + cooldown).
     */
    public boolean requestDescent(int slotDim, BlockPos afcPos, UUID shipId, int targetPlanetDim) {
        if (shipId == null || crossing.isCrossing(shipId)) {
            return false; // already descending
        }
        ShipLedger.Entry entry = ledger.get(shipId);
        if (entry == null || entry.state != ShipLedger.State.SETTLED) {
            return false; // only a ship genuinely in space can descend (the inverse of entry's guard)
        }
        long now = clock.getAsLong();
        Long cooldown = retryAfter.get(shipId);
        if (cooldown != null && now < cooldown) {
            return false;
        }

        double[] shipPos = crossing.ops().shipWorldPosition(slotDim, afcPos);
        if (shipPos == null) {
            return false; // not on a physics ship (or it unloaded mid-check)
        }

        final GalacticCoord sourceCell = entry.coord;

        int laneIndex = (laneCounter++ % DESCENT_LANE_COUNT);
        Landing landing = pasteResolver.resolve(slotDim, shipPos, targetPlanetDim, laneIndex);
        if (landing == null) {
            // No clear landing above the terrain (the ship is too tall for it) — a surfaced
            // outcome, and the pilot KEEPS HIS SEAT: the crew is only READ here (a capture would
            // dismount it), so a refusal costs the crew nothing but the message.
            LOGGER.warn("[SPACE] descent refused for ship {}: no landing fits in dim {}",
                    shipId, targetPlanetDim);
            crossing.ops().messageCrew(crossing.ops().peekCrew(slotDim, afcPos, shipPos),
                    "msg.shipdescent.refused");
            retryAfter.put(shipId, now + RETRY_COOLDOWN_TICKS);
            return false;
        }

        // Capture only now, with the landing RESOLVED — the last refusal is behind — and still
        // before the cut: the crossing cuts the seat blocks, and a post-cut capture finds nothing.
        final List<CrewTransfer.Crew> crew = crossing.ops().captureCrew(slotDim, afcPos, shipPos);

        final List<CrewTransfer.Crew> settledCrew = crew;
        BlockPos anchor = crossing.begin(shipId, slotDim, shipPos, targetPlanetDim,
                landing.pasteX, landing.pasteY, landing.pasteZ, crew, landing.landingPose,
                new ShipCrossingService.Completion() {
                    @Override
                    public void settled(UUID id) {
                        crossing.ops().messageCrew(settledCrew, "msg.shipdescent.arrived");
                        LOGGER.info("[SPACE] descent settled: ship {} on dim {}", id, targetPlanetDim);
                    }

                    @Override
                    public void abandoned(UUID id) {
                        // The re-assembled ship never became workable; the blocks are at the paste
                        // site in the planet dim. Tell the crew and stop spinning — the source cell
                        // was already released below, so the ship has left space either way.
                        crossing.ops().messageCrew(settledCrew, "msg.shipdescent.failed");
                        LOGGER.error("[SPACE] descent settle never completed for ship {} - ship left "
                                + "at the paste site in dim {}", id, targetPlanetDim);
                    }
                });
        if (anchor == null) {
            LOGGER.error("[SPACE] descent crossing failed for ship {} from slot {}", shipId, slotDim);
            // A null anchor means the cut never produced a paste — the ship is (best-effort) still
            // intact in its slot cell, so put the already-captured crew back on their seats before
            // messaging them; a missing seat just leaves that rider standing.
            crossing.ops().reseat(slotDim,
                    new BlockPos(shipPos[0], shipPos[1], shipPos[2]), crew, shipId);
            crossing.ops().messageCrew(crew, "msg.shipdescent.failed");
            retryAfter.put(shipId, now + RETRY_COOLDOWN_TICKS);
            return false;
        }
        // The crossing cut the ship out of its space cell — the cell diverged (a ship left it), the
        // occupant is released, and the ship is no longer in the subsystem. Do this NOW (the ship is
        // physically gone), not on settle: the settle only completes the arrival on the planet side.
        space.markDirty(sourceCell);
        space.dematerialize(sourceCell);
        ledger.remove(shipId);
        LOGGER.info("[SPACE] descent crossing started: ship {} leaving cell {} -> dim {}",
                shipId, sourceCell.cellKey(), targetPlanetDim);
        return true;
    }

    /** Advance every in-flight descent one tick (the shared crossing settle loop). */
    public void tick() {
        crossing.tick();
    }

    /** Whether {@code shipId} has a descent in flight. */
    public boolean isDescending(UUID shipId) {
        return crossing.isCrossing(shipId);
    }

    /** Number of in-flight descents. */
    public int descendingCount() {
        return crossing.crossingCount();
    }
}
