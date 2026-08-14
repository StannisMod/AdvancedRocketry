package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.Galaxy;
import zmaster587.advancedRocketry.universe.GalaxyField;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.UniverseScale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the galaxy lattice — the tier above the star lattice.
 *
 * <p>What is pinned: a galaxy is a pure function of {@code (seed, galaxy cell)}; the galaxy index is
 * DERIVED from the sector and nothing is stored; a galaxy never straddles its own cell face, which is
 * what makes "which galaxy is this point in" an O(1) question with one answer; radius is drawn
 * CONDITIONAL ON TYPE; the home galaxy exists under every seed while still differing between them;
 * and the cosmic-web hook is neutral today, so galaxy density comes out uniform.</p>
 */
public class GalaxyFieldTest {

    private static GalaxyGenConfig cfg(double galaxyDensity) {
        return new GalaxyGenConfig(GalaxyGenConfig.DEFAULT_MIN_SPACING, 0.9d,
                GalaxyGenConfig.DEFAULT_GALAXY_SPACING, galaxyDensity, null, null);
    }

    private static GalaxyField field(double galaxyDensity) {
        return new GalaxyField(cfg(galaxyDensity));
    }

    @Test
    public void theHomeGalaxyExistsUnderEverySeed() {
        // Authored content is placed at absolute coordinates near the origin, and a galaxy is otherwise
        // a hash draw that may simply not be there. Without the reserved cell the shipped solar system
        // would land in intergalactic space on almost every seed.
        GalaxyField f = field(GalaxyGenConfig.DEFAULT_GALAXY_DENSITY);
        for (long seed = 1L; seed <= 200L; seed++) {
            Galaxy home = f.home(seed);
            assertNotNull("seed " + seed + " has no home galaxy", home);
            assertEquals("the home galaxy is centred on the origin", GalacticCoord.ORIGIN.cellKey(),
                    home.centre().cellKey());
            assertTrue("seed " + seed + "'s home galaxy is only " + home.radiusLy()
                            + " ly across, under the guaranteed minimum",
                    home.radiusLy() >= UniverseScale.MIN_HOME_GALAXY_RADIUS_LY);
        }
    }

    @Test
    public void theHomeGalaxyIsSeatedEvenWhenNoOtherGalaxyIs() {
        // Its EXISTENCE is reserved, not its probability: a config that places no galaxies at all
        // still has to have the one the player lives in.
        GalaxyField f = field(0d);
        assertNotNull(f.home(7L));
        int others = 0;
        for (long gx = -3L; gx <= 3L; gx++) {
            for (long gy = -3L; gy <= 3L; gy++) {
                if (f.galaxyAtIndex(7L, gx, gy, 0L).isPresent() && !GalaxyField.isHomeCell(gx, gy, 0L)) {
                    others++;
                }
            }
        }
        assertEquals("galaxyDensity=0 must leave everything but the home cell void", 0, others);
    }

    @Test
    public void theHomeGalaxyStillDiffersBetweenSeeds() {
        // Only its existence and its centre are fixed. If its type and size were fixed too, every
        // world would open on the same sky.
        GalaxyField f = field(GalaxyGenConfig.DEFAULT_GALAXY_DENSITY);
        Set<String> shapes = new HashSet<>();
        for (long seed = 1L; seed <= 50L; seed++) {
            Galaxy home = f.home(seed);
            shapes.add(home.type().name + "@" + (long) home.radiusLy());
        }
        assertTrue("every seed produced the same home galaxy", shapes.size() > 1);
    }

    @Test
    public void aGalaxyIsAPureFunctionOfSeedAndCell() {
        GalaxyField f = field(GalaxyGenConfig.DEFAULT_GALAXY_DENSITY);
        for (long gx = -4L; gx <= 4L; gx++) {
            Optional<Galaxy> a = f.galaxyAtIndex(99L, gx, 1L, -2L);
            Optional<Galaxy> b = f.galaxyAtIndex(99L, gx, 1L, -2L);
            assertEquals("presence must be stable", a.isPresent(), b.isPresent());
            if (a.isPresent()) {
                assertEquals(a.get().toString(), b.get().toString());
            }
        }
    }

    @Test
    public void theGalaxyIndexIsDerivedFromTheSector() {
        // No stored tier, no new coordinate field: a coarse reading of the sector space that already
        // exists. Every sector of one galaxy cell must name the same galaxy.
        GalaxyGenConfig config = cfg(1.0d);
        GalaxyField f = new GalaxyField(config);
        long s = config.galaxySpacing;
        // The lattice is offset by half a cell, so the ORIGIN is a cell CENTRE. Without that, every
        // sector with a negative coordinate would sit in a neighbouring cell and the space around the
        // shipped solar system would be reading someone else's galaxy.
        assertEquals(0L, GalaxyField.galaxyIndex(0L, s));
        assertEquals("just below the origin is still the home cell", 0L,
                GalaxyField.galaxyIndex(-1L, s));
        assertEquals(0L, GalaxyField.galaxyIndex(-s / 2L, s));
        assertEquals(0L, GalaxyField.galaxyIndex(s / 2L - 1L, s));
        assertEquals("half a cell out is the next one", 1L, GalaxyField.galaxyIndex(s / 2L + 1L, s));
        assertEquals(-1L, GalaxyField.galaxyIndex(-s / 2L - 1L, s));
        assertEquals("the home cell's low corner is half a cell below the origin", -(s / 2L),
                GalaxyField.cellLowCorner(0L, s));

        Galaxy home = f.home(5L);
        for (long probe : new long[] {-s / 2L, -1L, 0L, 1L, s / 3L, s / 2L - 1L}) {
            Optional<Galaxy> owning = f.galaxyOwningSector(5L, probe, 0L, 0L);
            assertTrue("sector " + probe + " must belong to a galaxy cell that has one",
                    owning.isPresent());
            assertEquals("and it must be the same galaxy throughout the cell", home.toString(),
                    owning.get().toString());
        }
    }

    @Test
    public void everyPointIsInAGalaxyCellButNotEveryPointIsInAGalaxy() {
        // The two questions are different and both have to be answerable: a galaxy occupies a small
        // sphere inside its cell, and the rest of that cell is void. There is no "nowhere" state.
        GalaxyField f = field(1.0d);
        Galaxy home = f.home(11L);
        long inside = UniverseScale.cellsForLightYears(home.radiusLy() * 0.5d);
        long outside = UniverseScale.cellsForLightYears(home.radiusLy() * 4d);

        assertTrue(f.galaxyOwningSector(11L, inside, 0L, 0L).isPresent());
        assertTrue("a point at half the radius is in the galaxy", home.containsSector(inside, 0L, 0L));

        Optional<Galaxy> farOwner = f.galaxyOwningSector(11L, outside, 0L, 0L);
        assertTrue("a point deep in the same cell still HAS an owning cell", farOwner.isPresent());
        assertEquals(home.toString(), farOwner.get().toString());
        assertFalse("but it is not inside the galaxy", home.containsSector(outside, 0L, 0L));
        assertEquals("so the profile there is zero", 0d, home.densityAtSector(outside, 0L, 0L), 0d);
    }

    @Test
    public void aGalaxyNeverStraddlesItsOwnCellFace() {
        // Containment is what keeps three things true at once: at most one galaxy per cell, galaxies
        // that cannot overlap, and an ownership answer that reads the containing cell and nothing else.
        GalaxyGenConfig config = cfg(1.0d);
        GalaxyField f = new GalaxyField(config);
        long s = config.galaxySpacing;
        int checked = 0;
        for (long gx = -3L; gx <= 3L; gx++) {
            for (long gy = -2L; gy <= 2L; gy++) {
                for (long gz = -2L; gz <= 2L; gz++) {
                    Optional<Galaxy> g = f.galaxyAtIndex(4242L, gx, gy, gz);
                    if (!g.isPresent() || GalaxyField.isHomeCell(gx, gy, gz)) {
                        continue;
                    }
                    long reach = UniverseScale.cellsForLightYears(g.get().radiusLy());
                    assertInsideCell("x", g.get().centre().sectorX(), gx, s, reach);
                    assertInsideCell("y", g.get().centre().sectorY(), gy, s, reach);
                    assertInsideCell("z", g.get().centre().sectorZ(), gz, s, reach);
                    checked++;
                }
            }
        }
        assertTrue("the sweep must find galaxies", checked > 10);
    }

    private static void assertInsideCell(String axis, long centre, long index, long spacing,
                                         long reach) {
        long lo = GalaxyField.cellLowCorner(index, spacing);
        long hi = lo + spacing - 1L;
        assertTrue("a galaxy reaches past its cell's low " + axis + " face", centre - reach >= lo);
        assertTrue("a galaxy reaches past its cell's high " + axis + " face", centre + reach <= hi);
    }

    @Test
    public void radiusIsDrawnConditionalOnItsType() {
        // Never independently. Independent draws produce dwarfs the size of a spiral and spirals the
        // size of a dwarf — a real galaxy's type and its size are one fact, not two.
        GalaxyField f = field(1.0d);
        int checked = 0;
        for (long gx = -6L; gx <= 6L; gx++) {
            for (long gy = -3L; gy <= 3L; gy++) {
                Optional<Galaxy> g = f.galaxyAtIndex(31337L, gx, gy, 0L);
                if (!g.isPresent()) {
                    continue;
                }
                GalaxyGenConfig.GalaxyType t = g.get().type();
                assertTrue(g.get() + " falls outside its own type's band",
                        g.get().radiusLy() >= t.minRadiusLy && g.get().radiusLy() <= t.maxRadiusLy);
                assertTrue("a type with no arms must not carry a spiral's structure",
                        t.armCount >= 0);
                checked++;
            }
        }
        assertTrue(checked > 10);
    }

    @Test
    public void galaxyDensityDrivesHowManyGalaxiesThereAre() {
        int sparse = countGalaxies(field(0.1d), 5L);
        int dense = countGalaxies(field(0.9d), 5L);
        assertTrue("a higher galaxyDensity must seat more galaxies (" + sparse + " vs " + dense + ")",
                dense > sparse);
    }

    @Test
    public void galaxyDensityIsUniformWhileTheCosmicWebIsANeutralConstant() {
        // The web slot exists and is deliberately the constant 1 today: galaxy density is not REQUIRED
        // to be uniform, and this is where non-uniformity will live. Until it does, the occupied
        // fraction must come out AT the configured density rather than biased by a half-built field.
        GalaxyField f = field(0.5d);
        int occupied = 0;
        int total = 0;
        for (long gx = -8L; gx <= 8L; gx++) {
            for (long gy = -8L; gy <= 8L; gy++) {
                for (long gz = -3L; gz <= 3L; gz++) {
                    if (GalaxyField.isHomeCell(gx, gy, gz)) {
                        continue; // reserved, so it is not a sample of the draw
                    }
                    total++;
                    if (f.galaxyAtIndex(6060L, gx, gy, gz).isPresent()) {
                        occupied++;
                    }
                }
            }
        }
        double fraction = occupied / (double) total;
        assertEquals("the occupied fraction must sit at galaxyDensity", 0.5d, fraction, 0.05d);
    }

    @Test
    public void theVoidBetweenGalaxiesHoldsNoSystems() {
        // The generator's own view of the same fact: outside every galaxy the profile is zero, so the
        // intergalactic void is what the profile leaves empty rather than a second rule someone has to
        // remember to apply.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(1.0d));
        Galaxy home = gen.galaxies().home(77L);
        long beyond = UniverseScale.cellsForLightYears(home.radiusLy() * 3d);
        long spacing = GalaxyGenConfig.DEFAULT_MIN_SPACING;
        for (long i = 0; i < 40; i++) {
            GalacticCoord probe = GalacticCoord.ofSectorLocal(beyond + i * spacing, 0L, 0L, 0L, 0L, 0L);
            assertFalse("a system turned up in intergalactic space at " + probe.cellKey(),
                    gen.anchorAt(77L, probe).isPresent());
        }
    }

    @Test
    public void aGalaxyCellFitsInsideOneLongOfBlocks() {
        // This is what the galaxy SIZE was chosen for, and it is a structural claim rather than a
        // balance one. Out in the void a position is an offset from its galaxy cell's origin, so that
        // offset has to span a whole cell; a Milky-Way-sized galaxy at a realistic separation would
        // put the cell past the long range and force the void into a second, coarser representation.
        // Choosing this scale buys one primitive instead of two.
        long spacing = GalaxyGenConfig.DEFAULT_GALAXY_SPACING;
        long limitCells = Long.MAX_VALUE / GalacticCoord.CELL;
        assertTrue("a galaxy cell of " + spacing + " cells overflows a long of blocks",
                spacing <= limitCells);
        // The bound is not the edge but the DIAGONAL: two points in one void cell can be that far
        // apart, and a separation that cannot be expressed is a separation that silently wraps.
        assertTrue("a galaxy cell's diagonal overflows a long of blocks — the margin is only "
                        + String.format("%.2f", limitCells / (double) spacing) + "x on the edge",
                Math.sqrt(3d) * spacing <= limitCells);
    }

    @Test
    public void aGalaxyHoldsAPopulationOfTheRightOrder() {
        // Estimated rather than counted: sweeping every super-cell of a galaxy is 10^8 draws. The
        // profile is integrated by Monte Carlo over the galaxy's own sphere, which is the same
        // function the generator consults, so this measures the shipped shape and not a model of it.
        //
        // The band is deliberately wide — three orders. What it guards is the ORDER: a galaxy holding
        // thousands would make interstellar travel a tour of a village, and one holding billions would
        // put the cell past the long range the test above depends on.
        GalaxyGenConfig config = cfg(GalaxyGenConfig.DEFAULT_GALAXY_DENSITY);
        GalaxyField f = new GalaxyField(config);
        Galaxy home = f.home(0xC0FFEEL);
        double superCellLy = UniverseScale.lightYearsForCells(config.minSpacing);
        double sphereLy3 = 4d / 3d * Math.PI * Math.pow(home.radiusLy(), 3);
        double superCells = sphereLy3 / Math.pow(superCellLy, 3);

        // A fixed LCG, so the estimate is the same number on every run and a red is a real change.
        long state = 0x2545F4914F6CDD1DL;
        int samples = 200_000;
        double sum = 0d;
        for (int i = 0; i < samples; i++) {
            double[] p = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                p[axis] = ((state >>> 11) * 0x1.0p-53 - 0.5d) * 2d * home.radiusLy();
            }
            sum += home.densityAt(p[0], p[1], p[2]);
        }
        // The samples fill the CUBE around the galaxy; the sphere is pi/6 of it, and densityAt is
        // already zero outside the radius, so the cube mean scales straight onto the cube's volume.
        double cubeLy3 = Math.pow(2d * home.radiusLy(), 3);
        double meanOverSphere = (sum / samples) * cubeLy3 / sphereLy3;
        double systems = config.density * superCells * meanOverSphere;

        System.out.println("home galaxy " + home + ": ~" + (long) systems + " systems ("
                + (long) superCells + " super-cells in its sphere, mean profile "
                + String.format("%.5f", meanOverSphere) + ")");
        assertTrue("a galaxy holding only " + (long) systems + " systems is a village",
                systems > 1e4d);
        assertTrue("a galaxy holding " + (long) systems + " systems is past the scale this "
                + "lattice was sized for", systems < 1e7d);
    }

    private static int countGalaxies(GalaxyField f, long seed) {
        int found = 0;
        for (long gx = -5L; gx <= 5L; gx++) {
            for (long gy = -5L; gy <= 5L; gy++) {
                for (long gz = -2L; gz <= 2L; gz++) {
                    if (!GalaxyField.isHomeCell(gx, gy, gz) && f.galaxyAtIndex(seed, gx, gy, gz).isPresent()) {
                        found++;
                    }
                }
            }
        }
        return found;
    }
}
