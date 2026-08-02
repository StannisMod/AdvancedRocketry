package zmaster587.advancedRocketry.navigation;

import zmaster587.advancedRocketry.space.CellFrames;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Where to aim at a moving destination.
 *
 * <p>A jump takes time, and in that time the destination moves. Not its NAME — a cell name is
 * durable, so the cell a ship arrives into is the cell it aimed at, at every tick (C15 ADDR-14). What
 * moves is the POINT: the body's cell frame slides along the body's orbit, a moon slides inside that
 * frame, and both are somewhere else by the time the ship comes out of hyperspace. Aiming at where the
 * destination is NOW therefore prices the wrong flight and puts the ship down at the wrong end of the
 * neighbourhood.</p>
 *
 * <p>The aim point and the flight time depend on each other: a further aim point means a longer flight,
 * and a longer flight means the body has moved further. This resolves that by <b>bounded iteration</b>
 * — price the flight to where the body is now, re-aim at where it will be when that flight ends, and
 * repeat. It stops the moment two passes agree on BOTH the flight time and the aim point, and in any
 * case after {@link #MAX_PASSES} passes, so a pathological orbit costs a fixed handful of trig calls
 * rather than spinning. Not converging is not a failure: the last pass is still a far better aim than
 * the present position, which is what the unpredicted code did.</p>
 *
 * <p>The convergence test used to be {@code next.sameCell(aim)} — which, once names became durable,
 * is satisfied on pass 1 by construction and made the whole iteration a no-op. The test belongs on
 * the point, which is what actually moves.</p>
 *
 * <p>Pure — the ephemeris, the flight-time law and the frames the distance is measured through are all
 * injected, so the aiming rule can be checked without a world, a registry or a drive.</p>
 */
public final class TargetPrediction {

    /**
     * How many refinement passes to spend before accepting the current answer. Four is far more than
     * the one or two a real orbit needs (a transit is short next to an orbital period, so the second
     * pass almost always reproduces the first). {@code tunable}.
     */
    public static final int MAX_PASSES = 4;

    /** A body's full address — cell name plus in-cell offset — at a given world tick, or {@code null}. */
    public interface Ephemeris {
        GalacticCoord addressAt(int dimId, long worldTick);
    }

    /** How long a flight of {@code distanceBlocks} takes, in ticks. */
    public interface Flight {
        long ticksFor(double distanceBlocks);
    }

    private TargetPrediction() {
    }

    /**
     * The address to aim at for the body in dimension {@code dimId}, departing from {@code origin} at
     * world tick {@code now}, measuring distances through {@code frames}. Returns {@code null} when
     * the body cannot be located at all — the caller must treat that as "this ship no longer knows
     * where its target is", never as an aim of its own.
     *
     * <p>A {@code null} origin (a ship with no recorded position) cannot be priced, so the answer is
     * simply the body's present address; such a ship is refused by the gate long before it gets to
     * fly. A {@code null} {@code frames} falls back to the static reading, which is what a caller
     * with no registry has.</p>
     */
    public static GalacticCoord aimAt(int dimId, GalacticCoord origin, long now,
                                      Ephemeris ephemeris, Flight flight, CellFrames frames) {
        if (ephemeris == null) {
            return null;
        }
        GalacticCoord aim = ephemeris.addressAt(dimId, now);
        if (aim == null || origin == null || flight == null) {
            return aim;
        }
        CellFrames geometry = frames == null ? CellFrames.STATIC : frames;
        long ticks = -1L;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            // Price the flight against where the target will be when it ENDS, not where it is now:
            // the frame term dominates over a jump (a destination planet's frame moves on the order of
            // a hundred descent radii while the ship is in hyperspace).
            long nextTicks = flight.ticksFor(geometry.distanceBetween(origin, aim, now + Math.max(0L, ticks)));
            GalacticCoord next = ephemeris.addressAt(dimId, now + nextTicks);
            if (next == null) {
                return aim; // the body went out of view mid-refinement: keep the best aim so far
            }
            if (nextTicks == ticks && next.equals(aim)) {
                return next; // settled: another pass would price the same flight and re-derive this
            }
            ticks = nextTicks;
            aim = next;
        }
        return aim;
    }
}
