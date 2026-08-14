package zmaster587.advancedRocketry.universe;

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
 * holds one, a point is inside the galaxy iff it is within the declared radius. Those are two
 * different questions and they have two different methods here — {@link #galaxyOwning} names the
 * cell's galaxy, {@link Galaxy#containsSector} says whether you are in it.</p>
 *
 * <h3>The home galaxy</h3>
 * <p>Galaxy cell {@code (0,0,0)} is RESERVED: it always holds a galaxy, centred on the origin, drawn
 * only among types large enough to hold authored content. A galaxy is otherwise a hash draw and may
 * simply not be there under another seed — but authored content must exist under EVERY seed, and a
 * hand-picked absolute coordinate would otherwise land in intergalactic space with probability
 * 99.997 %. Only its EXISTENCE and its centre are fixed; its type, size, orientation and arms are
 * drawn like any other galaxy's, so every world's home galaxy is still its own.</p>
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

    /** Arms are drawn in this pitch band, in degrees — the range real spirals occupy. */
    private static final double MIN_ARM_PITCH_DEGREES = 10d;
    private static final double MAX_ARM_PITCH_DEGREES = 30d;

    private final GalaxyGenConfig config;
    private final long totalGalaxyWeight;
    private final long totalHomeWeight;

    public GalaxyField(GalaxyGenConfig config) {
        this.config = (config == null) ? GalaxyGenConfig.defaults() : config;
        long all = 0L; // accumulated in long so a few near-Integer.MAX weights cannot overflow the sum
        long home = 0L;
        for (GalaxyGenConfig.GalaxyType t : this.config.galaxyTypes) {
            all += t.weight;
            if (qualifiesAsHome(t)) {
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
     * The galaxy whose CELL contains this sector triple, or empty when that cell is void. It does not
     * ask whether the point is inside the galaxy — see {@link Galaxy#containsSector} for that.
     */
    public Optional<Galaxy> galaxyOwningSector(long seed, long sectorX, long sectorY, long sectorZ) {
        long s = config.galaxySpacing;
        return galaxyAtIndex(seed, galaxyIndex(sectorX, s), galaxyIndex(sectorY, s),
                galaxyIndex(sectorZ, s));
    }

    /** The galaxy whose cell contains {@code cell}, or empty when that cell is void. */
    public Optional<Galaxy> galaxyOwning(long seed, GalacticCoord cell) {
        return galaxyOwningSector(seed, cell.sectorX(), cell.sectorY(), cell.sectorZ());
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
     * The galaxy seated in galaxy cell {@code (gx, gy, gz)}, or empty when the cell is void.
     *
     * <p>Every parameter is a hash draw over the cell index, so the answer is a pure function of
     * {@code (seed, cell)} and two queries about the same galaxy can never disagree.</p>
     */
    public Optional<Galaxy> galaxyAtIndex(long seed, long gx, long gy, long gz) {
        boolean home = isHomeCell(gx, gy, gz);
        if (!home && !occupied(seed, gx, gy, gz)) {
            return Optional.empty();
        }
        GalaxyGenConfig.GalaxyType type = pickType(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_TYPE),
                home);
        double radiusFraction = CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_RADIUS));
        double radiusLy = type.minRadiusLy + radiusFraction * (type.maxRadiusLy - type.minRadiusLy);

        // An isotropic pole: cos(tilt) uniform, not tilt uniform, or galaxies would cluster edge-on.
        double tilt = Math.acos(2d * CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_TILT)) - 1d);
        double node = CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_NODE)) * 2d * Math.PI;
        double pitch = Math.toRadians(MIN_ARM_PITCH_DEGREES
                + CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_PITCH))
                * (MAX_ARM_PITCH_DEGREES - MIN_ARM_PITCH_DEGREES));
        double phase = CellHash.norm(CellHash.of(seed, gx, gy, gz, SALT_GALAXY_PHASE)) * 2d * Math.PI;

        return Optional.of(new Galaxy(gx, gy, gz, seatOf(seed, gx, gy, gz, radiusLy, home), type,
                radiusLy, tilt, node, pitch, phase));
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
     * <p>That containment is what keeps three things true at once — at most one galaxy per cell,
     * galaxies that cannot overlap, and an O(1) answer to "which galaxy is this point in" that reads
     * the containing cell and nothing else.</p>
     *
     * <p>The home galaxy is centred on the ORIGIN instead. Authored anchors are declared in absolute
     * coordinates today, so the origin is where authored content actually is; seating the home galaxy
     * anywhere else would put the shipped solar system in intergalactic space.</p>
     */
    private GalacticCoord seatOf(long seed, long gx, long gy, long gz, double radiusLy, boolean home) {
        if (home) {
            return GalacticCoord.ORIGIN;
        }
        long s = config.galaxySpacing;
        long margin = Math.min(UniverseScale.cellsForLightYears(radiusLy), Math.max(0L, (s - 1L) / 2L));
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
     * Whether a type may be drawn for the HOME galaxy: its smallest possible radius must already
     * clear the guaranteed minimum, so the guarantee is a constraint on the DRAW rather than a clamp
     * applied to its result.
     */
    private static boolean qualifiesAsHome(GalaxyGenConfig.GalaxyType type) {
        return type.minRadiusLy >= UniverseScale.MIN_HOME_GALAXY_RADIUS_LY;
    }

    /**
     * Draw a type by weight — over the whole table, or over the subset a home galaxy may be.
     *
     * <p>A table with nothing large enough to be a home falls back to the whole table: a pack that
     * ships only dwarf galaxies gets the universe it asked for, and its authored content had better
     * be close to the centre.</p>
     */
    private GalaxyGenConfig.GalaxyType pickType(long h, boolean home) {
        boolean restricted = home && totalHomeWeight > 0L;
        long r = Math.floorMod(h, restricted ? totalHomeWeight : totalGalaxyWeight);
        GalaxyGenConfig.GalaxyType last = null;
        for (GalaxyGenConfig.GalaxyType t : config.galaxyTypes) {
            if (restricted && !qualifiesAsHome(t)) {
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
