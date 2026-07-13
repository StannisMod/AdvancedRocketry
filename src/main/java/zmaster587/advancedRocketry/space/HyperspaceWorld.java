package zmaster587.advancedRocketry.space;

import net.minecraft.world.DimensionType;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * The single, permanent, shared <b>hyperspace world</b> that holds every tier-2 ship in transit
 * (space-model §10 "Transit hosting"). Unlike the pool {@linkplain SpaceSlotPool slots} it is never
 * rebound or evicted - it is registered once and force-kept-loaded, so parked ships keep ticking (their
 * TEs run, passengers walk) for the whole transit. Ships are spaced across it by {@link HyperspaceTiles}
 * so they never see or collide with one another.
 *
 * <p>Registered lazily on first use with the void {@link WorldProviderSpaceSlot} provider (an all-air
 * world - the transit lanes are the only content). Server main thread only.</p>
 */
public final class HyperspaceWorld {

    /** DimensionType id for the hyperspace world. Distinct from the slot pool's ({@code 90}). */
    private static final int HYPERSPACE_TYPE_ID = 91;

    private static DimensionType type;
    private static int dimId = Integer.MIN_VALUE;

    private HyperspaceWorld() { }

    /**
     * The hyperspace {@link WorldServer}, registering + loading it on first call and pinning it loaded.
     * Returns {@code null} only if the world could not be initialised.
     */
    public static WorldServer getOrCreate() {
        if (type == null) {
            type = DimensionType.register("arhyperspace", "arhyperspace", HYPERSPACE_TYPE_ID,
                    WorldProviderSpaceSlot.class, false);
        }
        if (dimId == Integer.MIN_VALUE) {
            dimId = DimensionManager.getNextFreeDimId();
            DimensionManager.registerDimension(dimId, type);
        }
        // Init ONLY when not already loaded: calling initDimension on a live dimension reloads it, which
        // wipes VS's per-world ship registry (a ship crossed here would vanish on the next getOrCreate).
        WorldServer world = DimensionManager.getWorld(dimId);
        if (world == null) {
            DimensionManager.initDimension(dimId);
            world = DimensionManager.getWorld(dimId);
        }
        // Permanent: keep it loaded with no occupant so parked in-transit ships keep ticking.
        DimensionManager.keepDimensionLoaded(dimId, true);
        return world;
    }

    /** The hyperspace dimension id, or {@link Integer#MIN_VALUE} if it has not been created yet. */
    public static int dimId() {
        return dimId;
    }

    /** Server-stop teardown: drop the registration so a single-player re-open re-creates it cleanly. */
    public static void reset() {
        if (dimId != Integer.MIN_VALUE) {
            DimensionManager.keepDimensionLoaded(dimId, false);
        }
        dimId = Integer.MIN_VALUE;
    }
}
