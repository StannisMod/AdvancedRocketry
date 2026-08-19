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
     * Mean distance between neighbouring star seats, in light years — and the lattice is now built to
     * PRODUCE it rather than to use it as a cube edge.
     *
     * <p>4.23 is the observed figure for a solar neighbourhood, and it is the quantity this layer
     * actually means; the cube edge is machinery underneath it. Until 2026-08-19 this number was
     * consumed directly as {@link #DEFAULT_SPACING_CELLS}, i.e. as the EDGE, which is a different
     * quantity: a cube of edge {@code e} occupied with probability {@code p} puts its neighbours
     * {@code e / p^(1/3)} apart, so the field stood <b>6.0 ly</b> apart at the shipped occupancy while
     * this constant said 4.23. Measured on the shipped generator before the fix: 4913 territories
     * around the origin seated 1574 systems, occupancy 0.320, mean separation 6.18 ly — 42 % above
     * what the name promised, and the javadoc attributed the excess to the lattice being stratified
     * when the dominant term was the occupancy.</p>
     *
     * <p>See {@link #DEFAULT_STAR_OCCUPANCY} for the knob that closes the gap, and
     * {@link #DEFAULT_SPACING_CELLS} for the edge that now follows from both.</p>
     */
    public static final double MEAN_STAR_SEPARATION_LY = 4.23d;

    /**
     * What fraction of star territories hold a system at a galaxy's densest point — the lattice's fill,
     * and a balance knob rather than an observation.
     *
     * <p>It lives here beside the separation because the two together decide the edge, and a knob that
     * silently changes a measured quantity belongs next to the quantity it changes. A pack may still
     * override the occupancy through {@code <galaxyGen density="...">}; what it cannot do is move the
     * separation without saying so, because the separation is what the edge is derived from.</p>
     */
    public static final double DEFAULT_STAR_OCCUPANCY = 0.35d;

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
     * Default edge of the cube that holds at most one system, in cells — <b>machinery, derived from the
     * two quantities that mean something</b>: the separation the field should show and the fraction of
     * territories that hold anything.
     *
     * <p>{@code edge = separation × occupancy^(1/3)}, the inverse of the relation in
     * {@link #MEAN_STAR_SEPARATION_LY}: a sparser lattice needs a smaller cube to put its neighbours the
     * same distance apart. At the shipped 4.23 ly and 0.35 that is 2.98 ly of edge, down from the 4.23
     * this used to take verbatim — a finer partition, and the field lands where the constant says.</p>
     *
     * <p>The clear space a seat needs is unaffected and remains far below the new edge:
     * {@link #SEPARATION_FLOOR_AU} is 10 000 AU = 0.158 ly, i.e. about 5 % of this edge rather than the
     * 3.7 % it was. A balance knob, overridable from the generator's configuration, never a contract.</p>
     */
    public static final int DEFAULT_SPACING_CELLS = (int) Math.min(Integer.MAX_VALUE,
            Math.max(1L, Math.round(MEAN_STAR_SEPARATION_LY * Math.cbrt(DEFAULT_STAR_OCCUPANCY)
                    * AstronomicalBodyHelper.BLOCKS_PER_LIGHT_YEAR / (double) GalacticCoord.CELL)));

    /**
     * The mean separation a lattice of {@code edgeCells} at occupancy {@code occupancy} actually
     * produces, in light years — the relation stated once, so a caller can ask instead of re-deriving.
     */
    public static double meanSeparationLy(long edgeCells, double occupancy) {
        double edgeLy = lightYearsForCells(edgeCells);
        double p = Math.min(1d, Math.max(1e-9d, occupancy));
        return edgeLy / Math.cbrt(p);
    }

    // ─── The galaxy lattice ────────────────────────────────────────────────────
    // One level up, and the same scheme: a cube that holds at most one galaxy, and a galaxy seated
    // inside it. What is stated here is a REFERENCE SIZE and a RATIO; the separation follows from
    // them, and an individual galaxy's radius is drawn per type.
    //
    // The reference is a REAL galaxy, and the separation is the real one, so the whole layer is at
    // its physical scale. It used to be about a thirtieth of that, to keep a galaxy cube inside one
    // long of BLOCKS; nothing stores a position that way — every position in this layer is a sector
    // triple plus an in-cell offset — so the compression was paying for a representation nobody
    // built. What the sector triple actually gives at this scale is measured in GalaxyFieldTest.

    /**
     * The size a galaxy is quoted against, in light years — a mid-sized spiral, i.e. the Milky Way,
     * holding of the order of 10<sup>11</sup> systems at {@link #MEAN_STAR_SEPARATION_LY}.
     *
     * <p>It is not itself a bound on anything: it anchors {@link #MEAN_GALAXY_SEPARATION_LY} and it
     * is the size {@code GalaxyGenConfig}'s type table is written against. Those bands are stated as
     * ABSOLUTE light years, so they can be checked against a real catalogue rather than read as
     * ratios nobody can verify — which means that <b>moving this number does not move them, and they
     * must be RE-DERIVED from real radii rather than scaled.</b> Scaling the old table by the same
     * factor produced dwarf galaxies larger than real spirals.</p>
     *
     * <p>The compressed value it replaces was chosen so that a galaxy CUBE fit inside one
     * {@code long} of blocks, because a void position was believed to be a block offset from its
     * cell's origin. It is not: it is a sector triple plus an in-cell offset, and the sector space
     * carries this scale with six orders of headroom. The one place a whole separation is still
     * expressed as a block {@code long} is a {@link zmaster587.advancedRocketry.space.BlockDelta},
     * which is now able to say when it could not hold one.</p>
     */
    public static final double REFERENCE_GALAXY_RADIUS_LY = 50_000d;

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
     *
     * <p>It is set at the smallest DISC GIANT a real catalogue holds, which is what makes the
     * qualifying set "the spirals and the ellipticals" and excludes both dwarf classes. It is a
     * separate number from any type's band on purpose: the two are not the same statement, and the
     * day a pack widens the spiral band downwards this floor should keep its meaning rather than
     * follow it.</p>
     */
    public static final double MIN_AUTHORED_GALAXY_RADIUS_LY = 15_000d;

    // ─── A galaxy's retinue ────────────────────────────────────────────────────
    // The lattice holds at most one galaxy per cube and the cube is 25 diameters across, so on the
    // lattice alone the nearest galaxy is always 25 diameters away. That is the distance to the nearest
    // equal GIANT — Milky Way to Andromeda — and it was standing in for the distance to the nearest
    // galaxy of any kind. Real giants keep company far closer: the Large Magellanic Cloud is 1.6
    // diameters out, the Sagittarius dwarf is inside the halo.
    //
    // So a galaxy draws SATELLITES as children inside its own cube, exactly as a system draws moons
    // inside its primary's cell. Nothing about the representation moves: the cube keeps its size, the
    // sector space is untouched, and a satellite is a Galaxy value produced from (seed, cell) like any
    // other.

    /**
     * How far a satellite is seated from its primary, in the primary's DIAMETERS. The band real
     * companions occupy: the LMC sits at about 1.6, and the more distant members of a group run to a
     * few.
     *
     * <p>Its floor is what keeps a satellite OUTSIDE its primary — a satellite is at least one whole
     * diameter out, so even the largest one clears the primary's edge by a comfortable margin, and two
     * galaxies never overlap. That is not cosmetic: overlapping spheres would make "which galaxy is
     * this point in" a question with two answers, and the whole layer is built on it having one.</p>
     */
    public static final double MIN_SATELLITE_DISTANCE_IN_DIAMETERS = 1d;
    public static final double MAX_SATELLITE_DISTANCE_IN_DIAMETERS = 3d;

    /**
     * How large a satellite may be, as a fraction of its primary's radius. A satellite is drawn from
     * the galaxy types whose whole band fits under this, so "smaller than what it orbits" is a property
     * of the DRAW rather than a clamp applied to its result — the same shape as the authored-content
     * floor above.
     *
     * <p>Measured against the real pair it is named for: the LMC is 0.14 of the Milky Way's radius and
     * the SMC 0.07, and M32 is about 0.1 of Andromeda. The bound is loose enough to admit a dwarf
     * irregular around a large spiral and tight enough to exclude a second giant.</p>
     */
    public static final double MAX_SATELLITE_RADIUS_FRACTION = 0.3d;

    /**
     * How far a galaxy's whole RETINUE reaches from its centre, in light years — the primary's own
     * radius, its farthest satellite seat, and that satellite's own radius.
     *
     * <p>This, and not the primary's radius, is what a galaxy must be seated clear of its cube's faces
     * by. A margin sized to the primary alone would let a galaxy seated near a face keep satellites
     * OUTSIDE the cube, and a galaxy outside its own lattice cell is one the index attributes to a
     * neighbour — the single-answer invariant, broken by a number that was right before satellites
     * existed.</p>
     */
    public static double retinueReachLy(double primaryRadiusLy) {
        double radius = Math.max(0d, primaryRadiusLy);
        double farthestSeat = MAX_SATELLITE_DISTANCE_IN_DIAMETERS * 2d * radius;
        return farthestSeat + MAX_SATELLITE_RADIUS_FRACTION * radius;
    }

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
