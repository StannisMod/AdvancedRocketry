package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.BlockDelta;
import zmaster587.advancedRocketry.space.CellFrames;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The frame seam: a cell NAME plus a tick resolves to a position — a cell holding a body rides that
 * body, a cell holding none stands still where its name says — and a distance exists only at a
 * stated tick, measured through both endpoints' frames.
 *
 * <p>The static reading is kept as the void case AND as the control: a witness that a distance
 * changes with time means nothing unless the same fixture, read over a grid that does not move,
 * gives the same answer twice.</p>
 */
public class CellFramesTest {

    private static GalacticCoord cell(long sx, long lx) {
        return GalacticCoord.ofSectorLocal(sx, 0L, 0L, lx, 0L, 0L);
    }

    /** A frame lookup that walks one named cell along +X and leaves every other cell alone. */
    private static CellFrames moving(final GalacticCoord which, final long blocksPerTick) {
        return new CellFrames() {
            @Override
            public AbsolutePos originAt(GalacticCoord name, long tick) {
                AbsolutePos base = AbsolutePos.ofCellName(name);
                return name.sameCell(which) ? base.plus(tick * blocksPerTick, 0L, 0L) : base;
            }
        };
    }

    @Test
    public void aVoidCellSitsWhereItsNameSaysForever() {
        GalacticCoord name = cell(7L, 0L);
        AbsolutePos expected = AbsolutePos.of(7L * GalacticCoord.CELL, 0L, 0L);
        assertEquals(expected, CellFrames.STATIC.originAt(name, 0L));
        assertEquals(expected, CellFrames.STATIC.originAt(name, 1_000_000L));
    }

    @Test
    public void anInCellOffsetIsCarriedThroughItsFrameUnchanged() {
        // The offset is already a displacement FROM the frame origin, which is what `local` means
        // once a name stopped being a place: it is added, never re-based.
        GalacticCoord coord = cell(2L, 5_000L);
        assertEquals(AbsolutePos.of(2L * GalacticCoord.CELL + 5_000L, 0L, 0L),
                CellFrames.STATIC.absoluteOf(coord, 0L));

        CellFrames drifting = moving(coord, 100L);
        assertEquals(AbsolutePos.of(2L * GalacticCoord.CELL + 5_000L + 4_000L, 0L, 0L),
                drifting.absoluteOf(coord, 40L));
    }

    /**
     * The observable the player actually feels, at the seam that owns it: a body seen from a cell
     * whose frame does not carry the observer visibly recedes, so the distance between two moving
     * cells — hence the cost and duration of a flight between them — is live. The static leg is the
     * control.
     */
    @Test
    public void aDistanceBetweenTwoCellsChangesWithTimeWhenOneOfThemMoves() {
        GalacticCoord here = cell(0L, 0L);
        GalacticCoord there = cell(3L, 0L);
        CellFrames receding = moving(there, 1_000L);

        double early = receding.distanceBetween(here, there, 0L);
        double late = receding.distanceBetween(here, there, 10_000L);
        assertTrue("a receding body gets further away", late > early);
        assertEquals("...by exactly what its frame travelled", 10_000_000d, late - early, 1d);

        assertEquals("control: over a static grid the same two names never move apart",
                CellFrames.STATIC.distanceBetween(here, there, 0L),
                CellFrames.STATIC.distanceBetween(here, there, 10_000L), 0d);
    }

    @Test
    public void theDeltaIsFromTheFirstArgumentToTheSecond() {
        // The render channel carries observer->body, so getting this backwards mirrors the whole sky.
        GalacticCoord observer = cell(0L, 100L);
        GalacticCoord body = cell(0L, 900L);
        BlockDelta delta = CellFrames.STATIC.deltaBetween(observer, body, 0L);
        assertEquals(800L, delta.dx());
        assertEquals(0L, delta.dy());
        assertEquals(0L, delta.dz());
    }

    @Test
    public void twoCellsInOneMovingSystemKeepTheirDistanceIfBothRide() {
        // Both endpoints on the same frame: the frame's motion cancels, which is why the descent
        // trigger and the in-cell placement ring need no frame lookup at all.
        GalacticCoord ship = cell(4L, 0L);
        GalacticCoord bodyInSameCell = cell(4L, 2_000L);
        CellFrames drifting = moving(ship, 12_345L);

        assertEquals(drifting.distanceBetween(ship, bodyInSameCell, 0L),
                drifting.distanceBetween(ship, bodyInSameCell, 999L), 0d);
        assertEquals("...and equals the plain in-cell delta",
                ship.staticFrameDistanceTo(bodyInSameCell),
                drifting.distanceBetween(ship, bodyInSameCell, 999L), 1e-6);
    }

    // ── separations wider than a block long ───────────────────────────────────

    /**
     * The furthest apart two sectors can be while a block delta between them still holds. Past this
     * the components are clamped, which is the whole subject of the two tests below.
     */
    private static final long BLOCK_REACH_SECTORS = Long.MAX_VALUE / GalacticCoord.CELL;

    @Test
    public void anOrdinarySeparationIsExactAndSaysSo() {
        // The control. Everything inside a galaxy is here, and a delta that reported itself clamped
        // when it was not would make the flag below useless by crying wolf.
        BlockDelta delta = CellFrames.STATIC.deltaBetween(cell(0L, 0L), cell(1_000_000L, 0L), 0L);
        assertFalse("a separation a million cells wide fits a long of blocks and must not be flagged",
                delta.isSaturated());
        assertEquals(1_000_000L * GalacticCoord.CELL, delta.dx());
    }

    @Test
    public void aSeparationTooWideForABlockLongCOMESBACKSAYINGSO() {
        // Deliberately asked for. Two things in different galaxies are further apart than three block
        // longs can hold — the galaxy lattice is millions of light years across — and the clamped
        // vector that comes back is a DIRECTION, not a distance. What must never happen is that it is
        // indistinguishable from a real one: a consumer measuring it would report a separation of
        // exactly Long.MAX_VALUE blocks as though it had measured something.
        GalacticCoord here = cell(0L, 0L);
        GalacticCoord farAway = cell(2L * BLOCK_REACH_SECTORS, 0L);

        BlockDelta delta = CellFrames.STATIC.deltaBetween(here, farAway, 0L);
        assertTrue("a separation past the block range must report itself saturated",
                delta.isSaturated());
        assertEquals("and must be held at the bound, never wrapped to a small number pointing back",
                Long.MAX_VALUE, delta.dx());

        // The direction survives, which is what the render and nav channels actually read.
        assertTrue("the clamped component must keep the sign of the real separation", delta.dx() > 0L);

        // And the distance is still answerable at that magnitude — through the positions, which are
        // sectorised, rather than through the delta, which is not.
        double honest = CellFrames.STATIC.distanceBetween(here, farAway, 0L);
        assertTrue("the true distance must exceed what the clamped vector can express: " + honest
                        + " vs " + delta.length(),
                honest > delta.length());
    }

    @Test
    public void addingToASaturatedDeltaDoesNotLaunderItBackIntoAnExactOne() {
        // A sum involving a lower bound is a lower bound. Dropping the flag here would let a clamped
        // vector re-enter the system as an exact answer one addition later.
        BlockDelta clamped = BlockDelta.saturated(Long.MAX_VALUE, 0L, 0L);
        assertTrue(clamped.plus(BlockDelta.of(1L, 2L, 3L)).isSaturated());
        assertTrue(BlockDelta.of(1L, 2L, 3L).plus(clamped).isSaturated());
        assertFalse("two exact deltas still add to an exact one",
                BlockDelta.of(1L, 0L, 0L).plus(BlockDelta.of(2L, 0L, 0L)).isSaturated());
    }
}
