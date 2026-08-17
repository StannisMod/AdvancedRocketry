package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.space.CellFrames;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.HyperspaceTiles;
import zmaster587.advancedRocketry.space.ShipCrossingService;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.ShipTransitManager;
import zmaster587.advancedRocketry.space.SlotBinder;
import zmaster587.advancedRocketry.space.SpaceManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A jump short enough to be over before it presents itself is performed as ONE cell&rarr;cell crossing,
 * not flown through hyperspace.
 *
 * <p>What these pin is the DECISION and its consequences, counted rather than timed: how many crossings
 * a jump costs, whether a lane is taken, whether a snapshot is cut, and what the ledger says while it
 * happens. Timing would pin this machine.</p>
 */
public class ShortJumpCrossesDirectlyTest {

    private static GalacticCoord cell(long s) {
        return GalacticCoord.ofSectorLocal(s, 0L, 0L, 0L, 0L, 0L);
    }

    private static final class FakeBinder implements SlotBinder {
        final int[] dims;
        FakeBinder(int... dims) { this.dims = dims; }
        @Override public int[] slotDims() { return dims; }
        @Override public void load(int dimId, String cellKey) { }
        @Override public void unload(int dimId) { }
        @Override public void discard(int dimId) { }
        @Override public void deleteStore(String cellKey) { }
    }

    /** Counts the hyperspace legs. A direct jump must not touch any of them. */
    private static final class CountingCrosser implements ShipTransitManager.Crosser {
        int departs;
        int sourceSnapshots;

        @Override
        public ShipCrossingService.Crossed departToHyperspace(int srcSlotDim, BlockPos srcAnchor,
                                                             String shipId, HyperspaceTiles.Tile tile) {
            departs++;
            return new ShipCrossingService.Crossed(new BlockPos(0, 200, 0), UUID.randomUUID());
        }

        @Override
        public ShipCrossingService.Crossed arriveFromHyperspace(String shipId, HyperspaceTiles.Tile tile,
                                                               BlockPos hyperAnchor, int targetSlotDim) {
            return new ShipCrossingService.Crossed(new BlockPos(0, 200, 0), UUID.randomUUID());
        }

        @Override
        public NBTTagCompound snapshotSource(int srcSlotDim, BlockPos srcAnchor) {
            sourceSnapshots++;
            return new NBTTagCompound();
        }
    }

    /** Counts the direct crossings, and can refuse one. */
    private static final class CountingDirectCrosser implements ShipTransitManager.DirectCrosser {
        final List<String> crossings = new ArrayList<>();
        boolean refuse;

        @Override
        public boolean crossDirect(String shipId, GalacticCoord origin, int originSlotDim,
                                   BlockPos originAnchor, GalacticCoord target) {
            crossings.add(shipId + " " + origin.cellKey() + "->" + target.cellKey());
            return !refuse;
        }
    }

    private static SpaceManager.Config never() {
        return new SpaceManager.Config(SpaceManager.GcPolicy.NEVER, 0L, 0);
    }

    /** How far the fixture's two cells are apart, read the way the departure reads it. */
    private static double fixtureDistance() {
        return CellFrames.STATIC.distanceBetween(cell(1), cell(2), 0L);
    }

    /** Fast enough that the whole leg fits inside the threshold — the direct case. */
    private static long directSpeed() {
        return (long) Math.ceil(fixtureDistance() / ShipTransitManager.DIRECT_CROSSING_MAX_TICKS);
    }

    /** Slow enough for a real flight: twice the threshold in ticks, so no rounding can reach it. */
    private static long flightSpeed() {
        return Math.max(1L,
                (long) (fixtureDistance() / (ShipTransitManager.DIRECT_CROSSING_MAX_TICKS * 2.0d)));
    }

    /**
     * The rule reads a DURATION, and its boundary is the point at which a flight stops having a middle.
     * Stated in ticks with no geometry in the way: one block per tick makes distance and duration the
     * same number.
     */
    @Test
    public void theRuleTurnsOverAtTheTickWhereAFlightStopsHavingACruise() {
        long n = ShipTransitManager.DIRECT_CROSSING_MAX_TICKS;

        assertTrue("a leg of exactly N ticks has no cruise, so it is a crossing",
                ShipTransitManager.isDirectCrossing(n, 1L));
        assertFalse("one tick more and there is a flight to fly",
                ShipTransitManager.isDirectCrossing(n + 1, 1L));
        assertTrue("a fast enough drive makes a LONG leg short — the rule keys on duration, never "
                        + "on how far away the destination is",
                ShipTransitManager.isDirectCrossing(n * 1_000_000.0d, 1_000_000L));
    }

    @Test
    public void aShortJumpCostsExactlyOneCrossingAndTakesNoLane() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        CountingCrosser hyperspace = new CountingCrosser();
        CountingDirectCrosser direct = new CountingDirectCrosser();
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, hyperspace);
        mgr.setDirectCrosser(direct);

        int originDim = space.materialize(cell(1));
        boolean began = mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), directSpeed());

        assertTrue("the short jump was performed", began);
        assertEquals("exactly one crossing", 1, direct.crossings.size());
        assertEquals("and none of them through hyperspace", 0, hyperspace.departs);
        assertEquals("no hyperspace lane is taken by a jump that never enters hyperspace",
                0, tiles.inUseCount());
        assertEquals("nothing is in transit: there is no flight to be in the middle of",
                0, mgr.inTransitCount());
        assertFalse(mgr.isInTransit("s"));
    }

    @Test
    public void aShortJumpCutsNoSnapshotBecauseItHasNoMidFlightToRestore() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        CountingCrosser hyperspace = new CountingCrosser();
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(), hyperspace);
        mgr.setDirectCrosser(new CountingDirectCrosser());

        int originDim = space.materialize(cell(1));
        mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), directSpeed());

        assertEquals("the depart-time floor cut exists to survive a restart mid-flight, and a crossing "
                + "has no mid-flight; cutting one would persist a record of a jump nothing resumes",
                0, hyperspace.sourceSnapshots);
    }

    /**
     * The ledger is what a login reads. A row saying IN_TRANSIT resolves the player through the shared
     * hyperspace world, so a direct crossing must never wear it — the ship's blocks are in a cell.
     */
    @Test
    public void aShortJumpNeverEntersTheInTransitState() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        ShipLedger ledger = new ShipLedger();
        ShipTransitManager mgr = new ShipTransitManager(space, new HyperspaceTiles(),
                new CountingCrosser(), ledger, () -> 1000L);
        mgr.setDirectCrosser(new CountingDirectCrosser());
        UUID ship = UUID.randomUUID();

        int originDim = space.materialize(cell(1));
        assertTrue(mgr.beginTransit(ship.toString(), cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), directSpeed()));

        ShipLedger.Entry e = ledger.get(ship);
        // The crossing itself settles the row; this manager must not have written IN_TRANSIT over it.
        assertTrue("the transit manager must not have put a direct crossing in transit",
                e == null || e.state != ShipLedger.State.IN_TRANSIT);
    }

    @Test
    public void aLongJumpStillFliesThroughHyperspaceWithItsLaneAndItsSnapshot() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        CountingCrosser hyperspace = new CountingCrosser();
        CountingDirectCrosser direct = new CountingDirectCrosser();
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, hyperspace);
        mgr.setDirectCrosser(direct);

        int originDim = space.materialize(cell(1));
        boolean began = mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), flightSpeed());

        assertTrue(began);
        assertEquals("a real flight is not a crossing", 0, direct.crossings.size());
        assertEquals("it departs into hyperspace", 1, hyperspace.departs);
        assertEquals("holding a lane", 1, tiles.inUseCount());
        assertEquals("and carrying a snapshot, because it HAS a mid-flight to restore",
                1, hyperspace.sourceSnapshots);
        assertTrue(mgr.isInTransit("s"));
    }

    /**
     * A refused crossing is a FAILED jump, not a jump by another route. The pilot has already paid the
     * drive's burst against the mechanism he was quoted; quietly flying him through hyperspace instead
     * would charge him for one flight and give him another, and would hide the refusal from the log.
     */
    @Test
    public void aRefusedShortJumpFailsRatherThanFallingBackToHyperspace() {
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        CountingCrosser hyperspace = new CountingCrosser();
        CountingDirectCrosser direct = new CountingDirectCrosser();
        direct.refuse = true;
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, hyperspace);
        mgr.setDirectCrosser(direct);

        int originDim = space.materialize(cell(1));
        boolean began = mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0),
                cell(2), directSpeed());

        assertFalse("the jump failed", began);
        assertEquals("it was attempted once", 1, direct.crossings.size());
        assertEquals("and not retried down the other path", 0, hyperspace.departs);
        assertEquals("no lane was consumed by the failure", 0, tiles.inUseCount());
        assertEquals(0, mgr.inTransitCount());
    }

    /**
     * The forecast and the flight must not be able to disagree. There is one predicate and both call
     * it, so this pins the property that keeps them together rather than re-deriving the rule: the same
     * (distance, speed) pair answers the same way however many times it is asked.
     */
    @Test
    public void theForecastAndTheDepartureCannotDisagreeBecauseThereIsOnlyOneRule() {
        double distance = fixtureDistance();
        long speed = directSpeed();

        boolean quoted = ShipTransitManager.isDirectCrossing(distance, speed);

        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), () -> 0L, never());
        HyperspaceTiles tiles = new HyperspaceTiles();
        CountingDirectCrosser direct = new CountingDirectCrosser();
        ShipTransitManager mgr = new ShipTransitManager(space, tiles, new CountingCrosser());
        mgr.setDirectCrosser(direct);
        int originDim = space.materialize(cell(1));
        mgr.beginTransit("s", cell(1), originDim, new BlockPos(0, 64, 0), cell(2), speed);

        boolean executed = !direct.crossings.isEmpty();
        assertEquals("what the console would quote is what the drive performed", quoted, executed);
        assertNull("and the lane allocator was never asked for one",
                tiles.inUseCount() == 0 ? null : "a lane was taken");
    }
}
