package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
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
}
