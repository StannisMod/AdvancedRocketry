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

        return Optional.of(new Galaxy(gx, gy, gz,
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

    /** How far a galaxy of this radius may drift before it would touch its own cell's face. */
    private double driftBudgetLy(double radiusLy) {
        double halfCellLy = UniverseScale.lightYearsForCells(config.galaxySpacing / 2d);
        return Math.max(0d, halfCellLy - radiusLy);
    }

    // ─── The intergalactic regime ──────────────────────────────────────────────

    /**
     * Which law carries this cell through time: bound to its galaxy, or comoving out in the void.
     *
     * <p>Ask this at a CROSSING and store the answer — see {@link GalacticFrame}. Calling it every
     * tick for a moving craft is the frame-flapping this design exists to prevent.</p>
     */
    public GalacticFrame frameAt(long seed, GalacticCoord cell) {
        Optional<Galaxy> galaxy = galaxyOwning(seed, cell);
        boolean bound = galaxy.isPresent()
                && galaxy.get().containsSector(cell.sectorX(), cell.sectorY(), cell.sectorZ());
        return bound ? GalacticFrame.GALACTIC : GalacticFrame.COMOVING;
    }

    /**
     * Where the cell named {@code cell} actually is at tick {@code tick}, under whichever law governs
     * it. The two laws meet here and nowhere else.
     */
    public LightYearVector positionAt(long seed, GalacticCoord cell, long tick) {
        Optional<Galaxy> galaxy = galaxyOwning(seed, cell);
        if (galaxy.isPresent()
                && galaxy.get().containsSector(cell.sectorX(), cell.sectorY(), cell.sectorZ())) {
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
     * <p>That containment is what keeps three things true at once — at most one galaxy per cell,
     * galaxies that cannot overlap, and an O(1) answer to "which galaxy is this point in" that reads
     * the containing cell and nothing else.</p>
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
