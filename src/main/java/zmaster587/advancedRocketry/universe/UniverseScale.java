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
 * <p>One level up, the same pair says how big a galaxy is and how far apart galaxies stand — see the
 * galaxy-lattice section below. It is the same scheme applied twice, which is the point: a system is
 * seated in a cube, and so is the galaxy that holds it.</p>
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

    // ─── The galaxy lattice ────────────────────────────────────────────────────
    // One level up, and the same scheme: a cube that holds at most one galaxy, and a galaxy seated
    // inside it. What is stated here is a REFERENCE SIZE and a RATIO; the separation follows from
    // them, and an individual galaxy's radius is drawn per type around the reference.
    //
    // The reference is deliberately about a thirtieth of a real giant galaxy, and the separation is
    // scaled with it so the RATIO stays real. That is what buys the whole void a single primitive:
    // an offset inside one galaxy cube has to fit a long, and at real sizes it would not.

    /**
     * The size a galaxy is quoted against, in light years — a mid-sized spiral, holding of the order
     * of a million systems at {@link #MEAN_STAR_SEPARATION_LY}. Every type's radius band is drawn
     * around it.
     *
     * <p>It is about a thirtieth of a real giant galaxy, and that is the number the whole layer is
     * sized by: a position out in the void is an offset from its galaxy cell's origin, so the cell
     * edge has to fit a {@code long} of blocks. At real sizes it would not, and the void would need a
     * second, coarser representation of its own.</p>
     */
    public static final double REFERENCE_GALAXY_RADIUS_LY = 1_500d;

    /**
     * How far apart galaxies stand, in galaxy DIAMETERS. This is the real number — galaxies in a
     * group sit tens of diameters apart — and it is what the separation below is derived from, so
     * shrinking the reference size shrinks the whole layer coherently instead of leaving galaxies
     * marooned at a real separation.
     */
    public static final double GALAXY_SEPARATION_IN_DIAMETERS = 25d;

    /** Edge of the cube that holds at most one galaxy, in light years. */
    public static final double MEAN_GALAXY_SEPARATION_LY =
            GALAXY_SEPARATION_IN_DIAMETERS * 2d * REFERENCE_GALAXY_RADIUS_LY;

    /**
     * The same edge in cells — the default {@code galaxySpacing}. A {@code long}, not an {@code int}:
     * the galaxy lattice is five orders coarser than the star lattice and does not fit one.
     */
    public static final long DEFAULT_GALAXY_SPACING_CELLS =
            cellsForLightYears(MEAN_GALAXY_SEPARATION_LY);

    /**
     * The radius a galaxy holding AUTHORED content is guaranteed to have at least, in light years. A
     * galaxy's size is hash-drawn, so without a floor a pack that places content a few hundred light
     * years out would work on one seed and put that content outside its own galaxy on the next. The
     * floor is expressed as a constraint on which TYPES such a galaxy may be drawn from, never as a
     * clamp applied afterwards.
     */
    public static final double MIN_AUTHORED_GALAXY_RADIUS_LY = 900d;

    /**
     * Where the universe ORIGIN sits inside the home galaxy, as a fraction of its radius — and it is
     * emphatically not the centre.
     *
     * <p>The home galaxy is seated AROUND the origin rather than ON it, because the origin is where
     * authored content lives and the centre of a galaxy is its nucleus: the densest, most violent
     * place in it, and the last address a shipped solar system should have. Sol sits at about half
     * the Milky Way's disc radius; this puts the origin in the same neighbourhood, out in the disc.</p>
     *
     * <p>The offset lies IN the galaxy's plane, so the origin is disc material and not halo.</p>
     */
    public static final double HOME_GALAXY_ORIGIN_FRACTION = 0.55d;

    /**
     * How far from the DECLARATION ORIGIN authored content is guaranteed to stay inside its galaxy,
     * in light years. Derived, not chosen: it is what is left of the smallest galaxy that may hold
     * authored content once the origin has been moved off its centre.
     *
     * <p>Beyond it a position is valid on some seeds and intergalactic on others, which is a thing an
     * author must be TOLD rather than left to discover — hence a loud error and never a clamp.</p>
     */
    public static final double GUARANTEED_AUTHORED_REACH_LY =
            (1d - HOME_GALAXY_ORIGIN_FRACTION) * MIN_AUTHORED_GALAXY_RADIUS_LY;

    /**
     * The smallest lattice cell a system can be more than a lone star in, in cells.
     *
     * <p>Derived from the rest: a cell must leave room for a body at one orbit unit after the seat
     * margin and the neighbourhood margin are taken out. It bounds how finely a star cluster may
     * refine the lattice — a cluster cannot conjure room that its coarse cell never had, and a
     * spacing too tight to be refined is a degenerate galaxy rather than an error.</p>
     */
    public static final long MIN_LATTICE_EDGE_CELLS = 9L;

    private UniverseScale() {
    }

    /** How many cells a length in light years spans. Rounded up: a reach must not come out short. */
    public static long cellsForLightYears(double lightYears) {
        double blocks = Math.max(0d, lightYears) * AstronomicalBodyHelper.BLOCKS_PER_LIGHT_YEAR;
        return (long) Math.ceil(blocks / (double) GalacticCoord.CELL);
    }

    /** A SIGNED length in light years as a whole number of cells, rounded to the nearest. */
    public static long cellsAt(double lightYears) {
        return Math.round(lightYears * AstronomicalBodyHelper.BLOCKS_PER_LIGHT_YEAR
                / (double) GalacticCoord.CELL);
    }

    /** The length in light years that {@code cells} cells span. */
    public static double lightYearsForCells(double cells) {
        return cells * (double) GalacticCoord.CELL
                / (double) AstronomicalBodyHelper.BLOCKS_PER_LIGHT_YEAR;
    }

    /**
     * A speed quoted in km/s as light years per TICK — the form an angular rate is evaluated in.
     *
     * <p>Galactic velocities are stated the way astronomy states them and converted once, here,
     * rather than being pre-divided into a per-tick literal that no longer says what it measures.</p>
     */
    public static double lightYearsPerTick(double kilometresPerSecond) {
        double metresPerYear = kilometresPerSecond * 1_000d * AstronomicalBodyHelper.SECONDS_PER_YEAR;
        return metresPerYear / AstronomicalBodyHelper.METRES_PER_LIGHT_YEAR
                / AstronomicalBodyHelper.TICKS_PER_YEAR;
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
