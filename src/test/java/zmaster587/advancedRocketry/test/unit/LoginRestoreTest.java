package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.LoginRestore;
import zmaster587.advancedRocketry.space.ShipAboardTag;
import zmaster587.advancedRocketry.space.ShipLedger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the login-restore decision, driven against a real {@link ShipLedger} behind a
 * recording fake {@link LoginRestore.Ops} (the entry-controller test discipline).
 *
 * <p>Pins the player-visible outcomes: a player who logged out aboard a settled ship wakes up in
 * that ship's slot world; one whose ship is mid-jump wakes up in hyperspace and his target cell is
 * NOT claimed on his behalf; the ledger's coordinate — not the possibly stale coordinate baked into
 * his player file — decides which cell is made live; and every failure (no tag, ship gone,
 * exhausted slot pool) degrades to a normal spawn instead of breaking the login.</p>
 */
public class LoginRestoreTest {

    private static final UUID SHIP = UUID.fromString("00000000-0000-0000-0000-0000000000AA");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000BB");

    /** Where the ledger says the ship is now. */
    private static final GalacticCoord LEDGER_COORD =
            GalacticCoord.ofSectorLocal(9L, 0L, 3L, 0L, 0L, 0L);
    /** Where the ship was when the player logged out — a different cell entirely. */
    private static final GalacticCoord STALE_COORD =
            GalacticCoord.ofSectorLocal(1L, 0L, 1L, 0L, 0L, 0L);

    private static final int SLOT_DIM = 42;
    private static final int HYPERSPACE_DIM = 7;

    private static ShipAboardTag.Aboard tag(GalacticCoord coord) {
        return new ShipAboardTag.Aboard(SHIP, coord, 0, 1, 2);
    }

    /** Recording ops over a real ledger; each knob forces one failure mode. */
    private static final class FakeOps implements LoginRestore.Ops {
        final ShipLedger ledger = new ShipLedger();
        final List<GalacticCoord> materialized = new ArrayList<>();
        int materializeDim = SLOT_DIM;
        int transitDim = HYPERSPACE_DIM;
        boolean shipPoseReadable = true;
        double[] personalSpawn;
        double[] overworldSpawn = {8.0, 64.0, -8.0};

        @Override public ShipLedger.Entry ledgerEntry(UUID shipId) {
            return ledger.get(shipId);
        }

        @Override public int materialize(GalacticCoord coord) {
            materialized.add(coord);
            return materializeDim;
        }

        @Override public int unpackTransit(UUID shipId) {
            return transitDim;
        }

        @Override public double[] shipWorldPos(int slotDim, UUID shipId) {
            return shipPoseReadable ? new double[]{12.5, 2000.25, -34.5} : null;
        }

        @Override public double[] personalSpawn(UUID playerId) {
            return personalSpawn;
        }

        @Override public double[] overworldSpawn() {
            return overworldSpawn;
        }
    }

    /** Everything unavailable: the shape a broken/absent space subsystem presents. */
    private static final class NullishOps implements LoginRestore.Ops {
        @Override public ShipLedger.Entry ledgerEntry(UUID shipId) { return null; }
        @Override public int materialize(GalacticCoord coord) { return -1; }
        @Override public int unpackTransit(UUID shipId) { return -1; }
        @Override public double[] shipWorldPos(int slotDim, UUID shipId) { return null; }
        @Override public double[] personalSpawn(UUID playerId) { return null; }
        @Override public double[] overworldSpawn() { return null; }
    }

    // --- aboard branches ------------------------------------------------------------------------

    @Test
    public void settledShipRestoresThePlayerAboardInItsSlotWorld() {
        FakeOps ops = new FakeOps();
        ops.ledger.settle(SHIP, LEDGER_COORD, SLOT_DIM);

        LoginRestore.Placement placed = LoginRestore.resolve(tag(LEDGER_COORD), ops, PLAYER);

        assertEquals(LoginRestore.Reason.ABOARD_SETTLED, placed.reason);
        assertTrue("he still belongs on the ship, so phase 2 must seat him", placed.aboard);
        assertEquals("restored into the cell's bound slot world", SLOT_DIM, placed.dimension);
        assertEquals(SHIP, placed.shipId);
        // Placed at the ship's live pose, not at some fixed world spawn.
        assertEquals(12.5, placed.x, 0.0);
        assertEquals(2000.25, placed.y, 0.0);
        assertEquals(-34.5, placed.z, 0.0);
    }

    @Test
    public void theLedgerCoordinateWinsOverAStaleTagCoordinate() {
        // The ship kept flying under another crew member while this player was offline, so the
        // coordinate baked into his player file points at a cell the ship left long ago.
        FakeOps ops = new FakeOps();
        ops.ledger.settle(SHIP, LEDGER_COORD, SLOT_DIM);

        LoginRestore.Placement placed = LoginRestore.resolve(tag(STALE_COORD), ops, PLAYER);

        assertTrue(placed.aboard);
        assertTrue("the ledger's cell is the one made live",
                ops.materialized.contains(LEDGER_COORD));
        assertFalse("the stale cell from the player file must never be materialized",
                ops.materialized.contains(STALE_COORD));
    }

    @Test
    public void midJumpShipRestoresIntoHyperspaceWithoutClaimingItsTargetCell() {
        FakeOps ops = new FakeOps();
        ops.ledger.beginTransit(SHIP, LEDGER_COORD);

        LoginRestore.Placement placed = LoginRestore.resolve(tag(STALE_COORD), ops, PLAYER);

        assertEquals(LoginRestore.Reason.ABOARD_IN_TRANSIT, placed.reason);
        assertTrue(placed.aboard);
        assertEquals("restored into the shared hyperspace world", HYPERSPACE_DIM, placed.dimension);
        assertEquals(SHIP, placed.shipId);
        // A ship in flight does not occupy its destination: claiming that cell would burn a pool
        // slot for a ship that is not there.
        assertTrue("a mid-jump restore materializes no cell", ops.materialized.isEmpty());
    }

    @Test
    public void aSilentShipStillRestoresAboardAtAProvisionalPosition() {
        // Re-assembly is asynchronous: the ship can still be building on the tick he logs in.
        FakeOps ops = new FakeOps();
        ops.ledger.settle(SHIP, LEDGER_COORD, SLOT_DIM);
        ops.shipPoseReadable = false;

        LoginRestore.Placement placed = LoginRestore.resolve(tag(LEDGER_COORD), ops, PLAYER);

        assertEquals(LoginRestore.Reason.ABOARD_SETTLED, placed.reason);
        assertTrue("he is still aboard, so the seating is retried later", placed.aboard);
        assertEquals(SLOT_DIM, placed.dimension);
        assertTrue("a usable position is still produced",
                isFinite(placed.x) && isFinite(placed.y) && isFinite(placed.z));
    }

    // --- orphan branches ------------------------------------------------------------------------

    @Test
    public void aPlayerWithNoTagSpawnsAtHisBedWhenHeHasOne() {
        FakeOps ops = new FakeOps();
        ops.personalSpawn = new double[]{3.0, 100.5, 70.0, -20.5};

        LoginRestore.Placement placed = LoginRestore.resolve(null, ops, PLAYER);

        assertEquals(LoginRestore.Reason.NO_TAG, placed.reason);
        assertFalse("he was never aboard", placed.aboard);
        assertNull("nothing to seat him onto", placed.shipId);
        assertEquals(3, placed.dimension);
        assertEquals(100.5, placed.x, 0.0);
        assertEquals(70.0, placed.y, 0.0);
        assertEquals(-20.5, placed.z, 0.0);
    }

    @Test
    public void aPlayerWithNoTagAndNoBedSpawnsInTheOverworld() {
        FakeOps ops = new FakeOps();
        ops.personalSpawn = null;

        LoginRestore.Placement placed = LoginRestore.resolve(null, ops, PLAYER);

        assertEquals(LoginRestore.Reason.NO_TAG, placed.reason);
        assertFalse(placed.aboard);
        assertEquals(LoginRestore.OVERWORLD_DIM, placed.dimension);
        assertEquals(8.0, placed.x, 0.0);
        assertEquals(64.0, placed.y, 0.0);
        assertEquals(-8.0, placed.z, 0.0);
    }

    @Test
    public void aTagWhoseShipTheLedgerNeverHeardOfFallsBackToSpawn() {
        // The ship descended onto a planet (or was dismantled) while he was offline; his tag
        // outlived its subject.
        FakeOps ops = new FakeOps();

        LoginRestore.Placement placed = LoginRestore.resolve(tag(STALE_COORD), ops, PLAYER);

        assertEquals(LoginRestore.Reason.SHIP_UNKNOWN, placed.reason);
        assertFalse(placed.aboard);
        assertNull(placed.shipId);
        assertEquals(LoginRestore.OVERWORLD_DIM, placed.dimension);
    }

    @Test
    public void anExhaustedSlotPoolDegradesToSpawnInsteadOfBreakingTheLogin() {
        FakeOps ops = new FakeOps();
        ops.ledger.settle(SHIP, LEDGER_COORD, SLOT_DIM);
        ops.materializeDim = -1; // the pool had no slot for his cell

        LoginRestore.Placement placed = LoginRestore.resolve(tag(LEDGER_COORD), ops, PLAYER);

        assertEquals(LoginRestore.Reason.CELL_UNAVAILABLE, placed.reason);
        assertFalse("he cannot be aboard a ship whose cell is not live", placed.aboard);
        assertNull(placed.shipId);
        assertEquals(LoginRestore.OVERWORLD_DIM, placed.dimension);
    }

    @Test
    public void aTransitThatCannotBeUnpackedDegradesToSpawn() {
        FakeOps ops = new FakeOps();
        ops.ledger.beginTransit(SHIP, LEDGER_COORD);
        ops.transitDim = -1;
        ops.personalSpawn = new double[]{0.0, 1.5, 65.0, 2.5};

        LoginRestore.Placement placed = LoginRestore.resolve(tag(STALE_COORD), ops, PLAYER);

        assertEquals(LoginRestore.Reason.CELL_UNAVAILABLE, placed.reason);
        assertFalse(placed.aboard);
        assertEquals("his bed is preferred over the world spawn", 1.5, placed.x, 0.0);
    }

    // --- totality -------------------------------------------------------------------------------

    @Test
    public void resolveIsTotalForEveryNullishCombination() {
        LoginRestore.Ops nothing = new NullishOps();

        assertNotNull("no tag, nothing available",
                LoginRestore.resolve(null, nothing, PLAYER));
        assertNotNull("no tag, no player id",
                LoginRestore.resolve(null, nothing, null));
        assertNotNull("tag present, nothing available",
                LoginRestore.resolve(tag(STALE_COORD), nothing, PLAYER));
        assertNotNull("no ops at all", LoginRestore.resolve(tag(STALE_COORD), null, PLAYER));
        assertNotNull("no inputs whatsoever", LoginRestore.resolve(null, null, null));

        // Even with no spawn to fall back on, the login gets a usable placement.
        LoginRestore.Placement placed = LoginRestore.resolve(null, nothing, PLAYER);
        assertFalse(placed.aboard);
        assertNotNull(placed.reason);
        assertTrue(isFinite(placed.x) && isFinite(placed.y) && isFinite(placed.z));
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
