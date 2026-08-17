package zmaster587.advancedRocketry.universe;

import java.util.Optional;

/**
 * Where the star clusters are: the seat one level BELOW the star lattice, inside a galaxy.
 *
 * <p>Space is partitioned into cluster cells {@code CLUSTER_SPACING_LY} across, measured in COARSE
 * super-cells; at most one cluster per cell, seated with a margin of its own radius so it never
 * straddles a face. That containment is what keeps "which cluster is this super-cell in" a single hash
 * lookup with one answer, exactly as it does one and two levels up.</p>
 *
 * <p>Plus one cluster that is not on the lattice at all: <b>every galaxy has a nucleus at its own
 * centre</b>. It is not a special case in the code either — it is a cluster of a different type,
 * seated at a known place instead of a drawn one.</p>
 *
 * <p>A cluster only exists where its galaxy has stars: occupancy is scaled by the same density profile
 * that placed the systems, so clusters thin out and stop where the galaxy does.</p>
 */
public final class ClusterField {

    // Its own salt space again, clear of the galaxy tier's and of the generator's.
    private static final long SALT_CLUSTER_OCC = 0x201L;
    private static final long SALT_CLUSTER_TYPE = 0x202L;
    private static final long SALT_CLUSTER_RADIUS = 0x203L;
    private static final long SALT_CLUSTER_OX = 0x204L;
    private static final long SALT_CLUSTER_OY = 0x205L;
    private static final long SALT_CLUSTER_OZ = 0x206L;
    private static final long SALT_NUCLEUS_RADIUS = 0x207L;

    private final GalaxyGenConfig config;
    private final GalaxyField galaxies;
    private final long spacingSuperCells;

    /**
     * @param galaxies the tier above — what a cluster cell's occupancy is scaled by. A cluster inside a
     *                 galaxy is scaled by that galaxy's profile; one out in the void is scaled by the
     *                 ejecta halo, which is how a globular can be intergalactic without a second rule
     */
    public ClusterField(GalaxyGenConfig config, GalaxyField galaxies) {
        this.config = (config == null) ? GalaxyGenConfig.defaults() : config;
        this.galaxies = (galaxies == null) ? new GalaxyField(this.config) : galaxies;
        this.spacingSuperCells = Math.max(1L,
                superCellsForLightYears(GalaxyGenConfig.CLUSTER_SPACING_LY, this.config.minSpacing));
    }

    /**
     * The cluster this coarse super-cell belongs to, or empty when it is ordinary field.
     *
     * <p>{@code galaxy} is the galaxy the super-cell is INSIDE, and it may be {@code null}: a cluster
     * out in the intergalactic void is a real object — a globular thrown clear of the galaxy it
     * formed around, still bound to itself. It used to be refused by construction here, on the
     * reasoning that there would be no stars out there to gather; what that missed is that a cluster
     * does not gather the field, it BRINGS its own. Its occupancy is scaled by the material at its own
     * cell, which out there is the ejecta halo — so intergalactic globulars thin out with the void and
     * cluster near the galaxies that threw them, on the same one function that places everything else.</p>
     *
     * <p>A galaxy-less cluster has no NUCLEUS, and that is not a special case either: a nucleus is the
     * cluster at a galaxy's own centre, and there is no galaxy here to have one.</p>
     */
    public Optional<StarCluster> clusterAt(long seed, Galaxy galaxy, long supX, long supY, long supZ) {
        Optional<StarCluster> nucleus = nucleusOf(seed, galaxy);
        if (nucleus.isPresent() && nucleus.get().containsSuperCell(supX, supY, supZ)) {
            return nucleus;
        }
        long cx = Math.floorDiv(supX, spacingSuperCells);
        long cy = Math.floorDiv(supY, spacingSuperCells);
        long cz = Math.floorDiv(supZ, spacingSuperCells);
        Optional<StarCluster> seated = clusterAtIndex(seed, galaxy, cx, cy, cz);
        if (seated.isPresent() && seated.get().containsSuperCell(supX, supY, supZ)) {
            return seated;
        }
        return Optional.empty();
    }

    /** The galactic nucleus: the richest cluster, at the galaxy's own centre. */
    public Optional<StarCluster> nucleusOf(long seed, Galaxy galaxy) {
        if (galaxy == null) {
            return Optional.empty();
        }
        // Keyed on the galaxy's own CENTRE, not on its lattice index: a cube holds a primary and its
        // satellites, and they share that index — so keying on it would give every galaxy in a group the
        // same nucleus, and a satellite's core would be sized by its primary's draw.
        double u = CellHash.norm(CellHash.of(seed, galaxy.centre().sectorX(),
                galaxy.centre().sectorY(), galaxy.centre().sectorZ(), SALT_NUCLEUS_RADIUS));
        GalaxyGenConfig.ClusterType type = GalaxyGenConfig.NUCLEUS;
        double radiusLy = type.minRadiusLy + u * (type.maxRadiusLy - type.minRadiusLy);
        long s = config.minSpacing;
        return Optional.of(new StarCluster(type, nucleusSubdivisionFor(galaxy),
                Math.floorDiv(galaxy.centre().sectorX(), s),
                Math.floorDiv(galaxy.centre().sectorY(), s),
                Math.floorDiv(galaxy.centre().sectorZ(), s),
                superCellsForLightYears(radiusLy, config.minSpacing)));
    }

    /**
     * How finely a galaxy's NUCLEUS divides the lattice &mdash; scaled to the galaxy it is the centre of,
     * never taken flat from the table.
     *
     * <p>Every other cluster's contrast is measured against the FIELD, whose density is real and the same
     * everywhere; a nucleus's is a statement about its own galaxy's POPULATION, so it cannot be one
     * number. The table's {@code k} is the real figure for a reference-sized galaxy (10⁷&times; the field
     * at 10¹¹ stars), and a galaxy's population goes as its radius cubed, so {@code k} goes as the
     * radius: {@code k = k_ref &middot; R / R_ref}. That holds the nucleus at a constant FRACTION of
     * whatever it is the centre of.</p>
     *
     * <p>Measured, and the reason this is derived at all: the flat {@code k = 215} put ~4&middot;10⁷ stars
     * inside a 6-light-year core of a 921-light-year dwarf that holds ~10⁷ altogether &mdash; a nucleus
     * four times its own galaxy. It is the same error the table's {@code k} was once held down to avoid,
     * one level lower, and it became reachable the moment satellite galaxies made small galaxies common.
     * A dwarf's nucleus comes out at {@code k = 4}, i.e. barely a concentration, which is what a real
     * dwarf spheroidal has.</p>
     */
    private static int nucleusSubdivisionFor(Galaxy galaxy) {
        double scaled = GalaxyGenConfig.NUCLEUS.subdivision
                * galaxy.radiusLy() / UniverseScale.REFERENCE_GALAXY_RADIUS_LY;
        return (int) Math.max(1L, Math.min(GalaxyGenConfig.NUCLEUS.subdivision, Math.round(scaled)));
    }

    /**
     * The cluster seated in cluster cell {@code (cx, cy, cz)}, or empty.
     *
     * <p>Occupancy is scaled by the material at the cell, so clusters live where material lives: the
     * galaxy's own profile inside one, and the ejecta halo outside — one function, not a second rule.
     * {@code galaxy} may be {@code null} for a cluster cell out in the void.</p>
     */
    public Optional<StarCluster> clusterAtIndex(long seed, Galaxy galaxy, long cx, long cy, long cz) {
        long s = config.minSpacing;
        // The cluster cell's centre, as a sector triple, so the profile is read at a fixed point.
        long centreSuper = spacingSuperCells / 2L;
        long sectorX = (cx * spacingSuperCells + centreSuper) * s;
        long sectorY = (cy * spacingSuperCells + centreSuper) * s;
        long sectorZ = (cz * spacingSuperCells + centreSuper) * s;
        // Inside a galaxy the caller has already resolved which one, so read it directly rather than
        // walking the cube again; out in the void there is nothing resolved and the halo is the answer.
        double profile = galaxy != null
                ? galaxy.densityAtSector(sectorX, sectorY, sectorZ)
                : galaxies.materialAtSector(seed, sectorX, sectorY, sectorZ).total();
        if (!(profile > 0d)) {
            return Optional.empty();
        }
        if (CellHash.norm(CellHash.of(seed, cx, cy, cz, SALT_CLUSTER_OCC))
                >= Math.min(1d, GalaxyGenConfig.CLUSTER_DENSITY * profile)) {
            return Optional.empty();
        }

        // Out in the void only a SELF-BOUND cluster is seated: an open cluster disperses in a few
        // hundred million years and a molecular cloud never was bound, so neither survives the
        // crossing it would have had to make to be out here. Expressed as a constraint on the DRAW
        // rather than a clamp on its result, which is the same shape the satellite-size and
        // authored-galaxy floors use.
        GalaxyGenConfig.ClusterType type = pickType(CellHash.of(seed, cx, cy, cz, SALT_CLUSTER_TYPE),
                galaxy == null);
        if (type == null) {
            return Optional.empty(); // no type qualifies — an honest answer, not an error
        }
        double radiusFraction = CellHash.norm(CellHash.of(seed, cx, cy, cz, SALT_CLUSTER_RADIUS));
        double radiusLy = type.minRadiusLy + radiusFraction * (type.maxRadiusLy - type.minRadiusLy);
        long radius = superCellsForLightYears(radiusLy, config.minSpacing);

        // Seated with a margin of its own radius, so a cluster never straddles a cluster-cell face and
        // the ownership question stays a single lookup.
        long margin = Math.min(radius, Math.max(0L, (spacingSuperCells - 1L) / 2L));
        long band = Math.max(1L, spacingSuperCells - 2L * margin);
        long ox = margin + Math.floorMod(CellHash.of(seed, cx, cy, cz, SALT_CLUSTER_OX), band);
        long oy = margin + Math.floorMod(CellHash.of(seed, cx, cy, cz, SALT_CLUSTER_OY), band);
        long oz = margin + Math.floorMod(CellHash.of(seed, cx, cy, cz, SALT_CLUSTER_OZ), band);
        // The type's own k: an open cluster's and a globular's contrast is measured against the FIELD,
        // whose density is real and uniform, so it needs no scaling to the galaxy it sits in.
        return Optional.of(new StarCluster(type, type.subdivision, cx * spacingSuperCells + ox,
                cy * spacingSuperCells + oy, cz * spacingSuperCells + oz, radius));
    }

    /** The cluster-lattice edge, in coarse super-cells. */
    public long spacingSuperCells() {
        return spacingSuperCells;
    }

    /** A length in light years as a whole number of coarse super-cells, at least one. */
    private static long superCellsForLightYears(double lightYears, long superCellEdgeCells) {
        long cells = UniverseScale.cellsForLightYears(lightYears);
        return Math.max(1L, cells / Math.max(1L, superCellEdgeCells));
    }

    /**
     * A weighted draw over the cluster table, or {@code null} when nothing qualifies.
     *
     * @param selfBoundOnly restrict to the types that survive outside a galaxy — the weights of the
     *                      rest are then not merely skipped but EXCLUDED from the total, so the
     *                      qualifying types keep their relative abundance instead of the draw falling
     *                      through to whichever one happens to be last
     */
    private GalaxyGenConfig.ClusterType pickType(long h, boolean selfBoundOnly) {
        long total = 0L;
        for (GalaxyGenConfig.ClusterType t : config.clusterTypes) {
            if (!selfBoundOnly || t.selfBound) {
                total += t.weight;
            }
        }
        if (total <= 0L) {
            return null;
        }
        long r = Math.floorMod(h, total);
        GalaxyGenConfig.ClusterType last = null;
        for (GalaxyGenConfig.ClusterType t : config.clusterTypes) {
            if (selfBoundOnly && !t.selfBound) {
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
}
