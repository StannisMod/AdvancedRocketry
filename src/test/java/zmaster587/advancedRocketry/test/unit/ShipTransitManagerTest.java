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
import zmaster587.advancedRocketry.space.ShipCrossingService;
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
        int snapshotParkedCalls;        // how often the live re-cut was asked for - a SAVE must ask zero times
        boolean snapshotParkedThrows;   // the physics world failing the cut, which must not lose the ship
        int settleFailCount;            // fail the arrival POSE settle this many times (ship pasted, not settled)
        // Crew seam (option-A capture at depart, reseat at arrival). EMPTY crew by default, so every existing
        // test sees no crew captured and no reseat entries - behaviorally identical to before this seam.
        final List<UUID> crewToCapture = new ArrayList<>(); // captureCrew hands these back (empty => no crew)
        int reseatFailCount;                                // fail reseatCrew this many times, then succeed
        final List<String> captureCalls = new ArrayList<>();
        final List<String> reseatCalls = new ArrayList<>();
        // WHERE each reseat was aimed. The dim alone cannot tell an abort's "put him back where he
        // was" from an arrival's "seat him at the far end": the abort happens before the ship has
        // gone anywhere, so both name a real world and only the anchor separates them.
        final List<BlockPos> reseatAnchors = new ArrayList<>();
        // Identity seam: which ship each crossing produced, and which one every later step was told to
        // act on. A jump keeps ONE identity, so departedAs is what the settle and the re-seat must be
        // handed - unless a crossing reports it could not keep it (arrivesAs / restoredAs), which is
        // exactly when the later steps must follow the NEW one instead.
        UUID departedAs = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        UUID arrivesAs;                                     // null => the arrival kept departedAs
        UUID restoredAs = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        final List<UUID> settledAs = new ArrayList<>();     // what settleArrivedPose was told, per call
        final List<UUID> reseatedAs = new ArrayList<>();    // what reseatCrew was told, per call
        final List<UUID> boardedAs = new ArrayList<>();     // what boardCrew was told, per call
        // Where in-flight ships are parked. MIN_VALUE (the interface default) means "there is no such
        // world", which suppresses the departure-side boarding entirely - so a test that wants to see
        // that leg has to name a world for it.
        int parkedDimToReturn = Integer.MIN_VALUE;
        // Durable hyperspace: which anchors still have a ship standing at them after the restart, and
        // which lanes hold one at all. Empty by default — the interface's own default answers "no
        // ship, no lanes", which is the ephemeral world every existing test was written against.
        final java.util.Set<BlockPos> parkedAnchors = new java.util.HashSet<>();
        // WHERE the hulls are standing, not which lanes they are in. The difference is the arrangement's
        // whole honesty here: a canned list of lane numbers answers the "which lane is this ship in"
        // question in production's place, and that question is where the defect lived. This resolves a
        // position the same way production does, so the arrangement can be wrong about a lane exactly
        // when production is.
        final java.util.Set<BlockPos> shipsParkedAt = new java.util.HashSet<>();
        final List<Integer> disposedLanes = new ArrayList<>();
        boolean disposeSucceeds = true;
        final List<String> order = new ArrayList<>();       // shared call order: pins capture-before-depart

        @Override
        public boolean parkedShipPresent(BlockPos hyperAnchor) {
            return parkedAnchors.contains(hyperAnchor);
        }

        @Override
        public List<Integer> parkedShipLanes() {
            List<Integer> lanes = new ArrayList<>();
            for (BlockPos at : shipsParkedAt) {
                int lane = HyperspaceTiles.laneIndexAt(at.getX(), at.getZ());
                if (lane >= 0) {
                    lanes.add(lane);
                }
            }
            return lanes;
        }

        @Override
        public boolean disposeParkedLane(int laneIndex) {
            disposedLanes.add(laneIndex);
            return disposeSucceeds;
        }

        @Override
        public ShipCrossingService.Crossed departToHyperspace(int srcSlotDim, BlockPos srcAnchor,
                                                              String shipId,
                                                              HyperspaceTiles.Tile tile) {
            departs.add(srcSlotDim + "@" + tile.index);
            order.add("depart");
            return failDepart ? null : new ShipCrossingService.Crossed(tile.pos, departedAs);
        }

        @Override
        public ShipCrossingService.Crossed arriveFromHyperspace(String shipId, HyperspaceTiles.Tile tile,
                                                                BlockPos hyperAnchor, int targetSlotDim) {
            arrivals.add(targetSlotDim + "@" + tile.index);
            if (arriveFailCount > 0) {
                arriveFailCount--;
                return null; // ship not yet crossable in hyperspace (async assembly) - retry next tick
            }
            // A live crossing KEEPS the ship's identity, so the arrival comes back under the same uuid
            // the departure did - unless a test asks for the case where it could not be kept.
            return new ShipCrossingService.Crossed(new BlockPos(0, 128, 0),
                    arrivesAs == null ? departedAs : arrivesAs);
        }

        @Override
        public BlockPos settleArrivedPose(int targetSlotDim, BlockPos pasteAnchor, UUID vsShipUuid,
                                          double px, double py, double pz) {
            settledAs.add(vsShipUuid);
            if (settleFailCount > 0) {
                settleFailCount--;
                return null; // re-assembly not queryable yet - the ship stays pasted, the settle retries
            }
            return pasteAnchor;
        }

        @Override
        public NBTTagCompound snapshotParked(HyperspaceTiles.Tile tile, BlockPos hyperAnchor) {
            snapshotParkedCalls++;
            if (snapshotParkedThrows) {
                throw new IllegalStateException("the physics world refused the cut");
            }
            return snapshotToReturn;
        }

        @Override
        public NBTTagCompound snapshotSource(int srcSlotDim, BlockPos srcAnchor) {
            return sourceSnapshotToReturn;
        }

        @Override
        public ShipCrossingService.Crossed completeRestored(NBTTagCompound snapshot, int targetSlotDim) {
            restoredCompletions.add(targetSlotDim + ":" + (snapshot != null));
            if (completeRestoredFailCount > 0) {
                completeRestoredFailCount--;
                return null; // paste/assembly not up yet - retry next tick
            }
            // A rebuild from stored blocks cannot keep an identity: it comes back under a fresh one.
            return new ShipCrossingService.Crossed(new BlockPos(0, 200, 0), restoredAs);
        }

        @Override
        public List<UUID> captureCrew(int srcSlotDim, BlockPos srcAnchor, String shipId) {
            captureCalls.add(srcSlotDim + "@" + shipId);
            order.add("capture");
            return new ArrayList<>(crewToCapture); // empty by default => existing tests get no crew
        }

        @Override
        public boolean reseatCrew(int targetSlotDim, BlockPos arrivalAnchor, String shipId,
                                  UUID vsShipUuid) {
            reseatCalls.add(targetSlotDim + "@" + shipId);
            reseatAnchors.add(arrivalAnchor);
            reseatedAs.add(vsShipUuid);
            if (reseatFailCount > 0) {
                reseatFailCount--;
                return false; // seat tiles not up yet - retry next tick
            }
            return true;
        }

        @Override
        public boolean boardCrew(int parkedDim, BlockPos anchor, String shipId, UUID vsShipUuid) {
            boardedAs.add(vsShipUuid);
            return true;
        }

        @Override
        public int parkedDim() {
            return parkedDimToReturn;
        }
    }

    private static SpaceManager.Config never() {
        return new SpaceManager.Config(SpaceManager.GcPolicy.NEVER, 0, 0);
    }

    // A speed that covers the inter-cell distance in ONE tick, so an arrival can be asserted without
    // ticking a flight out. DERIVED from the cell edge (the same 1.25x margin it always carried), not
    // written down: as a literal it silently became a speed that arrives in eight ticks the moment the
    // cell grew, and eight of these tests then read as "the arrival never happened".
    private static final long ARRIVE_IN_ONE_TICK = GalacticCoord.CELL * 5L / 4L;

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
        assertTrue("the arrived cell is marked dirty so an eviction flushes it",
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
        assertEquals("the origin is persisted: progress is meaningless without it", cell(1), r.origin);
        assertEquals(cell(2), r.target);
        assertEquals("nothing flown yet (not ticked)", 0L, r.travelledBlocks);
        // ONE cell apart, so the price IS the cell edge — bound to the constant, because this is the
        // one distance here that is derived rather than chosen. The fixture distances passed to
        // importTransit elsewhere in this file are NOT this number: they are magnitudes picked so a
        // flight completes inside a test's tick budget, and they merely used to equal it.
        assertEquals("the flight is priced at depart, once", GalacticCoord.CELL, r.distanceBlocks);
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

    // ── One jump, one ship identity ─────────────────────────────────────────────────────────────────

    @Test
    public void everyStepOfAJumpActsOnTheShipTheCrossingProduced() {
        // The contract: a jump names ITS ship at every step. Nothing here may fall back to "whichever
        // ship is nearest the arrival point" - a destination cell holding a second craft is ordinary,
        // and a settle or a re-seat that picks the neighbour moves the wrong ship and then hunts for
        // this crew's seats aboard it.
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        crosser.crewToCapture.add(UUID.randomUUID()); // a manned jump, so the re-seat leg runs too
        crosser.parkedDimToReturn = 900;              // a hyperspace world exists, so the crew boards it
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));
        mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), ARRIVE_IN_ONE_TICK);

        mgr.tick(); // departs, flies, arrives and settles on the same tick; the re-seat follows
        mgr.tick();

        assertFalse("the departure seated its crew on SOME ship", crosser.boardedAs.isEmpty());
        assertFalse("the arrival settled SOME ship", crosser.settledAs.isEmpty());
        assertFalse("the arrival re-seated its crew on SOME ship", crosser.reseatedAs.isEmpty());
        for (UUID named : crosser.boardedAs) {
            assertEquals("the hyperspace boarding names the departed ship", crosser.departedAs, named);
        }
        for (UUID named : crosser.settledAs) {
            assertEquals("the settle names the ship the crossing produced", crosser.departedAs, named);
        }
        for (UUID named : crosser.reseatedAs) {
            assertEquals("the re-seat names the ship the crossing produced", crosser.departedAs, named);
        }
    }

    @Test
    public void anArrivalThatCouldNotKeepTheIdentityIsFollowedNotOverruled() {
        // A crossing keeps the ship's identity, but it can fail to: something live at the destination
        // may already hold that name. The arrival then comes back under a different one, and THAT is
        // the ship which exists - so the settle and the re-seat must follow the arrival, not the
        // departure. Keeping the departure's value here would name a ship that is not there.
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        crosser.crewToCapture.add(UUID.randomUUID());
        crosser.arrivesAs = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));
        mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), ARRIVE_IN_ONE_TICK);

        mgr.tick();
        mgr.tick();

        assertFalse(crosser.settledAs.isEmpty());
        assertFalse(crosser.reseatedAs.isEmpty());
        for (UUID named : crosser.settledAs) {
            assertEquals("the settle follows the identity the ARRIVAL came back with",
                    crosser.arrivesAs, named);
        }
        for (UUID named : crosser.reseatedAs) {
            assertEquals("the re-seat follows the identity the ARRIVAL came back with",
                    crosser.arrivesAs, named);
        }
    }

    @Test
    public void aRestoredArrivalAdoptsTheIdentityOfTheShipItRebuilt() {
        // The one arrival that cannot keep an identity: it rebuilds the ship out of stored blocks,
        // because the ship it departed as died with the hyperspace world on the restart this transit
        // survived. Everything after it must name the rebuild.
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser, new ShipLedger(), () -> 0L);
        UUID ship = UUID.randomUUID();
        List<UUID> crew = new ArrayList<>();
        crew.add(UUID.randomUUID()); // a manned record, so the arrival hands work to the re-seat leg

        mgr.importTransit(new TransitRecord(ship.toString(), cell(1), cell(2), 4_000_000L, 0L, 10L, 0L,
                ARRIVE_IN_ONE_TICK, crew, new NBTTagCompound()));

        mgr.tick(); // completeRestored pastes the snapshot and reports its fresh identity
        mgr.tick(); // the re-seat retry runs against it

        assertFalse("the restored arrival settled SOME ship", crosser.settledAs.isEmpty());
        assertFalse("the restored arrival re-seated its crew on SOME ship", crosser.reseatedAs.isEmpty());
        for (UUID named : crosser.settledAs) {
            assertEquals("the settle names the REBUILT ship", crosser.restoredAs, named);
        }
        for (UUID named : crosser.reseatedAs) {
            assertEquals("the re-seat names the REBUILT ship", crosser.restoredAs, named);
        }
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
        mgr.importTransit(new TransitRecord(ship.toString(), cell(1), cell(2), 4_000_000L, 0L, 10L, 0L,
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

    /**
     * The invariant that makes the ledger usable at all: a ship is in the transit map exactly when its
     * ledger entry says IN_TRANSIT. An arrival that cannot complete may take as long as it needs, but it
     * may never leave the two disagreeing — a ship the manager has forgotten while the ledger still calls
     * it in flight is unreachable by every player action. The descent refuses it (it demands SETTLED) and
     * the next jump is not even refused: the recorded target cell is still bound to a slot, so the burst
     * is spent before the departure discovers there is no ship to cut.
     */
    @Test
    public void anArrivalThatNeverCompletesNeverLeavesTheLedgerDisagreeingWithTheTransitMap() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        ShipLedger ledger = new ShipLedger();
        FakeCrosser crosser = new FakeCrosser();
        // The crossing never produces a paste anchor, for longer than any budget the manager may hold.
        crosser.arriveFailCount = Integer.MAX_VALUE;
        crosser.completeRestoredFailCount = Integer.MAX_VALUE;
        crosser.sourceSnapshotToReturn = new NBTTagCompound(); // the depart-time floor snapshot exists
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser, ledger, () -> 0L);
        UUID ship = UUID.randomUUID();

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(ship.toString(), cell(1), originDim, new BlockPos(0, 64, 0), cell(2),
                ARRIVE_IN_ONE_TICK);
        assertEquals("departure marks the ledger in flight",
                ShipLedger.State.IN_TRANSIT, ledger.get(ship).state);

        // Well past any give-up budget: the arrival has been retried and retried.
        for (int i = 0; i < 400; i++) {
            mgr.tick();
        }

        ShipLedger.Entry entry = ledger.get(ship);
        assertNotNull("the ship must still be ledgered at all", entry);
        if (mgr.isInTransit(ship.toString())) {
            assertEquals("still flying, so the ledger must still say so",
                    ShipLedger.State.IN_TRANSIT, entry.state);
        } else {
            assertEquals("no longer flying, so the ledger must name a place the player can act on",
                    ShipLedger.State.SETTLED, entry.state);
        }
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
        mgr.importTransit(new TransitRecord(ship.toString(), cell(1), cell(2), 4_000_000L, 0L, 10L, 0L, 1L,
                new ArrayList<UUID>(), new NBTTagCompound()));

        assertEquals("still exactly one transit", 1, mgr.inTransitCount());
        assertEquals("the live lane is untouched", 1, tiles.inUseCount());
    }

    @Test
    public void refreshingRecutsALiveShipSnapshotFromHyperspace() {
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

        assertEquals("the parked ship is re-cut", 1, mgr.refreshSnapshots());
        TransitRecord r = mgr.exportTransits().get(0);
        assertNotNull("the transit carries a block snapshot", r.snapshot);
        assertEquals("and it is the freshly re-cut one, not the depart-time floor", 42,
                r.snapshot.getInteger("marker"));
    }

    /**
     * A save point reads what the transit already carries and asks the physics world nothing. This is the
     * whole of the contract: the export runs inside the server's save pass, where a throw does not merely
     * fail but aborts the pass for every remaining world — and once did, taking the fleet with it.
     */
    @Test
    public void exportingForASaveAsksThePhysicsWorldNothing() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();
        crosser.sourceSnapshotToReturn = new NBTTagCompound();
        crosser.sourceSnapshotToReturn.setInteger("floor", 1);
        crosser.snapshotToReturn = new NBTTagCompound();
        crosser.snapshotToReturn.setInteger("recut", 1);
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser,
                new ShipLedger(), () -> 0L);

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(UUID.randomUUID().toString(), cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), 7L);
        int afterDepart = crosser.snapshotParkedCalls;

        TransitRecord r = mgr.exportTransits().get(0);
        assertEquals("exporting for a save re-cuts nothing from the live world",
                afterDepart, crosser.snapshotParkedCalls);
        assertNotNull("it still persists the ship, from the snapshot the jump carries", r.snapshot);
        assertEquals("what a save writes is the depart-time floor cut, untouched by the export", 1,
                r.snapshot.getInteger("floor"));

        // The witness that this counter can move at all: the same crosser, asked by the refresh.
        assertEquals("control: the re-cut path DOES ask the physics world", 1, mgr.refreshSnapshots());
        assertEquals("control: and the ask reaches the crosser", afterDepart + 1,
                crosser.snapshotParkedCalls);
    }

    /**
     * A physics world that fails the cut must cost a stale snapshot, never the ship: the one it carries is
     * the only durable copy of a hull that is mid-jump, and hyperspace does not survive a restart.
     */
    @Test
    public void aFailedRecutKeepsTheSnapshotTheJumpAlreadyCarries() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();
        crosser.sourceSnapshotToReturn = new NBTTagCompound();
        crosser.sourceSnapshotToReturn.setInteger("floor", 1);
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser,
                new ShipLedger(), () -> 0L);

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(UUID.randomUUID().toString(), cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), 7L);

        crosser.snapshotParkedThrows = true;
        assertEquals("nothing was refreshed", 0, mgr.refreshSnapshots());

        TransitRecord r = mgr.exportTransits().get(0);
        assertNotNull("the jump still has a snapshot to be restored from", r.snapshot);
        assertEquals("it is the last good one", 1, r.snapshot.getInteger("floor"));
        assertEquals("and the jump is still in flight", 1, mgr.inTransitCount());
    }

    /**
     * Once the arrival crossing has landed the ship in its target cell, the hyperspace hull it was cut
     * from is gone — but the transit stays in the map while the pose settle retries. Re-cutting there
     * would read a hyperspace lane that no longer holds this ship, and the lookup underneath is
     * unbounded, so with a second jump in flight it answers with THAT ship: the snapshot of a hull the
     * player is about to be standing on would be replaced by somebody else's.
     */
    @Test
    public void aShipWhoseArrivalHasAlreadyLandedIsNotRecutFromHyperspace() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();
        crosser.sourceSnapshotToReturn = new NBTTagCompound();
        crosser.sourceSnapshotToReturn.setInteger("floor", 1);
        crosser.snapshotToReturn = new NBTTagCompound();
        crosser.snapshotToReturn.setInteger("recut", 1);
        crosser.settleFailCount = 5; // the ship is pasted; its pose settle keeps retrying
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser,
                new ShipLedger(), () -> 0L);

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(UUID.randomUUID().toString(), cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), ARRIVE_IN_ONE_TICK);
        assertEquals("control: while it is still parked, a re-cut is exactly what should happen",
                1, mgr.refreshSnapshots());

        mgr.tick(); // reaches the target: the arrival crossing pastes, the pose settle does not finish
        assertEquals("arrangement: the jump is still in flight (settling), not gone", 1, mgr.inTransitCount());

        assertEquals("a landed ship is not re-cut from a lane it no longer occupies",
                0, mgr.refreshSnapshots());
    }

    @Test
    public void refreshDoesNotRecutARestoredTransitButKeepsItsImportedSnapshot() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();
        crosser.snapshotToReturn = new NBTTagCompound(); // a would-be re-cut that MUST NOT be used
        crosser.snapshotToReturn.setInteger("recut", 1);
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser,
                new ShipLedger(), () -> 0L);
        String ship = UUID.randomUUID().toString();

        NBTTagCompound imported = new NBTTagCompound();
        imported.setInteger("imported", 7);
        mgr.importTransit(new TransitRecord(ship, cell(1), cell(2), 4_000_000L, 0L, 10L, 0L, 7L,
                new ArrayList<UUID>(), imported));

        assertEquals("a restored transit has no live hyperspace ship to re-cut", 0, mgr.refreshSnapshots());
        TransitRecord r = mgr.exportTransits().get(0);
        assertFalse("so nothing overwrote its snapshot", r.snapshot.hasKey("recut"));
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
        mgr.importTransit(new TransitRecord(ship, cell(1), cell(2), 4_000_000L, 0L, 10L, 0L, 1L,
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
        mgr.importTransit(new TransitRecord(UUID.randomUUID().toString(), cell(1), cell(2), 4_000_000L, 0L,
                10L, 0L, 1L, new ArrayList<UUID>(), null));
        assertEquals("a snapshot-less record is not imported as a doomed transit", 0, mgr.inTransitCount());

        // Blank/corrupt id (an absent NBT "shipId" reads as "") is likewise dropped.
        mgr.importTransit(new TransitRecord("", cell(1), cell(2), 4_000_000L, 0L, 10L, 0L, 1L,
                new ArrayList<UUID>(), new NBTTagCompound()));
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
    public void anAbortedDepartureIsANoOpForTheCrew() {
        // A jump that never leaves must leave nobody worse off. The capture runs FIRST — it has to,
        // the crossing is about to cut the blocks the crew is standing on — so by the time the cut
        // refuses, everyone aboard is already dismounted and standing in a cell whose ship is still
        // right there. Doing nothing at that point ejects the whole crew for a jump that did not
        // happen; the only honest end is to put them back exactly where they were.
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        crosser.crewToCapture.add(UUID.randomUUID());
        crosser.crewToCapture.add(UUID.randomUUID()); // a pilot and a passenger, not just a pilot
        crosser.failDepart = true;                    // the cut refuses
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser);

        int originDim = space.materialize(cell(1));
        BlockPos originAnchor = new BlockPos(0, 64, 0);
        boolean began = mgr.beginTransit("s", cell(1), originDim, originAnchor, cell(2),
                ARRIVE_IN_ONE_TICK);

        assertFalse("a departure whose cut refused has not begun", began);
        // The crew WAS taken off the ship, which is what makes the put-back obligatory rather than
        // optional. Without this the test would pass on a manager that never captured at all.
        assertEquals("the capture ran before the cut refused", 1, crosser.captureCalls.size());
        assertEquals("the aborted departure put its crew back, once", 1, crosser.reseatCalls.size());
        assertEquals("back into the cell they never left", originDim + "@s", crosser.reseatCalls.get(0));
        assertEquals("and onto the ship still sitting at its own anchor - not at the far end",
                originAnchor, crosser.reseatAnchors.get(0));

        // Nothing else moved: no flight exists, and the lane the attempt reserved went back to the
        // allocator rather than being held by a jump that is not happening.
        assertFalse("no flight was created", mgr.isInTransit("s"));
        assertEquals("nothing is in transit", 0, mgr.inTransitCount());
        assertEquals("and nobody is left waiting to be re-seated later", 0, mgr.reseatingCount());
        assertTrue("the origin cell is still loaded under them - the refcount handoff belongs to a"
                + " departure that happened", space.isLoaded(cell(1)));
        assertEquals("and it is the same world they were standing in", originDim,
                space.slotDimOf(cell(1)));

        // The lane came back: the next departure gets the same index, which it cannot do if the
        // aborted attempt is still holding it.
        crosser.failDepart = false;
        assertTrue(mgr.beginTransit("s", cell(1), originDim, originAnchor, cell(2),
                ARRIVE_IN_ONE_TICK));
        assertEquals("the aborted attempt freed its lane for the next jump",
                crosser.departs.get(0), crosser.departs.get(1));
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

        mgr.tick(); // the hull lands this tick; its crew is not aboard yet

        // THE JUMP IS NOT OVER WHILE ITS PEOPLE ARE NOT ABOARD. The hull has physically landed, and
        // that is deliberately NOT what finishes a transit: a crossing that carries crew and reports
        // success with a crew member still in the world it left has ended in a technical failure, and
        // a transition must not be able to do that. So the transit stays in flight, its lane stays
        // held, and nothing tells the crew they have arrived.
        assertTrue("a landed hull whose crew is not aboard is STILL in transit", mgr.isInTransit("s"));
        assertEquals("...and still counted in flight", 1, mgr.inTransitCount());
        assertEquals("the placement was attempted on the landing tick", 1, crosser.reseatCalls.size());

        mgr.tick(); // attempt 2 fails
        assertTrue("still in transit while the second attempt fails", mgr.isInTransit("s"));
        mgr.tick(); // attempt 3 succeeds

        assertFalse("the jump completes on the tick its crew is aboard, and not before",
                mgr.isInTransit("s"));
        assertEquals("...and leaves the in-flight map then", 0, mgr.inTransitCount());
        assertEquals("the placement was retried each tick until it took", 3, crosser.reseatCalls.size());
        assertEquals("nothing is left queued behind a completed jump", 0, mgr.reseatingCount());
    }

    /**
     * The half that has no budget: a placement that keeps failing keeps the jump OPEN rather than
     * completing it without its crew.
     *
     * <p>This is the clause the old design could not state. The transit settled on the landing tick and
     * handed the crew to a best-effort list which, on running out of attempts, dropped them — silently,
     * with no log line on that branch — leaving a player in the departure world while the ledger, the
     * chat message and the in-flight map all said the jump had succeeded.</p>
     */
    @Test
    public void aJumpWhoseCrewCannotBeSeatedNeverReportsSuccess() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();
        crosser.crewToCapture.add(UUID.randomUUID());
        crosser.reseatFailCount = Integer.MAX_VALUE; // the placement never takes
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser);

        int originDim = space.materialize(cell(1));
        mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), ARRIVE_IN_ONE_TICK);

        for (int tick = 0; tick < 400; tick++) {
            mgr.tick();
        }

        assertTrue("a jump whose crew cannot be put aboard stays in transit indefinitely - the ship is "
                + "not lost, the ledger keeps saying so, and a restart resumes it", mgr.isInTransit("s"));
        assertTrue("...and it keeps TRYING rather than settling into a dead state",
                crosser.reseatCalls.size() > 300);
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

    // -- durable hyperspace: a restart is something a jump SURVIVES (JUMP-9 / JUMP-10) ------------

    @Test
    public void aRestoredTransitKeepsTheLaneAndTheShipItLeftParkedIn() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        BlockPos parkedAt = HyperspaceTiles.tilePos(3);
        crosser.parkedAnchors.add(parkedAt);          // hyperspace kept it across the restart
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser, new ShipLedger(), () -> 0L);
        String ship = UUID.randomUUID().toString();

        mgr.importTransit(new TransitRecord(ship, cell(1), cell(2), 4_000_000L, 0L, 10_000L, 0L, 7L,
                new ArrayList<UUID>(), new NBTTagCompound(), 3, parkedAt));

        // The two questions a returning crew member's placement is decided by. Both answer "nowhere"
        // for a transit with no physical ship, and both have to answer about the parked hull now.
        assertEquals("a jump whose ship is still parked resumes with that ship, so its crew belongs"
                + " in the parked world", crosser.parkedDim(), mgr.crewDimensionOf(ship));
        assertEquals("...and at the ship, not at the world origin", parkedAt, mgr.hyperspaceAnchorOf(ship));
    }

    @Test
    public void aRestoredTransitWhoseLaneCameBackEmptyStillRebuildsFromItsSnapshot() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();   // parkedAnchors empty: the hull did not survive
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser,
                new ShipLedger(), () -> 0L);
        String ship = UUID.randomUUID().toString();

        mgr.importTransit(new TransitRecord(ship, cell(1), cell(2), 4_000_000L, 0L, 10L, 0L,
                ARRIVE_IN_ONE_TICK, new ArrayList<UUID>(), new NBTTagCompound(), 3,
                HyperspaceTiles.tilePos(3)));
        mgr.tick();

        assertFalse("with no ship in its lane the jump falls back to pasting its snapshot - which is"
                + " what that snapshot has always been for", crosser.restoredCompletions.isEmpty());
    }

    @Test
    public void aReclaimedLaneIsNeverHandedToTheNextDeparture() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        BlockPos parkedAt = HyperspaceTiles.tilePos(0);
        crosser.parkedAnchors.add(parkedAt);
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser, new ShipLedger(), () -> 0L);

        mgr.importTransit(new TransitRecord(UUID.randomUUID().toString(), cell(1), cell(2), 4_000_000L,
                0L, 10_000L, 0L, 7L, new ArrayList<UUID>(), new NBTTagCompound(), 0, parkedAt));

        int originDim = space.materialize(cell(3));
        mgr.beginTransit("fresh", cell(3), originDim, new BlockPos(0, 64, 0), cell(4), 7L);

        assertFalse("a departure must not be pasted into the lane a restored ship is standing in",
                crosser.departs.contains(originDim + "@0"));
    }

    @Test
    public void bootDisposesOfEveryParkedShipNoRecordClaims() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        BlockPos claimedLane = HyperspaceTiles.tilePos(1);
        crosser.parkedAnchors.add(claimedLane);
        crosser.shipsParkedAt.add(HyperspaceTiles.tilePos(0));   // a hull whose record did not survive
        crosser.shipsParkedAt.add(HyperspaceTiles.tilePos(1));   // ...and one that did
        crosser.shipsParkedAt.add(HyperspaceTiles.tilePos(4));   // another orphan
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser, new ShipLedger(), () -> 0L);

        mgr.importTransit(new TransitRecord(UUID.randomUUID().toString(), cell(1), cell(2), 4_000_000L,
                0L, 10_000L, 0L, 7L, new ArrayList<UUID>(), new NBTTagCompound(), 1, claimedLane));

        int disposed = mgr.reconcileParkedShips();

        assertEquals("both unclaimed hulls disposed of", 2, disposed);
        assertTrue("the orphans, by lane: " + crosser.disposedLanes,
                crosser.disposedLanes.contains(0) && crosser.disposedLanes.contains(4));
        assertFalse("the ship a record DOES claim is left alone - this is a check, not a demolition",
                crosser.disposedLanes.contains(1));
    }

    /**
     * The reconciliation must find a hull WHEREVER it is standing, and the lanes it has to reach are
     * precisely the ones no surviving record points at. Anything that derives the reach from what the
     * records reclaimed asks the orphans to announce themselves.
     */
    @Test
    public void anOrphanIsFoundInALaneNoSurvivingRecordCameNear() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        BlockPos claimedLane = HyperspaceTiles.tilePos(1);
        crosser.parkedAnchors.add(claimedLane);
        crosser.shipsParkedAt.add(claimedLane);
        // A ring further out than anything the surviving record touches, and well past the reach the
        // allocator would have had: it reclaimed lane 1, so a bound derived from it stopped at 3.
        crosser.shipsParkedAt.add(HyperspaceTiles.tilePos(12));
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser, new ShipLedger(), () -> 0L);

        mgr.importTransit(new TransitRecord(UUID.randomUUID().toString(), cell(1), cell(2), 4_000_000L,
                0L, 10_000L, 0L, 7L, new ArrayList<UUID>(), new NBTTagCompound(), 1, claimedLane));

        int disposed = mgr.reconcileParkedShips();

        assertEquals("the far orphan is the one this exists for: it is the hull whose record is gone,"
                + " so nothing points at its lane - disposed of: " + crosser.disposedLanes, 1, disposed);
        assertTrue("by lane: " + crosser.disposedLanes, crosser.disposedLanes.contains(12));
    }

    /**
     * The other half of the same promise, and the one that used to be silent: a lane is spoken for
     * because a hull is STANDING in it, not because we managed to get rid of that hull.
     */
    @Test
    public void aLaneWhoseHullCouldNotBeDisposedOfIsStillNeverHandedOut() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        FakeCrosser crosser = new FakeCrosser();
        crosser.shipsParkedAt.add(HyperspaceTiles.tilePos(0));
        crosser.disposeSucceeds = false; // e.g. the hull's chunks are still streaming in
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, crosser, new ShipLedger(), () -> 0L);

        int disposed = mgr.reconcileParkedShips();

        assertEquals("nothing was disposed of - that is the premise, not the claim", 0, disposed);
        assertTrue("CONTROL: the reconciliation must have tried, or the lane is untouched for the"
                + " wrong reason", crosser.disposedLanes.contains(0));

        int originDim = space.materialize(cell(3));
        mgr.beginTransit("fresh", cell(3), originDim, new BlockPos(0, 64, 0), cell(4), 7L);

        assertFalse("a departure must not be parked on top of a hull that is provably still there:"
                + " departures so far " + crosser.departs,
                crosser.departs.contains(originDim + "@0"));
    }

    @Test
    public void aRecordWithNeitherAShipNorASnapshotIsDropped() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser,
                new ShipLedger(), () -> 0L);
        String ship = UUID.randomUUID().toString();

        mgr.importTransit(new TransitRecord(ship, cell(1), cell(2), 4_000_000L, 0L, 10L, 0L, 7L,
                new ArrayList<UUID>(), null, 2, HyperspaceTiles.tilePos(2)));

        assertFalse("nothing to restore: no hull in the lane and no blocks on record",
                mgr.isInTransit(ship));
    }

    @Test
    public void aSnapshotlessRecordSurvivesWhenItsShipIsStillParked() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        FakeCrosser crosser = new FakeCrosser();
        BlockPos parkedAt = HyperspaceTiles.tilePos(2);
        crosser.parkedAnchors.add(parkedAt);
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), crosser,
                new ShipLedger(), () -> 0L);
        String ship = UUID.randomUUID().toString();

        mgr.importTransit(new TransitRecord(ship, cell(1), cell(2), 4_000_000L, 0L, 10_000L, 0L, 7L,
                new ArrayList<UUID>(), null, 2, parkedAt));

        assertTrue("a ship standing in its lane needs no stored blocks to be restorable - the record"
                + " that used to be rejected out of hand is the ordinary case now",
                mgr.isInTransit(ship));
    }

    @Test
    public void anExportedTransitCarriesTheLaneItIsParkedIn() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), new FakeCrosser(),
                new ShipLedger(), () -> 500L);
        String ship = UUID.randomUUID().toString();

        int originDim = space.materialize(cell(1));
        mgr.beginTransit(ship, cell(1), originDim, new BlockPos(0, 64, 0), cell(2), 7L);

        TransitRecord r = mgr.exportTransits().get(0);
        assertTrue("the lane is persisted, or a restore cannot know which one is taken",
                r.laneIndex >= 0);
        assertEquals("...and where in it the hull actually landed",
                HyperspaceTiles.tilePos(r.laneIndex), r.hyperAnchor);
    }
}