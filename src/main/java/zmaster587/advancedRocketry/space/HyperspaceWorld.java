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
 * <p><b>It is DURABLE.</b> Its chunks and the physics mod's own per-world ship registry live in a
 * folder named after the world rather than after the per-boot dimension id
 * ({@code SpaceSlotPool#hyperspaceSubfolder}), so a ship parked here is still parked here after the
 * server has stopped and started again — the id it lands on next boot is free to differ. Two things
 * follow, and both are load-bearing: a restored jump ADOPTS the hull standing in its lane instead of
 * pasting a copy at the far end, and every hull no record claims has to be collected at boot, or an
 * abandoned one would hold its lane for the life of the save. {@code HyperspaceSurvivesARestartE2ETest}
 * is the witness for both.</p>
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
            // Nothing is wiped here. The folder is named after the world rather than after the
            // per-boot dimension id, so a re-init lands on the same content — and that content is
            // the whole of what a jump has to survive: a transit record carries its lane and its
            // anchor, a restore reclaims the lane and adopts the hull standing in it, and the boot
            // reconciliation disposes of the hulls no record claims. A wipe here would delete the
            // world's own data folder along with its chunks, which is where the physics mod's ship
            // registry lives, so keeping the world durable and clearing it at boot are the same
            // switch: the reconciliation is what stops an abandoned hull becoming a ghost.
            DimensionManager.initDimension(dimId);
            world = DimensionManager.getWorld(dimId);
        }
        // Permanent: keep it loaded with no occupant so parked in-transit ships keep ticking.
        DimensionManager.keepDimensionLoaded(dimId, true);
        return world;
    }

    /**
     * The hyperspace world if it is ALREADY loaded, else {@code null} — never creating it.
     *
     * <p>The counterpart of {@link #getOrCreate()}, for a reader whose question is about what is in
     * hyperspace rather than about putting something there. Creating the world as a side effect of
     * inspecting it is not free: it pins the dimension loaded and starts ticking a chunk provider, at
     * a point in the boot the caller did not intend to reach.</p>
     *
     * <p><b>The cost of that honesty falls on the caller, and it is real.</b> "Not loaded" and "holds
     * nothing" are the same answer here, so a reader that has to know what is PARKED — the boot
     * restore, which adopts hulls and collects unclaimed ones — must load hyperspace itself before
     * asking, or it will be told an empty world every time and never notice.</p>
     */
    public static WorldServer getIfLoaded() {
        return dimId == Integer.MIN_VALUE ? null : DimensionManager.getWorld(dimId);
    }

    /**
     * Which dimension hyperspace is <b>on this side</b>, or {@link Integer#MIN_VALUE} when this side
     * does not know yet.
     *
     * <p>A remote client never runs {@link #register()} — the id is server state — so it is told the
     * id once and remembers it through {@link #adoptFromServer(int)}. A local registration wins
     * whenever there is one: on an integrated server this JVM IS the server and its own id is the
     * fact, while an adopted copy is only a message about someone's.</p>
     */
    public static int dimId() {
        return dimId != Integer.MIN_VALUE ? dimId : adoptedDimId;
    }

    /**
     * The hyperspace dim id as the connected server reports it. Consulted only where this side did
     * not register hyperspace itself, i.e. on a remote client.
     *
     * <p>Kept in its own field rather than written into {@link #dimId} so a client that later hosts
     * a world of its own does not start out believing another server's id is registered here — it is
     * not, and {@link #register()} would then skip the registration entirely.</p>
     */
    private static int adoptedDimId = Integer.MIN_VALUE;

    /** Learn the server's hyperspace dim id. {@link Integer#MIN_VALUE} means "none yet" — ignored. */
    public static void adoptFromServer(int id) {
        if (id != Integer.MIN_VALUE) {
            adoptedDimId = id;
        }
    }

    /**
     * Server-stop teardown: release the keep-loaded pin. The dim id is kept STABLE across a same-JVM
     * re-open, which costs nothing and avoids the churn of one leaked dim registration per re-open. It is
     * no longer load-bearing either way: the world's content is keyed by its FOLDER, not by its id. The
     * {@link DimensionType} and the dimension registration both stay JVM-global; a later re-open re-inits
     * the same world, with everything that was parked in it still there.
     */
    public static void reset() {
        if (dimId != Integer.MIN_VALUE) {
            DimensionManager.keepDimensionLoaded(dimId, false);
        }
    }
}
