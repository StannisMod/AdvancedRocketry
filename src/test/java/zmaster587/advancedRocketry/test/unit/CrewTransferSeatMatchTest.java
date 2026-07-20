package zmaster587.advancedRocketry.test.unit;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.util.math.BlockPos;
import org.junit.Test;

import zmaster587.advancedRocketry.space.CrewTransfer;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * A crossing / login-restore re-seat must put the rider back on HIS ship, never on a
 * neighbouring craft that merely shares the seat layout. The seat is re-identified by its
 * seat→flight-computer offset (the one binding that survives re-assembly), but that offset is a
 * DESIGN property — any two ships built from the same blueprint share it — while the candidate
 * seats are gathered by spatial proximity. So when the caller knows the ship's durable id, a seat
 * whose linked computer carries a different id (or none resolvable yet) must never match; two
 * ships parked side by side must each keep their own crew.
 *
 * <p>Pure unit: seats are real {@link TilePilotSeat} tiles (position + link offset, no world), the
 * ship-id resolver is injected — exactly the seam {@code CrewTransfer.matchSeat} exposes.</p>
 */
public class CrewTransferSeatMatchTest {

    private static final UUID MY_SHIP = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_SHIP = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final Map<TilePilotSeat, UUID> shipOf = new HashMap<>();
    private final Function<TilePilotSeat, UUID> resolver = shipOf::get;

    /** A seat at {@code seatPos} linked to the computer at {@code seatPos + (dx,dy,dz)}. */
    private TilePilotSeat seat(BlockPos seatPos, int dx, int dy, int dz, UUID shipId) {
        TilePilotSeat seat = new TilePilotSeat();
        seat.setPos(seatPos);
        seat.linkToFlightComputer(seatPos.add(dx, dy, dz));
        shipOf.put(seat, shipId);
        return seat;
    }

    private static CrewTransfer.Crew rider(int dx, int dy, int dz) {
        return new CrewTransfer.Crew(null, dx, dy, dz);
    }

    @Test
    public void aMatchingOffsetOnTheWrongShipNeverMatches() {
        TilePilotSeat wrongShipSeat = seat(new BlockPos(100, 64, 100), 0, 2, 3, OTHER_SHIP);
        assertNull("a seat with the rider's exact offset but another ship's id must not claim him",
                CrewTransfer.matchSeat(Arrays.asList(wrongShipSeat), rider(0, 2, 3),
                        MY_SHIP, resolver));
    }

    @Test
    public void theRidersOwnShipWinsAmongEqualOffsetNeighbours() {
        // Two identical builds parked near each other — the cross-seat hazard this filter closes.
        TilePilotSeat neighbour = seat(new BlockPos(100, 64, 100), 0, 2, 3, OTHER_SHIP);
        TilePilotSeat own = seat(new BlockPos(106, 64, 100), 0, 2, 3, MY_SHIP);
        assertSame("among equal-offset candidates, only the rider's own ship's seat matches",
                own, CrewTransfer.matchSeat(Arrays.asList(neighbour, own), rider(0, 2, 3),
                        MY_SHIP, resolver));
    }

    @Test
    public void anUnresolvableShipIdIsNotYetAMatch() {
        // The destination's computer tile is not up yet (async re-assembly): the seat must NOT be
        // grabbed on offset alone — the caller retries until the id resolves.
        TilePilotSeat unresolved = seat(new BlockPos(100, 64, 100), 0, 2, 3, null);
        assertNull("a seat whose ship id cannot be resolved yet must not match when an id is "
                        + "expected (retry, don't guess)",
                CrewTransfer.matchSeat(Arrays.asList(unresolved), rider(0, 2, 3),
                        MY_SHIP, resolver));
    }

    @Test
    public void withoutAnExpectedIdTheOffsetAloneStillMatches() {
        // A caller with no durable id (a ship whose computer never minted one) keeps the
        // offset-only behaviour — the filter must not break the id-less legacy path.
        TilePilotSeat seat = seat(new BlockPos(100, 64, 100), 0, 2, 3, null);
        assertSame(seat, CrewTransfer.matchSeat(Arrays.asList(seat), rider(0, 2, 3),
                null, resolver));
    }

    @Test
    public void aDifferentOffsetNeverMatchesRegardlessOfId() {
        TilePilotSeat seat = seat(new BlockPos(100, 64, 100), 0, 2, 3, MY_SHIP);
        assertNull(CrewTransfer.matchSeat(Arrays.asList(seat), rider(1, 2, 3), MY_SHIP, resolver));
    }
}
