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

    /** The shared slot {@link DimensionType} (provider = {@link WorldProviderSpaceSlot}). */
    public static DimensionType slotType;

    /**
     * A {@link DimensionType} id guaranteed free right now: one past the highest currently-registered
     * type id. Called at server-start, after every other mod has registered its {@code DimensionType}s,
     * so it never collides — unlike a hardcoded id. Server thread only; call sequentially (the caller
     * registers the type before the next call scans, so two consecutive calls yield distinct ids).
     */
    public static int nextFreeDimensionTypeId() {
        int max = Integer.MIN_VALUE;
        for (DimensionType t : DimensionType.values()) {
            if (t.getId() > max) {
                max = t.getId();
            }
        }
        return max + 1;
    }

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
            // Dynamic type id (scan-max) instead of a hardcoded one, so it never collides with another
            // mod's DimensionType.
            slotType = DimensionType.register(
                    "arspacepoolslot", "arspacepoolslot", nextFreeDimensionTypeId(),
                    WorldProviderSpaceSlot.class, false);
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

    /**
     * Remove slot {@code dimId}'s world WITHOUT persisting (no {@code saveAllChunks}) and delete its
     * bound cell's on-disk folder. Used to evict a CLEAN, regenerable cell: nothing is worth keeping,
     * and the folder is dropped so a later visit regenerates the cell from scratch. Server thread
     * only; the slot must have no occupants.
     */
    public static void discard(int dimId) {
        DimensionManager.keepDimensionLoaded(dimId, false);
        WorldServer world = DimensionManager.getWorld(dimId);
        String cellKey = cellKeyFor(dimId);
        net.minecraft.server.MinecraftServer server = world != null ? world.getMinecraftServer() : null;
        if (world != null) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                    new net.minecraftforge.event.world.WorldEvent.Unload(world));
            DimensionManager.setWorld(dimId, null, server);
        }
        if (server != null && cellKey != null) {
            deleteDir(cellDir(server, cellKey));
        }
    }

    /**
     * Delete {@code cellKey}'s content from the on-disk cell store (garbage collection). The cell must
     * not be currently bound to any slot. No-op if the server or folder is absent.
     */
    public static void deleteStore(String cellKey) {
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null && cellKey != null) {
            deleteDir(cellDir(server, cellKey));
        }
    }

    /**
     * The on-disk chunk subfolder (relative to the world save dir) of an UNBOUND slot dimension — a
     * per-slot scratch folder, and the shared hyperspace world. Single source of truth for the unbound
     * path, shared by {@link WorldProviderSpaceSlot#getSaveFolder()} and {@link #deleteUnboundSlotStore},
     * so the hyperspace wipe provably targets exactly the folder the provider reads/writes.
     */
    public static String unboundSlotSubfolder(int dimId) {
        return "advRocketry/spacepool/slot" + dimId;
    }

    /**
     * Delete an UNBOUND slot dimension's on-disk chunk folder ({@link #unboundSlotSubfolder}). Makes the
     * hyperspace world ephemeral: called before each (re)init so a ship left parked by a mid-transit quit
     * is never reloaded as an untracked ghost — the world regenerates as clean void. No-op if the server
     * or folder is absent. Server thread only.
     */
    public static void deleteUnboundSlotStore(int dimId) {
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            java.io.File worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory();
            deleteDir(new java.io.File(worldDir, unboundSlotSubfolder(dimId)));
        }
    }

    /** The on-disk folder backing {@code cellKey}, matching {@link WorldProviderSpaceSlot#getSaveFolder()}. */
    private static java.io.File cellDir(net.minecraft.server.MinecraftServer server, String cellKey) {
        java.io.File worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory();
        return new java.io.File(worldDir, "advRocketry/spacepool/cell_" + cellKey);
    }

    private static void deleteDir(java.io.File dir) {
        try {
            if (dir.exists()) {
                org.apache.commons.io.FileUtils.deleteDirectory(dir);
            }
        } catch (Exception e) {
            AdvancedRocketry.logger.error("[SPACE] failed to delete cell dir " + dir, e);
        }
    }
}
