package zmaster587.advancedRocketry.test.unit;

import net.minecraft.util.math.BlockPos;
import org.junit.Test;
import zmaster587.advancedRocketry.space.ShipRelativePoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Contract tests for the ship-relative point: the deck position that a durable aboard record stores
 * and a login restore reads back.
 *
 * <p>Two things are being pinned, and both are about the pair of directions AGREEING, never about
 * how either one is spelled. First, the round trip: a point stored relative to a ship's flight
 * computer comes back as the same point. A hidden half-block on one side only (the seat helper
 * carries exactly such a fudge) is a drift that repeats on every save/load cycle and would be
 * invisible to a one-direction test. Second, invariance under re-assembly: a ship is rebuilt into a
 * FRESH subspace on every jump, entry and descent, so a stored position is only worth storing if it
 * still denotes the same spot on the deck once the whole ship has moved.</p>
 */
public class ShipRelativePointTest {

    private static final BlockPos AFC = new BlockPos(1024, 96, -2048);

    @Test
    public void aDeckPointRoundTripsThroughItsOffset() {
        double sx = 1027.375, sy = 97.0, sz = -2051.5;

        double[] offset = ShipRelativePoint.offsetOfSubspacePoint(AFC, sx, sy, sz);
        double[] back = ShipRelativePoint.subspacePointOf(AFC, offset[0], offset[1], offset[2]);

        assertEquals("x must survive the round trip exactly", sx, back[0], 0.0D);
        assertEquals("y must survive the round trip exactly", sy, back[1], 0.0D);
        assertEquals("z must survive the round trip exactly", sz, back[2], 0.0D);
    }

    @Test
    public void aDeckPointSurvivesTheShipBeingRebuiltElsewhere() {
        // The same crew member, standing on the same spot of the same deck, before and after a
        // crossing rebuilds the ship at a completely different subspace address. The offset is what
        // travels; what must be preserved is where it lands RELATIVE to the rebuilt ship.
        double sx = 1021.5, sy = 100.25, sz = -2044.0;
        double[] offset = ShipRelativePoint.offsetOfSubspacePoint(AFC, sx, sy, sz);

        BlockPos rebuiltAfc = new BlockPos(-500_000, 40, 777_216);
        double[] after = ShipRelativePoint.subspacePointOf(
                rebuiltAfc, offset[0], offset[1], offset[2]);

        assertEquals(sx - AFC.getX(), after[0] - rebuiltAfc.getX(), 0.0D);
        assertEquals(sy - AFC.getY(), after[1] - rebuiltAfc.getY(), 0.0D);
        assertEquals(sz - AFC.getZ(), after[2] - rebuiltAfc.getZ(), 0.0D);
    }

    @Test
    public void aMissingComputerYieldsNoPointRatherThanThrowing() {
        // Both directions run on paths where the ship may simply not be loaded yet - a login tick
        // during an asynchronous re-assembly. They answer "not now", never with an exception.
        assertNull(ShipRelativePoint.subspacePointOf(null, 1.0, 2.0, 3.0));
        assertNull(ShipRelativePoint.offsetOfSubspacePoint(null, 1.0, 2.0, 3.0));
    }
}
