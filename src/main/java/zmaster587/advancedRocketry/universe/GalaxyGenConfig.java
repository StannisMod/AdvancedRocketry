package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tunable parameters for {@link ClusteredGalaxyGenerator} (universe-model.md &sect;3). All values are balance
 * knobs, never a contract — authored via the optional {@code <galaxyGen>} XML element; every field has a
 * default so {@code <galaxyGen/>} with no attributes is valid.
 *
 * <p>Immutable. The distribution is CLUSTERED: space is partitioned into {@link #minSpacing}-cube
 * "super-cells" (at most one system each — the spacing guarantee), and a coarser blob field grouped
 * {@link #clusterScale} super-cells wide decides which super-cells sit inside a galaxy versus the
 * inter-galaxy {@link #voidFraction void}.</p>
 */
public final class GalaxyGenConfig {

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

    /** Per-super-cell occupancy probability inside a galaxy (before the void mask). */
    public final double density;
    /** Super-cell edge in cells: at most one system per {@code minSpacing}-cube. Minimum system spacing. */
    public final int minSpacing;
    /** Blob field resolution in super-cells — the size of a galaxy cluster. */
    public final int clusterScale;
    /** Fraction of space that is inter-galaxy void (no systems). */
    public final double voidFraction;
    /** Star archetypes sampled by weight when a system is placed (never empty). */
    public final List<StarType> starTypes;

    public GalaxyGenConfig(double density, int minSpacing, int clusterScale, double voidFraction,
                           List<StarType> starTypes) {
        this.density = clamp01(density);
        this.minSpacing = Math.max(1, minSpacing);
        this.clusterScale = Math.max(1, clusterScale);
        this.voidFraction = clamp01(voidFraction);
        this.starTypes = (starTypes == null || starTypes.isEmpty())
                ? defaultStarTypes()
                : Collections.unmodifiableList(new ArrayList<>(starTypes));
    }

    /** A sparse, strongly-clustered default galaxy. */
    public static GalaxyGenConfig defaults() {
        return new GalaxyGenConfig(0.35d, 4, 16, 0.6d, defaultStarTypes());
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

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0d) {
            return 0d;
        }
        return v > 1d ? 1d : v;
    }
}
