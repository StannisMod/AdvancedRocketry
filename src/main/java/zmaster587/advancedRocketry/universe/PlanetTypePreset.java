package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import zmaster587.advancedRocketry.util.OreGenProperties;

/**
 * A <b>planet type</b>: the named region of physical parameter space a world can land in, together with
 * everything that follows from being that kind of world.
 *
 * <p>There is no second "subtype" concept — a type IS this preset. One language: it declares the
 * property ranges that admit a world, the weighted list of ways its terrain may be generated, its ore
 * table and its native biome palette. Advanced Rocketry ships a stock set ({@link PlanetTypes}); a pack
 * overrides them or adds its own, and a genuinely new class of world needs no code.</p>
 *
 * <p><b>This object straddles the layer boundary on purpose, and the two halves are read by different
 * layers.</b> The UNIVERSE layer (a pure {@code (seed, cell)} derivation) reads only the numeric
 * admission ranges, {@link #weight()}, {@link #gasGiant()}, {@link #allowsOxygen()} and the
 * {@link #terrain()} weights — none of which touch a world, a registry or a block. The DIMENSION layer
 * reads {@link #biomeIds()}, {@link #oreProperties()}, {@link #seaLevel()} and {@link #oceanBlock()}
 * when it materializes a body into a real dimension. Nothing in the first list may be made to depend on
 * the second, or the derivation stops being answerable from afar — which is the whole reason a scan can
 * describe a world before anyone has been there.</p>
 *
 * <p>Immutable; built through {@link #builder(String)}.</p>
 */
public final class PlanetTypePreset {

    private final String name;
    private final int weight;
    private final int minPressure;
    private final int maxPressure;
    private final int minTemperature;
    private final int maxTemperature;
    private final int minGravity;
    private final int maxGravity;
    private final boolean gasGiant;
    private final boolean allowsOxygen;
    private final boolean tidallyLockable;
    private final int seaLevel;
    private final String oceanBlock;
    private final List<TerrainOption> terrain;
    private final String biomes;
    private final OreGenProperties oreProperties;

    private PlanetTypePreset(Builder b) {
        this.name = b.name;
        this.weight = Math.max(1, b.weight);
        this.minPressure = Math.min(b.minPressure, b.maxPressure);
        this.maxPressure = Math.max(b.minPressure, b.maxPressure);
        this.minTemperature = Math.min(b.minTemperature, b.maxTemperature);
        this.maxTemperature = Math.max(b.minTemperature, b.maxTemperature);
        this.minGravity = Math.min(b.minGravity, b.maxGravity);
        this.maxGravity = Math.max(b.minGravity, b.maxGravity);
        this.gasGiant = b.gasGiant;
        this.allowsOxygen = b.allowsOxygen;
        this.tidallyLockable = b.tidallyLockable;
        this.seaLevel = b.seaLevel;
        this.oceanBlock = b.oceanBlock == null ? "" : b.oceanBlock;
        this.terrain = b.terrain.isEmpty()
                ? Collections.singletonList(TerrainOption.ofNative(0, 1))
                : Collections.unmodifiableList(new ArrayList<>(b.terrain));
        this.biomes = b.biomes == null ? "" : b.biomes.trim();
        this.oreProperties = b.oreProperties;
    }

    /** The type's name — what a scan reports and what a pack overrides by. */
    public String name() {
        return name;
    }

    /** Relative frequency among the presets that ALSO admit a given world. Never zero. */
    public int weight() {
        return weight;
    }

    /** Atmospheric pressure bound, in {@code DimensionProperties} atmosphere-density units (100 = 1 atm). */
    public int minPressure() {
        return minPressure;
    }

    public int maxPressure() {
        return maxPressure;
    }

    /** Surface temperature bound, in KELVIN — the unit {@code averageTemperature} is stored in. */
    public int minTemperature() {
        return minTemperature;
    }

    public int maxTemperature() {
        return maxTemperature;
    }

    /** Surface gravity bound, in PERCENT of Earth's ({@code MIN_GRAVITY}/{@code MAX_GRAVITY} units). */
    public int minGravity() {
        return minGravity;
    }

    public int maxGravity() {
        return maxGravity;
    }

    /** Whether this type describes a body with NO SURFACE — a giant, which is never landed on. */
    public boolean gasGiant() {
        return gasGiant;
    }

    /**
     * Whether a world of this type may draw a breathable atmosphere at all. Oxygen is BIOLOGY, not
     * physics: it is an independent rare roll over a world this flag permits, never a consequence of
     * landing in the right pressure and temperature band.
     */
    public boolean allowsOxygen() {
        return allowsOxygen;
    }

    /**
     * Whether a world of this type can be tidally locked when it orbits close enough to be. A giant is
     * excluded because nobody stands on one, so the permanent-day/permanent-night difficulty axis has
     * nothing to act on.
     */
    public boolean tidallyLockable() {
        return tidallyLockable;
    }

    /** Sea level for a realized world of this type, or {@link #SEA_LEVEL_UNSET} to keep the default. */
    public int seaLevel() {
        return seaLevel;
    }

    /** Registry name of the ocean fluid block, or empty for the default (water). */
    public String oceanBlock() {
        return oceanBlock;
    }

    /** Sentinel for {@link #seaLevel()}: this preset does not move the sea. */
    public static final int SEA_LEVEL_UNSET = -1;

    /** The weighted ways a world of this type may be generated. Never empty. */
    public List<TerrainOption> terrain() {
        return terrain;
    }

    /**
     * This type's native biome palette, in the SAME authored form as a planet's {@code <biomeIds>}
     * element: a comma-separated list of {@code name;weight} or {@code id;weight} entries, empty for
     * "let the world derive its own from its climate".
     *
     * <p>It is kept as the raw authored string rather than resolved ids because a biome's numeric id is
     * assigned at registration time and differs between modsets — and because one format with one
     * parser means a preset and a planet can never disagree about what an entry means.</p>
     */
    public String biomes() {
        return biomes;
    }

    /** This type's ore table, or {@code null} to fall back to the climate matrix. */
    public OreGenProperties oreProperties() {
        return oreProperties;
    }

    /**
     * Whether a world at {@code pressure} / {@code temperatureKelvin} / {@code gravityPercent} lands
     * inside this type's declared region, and agrees with it about having a surface.
     *
     * <p>Bounds are INCLUSIVE at both ends, so adjacent presets authored to touch ({@code max="175"}
     * and {@code min="175"}) both admit the boundary rather than leaving a world with no type at all.
     * Overlap is expected and resolved by a weighted draw — see {@link PlanetTypes}.</p>
     */
    public boolean admits(int pressure, int temperatureKelvin, int gravityPercent, boolean isGasGiant) {
        return isGasGiant == gasGiant
                && pressure >= minPressure && pressure <= maxPressure
                && temperatureKelvin >= minTemperature && temperatureKelvin <= maxTemperature
                && gravityPercent >= minGravity && gravityPercent <= maxGravity;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String toString() {
        return "PlanetTypePreset[" + name + " w=" + weight + " p=" + minPressure + ".." + maxPressure
                + " T=" + minTemperature + ".." + maxTemperature + " g=" + minGravity + ".." + maxGravity
                + (gasGiant ? " giant" : "") + ']';
    }

    /** Mutable builder — the authored form, used by both the stock table and the XML reader. */
    public static final class Builder {
        private final String name;
        private int weight = 10;
        private int minPressure;
        private int maxPressure = 1600;
        private int minTemperature;
        private int maxTemperature = 5000;
        private int minGravity;
        private int maxGravity = 400;
        private boolean gasGiant;
        private boolean allowsOxygen;
        private boolean tidallyLockable = true;
        private int seaLevel = SEA_LEVEL_UNSET;
        private String oceanBlock = "";
        private final List<TerrainOption> terrain = new ArrayList<>();
        private String biomes = "";
        private OreGenProperties oreProperties;

        private Builder(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public Builder weight(int w) {
            this.weight = w;
            return this;
        }

        public Builder pressure(int min, int max) {
            this.minPressure = min;
            this.maxPressure = max;
            return this;
        }

        public Builder temperature(int min, int max) {
            this.minTemperature = min;
            this.maxTemperature = max;
            return this;
        }

        public Builder gravity(int min, int max) {
            this.minGravity = min;
            this.maxGravity = max;
            return this;
        }

        public Builder gasGiant(boolean g) {
            this.gasGiant = g;
            return this;
        }

        public Builder allowsOxygen(boolean o) {
            this.allowsOxygen = o;
            return this;
        }

        public Builder tidallyLockable(boolean t) {
            this.tidallyLockable = t;
            return this;
        }

        public Builder seaLevel(int level) {
            this.seaLevel = level;
            return this;
        }

        public Builder oceanBlock(String registryName) {
            this.oceanBlock = registryName;
            return this;
        }

        public Builder terrain(TerrainOption option) {
            if (option != null) {
                this.terrain.add(option);
            }
            return this;
        }

        /** The raw {@code <biomeIds>} palette string — see {@link PlanetTypePreset#biomes()}. */
        public Builder biomes(String authoredList) {
            this.biomes = authoredList;
            return this;
        }

        public Builder ores(OreGenProperties ores) {
            this.oreProperties = ores;
            return this;
        }

        public PlanetTypePreset build() {
            return new PlanetTypePreset(this);
        }
    }
}
