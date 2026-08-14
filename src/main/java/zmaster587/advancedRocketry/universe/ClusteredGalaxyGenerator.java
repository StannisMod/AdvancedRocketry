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
 *   <li>partition space into {@link GalaxyGenConfig#minSpacing}-cube <i>super-cells</i> — at most one system
 *       each (the minimum-spacing guarantee);</li>
 *   <li>a coarse <i>blob</i> field grouped {@link GalaxyGenConfig#clusterScale} super-cells wide masks
 *       galaxy from void ({@link GalaxyGenConfig#voidFraction});</li>
 *   <li>inside a galaxy, a super-cell hosts a system with probability {@link GalaxyGenConfig#density}, seated
 *       at a hash-chosen cell within the super-cell.</li>
 * </ol>
 *
 * <p>A procedural system is a bare star (type/size sampled by weight from the seed) with a <b>synthetic
 * negative id</b> — it is never in the catalogue and never a dimension, so the id cannot collide with a real
 * star-id ({@code 0..N}) or a dim id. Planet CONTENT is a separate concern; this generator places stars only.</p>
 */
public final class ClusteredGalaxyGenerator implements IGalaxyGenerator {

    // Distinct salts so the independent hash draws (blob mask, occupancy, per-axis offset, star type/size/id)
    // never correlate with each other.
    private static final long SALT_BLOB = 0x1L;
    private static final long SALT_OCC = 0x2L;
    private static final long SALT_OX = 0x3L;
    private static final long SALT_OY = 0x4L;
    private static final long SALT_OZ = 0x5L;
    private static final long SALT_TYPE = 0x6L;
    private static final long SALT_SIZE = 0x7L;
    private static final long SALT_ID = 0x8L;

    private static final long SYNTHETIC_ID_RANGE = 2_000_000_000L; // ids in [-2_000_000_000, -1]

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

    private final GalaxyGenConfig config;
    private final long totalStarWeight;

    public ClusteredGalaxyGenerator(GalaxyGenConfig config) {
        this.config = (config == null) ? GalaxyGenConfig.defaults() : config;
        long w = 0L; // accumulate in long so a few near-Integer.MAX weights cannot overflow the sum
        for (GalaxyGenConfig.StarType t : this.config.starTypes) {
            w += t.weight;
        }
        this.totalStarWeight = Math.max(1L, w);
    }

    public GalaxyGenConfig config() {
        return config;
    }

    @Override
    public Optional<StarSystem> systemAt(long seed, GalacticCoord coord) {
        long sx = coord.sectorX();
        long sy = coord.sectorY();
        long sz = coord.sectorZ();
        long s = config.minSpacing;
        Optional<Generated> g = systemForSuperCell(seed,
                Math.floorDiv(sx, s), Math.floorDiv(sy, s), Math.floorDiv(sz, s));
        if (g.isPresent() && g.get().cell.sameCell(coord)) {
            return Optional.of(g.get().system);
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cost is O(super-cell volume of the box). Callers MUST pass a bounded region — a telescope scan is
     * range-limited by config — not a galactic-scale box.</p>
     */
    @Override
    public Map<GalacticCoord, StarSystem> systemsInRegion(long seed, GalacticCoord min, GalacticCoord max) {
        long s = config.minSpacing;
        long loX = Math.min(min.sectorX(), max.sectorX());
        long hiX = Math.max(min.sectorX(), max.sectorX());
        long loY = Math.min(min.sectorY(), max.sectorY());
        long hiY = Math.max(min.sectorY(), max.sectorY());
        long loZ = Math.min(min.sectorZ(), max.sectorZ());
        long hiZ = Math.max(min.sectorZ(), max.sectorZ());

        Map<GalacticCoord, StarSystem> out = new HashMap<>();
        for (long supX = Math.floorDiv(loX, s); supX <= Math.floorDiv(hiX, s); supX++) {
            for (long supY = Math.floorDiv(loY, s); supY <= Math.floorDiv(hiY, s); supY++) {
                for (long supZ = Math.floorDiv(loZ, s); supZ <= Math.floorDiv(hiZ, s); supZ++) {
                    Optional<Generated> g = systemForSuperCell(seed, supX, supY, supZ);
                    if (!g.isPresent()) {
                        continue;
                    }
                    GalacticCoord c = g.get().cell;
                    if (c.sectorX() >= loX && c.sectorX() <= hiX
                            && c.sectorY() >= loY && c.sectorY() <= hiY
                            && c.sectorZ() >= loZ && c.sectorZ() <= hiZ) {
                        out.put(c, g.get().system);
                    }
                }
            }
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
        Optional<StarSystem> sys = systemAt(seed, cell);
        if (!sys.isPresent()) {
            return Collections.emptyList();
        }
        int starId = sys.get().starId();
        StellarBody star = sys.get().star();
        List<SystemBody> bodies = new ArrayList<>();
        // The star sits at the anchor cell's centre.
        // A star does not move inside its own system: its frame IS the system's anchor.
        bodies.add(SystemBody.fixedAt(cell, SystemBodyKind.STAR, Constants.INVALID_PLANET, starId));

        // A body sits where its ORBIT puts it — one law, one constant, the same one an authored system
        // uses. What the neighbourhood decides is not how far a body goes but how many bodies there is
        // room for: orbits are drawn inside a bracket that already fits, and a system that would run
        // past its own clear space loses BODIES rather than being squashed to fit.
        long s = config.minSpacing;
        double outerBound = maxNamedOrbitUnits(s);

        // AT MOST ONE REAL BODY PER CELL, moons excepted. The draw picks each body's angle and radius
        // independently, so two of them CAN land on the same cell — and two real bodies in one cell are
        // two destinations a player can neither tell apart nor choose between. Claiming cells as they
        // are used, and relocating a body that finds its first choice taken, is what keeps the
        // generator's own output out of that state; the audit that reports it would otherwise fire on
        // the generator itself, and the more bodies a system has the likelier that becomes.
        Set<String> taken = new HashSet<>();
        taken.add(cell.cellKey());

        int count = retinueSize(seed, cell);
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
            int orbit = PlanetDerivation.orbitalDistanceOf(seed, cell, i, count, star);
            if (orbit > outerBound) {
                continue; // outside this system's clear space — a bound of the layout, not a failure
            }
            Seat seat = seatBody(seed, cell, i, orbit, star, s, taken);
            if (seat == null) {
                continue; // this system's neighbourhood is full — a bound of the layout, not a failure
            }
            // Planet or giant is not a roll of its own: it falls out of the body's derived physics,
            // which is what makes the zoning (rock inside, giants past the snow line) emerge instead
            // of being authored. Kept here rather than at realization because the nav list, the sky
            // and the descent trigger all read the kind long before anyone lands.
            BodyProfile profile = PlanetDerivation.derive(seed, cell, seat.cell, 0, star, false, orbit);
            // THE ORBIT LIVES IN THE FRAME, not in the body's own offset — the same shape an authored
            // system uses (SystemContent: a planet sits at its frame origin and the FRAME goes round
            // the star). Built with the convenience constructor, a procedural planet got
            // CellFrame.staticAt(...) and a FIXED offset, so it stood still relative to its star
            // forever while its own moons orbited it, and the identical system authored in XML moved.
            CellFrame bodyFrame = CellFrame.of(AbsolutePos.ofCellName(cell.cellCentre()), seat.law);
            // Procedural bodies have no realized dimension yet — a descent (Layer 2) realizes one.
            bodies.add(new SystemBody(seat.cell, bodyFrame, BodyEphemeris.STATIC, profile.kind(),
                    Constants.INVALID_PLANET, starId, orbit));
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
            addBelt(bodies, seed, cell, (int) (innermostGiantOrbit / INNER_BELT_RESONANCE), star, s,
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
                PlanetDerivation.innerOrbit(star) * 2d);
        addBelt(bodies, seed, cell, (int) Math.min(outerBelt, outerBound), star, s, starId, taken,
                count + 2);
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
    private static double maxNamedOrbitUnits(long s) {
        long reachCells = Math.max(1L, UniverseScale.seatMarginCells(s) - NEIGHBOURHOOD_MARGIN_CELLS);
        return Math.min(UniverseScale.MAX_NAMED_ORBIT_UNITS,
                UniverseScale.orbitUnitsForCells(reachCells));
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
                                 StellarBody star, long s, Set<String> taken) {
        double baseAngle = CellHash.norm(CellHash.ofBody(seed, anchor, index, SALT_BODYANG)) * 2d * Math.PI;
        // Out-of-plane displacement lives in the LAW as an inclination, so a body's height above the
        // disk is part of where it IS at every tick rather than a one-off nudge applied to its name.
        double sinPhi = (CellHash.norm(CellHash.ofBody(seed, anchor, index, SALT_BODYY)) - 0.5d)
                * PROC_DISK_FRACTION;
        double phiDegrees = Math.toDegrees(Math.asin(sinPhi));
        double periodTicks = AstronomicalBodyHelper.TICKS_PER_DAY
                * AstronomicalBodyHelper.getOrbitalPeriod(orbit, star.getMass());

        for (int attempt = 0; attempt < NUDGE_ATTEMPTS; attempt++) {
            BodyEphemeris law = BodyEphemeris.orbit(orbit, baseAngle + attempt * NUDGE_ANGLE,
                    phiDegrees, false, periodTicks, AstronomicalBodyHelper.BLOCKS_PER_ORBIT_UNIT);
            BlockDelta at0 = law.offsetAt(SystemContent.NAME_TICK);
            // The body's address is its OWN cell's centre (zone content sits near the cell centre),
            // box-clamped into the anchor's super-cell so member attribution stays exact at ANY
            // spacing — at tiny spacings a whole orbit can otherwise reach across the super-cell face.
            GalacticCoord addr = clampIntoSuperCell(
                    anchor.plusLocal(at0.dx(), at0.dy(), at0.dz()).cellCentre(), anchor, s);
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
                                StellarBody star, long s, int starId, Set<String> taken, int index) {
        int clamped = Math.max(1, orbit);
        Seat seat = seatBody(seed, anchor, index, clamped, star, s, taken);
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
            bodies.add(new SystemBody(parent, parentFrame, law, SystemBodyKind.MOON,
                    Constants.INVALID_PLANET, starId, parentOrbit));
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
        return PlanetDerivation.derive(seed, anchor.cellCentre(), body.name(), variant, star,
                body.kind() == SystemBodyKind.MOON, body.orbitalDistance());
    }

    @Override
    public Optional<GalacticCoord> anchorAt(long seed, GalacticCoord cell) {
        long s = config.minSpacing;
        Optional<Generated> g = systemForSuperCell(seed,
                Math.floorDiv(cell.sectorX(), s), Math.floorDiv(cell.sectorY(), s),
                Math.floorDiv(cell.sectorZ(), s));
        return g.isPresent() ? Optional.of(g.get().cell) : Optional.<GalacticCoord>empty();
    }

    @Override
    public int minSpacingCells() {
        return config.minSpacing;
    }

    /** Per-axis clamp of a body's cell into its anchor's super-cell box (margin when the box allows it). */
    private static GalacticCoord clampIntoSuperCell(GalacticCoord bodyCell, GalacticCoord anchor, long s) {
        long margin = (s > 2L * NEIGHBOURHOOD_MARGIN_CELLS) ? NEIGHBOURHOOD_MARGIN_CELLS : 0L;
        long cx = clampAxis(bodyCell.sectorX(), anchor.sectorX(), s, margin);
        long cy = clampAxis(bodyCell.sectorY(), anchor.sectorY(), s, margin);
        long cz = clampAxis(bodyCell.sectorZ(), anchor.sectorZ(), s, margin);
        if (cx == bodyCell.sectorX() && cy == bodyCell.sectorY() && cz == bodyCell.sectorZ()) {
            return bodyCell;
        }
        return GalacticCoord.ofSectorLocal(cx, cy, cz, 0L, 0L, 0L);
    }

    private static long clampAxis(long sector, long anchorSector, long s, long margin) {
        long sup = Math.floorDiv(anchorSector, s);
        long lo = sup * s + margin;
        long hi = sup * s + s - 1L - margin;
        if (sector < lo) {
            return lo;
        }
        return sector > hi ? hi : sector;
    }

    /** The single system a super-cell hosts (its cell coordinate + fabricated system), or empty. */
    private Optional<Generated> systemForSuperCell(long seed, long supX, long supY, long supZ) {
        long cs = config.clusterScale;
        // Void mask: a super-cell whose blob is below the void fraction hosts nothing.
        double blob = CellHash.norm(CellHash.of(seed, Math.floorDiv(supX, cs), Math.floorDiv(supY, cs),
                Math.floorDiv(supZ, cs), SALT_BLOB));
        if (blob < config.voidFraction) {
            return Optional.empty();
        }
        if (CellHash.norm(CellHash.of(seed, supX, supY, supZ, SALT_OCC)) >= config.density) {
            return Optional.empty();
        }
        long s = config.minSpacing;
        // Seat the anchor anywhere in its cube except a declared margin at the faces. That margin is
        // the system's own CLEAR SPACE, not a fraction of the cube: it is what guarantees two stars
        // never stand closer than the separation floor, and what keeps one system's named bodies from
        // reaching into the next cube (so member-cell attribution by floorDiv stays exact).
        //
        // It used to be the middle quarter per axis, which confined the seat to 1.6 % of the cube's
        // volume — a lattice of tight clumps with guaranteed-empty walls between them, visible in any
        // rendered star field. The margin now costs a couple of percent per face instead, because it
        // is sized by what a system actually needs rather than by the distance to the next star.
        long margin = UniverseScale.seatMarginCells(s);
        long band = Math.max(1L, s - 2L * margin);
        long base = margin;
        long ox = base + Math.floorMod(CellHash.of(seed, supX, supY, supZ, SALT_OX), band);
        long oy = base + Math.floorMod(CellHash.of(seed, supX, supY, supZ, SALT_OY), band);
        long oz = base + Math.floorMod(CellHash.of(seed, supX, supY, supZ, SALT_OZ), band);
        GalacticCoord cell = GalacticCoord.ofSectorLocal(supX * s + ox, supY * s + oy, supZ * s + oz,
                0L, 0L, 0L);
        return Optional.of(new Generated(cell, fabricate(seed, supX, supY, supZ)));
    }

    private StarSystem fabricate(long seed, long supX, long supY, long supZ) {
        GalaxyGenConfig.StarType type = pickType(CellHash.of(seed, supX, supY, supZ, SALT_TYPE));
        double sizeFrac = CellHash.norm(CellHash.of(seed, supX, supY, supZ, SALT_SIZE));

        StellarBody star = new StellarBody();
        star.setTemperature(type.temperature);
        star.setSize((float) (type.minSize + sizeFrac * (type.maxSize - type.minSize)));
        star.setId(syntheticId(seed, supX, supY, supZ));
        star.setName("PGS-" + supX + "." + supY + "." + supZ); // procedurally-generated system
        return new StarSystem(star);
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

    private static int syntheticId(long seed, long supX, long supY, long supZ) {
        return -(1 + (int) Math.floorMod(CellHash.of(seed, supX, supY, supZ, SALT_ID), SYNTHETIC_ID_RANGE));
    }

    private static final class Generated {
        final GalacticCoord cell;
        final StarSystem system;

        Generated(GalacticCoord cell, StarSystem system) {
            this.cell = cell;
            this.system = system;
        }
    }
}
