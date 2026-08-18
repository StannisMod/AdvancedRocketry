package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Where the galaxies are: the lattice one level above the star lattice, and the same scheme.
 *
 * <p>Space is partitioned into {@code galaxySpacing}-cube <b>galaxy cells</b>; a cell holds at most
 * one galaxy, seated at a hash offset inside it, with every parameter — type, radius, orientation,
 * arms — drawn from {@code hash(seed, gx, gy, gz)}. Nothing is stored. A galaxy is a value produced
 * by this class exactly as a system is produced by {@link ClusteredGalaxyGenerator}.</p>
 *
 * <h3>The galaxy index is DERIVED, not an addressing tier</h3>
 * <p>{@link #galaxyIndex} is {@code sector / galaxySpacing} — a coarse reading of the sector space
 * that already exists. No coordinate gains a field, nothing is persisted, and a distance is still a
 * distance. That is what makes "which galaxy is this?" an O(1) question with an answer, where an
 * independent per-cell mask left it undefined.</p>
 *
 * <h3>Every point is in a galaxy CELL; only some are in a GALAXY</h3>
 * <p>There is no "nowhere". A cell either holds a galaxy or is entirely void, and inside a cell that
 * holds one, a point is inside a galaxy iff it is within some declared radius. Those are different
 * questions with different methods here — {@link #galaxyOwning} names the cube's PRIMARY,
 * {@link Galaxy#containsSector} says whether you are in one named galaxy, and
 * {@link #galaxyContainingSector} answers which of the cube's galaxies you are actually in.</p>
 *
 * <h3>A cube holds a primary AND its retinue</h3>
 * <p>The lattice seats one galaxy per cube 25 diameters wide, so on the lattice alone the nearest
 * galaxy is always 25 diameters off — which is the distance to the nearest equal GIANT, not to the
 * nearest galaxy of any kind. A real giant keeps company at one to three diameters. So a galaxy draws
 * {@link #satellitesOf satellites} as CHILDREN inside its own cube, the way a system draws moons inside
 * its primary's cell. <b>Nothing about the representation moves</b>: the cube keeps its size, no
 * coordinate gains a field, and a satellite is a {@link Galaxy} value drawn from {@code (seed, cell,
 * ordinal)} and stored nowhere. What moves is only that a cube's galaxies now have to be told apart —
 * hence {@link Galaxy#satelliteIndex()} and the {@code -S<i>n</i>} suffix in its name.</p>
 *
 * <h3>The home galaxy</h3>
 * <p>Galaxy cell {@code (0,0,0)} is RESERVED: it always holds a galaxy, seated so that the universe
 * ORIGIN falls at a sun-like radius inside its disc, and drawn only among types large enough to hold
 * authored content. A galaxy is otherwise a hash draw and may simply not be there under another seed —
 * but authored content must exist under EVERY seed, and a hand-picked absolute coordinate would
 * otherwise land in intergalactic space with probability 99.997 %.</p>
 *
 * <p><b>Around the origin, not ON it.</b> The centre of a galaxy is its nucleus, which is the last
 * address a shipped solar system should have. Only the galaxy's EXISTENCE and the origin's place
 * inside it are fixed; its type, size, orientation and arms are drawn like any other galaxy's, so
 * every world's home galaxy is still its own.</p>
 */
public final class GalaxyField {

    // A salt space of its own, well clear of the generator's, so a galaxy draw and a star draw over
    // the same integer triple can never be the same number.
    private static final long SALT_GALAXY_OCC = 0x101L;
    private static final long SALT_GALAXY_TYPE = 0x102L;
    private static final long SALT_GALAXY_RADIUS = 0x103L;
    private static final long SALT_GALAXY_OX = 0x104L;
    private static final long SALT_GALAXY_OY = 0x105L;
    private static final long SALT_GALAXY_OZ = 0x106L;
    private static final long SALT_GALAXY_TILT = 0x107L;
    private static final long SALT_GALAXY_NODE = 0x108L;
    private static final long SALT_GALAXY_PITCH = 0x109L;
    private static final long SALT_GALAXY_PHASE = 0x10AL;
    private static final long SALT_GALAXY_SPEED = 0x10BL;
    private static final long SALT_GALAXY_HEADING = 0x10CL;
    private static final long SALT_GALAXY_ELEVATION = 0x10DL;
    private static final long SALT_GALAXY_HOME_ANGLE = 0x10EL;
    // The retinue's own draws. A satellite's parameters are drawn from its PRIMARY's cell index with
    // the satellite's own ordinal folded into the seed, so two satellites of one galaxy cannot
    // correlate and no salt has to be allocated per satellite.
    private static final long SALT_SATELLITE_COUNT = 0x10FL;
    private static final long SALT_SATELLITE_TYPE = 0x110L;
    private static final long SALT_SATELLITE_RADIUS = 0x111L;
    private static final long SALT_SATELLITE_DISTANCE = 0x112L;
    private static final long SALT_SATELLITE_HEADING = 0x113L;
    private static final long SALT_SATELLITE_ELEVATION = 0x114L;
    private static final long SALT_SATELLITE_TILT = 0x115L;
    private static final long SALT_SATELLITE_NODE = 0x116L;
    private static final long SALT_SATELLITE_PITCH = 0x117L;
    private static final long SALT_SATELLITE_PHASE = 0x118L;

    /**
     * What separates one satellite's draws from the next's. Mixed into the SEED through a multiplier of
     * its own, exactly as {@code CellHash.ofBody} does for a system's bodies — added to the salt
     * instead, the two would merge and satellite {@code i} would be a near-copy of {@code i+1}.
     */
    private static final long SATELLITE_ORDINAL_MIX = 0xD1B54A32D192ED03L;

    /** Arms are drawn in this pitch band, in degrees — the range real spirals occupy. */
    private static final double MIN_ARM_PITCH_DEGREES = 10d;
    private static final double MAX_ARM_PITCH_DEGREES = 30d;

    /**
     * A galaxy's own motion through the expanding universe, in km/s — the band real peculiar
     * velocities occupy. Andromeda's 110 km/s sits inside it.
     */
    private static final double MIN_PECULIAR_SPEED_KM_S = 50d;
    private static final double MAX_PECULIAR_SPEED_KM_S = 600d;

    private final GalaxyGenConfig config;
    private final long totalGalaxyWeight;
    private final long totalHomeWeight;

    public GalaxyField(GalaxyGenConfig config) {
        this.config = (config == null) ? GalaxyGenConfig.defaults() : config;
        long all = 0L; // accumulated in long so a few near-Integer.MAX weights cannot overflow the sum
        long home = 0L;
        for (GalaxyGenConfig.GalaxyType t : this.config.galaxyTypes) {
            all += t.weight;
            if (qualifiesForAuthoredContent(t)) {
                home += t.weight;
            }
        }
        this.totalGalaxyWeight = Math.max(1L, all);
        this.totalHomeWeight = home;
    }

    public GalaxyGenConfig config() {
        return config;
    }

    /**
     * The galaxy-lattice index a sector belongs to: the DERIVED grouping that answers "which galaxy
     * cell is this", with nothing stored anywhere.
     *
     * <p><b>The lattice is offset by half a cell, so the ORIGIN is a cell CENTRE and not a corner.</b>
     * That is what lets the home galaxy be centred on the origin and still sit wholly inside its own
     * cell — with the corner convention, every sector with a negative coordinate would belong to a
     * NEIGHBOURING cell, so most of the space around the shipped solar system would have been reading
     * a different galaxy's profile (or none) while standing inside the home galaxy.</p>
     *
     * <p>The half-cell shift is applied to the QUOTIENT rather than to the coordinate: adding it to a
     * sector near the {@code long} limit would overflow, and a coordinate that silently wraps is
     * exactly the failure this layer removed from {@code absoluteX()}.</p>
     */
    public static long galaxyIndex(long sector, long galaxySpacing) {
        long s = Math.max(1L, galaxySpacing);
        long half = s / 2L;
        long rem = Math.floorMod(sector, s);
        long base = Math.floorDiv(sector, s);
        return rem >= s - half ? base + 1L : base;
    }

    /** The lowest sector belonging to galaxy cell {@code index} on one axis. */
    public static long cellLowCorner(long index, long galaxySpacing) {
        long s = Math.max(1L, galaxySpacing);
        return index * s - s / 2L;
    }

    /**
     * The PRIMARY galaxy of the cube this sector triple falls in, or empty when that cube is void.
     *
     * <p>Three questions live near each other and only this one is answered here — <b>which galaxy owns
     * this cube</b>, the identity a cube is named and declared against. It does not ask whether the
     * point is inside that galaxy ({@link Galaxy#containsSector}), and it does not ask which of the
     * cube's galaxies the point is in, because a cube holds the primary AND its satellites
     * ({@link #galaxyContainingSector}). A caller that wants a PROFILE, a FRAME or a cluster wants that
     * third one; a caller naming the neighbourhood wants this.</p>
     */
    public Optional<Galaxy> galaxyOwningSector(long seed, long sectorX, long sectorY, long sectorZ) {
        long s = config.galaxySpacing;
        return galaxyAtIndex(seed, galaxyIndex(sectorX, s), galaxyIndex(sectorY, s),
                galaxyIndex(sectorZ, s));
    }

    /** The primary galaxy of the cube {@code cell} falls in, or empty when that cube is void. */
    public Optional<Galaxy> galaxyOwning(long seed, GalacticCoord cell) {
        return galaxyOwningSector(seed, cell.sectorX(), cell.sectorY(), cell.sectorZ());
    }

    /**
     * The galaxy this sector triple is actually INSIDE — the cube's primary, or one of its satellites,
     * or empty out in the void between them.
     *
     * <p>This is the question the placement profile, the frame law and the cluster lattice all ask, and
     * the reason it is separate from {@link #galaxyOwningSector} is that a cube holds more than one
     * galaxy. Asking the owner and then reading ITS profile would put every satellite's interior at
     * density zero — the satellites would be named, addressable and empty.</p>
     *
     * <p><b>The answer is always at most one.</b> A satellite is seated at least one full primary
     * DIAMETER out and is at most {@link UniverseScale#MAX_SATELLITE_RADIUS_FRACTION} of the primary's
     * radius, so no two spheres in a cube can overlap; the single-answer invariant the whole layer rests
     * on is a property of that geometry rather than of a tie-break rule here.</p>
     *
     * <p>Cost: the primary is tested first, then the retinue is rejected wholesale by one sphere test
     * against {@link UniverseScale#retinueReachLy} before any satellite is drawn. The retinue reaches a
     * few diameters and the cube is 25 across, so that rejects ~98 % of the cube's volume — which
     * matters, because this runs once per super-cell of every placement query.</p>
     */
    public Optional<Galaxy> galaxyContainingSector(long seed, long sectorX, long sectorY, long sectorZ) {
        Optional<Galaxy> owner = galaxyOwningSector(seed, sectorX, sectorY, sectorZ);
        if (!owner.isPresent()) {
            return owner;
        }
        Galaxy primary = owner.get();
        if (primary.containsSector(sectorX, sectorY, sectorZ)) {
            return owner;
        }
        if (!withinRetinueReach(primary, sectorX, sectorY, sectorZ)) {
            return Optional.empty();
        }
        for (Galaxy satellite : satellitesOf(seed, primary)) {
            if (satellite.containsSector(sectorX, sectorY, sectorZ)) {
                return Optional.of(satellite);
            }
        }
        return Optional.empty();
    }

    /** The galaxy {@code cell} is inside — primary, satellite, or none. */
    public Optional<Galaxy> galaxyContaining(long seed, GalacticCoord cell) {
        return galaxyContainingSector(seed, cell.sectorX(), cell.sectorY(), cell.sectorZ());
    }

    /**
     * How much stellar material stands at one sector triple, split into the part that is BOUND to a
     * galaxy and the part that is not.
     *
     * <p>The two are asked together because they are answered by the same walk over the cube, and that
     * walk is the expensive thing on the placement path — it runs once per lattice cell of every
     * placement query, so resolving the cube twice would double the cost of every star in the game.</p>
     */
    public static final class Material {

        /** Nothing here: the cube is empty, or a point too far from anything in it. */
        public static final Material NONE = new Material(0d, 0d);

        /**
         * The density of the galaxy this point is INSIDE, on {@link Galaxy#densityAt}'s scale, or zero
         * out in the void. What decides where stars form.
         */
        public final double bound;
        /**
         * The density of the cube's galaxies' ejecta at this point — what they have thrown out and no
         * longer hold. It is the void's whole population, and it is zero inside a galaxy, where the
         * bound profile already accounts for every body standing there.
         */
        public final double unbound;

        Material(double bound, double unbound) {
            this.bound = bound > 0d ? bound : 0d;
            this.unbound = unbound > 0d ? unbound : 0d;
        }

        /** Everything at this point, bound or not — what a population that does not need a star sees. */
        public double total() {
            return bound + unbound;
        }
    }

    /**
     * The bound and unbound material at a sector triple, resolved in ONE pass over the cube's galaxies.
     *
     * <p>Supersedes reading the profile alone. A caller that only wants to place a STAR reads
     * {@link Material#bound} and gets exactly what it got before; the void's own population reads
     * {@link Material#total()}, which is what makes the intergalactic content a consequence of the
     * galaxies rather than a second field seated by its own rule.</p>
     */
    public Material materialAtSector(long seed, long sectorX, long sectorY, long sectorZ) {
        Optional<Galaxy> owner = galaxyOwningSector(seed, sectorX, sectorY, sectorZ);
        if (!owner.isPresent()) {
            // A cube with no galaxy has thrown nothing out: the deepest void, and genuinely empty.
            return Material.NONE;
        }
        Galaxy primary = owner.get();
        if (primary.containsSector(sectorX, sectorY, sectorZ)) {
            // Inside the primary, and the retinue is never drawn here. A satellite is at most 0.3 R
            // across and sits one to three DIAMETERS out, so the strongest halo one can cast anywhere
            // inside its primary is a couple of percent of what the primary's own disc reads there —
            // and this is the hottest path in the layer, taken for every cell of the shipped galaxy.
            return new Material(primary.densityAtSector(sectorX, sectorY, sectorZ), 0d);
        }
        double falloff = config.rogue.ejectaFalloff;
        double unbound = primary.ejectaDensityAtSector(sectorX, sectorY, sectorZ, falloff);
        if (!withinRetinueReach(primary, sectorX, sectorY, sectorZ)) {
            return new Material(0d, unbound); // past the retinue: only the primary's own halo reaches
        }
        for (Galaxy satellite : satellitesOf(seed, primary)) {
            if (satellite.containsSector(sectorX, sectorY, sectorZ)) {
                return new Material(satellite.densityAtSector(sectorX, sectorY, sectorZ), unbound);
            }
            // The strongest halo, never the sum: two overlapping haloes are one region of thrown-out
            // material counted twice, and adding them would make the gap between two dwarfs read
            // denser than either dwarf's own edge.
            unbound = Math.max(unbound,
                    satellite.ejectaDensityAtSector(sectorX, sectorY, sectorZ, falloff));
        }
        return new Material(0d, unbound);
    }

    /**
     * The satellites of {@code primary} — drawn from {@code (seed, its cell, ordinal)}, stored nowhere,
     * exactly as the primary itself is. Empty for a type that keeps none, and for a satellite: the
     * retinue is one level deep, because a satellite of a satellite is not a thing a real group has and
     * would make the containment answer recursive.
     */
    public List<Galaxy> satellitesOf(long seed, Galaxy primary) {
        if (primary == null || primary.isSatellite()) {
            return Collections.emptyList();
        }
        GalaxyGenConfig.GalaxyType type = primary.type();
        if (type.maxSatellites <= 0) {
            return Collections.emptyList();
        }
        long gx = primary.cellX();
        long gy = primary.cellY();
        long gz = primary.cellZ();
        int span = type.maxSatellites - type.minSatellites + 1;
        int count = type.minSatellites
                + (int) Math.floorMod(CellHash.of(seed, gx, gy, gz, SALT_SATELLITE_COUNT), (long) span);
        if (count <= 0) {
            return Collections.emptyList();
        }
        List<Galaxy> retinue = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            Galaxy satellite = satelliteOf(seed, primary, i);
            if (satellite != null) {
                retinue.add(satellite);
            }
        }
        return Collections.unmodifiableList(retinue);
    }

    /** The satellites of the primary seated in the cube {@code cell} falls in. */
    public List<Galaxy> satellitesAround(long seed, GalacticCoord cell) {
        Optional<Galaxy> primary = galaxyOwning(seed, cell);
        return primary.isPresent() ? satellitesOf(seed, primary.get())
                : Collections.<Galaxy>emptyList();
    }

    /**
     * One satellite: a smaller galaxy of its own type, seated a band of primary DIAMETERS out in an
     * isotropic direction, with its own orientation and arms.
     *
     * <p>Its TYPE is drawn from the archetypes whose whole radius band fits under
     * {@link UniverseScale#MAX_SATELLITE_RADIUS_FRACTION} of the primary's radius, so "smaller than what
     * it orbits" is a constraint on the DRAW and never a clamp on its result — the same shape the
     * authored-content floor uses. A primary too small for any type to fit under that fraction keeps no
     * satellites, which is the honest answer rather than a forced dwarf.</p>
     *
     * <p><b>Its centre does not move relative to its primary,</b> and that is a measurement rather than
     * a simplification: a real satellite's orbit runs to 10⁹ years, three orders slower than the disc
     * rotation this layer already establishes is invisible inside one save. It carries the primary's
     * peculiar velocity, so the group travels together and the home galaxy's retinue stands as still as
     * the home galaxy does.</p>
     */
    private Galaxy satelliteOf(long seed, Galaxy primary, int ordinal) {
        long gx = primary.cellX();
        long gy = primary.cellY();
        long gz = primary.cellZ();
        long ownSeed = seed ^ ((long) ordinal * SATELLITE_ORDINAL_MIX);

        GalaxyGenConfig.GalaxyType type = pickSatelliteType(ownSeed, gx, gy, gz, primary.radiusLy());
        if (type == null) {
            return null;
        }
        double radiusFraction = CellHash.norm(
                CellHash.of(ownSeed, gx, gy, gz, SALT_SATELLITE_RADIUS));
        double radiusLy = type.minRadiusLy + radiusFraction * (type.maxRadiusLy - type.minRadiusLy);

        double distanceFraction = CellHash.norm(
                CellHash.of(ownSeed, gx, gy, gz, SALT_SATELLITE_DISTANCE));
        double diameters = UniverseScale.MIN_SATELLITE_DISTANCE_IN_DIAMETERS + distanceFraction
                * (UniverseScale.MAX_SATELLITE_DISTANCE_IN_DIAMETERS
                        - UniverseScale.MIN_SATELLITE_DISTANCE_IN_DIAMETERS);
        double distanceLy = diameters * 2d * primary.radiusLy();

        // Isotropic: cos(elevation) uniform rather than the elevation itself, or the retinue would pile
        // up over the primary's poles. Deliberately NOT in the primary's plane — real companions are
        // scattered around a giant rather than laid out in its disc.
        double heading = CellHash.norm(CellHash.of(ownSeed, gx, gy, gz, SALT_SATELLITE_HEADING))
                * 2d * Math.PI;
        double cosEl = 2d * CellHash.norm(CellHash.of(ownSeed, gx, gy, gz, SALT_SATELLITE_ELEVATION))
                - 1d;
        double sinEl = Math.sqrt(Math.max(0d, 1d - cosEl * cosEl));
        double offX = distanceLy * sinEl * Math.cos(heading);
        double offY = distanceLy * cosEl;
        double offZ = distanceLy * sinEl * Math.sin(heading);

        GalacticCoord centre = GalacticCoord.ofSectorLocal(
                primary.centre().sectorX() + UniverseScale.cellsAt(offX),
                primary.centre().sectorY() + UniverseScale.cellsAt(offY),
                primary.centre().sectorZ() + UniverseScale.cellsAt(offZ), 0L, 0L, 0L);

        double tilt = Math.acos(2d * CellHash.norm(
                CellHash.of(ownSeed, gx, gy, gz, SALT_SATELLITE_TILT)) - 1d);
        double node = CellHash.norm(CellHash.of(ownSeed, gx, gy, gz, SALT_SATELLITE_NODE))
                * 2d * Math.PI;
        double pitch = Math.toRadians(MIN_ARM_PITCH_DEGREES
                + CellHash.norm(CellHash.of(ownSeed, gx, gy, gz, SALT_SATELLITE_PITCH))
                * (MAX_ARM_PITCH_DEGREES - MIN_ARM_PITCH_DEGREES));
        double phase = CellHash.norm(CellHash.of(ownSeed, gx, gy, gz, SALT_SATELLITE_PHASE))
                * 2d * Math.PI;

        return new Galaxy(gx, gy, gz, ordinal, centre, type, radiusLy, tilt, node, pitch, phase,
                primary.peculiarVelocity());
    }

    /**
     * A satellite's archetype: drawn by weight among the types small enough to be one, or {@code null}
     * when the table holds none that small.
     */
    private GalaxyGenConfig.GalaxyType pickSatelliteType(long seed, long gx, long gy, long gz,
                                                         double primaryRadiusLy) {
        double ceiling = UniverseScale.MAX_SATELLITE_RADIUS_FRACTION * primaryRadiusLy;
        long total = 0L;
        for (GalaxyGenConfig.GalaxyType t : config.galaxyTypes) {
            if (t.maxRadiusLy <= ceiling) {
                total += t.weight;
            }
        }
        if (total <= 0L) {
            return null;
        }
        long r = Math.floorMod(CellHash.of(seed, gx, gy, gz, SALT_SATELLITE_TYPE), total);
        GalaxyGenConfig.GalaxyType last = null;
        for (GalaxyGenConfig.GalaxyType t : config.galaxyTypes) {
            if (t.maxRadiusLy > ceiling) {
                continue;
            }
            last = t;
            if (r < t.weight) {
                return t;
            }
            r -= t.weight;
        }
        return last;
    }

    /**
     * Whether a point is close enough to {@code primary} for any of its satellites to reach it. One
     * sphere test that rejects the whole retinue, so the void inside a cube costs nothing.
     */
    private static boolean withinRetinueReach(Galaxy primary, long sectorX, long sectorY,
                                              long sectorZ) {
        double reach = UniverseScale.retinueReachLy(primary.radiusLy());
        double dx = UniverseScale.lightYearsForCells(
                (double) (sectorX - primary.centre().sectorX()));
        double dy = UniverseScale.lightYearsForCells(
                (double) (sectorY - primary.centre().sectorY()));
        double dz = UniverseScale.lightYearsForCells(
                (double) (sectorZ - primary.centre().sectorZ()));
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /** The home galaxy — the one authored content lives in. Present under every seed, by construction. */
    public Galaxy home(long seed) {
        // Reserved, so the Optional is always full; unwrapping it here is what makes that a statement
        // callers can rely on rather than one they have to re-check.
        return galaxyAtIndex(seed, 0L, 0L, 0L).get();
    }

    /** Whether this galaxy-lattice index is the reserved home cell. */
    public static boolean isHomeCell(long gx, long gy, long gz) {
        return gx == 0L && gy == 0L && gz == 0L;
    }

    /**
     * Whether this cell holds a galaxy WHATEVER the hash says — the home cell, or any key authored
     * content was declared against.
     */
    public boolean isReserved(long gx, long gy, long gz) {
        for (GalaxyKey key : config.reservedGalaxies) {
            if (key.gx() == gx && key.gy() == gy && key.gz() == gz) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cell an authored anchor declared against {@code key} is measured FROM, or empty when that
     * cell holds no galaxy. A reserved key always answers, which is the whole point of reserving it.
     *
     * <p>For {@code home} it is the universe ORIGIN, not the galaxy's centre — the home galaxy is
     * seated around the origin rather than on it, and the origin is where authored content has always
     * been declared. So a coordinate written before galaxies existed still means exactly what it did,
     * and the galaxy is what moved to contain it.</p>
     */
    public Optional<GalacticCoord> declarationOriginOf(long seed, GalaxyKey key) {
        if (key != null && key.isHome()) {
            return Optional.of(GalacticCoord.ORIGIN);
        }
        return centreOf(seed, key);
    }

    /** Where the galaxy named by {@code key} is CENTRED, or empty when that cell holds no galaxy. */
    public Optional<GalacticCoord> centreOf(long seed, GalaxyKey key) {
        if (key == null) {
            return Optional.empty();
        }
        Optional<Galaxy> galaxy = galaxyAtIndex(seed, key.gx(), key.gy(), key.gz());
        return galaxy.isPresent() ? Optional.of(galaxy.get().centre()) : Optional.<GalacticCoord>empty();
    }

    /**
     * The galaxy seated in galaxy cell {@code (gx, gy, gz)}, or empty when the cell is void.
     *
     * <p>Every parameter is a hash draw over the cell index, so the answer is a pure function of
     * {@code (seed, cell)} and two queries about the same galaxy can never disagree.</p>
     */
    public Optional<Galaxy> galaxyAtIndex(long seed, long gx, long gy, long gz) {
        boolean home = isHomeCell(gx, gy, gz);
        boolean reserved = home || isReserved(gx, gy, gz);
        if (!reserved && !occupied(seed, gx, gy, gz)) {
            return Optional.empty();
        }
        // A reserved cell holds authored content, so its galaxy must be large enough to have room for
        // it — the guarantee is a constraint on the TYPE DRAW, never a clamp applied to its result.
        GalaxyGenConfig.GalaxyType type = pickType(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_TYPE),
                reserved);
        double radiusFraction = CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_RADIUS));
        double radiusLy = type.minRadiusLy + radiusFraction * (type.maxRadiusLy - type.minRadiusLy);

        // An isotropic pole: cos(tilt) uniform, not tilt uniform, or galaxies would cluster edge-on.
        double tilt = Math.acos(2d * CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_TILT)) - 1d);
        double node = CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_NODE)) * 2d * Math.PI;
        double pitch = Math.toRadians(MIN_ARM_PITCH_DEGREES
                + CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_PITCH))
                * (MAX_ARM_PITCH_DEGREES - MIN_ARM_PITCH_DEGREES));
        double phase = CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_PHASE)) * 2d * Math.PI;

        return Optional.of(new Galaxy(gx, gy, gz, 0,
                seatOf(seed, gx, gy, gz, radiusLy, tilt, node, home), type,
                radiusLy, tilt, node, pitch, phase,
                peculiarVelocityOf(seed, gx, gy, gz, radiusLy, home)));
    }

    /**
     * A galaxy's own motion through the expanding universe, in light years per tick.
     *
     * <p><b>The home galaxy has none.</b> It is the rest frame authored content is declared in: if it
     * drifted, the shipped solar system — named by absolute cells at {@code t = 0} — would be left
     * behind by its own galaxy. Every other galaxy moves relative to it, which is also what an
     * observer actually sees.</p>
     *
     * <p>The speed is CLAMPED so the galaxy cannot leave its own lattice cell within
     * {@link Cosmology#DRIFT_HORIZON_TICKS}. At realistic speeds the clamp is five orders from
     * binding — so galaxy mergers are excluded by construction and nothing else is.</p>
     */
    private LightYearVector peculiarVelocityOf(long seed, long gx, long gy, long gz, double radiusLy,
                                               boolean home) {
        if (home) {
            return LightYearVector.ZERO;
        }
        double u = CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_SPEED));
        double kmPerSecond = MIN_PECULIAR_SPEED_KM_S
                + u * (MAX_PECULIAR_SPEED_KM_S - MIN_PECULIAR_SPEED_KM_S);
        double speed = Math.min(UniverseScale.lightYearsPerTick(kmPerSecond),
                driftBudgetLy(radiusLy) / (double) Cosmology.DRIFT_HORIZON_TICKS);

        // Isotropic: cos(elevation) uniform, not the elevation itself, or the draws would pile up at
        // the poles of whatever axis happened to be written first.
        double heading = CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_HEADING)) * 2d * Math.PI;
        double cosEl = 2d * CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_ELEVATION)) - 1d;
        double sinEl = Math.sqrt(Math.max(0d, 1d - cosEl * cosEl));
        return LightYearVector.of(speed * sinEl * Math.cos(heading), speed * cosEl,
                speed * sinEl * Math.sin(heading));
    }

    /**
     * How far a galaxy of this radius may drift before its RETINUE would touch its own cell's face. The
     * whole group travels together, so the budget is the group's reach and not the primary's radius.
     */
    private double driftBudgetLy(double radiusLy) {
        double halfCellLy = UniverseScale.lightYearsForCells(config.galaxySpacing / 2d);
        return Math.max(0d, halfCellLy - UniverseScale.retinueReachLy(radiusLy));
    }

    // ─── The intergalactic regime ──────────────────────────────────────────────

    /**
     * Which law carries this cell through time: bound to its galaxy, or comoving out in the void.
     *
     * <p>Ask this at a CROSSING and store the answer — see {@link GalacticFrame}. Calling it every
     * tick for a moving craft is the frame-flapping this design exists to prevent.</p>
     */
    public GalacticFrame frameAt(long seed, GalacticCoord cell) {
        // The galaxy the cell is INSIDE, which may be a satellite: a thing in a satellite rides the
        // satellite's disc, not the primary's. Reading the cube's owner instead would leave every point
        // in every satellite comoving with the void it is demonstrably not in.
        return galaxyContaining(seed, cell).isPresent()
                ? GalacticFrame.GALACTIC : GalacticFrame.COMOVING;
    }

    /**
     * Where the cell named {@code cell} actually is at tick {@code tick}, under whichever law governs
     * it. The two laws meet here and nowhere else.
     */
    public LightYearVector positionAt(long seed, GalacticCoord cell, long tick) {
        Optional<Galaxy> galaxy = galaxyContaining(seed, cell);
        if (galaxy.isPresent()) {
            return galaxy.get().boundPositionOfCellAt(cell, tick);
        }
        return comovingPositionAt(cell, tick);
    }

    /**
     * Where a VOID cell is at tick {@code tick}: carried by the Hubble flow and nothing else.
     *
     * <p>Out here a position is stated in LIGHT YEARS, not in blocks, and that is what makes the
     * intergalactic regime expressible at all: a galaxy cube is millions of light years across, which
     * is orders past what a block {@code long} holds, and the layer never asks one to hold it. The
     * cell NAME carries the magnitude (a sector triple) and this vector carries the rest.</p>
     */
    public static LightYearVector comovingPositionAt(GalacticCoord cell, long tick) {
        return LightYearVector.ofCell(cell).scale(Cosmology.scaleFactorAt(tick));
    }

    /**
     * Whether this cell holds a galaxy at all.
     *
     * <p><b>The cosmic-web slot.</b> Galaxies in reality lie on filaments around genuine voids, and
     * that is a field over the lattice, not a per-cell coin toss. The field is not built — none of its
     * numbers is ratified and it needs spatially CORRELATED noise, a primitive this generator does not
     * have. What is built is the shape it drops into: {@link #webDensity} is the constant 1 today and
     * becomes that field later, with no change to placement.</p>
     */
    private boolean occupied(long seed, long gx, long gy, long gz) {
        double threshold = config.galaxyDensity * webDensity(gx, gy, gz);
        return CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_OCC)) < threshold;
    }

    /**
     * How much likelier than baseline a galaxy is at this lattice index — the cosmic web's hook. It is
     * deliberately a constant: this states that galaxy density is not REQUIRED to be uniform, and
     * names the one place non-uniformity will live.
     */
    static double webDensity(long gx, long gy, long gz) {
        return 1d;
    }

    /**
     * Where the galaxy sits inside its cube: anywhere that leaves it wholly inside, so it never
     * straddles a face.
     *
     * <p>That containment is what keeps three things true at once — at most one PRIMARY per cell,
     * galaxies that cannot overlap, and an O(1) answer to "which galaxy is this point in" that reads
     * the containing cell and nothing else.</p>
     *
     * <p><b>The margin is the whole RETINUE's reach, not the primary's radius.</b> A satellite is
     * seated a few diameters out, so a margin sized to the primary alone would let a galaxy near a face
     * keep satellites on the wrong side of it — and a galaxy outside its own lattice cell is one the
     * index hands to a neighbour, which is the single-answer invariant broken by a number that was
     * correct before satellites existed.</p>
     *
     * <p>The home galaxy is seated AROUND the origin instead — the origin is where authored content
     * is, so the galaxy has to contain it. Not ON it: the centre of a galaxy is its nucleus, and that
     * is the last address a shipped solar system should have. The offset puts the origin at a
     * sun-like galactic radius, in the plane, out in the disc.</p>
     */
    private GalacticCoord seatOf(long seed, long gx, long gy, long gz, double radiusLy, double tilt,
                                 double node, boolean home) {
        if (home) {
            double angle = CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_HOME_ANGLE))
                    * 2d * Math.PI;
            LightYearVector offset = Galaxy.planeDirection(tilt, node, angle)
                    .scale(-UniverseScale.HOME_GALAXY_ORIGIN_FRACTION * radiusLy);
            return GalacticCoord.ofSectorLocal(UniverseScale.cellsAt(offset.x()),
                    UniverseScale.cellsAt(offset.y()), UniverseScale.cellsAt(offset.z()),
                    0L, 0L, 0L);
        }
        long s = config.galaxySpacing;
        long margin = Math.min(UniverseScale.cellsForLightYears(UniverseScale.retinueReachLy(radiusLy)),
                Math.max(0L, (s - 1L) / 2L));
        long band = Math.max(1L, s - 2L * margin);
        // The index came from a real sector, so a cell corner is bounded by that sector and the
        // products below cannot overflow: each is at most the coordinate it was derived from.
        long ox = margin + Math.floorMod(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_OX), band);
        long oy = margin + Math.floorMod(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_OY), band);
        long oz = margin + Math.floorMod(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_OZ), band);
        return GalacticCoord.ofSectorLocal(cellLowCorner(gx, s) + ox, cellLowCorner(gy, s) + oy,
                cellLowCorner(gz, s) + oz, 0L, 0L, 0L);
    }

    /**
     * Whether a type may be drawn for a galaxy that HOLDS AUTHORED CONTENT: its smallest possible
     * radius must already clear the guaranteed minimum, so the guarantee is a constraint on the DRAW
     * rather than a clamp applied to its result.
     */
    private static boolean qualifiesForAuthoredContent(GalaxyGenConfig.GalaxyType type) {
        return type.minRadiusLy >= UniverseScale.MIN_AUTHORED_GALAXY_RADIUS_LY;
    }

    /**
     * Draw a type by weight — over the whole table, or over the subset a galaxy holding authored
     * content may be.
     *
     * <p>A table with nothing large enough falls back to the whole table: a pack that ships only dwarf
     * galaxies gets the universe it asked for, and its authored content had better be near the
     * centre.</p>
     */
    private GalaxyGenConfig.GalaxyType pickType(long h, boolean restrictToLarge) {
        boolean restricted = restrictToLarge && totalHomeWeight > 0L;
        long r = Math.floorMod(h, restricted ? totalHomeWeight : totalGalaxyWeight);
        GalaxyGenConfig.GalaxyType last = null;
        for (GalaxyGenConfig.GalaxyType t : config.galaxyTypes) {
            if (restricted && !qualifiesForAuthoredContent(t)) {
                continue;
            }
            last = t;
            if (r < t.weight) {
                return t;
            }
            r -= t.weight;
        }
        return last; // config.galaxyTypes is never empty
    }
}
