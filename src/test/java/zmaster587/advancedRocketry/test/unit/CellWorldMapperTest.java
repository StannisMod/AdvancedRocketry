package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the honest-3D cell&harr;slot-world pose mapping: all three axes realize
 * directly (world X/Z = cell-local X/Z; world Y = local Y + a fixed positive offset), the whole
 * canonical local range realizes ABOVE the vanilla void-kill line, and the mapping round-trips
 * exactly. The offset's numeric value is {@code tunable} and deliberately not pinned.
 */
public class CellWorldMapperTest {

    private static GalacticCoord at(long sx, long sy, long sz, long lx, long ly, long lz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, lx, ly, lz);
    }

    @Test
    public void worldXAndZAreTheCellLocalOffsets() {
        double[] pose = CellWorldMapper.poseWorldOf(at(3, -2, 7, 1500, 0, -42_000));
        assertEquals(1500.0, pose[0], 0.0);
        assertEquals(-42_000.0, pose[2], 0.0);
    }

    @Test
    public void fullCanonicalLocalYRangeRealizesAboveTheVoidKillLine() {
        // The lowest possible local Y (cell floor) must still realize above vanilla's Y=-64 kill.
        double[] floor = CellWorldMapper.poseWorldOf(at(0, 0, 0, 0, -GalacticCoord.HALF_CELL, 0));
        assertTrue("cell-floor pose " + floor[1] + " must stay above the void-kill line",
                floor[1] > -64.0);
        // And the mapping is monotone: the cell centre realizes HALF_CELL above the floor.
        double[] centre = CellWorldMapper.poseWorldOf(at(0, 0, 0, 0, 0, 0));
        assertEquals(GalacticCoord.HALF_CELL, centre[1] - floor[1], 0.0);
    }

    /**
     * The physics mod clamps every ship's altitude per physics step, and a ship's own thrust can
     * never carry it past the clamp - so the ceiling the subsystem initializes at registration
     * must sit ABOVE every pose a cell can realize, or some part of the advertised cell range is
     * an invisible wall. Pins ceiling-covers-band, not any particular number.
     */
    @Test
    public void initializedShipCeilingCoversEveryRealizablePose() {
        double top = CellWorldMapper.poseWorldOf(
                at(0, 0, 0, 0, GalacticCoord.HALF_CELL - 1, 0))[1];
        assertTrue("the ship ceiling raised at subsystem registration ("
                        + zmaster587.advancedRocketry.space.SpaceSubsystem.requiredShipCeiling()
                        + ") must clear the topmost realizable cell pose (" + top + ")",
                zmaster587.advancedRocketry.space.SpaceSubsystem.requiredShipCeiling() > top);
    }

    @Test
    public void poseMappingRoundTripsExactly() {
        GalacticCoord original = at(5, 1, -9, 123_456, -777_777, 42);
        double[] pose = CellWorldMapper.poseWorldOf(original);
        GalacticCoord back = CellWorldMapper.coordOfPose(original.cellCentre(),
                pose[0], pose[1], pose[2]);
        assertEquals(original, back);
    }

    @Test
    public void outOfRangePoseRenormalisesIntoTheNeighbouringSector() {
        // A pose past the cell's +X edge belongs to the next sector - the seam-crossing semantics.
        GalacticCoord cell = at(0, 0, 0, 0, 0, 0);
        double[] centrePose = CellWorldMapper.poseWorldOf(cell);
        GalacticCoord past = CellWorldMapper.coordOfPose(cell,
                GalacticCoord.HALF_CELL + 10.0, centrePose[1], 0.0);
        assertEquals(1L, past.sectorX());
    }

    /**
     * The other reading of the same pose, and the one a ship REPORTING its position must use: it
     * stays in the cell it is in.
     *
     * <p>The two are not interchangeable. A cell name is not a position — it names a world, a slot
     * binding and the ledger row that keeps that cell from being collected — and none of those follow
     * a pose over a cell face. A ship that renames itself by drifting ends up addressed in a cell
     * nobody loaded: its own cell's bodies vanish from its sky, its descent finds nothing to descend
     * to, and its jumps are refused for being somewhere it is not.</p>
     */
    @Test
    public void aReportedPosePastTheCellEdgeStaysInItsOwnCell() {
        GalacticCoord cell = at(0, 0, 0, 0, 0, 0);
        double[] centrePose = CellWorldMapper.poseWorldOf(cell);

        GalacticCoord held = CellWorldMapper.coordOfPoseWithin(cell,
                GalacticCoord.HALF_CELL + 10.0, centrePose[1], 0.0);

        assertEquals("a reported pose may not rename the cell", cell.cellKey(), held.cellKey());
        assertTrue("...and it is held at the boundary, not wrapped to the far side",
                held.localX() > 0L);
    }

    /**
     * The case that makes the clamp worth having, rather than a theoretical bound: an arrival paste
     * lands in a fixed block band near Y=200, while a cell's pose band starts at HALF_CELL + 256.
     * Inverting that pose gives a local Y just BELOW the cell's range — so a ship that reported
     * itself between the paste and the pose settle named the cell one sector down. It is reachable on
     * every single arrival, unlike the +X face, which takes hours of flight to reach.
     */
    @Test
    public void anArrivalPasteBandPoseDoesNotDropTheShipASectorDown() {
        GalacticCoord cell = at(57, 0, 5, 0, 0, 0);
        double pasteBandY = 200.0; // the arrival paste lane, far below the cell's own pose band

        assertTrue("the fixture must actually be outside the cell's local range",
                CellWorldMapper.poseEscapesCell(0.0, pasteBandY, 0.0));
        assertEquals("a paste-band pose must not name a neighbouring cell",
                cell.cellKey(),
                CellWorldMapper.coordOfPoseWithin(cell, 0.0, pasteBandY, 0.0).cellKey());
        assertEquals("...while the honest inverse still says it is out of range",
                cell.sectorY() - 1L,
                CellWorldMapper.coordOfPose(cell, 0.0, pasteBandY, 0.0).sectorY());
    }

    /** A pose inside the cell is not "escaping" — the detector must not fire on ordinary flight. */
    @Test
    public void anOrdinaryPoseInsideTheCellIsNotAnEscape() {
        GalacticCoord cell = at(1, 2, 3, 0, 0, 0);
        double[] pose = CellWorldMapper.poseWorldOf(at(1, 2, 3, 120_000L, -80_000L, 5L));

        assertFalse("a pose well inside the cell must not read as an escape",
                CellWorldMapper.poseEscapesCell(pose[0], pose[1], pose[2]));
        assertEquals("...and it round-trips unchanged through the held reading",
                CellWorldMapper.coordOfPose(cell, pose[0], pose[1], pose[2]),
                CellWorldMapper.coordOfPoseWithin(cell, pose[0], pose[1], pose[2]));
    }
}
