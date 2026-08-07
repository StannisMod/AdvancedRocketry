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
 *       ceiling), then hands the momentary crossing + async settle to the shared
 *       {@link ShipCrossingService};</li>
 *   <li>on settle, rigid-teleports the pose to the honest-3D realization of the entry coordinate
 *       (world Y &asymp; local Y + HALF_CELL + band) and settles the ship in the {@link ShipLedger}.</li>
 * </ol>
 *
 * <p>Crossing + re-assembly are asynchronous, so the settle runs over several ticks with retries
 * inside {@link ShipCrossingService}. World-touching operations go through the shared
 * {@link ShipCrossingService.Ops} seam (production: {@code VSShipCrossingOps}) so the state machine
 * is testable without VS. Server main thread only.</p>
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

    /** Ticks a ship waits after a refused/failed entry before the ceiling check may re-trigger. */
    private static final int RETRY_COOLDOWN_TICKS = 100;

    /** Paste-lane geometry inside a slot world: entries paste along their own -Z row so they can
     *  never overlap a transit ARRIVAL lane (those paste along +X at z = 0). */
    private static final int ENTRY_PASTE_Z = -1024;
    private static final int ENTRY_PASTE_Y = 200;
    private static final int ENTRY_LANE_STRIDE = 64;
    private static final int ENTRY_LANE_COUNT = 8;

    /** Resolves a launch dimension id to the launch BODY's full galactic address (cell + local
     *  offset), or {@code null} for "no placement" (the config home anchor is used). Production
     *  wires the universe registry's {@code coordForPlanet} + zone-body match. */
    @FunctionalInterface
    public interface LaunchCoordResolver {
        GalacticCoord launchBodyAddress(int dimId);
    }

    private final SpaceManager space;
    private final ShipLedger ledger;
    private final ShipCrossingService crossing;
    private final LaunchCoordResolver coordResolver;
    private final LongSupplier clock;

    /** shipId -> earliest tick a refused/failed entry may re-trigger. */
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private int laneCounter;

    public ShipEntryController(SpaceManager space, ShipLedger ledger, ShipCrossingService.Ops ops,
                               LaunchCoordResolver coordResolver, LongSupplier clock) {
        this.space = space;
        this.ledger = ledger;
        this.crossing = new ShipCrossingService(ops);
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
     * How far below the physics mod's own altitude clamp the entry line is forced. The clamp is a
     * hard per-tick cap on the ship's pose, so a trigger that requires {@code shipY > line} can
     * only ever fire if the line sits comfortably BELOW the clamp - this margin is the room the
     * ship needs to demonstrably cross the line before the clamp stops it.
     */
    public static final int PHYSICS_CLAMP_ENTRY_MARGIN = 16;

    /**
     * The orbit line entry actually fires on: the dimension's configured orbit height, capped
     * below the physics mod's hard altitude clamp by {@link #PHYSICS_CLAMP_ENTRY_MARGIN}. With
     * stock configs both numbers are 1000, which used to make the trigger line physically
     * unreachable - the ship stopped dead at an invisible wall and the branch's headline feature
     * never fired. Deriving the line from the live clamp keeps the two from ever desyncing,
     * whatever either config says. An infinite {@code physicsCeiling} (physics mod absent) leaves
     * the configured orbit height untouched.
     */
    public static int effectiveEntryCeiling(int orbitHeight, double physicsCeiling) {
        if (Double.isInfinite(physicsCeiling)) {
            return orbitHeight;
        }
        return (int) Math.min(orbitHeight, physicsCeiling - PHYSICS_CLAMP_ENTRY_MARGIN);
    }

    /**
     * Begin an entry for the ship whose flight computer sits at {@code afcPos} in dimension
     * {@code launchDimId}: resolve the target coordinate, materialize its cell, run the crossing,
     * and queue the multi-tick settle. Returns {@code true} if the crossing was started (the ship
     * has left the launch world). Refusals (exhausted pool) and failures message the crew and arm
     * a retry cooldown — the ship stays below the ceiling and the check may fire again later.
     */
    public boolean requestEntry(int launchDimId, BlockPos afcPos, UUID shipId) {
        if (shipId == null || crossing.isCrossing(shipId) || ledger.get(shipId) != null) {
            return false; // already entering / already in space
        }
        long now = clock.getAsLong();
        Long cooldown = retryAfter.get(shipId);
        if (cooldown != null && now < cooldown) {
            return false;
        }

        double[] shipPos = crossing.ops().shipWorldPosition(launchDimId, afcPos);
        if (shipPos == null) {
            return false; // not on a physics ship (or it unloaded mid-check)
        }
        final GalacticCoord entryCoord = resolveEntryCoord(launchDimId, shipId);

        final int slotDim;
        try {
            slotDim = space.materialize(entryCoord);
        } catch (SpaceManager.PoolExhaustedException full) {
            // Refuse entry: a normal, surfaced outcome — the ship stays below the ceiling and the
            // pilot KEEPS HIS SEAT. The crew is only READ here (a capture would dismount it), so a
            // refusal costs the crew nothing but the message.
            LOGGER.warn("[SPACE] entry refused for ship {}: {}", shipId, full.getMessage());
            crossing.ops().messageCrew(crossing.ops().peekCrew(launchDimId, afcPos, shipPos),
                    "msg.shipentry.refused");
            retryAfter.put(shipId, now + RETRY_COOLDOWN_TICKS);
            return false;
        }

        // Capture only now, with the cell GRANTED — the last refusal is behind — and still before
        // the cut: the crossing cuts the seat blocks, and a post-cut capture finds nothing.
        final List<CrewTransfer.Crew> crew = crossing.ops().captureCrew(launchDimId, afcPos, shipPos);

        int lane = (laneCounter++ % ENTRY_LANE_COUNT);
        double[] pose = CellWorldMapper.poseWorldOf(entryCoord);
        BlockPos anchor = crossing.begin(shipId, launchDimId, shipPos, slotDim,
                lane * ENTRY_LANE_STRIDE, ENTRY_PASTE_Y, ENTRY_PASTE_Z, crew, pose,
                new ShipCrossingService.Completion() {
                    @Override
                    public void settled(UUID id) {
                        ledger.settle(id, entryCoord);
                        crossing.ops().messageCrew(crew, "msg.shipentry.arrived");
                        LOGGER.info("[SPACE] entry settled: ship {} at {} (slot {})",
                                id, entryCoord, slotDim);
                    }

                    @Override
                    public void abandoned(UUID id) {
                        // The arrival never finished. The ship is somewhere in the slot world (the
                        // cell is dirty, so it flushes) — which place depends on the half that
                        // stalled, and the crossing's own give-up line names it; do not claim one
                        // here. Settle it cleanly rather than spin forever.
                        ledger.settle(id, entryCoord);
                        crossing.ops().messageCrew(crew, "msg.shipentry.failed");
                        LOGGER.error("[SPACE] entry settle never completed for ship {} arriving in "
                                + "slot {} - see the crossing give-up line above for which half "
                                + "stalled", id, slotDim);
                    }
                });
        if (anchor == null) {
            LOGGER.error("[SPACE] entry crossing failed for ship {} from dim {}", shipId, launchDimId);
            space.dematerialize(entryCoord);
            // A null anchor means the cut never produced a paste — the ship is (best-effort) still
            // intact in the launch world, so put the already-captured crew back on their seats
            // before messaging them; a missing seat just leaves that rider standing.
            crossing.ops().reseat(launchDimId,
                    new BlockPos(shipPos[0], shipPos[1], shipPos[2]), crew, shipId, null);
            crossing.ops().messageCrew(crew, "msg.shipentry.failed");
            retryAfter.put(shipId, now + RETRY_COOLDOWN_TICKS);
            return false;
        }
        // The paste diverged the cell from its procedural seed — eviction must flush, not discard.
        space.markDirty(entryCoord);
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
        return StandoffRing.pointAround(body, ENTRY_RING_BLOCKS, shipId.hashCode());
    }

    /** Advance every in-flight entry one tick (the shared crossing settle loop). */
    public void tick() {
        crossing.tick();
    }

    /** Whether {@code shipId} has an entry in flight. */
    public boolean isEntering(UUID shipId) {
        return crossing.isCrossing(shipId);
    }

    /** Number of in-flight entries. */
    public int enteringCount() {
        return crossing.crossingCount();
    }
}
