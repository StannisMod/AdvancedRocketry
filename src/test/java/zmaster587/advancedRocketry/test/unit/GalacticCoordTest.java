package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the sectorized fixed-point galactic coordinate.
 *
 * Pins the value-type invariants that the rest of the space subsystem relies on: the
 * {@code absolute = sector*CELL + local} identity, the canonical local range, cell identity
 * (same sector => same cell/key), exact Euclidean distance across cells, drift-free integration,
 * and NBT round-trip. Does not pin internal field layout beyond what these public contracts imply.
 */
public class GalacticCoordTest {

    private static final long CELL = GalacticCoord.CELL;
    private static final long HALF = GalacticCoord.HALF_CELL;
    private static final double EPS = 1e-6;

    private static void assertLocalCanonical(GalacticCoord c) {
        assertTrue("localX in [-HALF, HALF)", c.localX() >= -HALF && c.localX() < HALF);
        assertTrue("localY in [-HALF, HALF)", c.localY() >= -HALF && c.localY() < HALF);
        assertTrue("localZ in [-HALF, HALF)", c.localZ() >= -HALF && c.localZ() < HALF);
    }

    @Test
    public void absoluteRoundTripWithinCell() {
        GalacticCoord c = GalacticCoord.ofAbsolute(123L, -456L, 789L);
        assertEquals(0L, c.sectorX());
        assertEquals(0L, c.sectorY());
        assertEquals(0L, c.sectorZ());
        assertEquals(123L, c.absoluteX());
        assertEquals(-456L, c.absoluteY());
        assertEquals(789L, c.absoluteZ());
        assertLocalCanonical(c);
    }

    @Test
    public void absoluteRoundTripAcrossManyCells() {
        long ax = 37L * CELL + 111L;
        long ay = -12L * CELL - 5L;
        long az = 4L * CELL - HALF; // lands exactly on a cell's lower edge
        GalacticCoord c = GalacticCoord.ofAbsolute(ax, ay, az);
        assertEquals(ax, c.absoluteX());
        assertEquals(ay, c.absoluteY());
        assertEquals(az, c.absoluteZ());
        assertLocalCanonical(c);
    }

    @Test
    public void sectorLocalIdentityHolds() {
        GalacticCoord c = GalacticCoord.ofSectorLocal(5L, -3L, 8L, 100L, -200L, 300L);
        assertEquals(5L * CELL + 100L, c.absoluteX());
        assertEquals(-3L * CELL - 200L, c.absoluteY());
        assertEquals(8L * CELL + 300L, c.absoluteZ());
    }

    @Test
    public void localOffsetIsRenormalisedWithSectorCarry() {
        // Local offsets far outside a cell must fold back in and carry into the sector.
        GalacticCoord c = GalacticCoord.ofSectorLocal(0L, 0L, 0L, CELL + 10L, -CELL - 10L, 3L * CELL);
        assertLocalCanonical(c);
        assertEquals(CELL + 10L, c.absoluteX());
        assertEquals(-CELL - 10L, c.absoluteY());
        assertEquals(3L * CELL, c.absoluteZ());
    }

    @Test
    public void cellBoundaryFoldsToNextSector() {
        // The half-cell edge belongs to the next sector; its mirror belongs to this one: range [-HALF, HALF).
        GalacticCoord upper = GalacticCoord.ofAbsolute(HALF, 0L, 0L);
        assertEquals(1L, upper.sectorX());
        assertEquals(-HALF, upper.localX());

        GalacticCoord lower = GalacticCoord.ofAbsolute(-HALF, 0L, 0L);
        assertEquals(0L, lower.sectorX());
        assertEquals(-HALF, lower.localX());

        GalacticCoord justBelow = GalacticCoord.ofAbsolute(HALF - 1L, 0L, 0L);
        assertEquals(0L, justBelow.sectorX());
    }

    @Test
    public void sameCellTracksSectorTripleNotLocal() {
        GalacticCoord a = GalacticCoord.ofSectorLocal(2L, 2L, 2L, -HALF, 0L, HALF - 1L);
        GalacticCoord b = GalacticCoord.ofSectorLocal(2L, 2L, 2L, HALF - 1L, -5L, 0L);
        GalacticCoord other = GalacticCoord.ofSectorLocal(2L, 2L, 3L, 0L, 0L, 0L);

        assertTrue(a.sameCell(b));
        assertEquals(a.cellKey(), b.cellKey());
        assertFalse(a.sameCell(other));
        assertNotEquals(a.cellKey(), other.cellKey());
    }

    @Test
    public void cellKeyRoundTripsThroughFromCellKey() {
        GalacticCoord cell = GalacticCoord.ofSectorLocal(-3L, 1L, 7L, 0L, 0L, 0L);
        GalacticCoord back = GalacticCoord.fromCellKey(cell.cellKey());
        assertEquals(cell, back);
        // Malformed keys answer null instead of throwing (a slot may be unbound).
        assertNull(GalacticCoord.fromCellKey(null));
        assertNull(GalacticCoord.fromCellKey("scratch"));
        assertNull(GalacticCoord.fromCellKey("1_2"));
    }

    @Test
    public void cellCentreZeroesLocalAndStaysInCell() {
        GalacticCoord c = GalacticCoord.ofSectorLocal(7L, -1L, 4L, 12345L, -6789L, 42L);
        GalacticCoord centre = c.cellCentre();
        assertTrue(c.sameCell(centre));
        assertEquals(0, centre.localX());
        assertEquals(0, centre.localY());
        assertEquals(0, centre.localZ());
        // The centre of sector s sits at absolute s*CELL.
        assertEquals(7L * CELL, centre.absoluteX());
    }

    @Test
    public void distanceIsExactEuclideanAcrossCells() {
        GalacticCoord a = GalacticCoord.ofAbsolute(0L, 0L, 0L);
        GalacticCoord b = GalacticCoord.ofAbsolute(3L, 4L, 12L);
        assertEquals(169.0, a.staticFrameDistanceSqTo(b), EPS); // 3-4-12 => 13^2 = 169
        assertEquals(13.0, a.staticFrameDistanceTo(b), EPS);

        // A separation of exactly one cell along each axis.
        GalacticCoord p = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L);
        GalacticCoord q = GalacticCoord.ofSectorLocal(1L, 0L, 0L, 0L, 0L, 0L);
        assertEquals((double) CELL, p.staticFrameDistanceTo(q), 1.0);
    }

    @Test
    public void distanceIsSymmetric() {
        GalacticCoord a = GalacticCoord.ofSectorLocal(-4L, 9L, 2L, 500L, -600L, 700L);
        GalacticCoord b = GalacticCoord.ofSectorLocal(3L, -5L, 8L, -100L, 200L, -300L);
        assertEquals(a.staticFrameDistanceSqTo(b), b.staticFrameDistanceSqTo(a), EPS);
    }

    @Test
    public void integrationDoesNotDriftOverManySteps() {
        // Repeated per-tick steps must equal a single large offset - the fixed-point no-drift property.
        GalacticCoord stepwise = GalacticCoord.ORIGIN;
        for (int i = 0; i < 1_000_000; i++) {
            stepwise = stepwise.plusLocal(7L, 0L, 0L);
        }
        GalacticCoord oneShot = GalacticCoord.ORIGIN.plusLocal(7_000_000L, 0L, 0L);

        assertEquals(oneShot, stepwise);
        assertEquals(7_000_000L, stepwise.absoluteX());
    }

    @Test
    public void plusLocalCarriesAcrossCellBoundary() {
        GalacticCoord near = GalacticCoord.ofSectorLocal(0L, 0L, 0L, HALF - 10L, 0L, 0L);
        GalacticCoord crossed = near.plusLocal(20L, 0L, 0L);
        assertEquals(1L, crossed.sectorX());
        assertLocalCanonical(crossed);
        assertEquals(HALF + 10L, crossed.absoluteX());
    }

    @Test
    public void nbtRoundTripPreservesIdentity() {
        GalacticCoord c = GalacticCoord.ofSectorLocal(123L, -456L, 789L, 111L, -222L, 333L);
        NBTTagCompound nbt = new NBTTagCompound();
        c.writeToNBT(nbt);

        GalacticCoord restored = GalacticCoord.readFromNBT(nbt);
        assertEquals(c, restored);
        assertEquals(c.hashCode(), restored.hashCode());
    }

    @Test
    public void readFromNbtWithoutTagIsOrigin() {
        assertEquals(GalacticCoord.ORIGIN, GalacticCoord.readFromNBT(new NBTTagCompound()));
    }

    @Test
    public void equalsAndHashCodeAgreeWithNormalisation() {
        // Same absolute position reached two ways (canonical vs. pre-normalisation) must be equal.
        GalacticCoord viaAbsolute = GalacticCoord.ofAbsolute(CELL + 100L, 0L, 0L);
        GalacticCoord viaSectorLocal = GalacticCoord.ofSectorLocal(0L, 0L, 0L, CELL + 100L, 0L, 0L);
        assertEquals(viaAbsolute, viaSectorLocal);
        assertEquals(viaAbsolute.hashCode(), viaSectorLocal.hashCode());
    }
}
