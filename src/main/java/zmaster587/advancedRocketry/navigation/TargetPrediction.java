package zmaster587.advancedRocketry.navigation;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Where to aim at a moving destination.
 *
 * <p>A body orbits, and a jump takes time. Aiming at where the destination IS therefore sends the ship
 * to where the destination WAS: it arrives, minutes later, at an address the planet has already left,
 * with the capacitor burst spent and nothing to descend onto. What a navigation computer is for — the
 * reason a ship carries one at all — is precisely this: it knows the destination's motion, so it aims
 * at where the body will be at the moment the ship comes out of hyperspace.</p>
 *
 * <p>The aim point and the flight time depend on each other: a further aim point means a longer flight,
 * and a longer flight means the body has moved further. This resolves that by <b>bounded iteration</b>
 * — aim at the body's present cell, price the flight to it, re-aim at where the body will be when that
 * flight ends, and repeat. It stops the moment two passes agree on the CELL (the answer only has to be
 * right to a cell, which is what an address is), and in any case after {@link #MAX_PASSES} passes, so
 * a pathological orbit costs a fixed handful of trig calls rather than spinning. Not converging is not
 * a failure: the last pass is still a far better aim than the present position, which is what the
 * unpredicted code did.</p>
 *
 * <p>Pure — both the ephemeris and the flight-time law are injected, so the aiming rule can be checked
 * without a world, a registry or a drive.</p>
 */
public final class TargetPrediction {

    /**
     * How many refinement passes to spend before accepting the current answer. Four is far more than
     * the one or two a real orbit needs (a transit is short next to an orbital period, so the second
     * pass almost always reproduces the first). {@code tunable}.
     */
    public static final int MAX_PASSES = 4;

    /** Where a body's cell is at a given world tick, or {@code null} when the body cannot be found. */
    public interface Ephemeris {
        GalacticCoord cellAt(int dimId, long worldTick);
    }

    /** How long a flight of {@code distanceBlocks} takes, in ticks. */
    public interface Flight {
        long ticksFor(double distanceBlocks);
    }

    private TargetPrediction() {
    }

    /**
     * The cell to aim at for the body in dimension {@code dimId}, departing from {@code origin} at
     * world tick {@code now}. Returns {@code null} when the body cannot be located at all — the caller
     * must treat that as "this ship no longer knows where its target is", never as an aim of its own.
     *
     * <p>A {@code null} origin (a ship with no recorded position) cannot be priced, so the answer is
     * simply the body's present cell; such a ship is refused by the gate long before it gets to fly.</p>
     */
    public static GalacticCoord aimAt(int dimId, GalacticCoord origin, long now,
                                      Ephemeris ephemeris, Flight flight) {
        if (ephemeris == null) {
            return null;
        }
        GalacticCoord aim = ephemeris.cellAt(dimId, now);
        if (aim == null || origin == null || flight == null) {
            return aim;
        }
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            long ticks = flight.ticksFor(origin.distanceTo(aim));
            GalacticCoord next = ephemeris.cellAt(dimId, now + ticks);
            if (next == null) {
                return aim; // the body went out of view mid-refinement: keep the best aim so far
            }
            if (next.sameCell(aim)) {
                return next; // settled: another pass would price the same flight and re-derive this
            }
            aim = next;
        }
        return aim;
    }
}
