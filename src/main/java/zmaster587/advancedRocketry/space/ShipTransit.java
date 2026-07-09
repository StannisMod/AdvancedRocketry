package zmaster587.advancedRocketry.space;

/**
 * The automatic hyperspace flight of a ship from its origin toward a fixed target
 * {@link GalacticCoord}, advanced one server tick at a time. Immutable: {@link #advance(long)}
 * returns the next state, so a caller stores the returned value each tick.
 *
 * <p>The direction is recomputed every tick from the (small, near-exact) delta between the current
 * position and the target, cast to {@code double}; the step itself is applied in exact fixed-point
 * via {@link GalacticCoord#plusLocal(long, long, long)}, so the flight never accumulates drift and
 * always converges on the target. When the remaining distance is within one tick's reach the
 * position snaps exactly onto the target and the transit is {@linkplain #arrived() arrived}.</p>
 *
 * <p>Speed / reach / power are ship stats supplied by the caller (per-tick block speed); this type
 * only integrates the motion.</p>
 */
public final class ShipTransit {

    private final GalacticCoord position;
    private final GalacticCoord target;

    public ShipTransit(GalacticCoord position, GalacticCoord target) {
        this.position = position;
        this.target = target;
    }

    public GalacticCoord position() {
        return position;
    }

    public GalacticCoord target() {
        return target;
    }

    /** {@code true} once the ship has reached its target (position equals target). */
    public boolean arrived() {
        return position.equals(target);
    }

    /** Remaining distance to the target, in blocks. */
    public double remainingDistance() {
        return position.distanceTo(target);
    }

    /**
     * Advance one tick at {@code speedBlocksPerTick}. Moves the position toward the target by up to
     * that many blocks; if the target is within reach (or already reached, or the speed is
     * non-positive) the result snaps exactly onto the target. Returns the next transit state.
     */
    public ShipTransit advance(long speedBlocksPerTick) {
        if (arrived()) {
            return this;
        }
        double dx = (double) (target.sectorX() - position.sectorX()) * GalacticCoord.CELL
                + (target.localX() - position.localX());
        double dy = (double) (target.sectorY() - position.sectorY()) * GalacticCoord.CELL
                + (target.localY() - position.localY());
        double dz = (double) (target.sectorZ() - position.sectorZ()) * GalacticCoord.CELL
                + (target.localZ() - position.localZ());
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (speedBlocksPerTick <= 0 || dist <= speedBlocksPerTick) {
            return new ShipTransit(target, target); // snap onto the target
        }

        double scale = speedBlocksPerTick / dist;
        long mx = Math.round(dx * scale);
        long my = Math.round(dy * scale);
        long mz = Math.round(dz * scale);
        return new ShipTransit(position.plusLocal(mx, my, mz), target);
    }

    @Override
    public String toString() {
        return "ShipTransit[position=" + position + ", target=" + target
                + ", arrived=" + arrived() + "]";
    }
}
