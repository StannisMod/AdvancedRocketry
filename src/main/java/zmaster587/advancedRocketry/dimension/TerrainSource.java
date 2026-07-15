package zmaster587.advancedRocketry.dimension;

import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * How a planet dimension's terrain is produced. Orthogonal to {@link DimensionProperties#getGenType()},
 * which still selects the NATIVE sub-flavour (surface / cave / asteroid); Advanced Rocketry planet-ness
 * (atmosphere, gravity, ore) is keyed by dimension and applies in every mode.
 *
 * <ul>
 *   <li>{@link #NATIVE} - Advanced Rocketry's own chunk generators (the historical behaviour).</li>
 *   <li>{@link #MOD_WORLDTYPE} - delegate terrain and biomes to a foreign
 *       {@link net.minecraft.world.WorldType} resolved by name.</li>
 *   <li>{@link #TEMPLATE} - load pre-generated region files verbatim, filling any gaps with void.</li>
 * </ul>
 *
 * Persisted BY NAME (never ordinal) so the constants may be reordered without breaking saves or XML.
 */
public enum TerrainSource {
    NATIVE,
    MOD_WORLDTYPE,
    TEMPLATE;

    // A self-contained logger rather than AdvancedRocketry.logger: loading the mod class triggers Forge
    // bootstrap, which would break pure unit tests of this enum.
    private static final Logger LOGGER = LogManager.getLogger("AdvancedRocketry|Terrain");

    /**
     * Tolerant parse: trims and upper-cases the input. Returns {@link #NATIVE} for null, blank, or
     * unknown input, warning only on a non-blank unknown value. Never throws.
     */
    public static TerrainSource byName(String name) {
        if (name == null)
            return NATIVE;
        String key = name.trim();
        if (key.isEmpty())
            return NATIVE;
        try {
            return valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown terrainSource '" + name + "', defaulting to NATIVE");
            return NATIVE;
        }
    }
}
