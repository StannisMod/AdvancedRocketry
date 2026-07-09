package zmaster587.advancedRocketry.space;

/**
 * The world-lifecycle seam between the pure {@link SpaceManager} controller (coord&harr;cell
 * resolution, refcount/LRU pool bookkeeping, GC policy) and the physical slot worlds. The
 * production implementation delegates to {@link SpaceSlotPool}; tests substitute a recording fake so
 * the controller's policy logic is exercised without a live server.
 *
 * <p>All methods run on the server main thread: on 1.12.2 a {@code WorldServer} cannot be driven
 * off-thread - Forge's global event bus, the shared player list / netty, and Valkyrien Skies' own
 * thread guards all assume the server main thread.</p>
 */
public interface SlotBinder {

    /** The pre-registered physical slot dimension ids - the fixed working set (pool size = length). */
    int[] slotDims();

    /**
     * Bind slot {@code dimId} to {@code cellKey} and (re)initialise its world against that cell's
     * store (regenerated if the cell is clean/new, deserialized if it has stored content).
     */
    void load(int dimId, String cellKey);

    /**
     * Flush slot {@code dimId} to its currently bound cell's store and unload the world
     * synchronously. Called only for a cell that must be persisted (dirty); the world's chunks are
     * saved before unload.
     */
    void unload(int dimId);

    /**
     * Discard slot {@code dimId}'s world WITHOUT persisting - used when evicting a clean
     * (never-diverged, regenerable) cell, whose scratch directory is deleted rather than kept.
     */
    void discard(int dimId);

    /**
     * Delete {@code cellKey}'s content from the on-disk cell store (garbage collection). The cell
     * must not be currently loaded.
     */
    void deleteStore(String cellKey);
}
