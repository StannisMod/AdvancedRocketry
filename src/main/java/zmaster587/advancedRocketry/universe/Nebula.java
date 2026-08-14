package zmaster587.advancedRocketry.universe;

/**
 * A nebula: the diffuse cloud a star cluster is wrapped in.
 *
 * <p><b>It is not seated separately, and that is the design.</b> A molecular cloud, the young cluster
 * that condenses out of it and the ancient cluster that has blown it away are ONE object at three
 * ages — so a nebula is derived from a {@link StarCluster} and its type's residual gas, and there is
 * no second lattice, no second spacing number and no way for a cloud and its cluster to disagree
 * about where they are.</p>
 *
 * <h3>What it is for today, and what it is a seam for</h3>
 * <p>Today a cluster is a pure refinement of the star lattice: it has no property anything outside it
 * can observe, so it can only be discovered by counting stars. A nebula is what makes a cluster a
 * LANDMARK rather than a statistical fact.</p>
 *
 * <p><b>{@link #densityAt} is the whole seam.</b> Every consequence a nebula could ever have — a
 * sensor it muffles, a drag it imposes, something it conceals, something a ship mines out of it — is a
 * function of how thick it is at a point. That function exists now and is tested; what does NOT exist
 * is any consumer of it, deliberately: none of those numbers is ratified, and inventing them
 * alongside the thing they judge is how a mechanic ends up measuring itself.</p>
 *
 * <h3>Diffuse matter is NOT a body</h3>
 * <p>A nebula has no cell name, is not a destination, and does not participate in one-real-body-per-
 * cell. It is the same category as a system's comet cloud: <i>attribution reads names, not matter</i>,
 * so a nebula may freely overlap whatever it lies across.</p>
 *
 * <p>Immutable value type.</p>
 */
public final class Nebula {

    /**
     * How much wider than its cluster a nebula reaches. Real clouds are far larger than the cluster
     * inside them — Orion is about twelve light years across around a cluster of one.
     */
    private static final double MIN_SPREAD = 1.5d;
    private static final double MAX_SPREAD = 3d;

    /** Below this much residual gas a cluster has no cloud left worth drawing. */
    static final double MINIMUM_VISIBLE_GAS = 0.05d;

    /**
     * What a nebula looks like — DERIVED from how much gas is left, never drawn, because the three
     * appearances are one age sequence and not three options.
     *
     * <p>Youngest first: the cloud is dark and molecular while its stars are still forming inside it;
     * once they are burning, the hottest of them ionise what is left and it emits; once the gas is
     * blown clear, the remaining dust merely reflects.</p>
     */
    public enum Appearance {
        /** Thick and cold: it blocks the light behind it rather than making any of its own. */
        DARK,
        /** Ionised by the stars inside it, and shining because of them. */
        EMISSION,
        /** Thin dust, lit by whatever is nearby. */
        REFLECTION
    }

    private final StarCluster cluster;
    private final Appearance appearance;
    private final double centreXLy;
    private final double centreYLy;
    private final double centreZLy;
    private final double radiusLy;
    private final double peakDensity;

    public Nebula(StarCluster cluster, Appearance appearance, double centreXLy, double centreYLy,
                  double centreZLy, double radiusLy, double peakDensity) {
        this.cluster = cluster;
        this.appearance = appearance;
        this.centreXLy = centreXLy;
        this.centreYLy = centreYLy;
        this.centreZLy = centreZLy;
        this.radiusLy = Math.max(0.01d, radiusLy);
        this.peakDensity = Math.min(1d, Math.max(0d, peakDensity));
    }

    /** The cluster this cloud belongs to — the same object at a different age. */
    public StarCluster cluster() {
        return cluster;
    }

    public Appearance appearance() {
        return appearance;
    }

    /** Its centre in light years, in the static frame. It shares its cluster's. */
    public double centreXLy() {
        return centreXLy;
    }

    public double centreYLy() {
        return centreYLy;
    }

    public double centreZLy() {
        return centreZLy;
    }

    /** How far it reaches, in light years. Wider than the cluster inside it. */
    public double radiusLy() {
        return radiusLy;
    }

    /** How thick it is at its densest, {@code 0}..{@code 1}. */
    public double peakDensity() {
        return peakDensity;
    }

    /**
     * How thick this nebula is at a point, {@code 0}..{@code 1} — zero outside its radius.
     *
     * <p><b>This is the seam.</b> A Gaussian falloff, so a cloud has no edge to see: it thins out,
     * which is what diffuse matter does and what any consequence built on it will want. The
     * appearance decides how it is drawn; this decides how much of it there is.</p>
     */
    public double densityAt(double xLy, double yLy, double zLy) {
        double dx = xLy - centreXLy;
        double dy = yLy - centreYLy;
        double dz = zLy - centreZLy;
        double rSq = dx * dx + dy * dy + dz * dz;
        if (rSq > radiusLy * radiusLy) {
            return 0d;
        }
        double scale = radiusLy / 2d;
        return peakDensity * Math.exp(-rSq / (scale * scale));
    }

    /** The same reading at a cell name — the form the rest of the layer asks in. */
    public double densityAtSector(long sectorX, long sectorY, long sectorZ) {
        return densityAt(UniverseScale.lightYearsForCells(sectorX),
                UniverseScale.lightYearsForCells(sectorY),
                UniverseScale.lightYearsForCells(sectorZ));
    }

    /** Whether a point is inside this nebula at all. */
    public boolean contains(double xLy, double yLy, double zLy) {
        double dx = xLy - centreXLy;
        double dy = yLy - centreYLy;
        double dz = zLy - centreZLy;
        return dx * dx + dy * dy + dz * dz <= radiusLy * radiusLy;
    }

    /**
     * How wide a cloud of {@code spread} reaches around a cluster of {@code clusterRadiusLy}, and how
     * dense it is at its centre, given the residual gas. Static so the seating code and a test can
     * agree without one of them re-deriving it.
     */
    static double spreadFor(double fraction) {
        return MIN_SPREAD + Math.min(1d, Math.max(0d, fraction)) * (MAX_SPREAD - MIN_SPREAD);
    }

    /** The appearance a cloud with this much gas left has. One number, three ages, in order. */
    static Appearance appearanceFor(double fraction) {
        if (fraction >= 0.7d) {
            return Appearance.DARK;
        }
        return fraction >= 0.3d ? Appearance.EMISSION : Appearance.REFLECTION;
    }

    @Override
    public String toString() {
        return "Nebula[" + appearance + " r=" + (long) radiusLy + "ly d=" + peakDensity
                + " around " + cluster + "]";
    }
}
