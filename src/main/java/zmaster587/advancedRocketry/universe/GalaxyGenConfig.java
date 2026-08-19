package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tunable parameters for {@link ClusteredGalaxyGenerator} (universe-model.md &sect;3). All values are balance
 * knobs, never a contract — authored via the optional {@code <galaxyGen>} XML element; every field has a
 * default so {@code <galaxyGen/>} with no attributes is valid.
 *
 * <p>Immutable, and it describes TWO nested lattices of the same shape:</p>
 * <ul>
 *   <li>{@link #galaxySpacing}-cube <b>galaxy cells</b>, at most one galaxy each, occupied with
 *       probability {@link #galaxyDensity} — a galaxy is a seated object with a type, a radius, an
 *       orientation and a density profile ({@link Galaxy});</li>
 *   <li>{@link #minSpacing}-cube <b>super-cells</b>, at most one system each, occupied with
 *       probability {@link #density} <i>scaled by the owning galaxy's profile at that point</i>.</li>
 * </ul>
 *
 * <p>The galaxy tier replaces an independent per-blob Bernoulli mask (a {@code clusterScale} field and
 * a {@code voidFraction}, both retired). That mask drew each blob cell independently at a probability
 * above the site-percolation threshold, so the "galaxies" it produced were one unbounded sponge: no
 * centre, no radius, no orientation, and no answer to <i>which</i> galaxy a point is in.</p>
 */
public final class GalaxyGenConfig {

    /**
     * Default super-cell edge in cells: the mean distance between neighbouring stars, converted through
     * the chart metric by {@link UniverseScale#DEFAULT_SPACING_CELLS}.
     *
     * <p>It no longer decides how big a system is. A system's extent follows its outermost orbit and is
     * bounded by the separation floor, so this number moves the STARS apart and nothing else — raising
     * it does not inflate a single planet's orbit, and lowering it does not squash one.</p>
     *
     * <p>Deliberately a FIXED constant, never derived from the planet catalog: it partitions procedural
     * space, and deriving it from XML content would silently relocate the whole procedural galaxy on any
     * catalog edit.</p>
     */
    public static final int DEFAULT_MIN_SPACING = UniverseScale.DEFAULT_SPACING_CELLS;

    /**
     * Default galaxy-cell edge in cells — {@link UniverseScale#DEFAULT_GALAXY_SPACING_CELLS}. A
     * {@code long}: the galaxy lattice is five orders coarser than the star lattice.
     */
    public static final long DEFAULT_GALAXY_SPACING = UniverseScale.DEFAULT_GALAXY_SPACING_CELLS;

    /**
     * Fraction of galaxy cells that actually hold a galaxy, before the cosmic web weights them.
     *
     * <p><b>A knob, and deliberately not an observation — unlike its neighbours in this file.</b> The
     * star separation, the galaxy radii and the rogue abundance are all measured quantities; this one
     * is a chance-per-cube standing in for a number density astronomy states per unit volume, and
     * nothing here derives it from a catalogue. It is stated as a knob so the next reader does not
     * mistake it for a reading.
     *
     * <p><b>And it is doing double duty</b>, which is the part worth knowing: half of what it means is
     * "structure we have not built". The cosmic web is a deliberate deferral — {@code webDensity} is
     * the constant 1 — so the clumping that should come from the web is folded into this single
     * uniform chance. Deriving it properly is not a matter of finding a better number; it needs the
     * correlated noise the web needs, and until that exists a measured value would be no more honest
     * than this one.
     */
    public static final double DEFAULT_GALAXY_DENSITY = 0.5d;

    /** A weighted star archetype: a temperature (drives colour) and a size range. */
    public static final class StarType {
        public final int temperature;
        public final float minSize;
        public final float maxSize;
        public final int weight;

        public StarType(int temperature, float minSize, float maxSize, int weight) {
            this.temperature = temperature;
            this.minSize = Math.max(0.1f, minSize);
            this.maxSize = Math.max(this.minSize, maxSize);
            this.weight = Math.max(1, weight);
        }
    }

    /**
     * The radial shape a galaxy's stars are distributed in. It decides the FORM of the profile, not
     * its size: how far the stars reach is the galaxy's radius, which is drawn per type.
     */
    public enum GalaxyProfile {
        /** A flattened exponential disc with a central bulge, and arms when the type has them. */
        DISC,
        /** A round exponential cloud — no plane, no arms, no preferred direction. */
        SPHEROID
    }

    /**
     * A weighted galaxy archetype. The exact analogue of {@link StarType} one level up, and it exists
     * for the same reason: so that <b>size is drawn CONDITIONAL ON TYPE</b>, never independently.
     *
     * <p>Independent draws would produce dwarf galaxies carrying spiral arms and spirals the size of a
     * dwarf — the type and the size of a real galaxy are not two facts, they are one. The weights are
     * what makes "mostly dwarfs, and a spiral is a find" a property of the table rather than a rule
     * somewhere in the generator.</p>
     */
    public static final class GalaxyType {
        /** Short archetype name; a seated galaxy's designation is built from it. */
        public final String name;
        public final GalaxyProfile profile;
        /** Radius band, in light years. A galaxy's radius is DRAWN INSIDE ITS TYPE'S band. */
        public final double minRadiusLy;
        public final double maxRadiusLy;
        /**
         * Scale height as a fraction of the radius — how flat the thing is. A real thin disc is about
         * 1:50, an irregular is a fat slab, a spheroid is nearly round.
         */
        public final double scaleHeightRatio;
        /** Spiral arms, or 0 for a type that has none. */
        public final int armCount;
        /** The rotation curve's asymptotic speed, in km/s — quoted the way astronomy quotes it. */
        public final double rotationSpeedKmS;
        /**
         * Where the rotation curve turns over, as a fraction of the radius. Near 1 the whole galaxy
         * rotates almost as a solid body (little shear); near 0 the curve is flat almost everywhere
         * (strong shear, and arms that wind up).
         */
        public final double coreRadiusFraction;
        /**
         * How many SATELLITE galaxies a galaxy of this type keeps, as a band — the same shape as the
         * radius band above, and stated as two numbers for the same reason: a single maximum would hide
         * the decision of whether a giant may have none at all.
         *
         * <p>Real giants essentially all keep company, so the floor is non-zero for them; a dwarf keeps
         * none, and {@code 0..0} is how that is said. It is deliberately a handful and not the dozens a
         * real catalogue lists: a satellite is a full galaxy resolved on the placement path, so the
         * count is a cost per query, and the ultra-faint dwarfs beyond a handful are not destinations
         * anybody would fly to.</p>
         */
        public final int minSatellites;
        public final int maxSatellites;
        public final int weight;

        public GalaxyType(String name, GalaxyProfile profile, double minRadiusLy, double maxRadiusLy,
                          double scaleHeightRatio, int armCount, double rotationSpeedKmS,
                          double coreRadiusFraction, int minSatellites, int maxSatellites,
                          int weight) {
            this.name = (name == null || name.isEmpty()) ? "GALAXY" : name;
            this.profile = (profile == null) ? GalaxyProfile.DISC : profile;
            this.minRadiusLy = Math.max(1d, minRadiusLy);
            this.maxRadiusLy = Math.max(this.minRadiusLy, maxRadiusLy);
            this.scaleHeightRatio = Math.min(1d, Math.max(0.001d, scaleHeightRatio));
            this.armCount = Math.max(0, armCount);
            this.rotationSpeedKmS = Math.max(0d, rotationSpeedKmS);
            this.coreRadiusFraction = Math.min(1d, Math.max(0.001d, coreRadiusFraction));
            this.minSatellites = Math.max(0, minSatellites);
            this.maxSatellites = Math.max(this.minSatellites, maxSatellites);
            this.weight = Math.max(1, weight);
        }
    }

    /**
     * A weighted STAR-CLUSTER archetype — the same seat one level DOWN, and the third table of the
     * same shape.
     *
     * <p>The stratified lattice reads correctly as randomness but produces no GROUPS, and groups are
     * what a real sky has: the lattice caps density at roughly three times the mean, while an open
     * cluster runs tens of times the field. A cluster is therefore a seated object like a galaxy and
     * like a system, and inside it the star lattice is finer.</p>
     *
     * <p><b>{@code subdivision} is what makes this cheap rather than a graded spacing.</b> The fine
     * lattice divides each coarse super-cell into {@code k³} parts, so it tiles the coarse cells it
     * replaces exactly — there is no boundary pathology and nothing has to be re-proved per ring.
     * Density inside a cluster is {@code k³} times the field.</p>
     */
    public static final class ClusterType {
        public final String name;
        /** {@code k}: how many parts each coarse super-cell is divided into, per axis. */
        public final int subdivision;
        public final double minRadiusLy;
        public final double maxRadiusLy;
        /**
         * How much of its natal cloud a cluster of this type still has, {@code 0}..{@code 1} — which
         * is the same thing as how OLD it is. An open cluster is young and still wrapped in gas; a
         * globular is ancient and has none at all, which is why real globulars are gas-free.
         *
         * <p>It is the only input a nebula needs, and it is why a nebula is not seated separately: a
         * cluster and its cloud are one object at two ages.</p>
         */
        public final double nebulaFraction;
        /**
         * Whether a cluster of this type holds itself together well enough to survive OUTSIDE a
         * galaxy. Only these are seated in the intergalactic void.
         *
         * <p>It is not a gameplay switch but the property that decides the question: a globular is
         * bound tightly enough to have outlived its own galaxy's mergers and is routinely found far
         * out in a halo, while an open cluster disperses in a few hundred million years and a
         * molecular cloud never was bound at all. Something thrown clear of a galaxy has the whole
         * crossing to fall apart in, so only the bound one arrives.</p>
         */
        public final boolean selfBound;
        public final int weight;

        public ClusterType(String name, int subdivision, double minRadiusLy, double maxRadiusLy,
                           double nebulaFraction, boolean selfBound, int weight) {
            this.name = (name == null || name.isEmpty()) ? "CLUSTER" : name;
            this.subdivision = Math.max(1, subdivision);
            this.minRadiusLy = Math.max(0.01d, minRadiusLy);
            this.maxRadiusLy = Math.max(this.minRadiusLy, maxRadiusLy);
            this.nebulaFraction = Math.min(1d, Math.max(0d, nebulaFraction));
            this.selfBound = selfBound;
            this.weight = Math.max(1, weight);
        }
    }

    /**
     * Per-super-cell occupancy probability, before the owning galaxy's profile scales it. It is the
     * density AT A GALAXY'S DENSEST POINT, not an average over space: outside a galaxy the profile is
     * zero and no value here places a system.
     */
    public final double density;
    /**
     * Super-cell edge in cells: at most one system per {@code minSpacing}-cube, i.e. how far apart
     * stars stand. It bounds no orbit — see {@link #DEFAULT_MIN_SPACING}.
     */
    public final int minSpacing;
    /** Galaxy-cell edge in cells: at most one galaxy per {@code galaxySpacing}-cube. */
    public final long galaxySpacing;
    /** Fraction of galaxy cells that hold a galaxy at all — the rest is intergalactic void. */
    public final double galaxyDensity;
    /** Star archetypes sampled by weight when a system is placed (never empty). */
    public final List<StarType> starTypes;
    /** Galaxy archetypes sampled by weight when a galaxy is seated (never empty). */
    public final List<GalaxyType> galaxyTypes;
    /** Star-cluster archetypes sampled by weight when a cluster is seated (never empty). */
    public final List<ClusterType> clusterTypes;
    /**
     * Galaxy cells that hold a galaxy WHATEVER the hash says — every key authored content is declared
     * against. Always contains {@link GalaxyKey#HOME}: a pack that names no galaxy still has one.
     */
    public final List<GalaxyKey> reservedGalaxies;
    /**
     * What the UNBOUND population looks like — how many free-floating worlds there are, what they are
     * made of, and how far a galaxy's ejecta reaches. Never {@code null}; defaults to
     * {@link RogueTuning#physical()}, i.e. to what is measured.
     */
    public final RogueTuning rogue;

    /**
     * Each lattice states its EDGE and then its OCCUPANCY, stars first and galaxies second, so the two
     * (edge, density) pairs cannot be read for one another.
     */
    public GalaxyGenConfig(int minSpacing, double density, long galaxySpacing, double galaxyDensity,
                           List<StarType> starTypes, List<GalaxyType> galaxyTypes) {
        this(minSpacing, density, galaxySpacing, galaxyDensity, starTypes, galaxyTypes, null);
    }

    public GalaxyGenConfig(int minSpacing, double density, long galaxySpacing, double galaxyDensity,
                           List<StarType> starTypes, List<GalaxyType> galaxyTypes,
                           List<GalaxyKey> reservedGalaxies) {
        this.density = clamp01(density);
        this.minSpacing = Math.max(1, minSpacing);
        this.galaxySpacing = Math.max(1L, galaxySpacing);
        this.galaxyDensity = clamp01(galaxyDensity);
        this.starTypes = (starTypes == null || starTypes.isEmpty())
                ? defaultStarTypes()
                : Collections.unmodifiableList(new ArrayList<>(starTypes));
        this.galaxyTypes = (galaxyTypes == null || galaxyTypes.isEmpty())
                ? defaultGalaxyTypes()
                : Collections.unmodifiableList(new ArrayList<>(galaxyTypes));
        this.clusterTypes = defaultClusterTypes();
        List<GalaxyKey> reserved = new ArrayList<>();
        reserved.add(GalaxyKey.HOME);
        if (reservedGalaxies != null) {
            for (GalaxyKey key : reservedGalaxies) {
                if (key != null && !reserved.contains(key)) {
                    reserved.add(key);
                }
            }
        }
        this.reservedGalaxies = Collections.unmodifiableList(reserved);
        this.rogue = RogueTuning.physical();
    }

    private GalaxyGenConfig(GalaxyGenConfig from, RogueTuning rogue) {
        this.density = from.density;
        this.minSpacing = from.minSpacing;
        this.galaxySpacing = from.galaxySpacing;
        this.galaxyDensity = from.galaxyDensity;
        this.starTypes = from.starTypes;
        this.galaxyTypes = from.galaxyTypes;
        this.clusterTypes = from.clusterTypes;
        this.reservedGalaxies = from.reservedGalaxies;
        this.rogue = rogue == null ? RogueTuning.physical() : rogue;
    }

    /**
     * The same configuration with the unbound population retuned — the {@code <galaxyGen>} attributes
     * a pack may state about rogues.
     *
     * <p>A named copy rather than four more constructor parameters, and the same shape
     * {@link #withReservedGalaxies} already uses: what ships is the measured universe, and a pack
     * states only the part it disagrees with.</p>
     */
    public GalaxyGenConfig withRogueTuning(RogueTuning tuning) {
        return new GalaxyGenConfig(this, tuning);
    }

    /**
     * The same configuration, reserving these galaxy cells as well. Authored anchors are discovered
     * while the catalogue is walked, which is after {@code <galaxyGen>} has been read — so the keys
     * they name are folded in here rather than parsed twice.
     */
    public GalaxyGenConfig withReservedGalaxies(List<GalaxyKey> keys) {
        // The rogue tuning is carried over EXPLICITLY. This runs after the catalogue walk, i.e. after
        // <galaxyGen> has already been read, so going through the public constructor — which resets the
        // unbound population to the measured default — would silently discard whatever the pack
        // authored about rogues for every pack that also names a galaxy.
        return new GalaxyGenConfig(minSpacing, density, galaxySpacing, galaxyDensity, starTypes,
                galaxyTypes, keys).withRogueTuning(rogue);
    }

    /**
     * A stable digest of every knob in this configuration — the identity of the universe these
     * parameters describe.
     *
     * <p>What it is FOR: a save records the fingerprint of the configuration it was generated under,
     * and a later load compares. The generator is a pure function of {@code (seed, cell)} <i>and these
     * numbers</i>, so a pack that retunes one of them is not tweaking balance — it is describing a
     * different universe, in which every unpinned system moves. That is invisible without a stamp, and
     * a moved system is discovered by a player arriving somewhere his notes do not match.</p>
     *
     * <p>Stable across JVMs and runs by construction: no {@link Object#hashCode()} anywhere (identity
     * hashes and {@code String.hashCode} are not a promise across versions), doubles rendered through
     * {@link Double#doubleToLongBits} rather than formatted (no locale, no rounding), and every list
     * walked in its declared order — order IS part of the identity, because a weighted table's order
     * decides which archetype a given hash lands on.</p>
     *
     * @return 16 lowercase hex characters of SHA-256 over the canonical rendering — enough that a
     *         collision is not something a pack author will meet, short enough to read out of a log
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("v1;");
        sb.append("minSpacing=").append(minSpacing).append(';');
        sb.append("density=").append(bits(density)).append(';');
        sb.append("galaxySpacing=").append(galaxySpacing).append(';');
        sb.append("galaxyDensity=").append(bits(galaxyDensity)).append(';');
        for (StarType t : starTypes) {
            sb.append("star[").append(t.temperature).append(',').append(bits(t.minSize)).append(',')
                    .append(bits(t.maxSize)).append(',').append(t.weight).append("];");
        }
        for (GalaxyType t : galaxyTypes) {
            sb.append("galaxy[").append(t.name).append(',').append(t.profile).append(',')
                    .append(bits(t.minRadiusLy)).append(',').append(bits(t.maxRadiusLy)).append(',')
                    .append(bits(t.scaleHeightRatio)).append(',').append(t.armCount).append(',')
                    .append(bits(t.rotationSpeedKmS)).append(',').append(bits(t.coreRadiusFraction))
                    .append(',').append(t.minSatellites).append(',').append(t.maxSatellites)
                    .append(',').append(t.weight).append("];");
        }
        for (ClusterType t : clusterTypes) {
            sb.append("cluster[").append(t.name).append(',').append(t.subdivision).append(',')
                    .append(bits(t.minRadiusLy)).append(',').append(bits(t.maxRadiusLy)).append(',')
                    .append(bits(t.nebulaFraction)).append(',').append(t.selfBound).append(',')
                    .append(t.weight).append("];");
        }
        for (GalaxyKey key : reservedGalaxies) {
            sb.append("reserved[").append(key.gx()).append(',').append(key.gy()).append(',')
                    .append(key.gz()).append("];");
        }
        sb.append("rogue[").append(bits(rogue.abundance)).append(',').append(bits(rogue.giantFraction))
                .append(',').append(bits(rogue.ejectaFalloff)).append(']');
        for (RogueType t : rogue.types) {
            sb.append("rogueType[").append(t.name).append(',').append(t.primaryKind).append(',')
                    .append(t.weight).append("];");
        }
        return digest(sb.toString());
    }

    /** The fingerprint of "no procedural generator at all" — an authored-anchors-only universe. */
    public static String noGeneratorFingerprint() {
        return digest("none");
    }

    private static String bits(double v) {
        return Fingerprint.bits(v);
    }

    private static String digest(String canonical) {
        return Fingerprint.hex16(canonical);
    }

    /** A sparse, strongly-clustered default galaxy. */
    public static GalaxyGenConfig defaults() {
        // The occupancy is READ from the metric rather than repeated here: it is half of what decides
        // the mean star separation, and a second copy of it would move the field without moving the
        // constant that claims to state where the field is.
        return new GalaxyGenConfig(DEFAULT_MIN_SPACING, UniverseScale.DEFAULT_STAR_OCCUPANCY,
                DEFAULT_GALAXY_SPACING, DEFAULT_GALAXY_DENSITY, defaultStarTypes(), defaultGalaxyTypes());
    }

    /**
     * The stock star table, weighted by the OBSERVED abundance of each class rather than by a feel for
     * how often one should turn up.
     *
     * <p>Weights are per ten thousand systems, from a solar-neighbourhood census BY NUMBER — which is
     * the census that matters here, because this table is sampled once per seat. (A census by
     * luminosity or by mass gives almost the opposite ordering, and is what makes a blue star feel
     * common: it dominates every photograph of the sky while being nearly absent from the volume.)</p>
     *
     * <table>
     *   <caption>class, share by number, weight</caption>
     *   <tr><td>M red dwarf</td><td>~76 %</td><td>7600</td></tr>
     *   <tr><td>K orange</td><td>~12 %</td><td>1200</td></tr>
     *   <tr><td>G sun-like</td><td>~7.6 %</td><td>760</td></tr>
     *   <tr><td>F/A white</td><td>~3.6 %</td><td>360</td></tr>
     *   <tr><td>B blue</td><td>~0.13 %</td><td>13</td></tr>
     * </table>
     *
     * <p>They do not sum to 10 000, and that is correct rather than sloppy: the remaining ~0.7 % is
     * white and brown dwarfs, which this table does not model, and O stars at ~3&times;10<sup>-5</sup> %
     * are below the resolution of any weight an integer can carry. Weights are relative; a missing
     * class is simply absent, not redistributed.
     *
     * <p><b>What this changed.</b> The previous table read 40/25/20/10/5, i.e. a blue star in one
     * system out of twenty against an observed one in seven hundred and sixty — <b>38&times; too
     * common</b>, against its own comment calling them rare. It flowed downstream too: a star's
     * temperature and size set its habitable zone, so an over-bright field made warm orbits commoner
     * everywhere.
     */
    private static List<StarType> defaultStarTypes() {
        List<StarType> l = new ArrayList<>();
        //                     temp  size band     weight (per 10 000 systems, observed)
        l.add(new StarType(40, 0.6f, 1.0f, 7600));  // M — red dwarfs, three quarters of every sky
        l.add(new StarType(70, 0.8f, 1.2f, 1200));  // K — orange
        l.add(new StarType(100, 0.9f, 1.4f, 760));  // G — sun-like
        l.add(new StarType(150, 1.1f, 1.8f, 360));  // F/A — white
        l.add(new StarType(220, 1.4f, 2.6f, 13));   // B — blue, one system in ~760
        return Collections.unmodifiableList(l);
    }

    /**
     * The stock galaxy table. Weights are the real abundance ordering — dwarfs outnumber giants by two
     * orders — so a spiral is something a player FINDS rather than the default sky.
     */
    /**
     * The stock SPIRAL archetype — the type every partially-specified {@code <galaxyType>} inherits
     * its unwritten attributes from.
     *
     * <p>It exists so those defaults are not a second copy of the numbers below. They were, and the
     * copy went stale the moment the galaxy scale moved: a pack writing
     * {@code <galaxyType thickness="0.05"/>} got a "spiral" 900&ndash;2 200 ly across, an order and a
     * half under every real one, silently and only in the authored path.</p>
     */
    public static GalaxyType stockSpiral() {
        for (GalaxyType t : defaultGalaxyTypes()) {
            if ("Spiral".equals(t.name)) {
                return t;
            }
        }
        throw new IllegalStateException("the stock galaxy table must contain a Spiral");
    }

    private static List<GalaxyType> defaultGalaxyTypes() {
        // The bands are REAL radii, read off a catalogue and stated in light years so they can be
        // checked against one — never a multiple of UniverseScale.REFERENCE_GALAXY_RADIUS_LY. They
        // were once about a thirtieth of these; multiplying that table back up by the same factor is
        // the mistake to avoid, because it gives dwarf galaxies larger than real spirals. Each band
        // is instead the range its own class actually occupies:
        //   dwarf spheroidal  Sculptor ~1 000 ly, Fornax ~2 300 ly
        //   dwarf irregular   SMC ~3 500 ly, LMC ~7 000 ly
        //   spiral            M33 ~15 000 ly, Milky Way 50 000 ly, the largest discs past 60 000 ly
        //   elliptical        M87 ~60 000 ly, the cluster-centre giants far past that
        // scaleHeightRatio is a FRACTION of the radius, so it needs no re-derivation and the heights
        // it now produces are the real ones: a spiral's 0.02 is 1 000 ly at 50 000 ly of radius,
        // which is the disc thickness that makes a galaxy's population come out at 10^11.
        // Satellites: a dwarf keeps none — it IS somebody's satellite — and a giant keeps a handful,
        // never the dozens a real catalogue lists (see minSatellites for why the count is small).
        List<GalaxyType> l = new ArrayList<>();
        //                      name              profile                  radius band (ly)  flatten arms  km/s  core  sats  weight
        l.add(new GalaxyType("Dwarf Spheroidal", GalaxyProfile.SPHEROID, 500d, 3_000d, 0.70d, 0, 20d, 0.90d, 0, 0, 700));
        l.add(new GalaxyType("Dwarf Irregular", GalaxyProfile.DISC, 2_000d, 10_000d, 0.30d, 0, 50d, 0.60d, 0, 0, 290));
        l.add(new GalaxyType("Spiral", GalaxyProfile.DISC, 15_000d, 60_000d, 0.02d, 2, 220d, 0.08d, 1, 3, 7));
        l.add(new GalaxyType("Barred Spiral", GalaxyProfile.DISC, 20_000d, 75_000d, 0.02d, 4, 210d, 0.10d, 1, 4, 2));
        l.add(new GalaxyType("Elliptical", GalaxyProfile.SPHEROID, 30_000d, 150_000d, 0.60d, 0, 40d, 0.50d, 2, 5, 1));
        return Collections.unmodifiableList(l);
    }

    /**
     * The stock cluster table, and every subdivision in it is now the real one.
     *
     * <p><b>Two of the three always were.</b> An open cluster's and a globular's contrast is measured
     * against the FIELD, and the field's density is {@link UniverseScale#MEAN_STAR_SEPARATION_LY} —
     * real, and never compressed. So {@code k = 4} really does put about a thousand stars in a
     * ten-light-year open cluster and {@code k = 14} about a million in a globular, which is what
     * those objects hold.</p>
     *
     * <p><b>The NUCLEUS was the exception, and it no longer is.</b> Its contrast is the one number in
     * this table that is a statement about its whole GALAXY, and the galaxy used to be compressed in
     * radius while the star separation stayed real — so it held of the order of a million stars, and a
     * real nucleus's {@code k = 215} (about 10⁷ times the field) would have put ninety times the
     * galaxy's entire population inside five light years. It was held at {@code k = 25} for that
     * reason, and the reason is gone: a galaxy at its real radius holds ~10¹¹ systems, and 10⁷ times
     * the field over a few light years is the nuclear star cluster a real one has.</p>
     */
    private static List<ClusterType> defaultClusterTypes() {
        List<ClusterType> l = new ArrayList<>();
        //                    name                  k   radius band (ly)  gas  bound  weight
        // A molecular cloud is a cluster whose stars have not formed: it refines nothing (k = 1) and
        // is all gas. That it drops out of the SAME table as the others is the point — a cloud, a
        // young cluster and an ancient one are one sequence, not three features.
        l.add(new ClusterType("Molecular Cloud", 1, 10d, 30d, 1.0d, false, 60));
        l.add(new ClusterType("Open Cluster", 4, 5d, 15d, 0.55d, false, 80));
        l.add(new ClusterType("Globular Cluster", 14, 20d, 40d, 0d, true, 20));
        return Collections.unmodifiableList(l);
    }

    /**
     * The cluster every galaxy has at its own centre — the richest one, and no special case: it is a
     * cluster like the others, drawn at the galaxy's centre instead of on the cluster lattice.
     */
    public static final ClusterType NUCLEUS = new ClusterType("Nucleus", 215, 4d, 8d, 0.4d, true, 1);

    /** Edge of the cube that holds at most one cluster, in light years. */
    public static final double CLUSTER_SPACING_LY = 300d;

    /**
     * Fraction of those cubes that hold a cluster, before the galaxy's own profile scales it.
     *
     * <p>A KNOB, not a reading — the same class as {@link #DEFAULT_GALAXY_DENSITY}: a chance per cube
     * standing in for a number density astronomy states per unit volume. Said out loud so the next
     * reader does not take it for an observation the way the star separation, the galaxy radii and the
     * rogue abundance beside it are.
     */
    public static final double CLUSTER_DENSITY = 0.35d;

    /**
     * The unbound population's tuning: how many free-floating worlds there are, what they are made of,
     * and how far a galaxy's ejecta reaches.
     *
     * <p>Every default here is a MEASURED astronomical quantity rather than a balance choice, because
     * the rest of this layer already is — the star separation, the galaxy radii and the galaxy
     * separation are all real. A pack that wants a different sky changes them through
     * {@code <galaxyGen>}; what ships states what is out there.</p>
     */
    public static final class RogueTuning {

        /**
         * How many unbound worlds the lattice draws for each STAR, at the same point.
         *
         * <p><b>21, and it is an observation.</b> Nine years of MOA-II microlensing put the
         * terrestrial-mass free-floating population at roughly twenty per main-sequence star, and the
         * worlds this generator draws are overwhelmingly rocky, so that is the matching number. The
         * older headline of ~1.8 Jupiter-mass objects per star was retracted by OGLE, which caps that
         * mass range at ~0.25 — see {@link #giantFraction}.</p>
         *
         * <p><b>The lattice SATURATES this, and the saturation is the honest reading rather than a
         * bug.</b> A cube holds at most one seat, so any abundance past {@code 1/density} means "every
         * territory the stars left empty has something in it", which is exactly what twenty per star
         * says when a territory is one star's worth of space. Lowering it below that threshold is what
         * makes the number visible again.</p>
         */
        public final double abundance;

        /**
         * The fraction of unbound worlds massive enough to have kept hydrogen — a giant rather than
         * a rock.
         *
         * <p><b>Far below the ordinary outer-zone giant chance, and for a physical reason</b>: what
         * unbinds a planet is a scattering encounter, and a giant is the body doing the scattering
         * rather than the one thrown out. The number is the ratio of the two measured populations —
         * ~0.25 Jupiter-mass free floaters per star against ~21 terrestrial ones — so about one in
         * eighty. Inheriting the 0.34 that a bound body past the snow line gets would have produced
         * half a free-floating giant per star, two orders above what is seen.</p>
         */
        public final double giantFraction;

        /**
         * How steeply a galaxy's ejecta thins outside it, as a power of the distance in radii.
         *
         * <p>Three: the slope the outer parts of a stellar halo and the intracluster light are
         * measured at, which is what a population thrown out over a Hubble time into a growing volume
         * comes to. Not the disc's exponential — an exponential in units of the radius is dead within
         * a few of them, and the void is twenty-five across.</p>
         */
        public final double ejectaFalloff;

        /** What an unbound seat turns out to hold, by weight (never empty). */
        public final List<RogueType> types;

        public RogueTuning(double abundance, double giantFraction, double ejectaFalloff,
                           List<RogueType> types) {
            this.abundance = (Double.isNaN(abundance) || abundance < 0d) ? 0d : abundance;
            this.giantFraction = clamp01(giantFraction);
            this.ejectaFalloff = (Double.isNaN(ejectaFalloff) || ejectaFalloff <= 0d)
                    ? 3d : ejectaFalloff;
            this.types = (types == null || types.isEmpty())
                    ? defaultRogueTypes() : Collections.unmodifiableList(new ArrayList<>(types));
        }

        /** The measured universe: what the sky actually holds. */
        public static RogueTuning physical() {
            return new RogueTuning(21d, 0.012d, 3d, defaultRogueTypes());
        }
    }

    /**
     * A weighted ROGUE archetype — what an unbound seat turns out to hold. The fourth table of the
     * shape {@link StarType} / {@link GalaxyType} / {@link ClusterType} use, and it exists for the
     * same reason they do: <b>relative abundance is a WEIGHT</b>, so "by falling abundance" is a
     * property of the table rather than a rule somewhere in the generator, and adding a kind of
     * unbound object later is one row instead of another occupancy knob.
     */
    public static final class RogueType {
        public final String name;
        /** What is actually seated — the {@link SystemBodyKind} the anchor's primary body carries. */
        public final SystemBodyKind primaryKind;
        public final int weight;

        public RogueType(String name, SystemBodyKind primaryKind, int weight) {
            this.name = (name == null || name.isEmpty()) ? "ROGUE" : name;
            this.primaryKind = (primaryKind == null) ? SystemBodyKind.ROGUE_PLANET : primaryKind;
            this.weight = Math.max(1, weight);
        }
    }

    /**
     * The stock rogue table, and the ratio in it is measured too.
     *
     * <p>A thrown-out WORLD against a thrown-out STAR is ~21 per star against the few per cent of
     * stars that end up unbound from their galaxy at all — the intragroup population a galaxy group
     * carries, well below the intracluster fractions a rich cluster shows. So a rogue star is about
     * one seat in a thousand, which is what makes meeting a whole lit system out in the void an event
     * rather than routine.</p>
     *
     * <p>A rogue star is a {@link SystemBodyKind#STAR} and nothing else — rogue-ness is a statement
     * about WHERE it stands, not about what it is — so it is fabricated by the ordinary path and gets
     * an ordinary retinue.</p>
     */
    public static List<RogueType> defaultRogueTypes() {
        List<RogueType> l = new ArrayList<>();
        //                     name            what is seated                weight
        l.add(new RogueType("Rogue Planet", SystemBodyKind.ROGUE_PLANET, 1050));
        l.add(new RogueType("Rogue Star", SystemBodyKind.STAR, 1));
        return Collections.unmodifiableList(l);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0d) {
            return 0d;
        }
        return v > 1d ? 1d : v;
    }
}
