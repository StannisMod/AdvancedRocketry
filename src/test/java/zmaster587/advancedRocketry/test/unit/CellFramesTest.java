package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.BlockDelta;
import zmaster587.advancedRocketry.space.CellFrames;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
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
}
