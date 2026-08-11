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
import zmaster587.advancedRocketry.space.ShipCrossingService;
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
 * with a recording fake {@link ShipCrossingService.Ops} (the transit-manager test discipline).
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
    private static final class FakeOps implements ShipCrossingService.Ops {
        boolean shipPresent = true;
        boolean failCross;
        int reseatFailCount;
        int teleportFailCount;
        final List<String> messages = new ArrayList<>();
        final List<Integer> pinned = new ArrayList<>();
        final List<double[]> teleports = new ArrayList<>();
        final List<Integer> reseatDims = new ArrayList<>();
        /** The identity the cross hands back — every settle half must address THIS ship. */
        static final UUID CROSSED_SHIP = UUID.fromString("11111111-2222-3333-4444-555555555555");
        /** What each settle half actually named; a null is a position lookup, i.e. the defect. */
        final List<UUID> reseatShipUuids = new ArrayList<>();
        final List<UUID> teleportShipUuids = new ArrayList<>();
        final List<UUID> unparkShipUuids = new ArrayList<>();
        int unparks;
        int crossings;
        int captures;
        int peeks;
        int latches;
        List<CrewTransfer.Crew> lastCaptured;
        List<CrewTransfer.Crew> lastReseated;

        @Override public double[] shipWorldPosition(int dimId, BlockPos afcPos) {
            return shipPresent ? new double[]{10.0, 1200.0, 10.0} : null;
        }

        @Override public List<CrewTransfer.Crew> captureCrew(int dimId, BlockPos afcPos,
                                                             double[] shipWorldPos) {
            captures++;
            lastCaptured = new ArrayList<>(); // crew mechanics are integration-tier; none here
            return lastCaptured;
        }

        @Override public List<CrewTransfer.Crew> peekCrew(int dimId, BlockPos afcPos,
                                                          double[] shipWorldPos) {
            peeks++;
            return new ArrayList<>();
        }

        @Override public ShipCrossingService.Crossed cross(int srcDimId, double[] srcShipPos,
                                        int slotDim, int pasteX, int pasteY, int pasteZ) {
            crossings++;
            return failCross ? null : new ShipCrossingService.Crossed(
                    new BlockPos(pasteX, pasteY, pasteZ), CROSSED_SHIP);
        }

        @Override public void pinDim(int dimId) { pinned.add(dimId); }



        @Override public boolean reseat(int slotDim, BlockPos anchor, List<CrewTransfer.Crew> crew,
                java.util.UUID shipId, java.util.UUID vsShipUuid) {
            reseatDims.add(slotDim);
            reseatShipUuids.add(vsShipUuid);
            lastReseated = crew;
            if (reseatFailCount > 0) { reseatFailCount--; return false; }
            return true;
        }

        @Override public boolean teleportPoseWithRiders(int slotDim, BlockPos anchor,
                                                        java.util.UUID vsShipUuid,
                                                        double px, double py, double pz) {
            if (teleportFailCount > 0) { teleportFailCount--; return false; }
            teleports.add(new double[]{px, py, pz});
            teleportShipUuids.add(vsShipUuid);
            return true;
        }

        @Override public void unpark(int slotDim, java.util.UUID vsShipUuid,
                                     double px, double py, double pz) {
            unparks++;
            unparkShipUuids.add(vsShipUuid);
        }

        @Override public void messageCrew(List<CrewTransfer.Crew> crew, String langKey, Object... args) {
            messages.add(langKey);
        }

        /** Descent-only op; an entry never latches. Counted so the test can say so. */
        @Override public void latchEntryUntilBelowTheLine(int dimId, BlockPos afcPos) { latches++; }
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

        ctl.tick(); // pose teleport (runs FIRST: the split-pair invariant)
        ctl.tick(); // re-seat at the pose
        ctl.tick(); // unpark + settle

        assertFalse(ctl.isEntering(SHIP));
        ShipLedger.Entry entry = ledger.get(SHIP);
        assertNotNull("the ship is ledgered after entry", entry);
        assertEquals(ShipLedger.State.SETTLED, entry.state);
        assertTrue("entry cell is live", space.isLoaded(entry.coord));
        assertEquals("entered the launch body's own cell", body(5).cellKey(), entry.cellKey());
        // The hysteresis contract: the spawn ring lies strictly OUTSIDE the descent radius, so a
        // fresh entry can never immediately trip the descent trigger.
        double distance = entry.coord.staticFrameDistanceTo(body(5));
        assertTrue("ring distance " + distance + " must exceed the descent radius",
                distance > ShipEntryController.DESCENT_RADIUS_BLOCKS);
        // The realized pose matches the ledgered coordinate (the honest-3D mapping).
        double[] expectedPose = CellWorldMapper.poseWorldOf(entry.coord);
        assertEquals(1, ops.teleports.size());
        assertEquals(expectedPose[1], ops.teleports.get(0)[1], 0.0);
        assertEquals("ship unparked after the pose write", 1, ops.unparks);
        assertEquals("crew told they arrived", "msg.shipentry.arrived",
                ops.messages.get(ops.messages.size() - 1));
        // The post-descent entry latch belongs to the descent alone. An entry that latched would
        // hold its own on-ramp off the next time the ship came back down and tried to leave again.
        assertEquals("an entry never latches the entry on-ramp", 0, ops.latches);
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
        // A refusal must leave the pilot in his seat: the crew is READ for the message, never
        // captured (a capture dismounts every rider and retires the mounts).
        assertEquals("a refused entry never captures (= unseats) the crew", 0, ops.captures);
        assertTrue("the refusal message still reaches the crew via the read-only peek",
                ops.peeks >= 1);
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
        // The cut never happened, so the crew captured just before it must be put BACK on their
        // seats — in the LAUNCH world the intact ship still sits in.
        assertEquals("the captured crew is re-seated after the failed cut", 1, ops.reseatDims.size());
        assertEquals("the re-seat targets the launch world", LAUNCH_DIM,
                (int) ops.reseatDims.get(0));
        assertTrue("the re-seat restores exactly the crew the capture took",
                ops.lastReseated == ops.lastCaptured);
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
        ops.teleportFailCount = 2; // re-assembly queryable on the third tick
        ops.reseatFailCount = 1;   // seats resolve one tick after that
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

        assertTrue(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        for (int i = 0; i < 4; i++) {
            assertTrue("still settling on tick " + i, ctl.isEntering(SHIP));
            ctl.tick();
        }
        ctl.tick(); // the successful re-seat at the pose
        ctl.tick(); // the unpark+settle tick

        assertFalse("settled despite the slow re-assembly", ctl.isEntering(SHIP));
        assertNotNull(ledger.get(SHIP));
    }

    /**
     * Every world-facing half of the settle must address the ship the crossing CREATED, by its own
     * identity — not "whatever ship is at the arrival point".
     *
     * <p>The distinction is invisible while a destination holds one ship and decides the outcome
     * when it holds two: a position lookup has no distance bound, so it answers for the nearest
     * craft. A player's entry has already ended with his ship parked in a cell while the arrival
     * re-seated against another craft's shipyard — no pilot seat in it, ever, and the pilot left
     * standing while his own seat sat 51,200 blocks away in the same world.</p>
     *
     * <p>This pins the WIRING: the state machine hands its crossing's identity to all three halves.
     * That the identity then resolves the right shipyard is the seam's own contract, exercised
     * where a real physics registry exists.</p>
     */
    @Test
    public void everySettleHalfAddressesTheShipTheCrossingCreated() {
        AtomicLong clock = new AtomicLong();
        SpaceManager space = new SpaceManager(new FakeBinder(10, 11), clock::get, never());
        ShipLedger ledger = new ShipLedger();
        FakeOps ops = new FakeOps();
        ShipEntryController ctl = new ShipEntryController(space, ledger, ops,
                dim -> body(5), clock::get);

        assertTrue(ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        ctl.tick(); // pose
        ctl.tick(); // re-seat
        ctl.tick(); // unpark + settle

        assertFalse("the crossing never finished, so no half was exercised", ctl.isEntering(SHIP));
        assertEquals("the pose teleport did not name the crossed ship",
                java.util.Collections.singletonList(FakeOps.CROSSED_SHIP), ops.teleportShipUuids);
        assertEquals("the re-seat did not name the crossed ship — it would scan whichever "
                        + "shipyard happens to be nearest the arrival point",
                java.util.Collections.singletonList(FakeOps.CROSSED_SHIP), ops.reseatShipUuids);
        assertEquals("the unpark did not name the crossed ship",
                java.util.Collections.singletonList(FakeOps.CROSSED_SHIP), ops.unparkShipUuids);
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
        ctl.tick();
        assertFalse("a ship already in space cannot enter again",
                ctl.requestEntry(LAUNCH_DIM, AFC, SHIP));
        assertEquals(1, ops.crossings);
    }

    /**
     * The trigger is WORLD and ALTITUDE, and nothing else — see the twin note on
     * {@code DescentControllerTest}. The "no pilot, no entry" leg went with the parameter it
     * exercised: production always passed it {@code true}, and an unmanned ship above the orbit
     * line is now meant to leave.
     */
    @Test
    public void triggerPredicateGatesOnWorldAndCeilingOnly() {
        assertTrue(ShipEntryController.shouldTriggerEntry(false, 1001.0, 1000));
        assertFalse("below the ceiling",
                ShipEntryController.shouldTriggerEntry(false, 999.0, 1000));
        assertFalse("never from a space-subsystem world",
                ShipEntryController.shouldTriggerEntry(true, 1001.0, 1000));
    }

    /**
     * The entry line must always be REACHABLE under the physics mod's hard altitude clamp. With
     * stock configs both the orbit height and the clamp sit at 1000, and a line at the clamp can
     * never be crossed - the ship stops dead at an invisible wall and entry never fires. The
     * effective line is therefore derived from the live clamp, not trusted to configs to agree.
     */
    @Test
    public void entryLineStaysReachableBelowThePhysicsClamp() {
        // The stock-config collision: orbit 1000, clamp 1000 -> the line moves below the clamp.
        int line = ShipEntryController.effectiveEntryCeiling(1000, 1000.0);
        assertTrue("with orbit == clamp the line must drop below the clamp (got " + line + ")",
                line < 1000);
        assertTrue("a ship must be able to EXCEED the line before the clamp stops it (line " + line
                        + ", clamp 1000)",
                ShipEntryController.shouldTriggerEntry(false, 1000.0, line));

        // A clamp raised well above the orbit line leaves the configured orbit height in charge.
        assertEquals(1000, ShipEntryController.effectiveEntryCeiling(1000, 2_000_000.0));

        // No physics mod -> no clamp -> the configured orbit height is untouched.
        assertEquals(1000,
                ShipEntryController.effectiveEntryCeiling(1000, Double.POSITIVE_INFINITY));
    }
}
