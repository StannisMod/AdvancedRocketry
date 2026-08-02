package zmaster587.advancedRocketry.space;

/**
 * The automatic hyperspace flight of a ship from its origin toward its target, advanced one server
 * tick at a time. Immutable: {@link #advance(long)} returns the next state, so a caller stores the
 * returned value each tick.
 *
 * <p><b>A flight is a scalar, not a moving point.</b> Both ends are cell NAMES plus an offset inside
 * those cells, and a cell rides the body it belongs to — so the straight line between two absolute
 * positions is not a fixed line at all, and integrating a position along it would be integrating over
 * a grid that does not exist (C15 ADDR-9). What is well-defined is how far the ship has got: the
 * distance is priced once, through both frames, at departure; the flight then counts blocks flown
 * against it. That is also exactly what ADDR-12 permits a mid-transit state to be — origin name,
 * target name, progress — and nothing else about the position needs to be true, because there is no
 * observer: the ship is parked in hyperspace for the whole trip.</p>
 *
 * <p>Speed / reach / power are ship stats supplied by the caller (per-tick block speed); this type
 * only integrates the motion.</p>
 */
public final class ShipTransit {

    private final GalacticCoord origin;
    private final GalacticCoord target;
    private final long distanceBlocks;
    private final long travelledBlocks;

    /** A flight about to begin: nothing travelled yet. */
    public ShipTransit(GalacticCoord origin, GalacticCoord target, long distanceBlocks) {
        this(origin, target, distanceBlocks, 0L);
    }

    public ShipTransit(GalacticCoord origin, GalacticCoord target, long distanceBlocks,
                       long travelledBlocks) {
        this.origin = origin;
        this.target = target;
        this.distanceBlocks = Math.max(0L, distanceBlocks);
        this.travelledBlocks = Math.max(0L, Math.min(travelledBlocks, this.distanceBlocks));
    }

    /** Where the flight started — a cell name plus an in-cell offset. */
    public GalacticCoord origin() {
        return origin;
    }

    /** Where the flight ends — a cell name plus an in-cell offset. Durable: it does not move. */
    public GalacticCoord target() {
        return target;
    }

    /** The whole flight, in blocks, as priced at departure. */
    public long distanceBlocks() {
        return distanceBlocks;
    }

    /** Blocks flown so far. */
    public long travelledBlocks() {
        return travelledBlocks;
    }

    /** How far along the flight is, in {@code [0,1]}. A zero-length flight is complete. */
    public double progress() {
        return distanceBlocks <= 0L ? 1.0 : (double) travelledBlocks / (double) distanceBlocks;
    }

    /** {@code true} once the ship has flown the whole priced distance. */
    public boolean arrived() {
        return travelledBlocks >= distanceBlocks;
    }

    /** Remaining distance to the target, in blocks. */
    public double remainingDistance() {
        return Math.max(0L, distanceBlocks - travelledBlocks);
    }

    /**
     * Advance one tick at {@code speedBlocksPerTick}. Adds that many blocks to the distance flown; if
     * the remainder is within reach (or already reached, or the speed is non-positive) the flight
     * completes. Returns the next transit state.
     */
    public ShipTransit advance(long speedBlocksPerTick) {
        if (arrived()) {
            return this;
        }
        if (speedBlocksPerTick <= 0L
                || distanceBlocks - travelledBlocks <= speedBlocksPerTick) {
            return new ShipTransit(origin, target, distanceBlocks, distanceBlocks);
        }
        return new ShipTransit(origin, target, distanceBlocks, travelledBlocks + speedBlocksPerTick);
    }

    @Override
    public String toString() {
        return "ShipTransit[origin=" + origin + ", target=" + target
                + ", travelled=" + travelledBlocks + "/" + distanceBlocks
                + ", arrived=" + arrived() + "]";
    }
}
