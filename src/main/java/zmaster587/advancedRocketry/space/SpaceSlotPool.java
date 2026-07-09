package zmaster587.advancedRocketry.space;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.world.DimensionType;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import zmaster587.advancedRocketry.AdvancedRocketry;

/**
 * A fixed pool of pre-registered empty-space worlds ("slots") that are rebound to different
 * logical space cells at runtime by retargeting where each slot reads and writes chunks — the
 * storage mechanism for the movable-ship space subsystem.
 *
 * <p>Mechanism: a slot's chunk directory is derived from its provider's
 * {@link WorldProviderSpaceSlot#getSaveFolder()}, which this pool points at the BOUND CELL's
 * folder. Rebinding a slot to a new cell = flush + unload the world, then re-init it against the
 * new cell (MC's {@code AnvilSaveHandler} rebuilds the chunk loader from the new
 * {@code getSaveFolder()}; Valkyrien Skies' own per-world save serialises / restores its ships).
 * {@link DimensionManager#unloadWorld(int)} only QUEUES the unload (processed at the end of the
 * server tick), so a rebind is done as {@link #unload(int)} then, a tick later, {@link #load(int,
 * String)} — the caller (test) provides the tick gap.</p>
 */
public final class SpaceSlotPool {

    private SpaceSlotPool() {}

    /** DimensionType id for slot worlds. Distinctive to avoid clashes; adjust if it collides. */
    private static final int SLOT_TYPE_ID = 90;

    /** The shared slot {@link DimensionType} (provider = {@link WorldProviderSpaceSlot}). */
    public static DimensionType slotType;

    /** dimId &rarr; cell key currently bound to that slot ({@code null} = unbound scratch). */
    private static final Map<Integer, String> DIM_TO_CELL = new ConcurrentHashMap<>();

    /** Registered slot dimension ids. */
    private static final List<Integer> SLOT_DIMS = new CopyOnWriteArrayList<>();

    /** The cell key bound to slot {@code dimId}, or {@code null} if unbound. Read by the provider. */
    public static String cellKeyFor(int dimId) {
        return DIM_TO_CELL.get(dimId);
    }

    /** Bind slot {@code dimId} to {@code cellKey} (takes effect on the next {@link #load}). */
    public static void setCell(int dimId, String cellKey) {
        if (cellKey == null) {
            DIM_TO_CELL.remove(dimId);
        } else {
            DIM_TO_CELL.put(dimId, cellKey);
        }
    }

    /** Registered slot dimension ids (snapshot). */
    public static List<Integer> slotDims() {
        return new CopyOnWriteArrayList<>(SLOT_DIMS);
    }

    /**
     * Register the slot {@link DimensionType} (once) and {@code n} fresh slot dimensions (not yet
     * initialised). Returns the new dimension ids. Server thread only.
     */
    public static synchronized int[] registerPool(int n) {
        if (slotType == null) {
            // keepLoaded = false: no spawn-chunk force-load (which lagged the server). Lifecycle is
            // controlled EXPLICITLY (load / synchronous unload); callers must not leave a slot loaded
            // across ticks with no occupant, or Forge's auto-unload discards unsaved changes.
            slotType = DimensionType.register(
                    "arspacepoolslot", "arspacepoolslot", SLOT_TYPE_ID, WorldProviderSpaceSlot.class, false);
        }
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            int id = DimensionManager.getNextFreeDimId();
            DimensionManager.registerDimension(id, slotType);
            SLOT_DIMS.add(id);
            ids[i] = id;
        }
        return ids;
    }

    /**
     * Bind slot {@code dimId} to {@code cellKey} and (re)initialise its world, so the chunk loader
     * is built against the cell's folder. Returns the world (or {@code null} if init failed).
     */
    public static WorldServer load(int dimId, String cellKey) {
        setCell(dimId, cellKey);
        DimensionManager.initDimension(dimId);
        return DimensionManager.getWorld(dimId);
    }

    /**
     * Flush slot {@code dimId} to its CURRENT cell folder and remove its world SYNCHRONOUSLY (the
     * same save + {@code setWorld(null)} sequence Forge's {@code unloadWorlds()} runs at tick end),
     * so a rebind needs no tick gap: {@link #load} on the next line reconstructs the world against
     * the new cell. Server thread only; the slot must have no occupants.
     */
    public static void unload(int dimId) {
        // Clear any "keep loaded" hold (e.g. left by a cross-dim teleport into the slot) so the
        // synchronous removal below is not blocked.
        DimensionManager.keepDimensionLoaded(dimId, false);
        WorldServer world = DimensionManager.getWorld(dimId);
        if (world == null) {
            return;
        }
        try {
            world.saveAllChunks(true, null);
            world.flush();
        } catch (Exception e) {
            AdvancedRocketry.logger.error("[SPACE-SPIKE] flush failed for slot " + dimId, e);
        }
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                new net.minecraftforge.event.world.WorldEvent.Unload(world));
        DimensionManager.setWorld(dimId, null, world.getMinecraftServer());
    }
}
