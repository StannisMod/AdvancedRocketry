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

    /** Fraction of galaxy cells that actually hold a galaxy, before the cosmic web weights them. */
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
        public final int weight;

        public ClusterType(String name, int subdivision, double minRadiusLy, double maxRadiusLy,
                           double nebulaFraction, int weight) {
            this.name = (name == null || name.isEmpty()) ? "CLUSTER" : name;
            this.subdivision = Math.max(1, subdivision);
            this.minRadiusLy = Math.max(0.01d, minRadiusLy);
            this.maxRadiusLy = Math.max(this.minRadiusLy, maxRadiusLy);
            this.nebulaFraction = Math.min(1d, Math.max(0d, nebulaFraction));
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
    }

    /**
     * The same configuration, reserving these galaxy cells as well. Authored anchors are discovered
     * while the catalogue is walked, which is after {@code <galaxyGen>} has been read — so the keys
     * they name are folded in here rather than parsed twice.
     */
    public GalaxyGenConfig withReservedGalaxies(List<GalaxyKey> keys) {
        return new GalaxyGenConfig(minSpacing, density, galaxySpacing, galaxyDensity, starTypes,
                galaxyTypes, keys);
    }

    /** A sparse, strongly-clustered default galaxy. */
    public static GalaxyGenConfig defaults() {
        return new GalaxyGenConfig(DEFAULT_MIN_SPACING, 0.35d, DEFAULT_GALAXY_SPACING,
                DEFAULT_GALAXY_DENSITY, defaultStarTypes(), defaultGalaxyTypes());
    }

    private static List<StarType> defaultStarTypes() {
        List<StarType> l = new ArrayList<>();
        l.add(new StarType(40, 0.6f, 1.0f, 40));   // cool red dwarfs — most common
        l.add(new StarType(70, 0.8f, 1.2f, 25));   // orange
        l.add(new StarType(100, 0.9f, 1.4f, 20));  // sol-like yellow
        l.add(new StarType(150, 1.1f, 1.8f, 10));  // white
        l.add(new StarType(220, 1.4f, 2.6f, 5));   // hot blue giants — rare
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
        //                    name                  k   radius band (ly)  gas   weight
        // A molecular cloud is a cluster whose stars have not formed: it refines nothing (k = 1) and
        // is all gas. That it drops out of the SAME table as the others is the point — a cloud, a
        // young cluster and an ancient one are one sequence, not three features.
        l.add(new ClusterType("Molecular Cloud", 1, 10d, 30d, 1.0d, 60));
        l.add(new ClusterType("Open Cluster", 4, 5d, 15d, 0.55d, 80));
        l.add(new ClusterType("Globular Cluster", 14, 20d, 40d, 0d, 20));
        return Collections.unmodifiableList(l);
    }

    /**
     * The cluster every galaxy has at its own centre — the richest one, and no special case: it is a
     * cluster like the others, drawn at the galaxy's centre instead of on the cluster lattice.
     */
    public static final ClusterType NUCLEUS = new ClusterType("Nucleus", 215, 4d, 8d, 0.4d, 1);

    /** Edge of the cube that holds at most one cluster, in light years. */
    public static final double CLUSTER_SPACING_LY = 300d;

    /** Fraction of those cubes that hold a cluster, before the galaxy's own profile scales it. */
    public static final double CLUSTER_DENSITY = 0.35d;

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0d) {
            return 0d;
        }
        return v > 1d ? 1d : v;
    }
}
