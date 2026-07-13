package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.SlotBinder;
import zmaster587.advancedRocketry.space.SpaceManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Contract tests for the space controller's pure bookkeeping and policy: coord&harr;cell resolution,
 * refcount + lazy LRU pool binding, dirty-flush vs. clean-discard on eviction, and the GC policies.
 * World lifecycle is stubbed by a recording {@link SlotBinder}; time is a manual clock - so these pin
 * the controller's DECISIONS (which slot, evict which cell, flush or discard, GC which) without a
 * live server.
 */
public class SpaceManagerTest {

    /** A cell coordinate in sector {@code (s,0,0)} (each distinct s => a distinct cell). */
    private static GalacticCoord cell(long s) {
        return GalacticCoord.ofSectorLocal(s, 0L, 0L, 0L, 0L, 0L);
    }

    /** Recording binder: captures every world-lifecycle call so tests can assert on the decisions. */
    private static final class FakeBinder implements SlotBinder {
        final int[] dims;
        final List<String> loads = new ArrayList<>();      // "dimId:cellKey"
        final List<Integer> unloads = new ArrayList<>();
        final List<Integer> discards = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();

        FakeBinder(int... dims) { this.dims = dims; }

        @Override public int[] slotDims() { return dims; }
        @Override public void load(int dimId, String cellKey) { loads.add(dimId + ":" + cellKey); }
        @Override public void unload(int dimId) { unloads.add(dimId); }
        @Override public void discard(int dimId) { discards.add(dimId); }
        @Override public void deleteStore(String cellKey) { deletes.add(cellKey); }
    }

    /** Mutable tick source. */
    private static final class Clock {
        long tick;
        long now() { return tick; }
    }

    private static SpaceManager mgr(FakeBinder binder, Clock clock, SpaceManager.Config cfg) {
        return new SpaceManager(binder, clock::now, cfg);
    }

    private static SpaceManager.Config never() {
        return new SpaceManager.Config(SpaceManager.GcPolicy.NEVER, 0, 0);
    }

    // -- materialize / refcount ----------------------------------------------

    @Test
    public void materializeLoadsIntoAFreeSlotAndCountsOne() {
        FakeBinder binder = new FakeBinder(10, 11);
        Clock clock = new Clock();
        SpaceManager m = mgr(binder, clock, never());

        int dim = m.materialize(cell(5));

        assertTrue("must bind one of the pool slots", dim == 10 || dim == 11);
        assertEquals(1, binder.loads.size());
        assertEquals(dim + ":" + cell(5).cellKey(), binder.loads.get(0));
        assertTrue(m.isLoaded(cell(5)));
        assertEquals(1, m.loadedCellCount());
    }

    @Test
    public void materializeSameCellReusesSlotWithoutReloading() {
        FakeBinder binder = new FakeBinder(10, 11);
        SpaceManager m = mgr(binder, new Clock(), never());

        int first = m.materialize(cell(5));
        int second = m.materialize(cell(5));

        assertEquals(first, second);
        assertEquals("no second load for an already-live cell", 1, binder.loads.size());
        assertEquals(1, m.loadedCellCount());
    }

    @Test
    public void dematerializeToZeroKeepsCellLoaded() {
        FakeBinder binder = new FakeBinder(10, 11);
        SpaceManager m = mgr(binder, new Clock(), never());

        m.materialize(cell(5));
        m.materialize(cell(5)); // refcount 2
        m.dematerialize(cell(5));
        m.dematerialize(cell(5)); // refcount 0

        assertTrue("zero occupants => still loaded (lazy eviction)", m.isLoaded(cell(5)));
        assertTrue(binder.unloads.isEmpty());
        assertTrue(binder.discards.isEmpty());
    }

    // -- LRU eviction & pool exhaustion --------------------------------------

    @Test
    public void fullPoolEvictsLeastRecentlyVisitedIdleCell() {
        FakeBinder binder = new FakeBinder(10, 11);
        Clock clock = new Clock();
        SpaceManager m = mgr(binder, clock, never());

        clock.tick = 1; m.materialize(cell(1));
        clock.tick = 2; m.materialize(cell(2));
        clock.tick = 3; m.dematerialize(cell(1)); // cell1 idle, lastVisit 3
        clock.tick = 4; m.dematerialize(cell(2)); // cell2 idle, lastVisit 4
        clock.tick = 5; int dim = m.materialize(cell(3));

        assertFalse("cell1 (older last-visit) evicted", m.isLoaded(cell(1)));
        assertTrue("cell2 (newer) retained", m.isLoaded(cell(2)));
        assertTrue("cell3 now live", m.isLoaded(cell(3)));
        // cell1 was clean => discarded, and its freed slot re-bound to cell3.
        assertEquals(1, binder.discards.size());
        assertEquals(dim + ":" + cell(3).cellKey(), binder.loads.get(binder.loads.size() - 1));
    }

    @Test
    public void poolExhaustedWhenEveryCellHasOccupants() {
        FakeBinder binder = new FakeBinder(10); // pool of 1
        SpaceManager m = mgr(binder, new Clock(), never());

        m.materialize(cell(1)); // refcount 1, never released
        try {
            m.materialize(cell(2));
            fail("expected PoolExhaustedException");
        } catch (SpaceManager.PoolExhaustedException expected) {
            // the in-use cell must not be disturbed
            assertTrue(m.isLoaded(cell(1)));
            assertFalse(m.isLoaded(cell(2)));
        }
    }

    // -- pool-pressure eviction listener (the tier-1 overload signal) --------

    @Test
    public void forcedEvictionOfDirtyCellNotifiesListenerAsFlushed() {
        FakeBinder binder = new FakeBinder(10); // pool of 1 => next materialize forces an eviction
        List<String> fired = new ArrayList<>();
        SpaceManager m = new SpaceManager(binder, new Clock()::now, never(),
                (cellKey, wasDirty) -> fired.add(cellKey + ":" + wasDirty));

        m.materialize(cell(1));
        m.markDirty(cell(1));
        m.dematerialize(cell(1));   // idle, dirty
        m.materialize(cell(2));     // saturated pool => force-evict cell1 (flushed to store)

        assertEquals(1, fired.size());
        assertEquals("reports the victim and that it was flushed", cell(1).cellKey() + ":true", fired.get(0));
    }

    @Test
    public void forcedEvictionOfCleanCellNotifiesListenerAsDiscarded() {
        FakeBinder binder = new FakeBinder(10);
        List<String> fired = new ArrayList<>();
        SpaceManager m = new SpaceManager(binder, new Clock()::now, never(),
                (cellKey, wasDirty) -> fired.add(cellKey + ":" + wasDirty));

        m.materialize(cell(1));     // never dirtied
        m.dematerialize(cell(1));
        m.materialize(cell(2));     // force-evict clean cell1 (discarded)

        assertEquals(cell(1).cellKey() + ":false", fired.get(0));
    }

    @Test
    public void bindingIntoAFreeSlotDoesNotNotifyListener() {
        FakeBinder binder = new FakeBinder(10, 11); // pool of 2 => no pressure for two cells
        List<String> fired = new ArrayList<>();
        SpaceManager m = new SpaceManager(binder, new Clock()::now, never(),
                (cellKey, wasDirty) -> fired.add(cellKey));

        m.materialize(cell(1));
        m.materialize(cell(2)); // second cell takes the other free slot, no eviction

        assertTrue("no forced eviction => the overload signal stays silent", fired.isEmpty());
    }

    // -- flush (dirty) vs. discard (clean) on eviction -----------------------

    @Test
    public void dirtyCellIsFlushedToStoreOnEviction() {
        FakeBinder binder = new FakeBinder(10); // pool of 1 forces eviction on the next materialize
        SpaceManager m = mgr(binder, new Clock(), never());

        m.materialize(cell(1));
        m.markDirty(cell(1));
        m.dematerialize(cell(1));
        m.materialize(cell(2)); // evicts cell1

        assertEquals("dirty => unloaded (saved), not discarded", 1, binder.unloads.size());
        assertTrue(binder.discards.isEmpty());
        assertEquals(1, m.storedCellCount());
    }

    @Test
    public void cleanCellIsDiscardedOnEviction() {
        FakeBinder binder = new FakeBinder(10);
        SpaceManager m = mgr(binder, new Clock(), never());

        m.materialize(cell(1)); // never dirtied
        m.dematerialize(cell(1));
        m.materialize(cell(2)); // evicts cell1

        assertEquals("clean => discarded, nothing persisted", 1, binder.discards.size());
        assertTrue(binder.unloads.isEmpty());
        assertEquals(0, m.storedCellCount());
    }

    @Test
    public void reloadedStoredCellStaysStoredWhenNotReDirtied() {
        FakeBinder binder = new FakeBinder(10);
        SpaceManager m = mgr(binder, new Clock(), never());

        m.materialize(cell(1));
        m.markDirty(cell(1));
        m.dematerialize(cell(1));
        m.materialize(cell(2)); // evict cell1 -> stored
        m.dematerialize(cell(2));
        m.materialize(cell(1)); // reload cell1 (evicts clean cell2 -> discard)
        m.dematerialize(cell(1));
        m.materialize(cell(2)); // evict cell1 again, this time not re-dirtied

        assertTrue("its on-disk copy is kept", m.storedCellCount() >= 1);
        // The last eviction of the unchanged cell1 kept the store (unload), never discarded it.
        assertTrue(binder.unloads.size() >= 2);
    }

    // -- garbage collection --------------------------------------------------

    /** Store {@code count} distinct dirty cells (sectors 1..count) each visited at its sector tick. */
    private static void storeDirtyCells(SpaceManager m, FakeBinder binder, Clock clock, int count) {
        for (int s = 1; s <= count; s++) {
            clock.tick = s;
            m.materialize(cell(s)); // pool of 1 => evicts+flushes the previous dirty cell
            m.markDirty(cell(s));
            m.dematerialize(cell(s));
        }
        // Flush the final still-loaded cell to the store as well.
        clock.tick = count + 1;
        m.materialize(cell(1000)); // filler evicts the last real cell
        m.dematerialize(cell(1000));
    }

    @Test
    public void gcNeverDeletesNothing() {
        FakeBinder binder = new FakeBinder(10);
        Clock clock = new Clock();
        SpaceManager m = mgr(binder, clock, never());

        storeDirtyCells(m, binder, clock, 3);
        List<String> deleted = m.gc();

        assertTrue(deleted.isEmpty());
        assertTrue(binder.deletes.isEmpty());
    }

    @Test
    public void gcAgeDeletesOnlyStaleUnprotectedCells() {
        FakeBinder binder = new FakeBinder(10);
        Clock clock = new Clock();
        // maxAge 5 ticks.
        SpaceManager m = mgr(binder, clock, new SpaceManager.Config(SpaceManager.GcPolicy.AGE, 5, 0));

        storeDirtyCells(m, binder, clock, 3); // cells at last-visit 1,2,3 (filler cell1000 is clean)
        clock.tick = 100; // now everything real is stale (>5 since visits 1..3)
        List<String> deleted = m.gc();

        assertTrue("stale cells removed", deleted.contains(cell(1).cellKey()));
        assertTrue(deleted.contains(cell(2).cellKey()));
        assertTrue(deleted.contains(cell(3).cellKey()));
        assertEquals(deleted.size(), binder.deletes.size());
        assertEquals(0, m.storedCellCount());
    }

    @Test
    public void gcAgeKeepsRecentlyVisitedCells() {
        FakeBinder binder = new FakeBinder(10);
        Clock clock = new Clock();
        SpaceManager m = mgr(binder, clock, new SpaceManager.Config(SpaceManager.GcPolicy.AGE, 50, 0));

        storeDirtyCells(m, binder, clock, 3);
        clock.tick = 10; // within maxAge 50 of visits 1..3
        assertTrue(m.gc().isEmpty());
    }

    @Test
    public void gcCountTrimsOldestDownToLimit() {
        FakeBinder binder = new FakeBinder(10);
        Clock clock = new Clock();
        SpaceManager m = mgr(binder, clock, new SpaceManager.Config(SpaceManager.GcPolicy.COUNT, 0, 2));

        storeDirtyCells(m, binder, clock, 4); // 4 stored cells, last-visit 1,2,3,4
        List<String> deleted = m.gc();

        assertEquals("trim 4 -> 2 removes the two oldest", 2, deleted.size());
        assertTrue(deleted.contains(cell(1).cellKey()));
        assertTrue(deleted.contains(cell(2).cellKey()));
        assertEquals(2, m.storedCellCount());
    }

    @Test
    public void gcNeverTouchesLoadedCells() {
        FakeBinder binder = new FakeBinder(10, 11); // pool of 2
        Clock clock = new Clock();
        SpaceManager m = mgr(binder, clock, new SpaceManager.Config(SpaceManager.GcPolicy.AGE, 1, 0));

        // Make cell1 both STORED and LOADED: flush it once (evicted while dirty), then re-materialize
        // it and keep an occupant. cell2 is stored-but-idle so GC has something it *may* delete.
        clock.tick = 1; m.materialize(cell(1)); m.markDirty(cell(1)); m.dematerialize(cell(1));
        clock.tick = 2; m.materialize(cell(2)); m.markDirty(cell(2)); m.dematerialize(cell(2));
        clock.tick = 3; m.materialize(cell(3)); // evicts cell1 (older idle) -> cell1 flushed to store
        clock.tick = 4; m.dematerialize(cell(3));
        clock.tick = 5; m.materialize(cell(1)); // reload cell1 (evicts idle cell2 -> cell2 flushed)
        // cell1 now stored AND loaded with a live occupant; cell2 stored and idle.
        assertTrue(m.isLoaded(cell(1)));

        clock.tick = 100;
        List<String> deleted = m.gc();

        assertFalse("loaded-and-stored cell must survive GC", deleted.contains(cell(1).cellKey()));
        assertTrue("idle stored cell is collectable", deleted.contains(cell(2).cellKey()));
    }

    @Test
    public void gcSkipsClaimedCells() {
        FakeBinder binder = new FakeBinder(10);
        Clock clock = new Clock();
        SpaceManager m = mgr(binder, clock, new SpaceManager.Config(SpaceManager.GcPolicy.AGE, 1, 0));

        storeDirtyCells(m, binder, clock, 2);
        m.setClaimed(cell(1), true); // protect cell1
        clock.tick = 100;
        List<String> deleted = m.gc();

        assertFalse("claimed cell survives GC", deleted.contains(cell(1).cellKey()));
        assertTrue(deleted.contains(cell(2).cellKey()));
    }
}
