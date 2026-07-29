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
 * places the setting treats as common knowledge. Without that, a player's first navigation computer
 * would have nowhere at all to point, and the only way to get a first address would be to fly blind
 * to a coordinate typed at random.</p>
 *
 * <p>What counts as common knowledge is {@code planetsMustBeDiscovered}'s question, and it is asked
 * here: with discovery OFF (the default) every authored body is seeded, because nothing in that
 * regime is meant to need discovering; with it ON, only the bodies the pack author marked known.
 * Either way the home body's own cell is skipped — see the loop.</p>
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
                    InfoTier.TELESCOPE, now, 0));
        }

        DimensionManager dims = DimensionManager.getInstance();
        // WHICH bodies count as common knowledge is the discovery flag's own question, and until now
        // this seeding never asked it. With planetsMustBeDiscovered=false nothing in the game is
        // supposed to need discovering - the rocket destination gate and the station list both read
        // it exactly that way - so a first crystal carries every body the pack authored. With the
        // flag on, only the bodies the author marked known are common knowledge, which is the older
        // behaviour and stays.
        Iterable<Integer> candidates =
                zmaster587.advancedRocketry.api.ARConfiguration.getCurrentConfig().planetsMustBeDiscovered
                        ? dims.knownPlanets
                        : java.util.Arrays.asList(dims.getRegisteredDimensions());
        if (candidates == null) {
            return memory;
        }
        for (Integer dimId : candidates) {
            if (dimId == null || dimId == 0) {
                continue;
            }
            GalacticCoord coord = coordOf(registry, dimId);
            if (coord == null) {
                continue;
            }
            // Skipped: bodies sharing the HOME BODY'S OWN CELL - the home world itself and its moons,
            // which a crystal would only be repeating. NOT "the home system": a system spans a
            // neighbourhood of cells with every planet at a cell of its own
            // (UniverseRegistry.systemBodiesAt), so its other planets are ordinary jump targets and
            // are seeded like any other. (This comment used to say "home-system bodies", which reads
            // as though a system were one cell and intra-system jumps did not exist - they are
            // exactly what Milestone 1's script asks the player to fly.)
            if (home != null && home.cellKey().equals(coord.cellKey())) {
                continue;
            }
            // The dim id is the entry's IDENTITY: bodies orbit, so the coordinate recorded here is
            // where this one stood at seeding time and nothing more. A pick aims at the body.
            memory.record(new CrystalEntry(coord, nameOf(dimId), SystemBodyKind.PLANET,
                    InfoTier.TELESCOPE, now, dimId));
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
