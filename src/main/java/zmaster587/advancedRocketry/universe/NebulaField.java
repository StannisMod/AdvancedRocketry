package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Where the nebulae are — which is: wherever a star cluster still has gas.
 *
 * <p>There is no nebula lattice and no nebula spacing. A cloud is derived from the cluster it wraps
 * and that cluster's residual gas, so the two can never disagree about where they are, and adding
 * nebulae cost the generator no new partition, no new occupancy draw and no new number to invent.
 * A cloud with no stars in it is expressible too — it is a cluster type whose subdivision is 1.</p>
 *
 * <h3>This class is a SEAM, and it has no consumer yet</h3>
 * <p>What a nebula DOES to a ship that flies into it — muffled sensors, drag, concealment, something
 * to mine — is deliberately not here. None of those numbers is ratified, and building a mechanic
 * beside the criteria that would judge it is how a mechanic comes to measure itself. What is here is
 * everything such a mechanic would need: where the clouds are, how big they are, and
 * {@link Nebula#densityAt} for how thick one is at a point.</p>
 */
public final class NebulaField {

    private static final long SALT_NEBULA_GAS = 0x301L;
    private static final long SALT_NEBULA_SPREAD = 0x302L;

    /** How much a cluster's residual gas may vary from the figure its type states. */
    private static final double GAS_VARIATION = 0.35d;

    /** Step of the column integral, in light years. A cloud is tens across, so its profile is resolved. */
    private static final double COLUMN_SAMPLE_STEP_LY = 1d;

    /** Ceiling on that integral's samples: a bound on WORK, not a statement about the sky. */
    private static final int MAX_COLUMN_SAMPLES = 512;

    private final GalaxyGenConfig config;
    private final ClusterField clusters;

    public NebulaField(GalaxyGenConfig config, ClusterField clusters) {
        this.config = (config == null) ? GalaxyGenConfig.defaults() : config;
        this.clusters = clusters;
    }

    /**
     * The cloud wrapping this cluster, or empty when it has none left.
     *
     * <p>An ancient globular has blown its gas away and gets nothing; a molecular cloud is all gas and
     * no stars; the open clusters between them are the interesting middle.</p>
     */
    public Optional<Nebula> nebulaOf(long seed, StarCluster cluster) {
        if (cluster == null) {
            return Optional.empty();
        }
        double stated = cluster.type().nebulaFraction;
        if (!(stated > 0d)) {
            return Optional.empty();
        }
        // The type says how gassy its age is; the draw says how gassy THIS one is.
        double swing = (CellHash.of(seed, cluster.centreSuperX(), cluster.centreSuperY(),
                cluster.centreSuperZ(), SALT_NEBULA_GAS) >>> 11) * 0x1.0p-53;
        double gas = Math.min(1d, Math.max(0d, stated + (swing - 0.5d) * 2d * GAS_VARIATION));
        if (gas < Nebula.MINIMUM_VISIBLE_GAS) {
            return Optional.empty();
        }

        double spreadRoll = CellHash.norm(CellHash.of(seed, cluster.centreSuperX(),
                cluster.centreSuperY(), cluster.centreSuperZ(), SALT_NEBULA_SPREAD));
        double clusterRadiusLy = UniverseScale.lightYearsForCells(
                (double) cluster.radiusSuperCells() * config.minSpacing);
        double radiusLy = clusterRadiusLy * Nebula.spreadFor(spreadRoll);

        long s = config.minSpacing;
        return Optional.of(new Nebula(cluster, Nebula.appearanceFor(gas),
                UniverseScale.lightYearsForCells((double) cluster.centreSuperX() * s),
                UniverseScale.lightYearsForCells((double) cluster.centreSuperY() * s),
                UniverseScale.lightYearsForCells((double) cluster.centreSuperZ() * s),
                radiusLy, gas));
    }

    /** The cloud covering this coarse super-cell, if a cluster covers it and still has one. */
    public Optional<Nebula> nebulaAt(long seed, Galaxy galaxy, long supX, long supY, long supZ) {
        Optional<StarCluster> cluster = clusters.clusterAt(seed, galaxy, supX, supY, supZ);
        return cluster.isPresent() ? nebulaOf(seed, cluster.get()) : Optional.<Nebula>empty();
    }

    /**
     * Every nebula seated in the box of coarse super-cells {@code [min, max]} — what a render or a
     * long-range scan asks, because a cloud is meant to be seen from OUTSIDE it.
     *
     * <p>Enumerated over the CLUSTER lattice rather than per super-cell, so the cost is the number of
     * cluster cells the box crosses and not its volume.</p>
     */
    public List<Nebula> nebulaeInRegion(long seed, Galaxy galaxy, long supMinX, long supMinY,
                                        long supMinZ, long supMaxX, long supMaxY, long supMaxZ) {
        List<Nebula> out = new ArrayList<>();
        if (galaxy == null) {
            return out;
        }
        long spacing = clusters.spacingSuperCells();
        // A cloud reaches beyond its own cluster cell, so the sweep widens by one cell each way.
        for (long cx = Math.floorDiv(supMinX, spacing) - 1L;
                cx <= Math.floorDiv(supMaxX, spacing) + 1L; cx++) {
            for (long cy = Math.floorDiv(supMinY, spacing) - 1L;
                    cy <= Math.floorDiv(supMaxY, spacing) + 1L; cy++) {
                for (long cz = Math.floorDiv(supMinZ, spacing) - 1L;
                        cz <= Math.floorDiv(supMaxZ, spacing) + 1L; cz++) {
                    Optional<StarCluster> cluster = clusters.clusterAtIndex(seed, galaxy, cx, cy, cz);
                    if (!cluster.isPresent()) {
                        continue;
                    }
                    Optional<Nebula> nebula = nebulaOf(seed, cluster.get());
                    if (nebula.isPresent()) {
                        out.add(nebula.get());
                    }
                }
            }
        }
        // The nucleus is not on the cluster lattice, so it is asked for separately — the same
        // exception the cluster tier already makes for it.
        Optional<StarCluster> nucleus = clusters.nucleusOf(seed, galaxy);
        if (nucleus.isPresent()) {
            Optional<Nebula> core = nebulaOf(seed, nucleus.get());
            if (core.isPresent()) {
                out.add(core.get());
            }
        }
        return out;
    }

    /**
     * How much diffuse matter lies ALONG A LINE, in density-light-years — the integral of
     * {@link #densityAtSector} from one cell to another.
     *
     * <p><b>Built once, on purpose.</b> Every consequence of a cloud that involves LOOKING is this
     * number: what a survey loses to a cloud between it and its target, and what a ship inside one
     * loses looking out, are the same integral with the endpoints moved. Two functions computing it
     * would drift in the third decimal and nobody would notice for months.</p>
     *
     * <p>Sampled rather than solved. A closed form exists for one Gaussian, but the line crosses an
     * arbitrary set of clouds seated on a lattice, and the sampled form stays correct when the
     * profile changes. The step is a light year — a cloud is tens of them across, so its profile is
     * resolved many times over — and the sample count is bounded, which is a bound on WORK and not a
     * physical statement.</p>
     */
    public double columnDensityBetween(long seed, Galaxy galaxy, GalacticCoord from,
                                       GalacticCoord to) {
        if (galaxy == null || from == null || to == null) {
            return 0d;
        }
        GalacticCoord a = from.cellCentre();
        GalacticCoord b = to.cellCentre();
        double ax = UniverseScale.lightYearsForCells(a.sectorX());
        double ay = UniverseScale.lightYearsForCells(a.sectorY());
        double az = UniverseScale.lightYearsForCells(a.sectorZ());
        double bx = UniverseScale.lightYearsForCells(b.sectorX());
        double by = UniverseScale.lightYearsForCells(b.sectorY());
        double bz = UniverseScale.lightYearsForCells(b.sectorZ());
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double lengthLy = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (lengthLy <= 0d) {
            return 0d;
        }

        int samples = (int) Math.max(2L, Math.min(MAX_COLUMN_SAMPLES,
                Math.round(lengthLy / COLUMN_SAMPLE_STEP_LY) + 1L));
        double step = lengthLy / (samples - 1);
        double sum = 0d;
        for (int i = 0; i < samples; i++) {
            double t = i / (double) (samples - 1);
            double density = densityAtLightYears(seed, galaxy, ax + dx * t, ay + dy * t, az + dz * t);
            // Trapezoid: the endpoints are half-weighted, so the answer does not depend on which
            // end the walk started from.
            sum += (i == 0 || i == samples - 1) ? density * 0.5d : density;
        }
        return sum * step;
    }

    /** The density at a point stated in light years — what the line integral samples. */
    public double densityAtLightYears(long seed, Galaxy galaxy, double xLy, double yLy, double zLy) {
        long s = config.minSpacing;
        long sectorX = UniverseScale.cellsAt(xLy);
        long sectorY = UniverseScale.cellsAt(yLy);
        long sectorZ = UniverseScale.cellsAt(zLy);
        Optional<Nebula> nebula = nebulaAt(seed, galaxy, Math.floorDiv(sectorX, s),
                Math.floorDiv(sectorY, s), Math.floorDiv(sectorZ, s));
        return nebula.isPresent() ? nebula.get().densityAt(xLy, yLy, zLy) : 0d;
    }

    /**
     * How much diffuse matter lies at this cell, {@code 0}..{@code 1} — the one query a consequence
     * would be written against, whatever the consequence turns out to be.
     */
    public double densityAtSector(long seed, Galaxy galaxy, long sectorX, long sectorY, long sectorZ) {
        long s = config.minSpacing;
        Optional<Nebula> nebula = nebulaAt(seed, galaxy, Math.floorDiv(sectorX, s),
                Math.floorDiv(sectorY, s), Math.floorDiv(sectorZ, s));
        return nebula.isPresent() ? nebula.get().densityAtSector(sectorX, sectorY, sectorZ) : 0d;
    }
}
