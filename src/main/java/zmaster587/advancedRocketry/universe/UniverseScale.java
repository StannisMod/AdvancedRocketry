package zmaster587.advancedRocketry.universe;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

/**
 * How big the universe layer's furniture is, in one place: how far apart stars stand, how much room a
 * system is allowed to occupy, and how those two turn into cells.
 *
 * <p>Every number here is stated as a PHYSICAL length and converted, never written as a cell count.
 * A cell count is a reading of {@link GalacticCoord#CELL}, and that constant may move; a light year
 * may not. Stating the physics and deriving the cells is what keeps the star field looking the same
 * after the cell edge is retuned.</p>
 *
 * <h3>The two lengths, and why they are separate</h3>
 * <ul>
 *   <li><b>Star separation</b> — the edge of the cube that holds at most one system. It decides how
 *       far a flight between two stars is, and nothing else.</li>
 *   <li><b>The separation floor</b> — the guaranteed clear space around a system. It decides how much
 *       room a system's bodies have and how close two unrelated systems may ever be seen to stand.</li>
 * </ul>
 *
 * <p>These used to be one number: a system's extent was <i>defined</i> as a fraction of the
 * interstellar step, which truncated systems at a few AU, filled half the gap to the next star with
 * one system's neighbourhood, and forced the orbit scale to shrink to compensate. Separating them is
 * what lets a system be as big as its outermost orbit while the sky still reads as a sky.</p>
 *
 * <p>A near-pair of lattice seats is NOT a binary — it is two unrelated systems with two names, two
 * frames and no gravitational relation between them. The floor is therefore set comfortably wider
 * than any binary the model describes, so multiplicity is something the generator states inside ONE
 * system rather than something the lattice fakes by accident.</p>
 */
public final class UniverseScale {

    /**
     * Mean distance between neighbouring star seats, in light years. Real stellar neighbourhoods run
     * 4&ndash;5 light years between neighbours; the lattice is stratified rather than Poisson, so the
     * mean neighbour distance it produces comes out somewhat above this edge.
     */
    public static final double MEAN_STAR_SEPARATION_LY = 4.23d;

    /**
     * The guaranteed clear space around a system, in AU: two stars never stand closer than this,
     * however the lattice falls. Four times the widest binary the star model describes, so a lattice
     * near-pair can never be mistaken for one.
     *
     * <p>It bounds SEATS. Each system's named bodies then stay inside half of it (see
     * {@link #MAX_NAMED_ORBIT_UNITS}), which is what makes two neighbourhoods unable to overlap.</p>
     */
    public static final double SEPARATION_FLOOR_AU = 10_000d;

    /**
     * How far a system's NAMED bodies may reach from their star, in orbital-distance units — half the
     * separation floor, which is exactly what makes two systems' neighbourhoods unable to overlap.
     *
     * <p>It is a bound, not a size. An ordinary system ends at its outermost orbit (a few tens of AU);
     * this is the wall a system that would grow past its own clear space runs into, and the rule when
     * it does is that the system loses BODIES, never scale.</p>
     *
     * <p>Diffuse, nameless matter — a comet cloud — is not bound by it and may reach past a
     * neighbour's, exactly as real ones nearly touch: attribution reads names, not matter.</p>
     */
    public static final int MAX_NAMED_ORBIT_UNITS =
            (int) Math.min(Integer.MAX_VALUE,
                    Math.round(SEPARATION_FLOOR_AU / 2d * AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU));

    /** The same reach, in cells: the margin a system's seat keeps clear of its cube's faces. */
    public static final long SEAT_MARGIN_CELLS = cellsForOrbitUnits(MAX_NAMED_ORBIT_UNITS);

    /**
     * Default edge of the cube that holds at most one system, in cells. Derived from
     * {@link #MEAN_STAR_SEPARATION_LY}; a balance knob, overridable from the universe generator's
     * configuration, and never a contract.
     */
    public static final int DEFAULT_SPACING_CELLS = (int) Math.min(Integer.MAX_VALUE,
            Math.max(1L, Math.round(MEAN_STAR_SEPARATION_LY
                    * AstronomicalBodyHelper.BLOCKS_PER_LIGHT_YEAR / (double) GalacticCoord.CELL)));

    private UniverseScale() {
    }

    /** How many cells an orbital distance spans. Rounded up: a reach must not come out short. */
    public static long cellsForOrbitUnits(double orbitUnits) {
        double blocks = Math.max(0d, orbitUnits) * AstronomicalBodyHelper.BLOCKS_PER_ORBIT_UNIT;
        return (long) Math.ceil(blocks / (double) GalacticCoord.CELL);
    }

    /** The largest orbital distance that fits inside {@code cells} cells of a system's star. */
    public static double orbitUnitsForCells(long cells) {
        return Math.max(0d, cells) * (double) GalacticCoord.CELL
                / AstronomicalBodyHelper.BLOCKS_PER_ORBIT_UNIT;
    }

    /**
     * The clear margin a system of edge {@code spacingCells} actually gets: the declared one, or as
     * much of it as a cube that small can give.
     *
     * <p>A cube smaller than twice the floor cannot honour the floor — that is a degenerate galaxy,
     * not an error, and it is what a test or a pack asking for a compact universe gets. What may
     * never happen is a margin so large that no seat is left, so it stops one short of half the cube.</p>
     */
    public static long seatMarginCells(long spacingCells) {
        long half = Math.max(0L, (spacingCells - 1L) / 2L);
        return Math.min(SEAT_MARGIN_CELLS, half);
    }
}
