package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Server-side controller for the movable-ship space subsystem: it resolves absolute
 * {@link GalacticCoord}s to logical cells, keeps a fixed pool of physical slot worlds bound to the
 * hottest cells (refcount + lazy LRU eviction), and garbage-collects the on-disk cell store.
 *
 * <p>Two tiers, decoupled:</p>
 * <ul>
 *   <li><b>Slot pool</b> - the {@link SlotBinder#slotDims() N} pre-registered worlds that actually
 *       tick. A cell is <i>materialized</i> by binding it to a slot; only materialized cells are
 *       live. N is the direct performance knob.</li>
 *   <li><b>Cell store</b> - a sparse, coord-keyed on-disk record of <i>modified</i> cells only. A
 *       clean (never-diverged, regenerable) cell is discarded on eviction; a dirty one is flushed to
 *       the store and kept until GC removes it.</li>
 * </ul>
 *
 * <p>This class holds only the pure bookkeeping and policy; every world-touching action goes through
 * the injected {@link SlotBinder}. Time (for last-visit / GC age) comes from the injected
 * {@link LongSupplier} clock (server tick count in production). Single-threaded: all methods run on
 * the server main thread.</p>
 */
public final class SpaceManager {

    /** Garbage-collection policy over the on-disk cell store. */
    public enum GcPolicy { AGE, COUNT, BOTH, NEVER }

    /** Tunables (from {@code ARConfiguration} in production; constructed directly in tests). */
    public static final class Config {
        public final GcPolicy gcPolicy;
        /** Max ticks since last visit before an {@code AGE}/{@code BOTH} GC deletes a stored cell. */
        public final long maxAgeTicks;
        /** Max stored-cell count a {@code COUNT}/{@code BOTH} GC trims down to (LRU first). */
        public final int maxStoredCells;

        public Config(GcPolicy gcPolicy, long maxAgeTicks, int maxStoredCells) {
            this.gcPolicy = gcPolicy;
            this.maxAgeTicks = maxAgeTicks;
            this.maxStoredCells = maxStoredCells;
        }
    }

    /** Raised when a cell must be materialized but every slot is bound to an in-use (refcount&gt;0) cell. */
    public static final class PoolExhaustedException extends RuntimeException {
        public PoolExhaustedException(String message) {
            super(message);
        }
    }

    /** Per-cell metadata that outlives a single load (drives eviction flush/discard and GC). */
    private static final class CellMeta {
        long lastVisitTick;
        boolean dirty;   // diverged from its procedural seed since it was last flushed
        boolean stored;  // has content persisted in the on-disk cell store
        boolean claimed; // player-protected (e.g. a built station) - never GC'd
    }

    private final SlotBinder binder;
    private final LongSupplier clock;
    private final Config config;

    /** cellKey &rarr; the slot dim id it is currently materialized in. */
    private final Map<String, Integer> loadedCellToSlot = new HashMap<>();
    /** cellKey &rarr; number of occupants keeping it live. 0 = evict-eligible, still loaded (lazy). */
    private final Map<String, Integer> refCount = new HashMap<>();
    /** cellKey &rarr; persistent metadata. Present for any cell seen since startup. */
    private final Map<String, CellMeta> meta = new HashMap<>();

    public SpaceManager(SlotBinder binder, LongSupplier clock, Config config) {
        this.binder = binder;
        this.clock = clock;
        this.config = config;
    }

    private CellMeta metaOf(String cellKey) {
        return meta.computeIfAbsent(cellKey, k -> new CellMeta());
    }

    /**
     * Resolve {@code coord} to its cell, ensure that cell is live in a slot, and add one occupant.
     * Returns the slot dim id the cell is bound to.
     *
     * @throws PoolExhaustedException if no slot is free and no loaded cell is evict-eligible.
     */
    public int materialize(GalacticCoord coord) {
        String cellKey = coord.cellKey();
        CellMeta m = metaOf(cellKey);
        m.lastVisitTick = clock.getAsLong();

        Integer slot = loadedCellToSlot.get(cellKey);
        if (slot != null) {
            refCount.merge(cellKey, 1, Integer::sum);
            return slot;
        }

        int dimId = acquireSlot(cellKey);
        binder.load(dimId, cellKey);
        loadedCellToSlot.put(cellKey, dimId);
        refCount.put(cellKey, 1);
        return dimId;
    }

    /**
     * Remove one occupant from {@code coord}'s cell. At zero occupants the cell stays loaded but
     * becomes eligible for LRU eviction the next time a slot is needed.
     */
    public void dematerialize(GalacticCoord coord) {
        String cellKey = coord.cellKey();
        Integer count = refCount.get(cellKey);
        if (count == null) {
            return;
        }
        metaOf(cellKey).lastVisitTick = clock.getAsLong();
        if (count <= 1) {
            refCount.put(cellKey, 0);
        } else {
            refCount.put(cellKey, count - 1);
        }
    }

    /** Mark {@code coord}'s cell as diverged from its seed, so eviction flushes it to the store. */
    public void markDirty(GalacticCoord coord) {
        metaOf(coord.cellKey()).dirty = true;
    }

    /** Set/clear the player-protected flag on {@code coord}'s cell (protected cells are never GC'd). */
    public void setClaimed(GalacticCoord coord, boolean claimed) {
        metaOf(coord.cellKey()).claimed = claimed;
    }

    /** Number of cells currently bound to a slot (live). */
    public int loadedCellCount() {
        return loadedCellToSlot.size();
    }

    /** Number of cells with content persisted in the on-disk store. */
    public int storedCellCount() {
        int n = 0;
        for (CellMeta m : meta.values()) {
            if (m.stored) {
                n++;
            }
        }
        return n;
    }

    /** {@code true} iff {@code coord}'s cell is currently materialized in a slot. */
    public boolean isLoaded(GalacticCoord coord) {
        return loadedCellToSlot.containsKey(coord.cellKey());
    }

    /** Pick a free slot, else LRU-evict a refcount-0 loaded cell to free one. */
    private int acquireSlot(String incomingCellKey) {
        int free = firstFreeSlot();
        if (free >= 0) {
            return free;
        }
        // All slots bound: evict the least-recently-visited cell with no occupants.
        String victim = lruEvictableCell();
        if (victim == null) {
            throw new PoolExhaustedException(
                    "no free slot and no evict-eligible cell for " + incomingCellKey
                            + " (all " + binder.slotDims().length + " slots in use)");
        }
        evict(victim);
        int freed = firstFreeSlot();
        if (freed < 0) {
            throw new IllegalStateException("eviction did not free a slot");
        }
        return freed;
    }

    /** A slot dim id not currently bound to any cell, or {@code -1} if the pool is full. */
    private int firstFreeSlot() {
        List<Integer> boundSlots = new ArrayList<>(loadedCellToSlot.values());
        for (int dimId : binder.slotDims()) {
            if (!boundSlots.contains(dimId)) {
                return dimId;
            }
        }
        return -1;
    }

    /** The least-recently-visited loaded cell with no occupants, or {@code null} if none is idle. */
    private String lruEvictableCell() {
        String victim = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Integer> e : refCount.entrySet()) {
            if (e.getValue() != 0 || !loadedCellToSlot.containsKey(e.getKey())) {
                continue;
            }
            long visit = metaOf(e.getKey()).lastVisitTick;
            if (visit < oldest) {
                oldest = visit;
                victim = e.getKey();
            }
        }
        return victim;
    }

    /** Unbind a loaded cell: dirty &rarr; flush to store; clean &rarr; discard its scratch world. */
    private void evict(String cellKey) {
        int dimId = loadedCellToSlot.get(cellKey);
        CellMeta m = metaOf(cellKey);
        if (m.dirty) {
            binder.unload(dimId);   // saves chunks to the cell's store folder
            m.stored = true;
            m.dirty = false;
        } else if (m.stored) {
            binder.unload(dimId);   // unchanged since a prior flush; keep the on-disk copy
        } else {
            binder.discard(dimId);  // regenerable, nothing to persist
        }
        loadedCellToSlot.remove(cellKey);
        refCount.remove(cellKey);
    }

    /**
     * Garbage-collect the on-disk cell store per {@link Config#gcPolicy}. Never touches a loaded or
     * claimed cell. Returns the cell keys deleted from the store.
     */
    public List<String> gc() {
        List<String> deleted = new ArrayList<>();
        if (config.gcPolicy == GcPolicy.NEVER) {
            return deleted;
        }
        long now = clock.getAsLong();
        boolean byAge = config.gcPolicy == GcPolicy.AGE || config.gcPolicy == GcPolicy.BOTH;
        boolean byCount = config.gcPolicy == GcPolicy.COUNT || config.gcPolicy == GcPolicy.BOTH;

        if (byAge) {
            List<String> aged = new ArrayList<>();
            for (Map.Entry<String, CellMeta> e : meta.entrySet()) {
                CellMeta m = e.getValue();
                if (isGcCandidate(e.getKey(), m) && now - m.lastVisitTick > config.maxAgeTicks) {
                    aged.add(e.getKey());
                }
            }
            for (String key : aged) {
                deleteFromStore(key);
                deleted.add(key);
            }
        }

        if (byCount) {
            while (storedCellCount() > config.maxStoredCells) {
                String victim = oldestGcCandidate();
                if (victim == null) {
                    break; // remaining stored cells are all loaded/claimed - protected
                }
                deleteFromStore(victim);
                deleted.add(victim);
            }
        }
        return deleted;
    }

    private boolean isGcCandidate(String cellKey, CellMeta m) {
        return m.stored && !m.claimed && !loadedCellToSlot.containsKey(cellKey);
    }

    private String oldestGcCandidate() {
        String victim = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, CellMeta> e : meta.entrySet()) {
            CellMeta m = e.getValue();
            if (isGcCandidate(e.getKey(), m) && m.lastVisitTick < oldest) {
                oldest = m.lastVisitTick;
                victim = e.getKey();
            }
        }
        return victim;
    }

    private void deleteFromStore(String cellKey) {
        binder.deleteStore(cellKey);
        CellMeta m = meta.get(cellKey);
        if (m != null) {
            m.stored = false;
        }
    }
}
