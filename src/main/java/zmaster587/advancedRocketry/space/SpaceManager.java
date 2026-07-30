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

    /**
     * Notified when the pool is full and a live (loaded, idle) cell has to be evicted to free a slot -
     * i.e. the working set is saturated. This is the tier-1 pool-pressure overload signal; production
     * wires it to a WARN log (see {@code SpaceSubsystem}). Kept pure: {@link SpaceManager} takes no
     * logger, it only surfaces the event. {@code wasDirty} = the victim was flushed to the store (true)
     * vs. discarded as regenerable (false).
     */
    @FunctionalInterface
    public interface EvictionListener {
        void onForcedEviction(String cellKey, boolean wasDirty);
    }

    private static final EvictionListener NO_EVICTION_LISTENER = (cellKey, wasDirty) -> { };

    /**
     * Per-cell metadata that outlives a single load (drives eviction flush/discard and GC).
     *
     * <p>Only the two genuinely in-memory facts live here. {@code stored} and {@code claimed} are
     * NOT fields: they are DERIVED on demand (from the on-disk store and from the ship ledger
     * respectively), because a field would silently read {@code false} for every cell after a
     * restart. That false answer is destructive rather than merely stale — {@link #evict} would
     * classify a cell a player had built in as regenerable and delete its folder.</p>
     */
    private static final class CellMeta {
        long lastVisitTick;
        boolean dirty;   // diverged from its procedural seed since it was last flushed
    }

    /** Cell keys the ledger reports as occupied by a parked ship — never GC'd. Derived, never stored. */
    private java.util.function.Predicate<String> claimedCells = key -> false;

    /**
     * Install the claimed-cell predicate. A cell is claimed while something the player owns is parked
     * in it, which the ship ledger already knows — so the protection is derived from the ledger
     * rather than tracked as a second, separately-persisted flag that could drift out of step with it.
     */
    public void setClaimedCells(java.util.function.Predicate<String> predicate) {
        this.claimedCells = predicate == null ? key -> false : predicate;
    }

    /** Whether {@code cellKey} has persisted content — asked of the store, never remembered. */
    private boolean isStored(String cellKey) {
        return binder.hasStored(cellKey);
    }

    /** Whether {@code cellKey} is protected from GC — asked of the ledger, never remembered. */
    private boolean isClaimed(String cellKey) {
        return claimedCells.test(cellKey);
    }

    private final SlotBinder binder;
    private final LongSupplier clock;
    private final Config config;
    private final EvictionListener evictionListener;

    /** cellKey &rarr; the slot dim id it is currently materialized in. */
    private final Map<String, Integer> loadedCellToSlot = new HashMap<>();
    /** cellKey &rarr; number of occupants keeping it live. 0 = evict-eligible, still loaded (lazy). */
    private final Map<String, Integer> refCount = new HashMap<>();
    /** cellKey &rarr; persistent metadata. Present for any cell seen since startup. */
    private final Map<String, CellMeta> meta = new HashMap<>();

    public SpaceManager(SlotBinder binder, LongSupplier clock, Config config) {
        this(binder, clock, config, NO_EVICTION_LISTENER);
    }

    public SpaceManager(SlotBinder binder, LongSupplier clock, Config config,
                        EvictionListener evictionListener) {
        this.binder = binder;
        this.clock = clock;
        this.config = config;
        this.evictionListener = evictionListener;
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

    /** {@code true} iff {@code coord}'s cell has diverged from its seed (an eviction will flush it). */
    public boolean isDirty(GalacticCoord coord) {
        CellMeta m = meta.get(coord.cellKey());
        return m != null && m.dirty;
    }

    /** Number of cells currently bound to a slot (live). */
    public int loadedCellCount() {
        return loadedCellToSlot.size();
    }

    /** Number of cells with content persisted in the on-disk store. */
    public int storedCellCount() {
        return binder.storedCells().size();
    }

    /**
     * Export each known cell's last-visit tick so it can be persisted alongside the ship ledger.
     * Visit times drive age-based GC; without persistence every cell would look freshly visited
     * after a restart and the store would never age out.
     */
    public java.util.Map<String, Long> exportVisits() {
        java.util.Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, CellMeta> e : meta.entrySet()) {
            out.put(e.getKey(), e.getValue().lastVisitTick);
        }
        return out;
    }

    /** Restore previously exported last-visit ticks. Cells not mentioned keep their current value. */
    public void importVisits(java.util.Map<String, Long> visits) {
        if (visits == null) {
            return;
        }
        for (Map.Entry<String, Long> e : visits.entrySet()) {
            metaOf(e.getKey()).lastVisitTick = e.getValue();
        }
    }

    /** {@code true} iff {@code coord}'s cell is currently materialized in a slot. */
    public boolean isLoaded(GalacticCoord coord) {
        return loadedCellToSlot.containsKey(coord.cellKey());
    }

    /** Answer for a cell bound to no slot: there is no world to name, and saying so beats naming one
     *  at random. Chosen to be a dimension id nothing can ever register. */
    public static final int UNBOUND_SLOT = Integer.MIN_VALUE;

    /**
     * The slot dim {@code coord}'s cell is materialized in right now, or {@link #UNBOUND_SLOT} if it
     * is bound to no slot.
     *
     * <p>This is the ONE place the cell&rarr;slot binding is decided, and every consumer that needs a
     * ship's dimension reads it from here. Slot ids are minted per boot and re-used as cells come and
     * go, so a copy of one kept anywhere else — in a ledger entry, in a tile, on disk — is a cache
     * that goes stale the first time the pool hands that id to a different cell. What survives is the
     * {@link GalacticCoord}; the dimension is DERIVED from it, never carried alongside it.</p>
     */
    public int slotDimOf(GalacticCoord coord) {
        if (coord == null) {
            return UNBOUND_SLOT;
        }
        Integer slot = loadedCellToSlot.get(coord.cellKey());
        return slot == null ? UNBOUND_SLOT : slot;
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
        // Pool pressure: a live (but idle) bubble is being dropped to make room. Surface it before the
        // eviction resets the dirty flag, so a listener can log the true flush-vs-discard outcome.
        evictionListener.onForcedEviction(victim, metaOf(victim).dirty);
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
            m.dirty = false;
        } else if (isStored(cellKey)) {
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
            for (String key : gcKnownCells()) {
                if (isGcCandidate(key) && now - metaOf(key).lastVisitTick > config.maxAgeTicks) {
                    aged.add(key);
                }
            }
            for (String key : aged) {
                deleteFromStore(key);
                deleted.add(key);
            }
        }

        if (byCount) {
            // The loop condition is now answered by the STORE, not by an in-memory counter, so a
            // delete that silently fails (a file still held open, a permission problem) would leave
            // the count unchanged and the same victim chosen again - an endless loop on the server
            // main thread, i.e. a hung server. Each victim therefore gets exactly one attempt.
            java.util.Set<String> attempted = new java.util.HashSet<>();
            while (storedCellCount() > config.maxStoredCells) {
                String victim = oldestGcCandidate();
                if (victim == null || !attempted.add(victim)) {
                    break; // nothing collectable left, or the store is refusing to shrink
                }
                deleteFromStore(victim);
                deleted.add(victim);
            }
        }
        return deleted;
    }

    /**
     * Every cell GC must consider: those seen since startup PLUS everything still in the on-disk
     * store. The union matters after a restart — an earlier session's cells are absent from the
     * in-memory map, and without the store half they would never be collected at all.
     */
    private java.util.Set<String> gcKnownCells() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(meta.keySet());
        for (String stored : binder.storedCells()) {
            if (!meta.containsKey(stored)) {
                // First sight of a cell this session with no surviving visit record — an older save,
                // or one whose record did not persist. Treat it as seen NOW rather than at tick zero:
                // an unseeded entry reads as infinitely old and would be deleted by the very next
                // age sweep, destroying content purely because we had forgotten about it.
                metaOf(stored).lastVisitTick = clock.getAsLong();
            }
            keys.add(stored);
        }
        return keys;
    }

    private boolean isGcCandidate(String cellKey) {
        return isStored(cellKey) && !isClaimed(cellKey) && !loadedCellToSlot.containsKey(cellKey);
    }

    private String oldestGcCandidate() {
        String victim = null;
        long oldest = Long.MAX_VALUE;
        for (String key : gcKnownCells()) {
            long visit = metaOf(key).lastVisitTick;
            if (isGcCandidate(key) && visit < oldest) {
                oldest = visit;
                victim = key;
            }
        }
        return victim;
    }

    private void deleteFromStore(String cellKey) {
        binder.deleteStore(cellKey);
        meta.remove(cellKey);
    }
}
