package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.HyperspaceTiles;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.ShipTransitManager;
import zmaster587.advancedRocketry.space.SlotBinder;
import zmaster587.advancedRocketry.space.SpaceManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the transit state machine's WIRING - the depart/advance/arrive lifecycle, the
 * hyperspace-lane bookkeeping, and the origin&rarr;target refcount handoff - exercised against the real
 * {@link SpaceManager} with a recording fake {@link ShipTransitManager.Crosser} in place of the VS world
 * operations. Pins the decisions (release origin on depart, materialize target on arrival, free the lane,
 * abort cleanly on a failed crossing) without a live server or VS.
 */
public class ShipTransitManagerTest {

    private static GalacticCoord cell(long s) {
        return GalacticCoord.ofSectorLocal(s, 0L, 0L, 0L, 0L, 0L);
    }

    /** Recording binder (mirrors SpaceManagerTest) so the transit tests drive a real SpaceManager. */
    private static final class FakeBinder implements SlotBinder {
        final int[] dims;
        FakeBinder(int... dims) { this.dims = dims; }
        @Override public int[] slotDims() { return dims; }
        @Override public void load(int dimId, String cellKey) { }
        @Override public void unload(int dimId) { }
        @Override public void discard(int dimId) { }
        @Override public void deleteStore(String cellKey) { }
    }

    /** Recording crosser: returns non-null anchors by default; a flag forces a crossing failure. */
    private static final class FakeCrosser implements ShipTransitManager.Crosser {
        final List<String> departs = new ArrayList<>();
        final List<String> arrivals = new ArrayList<>();
        boolean failDepart;
        int arriveFailCount; // fail the arrival crossing this many times (async-assembly retry), then succeed

        @Override
        public BlockPos departToHyperspace(int srcSlotDim, BlockPos srcAnchor, HyperspaceTiles.Tile tile) {
            departs.add(srcSlotDim + "@" + tile.index);
            return failDepart ? null : tile.pos;
        }

        @Override
        public BlockPos arriveFromHyperspace(HyperspaceTiles.Tile tile, BlockPos hyperAnchor, int targetSlotDim) {
            arrivals.add(targetSlotDim + "@" + tile.index);
            if (arriveFailCount > 0) {
                arriveFailCount--;
                return null; // ship not yet crossable in hyperspace (async assembly) - retry next tick
            }
            return new BlockPos(0, 128, 0);
        }
    }

    private static SpaceManager.Config never() {
        return new SpaceManager.Config(SpaceManager.GcPolicy.NEVER, 0, 0);
    }

    // Speed >= the 4,000,000-block inter-cell distance so a single tick arrives; a small speed does not.
    private static final long ARRIVE_IN_ONE_TICK = 5_000_000L;

    @Test
    public void departPutsShipInTransitAndAllocatesALane() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));
        boolean began = mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), ARRIVE_IN_ONE_TICK);

        assertTrue(began);
        assertTrue(mgr.isInTransit("s"));
        assertEquals(1, mgr.inTransitCount());
        assertEquals(1, tiles.inUseCount());
        assertEquals("departure crossing invoked once", 1, crosser.departs.size());
        assertTrue("crossing left the origin slot", crosser.departs.get(0).startsWith(originDim + "@"));
    }

    @Test
    public void arrivalCrossesIntoTargetHandsOffRefcountAndFreesLane() {
        // Pool of ONE: the arrival's materialize(target) can only succeed if depart released origin's
        // refcount - otherwise the single slot is stuck on origin and PoolExhaustedException is thrown.
        SpaceManager space = new SpaceManager(new FakeBinder(10), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));           // ship occupies origin, refcount 1
        mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), ARRIVE_IN_ONE_TICK);

        mgr.tick(); // distance 4,000,000 < speed 5,000,000 => arrives this tick

        assertFalse("no longer in transit", mgr.isInTransit("s"));
        assertEquals(0, mgr.inTransitCount());
        assertEquals("hyperspace lane freed", 0, tiles.inUseCount());
        assertEquals("arrival crossing invoked once", 1, crosser.arrivals.size());
        assertTrue("target cell now live", space.isLoaded(cell(2)));
        assertFalse("origin cell released (evicted for the target under a pool of 1)", space.isLoaded(cell(1)));
    }

    @Test
    public void arrivalRetriesUntilTheAsyncHyperspaceShipBecomesCrossable() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        crosser.arriveFailCount = 3; // first 3 arrival attempts fail (ship still assembling in hyperspace)
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));
        mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), ARRIVE_IN_ONE_TICK);

        mgr.tick(); // arrives, arrival attempt 1 fails
        assertTrue("stays in transit while the hyperspace ship is not yet crossable", mgr.isInTransit("s"));
        assertEquals("lane still held during retry", 1, tiles.inUseCount());
        mgr.tick(); // attempt 2 fails
        mgr.tick(); // attempt 3 fails
        assertTrue(mgr.isInTransit("s"));
        mgr.tick(); // attempt 4 succeeds

        assertFalse("arrived once the crossing succeeded", mgr.isInTransit("s"));
        assertEquals(0, tiles.inUseCount());
        assertEquals("four arrival crossing attempts", 4, crosser.arrivals.size());
        assertTrue("target materialized exactly once (no refcount churn on retry)", space.isLoaded(cell(2)));
    }

    @Test
    public void enRouteShipStaysParkedUntilItArrives() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));
        mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), 1L); // 1 block/tick

        mgr.tick(); // covers 1 of 4,000,000 blocks - nowhere near arrival

        assertTrue("still in transit", mgr.isInTransit("s"));
        assertEquals("no arrival crossing yet", 0, crosser.arrivals.size());
        assertTrue("remaining distance still positive", mgr.remainingDistance("s") > 0.0);
        assertEquals("target not materialized yet", false, space.isLoaded(cell(2)));
    }

    @Test
    public void failedDepartAbortsCleanlyWithoutConsumingALaneOrReleasingOrigin() {
        SpaceManager space = new SpaceManager(new FakeBinder(10), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        crosser.failDepart = true;
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));
        boolean began = mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), ARRIVE_IN_ONE_TICK);

        assertFalse("depart crossing failed => jump aborted", began);
        assertFalse(mgr.isInTransit("s"));
        assertEquals("lane returned", 0, tiles.inUseCount());
        assertTrue("origin NOT released on a failed depart", space.isLoaded(cell(1)));
    }

    @Test
    public void beginTransitRecordsInTransitInTheLedger() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        ShipLedger ledger = new ShipLedger();
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), new FakeCrosser(),
                ledger, () -> 1000L);
        UUID ship = UUID.randomUUID();

        int originDim = space.materialize(cell(1));
        assertTrue(mgr.beginTransit(ship.toString(), cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), ARRIVE_IN_ONE_TICK));

        ShipLedger.Entry e = ledger.get(ship);
        assertNotNull("the ledger now records the in-flight ship (no depart amnesia)", e);
        assertEquals(ShipLedger.State.IN_TRANSIT, e.state);
        assertEquals("ledger holds the transit TARGET", cell(2), e.coord);
        assertTrue("an ETA (arrivalTick) is computed from now", mgr.arrivalTick(ship.toString()) > 1000L);
    }

    @Test
    public void arrivalSettlesTheLedgerAndMarksTheCellDirty() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        ShipLedger ledger = new ShipLedger();
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), new FakeCrosser(),
                ledger, () -> 0L);
        UUID ship = UUID.randomUUID();

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(ship.toString(), cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), ARRIVE_IN_ONE_TICK);
        mgr.tick(); // distance 4,000,000 < speed 5,000,000 => arrives this tick

        ShipLedger.Entry e = ledger.get(ship);
        assertNotNull(e);
        assertEquals("arrival settles the ledger (no longer amnesiac)", ShipLedger.State.SETTLED, e.state);
        assertEquals("settled at the target cell", cell(2), e.coord);
        assertTrue("the arrived cell is marked dirty so an eviction flushes it (closes ledger #46)",
                space.isDirty(cell(2)));
    }

    @Test
    public void doubleBeginForSameShipIsRejected() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, new FakeCrosser());

        int originDim = space.materialize(cell(1));
        assertTrue(mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), 1L));
        assertFalse("already in transit", mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0),
                cell(3), 1L));
        assertEquals(1, mgr.inTransitCount());
    }
}
