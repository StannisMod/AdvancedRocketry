package zmaster587.advancedRocketry.test.unit;

import java.util.UUID;

import org.junit.Test;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipLedger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

/**
 * Contract tests for the ship ledger — the authoritative shipId&rarr;(coordinate, state) record.
 * Pins the lifecycle semantics (settle / transit / position self-report / removal), in particular
 * that a live pose report can never overwrite a parked ship's logically-integrated coordinate.
 */
public class ShipLedgerTest {

    private static final UUID SHIP = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static GalacticCoord coord(long sector, long local) {
        return GalacticCoord.ofSectorLocal(sector, 0L, 0L, local, 0L, 0L);
    }

    @Test
    public void settleRecordsCoordinateAndState() {
        ShipLedger ledger = new ShipLedger();
        ledger.settle(SHIP, coord(3, 100));

        ShipLedger.Entry e = ledger.get(SHIP);
        assertNotNull(e);
        assertEquals(coord(3, 100), e.coord);
        assertEquals(ShipLedger.State.SETTLED, e.state);
        assertEquals(coord(3, 0).cellKey(), e.cellKey());
    }

    @Test
    public void positionReportRefreshesASettledShip() {
        ShipLedger ledger = new ShipLedger();
        ledger.settle(SHIP, coord(3, 100));

        ledger.updatePosition(SHIP, coord(3, 2500));

        assertEquals("the self-reported pose is the ship's coordinate now",
                coord(3, 2500), ledger.get(SHIP).coord);
        assertEquals("a position report does not change the lifecycle state",
                ShipLedger.State.SETTLED, ledger.get(SHIP).state);
    }

    @Test
    public void positionReportNeverTouchesAShipInTransit() {
        ShipLedger ledger = new ShipLedger();
        ledger.beginTransit(SHIP, coord(9, 0));

        ledger.updatePosition(SHIP, coord(1, 1)); // a stale pose from a parked hull

        assertEquals("a parked ship's coordinate is owned by the transit integrator",
                coord(9, 0), ledger.get(SHIP).coord);
        assertEquals(ShipLedger.State.IN_TRANSIT, ledger.get(SHIP).state);
    }

    @Test
    public void arrivalAfterTransitSettlesAtTheTarget() {
        // The transit-arrival amnesia fix: after a jump the ship's coordinate must survive in the
        // ledger instead of vanishing with the finished transit record.
        ShipLedger ledger = new ShipLedger();
        ledger.settle(SHIP, coord(1, 0));
        ledger.beginTransit(SHIP, coord(2, 0));
        ledger.settle(SHIP, coord(2, 0));

        ShipLedger.Entry e = ledger.get(SHIP);
        assertEquals(coord(2, 0), e.coord);
        assertEquals(ShipLedger.State.SETTLED, e.state);
    }

    @Test
    public void removalAndUnknownShipsAnswerNull() {
        ShipLedger ledger = new ShipLedger();
        assertNull(ledger.get(SHIP));
        ledger.updatePosition(SHIP, coord(1, 1)); // reporting an unknown ship is a safe no-op
        assertNull(ledger.get(SHIP));

        ledger.settle(SHIP, coord(1, 0));
        ledger.remove(SHIP);
        assertNull(ledger.get(SHIP));
        assertEquals(0, ledger.size());
    }
}
