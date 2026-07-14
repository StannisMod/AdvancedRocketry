package zmaster587.advancedRocketry.space;

import net.minecraft.world.DimensionType;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * The single, permanent, shared <b>hyperspace world</b> that holds every tier-2 ship in transit
 * ("transit hosting"). Unlike the pool {@linkplain SpaceSlotPool slots} it is never
 * rebound or evicted - it is registered once and force-kept-loaded, so parked ships keep ticking (their
 * TEs run, passengers walk) for the whole transit. Ships are spaced across it by {@link HyperspaceTiles}
 * so they never see or collide with one another.
 *
 * <p>Registered upfront by {@link #register()} (a cheap Forge map entry, mirroring the slot pool) with
 * the void {@link WorldProviderSpaceSlot} provider (an all-air world - the transit lanes are the only
 * content); the world itself is loaded lazily by {@link #getOrCreate()} on the first transit. Server main
 * thread only.</p>
 */
public final class HyperspaceWorld {

    private static DimensionType type;
    private static int dimId = Integer.MIN_VALUE;

    private HyperspaceWorld() { }

    /**
     * Register the hyperspace {@link DimensionType} and dimension id (no world loaded — one Forge map
     * entry, like {@link SpaceSlotPool#registerPool}). Idempotent + JVM-global: safe to call every
     * server start; the id survives a single-player re-open. Called upfront from {@code SpaceSubsystem}
     * so the world is never lazily registered mid-transit. Uses a dynamic (scan-max) type id so it never
     * collides with another mod's {@code DimensionType}. Server thread only.
     */
    public static void register() {
        if (type == null) {
            type = DimensionType.register("arhyperspace", "arhyperspace",
                    SpaceSlotPool.nextFreeDimensionTypeId(), WorldProviderSpaceSlot.class, false);
        }
        if (dimId == Integer.MIN_VALUE) {
            dimId = DimensionManager.getNextFreeDimId();
            DimensionManager.registerDimension(dimId, type);
        }
    }

    /**
     * The hyperspace {@link WorldServer}, LOADING it on first use and pinning it loaded. Registration is
     * done upfront by {@link #register()}; this call is the lazy world-init half (an empty-air world is
     * only worth loading once a ship actually transits). If called before {@link #register()} it
     * registers as a fallback (test parity). Returns {@code null} only if the world could not init.
     */
    public static WorldServer getOrCreate() {
        register();
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

    /**
     * Server-stop teardown: release the keep-loaded pin and clear the dim id so a single-player re-open
     * allocates a FRESH hyperspace (a new {@code slot<dimId>} folder). This deliberately does NOT reuse
     * the id: the hyperspace world persists to disk and is not yet ephemeral, so reusing it would reload a
     * ship left parked by a mid-transit quit as an untracked ghost. Cost of the fresh id: one leaked dim
     * registration + one orphan {@code slot<oldId>} folder per same-JVM re-open — harmless (near-empty void
     * files). The {@link DimensionType} stays registered JVM-global.
     */
    public static void reset() {
        if (dimId != Integer.MIN_VALUE) {
            DimensionManager.keepDimensionLoaded(dimId, false);
        }
        dimId = Integer.MIN_VALUE;
    }
}
