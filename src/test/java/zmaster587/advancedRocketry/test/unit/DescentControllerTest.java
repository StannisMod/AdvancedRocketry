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
import zmaster587.advancedRocketry.space.SpaceSubsystem;
import zmaster587.advancedRocketry.space.TerrainHeightFinder;
import zmaster587.advancedRocketry.space.VSDescentPasteResolver;

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
 * and drops the ledger entry; an arrival that cannot be resolved at all REFUSES the descent (message +
 * cooldown, ship stays in space); a failed crossing leaves the ship in space.
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
        final List<Integer> reseatDims = new ArrayList<>();
        /** The identity the cross hands back — every settle half must address THIS ship. */
        static final UUID CROSSED_SHIP = UUID.fromString("11111111-2222-3333-4444-555555555555");
        /** What the settle actually named when it re-seated; a null here is a position lookup. */
        final List<UUID> reseatShipUuids = new ArrayList<>();
        int crossings;
        int captures;
        int peeks;
        final List<Integer> latchedDims = new ArrayList<>();
        /** The crossing count at each latch call — so a test can say the latch preceded the cut. */
        final List<Integer> latchedBeforeCross = new ArrayList<>();
        List<CrewTransfer.Crew> lastCaptured;
        List<CrewTransfer.Crew> lastReseated;

        @Override public double[] shipWorldPosition(int dimId, BlockPos afcPos) {
            return shipPresent ? new double[]{5.0, 80.0, 5.0} : null;
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

        @Override public ShipCrossingService.Crossed cross(int srcDimId, double[] srcShipPos, int destDim,
                                        int pasteX, int pasteY, int pasteZ) {
            crossings++;
            return failCross ? null : new ShipCrossingService.Crossed(
                    new BlockPos(pasteX, pasteY, pasteZ), CROSSED_SHIP);
        }

        /** Dims the controller asked to be present, IN ORDER — the pin must precede the resolve. */
        final List<Integer> pinned = new ArrayList<>();
        @Override public void pinDim(int dimId) { pinned.add(dimId); }


        @Override public boolean reseat(int destDim, BlockPos anchor, List<CrewTransfer.Crew> crew,
                java.util.UUID shipId, java.util.UUID vsShipUuid) {
            reseatDims.add(destDim);
            reseatShipUuids.add(vsShipUuid);
            lastReseated = crew;
            return true;
        }

        @Override public boolean teleportPoseWithRiders(int destDim, BlockPos anchor, java.util.UUID vsShipUuid,
                                                        double px, double py, double pz) {
            return true;
        }

        @Override public void unpark(int destDim, java.util.UUID vsShipUuid, double px, double py, double pz) { }

        @Override public void messageCrew(List<CrewTransfer.Crew> crew, String langKey, Object... args) {
            messages.add(langKey);
        }

        @Override public void latchEntryUntilBelowTheLine(int dimId, BlockPos afcPos) {
            latchedDims.add(dimId);
            latchedBeforeCross.add(crossings);
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
        ledger.settle(SHIP, cell);
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

    /**
     * A descent brings its destination up BEFORE it asks whether it can arrive there.
     *
     * <p>A planet with nobody standing on it is unloaded within seconds of the last player leaving,
     * and the arrival resolve needs that world to compute anything at all. So a pilot who flew to
     * orbit and came back later was refused on every attempt, forever, and told only to wait — the
     * one piece of advice that could never come true. Measured in a live session: the destination
     * was unloaded twelve minutes before the first attempt, and thirteen consecutive refusals all
     * carried the same discriminator, the destination world absent while everything else was
     * present.</p>
     *
     * <p>The ORDER is the whole assertion, which is why the fake records it: the crossing pins the
     * destination as well, but that happens after the resolve it would have had to enable.</p>
     */
    @Test
    public void aDescentBringsItsDestinationUpBeforeAskingWhereToLand() {
        AtomicLong clock = new AtomicLong();
        ShipLedger ledger = new ShipLedger();
        SpaceManager space = spaceWithSettledShip(ledger, clock, body(5), SLOT_DIM);
        FakeOps ops = new FakeOps();
        FakeResolver resolver = new FakeResolver();
        // The destination cannot be resolved — the case where the pin has to have happened ANYWAY,
        // because a refusal that never asked for the world is a refusal that can never stop.
        resolver.fail = true;
        DescentController ctl = new DescentController(space, ledger, ops, resolver, clock::get);

        assertFalse(ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));

        assertTrue("the destination must have been asked for, even on the path that refuses: "
                        + ops.pinned, ops.pinned.contains(PLANET_DIM));
        assertEquals("...and asked for BEFORE the arrival was resolved, or the resolve is answering "
                        + "about a world nobody has brought up yet", 1, resolver.calls);
    }

    @Test
    public void anUnresolvableArrivalRefusesTheDescentAndKeepsTheShipInSpace() {
        AtomicLong clock = new AtomicLong();
        ShipLedger ledger = new ShipLedger();
        SpaceManager space = spaceWithSettledShip(ledger, clock, body(5), SLOT_DIM);
        FakeOps ops = new FakeOps();
        FakeResolver resolver = new FakeResolver();
        resolver.fail = true; // the destination's arrival could not be resolved at all
        DescentController ctl = new DescentController(space, ledger, ops, resolver, clock::get);

        assertFalse(ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));
        assertFalse(ctl.isDescending(SHIP));
        assertEquals("no crossing was attempted", 0, ops.crossings);
        // A refusal must leave the pilot in his seat: the crew is READ for the message, never
        // captured (a capture dismounts every rider and retires the mounts).
        assertEquals("a refused descent never captures (= unseats) the crew", 0, ops.captures);
        assertTrue("the refusal message still reaches the crew via the read-only peek",
                ops.peeks >= 1);
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
        // The cut never happened, so the crew captured just before it must be put BACK on their
        // seats — in the slot world the intact ship still sits in.
        assertEquals("the captured crew is re-seated after the failed cut", 1, ops.reseatDims.size());
        assertEquals("the re-seat targets the ship's slot world", SLOT_DIM,
                (int) ops.reseatDims.get(0));
        assertTrue("the re-seat restores exactly the crew the capture took",
                ops.lastReseated == ops.lastCaptured);
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

    /**
     * A committed descent holds the entry on-ramp off the ship, and does it on the SOURCE computer
     * BEFORE the cut — the crossing carries tile NBT verbatim, so that is what puts the latch on the
     * ship that arrives. Latching after the cut would target a computer that no longer exists.
     */
    @Test
    public void aCommittedDescentLatchesEntryOnTheSourceShipBeforeTheCut() {
        AtomicLong clock = new AtomicLong();
        ShipLedger ledger = new ShipLedger();
        SpaceManager space = spaceWithSettledShip(ledger, clock, body(5), SLOT_DIM);
        FakeOps ops = new FakeOps();
        DescentController ctl = new DescentController(space, ledger, ops, new FakeResolver(), clock::get);

        assertTrue(ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));

        assertEquals("a descent must latch entry exactly once", 1, ops.latchedDims.size());
        assertEquals("the latch is set on the ship in its SLOT world — the source computer, whose "
                        + "NBT the crossing carries to the destination",
                SLOT_DIM, (int) ops.latchedDims.get(0));
        assertEquals("the latch must be set BEFORE the cut: after it, the source computer is gone "
                        + "and the write lands on nothing",
                0, (int) ops.latchedBeforeCross.get(0));
    }

    /** A refused descent never latches: the ship did not go anywhere, so nothing may hold its entry
     *  on-ramp off — that would strand it in space with no way to re-arm. */
    @Test
    public void aRefusedDescentDoesNotLatchEntry() {
        AtomicLong clock = new AtomicLong();
        ShipLedger ledger = new ShipLedger();
        SpaceManager space = spaceWithSettledShip(ledger, clock, body(5), SLOT_DIM);
        FakeOps ops = new FakeOps();
        FakeResolver resolver = new FakeResolver();
        resolver.fail = true;
        DescentController ctl = new DescentController(space, ledger, ops, resolver, clock::get);

        assertFalse(ctl.requestDescent(SLOT_DIM, AFC, SHIP, PLANET_DIM));
        assertTrue("a descent that never happened must leave entry armed", ops.latchedDims.isEmpty());
    }

    /**
     * The arrival altitude contract. A descent brings the ship out in the AIR over the destination,
     * so the only thing that has to be true of the altitude is a set of RELATIONS — never a
     * particular number, which is a balance knob: it must clear everything the world can build, and
     * it must stay clear, by the declared hysteresis, of both ceilings a planet-side ship is subject
     * to (the physics pose clamp, and the entry line that would take the ship straight back off the
     * planet).
     */
    @Test
    public void theArrivalComesOutAboveTheGroundAndClearOfBothCeilings() {
        // A representative orbit line for a planet (the shipped default). Not a pin — the relations
        // below are what the test is about; any orbit line well above the block band behaves the same.
        int orbit = 1000;
        // The clamp as PRODUCTION leaves it: the space subsystem raises it at server start to cover
        // the realized cell pose band, so this — not the physics mod's stock value — is what the
        // resolver actually reads on a live server.
        double clamp = SpaceSubsystem.requiredShipCeiling();
        int hysteresis = VSDescentPasteResolver.ARRIVAL_HYSTERESIS_BLOCKS;

        double arrival = VSDescentPasteResolver.arrivalAltitude(orbit, clamp);

        assertTrue("a ship arriving at or below the build height can materialize inside terrain — the "
                        + "whole point of arriving in the air is that no ground fit is needed; "
                        + "arrival=" + arrival + " buildHeight=" + TerrainHeightFinder.MAX_BUILD_Y,
                arrival > TerrainHeightFinder.MAX_BUILD_Y);
        assertTrue("the physics clamp pins a ship's pose every step, so the arrival must sit at least "
                        + "the hysteresis below it; arrival=" + arrival + " clamp=" + clamp,
                arrival <= clamp - hysteresis);
        int entryLine = ShipEntryController.effectiveEntryCeiling(orbit, clamp);
        assertTrue("arriving at or above the entry line would bounce the ship back into space on the "
                        + "tick it arrived — the descent must land INSIDE the on-ramp's hysteresis, "
                        + "not on top of it; arrival=" + arrival + " entryLine=" + entryLine
                        + " hysteresis=" + hysteresis,
                arrival <= entryLine - hysteresis);
    }

    /** The altitude floor holds even for a destination whose orbit line is pushed to its minimum:
     *  an arrival never sinks into the block band, where the ship could come out inside a mountain. */
    @Test
    public void theArrivalNeverSinksIntoTheBlockBand() {
        double arrival = VSDescentPasteResolver.arrivalAltitude(
                TerrainHeightFinder.MAX_BUILD_Y, SpaceSubsystem.requiredShipCeiling());
        assertTrue("the lowest orbit line a dimension may declare still must not put the arrival "
                        + "inside the world's blocks; arrival=" + arrival,
                arrival > TerrainHeightFinder.MAX_BUILD_Y);
    }

    /**
     * The trigger is WORLD and GEOMETRY, and nothing else.
     *
     * <p>The "no pilot, no descent" leg this used to carry is gone with the parameter it exercised:
     * every production call site passed that conjunct as a literal {@code true}, so the leg pinned a
     * path production could not reach — and the behaviour it appeared to protect (a ship crossing an
     * atmosphere with nobody at the controls) is now deliberately ALLOWED, because crossing is a
     * physical event and the one ship it excluded was the autopilot.</p>
     */
    @Test
    public void triggerPredicateGatesOnWorldAndRadiusOnly() {
        long r = ShipEntryController.DESCENT_RADIUS_BLOCKS;
        assertTrue("in space, inside the radius",
                DescentController.shouldTriggerDescent(true, r - 1.0, r));
        assertTrue("exactly on the radius still triggers",
                DescentController.shouldTriggerDescent(true, r, r));
        assertFalse("outside the radius",
                DescentController.shouldTriggerDescent(true, r + 1.0, r));
        assertFalse("never from a planet-side world",
                DescentController.shouldTriggerDescent(false, 0.0, r));
    }
}
