package zmaster587.advancedRocketry.dimension;

import net.minecraft.world.WorldType;
import zmaster587.advancedRocketry.AdvancedRocketry;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves what a planet dimension's terrain is ACTUALLY produced by, after the fallbacks: an
 * authored {@link TerrainSource} plus, for {@link TerrainSource#MOD_WORLDTYPE}, the foreign
 * {@link WorldType} it names.
 *
 * <p>This exists as one shared answer rather than one per caller because two very different places
 * need it and must not be able to disagree: the {@code WorldProviderPlanet} that picks the chunk
 * generator, and the per-dimension {@code WorldInfo} that publishes this world's generation identity
 * to third-party code. A planet that generates with a foreign world type while telling everyone it
 * is something else is the defect this class prevents from being re-introduced.</p>
 *
 * <p>Fallbacks are deliberate and quiet-ish: a MOD_WORLDTYPE naming a world type no installed mod
 * registered, or a TEMPLATE with no template path, degrades to {@link TerrainSource#NATIVE} with one
 * warning per dimension, so a mis-authored planet still generates instead of failing to load.</p>
 */
public final class TerrainResolution {

    /** Dimensions already warned about, so a per-chunk or per-lookup resolve cannot spam the log. */
    private static final Set<Integer> warnedDims = Collections.synchronizedSet(new HashSet<Integer>());

    /** The terrain source actually in force — never the authored value if that value was unusable. */
    public final TerrainSource source;
    /**
     * The world type this dimension actually generates with: the foreign one when {@link #source} is
     * {@link TerrainSource#MOD_WORLDTYPE}, otherwise Advanced Rocketry's own planet world type.
     * Null only if AR's world type has not been registered yet (before {@code FMLInitializationEvent}).
     */
    public final WorldType worldType;

    private TerrainResolution(TerrainSource source, WorldType worldType) {
        this.source = source;
        this.worldType = worldType;
    }

    /** @param props this dimension's properties; must not be null (a non-AR dimension has no resolution). */
    public static TerrainResolution of(int dim, DimensionProperties props) {
        TerrainSource requested = props.getTerrainSource();

        if (requested == TerrainSource.MOD_WORLDTYPE) {
            String name = props.getTerrainWorldType();
            WorldType foreign = (name == null || name.isEmpty()) ? null : WorldType.parseWorldType(name);
            if (foreign != null)
                return new TerrainResolution(TerrainSource.MOD_WORLDTYPE, foreign);
            warnOnce(dim, "requests MOD_WORLDTYPE '" + name
                    + "' which is not registered; falling back to NATIVE terrain");
        } else if (requested == TerrainSource.TEMPLATE) {
            String template = props.getTerrainTemplate();
            if (template != null && !template.isEmpty())
                return new TerrainResolution(TerrainSource.TEMPLATE, AdvancedRocketry.planetWorldType);
            warnOnce(dim, "requests TEMPLATE terrain with no template path; falling back to NATIVE");
        }

        return new TerrainResolution(TerrainSource.NATIVE, AdvancedRocketry.planetWorldType);
    }

    private static void warnOnce(int dim, String message) {
        if (warnedDims.add(dim))
            AdvancedRocketry.logger.warn("Planet dimension " + dim + " " + message);
    }
}
