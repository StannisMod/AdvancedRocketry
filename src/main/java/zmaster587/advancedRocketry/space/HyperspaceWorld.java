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
 * <p><b>It is not durable yet, and the reason is measured.</b> Its chunks live in a folder named
 * after the world rather than after the per-boot dimension id ({@code SpaceSlotPool#hyperspaceSubfolder}),
 * which is the half of durability this side owns — but the folder is still wiped at each (re)init.
 * See {@link #getOrCreate()} for what a two-boot restart actually measured, and
 * {@code HyperspaceSurvivesARestartE2ETest} for the witness that will go green when it holds.</p>
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
            // Same combined free-id scan the pool uses: an id Forge calls free may still belong to a
            // surface-less Advanced Rocketry body (see SpaceSlotPool#nextFreeDimensionId).
            dimId = SpaceSlotPool.nextFreeDimensionId();
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
            // The folder is wiped before each (re)init, so hyperspace starts every boot as clean void
            // and a hull left by a mid-transit quit is never reloaded as an untracked ghost. Only ever
            // reached when the world is NOT loaded, so this never wipes under a live world.
            //
            // This is NOT the design any more — it is what the design is BLOCKED ON, and the block was
            // measured rather than assumed. Everything a durable hyperspace needs on our side exists:
            // the folder is named after the world rather than after a per-boot dimension id, a transit
            // record carries its lane and its anchor, a restore reclaims the lane and adopts the hull
            // that is standing in it, and the boot reconciliation disposes of the hulls no record
            // claims. What does not hold is the physics mod's own per-world ship data: measured
            // 2026-08-08 across a real two-boot restart, hyperspace's registry serialised EMPTY
            // (140 bytes on disk, against 1350 for a cell holding one ship) while its in-memory
            // registry was answering "one ship" moments earlier. Keeping the folder without that
            // would accumulate hull blocks nothing can ever adopt, so the wipe stays until the ships
            // themselves round-trip.
            SpaceSlotPool.deleteHyperspaceStore();
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
     * Server-stop teardown: release the keep-loaded pin. The dim id is kept STABLE across a same-JVM
     * re-open, which costs nothing and avoids the churn of one leaked dim registration per re-open. It is
     * no longer load-bearing either way: the world's content is keyed by its FOLDER, not by its id. The
     * {@link DimensionType} and the dimension registration both stay JVM-global; a later re-open re-inits
     * the same (freshly-wiped) world.
     */
    public static void reset() {
        if (dimId != Integer.MIN_VALUE) {
            DimensionManager.keepDimensionLoaded(dimId, false);
        }
    }
}
