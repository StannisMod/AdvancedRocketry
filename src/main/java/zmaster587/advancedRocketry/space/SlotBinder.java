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

    /**
     * Whether {@code cellKey} already has persisted content in the store. The controller asks the
     * STORE rather than remembering a flag, so the answer survives a restart: a cell built in during
     * an earlier session must not look regenerable and get discarded on its first eviction of the
     * new session.
     *
     * <p>Defaults to {@code false} so a recording test fake that models no store keeps its existing
     * behaviour (nothing persisted &rArr; everything regenerable).</p>
     */
    default boolean hasStored(String cellKey) {
        return false;
    }

    /**
     * Every cell key with persisted content in the store, including cells not yet visited in this
     * session. Lets GC reach an earlier session's leftovers instead of only what has been touched
     * since startup. Defaults to empty for the same reason as {@link #hasStored}.
     */
    default java.util.List<String> storedCells() {
        return java.util.Collections.emptyList();
    }

    /**
     * Whether slot {@code dimId} has a world right now.
     *
     * <p>A binding is not self-enforcing. The controller keeps a cell bound to its slot after the last
     * occupant leaves, so a revisit is cheap — but on 1.12.2 Forge queues a player-less, chunk-less
     * dimension for unload from {@code ChunkProviderServer.tick()} and removes its world at tick end,
     * with no call through this seam. The controller therefore ASKS before handing out a slot it
     * already believes in, instead of treating its own record as proof of a world.</p>
     *
     * <p>Defaults to {@code true}: a recording test fake models no worlds, so every slot it has ever
     * been asked to load is live for as long as the binding exists.</p>
     */
    default boolean isLive(int dimId) {
        return true;
    }

    /**
     * Whether slot {@code dimId}'s world currently has anyone in it.
     *
     * <p>The controller's refcount is a claim taken by whoever MATERIALIZED a cell, and a body can be
     * in a cell without any of them holding one: a crew member carried in on a ship, a player who
     * walked in behind someone else's claim, a jump that released the origin cell's count one line
     * after dismounting its crew into it. Eviction therefore asks the world who is standing in it
     * rather than trusting a counter to have been incremented — the same "derive it from state, not
     * from transitions" rule the durable aboard record is written by, and for the same reason: a
     * paired claim is only as good as the pairing nobody forgot.</p>
     *
     * <p>Defaults to {@code false}: a recording test fake models no players, so nothing it binds is
     * ever occupied unless it says so.</p>
     */
    default boolean hasOccupants(int dimId) {
        return false;
    }
}
