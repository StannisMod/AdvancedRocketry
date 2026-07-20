package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.space.CrewTransfer;
import zmaster587.advancedRocketry.space.DescentController;
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
 * Contract tests for the planet-descent state machine, driven against the real {@link SpaceManager}
 * with a recording fake {@link ShipCrossingService.Ops} + a fake {@link DescentController.PasteResolver}
 * (the entry-controller test discipline). Pins the decisions: only a ship genuinely in space (a SETTLED
 * ledger entry) may descend (the INVERSE of entry's guard); a successful cut releases the source cell
 * and drops the ledger entry; an unfittable landing REFUSES the descent (message + cooldown, ship stays
 * in space); a failed crossing leaves the ship in space.
 */
public class DescentControllerTest {

    private static final UUID SHIP = UUID.fromString("00000000-0000-0000-0000-0000000000BB");
    private static final BlockPos AFC = new BlockPos(2, 70, 2);
    private static final int SLOT_DIM = 10;
    private static final int PLANET_DIM = 3;

    private static GalacticCoord body(long sector) {
        return GalacticCoord.ofSectorLocal(sector, 0L, 0L, 0L, 0L, 0L);
    }

    /** Recording binder (mirrors the entry-controller test) so descent drives a real SpaceManager. */
    private static final class FakeBinder implements SlotBinder {
        final int[] dims;
        FakeBinder(int... dims) { this.dims = dims; }
        @Override public int[] slotDims() { return dims; }
        @Override public void load(int dimId, String cellKey) { }
        @Override public void unload(int dimId) { }
        @Override public void discard(int dimId) { }
        @Override public void deleteStore(String cellKey) { }
    }

    /** Recording ops: a ship exists in the slot; knobs force each failure mode. */
    private static final class FakeOps implements ShipCrossingService.Ops {
        boolean shipPresent = true;
        boolean failCross;
        final List<String> messages = new ArrayList<>();
        int crossings;

        @Override public double[] shipWorldPosition(int dimId, BlockPos afcPos) {
            return shipPresent ? new double[]{5.0, 80.0, 5.0} : null;
        }

        @Override public List<CrewTransfer.Crew> captureCrew(int dimId, BlockPos afcPos,
                                                             double[] shipWorldPos) {
            return new ArrayList<>(); // crew mechanics are integration-tier; none here
        }

        @Override public BlockPos cross(int srcDimId, double[] srcShipPos, int destDim,
                                        int pasteX, int pasteY, int pasteZ) {
            crossings++;
            return failCross ? null : new BlockPos(pasteX, pasteY, pasteZ);
        }

        @Override public void pinDim(int dimId) { }
        @Override public void loadShips(int destDim) { }

        @Override public boolean reseat(int destDim, BlockPos anchor, List<CrewTransfer.Crew> crew) {
            return true;
        }

        @Override public boolean teleportPoseWithRiders(int destDim, BlockPos anchor,
                                                        double px, double py, double pz) {
            return true;
        }

        @Override public void unparkAt(int destDim, double px, double py, double pz) { }

        @Override public void messageCrew(List<CrewTransfer.Crew> crew, String langKey, Object... args) {
            messages.add(langKey);
        }
    }

    /** Recording paste resolver: a fixed landing, or null when {@code fail} is set (unfittable). */
    private static final class FakeResolver implements DescentController.PasteResolver {
        boolean fail;
        int calls;
        @Override public DescentController.Landing resolve(int slotDim, double[] shipWorldPos,
                                                           int destPlanetDim, int laneIndex) {
            calls++;
            return fail ? null : new DescentController.Landing(0, 100, 0, new double[]{0.5, 101.0, 0.5});
        }
    }

    private static SpaceManager.Config never() {
        return new SpaceManager.Config(SpaceManager.GcPolicy.NEVER, 0, 0);
    }

    /** Settle a ship in space at {@code cell} so it becomes descend-eligible; returns the manager. */
    private static SpaceManager spaceWithSettledShip(ShipLedger ledger, AtomicLong clock,
                                                     GalacticCoord cell, int... dims) {
        SpaceManager space = new SpaceManager(new FakeBinder(dims), clock::get, never());
        int slot = space.materialize(cell); // the ship holds one occupant on its cell
        ledger.settle(SHIP, cell, slot);
        return space;
    }

    @Test
    public void settledShipDescendsReleasingTheCellAndDroppingTheLedgerEntry() {
        AtomicLong clock = new AtomicLong();
        ShipLedger ledger = new ShipLedger();
        SpaceManager space = spaceWithSettledShip(ledger, clock, body(5), SLOT_DIM);
        FakeOps ops = new FakeOps();
        FakeResolver resolver = new FakeResolver();
        DescentController ctl = new DescentController(space, ledger, ops, resolver, clock::get);

        assertTrue(ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));
        assertEquals("a landing was resolved", 1, resolver.calls);
        assertEquals("one crossing was run", 1, ops.crossings);
        // The ship is physically cut from its cell at once: the ledger entry is gone and the cell is
        // released, so the single slot can be reused immediately (the occupant was released).
        assertNull("the descending ship leaves the ledger on the cut", ledger.get(SHIP));
        assertEquals("the vacated cell's slot is free", SLOT_DIM, space.materialize(body(77)));
        assertTrue("the settle is still in flight", ctl.isDescending(SHIP));

        ctl.tick(); // pose teleport (runs FIRST: the split-pair invariant)
        ctl.tick(); // re-seat at the pose
        ctl.tick(); // unpark + settle

        assertFalse(ctl.isDescending(SHIP));
        assertEquals("crew told they arrived", "msg.shipdescent.arrived",
                ops.messages.get(ops.messages.size() - 1));
    }

    @Test
    public void aShipNotInSpaceCannotDescend() {
        AtomicLong clock = new AtomicLong();
        ShipLedger ledger = new ShipLedger(); // SHIP was never settled
        SpaceManager space = new SpaceManager(new FakeBinder(SLOT_DIM), clock::get, never());
        FakeOps ops = new FakeOps();
        FakeResolver resolver = new FakeResolver();
        DescentController ctl = new DescentController(space, ledger, ops, resolver, clock::get);

        assertFalse("no ledger entry -> not in space -> cannot descend",
                ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));
        assertFalse(ctl.isDescending(SHIP));
        assertEquals("no crossing attempted", 0, ops.crossings);
        assertEquals("no landing even resolved", 0, resolver.calls);
    }

    @Test
    public void anUnfittableLandingRefusesTheDescentAndKeepsTheShipInSpace() {
        AtomicLong clock = new AtomicLong();
        ShipLedger ledger = new ShipLedger();
        SpaceManager space = spaceWithSettledShip(ledger, clock, body(5), SLOT_DIM);
        FakeOps ops = new FakeOps();
        FakeResolver resolver = new FakeResolver();
        resolver.fail = true; // no clear landing above the terrain
        DescentController ctl = new DescentController(space, ledger, ops, resolver, clock::get);

        assertFalse(ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));
        assertFalse(ctl.isDescending(SHIP));
        assertEquals("no crossing was attempted", 0, ops.crossings);
        assertEquals("the pilot is told", "msg.shipdescent.refused", ops.messages.get(0));
        assertNotNull("the ship is still in space after a refusal", ledger.get(SHIP));

        // The refusal armed a cooldown: an immediate retry is silently ignored (no message spam).
        assertFalse(ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));
        assertEquals("no second message inside the cooldown", 1, ops.messages.size());

        clock.addAndGet(1000L);
        resolver.fail = false;
        assertTrue("descent retries after the cooldown", ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));
    }

    @Test
    public void aFailedCrossingLeavesTheShipInSpace() {
        AtomicLong clock = new AtomicLong();
        ShipLedger ledger = new ShipLedger();
        SpaceManager space = spaceWithSettledShip(ledger, clock, body(5), SLOT_DIM);
        FakeOps ops = new FakeOps();
        ops.failCross = true;
        FakeResolver resolver = new FakeResolver();
        DescentController ctl = new DescentController(space, ledger, ops, resolver, clock::get);

        assertFalse(ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));
        assertFalse(ctl.isDescending(SHIP));
        assertEquals("msg.shipdescent.failed", ops.messages.get(0));
        // The crossing never removed the ship, so it is still settled in space and holds its cell.
        ShipLedger.Entry entry = ledger.get(SHIP);
        assertNotNull("a failed descent leaves the ship in space", entry);
        assertEquals(ShipLedger.State.SETTLED, entry.state);
    }

    @Test
    public void duplicateDescentIsNotRestarted() {
        AtomicLong clock = new AtomicLong();
        ShipLedger ledger = new ShipLedger();
        SpaceManager space = spaceWithSettledShip(ledger, clock, body(5), SLOT_DIM, 11);
        FakeOps ops = new FakeOps();
        FakeResolver resolver = new FakeResolver();
        DescentController ctl = new DescentController(space, ledger, ops, resolver, clock::get);

        assertTrue(ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));
        assertFalse("an in-flight descent is not restarted",
                ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));
        assertEquals(1, ops.crossings);
    }

    @Test
    public void triggerPredicateGatesOnWorldPilotAndRadius() {
        long r = ShipEntryController.DESCENT_RADIUS_BLOCKS;
        assertTrue("in space, pilot flying, inside the radius",
                DescentController.shouldTriggerDescent(true, true, r - 1.0, r));
        assertTrue("exactly on the radius still triggers",
                DescentController.shouldTriggerDescent(true, true, r, r));
        assertFalse("no pilot, no descent",
                DescentController.shouldTriggerDescent(true, false, 0.0, r));
        assertFalse("outside the radius",
                DescentController.shouldTriggerDescent(true, true, r + 1.0, r));
        assertFalse("never from a planet-side world",
                DescentController.shouldTriggerDescent(false, true, 0.0, r));
    }
}
