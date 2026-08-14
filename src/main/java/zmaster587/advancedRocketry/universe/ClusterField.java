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
    private final long spacingSuperCells;
    private final long totalClusterWeight;

    public ClusterField(GalaxyGenConfig config) {
        this.config = (config == null) ? GalaxyGenConfig.defaults() : config;
        this.spacingSuperCells = Math.max(1L,
                superCellsForLightYears(GalaxyGenConfig.CLUSTER_SPACING_LY, this.config.minSpacing));
        long w = 0L;
        for (GalaxyGenConfig.ClusterType t : this.config.clusterTypes) {
            w += t.weight;
        }
        this.totalClusterWeight = Math.max(1L, w);
    }

    /**
     * The cluster this coarse super-cell belongs to, or empty when it is ordinary field.
     *
     * <p>{@code galaxy} is the galaxy that owns the super-cell; a cluster outside a galaxy is not a
     * thing this generator makes, because there would be no stars to gather.</p>
     */
    public Optional<StarCluster> clusterAt(long seed, Galaxy galaxy, long supX, long supY, long supZ) {
        if (galaxy == null) {
            return Optional.empty();
        }
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
        double u = CellHash.norm(CellHash.of(seed, galaxy.cellX(), galaxy.cellY(), galaxy.cellZ(),
                SALT_NUCLEUS_RADIUS));
        GalaxyGenConfig.ClusterType type = GalaxyGenConfig.NUCLEUS;
        double radiusLy = type.minRadiusLy + u * (type.maxRadiusLy - type.minRadiusLy);
        long s = config.minSpacing;
        return Optional.of(new StarCluster(type,
                Math.floorDiv(galaxy.centre().sectorX(), s),
                Math.floorDiv(galaxy.centre().sectorY(), s),
                Math.floorDiv(galaxy.centre().sectorZ(), s),
                superCellsForLightYears(radiusLy, config.minSpacing)));
    }

    /**
     * The cluster seated in cluster cell {@code (cx, cy, cz)}, or empty.
     *
     * <p>Occupancy is scaled by the galaxy's own density profile at the cell, so clusters live where
     * stars live and stop where the galaxy stops — one function, not a second rule.</p>
     */
    public Optional<StarCluster> clusterAtIndex(long seed, Galaxy galaxy, long cx, long cy, long cz) {
        long s = config.minSpacing;
        // The cluster cell's centre, as a sector triple, so the profile is read at a fixed point.
        long centreSuper = spacingSuperCells / 2L;
        long sectorX = (cx * spacingSuperCells + centreSuper) * s;
        long sectorY = (cy * spacingSuperCells + centreSuper) * s;
        long sectorZ = (cz * spacingSuperCells + centreSuper) * s;
        double profile = galaxy.densityAtSector(sectorX, sectorY, sectorZ);
        if (!(profile > 0d)) {
            return Optional.empty();
        }
        if (CellHash.norm(CellHash.of(seed, cx, cy, cz, SALT_CLUSTER_OCC))
                >= Math.min(1d, GalaxyGenConfig.CLUSTER_DENSITY * profile)) {
            return Optional.empty();
        }

        GalaxyGenConfig.ClusterType type = pickType(CellHash.of(seed, cx, cy, cz, SALT_CLUSTER_TYPE));
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
        return Optional.of(new StarCluster(type, cx * spacingSuperCells + ox,
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

    private GalaxyGenConfig.ClusterType pickType(long h) {
        long r = Math.floorMod(h, totalClusterWeight);
        GalaxyGenConfig.ClusterType last = null;
        for (GalaxyGenConfig.ClusterType t : config.clusterTypes) {
            last = t;
            if (r < t.weight) {
                return t;
            }
            r -= t.weight;
        }
        return last; // config.clusterTypes is never empty
    }
}
