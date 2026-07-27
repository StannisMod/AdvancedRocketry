package zmaster587.advancedRocketry.navigation;

import java.util.Optional;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.UniverseRegistry;

/**
 * What a brand-new memory crystal already knows.
 *
 * <p>A first crystal is not blank. It carries the address of the world the pilot came from, plus the
 * places the setting treats as common knowledge — the bodies a pack author marked as known. Without
 * that, a player's first navigation computer would have nowhere at all to point, and the only way to
 * get a first address would be to fly blind to a coordinate typed at random.</p>
 *
 * <p>Everything seeded here is recorded at {@link InfoTier#TELESCOPE}: common knowledge is knowing a
 * place exists, not having surveyed it.</p>
 */
public final class CrystalSeeding {

    private CrystalSeeding() {
    }

    /**
     * The starter set of addresses for {@code world}'s server: the home world's own coordinate, and
     * every authored-known body outside the home system. Empty when the universe registry is not up —
     * a crystal made before the world is ready is simply blank, never broken.
     */
    public static CrystalMemory starterFor(World world) {
        CrystalMemory memory = new CrystalMemory();
        if (world == null) {
            return memory;
        }
        MinecraftServer server = world.getMinecraftServer();
        UniverseRegistry registry = server == null ? null : UniverseRegistry.get(server);
        if (registry == null) {
            return memory;
        }
        long now = world.getTotalWorldTime();

        GalacticCoord home = coordOf(registry, 0);
        if (home != null) {
            memory.record(new CrystalEntry(home, nameOf(0), SystemBodyKind.PLANET,
                    InfoTier.TELESCOPE, now));
        }

        DimensionManager dims = DimensionManager.getInstance();
        if (dims.knownPlanets == null) {
            return memory;
        }
        for (Integer dimId : dims.knownPlanets) {
            if (dimId == null || dimId == 0) {
                continue;
            }
            GalacticCoord coord = coordOf(registry, dimId);
            if (coord == null) {
                continue;
            }
            // Home-system bodies are deliberately NOT seeded: inside the home system knowledge is
            // innate (the tier-1 selector already lists them) and needs no crystal to carry it.
            if (home != null && home.cellKey().equals(coord.cellKey())) {
                continue;
            }
            memory.record(new CrystalEntry(coord, nameOf(dimId), SystemBodyKind.PLANET,
                    InfoTier.TELESCOPE, now));
        }
        return memory;
    }

    private static GalacticCoord coordOf(UniverseRegistry registry, int dimId) {
        Optional<GalacticCoord> coord = registry.coordForPlanet(dimId);
        return coord.isPresent() ? coord.get() : null;
    }

    private static String nameOf(int dimId) {
        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(dimId);
        return props == null || props.getName() == null ? "" : props.getName();
    }
}
