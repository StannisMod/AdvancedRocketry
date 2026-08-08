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

    /**
     * A dimension id nothing in this game already owns — the id every space dimension (a pool slot,
     * hyperspace) is minted from.
     *
     * <p>Forge's own {@code getNextFreeDimId} is not enough, because Advanced Rocketry keeps a SECOND
     * id space Forge cannot see: every catalogued body has a dimension id, but only the ones with a
     * surface are registered with Forge — a gas giant is a real body, addressable and jumpable to,
     * whose id Forge therefore reports as free. Taking it hands one number to two owners: the
     * universe registry goes on describing dim 3 as a body of the home system while the world behind
     * dim 3 is an empty space slot, so a ship that flies to that body "lands" in a void. Asking AR's
     * own {@code DimensionManager} asks BOTH registries at once, which is the only way to get an
     * honest answer.</p>
     *
     * <p>Server thread only, and non-reserving: the caller must register the id it is handed before
     * asking again.</p>
     */
    public static int nextFreeDimensionId() {
        int id = zmaster587.advancedRocketry.dimension.DimensionManager.getInstance().getNextFreeDim(2);
        if (id == zmaster587.advancedRocketry.api.Constants.INVALID_PLANET) {
            // AR's scan is bounded; fall back rather than refuse to register a pool at all.
            return DimensionManager.getNextFreeDimId();
        }
        return id;
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
     * Register the slot {@link DimensionType} (once) and ensure the pool holds at least {@code n}
     * slot dimensions, returning the pool's dimension ids. <b>Idempotent:</b> a pool already
     * registered in this JVM is REUSED — a second call mints nothing and returns the existing ids.
     *
     * <p>Idempotence is what makes this safe to call from more than one place in a single JVM (the
     * production server-start hook and, in a harness run, a test that drives that hook itself).
     * {@code DimensionManager} registration is JVM-global, so minting a second pool would not merely
     * waste ids — it would shift the slot ids out from under everything already bound to the first.
     * A caller that genuinely wants {@code n} ADDITIONAL scratch dimensions must say so explicitly
     * via {@link #registerAdditionalSlots(int)}.</p>
     */
    public static synchronized int[] registerPool(int n) {
        if (!SLOT_DIMS.isEmpty()) {
            int[] existing = new int[SLOT_DIMS.size()];
            for (int i = 0; i < existing.length; i++) {
                existing[i] = SLOT_DIMS.get(i);
            }
            // Re-broadcast anyway: the caller's contract is "after this returns, the pool is
            // registered AND every online client knows it", and a late second call may be the first
            // one with players online.
            broadcastSlotDims();
            return existing;
        }
        return registerAdditionalSlots(n);
    }

    /**
     * Register the slot {@link DimensionType} (once) and {@code n} FRESH slot dimensions (not yet
     * initialised), appending them to the pool. Returns the newly minted dimension ids — never
     * previously registered ones. Server thread only.
     *
     * <p>This is the non-idempotent primitive: each call grows the pool. Use {@link #registerPool}
     * unless you specifically need dimensions disjoint from whatever is already registered.</p>
     */
    public static synchronized int[] registerAdditionalSlots(int n) {
        if (slotType == null) {
            // keepLoaded = false: no spawn-chunk force-load (which lagged the server). Lifecycle is
            // controlled EXPLICITLY (load / synchronous unload). This used to carry a warning that a slot
            // left loaded across ticks with no occupant would be taken by Forge's auto-unload - which is
            // exactly what the controller does, on purpose, because eviction there is lazy. The warning
            // described a real hazard nobody could obey; load() now takes a per-dimension keep-loaded hold
            // instead, so a bound slot is not eligible for that sweep at all.
            // Dynamic type id (scan-max) instead of a hardcoded one, so it never collides with another
            // mod's DimensionType.
            slotType = DimensionType.register(
                    "arspacepoolslot", "arspacepoolslot", nextFreeDimensionTypeId(),
                    WorldProviderSpaceSlot.class, false);
        }
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            int id = nextFreeDimensionId();
            DimensionManager.registerDimension(id, slotType);
            SLOT_DIMS.add(id);
            ids[i] = id;
        }
        // Sync the (grown) pool to every online client BEFORE anything can move a player into a fresh
        // slot — the client half of slot-dim registration. Registration at server start broadcasts to
        // nobody (no players yet); late joiners are covered by the login sync in SpaceSubsystem.
        broadcastSlotDims();
        return ids;
    }

    /** Send the current slot-dim snapshot to every online player. Safe no-op with no server/players. */
    public static void broadcastSlotDims() {
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server == null) {
                return;
            }
            zmaster587.advancedRocketry.network.PacketSlotDimSync sync =
                    zmaster587.advancedRocketry.network.PacketSlotDimSync.current();
            if (sync.isEmpty()) {
                return;
            }
            for (net.minecraft.entity.player.EntityPlayerMP p : server.getPlayerList().getPlayers()) {
                zmaster587.libVulpes.network.PacketHandler.sendToPlayer(sync, p);
            }
        } catch (Throwable t) {
            AdvancedRocketry.logger.warn("[SPACE] slot-dim client sync failed", t);
        }
    }

    /**
     * Bind slot {@code dimId} to {@code cellKey} and (re)initialise its world, so the chunk loader
     * is built against the cell's folder. Returns the world (or {@code null} if init failed).
     */
    public static WorldServer load(int dimId, String cellKey) {
        setCell(dimId, cellKey);
        DimensionManager.initDimension(dimId);
        // Hold the world for as long as the cell is bound to this slot. Without the hold, Forge's
        // tick-end sweep takes any slot that is player-less and has run out of chunks - which is the
        // ordinary state of a cell whose last occupant has left, since eviction here is deliberately
        // lazy. The controller's binding then names a dimension with no world, and every reader that
        // derives a ship's world from it gets null. Cleared again by unload / discard, so the pool's
        // own rebind still works; the shared hyperspace world holds itself the same way.
        DimensionManager.keepDimensionLoaded(dimId, true);
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
     * Whether {@code cellKey} has content in the on-disk cell store — i.e. its folder exists.
     *
     * <p>This is the DERIVED answer to "has this cell ever been persisted", and it is deliberately
     * read from the filesystem rather than from an in-memory flag: an in-memory flag is empty after
     * a restart, which would make a cell a player has BUILT IN look regenerable and get its folder
     * deleted on the first eviction of the new session. The folder is the durable truth, so it is
     * the thing to ask. Same derive-don't-store discipline as the world seed.</p>
     */
    public static boolean hasStoredCell(String cellKey) {
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || cellKey == null) {
            return false;
        }
        return hasRegionData(cellDir(server, cellKey));
    }

    /**
     * Whether a cell folder actually holds saved chunks, rather than merely existing.
     *
     * <p>The distinction is the whole point: Minecraft's save handler {@code mkdirs()} a dimension's
     * chunk folder the moment it builds a chunk loader for it, so the folder springs into existence on
     * the first LOAD — before anything has been written and even for a cell that turns out to be empty
     * void. Treating "the folder is there" as "there is content here" would make every cell ever
     * visited look permanently worth keeping, which quietly disables the eviction of regenerable
     * cells. Region files are only written by an actual save, so they are the honest witness.</p>
     */
    private static boolean hasRegionData(java.io.File cellFolder) {
        java.io.File[] regions = new java.io.File(cellFolder, "region").listFiles();
        if (regions == null) {
            return false;
        }
        for (java.io.File f : regions) {
            if (f.isFile() && f.length() > 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every cell key with content in the on-disk store, recovered by scanning the store folder for
     * {@code cell_<key>} entries. Lets garbage collection see cells that were persisted by an
     * EARLIER session and have not been visited yet in this one — without this the store grows
     * without bound across restarts, because the in-memory metadata only knows cells seen since
     * startup. Empty when the server or the store folder is absent.
     */
    public static java.util.List<String> storedCellKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return keys;
        }
        java.io.File worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory();
        java.io.File[] entries = new java.io.File(worldDir, "advRocketry/spacepool").listFiles();
        if (entries == null) {
            return keys;
        }
        for (java.io.File f : entries) {
            // Same "region files, not a bare folder" test as hasStoredCell - an empty folder left by a
            // load is not a stored cell and must not be reported as one.
            if (f.isDirectory() && f.getName().startsWith("cell_") && hasRegionData(f)) {
                keys.add(f.getName().substring("cell_".length()));
            }
        }
        return keys;
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
     * The on-disk chunk subfolder of the shared HYPERSPACE world — a constant, named after what the
     * world IS rather than after the dimension id it happens to hold this boot.
     *
     * <p>That distinction is the whole of hyperspace's durability. Its id is minted per boot by a
     * free-id scan, so a folder named after the id relocates the world's content on any boot where
     * the scan lands elsewhere: the ships would still be on disk and the world would come back empty
     * beside them. The pool's cells already work this way — {@code cell_<key>} — for the same
     * reason, and this is the same fix applied to the one world that was still keyed by its id.</p>
     */
    public static String hyperspaceSubfolder() {
        return "advRocketry/hyperspace";
    }

    /**
     * Delete an UNBOUND slot dimension's on-disk chunk folder ({@link #unboundSlotSubfolder}). Makes the
     * hyperspace world ephemeral: called before each (re)init so a ship left parked by a mid-transit quit
     * is never reloaded as an untracked ghost — the world regenerates as clean void. No-op if the server
     * or folder is absent. Server thread only.
     */
    public static void deleteHyperspaceStore() {
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            java.io.File worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory();
            deleteDir(new java.io.File(worldDir, hyperspaceSubfolder()));
        }
    }

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
