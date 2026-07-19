package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.HyperspaceTiles;
import zmaster587.advancedRocketry.space.OfflineProgress;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.ShipTransitManager;
import zmaster587.advancedRocketry.space.SlotBinder;
import zmaster587.advancedRocketry.space.SpaceManager;
import zmaster587.advancedRocketry.space.TransitRecord;

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
        final List<String> restoredCompletions = new ArrayList<>();
        boolean failDepart;
        int arriveFailCount; // fail the arrival crossing this many times (async-assembly retry), then succeed
        int completeRestoredFailCount; // fail the restored paste this many times, then succeed
        NBTTagCompound snapshotToReturn; // what snapshotParked hands back (the re-cut stub)
        NBTTagCompound sourceSnapshotToReturn; // what snapshotSource hands back (the depart-time floor)
        // Crew seam (option-A capture at depart, reseat at arrival). EMPTY crew by default, so every existing
        // test sees no crew captured and no reseat entries - behaviorally identical to before this seam.
        final List<UUID> crewToCapture = new ArrayList<>(); // captureCrew hands these back (empty => no crew)
        int reseatFailCount;                                // fail reseatCrew this many times, then succeed
        final List<String> captureCalls = new ArrayList<>();
        final List<String> reseatCalls = new ArrayList<>();
        final List<String> order = new ArrayList<>();       // shared call order: pins capture-before-depart

        @Override
        public BlockPos departToHyperspace(int srcSlotDim, BlockPos srcAnchor, HyperspaceTiles.Tile tile) {
            departs.add(srcSlotDim + "@" + tile.index);
            order.add("depart");
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

        @Override
        public NBTTagCompound snapshotParked(HyperspaceTiles.Tile tile, BlockPos hyperAnchor) {
            return snapshotToReturn;
        }

        @Override
        public NBTTagCompound snapshotSource(int srcSlotDim, BlockPos srcAnchor) {
            return sourceSnapshotToReturn;
        }

        @Override
        public BlockPos completeRestored(NBTTagCompound snapshot, int targetSlotDim) {
            restoredCompletions.add(targetSlotDim + ":" + (snapshot != null));
            if (completeRestoredFailCount > 0) {
                completeRestoredFailCount--;
                return null; // paste/assembly not up yet - retry next tick
            }
            return new BlockPos(0, 200, 0);
        }

        @Override
        public List<UUID> captureCrew(int srcSlotDim, BlockPos srcAnchor, String shipId) {
            captureCalls.add(srcSlotDim + "@" + shipId);
            order.add("capture");
            return new ArrayList<>(crewToCapture); // empty by default => existing tests get no crew
        }

        @Override
        public boolean reseatCrew(int targetSlotDim, BlockPos arrivalAnchor, String shipId) {
            reseatCalls.add(targetSlotDim + "@" + shipId);
            if (reseatFailCount > 0) {
                reseatFailCount--;
                return false; // seat tiles not up yet - retry next tick
            }
            return true;
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
        assertTrue("the arrived cell is marked dirty so an eviction flushes it (closes ledger #79)",
                space.isDirty(cell(2)));
    }

    @Test
    public void crewOnlineGatePausesAdvanceWhileNoCrewOnline() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), new FakeCrosser(),
                new ShipLedger(), () -> 0L);
        // crew-online mode, nobody online -> a MANNED transit is paused.
        mgr.setOfflineProgress(new OfflineProgress(OfflineProgress.Mode.CREW_ONLINE, id -> false));
        String ship = UUID.randomUUID().toString();

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(ship, cell(1), originDim, new BlockPos(0, 64, 0), cell(2), 1L);
        mgr.setTransitCrew(ship, java.util.Collections.singletonList(UUID.randomUUID()));

        double before = mgr.remainingDistance(ship);
        mgr.tick();
        assertEquals("a manned crew-online transit does not advance while no crew is online",
                before, mgr.remainingDistance(ship), 0.0);
        assertTrue("it stays in transit (paused, not dropped)", mgr.isInTransit(ship));
    }

    @Test
    public void exportTransitsSnapshotsInFlightShips() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), new FakeCrosser(),
                new ShipLedger(), () -> 500L);
        String ship = UUID.randomUUID().toString();

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(ship, cell(1), originDim, new BlockPos(0, 64, 0), cell(2), 7L);

        List<TransitRecord> records = mgr.exportTransits();
        assertEquals(1, records.size());
        TransitRecord r = records.get(0);
        assertEquals(ship, r.shipId);
        assertEquals("position starts at origin (not yet ticked)", cell(1), r.position);
        assertEquals(cell(2), r.target);
        assertEquals(7L, r.speed);
        assertTrue("no crew captured yet (option-A capture is the VS layer)", r.crew.isEmpty());
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

    // ── Restored transits (survive a restart): imported from a persisted TransitRecord ──────────────

    @Test
    public void importedTransitCompletesByPastingItsSnapshotNotCrossingHyperspace() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        ShipLedger ledger = new ShipLedger();
        FakeCrosser crosser = new FakeCrosser();
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser, ledger, () -> 0L);
        UUID ship = UUID.randomUUID();

        // A record as if persisted before a restart; arrives in one tick (distance < speed).
        mgr.importTransit(new TransitRecord(ship.toString(), cell(1), cell(2), 10L, 0L,
                ARRIVE_IN_ONE_TICK, new ArrayList<UUID>(), new NBTTagCompound()));

        assertTrue("the imported record is in transit", mgr.isInTransit(ship.toString()));
        assertEquals("a restored transit holds no hyperspace lane", 0, tiles.inUseCount());
        assertEquals("import re-marks the ledger IN_TRANSIT (it persists SETTLED only)",
                ShipLedger.State.IN_TRANSIT, ledger.get(ship).state);

        mgr.tick(); // arrives -> completeRestored (paste the snapshot), NOT arriveFromHyperspace

        assertFalse(mgr.isInTransit(ship.toString()));
        assertEquals("a restored arrival pastes its snapshot", 1, crosser.restoredCompletions.size());
        assertTrue("the persisted snapshot was handed to completeRestored",
                crosser.restoredCompletions.get(0).endsWith(":true"));
        assertTrue("no live hyperspace crossing was attempted for a restored transit",
                crosser.arrivals.isEmpty());
        assertEquals("no lane to free (restored held none)", 0, tiles.inUseCount());
        ShipLedger.Entry e = ledger.get(ship);
        assertEquals("a restored transit settles the ledger on arrival", ShipLedger.State.SETTLED, e.state);
        assertEquals("settled at the target cell", cell(2), e.coord);
        assertTrue("the arrived cell is marked dirty so an eviction flushes it", space.isDirty(cell(2)));
    }

    @Test
    public void importTransitIsANoOpForAShipAlreadyInTransit() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, new FakeCrosser(), new ShipLedger(),
                () -> 0L);
        UUID ship = UUID.randomUUID();

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(ship.toString(), cell(1), originDim, new BlockPos(0, 64, 0), cell(2), 1L);
        assertEquals(1, tiles.inUseCount());

        // A stray restore of a ship already flying must not spawn a second (restored) transit.
        mgr.importTransit(new TransitRecord(ship.toString(), cell(1), cell(2), 10L, 0L, 1L,
                new ArrayList<UUID>(), new NBTTagCompound()));

        assertEquals("still exactly one transit", 1, mgr.inTransitCount());
        assertEquals("the live lane is untouched", 1, tiles.inUseCount());
    }

    @Test
    public void exportRecutsALiveShipSnapshotFromHyperspace() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();
        NBTTagCompound cut = new NBTTagCompound();
        cut.setInteger("marker", 42);
        crosser.snapshotToReturn = cut;
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser,
                new ShipLedger(), () -> 0L);
        String ship = UUID.randomUUID().toString();

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(ship, cell(1), originDim, new BlockPos(0, 64, 0), cell(2), 7L);

        TransitRecord r = mgr.exportTransits().get(0);
        assertNotNull("export re-cut the parked ship's block snapshot", r.snapshot);
        assertEquals("the freshly re-cut snapshot is what gets persisted", 42, r.snapshot.getInteger("marker"));
    }

    @Test
    public void exportDoesNotRecutARestoredTransitButKeepsItsImportedSnapshot() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();
        crosser.snapshotToReturn = new NBTTagCompound(); // a would-be re-cut that MUST NOT be used
        crosser.snapshotToReturn.setInteger("recut", 1);
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser,
                new ShipLedger(), () -> 0L);
        String ship = UUID.randomUUID().toString();

        NBTTagCompound imported = new NBTTagCompound();
        imported.setInteger("imported", 7);
        mgr.importTransit(new TransitRecord(ship, cell(1), cell(2), 10L, 0L, 7L, new ArrayList<UUID>(),
                imported));

        TransitRecord r = mgr.exportTransits().get(0);
        assertFalse("a restored transit has no live hyperspace ship to re-cut", r.snapshot.hasKey("recut"));
        assertEquals("it keeps the snapshot it was imported with", 7, r.snapshot.getInteger("imported"));
    }

    @Test
    public void restoredTransitRespectsTheCrewOnlineGate() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), new FakeCrosser(),
                new ShipLedger(), () -> 0L);
        mgr.setOfflineProgress(new OfflineProgress(OfflineProgress.Mode.CREW_ONLINE, id -> false));
        String ship = UUID.randomUUID().toString();

        // A restored MANNED transit (crew persisted in the record) with nobody online -> paused.
        mgr.importTransit(new TransitRecord(ship, cell(1), cell(2), 10L, 0L, 1L,
                java.util.Collections.singletonList(UUID.randomUUID()), new NBTTagCompound()));

        double before = mgr.remainingDistance(ship);
        mgr.tick();
        assertEquals("a restored manned crew-online transit is paused while no crew is online",
                before, mgr.remainingDistance(ship), 0.0);
        assertTrue("it stays in transit (paused, not dropped)", mgr.isInTransit(ship));
    }

    @Test
    public void departCapturesAFloorSnapshotSoAPreAssemblySaveIsNeverSnapshotless() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();
        NBTTagCompound floor = new NBTTagCompound();
        floor.setInteger("floor", 1);
        crosser.sourceSnapshotToReturn = floor; // captured at depart, before the crossing cuts the ship
        crosser.snapshotToReturn = null;         // hyperspace ship not assembled yet -> re-cut unavailable
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser,
                new ShipLedger(), () -> 0L);
        String ship = UUID.randomUUID().toString();

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(ship, cell(1), originDim, new BlockPos(0, 64, 0), cell(2), 7L);

        // A save fired NOW (before the hyperspace ship assembled) must still carry a block snapshot, or the
        // ship is deleted on restart. The depart-time floor guarantees it even though the re-cut returned null.
        TransitRecord r = mgr.exportTransits().get(0);
        assertNotNull("a just-departed ship carries its depart-time floor snapshot", r.snapshot);
        assertEquals("the floor snapshot is what gets persisted before the first re-cut", 1,
                r.snapshot.getInteger("floor"));
    }

    @Test
    public void importTransitDropsASnapshotlessOrBlankRecordInsteadOfCreatingADoomedTransit() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), new FakeCrosser(),
                new ShipLedger(), () -> 0L);

        // Snapshot-less record: the ship's blocks are unrecoverable, so a restored transit would only spin to
        // MAX_ARRIVAL_ATTEMPTS then silently delete it. Drop it instead.
        mgr.importTransit(new TransitRecord(UUID.randomUUID().toString(), cell(1), cell(2), 10L, 0L, 1L,
                new ArrayList<UUID>(), null));
        assertEquals("a snapshot-less record is not imported as a doomed transit", 0, mgr.inTransitCount());

        // Blank/corrupt id (an absent NBT "shipId" reads as "") is likewise dropped.
        mgr.importTransit(new TransitRecord("", cell(1), cell(2), 10L, 0L, 1L, new ArrayList<UUID>(),
                new NBTTagCompound()));
        assertEquals("a blank-id record is dropped", 0, mgr.inTransitCount());
    }

    // ── Crew capture at depart + reseat at arrival (option A) ────────────────────────────────────────

    @Test
    public void captureRunsAtDepartAndCrewFlowsToTheOfflineGate() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        crosser.crewToCapture.add(UUID.randomUUID()); // one aboard crew member, captured at depart
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);
        // crew-online mode, nobody online -> a transit whose captured crew is offline must pause.
        mgr.setOfflineProgress(new OfflineProgress(OfflineProgress.Mode.CREW_ONLINE, id -> false));

        int originDim = space.materialize(cell(1));
        assertTrue(mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2),
                ARRIVE_IN_ONE_TICK));

        // captureCrew ran exactly once, and BEFORE the depart crossing cut the seat blocks.
        assertEquals("capture invoked once at depart", 1, crosser.captureCalls.size());
        assertEquals("crew captured before the depart crossing", "capture", crosser.order.get(0));
        assertTrue("the depart crossing ran too", crosser.order.contains("depart"));

        // The captured UUID reached the transit's crew list: the crew-online gate now reads it and, with the
        // crew offline, PAUSES the flight - it would otherwise arrive this tick (ARRIVE_IN_ONE_TICK).
        double before = mgr.remainingDistance("s");
        mgr.tick();
        assertTrue("captured crew keeps the transit alive (paused, not arrived)", mgr.isInTransit("s"));
        assertEquals("a manned crew-online transit does not advance while its crew is offline",
                before, mgr.remainingDistance("s"), 0.0);
    }

    @Test
    public void arrivedShipRetriesReseatUntilDone() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        crosser.crewToCapture.add(UUID.randomUUID());
        crosser.reseatFailCount = 2; // seat tiles not up for the first 2 reseat attempts, then succeed
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));
        mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), ARRIVE_IN_ONE_TICK);

        mgr.tick(); // arrives this tick: the transit settles + is removed, but the crew reseat is pending

        assertFalse("the ship has physically arrived (no longer in transit)", mgr.isInTransit("s"));
        assertEquals("the transit is off the in-flight map at arrival", 0, mgr.inTransitCount());
        assertEquals("an arrived ship with crew is awaiting a reseat", 1, mgr.reseatingCount());
        assertEquals("first reseat attempt ran on the arrival tick", 1, crosser.reseatCalls.size());

        mgr.tick(); // reseat attempt 2 fails
        assertEquals("still awaiting reseat after the second failed attempt", 1, mgr.reseatingCount());
        mgr.tick(); // reseat attempt 3 succeeds -> drops out of the reseat list

        assertEquals("the crew reseat succeeded on the third attempt", 0, mgr.reseatingCount());
        assertEquals("the reseat was retried each tick until it took", 3, crosser.reseatCalls.size());
    }

    @Test
    public void crewlessArrivalNeverEntersTheReseatList() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser(); // crewToCapture empty => captureCrew returns no crew
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));
        mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), ARRIVE_IN_ONE_TICK);

        mgr.tick(); // arrives this tick

        assertEquals("the ship arrived and left the in-flight map", 0, mgr.inTransitCount());
        assertEquals("a crewless transit is fully done at arrival - no reseat pending, no strand",
                0, mgr.reseatingCount());
    }

    @Test
    public void abortedDepartReseatsTheCrewOnOrigin() {
        SpaceManager space = new SpaceManager(new FakeBinder(10), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        crosser.crewToCapture.add(UUID.randomUUID()); // captureCrew dismounts a crew member at depart
        crosser.failDepart = true;                    // ...then the depart crossing fails -> jump aborts
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));
        boolean began = mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2),
                ARRIVE_IN_ONE_TICK);

        assertFalse("depart crossing failed => jump aborted", began);
        assertFalse(mgr.isInTransit("s"));
        // The crew was dismounted by captureCrew before the (failed) cut; the abort must re-seat them onto the
        // still-present origin ship rather than leaving the pilot ejected.
        assertFalse("an aborted jump re-seats the crew it dismounted", crosser.reseatCalls.isEmpty());
    }
}
