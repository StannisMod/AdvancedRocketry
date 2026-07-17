package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.CrewTransfer;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipEntryController;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.SlotBinder;
import zmaster587.advancedRocketry.space.SpaceManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the entry on-ramp state machine, driven against the real {@link SpaceManager}
 * with a recording fake {@link ShipEntryController.Ops} (the transit-manager test discipline).
 * Pins the decisions: an exhausted pool REFUSES entry as a normal surfaced outcome (message +
 * cooldown, cell not leaked); a started entry settles the ship in the ledger at a ring coordinate
 * OUTSIDE the descent radius (the entry&harr;descent hysteresis contract); the crossed cell is
 * marked dirty; a failed crossing releases the cell.
 */
public class ShipEntryControllerTest {

    private static final UUID SHIP = UUID.fromString("00000000-0000-0000-0000-0000000000AA");
    private static final BlockPos AFC = new BlockPos(1, 65, 1);
    private static final int LAUNCH_DIM = 0;

    private static GalacticCoord body(long sector) {
        return GalacticCoord.ofSectorLocal(sector, 0L, 0L, 0L, 0L, 0L);
    }

    /** Recording binder (mirrors SpaceManagerTest) so the entry tests drive a real SpaceManager. */
    private static final class FakeBinder implements SlotBinder {
        final int[] dims;
        FakeBinder(int... dims) { this.dims = dims; }
        @Override public int[] slotDims() { return dims; }
        @Override public void load(int dimId, String cellKey) { }
        @Override public void unload(int dimId) { }
        @Override public void discard(int dimId) { }
        @Override public void deleteStore(String cellKey) { }
    }

    /** Recording ops: a ship exists at the launch pad; knobs force each failure mode. */
    private static final class FakeOps implements ShipEntryController.Ops {
        boolean shipPresent = true;
        boolean failCross;
        int reseatFailCount;
        int teleportFailCount;
        final List<String> messages = new ArrayList<>();
        final List<Integer> pinned = new ArrayList<>();
        final List<double[]> teleports = new ArrayList<>();
        int unparks;
        int crossings;

        @Override public double[] shipWorldPosition(int dimId, BlockPos afcPos) {
            return shipPresent ? new double[]{10.0, 1200.0, 10.0} : null;
        }

        @Override public List<CrewTransfer.Crew> captureCrew(int dimId, BlockPos afcPos,
                                                             double[] shipWorldPos) {
            return new ArrayList<>(); // crew mechanics are integration-tier; none here
        }

        @Override public BlockPos cross(int srcDimId, double[] srcShipPos, int slotDim,
                                        int pasteX, int pasteY, int pasteZ) {
            crossings++;
            return failCross ? null : new BlockPos(pasteX, pasteY, pasteZ);
        }

        @Override public void pinDim(int dimId) { pinned.add(dimId); }

        @Override public void loadShips(int slotDim) { }

        @Override public boolean reseat(int slotDim, BlockPos anchor, List<CrewTransfer.Crew> crew) {
            if (reseatFailCount > 0) { reseatFailCount--; return false; }
            return true;
        }

        @Override public boolean teleportPoseWithRiders(int slotDim, BlockPos anchor,
                                                        double px, double py, double pz) {
            if (teleportFailCount > 0) { teleportFailCount--; return false; }
            teleports.add(new double[]{px, py, pz});
            return true;
        }

        @Override public void unparkAt(int slotDim, double px, double py, double pz) { unparks++; }

        @Override public void messageCrew(List<CrewTransfer.Crew> crew, String langKey, Object... args) {
            messages.add(langKey);
        }
    }

    private static SpaceManager.Config never() {
        return new SpaceManager.Config(SpaceManager.GcPolicy.NEVER, 0, 0);
    }

    @Test
    public void entryCrossesSettlesInLedgerOutsideTheDescentRadius() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), clock::get, never());
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

        assertTrue(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertTrue(ctl.isEntering(SHIP));
        assertEquals("slot pinned across the async crossing", 1, ops.pinned.size());

        ctl.tick(); // reseat + pose teleport
        ctl.tick(); // unpark + settle

        assertFalse(ctl.isEntering(SHIP));
        ShipLedger.Entry entry = ledger.get(SHIP);
        assertNotNull("the ship is ledgered after entry", entry);
        assertEquals(ShipLedger.State.SETTLED, entry.state);
        assertTrue("entry cell is live", space.isLoaded(entry.coord));
        assertEquals("entered the launch body's own cell", body(5).cellKey(), entry.cellKey());
        // The hysteresis contract: the spawn ring lies strictly OUTSIDE the descent radius, so a
        // fresh entry can never immediately trip the descent trigger.
        double distance = entry.coord.distanceTo(body(5));
        assertTrue("ring distance " + distance + " must exceed the descent radius",
                distance > ShipEntryController.DESCENT_RADIUS_BLOCKS);
        // The realized pose matches the ledgered coordinate (the honest-3D mapping).
        double[] expectedPose = CellWorldMapper.poseWorldOf(entry.coord);
        assertEquals(1, ops.teleports.size());
        assertEquals(expectedPose[1], ops.teleports.get(0)[1], 0.0);
        assertEquals("ship unparked after the pose write", 1, ops.unparks);
        assertEquals("crew told they arrived", "msg.shipentry.arrived",
                ops.messages.get(ops.messages.size() - 1));
    }

    @Test
    public void exhaustedPoolRefusesEntryWithoutLeakingTheCell() {
        AtomicLong clock = new AtomicLong();
        // Pool of ONE, already held by another occupied cell -> materialize must throw.
        SpaceManager space = new SpaceManager(new FakeBinder(10), clock::get, never());
        space.materialize(body(99)); // refcount 1, not evictable
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

        assertFalse("entry is refused", ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertFalse(ctl.isEntering(SHIP));
        assertNull("nothing ledgered on a refusal", ledger.get(SHIP));
        assertEquals("no crossing was attempted", 0, ops.crossings);
        assertEquals("the pilot is told", "msg.shipentry.refused", ops.messages.get(0));

        // The refusal armed a cooldown: an immediate retry is silently ignored (no message spam).
        assertFalse(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertEquals("no second message inside the cooldown", 1, ops.messages.size());

        // After the cooldown (and pool pressure gone) the entry goes through.
        space.dematerialize(body(99));
        clock.addAndGet(1000L);
        assertTrue("entry retries after the cooldown", ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
    }

    @Test
    public void failedCrossingReleasesTheCellAndArmsTheCooldown() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10), clock::get, never());
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ops.failCross = true;
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

        assertFalse(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertFalse(ctl.isEntering(SHIP));
        assertNull(ledger.get(SHIP));
        assertEquals("msg.shipentry.failed", ops.messages.get(0));
        // The refcount was released: another ship can claim the single slot right away.
        SpaceManager probe = space; // same manager
        assertEquals("the failed entry's cell holds no occupant",
                10, probe.materialize(body(77)));
    }

    @Test
    public void settleRetriesWhileTheAsyncReassemblyIsNotUpYet() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), clock::get, never());
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ops.reseatFailCount = 2;   // seats resolve on the third tick
        ops.teleportFailCount = 1; // ship queryable one tick after that
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

        assertTrue(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        for (int i = 0; i < 4; i++) {
            assertTrue("still settling on tick " + i, ctl.isEntering(SHIP));
            ctl.tick();
        }
        ctl.tick(); // the unpark+settle tick after the successful pose write

        assertFalse("settled despite the slow re-assembly", ctl.isEntering(SHIP));
        assertNotNull(ledger.get(SHIP));
    }

    @Test
    public void duplicateAndLedgeredShipsDoNotReenter() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), clock::get, never());
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

        assertTrue(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertFalse("an in-flight entry is not restarted", ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertEquals(1, ops.crossings);

        ctl.tick();
        ctl.tick();
        assertFalse("a ship already in space cannot enter again",
                ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertEquals(1, ops.crossings);
    }

    @Test
    public void triggerPredicateGatesOnWorldPilotAndCeiling() {
        assertTrue(ShipEntryController.shouldTriggerEntry(false, true, 1001.0, 1000));
        assertFalse("no pilot, no entry",
                ShipEntryController.shouldTriggerEntry(false, false, 1001.0, 1000));
        assertFalse("below the ceiling",
                ShipEntryController.shouldTriggerEntry(false, true, 999.0, 1000));
        assertFalse("never from a space-subsystem world",
                ShipEntryController.shouldTriggerEntry(true, true, 1001.0, 1000));
    }
}
