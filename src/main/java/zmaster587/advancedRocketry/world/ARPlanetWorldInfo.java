package zmaster587.advancedRocketry.world;

import net.minecraft.world.WorldType;
import net.minecraft.world.storage.DerivedWorldInfo;
import net.minecraft.world.storage.WorldInfo;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.dimension.TerrainResolution;

/**
 * The {@link WorldInfo} an Advanced Rocketry dimension publishes, replacing the plain
 * {@link DerivedWorldInfo} vanilla installs on every secondary world.
 *
 * <p>It answers exactly two questions per dimension instead of per save — the world type and the
 * generator options string — and inherits the delegation of everything else, so nothing about the
 * shared level state changes. Two questions, because those two are what a third-party
 * {@link WorldType} reads when it identifies and configures itself, and Advanced Rocketry cannot
 * patch a foreign generator's read sites. Vanilla's {@code DerivedWorldInfo} answers both about the
 * OVERWORLD: {@code setTerrainType} is an empty method there, so a planet's attempt to stamp its own
 * is silently dropped, and {@code getGeneratorOptions} is never overridden at all, so the string is
 * always empty.</p>
 *
 * <p><b>Why it is installed in the constructor</b> and not from a world event: {@code WorldServer}'s
 * constructor calls {@code provider.setWorld(this)} and then {@code createChunkProvider()} before it
 * returns, and {@code WorldProvider.setWorld} caches both of these values into private fields. A
 * {@code WorldInfo} swapped in later — at {@code WorldEvent.Load}, say — is already too late to
 * reach the biome provider or the chunk generator, which is the whole point of having it.</p>
 *
 * <p>Values are read live from {@link DimensionProperties} rather than snapshotted: the properties
 * are the source of truth, and a copy taken at construction would go stale the moment a dimension's
 * terrain is re-authored.</p>
 */
public class ARPlanetWorldInfo extends DerivedWorldInfo {

    private final int dimension;
    private final WorldInfo delegate;

    public ARPlanetWorldInfo(WorldInfo delegate, int dimension) {
        super(delegate);
        this.delegate = delegate;
        this.dimension = dimension;
    }

    /** The dimension this info speaks for. */
    public int getDimension() {
        return dimension;
    }

    /**
     * Replaces {@code world}'s vanilla {@link DerivedWorldInfo} with a per-dimension one, if this is
     * an Advanced Rocketry dimension that still carries the shared-overworld info. Idempotent, and a
     * no-op for every world it is not about: the client, the overworld, and any dimension AR did not
     * create keep exactly the info they had.
     *
     * @return whether an info was installed by this call
     */
    public static boolean installIfNeeded(net.minecraft.world.World world) {
        if (!(world instanceof net.minecraft.world.WorldServer))
            return false;
        if (world.provider == null)
            return false;
        WorldInfo current = world.getWorldInfo();
        // Only vanilla's shared-overworld info is replaced. Anything else is either the overworld's
        // real WorldInfo, our own (already installed), or another mod's — none of them ours to swap.
        if (!(current instanceof DerivedWorldInfo) || current instanceof ARPlanetWorldInfo)
            return false;
        int dim = world.provider.getDimension();
        if (dim == 0 || !DimensionManager.getInstance().isDimensionCreated(dim))
            return false;
        world.worldInfo = new ARPlanetWorldInfo(current, dim);
        return true;
    }

    @Override
    public WorldType getTerrainType() {
        TerrainResolution resolved = resolve();
        if (resolved == null || resolved.worldType == null)
            return delegate.getTerrainType();
        return resolved.worldType;
    }

    /**
     * Kept a no-op like the superclass. The per-dimension world type is derived from
     * {@link DimensionProperties}, so accepting a write here would create a second source of truth
     * that only the writer could see — and every existing caller of this setter is passing the value
     * this class already derives.
     */
    @Override
    public void setTerrainType(WorldType type) {
    }

    @Override
    public String getGeneratorOptions() {
        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(dimension);
        return props == null ? delegate.getGeneratorOptions() : props.getTerrainGeneratorOptions();
    }

    private TerrainResolution resolve() {
        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(dimension);
        return props == null ? null : TerrainResolution.of(dimension, props);
    }
}
