package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.BlockDelta;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

/**
 * A deterministic, addon-default {@link IGalaxyGenerator} producing a CLUSTERED procedural galaxy
 * (universe-model.md &sect;3): dense galaxies separated by inter-galaxy void, so a bounded scan/reach range is
 * a natural horizon.
 *
 * <p>Every answer is a pure function of {@code (seed, cell)} — no state, no RNG — so a scan and a later jump
 * agree and a re-materialised cell regenerates identically. The scheme, all O(1) per query:</p>
 * <ol>
 *   <li>{@link GalaxyField} seats the GALAXIES — one per {@link GalaxyGenConfig#galaxySpacing}-cube, each
 *       with a centre, a type, a radius, an orientation and a density profile;</li>
 *   <li>partition space into {@link GalaxyGenConfig#minSpacing}-cube <i>super-cells</i> — at most one system
 *       each (the minimum-spacing guarantee);</li>
 *   <li>a super-cell hosts a system with probability {@link GalaxyGenConfig#density} <i>scaled by the
 *       owning galaxy's profile at that point</i>, seated at a hash-chosen cell within the super-cell.
 *       Outside every galaxy the profile is zero, so the intergalactic void is what the profile leaves
 *       empty rather than a second rule.</li>
 * </ol>
 *
 * <p>The galaxy tier replaces an independent per-blob Bernoulli mask. Drawn per cell above the
 * site-percolation threshold, that mask produced one unbounded sponge rather than galaxies: no centre,
 * no radius, no orientation, and no answer to which galaxy a point was in.</p>
 *
 * <p>A procedural system is one or more stars (type and size sampled by weight from the seed) with
 * <b>synthetic negative ids</b> — never in the catalogue and never a dimension, so an id cannot collide
 * with a real star id ({@code 0..N}) or a dim id. About half of systems hold a companion, and a
 * companion is a star in its own right: it has its own id, its own orbit about the primary, and its own
 * cell, so a world can be bound to it and every world here is lit by all of them.</p>
 */
public final class ClusteredGalaxyGenerator implements IGalaxyGenerator {

    // Distinct salts so the independent hash draws (occupancy, per-axis offset, star type/size/id) never
    // correlate with each other.
    // 0x1 was SALT_BLOB, the galaxy-vs-void blob mask. Retired: which galaxy a super-cell is in, and
    // how dense that galaxy is there, is now GalaxyField's answer. The number stays burned so a future
    // draw cannot silently inherit an old galaxy's stream.
    private static final long SALT_OCC = 0x2L;
    private static final long SALT_OX = 0x3L;
    private static final long SALT_OY = 0x4L;
    private static final long SALT_OZ = 0x5L;
    private static final long SALT_TYPE = 0x6L;
    private static final long SALT_SIZE = 0x7L;
    private static final long SALT_ID = 0x8L;
    private static final long SALT_MULTIPLICITY = 0x9L;
    private static final long SALT_COMPANION_COUNT = 0xAL;
    private static final long SALT_COMPANION_TYPE = 0xBL;
    private static final long SALT_COMPANION_SIZE = 0xCL;
    private static final long SALT_COMPANION_SEP = 0xDL;
    private static final long SALT_COMPANION_ANG = 0xEL;

    private static final long SYNTHETIC_ID_RANGE = 2_000_000_000L; // ids in [-2_000_000_000, -1]

    // ─── Multiplicity ──────────────────────────────────────────────────────────
    // Roughly half of real stars are not alone, and a system that can only ever be one star is a
    // model that cannot express the commonest thing in the sky. Every number here is a balance knob;
    // what is NOT a knob is that multiplicity belongs inside ONE system — a near-pair of lattice
    // seats would be two unrelated systems with two names, two frames and no gravitational relation.

    /** Fraction of systems that hold more than one star. */
    private static final double MULTIPLE_FRACTION = 0.45d;
    /** How many companions a multiple system holds, by falling probability: 1, then 2, then 3. */
    private static final double[] COMPANION_COUNT_WEIGHTS = {0.75d, 0.20d, 0.05d};
    /**
     * Id slots reserved per system, so a primary and its companions can never collide with each
     * other however the hash falls. A system's stars take consecutive ids inside its own slot.
     */
    private static final int ID_SLOTS_PER_SYSTEM = 1 + COMPANION_COUNT_WEIGHTS.length;

    /**
     * Separation band for a companion, in orbital-distance units — 0.01 AU to 2 000 AU, drawn
     * log-uniformly, which is roughly how real separations are distributed over that range.
     *
     * <p>The floor IS one cell's worth of orbit ({@link AstronomicalBodyHelper#MIN_ADDRESSABLE_ORBIT_UNITS}),
     * so a companion always gets a cell of its own to be addressed by — derived rather than written
     * down, because it was written down as {@code 1} and quietly stopped meaning "one cell" when the
     * cell grew, at which point every tightest-band companion landed in the primary's cell, lost the
     * seat race and was dropped. The ceiling is a quarter of the guaranteed clear space around a
     * system, which is what lets that clear space state "no two unrelated stars come this close"
     * without a binary ever being mistaken for one.</p>
     */
    private static final int COMPANION_MIN_SEPARATION =
            AstronomicalBodyHelper.MIN_ADDRESSABLE_ORBIT_UNITS;
    private static final int COMPANION_MAX_SEPARATION = 200_000;
    /**
     * A retinue cannot survive inside a companion's orbit, nor a companion inside the retinue's: a
     * body between roughly a third of the separation and three times it is on an unstable orbit. So a
     * separation drawn into the planets' band is pushed to whichever side of it is nearer, and the
     * system comes out either circumbinary or widely separated — never impossible.
     */
    private static final double STABILITY_FACTOR = 3d;

    // Procedural in-system content (bodiesFor). All tunable. Per amendment A#1a each body gets its OWN cell
    // at a sector offset from the anchor (snapped to that cell's centre); the neighbourhood radius is bounded
    // by the super-cell partition (minSpacing/2 - margin) so two systems' neighbourhoods never interleave.
    private static final long SALT_BODYCOUNT = 0x11L;
    private static final long SALT_BODYANG = 0x12L;
    // 0x13 was SALT_BODYRAD, the uniform cell-radius draw. Retired: a body's cell radius now FOLLOWS
    // its orbital distance (PlanetDerivation.orbitFraction), so the two layouts cannot disagree. The
    // number stays burned so a future draw cannot silently inherit an old galaxy's stream.
    private static final long SALT_BODYY = 0x14L;
    // 0x15 was SALT_BELT, the "roughly a third of systems end in a belt" roll. Retired: an outer belt is
    // now MANDATORY and an inner one is derived from a giant, so a belt is never a coin toss. The number
    // stays burned so a future draw cannot inherit an old galaxy's stream.
    private static final long SALT_MOONCOUNT = 0x16L;
    private static final long SALT_MOONANG = 0x17L;
    private static final long SALT_MOONRAD = 0x18L;

    // The UNBOUND draw: a second, independent roll on the same lattice cell, so a cube the star draw
    // passed over may still hold something. Its own salts, so the two rolls cannot correlate — a shared
    // stream would make "no star here" and "a rogue here" the same coin toss read twice.
    private static final long SALT_ROGUE_OCC = 0x19L;
    private static final long SALT_ROGUE_TYPE = 0x1AL;
    private static final long SALT_ROGUE_ID = 0x1BL;
    private static final long SALT_ROGUE_MOONCOUNT = 0x1CL;
    private static final long SALT_ROGUE_MOONANG = 0x1DL;
    private static final long SALT_ROGUE_MOONRAD = 0x1EL;

    // ─── The retinue: how many bodies a system has, and where they sit ─────────
    // Every number here is a balance knob. What is NOT a knob is the shape: a long tail, a mandatory
    // outer belt, and moons on the bodies big enough to hold them.

    /**
     * Body count is drawn from a shifted exponential: a median around five or six, and a thin tail that
     * occasionally produces a system of fifteen or more. A rich system is itself a find, which is what
     * makes exploring for one worth doing — a fixed ceiling of six made every system the same size.
     */
    private static final int MIN_PROC_PLANETS = 3;
    private static final double PLANET_COUNT_SCALE = 3.385d;
    /**
     * Hard ceiling on the retinue. Not a balance number: {@code bodiesFor} runs on EVERY registry query
     * — the render feed, the console's forecast, every proximity check — so the tail has to be bounded
     * by something other than luck.
     */
    private static final int MAX_PROC_PLANETS = 24;

    /** Moons per body, drawn as {@code floor(u^BIAS · (MAX+1))}: most bodies have none, giants have several. */
    private static final int MAX_MOONS_ROCKY = 2;
    private static final int MAX_MOONS_GIANT = 5;
    private static final double MOON_COUNT_BIAS = 1.9d;
    /** A moon's orbit about its parent, in the parent-relative units the moon ephemeris is written in. */
    private static final int MOON_MIN_ORBIT = 20;
    private static final int MOON_ORBIT_SPAN = 110;

    /** The outer belt sits this far beyond the outermost major body — the Kuiper analogue. */
    private static final double OUTER_BELT_FACTOR = 1.6d;
    /**
     * An inner belt sits at the resonance-cleared gap inside a giant. A belt is not a destroyed planet:
     * it is material that never accreted because a nearby giant pumped relative velocities past the
     * point where collisions stick — so a belt is DERIVED from a giant, and a system with no giant has
     * no inner belt.
     */
    private static final double INNER_BELT_RESONANCE = 1.8d;

    /** Deterministic angular step used when a body's first-choice cell is already occupied. */
    private static final double NUDGE_ANGLE = 2.399963229728653d; // the golden angle, in radians
    /** How many relocations a body gets before its system is declared full. */
    private static final int NUDGE_ATTEMPTS = 96;
    /** Neighbourhood margin (cells) kept clear of the seat's own clear space. */
    private static final int NEIGHBOURHOOD_MARGIN_CELLS = 2;
    /** Thin-disk half-thickness as a fraction of the orbit radius (bodies keep honest 3D Y). */
    private static final double PROC_DISK_FRACTION = 0.1d;

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("AdvancedRocketry|Universe");

    /**
     * Hard ceiling on what one region query returns. Not a balance number: a nucleus divides each
     * coarse cell fifteen thousand ways, so a box that looks small in super-cells can hold millions of
     * systems and an unbounded enumeration would hang the caller.
     */
    private static final int MAX_SYSTEMS_PER_REGION_QUERY = 20_000;

    private final GalaxyGenConfig config;
    private final IBodyDerivation derivation;
    private final IUniverseLaws laws;
    private final GalaxyField galaxies;
    private final ClusterField clusters;
    private final NebulaField nebulae;
    private final long totalStarWeight;
    private final List<GalaxyGenConfig.RogueType> rogueTypes;
    private final long totalRogueWeight;

    /**
     * The stock generator: version 1's body derivation. Kept so every existing call site and test
     * reads unchanged; a schema that means something else says so with the constructor below.
     */
    public ClusteredGalaxyGenerator(GalaxyGenConfig config) {
        this(config, BodyDerivationV0.INSTANCE, UniverseLawsV0.INSTANCE);
    }

    /** The same field with a stated derivation, measuring by version 1's laws. */
    public ClusteredGalaxyGenerator(GalaxyGenConfig config, IBodyDerivation derivation) {
        this(config, derivation, UniverseLawsV0.INSTANCE);
    }

    /**
      * The full form: a field that derives its bodies by {@code derivation} and measures by
      * {@code laws} — the two halves a later schema version differs in.
      */
    public ClusteredGalaxyGenerator(GalaxyGenConfig config, IBodyDerivation derivation,
                                    IUniverseLaws laws) {
        this.derivation = (derivation == null) ? BodyDerivationV0.INSTANCE : derivation;
        this.laws = (laws == null) ? UniverseLawsV0.INSTANCE : laws;
        this.config = (config == null) ? GalaxyGenConfig.defaults() : config;
        this.galaxies = new GalaxyField(this.config, this.laws);
        this.clusters = new ClusterField(this.config, this.galaxies, this.laws);
        this.nebulae = new NebulaField(this.config, this.clusters, this.laws);
        long w = 0L; // accumulate in long so a few near-Integer.MAX weights cannot overflow the sum
        for (GalaxyGenConfig.StarType t : this.config.starTypes) {
            w += t.weight;
        }
        this.totalStarWeight = Math.max(1L, w);
        this.rogueTypes = this.config.rogue.types;
        long rw = 0L;
        for (GalaxyGenConfig.RogueType t : this.rogueTypes) {
            rw += t.weight;
        }
        this.totalRogueWeight = Math.max(1L, rw);
    }

    @Override
    public IBodyDerivation derivation() {
        return derivation;
    }

    @Override
    public IUniverseLaws laws() {
        return laws;
    }

    @Override
    public java.util.Optional<GalaxyGenConfig> tuning() {
        return java.util.Optional.of(config);
    }

    public GalaxyGenConfig config() {
        return config;
    }

    /** The galaxies this generator places its systems in — the tier above the star lattice. */
    public GalaxyField galaxies() {
        return galaxies;
    }

    /** The star clusters that refine the lattice — the tier below it. */
    public ClusterField clusters() {
        return clusters;
    }

    /**
     * The clouds those clusters are wrapped in. Diffuse matter, so it names nothing and places
     * nothing — it is what makes a cluster visible from outside, and the seam any later consequence
     * of flying into one would be written against.
     */
    public NebulaField nebulae() {
        return nebulae;
    }

    @Override
    public Optional<PlanetarySystem> systemAt(long seed, GalacticCoord coord) {
        Optional<Generated> g = systemForLattice(seed,
                latticeAt(seed, coord.sectorX(), coord.sectorY(), coord.sectorZ()));
        if (g.isPresent() && g.get().cell.sameCell(coord)) {
            return Optional.of(g.get().system);
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cost is O(super-cell volume of the box), times {@code k³} for any part of it inside a star
     * cluster. Callers MUST pass a bounded region — a telescope scan is range-limited by config — not a
     * galactic-scale box.</p>
     *
     * <p><b>The result is capped</b>, and a cap that fires is LOGGED. A nucleus subdivides each coarse
     * cell fifteen thousand ways, so a box that looks small in super-cells can hold millions of
     * systems; silently returning the first few would read as "that is all there is", which is the one
     * outcome worse than a slow scan.</p>
     */
    @Override
    public Map<GalacticCoord, PlanetarySystem> systemsInRegion(long seed, GalacticCoord min, GalacticCoord max) {
        long s = config.minSpacing;
        long loX = Math.min(min.sectorX(), max.sectorX());
        long hiX = Math.max(min.sectorX(), max.sectorX());
        long loY = Math.min(min.sectorY(), max.sectorY());
        long hiY = Math.max(min.sectorY(), max.sectorY());
        long loZ = Math.min(min.sectorZ(), max.sectorZ());
        long hiZ = Math.max(min.sectorZ(), max.sectorZ());

        Map<GalacticCoord, PlanetarySystem> out = new HashMap<>();
        boolean capped = false;
        for (long supX = Math.floorDiv(loX, s); supX <= Math.floorDiv(hiX, s) && !capped; supX++) {
            for (long supY = Math.floorDiv(loY, s); supY <= Math.floorDiv(hiY, s) && !capped; supY++) {
                for (long supZ = Math.floorDiv(loZ, s); supZ <= Math.floorDiv(hiZ, s) && !capped; supZ++) {
                    LocalField local = localFieldAt(seed, supX, supY, supZ);
                    int k = local.subdivision;
                    // Only the sub-cells the query box actually reaches. A system seated in a
                    // sub-cell is placed INSIDE it, so this is exactly the same answer as walking all
                    // k³ and filtering — and it is the difference between a bounded query and a
                    // 10⁷-cell walk, because a galactic nucleus divides one coarse cell that finely.
                    long iLo = subIndex(offsetInCoarse(loX, supX, s), s, k);
                    long iHi = subIndex(offsetInCoarse(hiX, supX, s), s, k);
                    long jLo = subIndex(offsetInCoarse(loY, supY, s), s, k);
                    long jHi = subIndex(offsetInCoarse(hiY, supY, s), s, k);
                    long mLo = subIndex(offsetInCoarse(loZ, supZ, s), s, k);
                    long mHi = subIndex(offsetInCoarse(hiZ, supZ, s), s, k);
                    for (long i = iLo; i <= iHi && !capped; i++) {
                        for (long j = jLo; j <= jHi && !capped; j++) {
                            for (long m = mLo; m <= mHi && !capped; m++) {
                                Optional<Generated> g = systemForLattice(seed,
                                        Lattice.of(supX, supY, supZ, i, j, m, k, s, local.ownField));
                                if (!g.isPresent()) {
                                    continue;
                                }
                                GalacticCoord c = g.get().cell;
                                if (c.sectorX() >= loX && c.sectorX() <= hiX
                                        && c.sectorY() >= loY && c.sectorY() <= hiY
                                        && c.sectorZ() >= loZ && c.sectorZ() <= hiZ) {
                                    out.put(c, g.get().system);
                                    capped = out.size() >= MAX_SYSTEMS_PER_REGION_QUERY;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (capped) {
            LOGGER.warn("systemsInRegion stopped at " + MAX_SYSTEMS_PER_REGION_QUERY + " systems for the"
                    + " box " + min.cellKey() + " .. " + max.cellKey() + "; there are more. This region"
                    + " crosses a dense star cluster - narrow the query.");
        }
        return out;
    }

    @Override
    public List<SystemBody> bodiesFor(long seed, GalacticCoord systemCoord) {
        // Accept any member cell: resolve the owning anchor first (A#1a).
        Optional<GalacticCoord> anchorOpt = anchorAt(seed, systemCoord);
        if (!anchorOpt.isPresent()) {
            return Collections.emptyList();
        }
        GalacticCoord cell = anchorOpt.get();
        Optional<PlanetarySystem> sys = systemAt(seed, cell);
        if (!sys.isPresent()) {
            return Collections.emptyList();
        }
        int systemId = sys.get().systemId();
        if (!sys.get().star().isPresent()) {
            // A system whose primary is not a star: no companions, no zone, no orbits — the whole
            // second half of the retinue law is about distances FROM a star. What it can still have is
            // moons, so that is what it gets.
            return rogueBodiesFor(seed, cell, systemId, config.rogue.giantFraction);
        }
        StellarBody star = sys.get().star().get();
        List<SystemBody> bodies = new ArrayList<>();
        // The star sits at the anchor cell's centre.
        // A star does not move inside its own system: its frame IS the system's anchor.
        bodies.add(SystemBody.fixedAt(cell, SystemBodyKind.STAR, Constants.INVALID_PLANET, systemId));

        // A body sits where its ORBIT puts it — one law, one constant, the same one an authored system
        // uses. What the neighbourhood decides is not how far a body goes but how many bodies there is
        // room for: orbits are drawn inside a bracket that already fits, and a system that would run
        // past its own clear space loses BODIES rather than being squashed to fit.
        //
        // The room is the LOCAL lattice cell's, not the coarse one's. A system inside a star cluster
        // sits on a finer lattice, so it has less of it and keeps fewer named bodies — which is the
        // same rule as everywhere else, applied to the level it is defined on.
        Lattice lattice = latticeAt(seed, cell.sectorX(), cell.sectorY(), cell.sectorZ());
        long s = lattice.minEdge();
        double outerBound = maxNamedOrbitUnits(s);

        // AT MOST ONE REAL BODY PER CELL, moons excepted. The draw picks each body's angle and radius
        // independently, so two of them CAN land on the same cell — and two real bodies in one cell are
        // two destinations a player can neither tell apart nor choose between. Claiming cells as they
        // are used, and relocating a body that finds its first choice taken, is what keeps the
        // generator's own output out of that state; the audit that reports it would otherwise fire on
        // the generator itself, and the more bodies a system has the likelier that becomes.
        Set<String> taken = new HashSet<>();
        taken.add(cell.cellKey());

        // Every star of the system is a body in it. A companion that existed only on the StellarBody
        // would light the worlds here and appear in no sky, on no chart and at no address — which is
        // the shape of "expressible in storage, meaningless everywhere else" this whole seam removes.
        // It is seated from ITS OWN elements, never from a fresh draw: the star object and the body
        // that stands for it have to be the same statement, or one system holds a companion in two
        // places at once.
        for (StellarBody companion : star.getSubStars()) {
            double periodTicks = AstronomicalBodyHelper.TICKS_PER_DAY
                    * AstronomicalBodyHelper.getOrbitalPeriod(companion.getOrbitalDistance(),
                            star.getMass());
            Seat seat = claimSeat(cell, lattice, taken, companion.getOrbitalDistance(),
                    companion.getBaseTheta(), 0d, periodTicks);
            if (seat == null) {
                continue;
            }
            bodies.add(new SystemBody(seat.cell,
                    CellFrame.of(AbsolutePos.ofCellName(cell.cellCentre()), seat.law),
                    BodyEphemeris.STATIC, SystemBodyKind.STAR, Constants.INVALID_PLANET,
                    companion.getId(), companion.getOrbitalDistance())
                    .withRadius(AstronomicalBodyHelper.starRadiusEarths(companion)));
        }

        appendRetinue(bodies, seed, cell, star, systemId, lattice, taken, outerBound,
                retinueSize(seed, cell));
        return bodies;
    }

    /**
     * The bodies of a system anchored on a STARLESS world — the rogue itself, and whatever it kept.
     *
     * <p>It is a short list on purpose. There is no belt, because an unbound world carries no disc: a
     * belt is material that never accreted in a star's own gravity well, and this world left that well
     * behind. There is no orbit and no zone, so nothing here is placed by distance from anything.</p>
     *
     * <p><b>Moons it may keep, and few.</b> Whatever unbound a planet from its star pulled far harder
     * on the loosely-held satellites than on the tight ones, so a rogue arrives out here with the
     * inner few and nothing else — the same ceiling a rocky world has, applied whatever its bulk,
     * rather than the ceiling its mass would otherwise buy it.</p>
     */
    private List<SystemBody> rogueBodiesFor(long seed, GalacticCoord cell, int systemId,
                                                   double giantFraction) {
        List<SystemBody> bodies = new ArrayList<>();
        BodyProfile profile = derivation.deriveRogue(seed, cell, 0, giantFraction);
        // It does not move inside its own system: it IS the system, so its frame is the anchor's.
        bodies.add(SystemBody.fixedAt(cell, SystemBodyKind.ROGUE_PLANET, Constants.INVALID_PLANET,
                systemId).withRadius(profile.radiusEarths()));

        double u = CellHash.norm(CellHash.ofCell(seed, cell, SALT_ROGUE_MOONCOUNT));
        int moons = (int) (Math.pow(u, MOON_COUNT_BIAS) * (MAX_MOONS_ROCKY + 1));
        if (moons > MAX_MOONS_ROCKY) {
            moons = MAX_MOONS_ROCKY;
        }
        CellFrame frame = CellFrame.staticAt(cell);
        for (int j = 1; j <= moons; j++) {
            int moonOrbit = MOON_MIN_ORBIT + (int) (CellHash.norm(
                    CellHash.ofBody(seed, cell, j, SALT_ROGUE_MOONRAD)) * MOON_ORBIT_SPAN);
            double theta = CellHash.norm(CellHash.ofBody(seed, cell, j, SALT_ROGUE_MOONANG))
                    * 2d * Math.PI;
            double periodTicks = AstronomicalBodyHelper.TICKS_PER_DAY
                    * AstronomicalBodyHelper.getMoonOrbitalPeriod(moonOrbit,
                            (float) Math.max(0.05d, profile.massEarths()));
            BodyEphemeris law = BodyEphemeris.orbit(moonOrbit, theta, 0d, false, periodTicks,
                    SystemContent.MOON_UNIT_BLOCKS);
            // A moon of a rogue is starless too, so it is derived the same way its parent was, one
            // variant along — never through the star-lit law with a star that is not there.
            BodyProfile moonProfile = derivation.deriveRogue(seed, cell, j, giantFraction);
            bodies.add(new SystemBody(cell, frame, law, SystemBodyKind.MOON,
                    Constants.INVALID_PLANET, systemId, SystemBody.ORBIT_UNKNOWN)
                    .withRadius(moonProfile.radiusEarths()));
        }
        return bodies;
    }

    /**
     * Append a system's RETINUE — its worlds, their moons and its belts — to {@code bodies}.
     *
     * <p>Extracted so an AUTHORED system can have one too. The legacy random generator used to fill an
     * authored star's system at world creation from {@code new Random(System.currentTimeMillis())},
     * which meant two saves of one seed differed and every fix had to be made twice, in two models
     * that answered the same question differently. This is the one model, and an authored system now
     * reaches it through {@link #authoredRetinueFor} with the pack's own body count as the bound.</p>
     *
     * @param taken cells already claimed — an authored system passes the cells its authored worlds
     *              hold, so a derived body can never land on one
     * @param count how many major bodies to attempt; the drawn orbits still decide how many FIT
     */
    private void appendRetinue(List<SystemBody> bodies, long seed, GalacticCoord cell, StellarBody star,
                               int starId, Lattice lattice, Set<String> taken, double outerBound,
                               int count) {
        int outermostOrbit = 0;
        int innermostGiantOrbit = 0;
        for (int i = 0; i < count; i++) {
            // The ORBIT is drawn first and the cell follows from it, rather than the other way round:
            // a body's physics is derived from its orbit, so letting the placement pick the distance
            // would make every world's climate a function of the layout arithmetic.
            //
            // The orbit is drawn across the STAR'S OWN zone, and a body that lands outside the room
            // this system has is DROPPED. Narrowing the bracket instead would have kept the body and
            // moved it inward, which is the one thing this whole seam exists to prevent: a world's
            // distance is its star's business, and a system squeezed by its neighbours holds fewer
            // worlds rather than the same worlds at the wrong distances.
            int orbit = derivation.orbitalDistanceOf(seed, cell, i, count, star);
            if (orbit > outerBound) {
                continue; // outside this system's clear space — a bound of the layout, not a failure
            }
            if (!orbitIsStableAmong(star.getSubStars(), orbit)) {
                continue; // too near one of this system's other stars for any orbit to survive
            }
            Seat seat = seatBody(seed, cell, i, orbit, star, lattice, taken);
            if (seat == null) {
                continue; // this system's neighbourhood is full — a bound of the layout, not a failure
            }
            // Planet or giant is not a roll of its own: it falls out of the body's derived physics,
            // which is what makes the zoning (rock inside, giants past the snow line) emerge instead
            // of being authored. Kept here rather than at realization because the nav list, the sky
            // and the descent trigger all read the kind long before anyone lands.
            BodyProfile profile = derivation.derive(seed, cell, seat.cell, 0, star, false, orbit);
            // THE ORBIT LIVES IN THE FRAME, not in the body's own offset — the same shape an authored
            // system uses (SystemContent: a planet sits at its frame origin and the FRAME goes round
            // the star). Built with the convenience constructor, a procedural planet got
            // CellFrame.staticAt(...) and a FIXED offset, so it stood still relative to its star
            // forever while its own moons orbited it, and the identical system authored in XML moved.
            CellFrame bodyFrame = CellFrame.of(AbsolutePos.ofCellName(cell.cellCentre()), seat.law);
            // Procedural bodies have no realized dimension yet — a descent (Layer 2) realizes one.
            // The body carries its OWN size. Nothing downstream can recover it: a procedural world
            // has no dimension until a descent mints one, and the render feed reaches a client with
            // no registry to ask.
            bodies.add(new SystemBody(seat.cell, bodyFrame, BodyEphemeris.STATIC, profile.kind(),
                    Constants.INVALID_PLANET, starId, orbit)
                    .withRadius(profile.radiusEarths()));
            outermostOrbit = Math.max(outermostOrbit, orbit);
            if (profile.kind() == SystemBodyKind.GAS_GIANT
                    && (innermostGiantOrbit == 0 || orbit < innermostGiantOrbit)) {
                innermostGiantOrbit = orbit;
            }
            addMoons(bodies, seed, cell, seat.cell, bodyFrame, orbit, star, starId, profile);
        }

        // An inner belt is DERIVED from a giant and never rolled: it is material a giant's resonances
        // stopped from accreting, so it belongs in the gap inside one and a system with no giant has none.
        if (innermostGiantOrbit > 0) {
            addBelt(bodies, seed, cell, (int) (innermostGiantOrbit / INNER_BELT_RESONANCE), star, lattice,
                    starId, taken, count + 1);
        }
        // The outer belt is MANDATORY on every system — the Kuiper analogue, and the reason every system
        // is worth arriving in: it is a gravity-well-free mining site that needs no landing, so a ship
        // that drifts into any system at all has something to work.
        //
        // It is the one body allowed to sit past the drawn bracket, because it is defined as being
        // beyond the outermost world; what it may NOT pass is the system's own clear space, and there
        // it is bounded like everything else rather than being quietly dropped.
        double outerBelt = Math.max(outermostOrbit * OUTER_BELT_FACTOR,
                derivation.innerOrbit(star) * 2d);
        addBelt(bodies, seed, cell, (int) Math.min(outerBelt, outerBound), star, lattice, starId, taken,
                count + 2);
    }

    /**
     * The retinue an AUTHORED system gets: derived from {@code (seed, anchor)} like every other, and
     * bounded by the pack's own {@code numPlanets} rather than by the generator's draw.
     *
     * <p>What this preserves from the generator it replaces: a pack that asks for twelve worlds around
     * its star still gets twelve. What it changes, deliberately: the worlds are the same two saves
     * running from the same seed, because the clock is no longer an input.</p>
     *
     * @param takenCells the cells the system's AUTHORED bodies already occupy, so nothing derived
     *                   lands on one
     */
    public List<SystemBody> authoredRetinueFor(long seed, GalacticCoord anchor, StellarBody star,
                                               int starId, int count, Set<String> takenCells) {
        List<SystemBody> bodies = new ArrayList<>();
        if (star == null || anchor == null || count <= 0) {
            return bodies;
        }
        GalacticCoord cell = anchor.cellCentre();
        Lattice lattice = latticeAt(seed, cell.sectorX(), cell.sectorY(), cell.sectorZ());
        Set<String> taken = new HashSet<>();
        taken.add(cell.cellKey());
        if (takenCells != null) {
            taken.addAll(takenCells);
        }
        appendRetinue(bodies, seed, cell, star, starId, lattice, taken,
                maxNamedOrbitUnits(lattice.minEdge()), count);
        return bodies;
    }

    /**
     * How many major bodies a system has. A shifted exponential: most systems are ordinary, a few are
     * enormous, and the ceiling exists to bound the per-query cost rather than the fiction.
     */
    public static int retinueSize(long seed, GalacticCoord anchor) {
        double u = CellHash.norm(CellHash.ofCell(seed, anchor, SALT_BODYCOUNT));
        double tail = -Math.log(Math.max(1e-12d, 1d - u)) * PLANET_COUNT_SCALE;
        int n = MIN_PROC_PLANETS + (int) tail;
        return Math.max(1, Math.min(MAX_PROC_PLANETS, n));
    }

    /**
     * How far this system's NAMED bodies may reach from its star, in orbital-distance units: the
     * declared clear space around a seat, or as much of it as this spacing can actually give.
     */
    private double maxNamedOrbitUnits(long s) {
        long reachCells = Math.max(1L, laws.seatMarginCells(s) - NEIGHBOURHOOD_MARGIN_CELLS);
        return Math.min(UniverseScale.MAX_NAMED_ORBIT_UNITS,
                laws.orbitUnitsForCells(reachCells));
    }

    /**
     * Claim a free cell for a body orbiting at {@code orbit}, or {@code null} when the neighbourhood has
     * no room left.
     *
     * <p>The cell is READ OFF the body's own orbital law at the naming instant, not computed by a second
     * arithmetic beside it: the name a body carries and the frame its cell rides are then the same
     * statement evaluated once, and cannot drift apart when either is retuned. That is exactly how an
     * authored body is named, which is what makes one orbital distance mean one distance in both
     * families.</p>
     *
     * <p>If the first choice is already spoken for, the body is walked around its ring by the golden
     * angle — a relocation costs a body its ANGLE and never its distance, so no world's climate is
     * disturbed by the layout arithmetic and the orbital order survives. A body that still finds nothing
     * is dropped: a neighbourhood holds what it holds, and inventing a second occupant for a cell is the
     * one outcome that is worse than a smaller system.</p>
     */
    private static Seat seatBody(long seed, GalacticCoord anchor, int index, int orbit,
                                 StellarBody star, Lattice lattice, Set<String> taken) {
        double baseAngle = CellHash.norm(CellHash.ofBody(seed, anchor, index, SALT_BODYANG)) * 2d * Math.PI;
        // Out-of-plane displacement lives in the LAW as an inclination, so a body's height above the
        // disk is part of where it IS at every tick rather than a one-off nudge applied to its name.
        double sinPhi = (CellHash.norm(CellHash.ofBody(seed, anchor, index, SALT_BODYY)) - 0.5d)
                * PROC_DISK_FRACTION;
        double phiDegrees = Math.toDegrees(Math.asin(sinPhi));
        double periodTicks = AstronomicalBodyHelper.TICKS_PER_DAY
                * AstronomicalBodyHelper.getOrbitalPeriod(orbit, star.getMass());
        return claimSeat(anchor, lattice, taken, orbit, baseAngle, phiDegrees, periodTicks);
    }

    /** Walk the ring from {@code baseAngle} until a free cell turns up, or give up. */
    private static Seat claimSeat(GalacticCoord anchor, Lattice lattice, Set<String> taken, int orbit,
                                  double baseAngle, double phiDegrees, double periodTicks) {
        for (int attempt = 0; attempt < NUDGE_ATTEMPTS; attempt++) {
            BodyEphemeris law = BodyEphemeris.orbit(orbit, baseAngle + attempt * NUDGE_ANGLE,
                    phiDegrees, false, periodTicks, AstronomicalBodyHelper.BLOCKS_PER_ORBIT_UNIT);
            BlockDelta at0 = law.offsetAt(SystemContent.NAME_TICK);
            // The body's address is its OWN cell's centre (zone content sits near the cell centre),
            // box-clamped into the anchor's super-cell so member attribution stays exact at ANY
            // spacing — at tiny spacings a whole orbit can otherwise reach across the super-cell face.
            GalacticCoord addr = clampIntoLattice(
                    anchor.plusLocal(at0.dx(), at0.dy(), at0.dz()).cellCentre(), lattice);
            if (taken.add(addr.cellKey())) {
                return new Seat(addr, law);
            }
        }
        return null;
    }

    /** A body's claimed cell together with the orbital law that put it there — one statement, not two. */
    private static final class Seat {
        final GalacticCoord cell;
        final BodyEphemeris law;

        Seat(GalacticCoord cell, BodyEphemeris law) {
            this.cell = cell;
            this.law = law;
        }
    }

    /** Append an asteroid belt at {@code orbit}, if the neighbourhood still has a cell for one. */
    private static void addBelt(List<SystemBody> bodies, long seed, GalacticCoord anchor, int orbit,
                                StellarBody star, Lattice lattice, int starId, Set<String> taken,
                                int index) {
        int clamped = Math.max(1, orbit);
        Seat seat = seatBody(seed, anchor, index, clamped, star, lattice, taken);
        if (seat != null) {
            // A belt is centred on the star it rings, so as a whole it does not travel round it. Its
            // cell is a marker on the ring; the ring itself does not go anywhere.
            bodies.add(SystemBody.fixedAt(seat.cell, SystemBodyKind.ASTEROID_BELT,
                    Constants.INVALID_PLANET, starId, clamped));
        }
    }

    /**
     * Append this body's moons. They share their parent's CELL by construction — a planet and its moons
     * are one destination, which is the whole reason the one-real-body-per-cell invariant exempts them —
     * and each carries its own live offset inside it.
     *
     * <p>Their {@code orbitalDistance} is the PARENT's distance from the star, not their own distance
     * from the parent: that field is what a moon's climate is derived from, and what warms a moon is
     * where its planet is. How far the moon sits from the planet lives in its ephemeris, which is the
     * thing that actually positions it.</p>
     */
    private void addMoons(List<SystemBody> bodies, long seed, GalacticCoord anchor, GalacticCoord parent,
                          CellFrame parentFrame, int parentOrbit, StellarBody star, int starId,
                          BodyProfile parentProfile) {
        boolean giant = parentProfile.kind() == SystemBodyKind.GAS_GIANT;
        int max = giant ? MAX_MOONS_GIANT : MAX_MOONS_ROCKY;
        double u = CellHash.norm(CellHash.ofCell(seed, parent, SALT_MOONCOUNT));
        int moons = (int) (Math.pow(u, MOON_COUNT_BIAS) * (max + 1));
        if (moons > max) {
            moons = max;
        }
        // A moon's period comes from its parent's MASS. Passing gravity here is exact only at one
        // Earth radius and made a giant's moons crawl — Jupiter is 318 Earth masses but 2.53 g, a
        // factor of sqrt(318/2.53) = 11.2 in the period. A profile with no mass falls back to gravity,
        // which is the same number for the one-Earth-radius body an unstated bulk describes.
        double parentMass = parentProfile.massEarths() > 0d
                ? parentProfile.massEarths()
                : Math.max(0.05d, parentProfile.gravityPercent() / 100d);
        for (int j = 1; j <= moons; j++) {
            int moonOrbit = MOON_MIN_ORBIT + (int) (CellHash.norm(
                    CellHash.ofBody(seed, parent, j, SALT_MOONRAD)) * MOON_ORBIT_SPAN);
            double theta = CellHash.norm(CellHash.ofBody(seed, parent, j, SALT_MOONANG)) * 2d * Math.PI;
            double periodTicks = AstronomicalBodyHelper.TICKS_PER_DAY
                    * AstronomicalBodyHelper.getMoonOrbitalPeriod(moonOrbit, (float) parentMass);
            BodyEphemeris law = BodyEphemeris.orbit(moonOrbit, theta, 0d, false, periodTicks,
                    SystemContent.MOON_UNIT_BLOCKS);
            // A moon rides its PARENT's frame, so a planet and its moons travel as one destination.
            // It used to ride a static frame of its own, which pinned the whole family in place.
            // A moon's size comes from the SAME derivation a descent will realize it with, so the
            // moon a pilot sees from orbit is the moon he lands on.
            BodyProfile moonProfile = derivation.derive(seed, anchor, parent, j, star, true,
                    parentOrbit);
            bodies.add(new SystemBody(parent, parentFrame, law, SystemBodyKind.MOON,
                    Constants.INVALID_PLANET, starId, parentOrbit)
                    .withRadius(moonProfile.radiusEarths()));
        }
    }

    /**
     * The full derived profile of one of this generator's bodies — what realization materializes.
     *
     * <p>Answerable for a body nobody has visited, because it is the same pure derivation the kind above
     * came from. The body carries its own orbit, so this stays correct for a PINNED system whose layout
     * the live generator would no longer reproduce.</p>
     */
    public BodyProfile profileOf(long seed, GalacticCoord anchor, SystemBody body, StellarBody star,
                                 int variant) {
        if (star == null) {
            // Nothing lights this system, so nothing about the body follows from a distance: it is the
            // starless derivation or it is a body whose physics would be read off a star that is not
            // there. A moon of a rogue takes the same branch, which is right — it is starless too.
            return derivation.deriveRogue(seed, body.name(), variant, config.rogue.giantFraction);
        }
        return derivation.derive(seed, anchor.cellCentre(), body.name(), variant, star,
                body.kind() == SystemBodyKind.MOON, body.orbitalDistance());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cost is the number of CLUSTER cells the reach crosses, not its volume in cells — the clouds
     * are enumerated on the cluster lattice they are derived from. Outside a galaxy the answer is
     * empty by construction: clusters are seated inside galaxies, and a cloud is a cluster's own gas.</p>
     */
    @Override
    public List<Nebula> nebulaeAround(long seed, GalacticCoord cell, double radiusLy) {
        if (cell == null || !(radiusLy > 0d)) {
            return Collections.emptyList();
        }
        GalacticCoord c = cell.cellCentre();
        // The galaxy the observer is INSIDE, so a cell in a satellite sees the satellite's clouds.
        Optional<Galaxy> galaxy = galaxies.galaxyContainingSector(seed, c.sectorX(), c.sectorY(),
                c.sectorZ());
        if (!galaxy.isPresent()) {
            return Collections.emptyList();
        }
        long s = config.minSpacing;
        long reachSuper = Math.max(1L, laws.cellsForLightYears(radiusLy) / s);
        long supX = Math.floorDiv(c.sectorX(), s);
        long supY = Math.floorDiv(c.sectorY(), s);
        long supZ = Math.floorDiv(c.sectorZ(), s);
        return nebulae.nebulaeInRegion(seed, galaxy.get(), supX - reachSuper, supY - reachSuper,
                supZ - reachSuper, supX + reachSuper, supY + reachSuper, supZ + reachSuper);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The galaxy is resolved at the OBSERVER's end. Over the ranges a look spans — a survey's
     * horizon is ~100 ly against a galaxy thousands across — both ends share one galaxy; a sight line
     * that genuinely left one would be looking at another galaxy, which is a different feature.</p>
     */
    @Override
    public double columnDensityBetween(long seed, GalacticCoord from, GalacticCoord to) {
        if (from == null || to == null) {
            return 0d;
        }
        GalacticCoord a = from.cellCentre();
        Optional<Galaxy> galaxy = galaxies.galaxyContainingSector(seed, a.sectorX(), a.sectorY(),
                a.sectorZ());
        return galaxy.isPresent() ? nebulae.columnDensityBetween(seed, galaxy.get(), from, to) : 0d;
    }

    @Override
    public Optional<GalacticCoord> anchorAt(long seed, GalacticCoord cell) {
        Optional<Generated> g = systemForLattice(seed,
                latticeAt(seed, cell.sectorX(), cell.sectorY(), cell.sectorZ()));
        return g.isPresent() ? Optional.of(g.get().cell) : Optional.<GalacticCoord>empty();
    }

    @Override
    public int minSpacingCells() {
        return config.minSpacing;
    }

    @Override
    public Optional<GalacticCoord> declarationOriginOf(long seed, GalaxyKey key) {
        return galaxies.declarationOriginOf(seed, key);
    }

    @Override
    public double guaranteedAuthoredReachLy() {
        return UniverseScale.GUARANTEED_AUTHORED_REACH_LY;
    }

    /**
     * Per-axis clamp of a body's cell into its anchor's own LATTICE cell (margin when the box allows
     * it), so a system's neighbourhood cannot reach into a neighbour's however far an orbit runs.
     *
     * <p>Against the lattice cell rather than a spacing, because inside a star cluster the cell is a
     * sub-cell whose bounds are not a multiple of its own edge — dividing to find the box would put
     * the box somewhere else entirely.</p>
     */
    private static GalacticCoord clampIntoLattice(GalacticCoord bodyCell, Lattice lattice) {
        long cx = clampAxis(bodyCell.sectorX(), lattice.lowX, lattice.edgeX);
        long cy = clampAxis(bodyCell.sectorY(), lattice.lowY, lattice.edgeY);
        long cz = clampAxis(bodyCell.sectorZ(), lattice.lowZ, lattice.edgeZ);
        if (cx == bodyCell.sectorX() && cy == bodyCell.sectorY() && cz == bodyCell.sectorZ()) {
            return bodyCell;
        }
        return GalacticCoord.ofSectorLocal(cx, cy, cz, 0L, 0L, 0L);
    }

    private static long clampAxis(long sector, long low, long edge) {
        long margin = (edge > 2L * NEIGHBOURHOOD_MARGIN_CELLS) ? NEIGHBOURHOOD_MARGIN_CELLS : 0L;
        long lo = low + margin;
        long hi = low + edge - 1L - margin;
        if (sector < lo) {
            return lo;
        }
        return sector > hi ? hi : sector;
    }

    /** The single system a lattice cell hosts (its cell coordinate + fabricated system), or empty. */
    private Optional<Generated> systemForLattice(long seed, Lattice lattice) {
        // OCCUPANCY IS DECIDED IN THE GALAXY'S OWN FRAME, so the profile does the drawing: the disc,
        // the bulge and the arms place the stars. An independent per-cell draw could only ever produce
        // a uniform fog, which is what made "which galaxy is this?" a question with no answer.
        //
        // Evaluated at the lattice cell's CENTRE — a point fixed by the partition, not by any draw, so
        // the probability a cube is occupied cannot depend on where its seat would have landed. And
        // evaluated at t = 0 and never again: a time-dependent occupancy would pop systems in and out
        // of existence. Systems drift afterwards at their galaxy's own omega(r), which is the shear.
        GalaxyField.Material material = galaxies.materialAtSector(seed,
                lattice.lowX + lattice.edgeX / 2L, lattice.lowY + lattice.edgeY / 2L,
                lattice.lowZ + lattice.edgeZ / 2L);
        // A cluster out in the void supplies its own field, because k³ times the halo is still nothing
        // and an intergalactic globular has to be a globular. Inside a galaxy ownField is zero and the
        // profile speaks, so this is the same number it always was everywhere anything already exists.
        double bound = Math.max(material.bound, lattice.ownField);
        // Keyed by the cell's LOW CORNER, which is globally unique whatever lattice it belongs to —
        // a coarse index would collide with a fine one wherever a cluster refines the field.
        boolean star = bound > 0d
                && CellHash.norm(lattice.hash(seed, SALT_OCC)) < Math.min(1d, config.density * bound);
        if (!star) {
            // THE SECOND DRAW, on the cube the first one passed over. Stars need a galaxy to form in;
            // an unbound world does not, so out in the void this is the only roll there is, and inside
            // a galaxy it is what makes free-floating worlds as numerous as the sky says they are.
            //
            // It reads material.total(), which is the bound profile inside a galaxy and the ejecta halo
            // outside it — so the void's population is what the galaxies have thrown out, on one
            // continuous function, rather than a second rule with a density of its own.
            return rogueForLattice(seed, lattice, Math.max(material.total(), lattice.ownField));
        }
        // Seat the anchor anywhere in its cube except a declared margin at the faces. That margin is
        // the system's own CLEAR SPACE, not a fraction of the cube: it is what guarantees two stars
        // never stand closer than the separation floor, and what keeps one system's named bodies from
        // reaching into the next cube (so member-cell attribution stays exact).
        //
        // It used to be the middle quarter per axis, which confined the seat to 1.6 % of the cube's
        // volume — a lattice of tight clumps with guaranteed-empty walls between them, visible in any
        // rendered star field. The margin now costs a couple of percent per face instead, because it
        // is sized by what a system actually needs rather than by the distance to the next star.
        //
        // It is read off the LOCAL edge, so inside a cluster the floor shrinks with the lattice: stars
        // in a globular core really do stand closer than a wide binary, and a system there loses outer
        // bodies by the same rule that has always applied.
        return Optional.of(new Generated(seatIn(seed, lattice), fabricate(seed, lattice)));
    }

    /**
     * What an UNBOUND seat holds, or empty when this cube holds nothing at all.
     *
     * <p>A weighted draw over {@link GalaxyGenConfig#defaultRogueTypes()}, so relative abundance lives
     * in a table exactly as it does for star types, galaxy types and cluster types. Two outcomes
     * today: a starless world, which is what the void is mostly made of, and a whole STAR SYSTEM that
     * was thrown out of its galaxy — rare enough that meeting one out here is an event.</p>
     *
     * <p>A rogue star is fabricated by {@link #fabricate}, unchanged, and it is not marked as anything:
     * rogue-ness is a statement about WHERE a star stands and not about what it is, so a system out in
     * the void is an ordinary system with an ordinary retinue, and the only thing that makes it a find
     * is its address.</p>
     *
     * @param profile the material at this cell — the galaxy's own where there is one, its ejecta where
     *                there is not
     */
    private Optional<Generated> rogueForLattice(long seed, Lattice lattice, double profile) {
        if (!(profile > 0d)) {
            return Optional.empty(); // a galaxy cell with no galaxy in it: the deepest void, and empty
        }
        double occupancy = Math.min(1d, config.density * config.rogue.abundance * profile);
        if (CellHash.norm(lattice.hash(seed, SALT_ROGUE_OCC)) >= occupancy) {
            return Optional.empty();
        }
        GalaxyGenConfig.RogueType type = pickRogueType(lattice.hash(seed, SALT_ROGUE_TYPE));
        if (type.primaryKind == SystemBodyKind.STAR) {
            return Optional.of(new Generated(seatIn(seed, lattice), fabricate(seed, lattice)));
        }
        return Optional.of(new Generated(seatIn(seed, lattice), fabricateRogue(seed, lattice)));
    }

    /**
     * Where this lattice cell's system sits: anywhere in its cube except a declared margin at the
     * faces.
     *
     * <p>That margin is the system's own CLEAR SPACE, not a fraction of the cube: it is what guarantees
     * two systems never stand closer than the separation floor, and what keeps one system's named
     * bodies from reaching into the next cube, so member-cell attribution stays exact.</p>
     *
     * <p>It used to be the middle quarter per axis, which confined the seat to 1.6 % of the cube's
     * volume — a lattice of tight clumps with guaranteed-empty walls between them, visible in any
     * rendered star field. The margin now costs a couple of percent per face instead, because it is
     * sized by what a system actually needs rather than by the distance to the next star.</p>
     *
     * <p>It is read off the LOCAL edge, so inside a cluster the floor shrinks with the lattice: stars
     * in a globular core really do stand closer than a wide binary, and a system there loses outer
     * bodies by the same rule that has always applied.</p>
     */
    private GalacticCoord seatIn(long seed, Lattice lattice) {
        return GalacticCoord.ofSectorLocal(
                lattice.lowX + seatOffset(seed, lattice, SALT_OX, lattice.edgeX),
                lattice.lowY + seatOffset(seed, lattice, SALT_OY, lattice.edgeY),
                lattice.lowZ + seatOffset(seed, lattice, SALT_OZ, lattice.edgeZ), 0L, 0L, 0L);
    }

    /**
     * A system anchored on a starless world. Its id comes from the same synthetic negative range a
     * procedural star's does, through a stream of its own — the id space names systems and does not
     * care what kind of thing stands at one.
     */
    private static PlanetarySystem fabricateRogue(long seed, Lattice lattice) {
        int id = syntheticId(seed, lattice.lowX, lattice.lowY, lattice.lowZ, SALT_ROGUE_ID);
        return PlanetarySystem.ofRogue(id,
                "PGR-" + lattice.lowX + "." + lattice.lowY + "." + lattice.lowZ); // rogue
    }

    private GalaxyGenConfig.RogueType pickRogueType(long h) {
        long r = Math.floorMod(h, totalRogueWeight);
        GalaxyGenConfig.RogueType last = null;
        for (GalaxyGenConfig.RogueType t : rogueTypes) {
            last = t;
            if (r < t.weight) {
                return t;
            }
            r -= t.weight;
        }
        return last; // the table is never empty
    }

    /** Where the seat sits on one axis of its lattice cell, clear of the faces by the local margin. */
    private long seatOffset(long seed, Lattice lattice, long salt, long edge) {
        long margin = laws.seatMarginCells(edge);
        long band = Math.max(1L, edge - 2L * margin);
        return margin + Math.floorMod(lattice.hash(seed, salt), band);
    }

    /**
     * One cell of the star lattice: a coarse super-cell, or one of the {@code k³} sub-cells a star
     * cluster divides it into.
     *
     * <p>Its bounds are PROPORTIONED rather than divided, so the fine lattice tiles a coarse cell of
     * any edge exactly — a plain {@code s / k} would leave a remainder at the top of every coarse
     * cell, and a remainder is a seam.</p>
     */
    private static final class Lattice {
        final long lowX;
        final long lowY;
        final long lowZ;
        final long edgeX;
        final long edgeY;
        final long edgeZ;

        /** See {@link LocalField#ownField} — what a cluster out in the void brings with it. */
        final double ownField;

        private Lattice(long lowX, long lowY, long lowZ, long edgeX, long edgeY, long edgeZ,
                        double ownField) {
            this.lowX = lowX;
            this.lowY = lowY;
            this.lowZ = lowZ;
            this.edgeX = edgeX;
            this.edgeY = edgeY;
            this.edgeZ = edgeZ;
            this.ownField = ownField;
        }

        /** Sub-cell {@code (i, j, m)} of coarse super-cell {@code (supX, supY, supZ)}, at {@code k}. */
        static Lattice of(long supX, long supY, long supZ, long i, long j, long m, int k, long s,
                          double ownField) {
            long baseX = supX * s;
            long baseY = supY * s;
            long baseZ = supZ * s;
            long loI = Math.floorDiv(i * s, (long) k);
            long loJ = Math.floorDiv(j * s, (long) k);
            long loM = Math.floorDiv(m * s, (long) k);
            return new Lattice(baseX + loI, baseY + loJ, baseZ + loM,
                    Math.max(1L, Math.floorDiv((i + 1L) * s, (long) k) - loI),
                    Math.max(1L, Math.floorDiv((j + 1L) * s, (long) k) - loJ),
                    Math.max(1L, Math.floorDiv((m + 1L) * s, (long) k) - loM), ownField);
        }

        /** Its draw for one field, keyed by the low corner — globally unique at any subdivision. */
        long hash(long seed, long salt) {
            return CellHash.of(seed, lowX, lowY, lowZ, salt);
        }

        /** Whether {@code sector} lies inside this cell on the axis whose low/edge are given. */
        static boolean within(long sector, long low, long edge) {
            return sector >= low && sector < low + edge;
        }

        boolean contains(long sectorX, long sectorY, long sectorZ) {
            return within(sectorX, lowX, edgeX) && within(sectorY, lowY, edgeY)
                    && within(sectorZ, lowZ, edgeZ);
        }

        /**
         * The smallest of its three edges — what a system's room is measured against. The edges differ
         * by at most one cell, and taking the smallest is what keeps a neighbourhood inside its cell on
         * every axis rather than on the average of them.
         */
        long minEdge() {
            return Math.min(edgeX, Math.min(edgeY, edgeZ));
        }
    }

    /**
     * What the star lattice looks like at one coarse super-cell: how finely it is divided, and what
     * field it is divided AGAINST.
     *
     * <p>Membership of a cluster is a property of the COARSE cell, which is what keeps this an O(1)
     * question with one answer — and what makes the fine lattice tile the coarse cells it replaces
     * exactly.</p>
     */
    private static final class LocalField {

        static final LocalField PLAIN = new LocalField(1, 0d);

        /** {@code 1} in the ordinary field, and the covering cluster's {@code k} where there is one. */
        final int subdivision;
        /**
         * The field a cluster BRINGS with it, or zero where the surrounding profile already speaks.
         *
         * <p>A cluster's density is expressed as a contrast — {@code k³} times whatever is around it —
         * and inside a galaxy that is exactly right, because what is around it is the real solar
         * neighbourhood. Out in the void it is a contrast against nearly nothing, and {@code k³} times
         * nearly nothing is still nothing: an intergalactic globular would be named, addressable and
         * empty. A globular does not gather the field it sits in; it arrived carrying its own.</p>
         */
        final double ownField;

        LocalField(int subdivision, double ownField) {
            this.subdivision = subdivision;
            this.ownField = ownField;
        }
    }

    /**
     * The field a cluster outside every galaxy supplies, on {@link Galaxy#densityAt}'s scale.
     *
     * <p>One, and derived rather than picked: that profile is normalised at the sun-like galactic
     * radius, so {@code 1} IS the density of an ordinary stellar neighbourhood. A globular thrown clear
     * of its galaxy therefore holds what a globular inside one holds, which is the whole content of
     * "it brought its own stars".</p>
     */
    private static final double INTERGALACTIC_CLUSTER_FIELD = 1d;

    private LocalField localFieldAt(long seed, long supX, long supY, long supZ) {
        long s = config.minSpacing;
        // The CONTAINING galaxy: a cluster inside a satellite belongs to the satellite, and its nucleus
        // sits at the satellite's own centre. Absent out in the void, where a cluster may still sit.
        Optional<Galaxy> galaxy = galaxies.galaxyContainingSector(seed, supX * s + s / 2L,
                supY * s + s / 2L, supZ * s + s / 2L);
        Optional<StarCluster> cluster = clusters.clusterAt(seed, galaxy.orElse(null), supX, supY, supZ);
        if (!cluster.isPresent()) {
            return LocalField.PLAIN;
        }
        // A cluster cannot conjure room its coarse cell never had. Refining below the smallest cell a
        // system can be more than a lone star in would not make a dense cluster — it would make a
        // field of bare stars, which is the opposite of the thing. A spacing too tight to refine is a
        // degenerate galaxy rather than an error, exactly as too tight a spacing already is.
        long ceiling = Math.max(1L, s / UniverseScale.MIN_LATTICE_EDGE_CELLS);
        int k = (int) Math.max(1L, Math.min(cluster.get().subdivision(), ceiling));
        return new LocalField(k, galaxy.isPresent() ? 0d : INTERGALACTIC_CLUSTER_FIELD);
    }

    /** The lattice cell a sector triple falls in. */
    private Lattice latticeAt(long seed, long sectorX, long sectorY, long sectorZ) {
        long s = config.minSpacing;
        long supX = Math.floorDiv(sectorX, s);
        long supY = Math.floorDiv(sectorY, s);
        long supZ = Math.floorDiv(sectorZ, s);
        LocalField local = localFieldAt(seed, supX, supY, supZ);
        int k = local.subdivision;
        if (k <= 1) {
            return Lattice.of(supX, supY, supZ, 0L, 0L, 0L, 1, s, local.ownField);
        }
        return Lattice.of(supX, supY, supZ,
                subIndex(Math.floorMod(sectorX, s), s, k),
                subIndex(Math.floorMod(sectorY, s), s, k),
                subIndex(Math.floorMod(sectorZ, s), s, k), k, s, local.ownField);
    }

    /**
     * Where a region bound sits inside coarse super-cell {@code sup}, as an offset clamped into it —
     * so a bound lying outside the cell reads as its nearest face rather than as a sub-index off the
     * end of the lattice.
     */
    private static long offsetInCoarse(long sector, long sup, long coarseEdge) {
        long offset = sector - sup * coarseEdge;
        return Math.min(coarseEdge - 1L, Math.max(0L, offset));
    }

    /** Which sub-cell an offset inside a coarse cell falls in, on one axis. */
    private static long subIndex(long offsetInCoarse, long coarseEdge, int k) {
        long index = Math.floorDiv(offsetInCoarse * (long) k, Math.max(1L, coarseEdge));
        return Math.min((long) k - 1L, Math.max(0L, index));
    }

    // galaxyProfileAt — the CONTAINING galaxy's density at a sector triple — moved into
    // GalaxyField.materialAtSector, which answers it together with the ejecta halo out in the void.
    // The two are one walk over the cube, and that walk runs once per lattice cell of every placement
    // query, so leaving this here would have meant resolving the cube twice for every star in the game.

    private PlanetarySystem fabricate(long seed, Lattice lattice) {
        long supX = lattice.lowX;
        long supY = lattice.lowY;
        long supZ = lattice.lowZ;
        GalaxyGenConfig.StarType type = pickType(CellHash.of(seed, supX, supY, supZ, SALT_TYPE));
        double sizeFrac = CellHash.norm(CellHash.of(seed, supX, supY, supZ, SALT_SIZE));

        StellarBody star = new StellarBody();
        star.setTemperature(type.temperature);
        star.setSize((float) (type.minSize + sizeFrac * (type.maxSize - type.minSize)));
        int primaryId = syntheticId(seed, supX, supY, supZ, SALT_ID);
        star.setId(primaryId);
        star.setName("PGS-" + supX + "." + supY + "." + supZ); // procedurally-generated system
        addCompanions(seed, supX, supY, supZ, star, primaryId);
        return PlanetarySystem.ofStar(star);
    }

    /**
     * Give this system the stars it has beyond the first.
     *
     * <p>The generator had never produced one: its own javadoc said "a procedural system is a bare
     * star", so every procedural system in the galaxy was single while about half of real stars are
     * not. The type layer could always express a hierarchy; what was missing was anything that drew
     * one, and an id space in which a companion could be addressed at all.</p>
     *
     * <p>Ids come from the system's own reserved slot, so a primary and its companions cannot collide
     * with each other whatever the hash does. A companion is never larger than its primary — the
     * primary is by definition the star its system is named for.</p>
     */
    private void addCompanions(long seed, long supX, long supY, long supZ, StellarBody primary,
                               int primaryId) {
        if (CellHash.norm(CellHash.of(seed, supX, supY, supZ, SALT_MULTIPLICITY)) >= MULTIPLE_FRACTION) {
            return;
        }
        int count = drawCompanionCount(CellHash.norm(
                CellHash.of(seed, supX, supY, supZ, SALT_COMPANION_COUNT)));
        GalacticCoord key = cellOf(supX, supY, supZ);
        for (int i = 1; i <= count; i++) {
            GalaxyGenConfig.StarType type = pickType(
                    CellHash.ofBody(seed, key, i, SALT_COMPANION_TYPE));
            double sizeFrac = CellHash.norm(CellHash.ofBody(seed, key, i, SALT_COMPANION_SIZE));
            float size = (float) (type.minSize + sizeFrac * (type.maxSize - type.minSize));

            StellarBody companion = new StellarBody();
            companion.setTemperature(type.temperature);
            companion.setSize(Math.min(size, primary.getSize()));
            companion.setId(primaryId - i); // the system's own reserved slot; see ID_SLOTS_PER_SYSTEM
            companion.setName(primary.getName() + "-" + (char) ('B' + i - 1));
            companion.setOrbitalDistance(drawSeparation(
                    CellHash.norm(CellHash.ofBody(seed, key, i, SALT_COMPANION_SEP))));
            companion.setBaseTheta(
                    CellHash.norm(CellHash.ofBody(seed, key, i, SALT_COMPANION_ANG)) * 2d * Math.PI);
            primary.addSubStar(companion);
        }
    }

    /** How many companions, from a falling distribution over {@link #COMPANION_COUNT_WEIGHTS}. */
    private static int drawCompanionCount(double u) {
        double acc = 0d;
        for (int i = 0; i < COMPANION_COUNT_WEIGHTS.length; i++) {
            acc += COMPANION_COUNT_WEIGHTS[i];
            if (u < acc) {
                return i + 1;
            }
        }
        return COMPANION_COUNT_WEIGHTS.length;
    }

    /**
     * A companion's separation: log-uniform across the band, bounded by the room the system has.
     *
     * <p>It depends on NOTHING but its own draw. That is deliberate and it is what makes the system
     * buildable at all: a star's zone is a function of the system's luminosity, and the luminosity is
     * a function of where its stars stand, so a separation chosen to avoid the zone would be chosen
     * against a zone that its own choice then moved. Measured 2026-08-14: one such pass left a
     * companion at 177 AU inside planets running out to 180 AU, because pushing the other two
     * companions inward had brightened the system fivefold and widened the very band being avoided.
     * The dependency runs one way instead — stars first, and the retinue accommodates them.</p>
     */
    private static int drawSeparation(double u) {
        double separation = COMPANION_MIN_SEPARATION
                * Math.pow((double) COMPANION_MAX_SEPARATION / COMPANION_MIN_SEPARATION, u);
        return (int) Math.max(COMPANION_MIN_SEPARATION,
                Math.min(UniverseScale.MAX_NAMED_ORBIT_UNITS, Math.round(separation)));
    }

    /**
     * Whether a planet at {@code orbit} could survive among these stars: not between roughly a third
     * of a companion's separation and three times it, where neither a circumbinary nor a satellite
     * orbit is stable.
     */
    private static boolean orbitIsStableAmong(Iterable<StellarBody> companions, int orbit) {
        for (StellarBody companion : companions) {
            double separation = companion.getOrbitalDistance();
            if (orbit > separation / STABILITY_FACTOR && orbit < separation * STABILITY_FACTOR) {
                return false;
            }
        }
        return true;
    }

    /** The super-cell index triple as a coordinate, for the per-index hash draws. */
    private static GalacticCoord cellOf(long supX, long supY, long supZ) {
        return GalacticCoord.ofSectorLocal(supX, supY, supZ, 0L, 0L, 0L);
    }

    private GalaxyGenConfig.StarType pickType(long h) {
        long r = Math.floorMod(h, totalStarWeight); // long arithmetic — overflow-safe over the weight sum
        GalaxyGenConfig.StarType last = null;
        for (GalaxyGenConfig.StarType t : config.starTypes) {
            last = t;
            if (r < t.weight) {
                return t;
            }
            r -= t.weight;
        }
        return last; // config.starTypes is never empty
    }

    /**
     * The primary's synthetic id: negative, so it can never collide with a catalogued star id
     * ({@code 0..N}) or a dim id, and spaced {@link #ID_SLOTS_PER_SYSTEM} apart so a system's
     * companions have ids of their own below it that belong to no other system.
     *
     * @param salt which population is being named. A rogue system draws from the SAME space as a star
     *             through a different stream, so its id is as distinct from a star's as two stars' ids
     *             are from each other — no more and no less
     */
    private static int syntheticId(long seed, long supX, long supY, long supZ, long salt) {
        long slot = Math.floorMod(CellHash.of(seed, supX, supY, supZ, salt),
                SYNTHETIC_ID_RANGE / ID_SLOTS_PER_SYSTEM);
        return -(1 + (int) (slot * ID_SLOTS_PER_SYSTEM));
    }

    private static final class Generated {
        final GalacticCoord cell;
        final PlanetarySystem system;

        Generated(GalacticCoord cell, PlanetarySystem system) {
            this.cell = cell;
            this.system = system;
        }
    }
}
