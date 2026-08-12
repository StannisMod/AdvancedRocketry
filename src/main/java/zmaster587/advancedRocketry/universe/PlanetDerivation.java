package zmaster587.advancedRocketry.universe;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

/**
 * Where a procedural body's PHYSICS comes from, and therefore where its TYPE comes from.
 *
 * <p>A pure function of {@code (seed, cell, body index)}: no world, no {@code Random}, no registry, no
 * tick. Ask it twice and it answers the same, which is the whole point — a telescope reports a world
 * from across the system, and the landing has to match what the telescope said.</p>
 *
 * <h3>The order, and why it is this order</h3>
 * <ol>
 *   <li><b>Metallicity</b> — one more seeded property of the star beside temperature and size. A
 *       metal-poor star formed a metal-poor disk, which is what makes the ore profile physical rather
 *       than tabulated.</li>
 *   <li><b>Orbital radius</b>, drawn LOGARITHMICALLY: real systems are spaced roughly geometrically, and
 *       the range is anchored on the star's own {@linkplain #referenceDistance reference distance}, so
 *       zoning follows the star instead of a fixed table. A cool dwarf gets a compact system and a hot
 *       giant a sprawling one, for free.</li>
 *   <li><b>Bare temperature</b> at that radius, with NO atmosphere. The snow line is this temperature
 *       crossing a threshold — never a separate parameter.</li>
 *   <li><b>Radius and mass</b>, correlated with the zone: small rock inside, giants past the snow line.
 *       Gravity is DERIVED from them ({@code g = M/R²}); it is not drawn.</li>
 *   <li><b>Pressure</b>, from the world's ability to hold an atmosphere against its own heat — heavy and
 *       cold retains, light and hot does not.</li>
 *   <li><b>Temperature again</b>, now with that atmosphere. The greenhouse term needs a pressure, and
 *       the pressure needed a temperature; one pass each way resolves it without iterating, and the bare
 *       reading is kept for the zoning decisions that must not depend on the atmosphere.</li>
 *   <li><b>Type</b> = a weighted draw among the presets that admit the resulting point. Zoning
 *       therefore EMERGES from the physics; no preset is placed anywhere by hand.</li>
 *   <li><b>Terrain</b> from that type's weighted list, and finally the <b>oxygen</b> roll — biology on
 *       top of an already-suitable world, never a consequence of it.</li>
 * </ol>
 *
 * <p>Every constant below is a balance knob. None is a contract, and the class deliberately exposes the
 * intermediate steps so a test can pin the RELATIONS (colder past the snow line, heavier holds more air)
 * without pinning any of the numbers.</p>
 */
public final class PlanetDerivation {

    // Salts, disjoint from ClusteredGalaxyGenerator's placement salts (0x1..0x15) and from each other.
    private static final long SALT_METALLICITY = 0x21L;
    private static final long SALT_ORBIT = 0x22L;
    private static final long SALT_GIANT = 0x23L;
    private static final long SALT_RADIUS = 0x24L;
    private static final long SALT_DENSITY = 0x25L;
    private static final long SALT_PRESSURE = 0x26L;
    private static final long SALT_TYPE = 0x27L;
    private static final long SALT_TERRAIN = 0x28L;
    private static final long SALT_OXYGEN = 0x29L;
    private static final long SALT_RINGS = 0x2AL;
    private static final long SALT_SPIN = 0x2BL;

    /** A rocky world's day, as a multiple of the default, log-uniform between these. */
    private static final double SPIN_ROCKY_MIN = 0.25d;
    private static final double SPIN_ROCKY_MAX = 4.0d;
    /** Giants spin fast — a real correlation, unlike the gravity law this replaces. */
    private static final double SPIN_GIANT_MIN = 0.20d;
    private static final double SPIN_GIANT_MAX = 0.60d;

    /**
     * The temperature, in Kelvin, that defines a star's REFERENCE distance — Earth's equilibrium
     * temperature with no atmosphere. Every orbital radius is drawn as a multiple of the distance at
     * which this star produces it, so "the warm zone" means the same thing around every star.
     */
    private static final double REFERENCE_TEMPERATURE_K = 255d;

    /** Innermost / outermost drawn orbit, as multiples of {@link #referenceDistance}. */
    private static final double INNER_ORBIT_FACTOR = 0.2d;
    private static final double OUTER_ORBIT_FACTOR = 45d;

    /**
     * Bare temperature below which volatiles freeze out — the SNOW LINE, expressed as the threshold it
     * really is. Numerically the {@code FRIGID} band's floor, and deliberately the same number: a world
     * the game calls frigid and a world past the snow line must be the same world.
     */
    private static final int SNOW_LINE_K = 175;

    /** Probability that a body past the snow line accreted into a giant rather than staying a rock. */
    private static final double GIANT_CHANCE_OUTER = 0.34d;
    /** The same, in the cool-but-not-frozen band just inside it. */
    private static final double GIANT_CHANCE_COOL = 0.06d;
    /** Bare temperature below which the cool-band giant chance applies at all. */
    private static final int COOL_BAND_K = 260;

    /** Giant radius range, in Earth radii (Neptune ~3.9, Jupiter ~11). */
    private static final double GIANT_MIN_RADIUS = 3.0d;
    private static final double GIANT_MAX_RADIUS = 11.0d;
    /** Jupiter's mass in Earth masses, and the exponent that carries a smaller giant down from it. */
    private static final double JUPITER_MASSES = 318d;
    private static final double GIANT_MASS_EXPONENT = 2.3d;

    /** Rocky radius draw: {@code MIN + u^BIAS · SPAN}, biased small so Earth-sized is the median. */
    private static final double ROCK_MIN_RADIUS = 0.2d;
    private static final double ROCK_RADIUS_SPAN = 2.3d;
    private static final double ROCK_RADIUS_BIAS = 1.7d;
    /** A moon is drawn from the same law with a smaller span — moons are small by construction. */
    private static final double MOON_RADIUS_SPAN = 0.55d;

    /** Bulk density relative to Earth's, and the exponent that makes big rocky worlds denser. */
    private static final double MIN_DENSITY = 0.75d;
    private static final double DENSITY_SPAN = 0.5d;
    private static final double ROCK_MASS_EXPONENT = 3.7d;

    /** Gravity floor in g — the same floor the legacy random generator has always used. */
    private static final double MIN_GRAVITY_G = 0.05d;

    /**
     * Atmospheric retention. {@code (M/R)} is escape velocity squared in Earth units; dividing by the
     * bare temperature gives the Jeans-parameter shape — heavy and cold holds air, light and hot loses
     * it. Normalised so Earth sits at 1, then raised to a steep power because the real transition from
     * airless to crushing happens over a narrow range of that ratio.
     */
    private static final double EARTH_RETENTION = 1d / (255d / 288d);
    private static final double RETENTION_EXPONENT = 2.6d;
    private static final double PRESSURE_SCATTER_MIN = 0.4d;
    private static final double PRESSURE_SCATTER_SPAN = 2.6d;

    /** Chance that a world whose type PERMITS oxygen actually has it. Biology, so: rare. */
    private static final double OXYGEN_CHANCE = 0.18d;

    /**
     * Ring chance for a giant, and for everything else. Rings are the debris of a moon that came apart
     * inside its planet's Roche limit, and only a giant's limit reaches far enough beyond its own body
     * for that to be a place a moon could ever have been — which is why all four Solar giants have them
     * and none of the rocky planets does.
     */
    private static final double RING_CHANCE_GIANT = 0.7d;
    private static final double RING_CHANCE_ROCKY = 0.02d;

    /**
     * Tidal-locking radius at one solar radius, in AU. Beyond a scale factor this is the real
     * astronomical embarrassment about M-dwarf habitability: the locking radius shrinks far more slowly
     * with the star than the warm zone does, so a cool dwarf's habitable orbits sit WELL inside it and
     * its temperate worlds are locked, while a sunlike star's are not.
     */
    private static final double TIDAL_LOCK_AU = 0.5d;

    /** Metallicity draw, relative to Sol. */
    private static final double MIN_METALLICITY = 0.35d;
    private static final double METALLICITY_SPAN = 1.25d;
    private static final double METALLICITY_BIAS = 1.3d;

    private PlanetDerivation() {
    }

    // ─── The pieces, each answerable on its own ────────────────────────────────

    /**
     * The parent star's metal content relative to Sol. Keyed on the system's ANCHOR cell, not on the
     * body, because it is a property of the star: every body of one system shares it.
     */
    public static double metallicityOf(long seed, GalacticCoord anchor) {
        double u = CellHash.norm(CellHash.ofCell(seed, anchor.cellCentre(), SALT_METALLICITY));
        return MIN_METALLICITY + Math.pow(u, METALLICITY_BIAS) * METALLICITY_SPAN;
    }

    /**
     * The orbital distance, in Advanced Rocketry units, at which this star warms a bare world to
     * {@link #REFERENCE_TEMPERATURE_K}. One AU for Sol by construction; a tenth of that for a cool red
     * dwarf; a dozen AU for a hot blue giant.
     */
    public static int referenceDistance(StellarBody star) {
        if (star == null) {
            return AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        }
        // T falls as 1/sqrt(distance), so one probe at 1 AU fixes the whole curve.
        int atOneAu = AstronomicalBodyHelper.getAverageTemperature(star,
                AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU, 0);
        if (atOneAu <= 0) {
            return AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        }
        double ratio = atOneAu / REFERENCE_TEMPERATURE_K;
        double ref = AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU * ratio * ratio;
        return (int) clamp(ref, DimensionProperties.MIN_DISTANCE, 100_000d);
    }

    /**
     * The orbital distance of body {@code index} of {@code count}, drawn log-uniformly across the
     * star's zone.
     *
     * <p>Each body owns a SLOT of the logarithmic range and is jittered inside it by less than half a
     * slot, so the draw is irregular but the ordering is not: body {@code i} is always inside body
     * {@code i+1}. That is what lets the placement map an orbit onto a cell radius monotonically, and it
     * is why two bodies of one system cannot swap places when a tuning constant moves.</p>
     */
    public static int orbitalDistanceOf(long seed, GalacticCoord anchor, int index, int count,
                                        StellarBody star) {
        double lo = innerOrbit(star);
        double hi = outerOrbit(star);
        int slots = Math.max(1, count);
        double jitter = 0.6d * (CellHash.norm(CellHash.ofBody(seed, anchor.cellCentre(), index, SALT_ORBIT))
                - 0.5d);
        double f = (Math.min(index, slots - 1) + 0.5d + jitter) / slots;
        double distance = lo * Math.pow(hi / lo, clamp(f, 0d, 1d));
        return (int) clamp(distance, DimensionProperties.MIN_DISTANCE, 1_000_000d);
    }

    /** The innermost orbit this star's system may hold, in Advanced Rocketry distance units. */
    public static double innerOrbit(StellarBody star) {
        return Math.max(DimensionProperties.MIN_DISTANCE, referenceDistance(star) * INNER_ORBIT_FACTOR);
    }

    /** The outermost orbit this star's system may hold. Always comfortably above {@link #innerOrbit}. */
    public static double outerOrbit(StellarBody star) {
        return Math.max(innerOrbit(star) * 1.5d, referenceDistance(star) * OUTER_ORBIT_FACTOR);
    }

    /**
     * Where {@code orbitalDistance} sits in this star's zone, as a fraction in {@code [0,1]} on a
     * LOGARITHMIC scale — the inverse of the orbital draw.
     *
     * <p>This is what lets the galactic placement map an orbit onto a cell radius: the two layouts then
     * agree by construction, so a body that is third from its star is also third out from the anchor
     * cell, and neither can be re-tuned without the other following.</p>
     */
    public static double orbitFraction(int orbitalDistance, StellarBody star) {
        double lo = innerOrbit(star);
        double hi = outerOrbit(star);
        if (!(hi > lo)) {
            return 0d;
        }
        return clamp(Math.log(Math.max(lo, orbitalDistance) / lo) / Math.log(hi / lo), 0d, 1d);
    }

    /** The bare (no-atmosphere) equilibrium temperature at a distance — the zoning reading. */
    public static int bareTemperature(StellarBody star, int orbitalDistance) {
        return AstronomicalBodyHelper.getAverageTemperature(star, Math.max(1, orbitalDistance), 0);
    }

    /** Whether a body this close to this star keeps one face to it. */
    public static boolean tidallyLockedAt(StellarBody star, int orbitalDistance) {
        if (star == null) {
            return false;
        }
        double lockDistance = AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU * TIDAL_LOCK_AU
                * Math.cbrt(Math.max(0.05d, star.getSize()));
        return orbitalDistance <= lockDistance;
    }

    /** Whether the body at this index accreted into a giant, given how cold its orbit is. */
    public static boolean isGiantAt(long seed, GalacticCoord anchor, int index, int bareTemperatureK) {
        double chance = bareTemperatureK < SNOW_LINE_K ? GIANT_CHANCE_OUTER
                : (bareTemperatureK < COOL_BAND_K ? GIANT_CHANCE_COOL : 0d);
        if (chance <= 0d) {
            return false;
        }
        return CellHash.norm(CellHash.ofBody(seed, anchor.cellCentre(), index, SALT_GIANT)) < chance;
    }

    // ─── The whole derivation ──────────────────────────────────────────────────

    /**
     * The full profile of a body, keyed on the cell it OCCUPIES rather than on its position in a list.
     *
     * <p>That choice is what makes a profile survive a pin. A cell name is durable for the life of the
     * save; a body's index in the generator's output is not — it moves the moment a tuning constant
     * changes the body count, and every planet in the system would then be a different world than the
     * one a player scanned. Metallicity is the deliberate exception: it is a property of the STAR, so it
     * is keyed on the anchor and shared by every body of the system.</p>
     *
     * @param variant         disambiguates bodies that legitimately SHARE a cell — a planet is 0 and its
     *                        moons are 1, 2, … Without it a moon would draw its parent's exact physics,
     *                        because it draws from its parent's cell by construction
     * @param moon            a satellite: never a giant, and drawn from a smaller size law
     * @param orbitalDistance where the body sits, in Advanced Rocketry distance units. A moon takes its
     *                        PARENT's, because what a moon's climate depends on is where the parent is
     */
    public static BodyProfile derive(long seed, GalacticCoord anchor, GalacticCoord bodyCell, int variant,
                                     StellarBody star, boolean moon, int orbitalDistance) {
        GalacticCoord key = bodyCell.cellCentre();
        double metallicity = metallicityOf(seed, anchor);
        int bareTemp = bareTemperature(star, orbitalDistance);
        boolean giant = !moon && isGiantAt(seed, key, variant, bareTemp);

        double radius = radiusOf(seed, key, variant, giant, moon);
        double mass = massOf(seed, key, variant, radius, giant);
        int gravityPercent = gravityPercentOf(mass, radius);
        int pressure = pressureOf(seed, key, variant, mass, radius, bareTemp, giant);
        int temperature = AstronomicalBodyHelper.getAverageTemperature(star,
                Math.max(1, orbitalDistance), pressure);

        PlanetTypePreset preset = PlanetTypes.drawType(pressure, temperature, gravityPercent, giant,
                CellHash.ofBody(seed, key, variant, SALT_TYPE));
        TerrainOption terrain = PlanetTypes.drawTerrain(preset,
                CellHash.ofBody(seed, key, variant, SALT_TERRAIN));

        boolean oxygen = preset != null && preset.allowsOxygen()
                && CellHash.norm(CellHash.ofBody(seed, key, variant, SALT_OXYGEN)) < OXYGEN_CHANCE;
        boolean locked = (preset == null || preset.tidallyLockable()) && !giant
                && tidallyLockedAt(star, orbitalDistance);
        boolean rings = !moon
                && CellHash.norm(CellHash.ofBody(seed, key, variant, SALT_RINGS))
                        < (giant ? RING_CHANCE_GIANT : RING_CHANCE_ROCKY);

        int spin = rotationalPeriodOf(seed, key, variant, giant);

        SystemBodyKind kind = giant ? SystemBodyKind.GAS_GIANT
                : (moon ? SystemBodyKind.MOON : SystemBodyKind.PLANET);
        return new BodyProfile(kind, preset == null ? PlanetTypes.UNCLASSIFIED : preset.name(), preset,
                orbitalDistance, mass, radius, gravityPercent, pressure, temperature, oxygen, locked,
                rings, metallicity, terrain, spin);
    }

    /**
     * How long this body takes to turn once, in ticks.
     *
     * <p>DRAWN, not derived — and that is the honest answer. A planet's spin comes from how it
     * accreted and what has since torqued it; nothing else this derivation knows predicts it. What it
     * replaces was worse than a draw: {@code (1/g)^3 * DEFAULT} made the day a function of SURFACE
     * GRAVITY, which has no bearing on rotation at all, so a half-gravity world got a day eight times
     * longer. A drawn number is honest; a fabricated law that looks derived is not.</p>
     *
     * <p>Log-uniform across the band, so short and long days are equally likely by ratio rather than
     * by difference. Giants spin fast, which IS a real correlation — angular momentum shed to a large
     * envelope — so they take a tighter, faster band. Tidal locking overrides this entirely and is
     * applied where the body is realized.</p>
     */
    static int rotationalPeriodOf(long seed, GalacticCoord key, int variant, boolean giant) {
        double lo = giant ? SPIN_GIANT_MIN : SPIN_ROCKY_MIN;
        double hi = giant ? SPIN_GIANT_MAX : SPIN_ROCKY_MAX;
        double u = CellHash.norm(CellHash.ofBody(seed, key, variant, SALT_SPIN));
        double factor = lo * Math.pow(hi / lo, u);
        long ticks = Math.round(factor * DimensionProperties.DEFAULT_ROTATIONAL_PERIOD);
        return (int) Math.max(1L, Math.min(ticks, Integer.MAX_VALUE));
    }

    // ─── The individual laws ───────────────────────────────────────────────────

    private static double radiusOf(long seed, GalacticCoord cell, int index, boolean giant, boolean moon) {
        double u = CellHash.norm(CellHash.ofBody(seed, cell, index, SALT_RADIUS));
        if (giant) {
            return GIANT_MIN_RADIUS + u * (GIANT_MAX_RADIUS - GIANT_MIN_RADIUS);
        }
        double span = moon ? MOON_RADIUS_SPAN : ROCK_RADIUS_SPAN;
        return ROCK_MIN_RADIUS + Math.pow(u, ROCK_RADIUS_BIAS) * span;
    }

    private static double massOf(long seed, GalacticCoord cell, int index, double radius, boolean giant) {
        if (giant) {
            return JUPITER_MASSES * Math.pow(radius / GIANT_MAX_RADIUS, GIANT_MASS_EXPONENT);
        }
        double density = MIN_DENSITY
                + CellHash.norm(CellHash.ofBody(seed, cell, index, SALT_DENSITY)) * DENSITY_SPAN;
        // M = ρ·R^3.7 rather than ρ·R³: a bigger rocky world compresses its own interior, which is what
        // stops a super-Earth's surface gravity from running away with the cube of its radius.
        return density * Math.pow(radius, ROCK_MASS_EXPONENT);
    }

    private static int gravityPercentOf(double mass, double radius) {
        double g = mass / Math.max(1e-6d, radius * radius);
        double clamped = clamp(g, MIN_GRAVITY_G, DimensionProperties.MAX_GRAVITY / 100d);
        return (int) Math.round(clamped * 100d);
    }

    private static int pressureOf(long seed, GalacticCoord cell, int index, double mass, double radius,
                                  int bareTemperatureK, boolean giant) {
        if (giant) {
            return DimensionProperties.MAX_ATM_PRESSURE;
        }
        double retention = (mass / Math.max(1e-6d, radius))
                / Math.max(0.2d, bareTemperatureK / 288d);
        double scatter = PRESSURE_SCATTER_MIN
                + CellHash.norm(CellHash.ofBody(seed, cell, index, SALT_PRESSURE)) * PRESSURE_SCATTER_SPAN;
        double raw = AstronomicalBodyHelper.ATM_PRESSURE_UNITS_PER_ATMOSPHERE
                * Math.pow(retention / EARTH_RETENTION, RETENTION_EXPONENT) * scatter;
        if (!(raw > 0d) || Double.isNaN(raw)) {
            return DimensionProperties.MIN_ATM_PRESSURE;
        }
        return (int) clamp(Math.round(raw), DimensionProperties.MIN_ATM_PRESSURE,
                DimensionProperties.MAX_ATM_PRESSURE);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
