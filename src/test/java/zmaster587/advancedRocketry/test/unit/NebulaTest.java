package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.util.List;
import java.util.Optional;

import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.Galaxy;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.Nebula;
import zmaster587.advancedRocketry.universe.NebulaField;
import zmaster587.advancedRocketry.universe.StarCluster;
import zmaster587.advancedRocketry.universe.UniverseScale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for nebulae — the diffuse cloud a star cluster is wrapped in.
 *
 * <p>What is pinned is that a cloud is DERIVED from its cluster and cannot disagree with it, that its
 * appearance is one age sequence rather than three drawn options, that it reaches beyond the cluster
 * inside it (so it can be seen from outside, which is the whole point of having it), and that
 * {@code densityAt} is a continuous falloff with no edge — because that function is the seam every
 * later consequence will be written against.</p>
 *
 * <p><b>There is deliberately no test of what a nebula DOES</b>, because it does nothing yet. None of
 * those numbers is ratified.</p>
 */
public class NebulaTest {

    private static final long SEED = 0xC10DDL;

    private static ClusteredGalaxyGenerator gen() {
        return new ClusteredGalaxyGenerator(GalaxyGenConfig.defaults());
    }

    private static StarCluster clusterOfType(GalaxyGenConfig.ClusterType type) {
        return new StarCluster(type, 100L, 0L, 0L, 2L);
    }

    private static GalaxyGenConfig.ClusterType typeWithGas(double gas) {
        return new GalaxyGenConfig.ClusterType("Test", 4, 5d, 15d, gas, 1);
    }

    // ─── The derivation ────────────────────────────────────────────────────────

    @Test
    public void aCloudBelongsToItsClusterAndSharesItsPlace() {
        // Not seated separately, and that is the design: a cloud and the cluster inside it are one
        // object at two ages, so there is no way for them to disagree about where they are.
        NebulaField field = gen().nebulae();
        StarCluster cluster = clusterOfType(typeWithGas(0.8d));
        Optional<Nebula> nebula = field.nebulaOf(SEED, cluster);
        assertTrue(nebula.isPresent());
        assertEquals(cluster, nebula.get().cluster());

        double expectedX = UniverseScale.lightYearsForCells(
                (double) cluster.centreSuperX() * GalaxyGenConfig.DEFAULT_MIN_SPACING);
        assertEquals("a cloud is centred on its cluster", expectedX, nebula.get().centreXLy(), 1e-6d);
    }

    @Test
    public void aClusterWithNoGasLeftHasNoCloud() {
        // An ancient globular has blown its gas away — real ones are gas-free, and that is what the
        // type table states rather than something the generator decides separately.
        NebulaField field = gen().nebulae();
        assertFalse(field.nebulaOf(SEED, clusterOfType(typeWithGas(0d))).isPresent());
    }

    @Test
    public void aCloudReachesBeyondTheClusterInsideIt() {
        // The point of having one: a cluster is otherwise a pure refinement of the lattice with no
        // property anything outside it can observe. Real clouds dwarf their clusters.
        NebulaField field = gen().nebulae();
        StarCluster cluster = clusterOfType(typeWithGas(0.9d));
        Nebula nebula = field.nebulaOf(SEED, cluster).get();
        double clusterRadiusLy = UniverseScale.lightYearsForCells(
                (double) cluster.radiusSuperCells() * GalaxyGenConfig.DEFAULT_MIN_SPACING);
        assertTrue("a cloud of " + nebula.radiusLy() + " ly must exceed its cluster's "
                + clusterRadiusLy, nebula.radiusLy() > clusterRadiusLy);
    }

    @Test
    public void appearanceIsAnAgeSequenceNotAChoice() {
        // One number, three appearances, IN ORDER: dark while the stars are still forming inside it,
        // emitting once they ionise what is left, reflecting once the gas is blown clear. Three
        // independent draws would let a nearly-gone cloud come out thick and black.
        //
        // Pinned as the ORDERING over a sample rather than by repeating the thresholds here — a test
        // that copies the derivation it is checking cannot fail when the derivation is wrong.
        NebulaField field = gen().nebulae();
        double darkestEmission = 0d;
        double thinnestDark = 1d;
        double darkestReflection = 0d;
        double thinnestEmission = 1d;
        int seen = 0;
        for (int i = 0; i <= 20; i++) {
            double stated = i / 20d;
            Optional<Nebula> n = field.nebulaOf(SEED + i, clusterOfType(typeWithGas(stated)));
            if (!n.isPresent()) {
                continue;
            }
            double gas = n.get().peakDensity();
            seen++;
            switch (n.get().appearance()) {
                case DARK:
                    thinnestDark = Math.min(thinnestDark, gas);
                    break;
                case EMISSION:
                    darkestEmission = Math.max(darkestEmission, gas);
                    thinnestEmission = Math.min(thinnestEmission, gas);
                    break;
                default:
                    darkestReflection = Math.max(darkestReflection, gas);
                    break;
            }
        }
        assertTrue("the sample must contain clouds", seen > 5);
        assertTrue("every DARK cloud must be thicker than every EMISSION one",
                thinnestDark > darkestEmission);
        assertTrue("and every EMISSION one thicker than every REFLECTION one",
                thinnestEmission > darkestReflection);
    }

    // ─── The seam ──────────────────────────────────────────────────────────────

    @Test
    public void densityFallsOffSmoothlyAndStopsAtTheRadius() {
        // THE seam: every consequence a nebula could have — a muffled sensor, a drag, something
        // concealed, something mined — is a function of how thick it is here. Diffuse matter has no
        // edge, so the falloff is continuous; what it does have is a bound, so a consumer can stop.
        NebulaField field = gen().nebulae();
        Nebula n = field.nebulaOf(SEED, clusterOfType(typeWithGas(0.8d))).get();
        double cx = n.centreXLy();
        double cy = n.centreYLy();
        double cz = n.centreZLy();

        double centre = n.densityAt(cx, cy, cz);
        double half = n.densityAt(cx + n.radiusLy() * 0.5d, cy, cz);
        double edge = n.densityAt(cx + n.radiusLy() * 0.99d, cy, cz);
        assertEquals("the centre must be the stated peak", n.peakDensity(), centre, 1e-9d);
        assertTrue("it must thin outwards", centre > half);
        assertTrue("and keep thinning", half > edge);
        assertTrue("but never reach zero inside its own radius", edge > 0d);
        assertEquals("and be exactly zero outside it", 0d,
                n.densityAt(cx + n.radiusLy() * 1.01d, cy, cz), 0d);
        assertTrue(n.contains(cx, cy, cz));
        assertFalse(n.contains(cx + n.radiusLy() * 1.01d, cy, cz));
    }

    @Test
    public void aSectorReadingAgreesWithTheLengthItStandsFor() {
        // The rest of the layer asks in cell names; a nebula is written in light years. If those two
        // disagreed, a cloud would be placed by one metric and read by another.
        NebulaField field = gen().nebulae();
        Nebula n = field.nebulaOf(SEED, clusterOfType(typeWithGas(0.8d))).get();
        long sector = UniverseScale.cellsAt(n.centreXLy());
        assertEquals(n.densityAt(UniverseScale.lightYearsForCells(sector), 0d, 0d),
                n.densityAtSector(sector, 0L, 0L), 1e-9d);
    }

    // ─── Where they turn up ────────────────────────────────────────────────────

    @Test
    public void aGalaxyHoldsNebulaeAndTheyAreDeterministic() {
        ClusteredGalaxyGenerator g = gen();
        Galaxy home = g.galaxies().home(SEED);
        long spacing = g.clusters().spacingSuperCells();
        List<Nebula> found = g.nebulae().nebulaeInRegion(SEED, home, -3L * spacing, -2L * spacing,
                -2L * spacing, 3L * spacing, 2L * spacing, 2L * spacing);
        assertFalse("a galaxy must hold clouds", found.isEmpty());
        assertEquals("the same query must answer the same way", found.size(),
                g.nebulae().nebulaeInRegion(SEED, home, -3L * spacing, -2L * spacing, -2L * spacing,
                        3L * spacing, 2L * spacing, 2L * spacing).size());
        for (Nebula n : found) {
            assertNotNull(n.appearance());
            assertTrue("a cloud that exists must have something in it", n.peakDensity() > 0d);
        }
    }

    @Test
    public void thereIsNoDiffuseMatterOutsideAGalaxy() {
        // A cloud only exists where a cluster does, and a cluster only exists inside a galaxy — one
        // chain, not a separate rule about the void.
        ClusteredGalaxyGenerator g = gen();
        Galaxy home = g.galaxies().home(SEED);
        long farSector = UniverseScale.cellsForLightYears(home.radiusLy() * 4d);
        assertEquals(0d, g.nebulae().densityAtSector(SEED, home, farSector, 0L, 0L), 0d);
    }
}
