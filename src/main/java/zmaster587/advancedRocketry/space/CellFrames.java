package zmaster587.advancedRocketry.space;

/**
 * Where a cell IS, at a stated moment (C15 ADDR-6/ADDR-7).
 *
 * <p>A cell is the neighbourhood OF a body and it rides with that body. Its NAME &mdash; the sector
 * triple &mdash; is eternal; where it is stays a function of time. This is the seam that answers the
 * second half: given a name and a tick, the absolute position of that cell's frame origin, which is
 * the position of its primary at that tick. A cell with no primary is VOID and its origin is the
 * static {@code sector * CELL}.</p>
 *
 * <p>Everything that measures a distance ACROSS cells goes through here. {@link GalacticCoord}'s own
 * distance is the static-frame reading and is exact only between two cells that do not move, which is
 * why it is spelled {@code staticFrameDistanceTo}.</p>
 */
public interface CellFrames {

    /**
     * The static reading: every cell sits at {@code sector * CELL} forever. This is what a void cell
     * really does (ADDR-7), and it is the honest answer for any caller with no registry — a pure unit
     * test, a fixture, a probe with no world.
     */
    CellFrames STATIC = new CellFrames() {
        @Override
        public AbsolutePos originAt(GalacticCoord name, long tick) {
            return AbsolutePos.ofCellName(name);
        }
    };

    /** The absolute position of the frame origin of the cell NAMED by {@code name}, at {@code tick}. */
    AbsolutePos originAt(GalacticCoord name, long tick);

    /**
     * The absolute position {@code coord} denotes at {@code tick}: its cell's frame origin plus its
     * offset inside that frame. The offset is carried unchanged — it is already a displacement from
     * the origin, which is exactly what {@code local} means once names stopped being places.
     */
    default AbsolutePos absoluteOf(GalacticCoord coord, long tick) {
        if (coord == null) {
            return AbsolutePos.ORIGIN;
        }
        return originAt(coord.cellCentre(), tick).plus(coord.localX(), coord.localY(), coord.localZ());
    }

    /** The vector FROM {@code from} TO {@code to} at {@code tick}, through both frames. */
    default BlockDelta deltaBetween(GalacticCoord from, GalacticCoord to, long tick) {
        return absoluteOf(to, tick).minus(absoluteOf(from, tick));
    }

    /** The true distance in blocks between two coordinates at {@code tick}, through both frames. */
    default double distanceBetween(GalacticCoord a, GalacticCoord b, long tick) {
        return absoluteOf(a, tick).distanceTo(absoluteOf(b, tick));
    }
}
