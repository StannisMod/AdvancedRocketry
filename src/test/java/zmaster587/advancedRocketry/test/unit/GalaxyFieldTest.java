package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.Cosmology;
import zmaster587.advancedRocketry.universe.GalacticAnchor;
import zmaster587.advancedRocketry.universe.GalacticFrame;
import zmaster587.advancedRocketry.universe.GalaxyKey;
import zmaster587.advancedRocketry.universe.Galaxy;
import zmaster587.advancedRocketry.universe.GalaxyField;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.LightYearVector;
import zmaster587.advancedRocketry.universe.PlanetarySystem;
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
            assertTrue("seed " + seed + "'s home galaxy is only " + home.radiusLy()
                            + " ly across, under the guaranteed minimum",
                    home.radiusLy() >= UniverseScale.MIN_AUTHORED_GALAXY_RADIUS_LY);
            assertTrue("the ORIGIN must be inside the home galaxy under seed " + seed,
                    home.containsSector(0L, 0L, 0L));
            // And out in the disc, not at the centre: the centre of a galaxy is its nucleus, which is
            // the last address a shipped solar system should have.
            double originRadius = home.localRadius(
                    -UniverseScale.lightYearsForCells(home.centre().sectorX()),
                    -UniverseScale.lightYearsForCells(home.centre().sectorY()),
                    -UniverseScale.lightYearsForCells(home.centre().sectorZ()));
            assertEquals("the origin must sit at a sun-like galactic radius",
                    UniverseScale.HOME_GALAXY_ORIGIN_FRACTION * home.radiusLy(), originRadius,
                    home.radiusLy() * 1e-3d);
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
                    // The whole RETINUE's reach, not the primary's radius: satellites are children
                    // inside this cube, and one seated outside it is a galaxy the index would hand to
                    // a neighbouring cell.
                    long reach = UniverseScale.cellsForLightYears(
                            UniverseScale.retinueReachLy(g.get().radiusLy()));
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
    public void theVoidBetweenGalaxiesHoldsNothingThatFormedThere() {
        // Outside every galaxy the BOUND profile is zero, so the intergalactic void is what the profile
        // leaves empty rather than a second rule someone has to remember to apply.
        //
        // "Empty of stars", not "empty": what a ship meets out here is material the galaxies threw out,
        // and that is the ejecta halo rather than the profile. This pins the half that has not moved —
        // nothing CONDENSES out here — and VoidContentTest pins the half that has.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(1.0d));
        Galaxy home = gen.galaxies().home(77L);
        // Past the whole RETINUE, not just past the primary: a satellite sits one to three diameters
        // out, so probing at three radii would be probing inside a galaxy and this test would be
        // asserting that a galaxy is empty. The void starts where the group ends.
        long beyond = UniverseScale.cellsForLightYears(
                UniverseScale.retinueReachLy(home.radiusLy()) * 1.5d);
        long spacing = GalaxyGenConfig.DEFAULT_MIN_SPACING;
        for (long i = 0; i < 40; i++) {
            GalacticCoord probe = GalacticCoord.ofSectorLocal(beyond + i * spacing, 0L, 0L, 0L, 0L, 0L);
            assertEquals("star-forming material turned up in intergalactic space at " + probe.cellKey(),
                    0d, gen.galaxies().materialAtSector(77L, probe.sectorX(), probe.sectorY(),
                            probe.sectorZ()).bound, 0d);
        }
    }

    @Test
    public void theGalaxyLatticeFitsTheSECTORSPACE_whichIsWhatNamesAPosition() {
        // What actually bounds this layer, and what does NOT.
        //
        // It does not: a galaxy cube no longer fits one long of BLOCKS, and never had to. A position
        // here is a cell NAME — a sector triple — plus an offset inside that cell, so the addressable
        // range is the sector space, not a block count. This test used to assert the opposite, and
        // that false constraint is what the galaxy scale had been compressed thirty-fold to satisfy.
        long spacing = GalaxyGenConfig.DEFAULT_GALAXY_SPACING;
        long blockLimitCells = Long.MAX_VALUE / GalacticCoord.CELL;
        assertTrue("a galaxy cube that fits a long of blocks means the scale is still compressed: "
                        + spacing + " cells vs " + blockLimitCells,
                spacing > blockLimitCells);

        // It does: the DIAGONAL of a galaxy cube has to be nameable, because a sector coordinate that
        // wraps renames the cell. That is the real ceiling and it is orders away.
        double diagonal = Math.sqrt(3d) * spacing;
        double headroom = Long.MAX_VALUE / diagonal;
        System.out.println(String.format(
                "galaxy cube %d cells (%.3e ly), diagonal %.3e cells, sector headroom %.2ex",
                spacing, UniverseScale.lightYearsForCells(spacing), diagonal, headroom));
        assertTrue("the galaxy lattice must fit the sector space with room to spare — headroom is only "
                        + String.format("%.2f", headroom) + "x", headroom >= 1000d);
    }

    @Test
    public void theReferenceSizeIsTheSizeTheTypeTableIsWrittenAgainst() {
        // The reference anchors the galaxy SEPARATION, and the type bands are absolute light years so
        // they can be checked against a catalogue. Nothing mechanical tied the two together, so the
        // bands could sit two orders from the reference and nothing would notice — which is exactly
        // what happened. This is that tie: the reference has to be a size an ordinary spiral IS.
        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        GalaxyGenConfig.GalaxyType spiral = typeNamed(config, "Spiral");
        assertTrue("the reference galaxy radius (" + UniverseScale.REFERENCE_GALAXY_RADIUS_LY
                        + " ly) falls outside the spiral band [" + spiral.minRadiusLy + ", "
                        + spiral.maxRadiusLy + "] — one of the two was moved without the other",
                UniverseScale.REFERENCE_GALAXY_RADIUS_LY >= spiral.minRadiusLy
                        && UniverseScale.REFERENCE_GALAXY_RADIUS_LY <= spiral.maxRadiusLy);
    }

    @Test
    public void authoredContentIsAdmittedToTheDISCGIANTSandToNoDwarf() {
        // The floor is a constraint on the TYPE DRAW, so what it really states is a SET: the classes a
        // galaxy holding authored content may be. A floor that slipped below the dwarf-irregular band
        // would let a pack's content be seated in an object a few thousand light years across and
        // land outside it on the next seed.
        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        double floor = UniverseScale.MIN_AUTHORED_GALAXY_RADIUS_LY;
        for (GalaxyGenConfig.GalaxyType t : config.galaxyTypes) {
            boolean dwarf = t.name.startsWith("Dwarf");
            boolean qualifies = t.minRadiusLy >= floor;
            assertEquals(t.name + " qualifies for authored content: expected " + !dwarf,
                    !dwarf, qualifies);
        }
    }

    private static GalaxyGenConfig.GalaxyType typeNamed(GalaxyGenConfig config, String name) {
        for (GalaxyGenConfig.GalaxyType t : config.galaxyTypes) {
            if (name.equals(t.name)) {
                return t;
            }
        }
        throw new AssertionError("the stock table has no type named " + name);
    }

    /**
     * The population a galaxy of this shape holds, at the SHIPPED densities.
     *
     * <p>Estimated rather than counted: sweeping every super-cell of a real-sized galaxy is 10¹¹
     * draws. The profile is integrated by Monte Carlo over the galaxy's own sphere, and it is the same
     * function the generator consults, so this measures the shipped shape and not a model of it.</p>
     */
    private static double estimateSystems(Galaxy galaxy, GalaxyGenConfig config) {
        double superCellLy = UniverseScale.lightYearsForCells(config.minSpacing);
        double sphereLy3 = 4d / 3d * Math.PI * Math.pow(galaxy.radiusLy(), 3);
        double superCells = sphereLy3 / Math.pow(superCellLy, 3);

        // A fixed LCG, so the estimate is the same number on every run and a red is a real change.
        long state = 0x2545F4914F6CDD1DL;
        int samples = 200_000;
        double sum = 0d;
        for (int i = 0; i < samples; i++) {
            double[] p = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                p[axis] = ((state >>> 11) * 0x1.0p-53 - 0.5d) * 2d * galaxy.radiusLy();
            }
            sum += galaxy.densityAt(p[0], p[1], p[2]);
        }
        // The samples fill the CUBE around the galaxy; densityAt is already zero outside the radius,
        // so the cube mean scales straight onto the cube's volume.
        double cubeLy3 = Math.pow(2d * galaxy.radiusLy(), 3);
        double meanOverSphere = (sum / samples) * cubeLy3 / sphereLy3;
        return config.density * superCells * meanOverSphere;
    }

    /** A spiral at exactly the reference radius: the galaxy the whole layer is quoted against. */
    private static Galaxy referenceSpiral() {
        return new Galaxy(0L, 0L, 0L, 0, GalacticCoord.ORIGIN,
                typeNamed(GalaxyGenConfig.defaults(), "Spiral"),
                UniverseScale.REFERENCE_GALAXY_RADIUS_LY,
                0d, 0d, Math.toRadians(20d), 0d, LightYearVector.ZERO);
    }

    @Test
    public void aReferenceSpiralHoldsTenToTheEleventhSystems() {
        // STATED BEFORE THE SWEEP. A galaxy at the reference radius, at the shipped star separation and
        // the shipped disc thickness, must come out at the population a real one has: ~10^11. This is
        // not a balance pin — it is the arithmetic that made the real scale choosable at all. Size,
        // separation and population are ONE fact (pi.R^2.h at h = 1000 ly and ~76 ly^3 per seat), so a
        // galaxy that came out at 10^6 here would mean the radius, the separation or the disc height
        // had stopped agreeing with each other.
        final double EXPECTED_SYSTEMS = 1e11d;
        final double TOLERANCE_FACTOR = 3d;

        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        Galaxy reference = referenceSpiral();
        double systems = estimateSystems(reference, config);

        System.out.println(String.format(
                "reference spiral r=%.0f ly, disc height %.0f ly, star separation %.2f ly"
                        + " -> ~%.3e systems (expected %.0e +/- x%.0f)",
                reference.radiusLy(), reference.radiusLy() * reference.type().scaleHeightRatio,
                UniverseScale.MEAN_STAR_SEPARATION_LY, systems, EXPECTED_SYSTEMS, TOLERANCE_FACTOR));

        assertTrue("a reference-sized galaxy holding ~" + String.format("%.3e", systems)
                        + " systems is not the 10^11 the scale was taken for",
                systems >= EXPECTED_SYSTEMS / TOLERANCE_FACTOR
                        && systems <= EXPECTED_SYSTEMS * TOLERANCE_FACTOR);
    }

    @Test
    public void everySeedsHomeGalaxyIsAPlaceOfTheRightOrder() {
        // The home galaxy's radius is DRAWN, so its population is not one number — a spiral at the
        // small end of its band and a giant elliptical differ by three orders, which is what a drawn
        // radius cubed means. The band here is therefore wide on purpose; what it guards is that no
        // seed opens on a village, and that none opens on something the lattice cannot address.
        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        GalaxyField f = new GalaxyField(config);
        for (long seed : new long[] {0xC0FFEEL, 1L, 2L, 3L, 17L, 99L}) {
            Galaxy home = f.home(seed);
            double systems = estimateSystems(home, config);
            System.out.println("seed " + seed + " home " + home + ": ~"
                    + String.format("%.3e", systems) + " systems");
            assertTrue("seed " + seed + "'s home galaxy holds only " + (long) systems + " systems",
                    systems > 1e9d);
            assertTrue("seed " + seed + "'s home galaxy holds " + String.format("%.3e", systems)
                    + " systems, past the largest galaxy a catalogue has (~10^14 stars)",
                    systems < 3e14d);
        }
    }

    // ─── The retinue: satellite galaxies ───────────────────────────────────────

    /** The separation in the primary's DIAMETERS — the unit the satellite band is stated in. */
    private static double diametersApart(Galaxy primary, Galaxy satellite) {
        double dx = UniverseScale.lightYearsForCells(
                (double) (satellite.centre().sectorX() - primary.centre().sectorX()));
        double dy = UniverseScale.lightYearsForCells(
                (double) (satellite.centre().sectorY() - primary.centre().sectorY()));
        double dz = UniverseScale.lightYearsForCells(
                (double) (satellite.centre().sectorZ() - primary.centre().sectorZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz) / (2d * primary.radiusLy());
    }

    @Test
    public void aGiantKeepsARetinueAndADwarfKeepsNone() {
        // The whole point of the feature: on the lattice alone the nearest galaxy is always 25
        // diameters away, because a cube holds one. A dwarf keeps none — it IS somebody's satellite.
        GalaxyField f = field(1.0d);
        int giantsWithRetinue = 0;
        int checked = 0;
        for (long gx = -6L; gx <= 6L; gx++) {
            for (long gy = -3L; gy <= 3L; gy++) {
                Optional<Galaxy> g = f.galaxyAtIndex(31337L, gx, gy, 0L);
                if (!g.isPresent()) {
                    continue;
                }
                Galaxy primary = g.get();
                int count = f.satellitesOf(31337L, primary).size();
                checked++;
                if (primary.type().maxSatellites == 0) {
                    assertEquals(primary + " keeps no satellites", 0, count);
                } else {
                    assertTrue(primary + " kept " + count + " satellites, outside its type's band ["
                                    + primary.type().minSatellites + ", "
                                    + primary.type().maxSatellites + "]",
                            count >= primary.type().minSatellites
                                    && count <= primary.type().maxSatellites);
                    giantsWithRetinue++;
                }
            }
        }
        assertTrue("the sweep must find galaxies", checked > 10);
        assertTrue("the sweep must find at least one galaxy that HAS a retinue, or this proves"
                + " nothing about satellites at all", giantsWithRetinue > 0);
    }

    @Test
    public void aRetinueIsAPureFunctionOfSeedAndCell() {
        // Same rule as the primary: nothing is stored, so two queries about the same group must never
        // disagree — including across two GalaxyField instances, which is what a reload really is.
        GalaxyField a = field(1.0d);
        GalaxyField b = field(1.0d);
        Galaxy primary = a.home(0xBEEFL);
        List<Galaxy> first = a.satellitesOf(0xBEEFL, primary);
        List<Galaxy> second = b.satellitesOf(0xBEEFL, b.home(0xBEEFL));

        assertEquals("the retinue must have the same size on a fresh field", first.size(),
                second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).toString(), second.get(i).toString());
        }
    }

    @Test
    public void noTwoGalaxiesInACubeOverlap() {
        // The single-answer invariant. Two overlapping spheres would make "which galaxy is this point
        // in" a question with two answers, and every frame, profile and cluster read rests on it
        // having one. It is geometry rather than a tie-break: a satellite is at least one full
        // DIAMETER out and at most a fraction of the primary's radius across.
        GalaxyField f = field(1.0d);
        int pairs = 0;
        for (long seed = 1L; seed <= 40L; seed++) {
            Galaxy primary = f.home(seed);
            List<Galaxy> retinue = f.satellitesOf(seed, primary);
            for (int i = 0; i < retinue.size(); i++) {
                Galaxy s = retinue.get(i);
                assertTrue(s + " is not smaller than its primary " + primary,
                        s.radiusLy() <= UniverseScale.MAX_SATELLITE_RADIUS_FRACTION
                                * primary.radiusLy());
                double d = diametersApart(primary, s);
                assertTrue(s + " sits " + String.format("%.2f", d) + " diameters out, outside the band",
                        d >= UniverseScale.MIN_SATELLITE_DISTANCE_IN_DIAMETERS * 0.99d
                                && d <= UniverseScale.MAX_SATELLITE_DISTANCE_IN_DIAMETERS * 1.01d);
                assertTrue(s + " overlaps its primary " + primary,
                        d * 2d * primary.radiusLy() > primary.radiusLy() + s.radiusLy());
                for (int j = i + 1; j < retinue.size(); j++) {
                    Galaxy other = retinue.get(j);
                    double sep = separationLy(s, other);
                    assertTrue(s + " overlaps " + other + " (" + (long) sep + " ly apart)",
                            sep > s.radiusLy() + other.radiusLy());
                    pairs++;
                }
            }
        }
        assertTrue("the sweep must compare at least one PAIR of satellites, or the overlap check"
                + " between two of them never executed", pairs > 0);
    }

    private static double separationLy(Galaxy a, Galaxy b) {
        double dx = UniverseScale.lightYearsForCells(
                (double) (a.centre().sectorX() - b.centre().sectorX()));
        double dy = UniverseScale.lightYearsForCells(
                (double) (a.centre().sectorY() - b.centre().sectorY()));
        double dz = UniverseScale.lightYearsForCells(
                (double) (a.centre().sectorZ() - b.centre().sectorZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    public void aSatelliteIsCloserThanTheNEARESTGIANT() {
        // The measurement the feature exists for, stated as the comparison rather than as a number:
        // the lattice spacing is the giant-to-giant distance and stays real, and the retinue fills in
        // what was missing beneath it.
        GalaxyField f = field(1.0d);
        double lattice = UniverseScale.GALAXY_SEPARATION_IN_DIAMETERS;
        int measured = 0;
        double nearest = Double.MAX_VALUE;
        for (long seed = 1L; seed <= 40L; seed++) {
            Galaxy primary = f.home(seed);
            for (Galaxy s : f.satellitesOf(seed, primary)) {
                nearest = Math.min(nearest, diametersApart(primary, s));
                measured++;
            }
        }
        assertTrue("no seed produced a satellite to measure", measured > 0);
        System.out.println(String.format(
                "nearest satellite over 40 seeds: %.2f diameters, against a lattice spacing of %.0f",
                nearest, lattice));
        assertTrue("a satellite at " + String.format("%.2f", nearest) + " diameters is no closer than"
                + " the lattice already put the nearest giant", nearest < lattice);
    }

    @Test
    public void aSatelliteIsNamedAPARTfromItsPrimary() {
        // A satellite is a destination with an address. Two galaxies in one cube sharing a name would
        // be two places a player could neither tell apart nor write down.
        GalaxyField f = field(1.0d);
        Galaxy primary = f.home(0xC0FFEEL);
        List<Galaxy> retinue = f.satellitesOf(0xC0FFEEL, primary);
        assertTrue("the fixture needs a home galaxy WITH a retinue", !retinue.isEmpty());

        Set<String> names = new HashSet<>();
        assertTrue(names.add(primary.name()));
        assertFalse("a primary must not report itself a satellite", primary.isSatellite());
        for (Galaxy s : retinue) {
            assertTrue("two galaxies in one cube share the name " + s.name(), names.add(s.name()));
            assertTrue(s + " must report itself a satellite", s.isSatellite());
            assertTrue("a satellite's name must be derived from its primary's: " + s.name(),
                    s.name().startsWith(primary.name() + "-S"));
        }
        assertEquals("a satellite keeps no retinue of its own — the group is one level deep",
                0, f.satellitesOf(0xC0FFEEL, retinue.get(0)).size());
    }

    @Test
    public void aSatelliteIsAPLACE_withStarsOfItsOwn() {
        // THE assumption the retinue was designed around, and the one nobody had checked: that the star
        // field can be generated at an offset inside a parent's cube. It can — placement reads the
        // profile of the galaxy CONTAINING a point, so a satellite is populated by the same generator
        // that populates its primary. Had the profile been read off the cube's OWNER instead, every
        // satellite would be named, addressable and completely empty, which is what this catches.
        GalaxyGenConfig config = cfg(1.0d);
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(config);
        GalaxyField f = gen.galaxies();

        long seed = 0xC0FFEEL;
        Galaxy primary = f.home(seed);
        List<Galaxy> retinue = f.satellitesOf(seed, primary);
        assertTrue("the fixture needs a home galaxy WITH a retinue", !retinue.isEmpty());
        Galaxy satellite = retinue.get(0);

        // Its centre is inside it, and the profile there is the SATELLITE's, not zero.
        GalacticCoord core = satellite.centre();
        assertEquals("the cell at a satellite's centre must resolve to the satellite",
                satellite.toString(),
                f.galaxyContaining(seed, core).get().toString());
        assertTrue("a satellite's own profile at its centre must be positive",
                satellite.densityAtSector(core.sectorX(), core.sectorY(), core.sectorZ()) > 0d);
        assertEquals("and the cube's PRIMARY must read zero there — that is why the containing galaxy"
                        + " is the one to ask", 0d,
                primary.densityAtSector(core.sectorX(), core.sectorY(), core.sectorZ()), 0d);

        // And the generator actually seats systems in it.
        long stride = config.minSpacing;
        Map<GalacticCoord, PlanetarySystem> found = gen.systemsInRegion(seed,
                GalacticCoord.ofSectorLocal(core.sectorX() - 3L * stride,
                        core.sectorY() - 3L * stride, core.sectorZ() - 3L * stride, 0L, 0L, 0L),
                GalacticCoord.ofSectorLocal(core.sectorX() + 3L * stride,
                        core.sectorY() + 3L * stride, core.sectorZ() + 3L * stride, 0L, 0L, 0L));
        System.out.println("satellite " + satellite + " holds " + found.size()
                + " systems in the 7x7x7 territories around its core");
        assertFalse("a satellite with no systems in it is not a place anybody can go to",
                found.isEmpty());
    }

    @Test
    public void aCellInsideASatelliteIsBOUNDtoTheSATELLITE() {
        // The frame decides both rotation and expansion, so getting this wrong does not make a
        // satellite slightly wrong — it makes its interior comove with a void it is not in, while the
        // primary it orbits turns.
        GalaxyField f = field(1.0d);
        long seed = 0xC0FFEEL;
        Galaxy primary = f.home(seed);
        List<Galaxy> retinue = f.satellitesOf(seed, primary);
        assertTrue("the fixture needs a home galaxy WITH a retinue", !retinue.isEmpty());
        Galaxy satellite = retinue.get(0);
        GalacticCoord core = satellite.centre();

        assertEquals("a cell inside a satellite is bound, not comoving", GalacticFrame.GALACTIC,
                f.frameAt(seed, core));
        assertEquals("and its position is the SATELLITE's bound law",
                satellite.boundPositionOfCellAt(core, 5_000L).toString(),
                f.positionAt(seed, core, 5_000L).toString());

        // The control: a point in the same cube but in no galaxy is still comoving.
        long past = UniverseScale.cellsForLightYears(
                UniverseScale.retinueReachLy(primary.radiusLy()) * 1.5d);
        GalacticCoord voidCell = GalacticCoord.ofSectorLocal(primary.centre().sectorX() + past,
                primary.centre().sectorY(), primary.centre().sectorZ(), 0L, 0L, 0L);
        assertEquals("past the whole group, a cell is comoving again", GalacticFrame.COMOVING,
                f.frameAt(seed, voidCell));
    }

    @Test
    public void aSatelliteCarriesItsPrimarysMotionSoTheGroupTravelsTogether() {
        // A group is bound: if a satellite drew its own peculiar velocity it would drift away from the
        // galaxy it orbits over the drift horizon. The home galaxy's retinue must stand as still as
        // the home galaxy does, or authored content's neighbours would leave it behind.
        GalaxyField f = field(1.0d);
        for (long seed : new long[] {1L, 7L, 0xC0FFEEL}) {
            Galaxy home = f.home(seed);
            for (Galaxy s : f.satellitesOf(seed, home)) {
                assertEquals("the home galaxy's satellites must not drift either", 0d,
                        s.peculiarVelocity().length(), 0d);
            }
            Optional<Galaxy> mover = f.galaxyAtIndex(seed, 3L, 1L, -2L);
            if (mover.isPresent()) {
                for (Galaxy s : f.satellitesOf(seed, mover.get())) {
                    assertEquals("a satellite travels with its primary",
                            mover.get().peculiarVelocity().toString(),
                            s.peculiarVelocity().toString());
                }
            }
        }
    }

    // ─── The intergalactic regime (R3 + R8) ────────────────────────────────────

    @Test
    public void theHomeGalaxyHasNoMotionOfItsOwn() {
        // It is the rest frame everything else is measured against: every other galaxy moves relative
        // to it, which is also what an observer actually sees. What must NOT happen is authored
        // content being left behind by its own galaxy — so the check is that the origin keeps its
        // place INSIDE the galaxy, not that the galaxy sits still on a static grid it does not live on.
        GalaxyField f = field(GalaxyGenConfig.DEFAULT_GALAXY_DENSITY);
        for (long seed = 1L; seed <= 30L; seed++) {
            Galaxy home = f.home(seed);
            assertEquals("the home galaxy must have no peculiar velocity", 0d,
                    home.peculiarVelocity().length(), 0d);
            double at0 = home.boundPositionOfCellAt(GalacticCoord.ORIGIN, 0L)
                    .distanceTo(home.centreAt(0L));
            for (long t : new long[] {1_000_000L, 1_000_000_000_000L}) {
                assertEquals("authored content must ride its galaxy, not be left behind by it", at0,
                        home.boundPositionOfCellAt(GalacticCoord.ORIGIN, t).distanceTo(home.centreAt(t)),
                        at0 * 1e-9d);
            }
        }
    }

    @Test
    public void aGalaxyCannotDriftOutOfItsOwnCell() {
        // The invariant peculiar velocity threatens: a galaxy that wandered into a neighbouring cell
        // would break at-most-one-per-cell, non-overlap, AND the O(1) ownership answer at once. The
        // bound is real code, and it is measured here rather than asserted — at realistic speeds it is
        // orders away from binding, which is the finding.
        GalaxyGenConfig config = cfg(1.0d);
        GalaxyField f = new GalaxyField(config);
        double halfCellLy = UniverseScale.lightYearsForCells(config.galaxySpacing / 2d);
        double worstFraction = 0d;
        int checked = 0;
        for (long gx = -4L; gx <= 4L; gx++) {
            for (long gy = -2L; gy <= 2L; gy++) {
                Optional<Galaxy> g = f.galaxyAtIndex(2024L, gx, gy, 0L);
                if (!g.isPresent() || GalaxyField.isHomeCell(gx, gy, 0L)) {
                    continue;
                }
                double drift = g.get().peculiarVelocity().length()
                        * (double) Cosmology.DRIFT_HORIZON_TICKS;
                double room = halfCellLy - g.get().radiusLy();
                assertTrue(g.get() + " drifts " + drift + " ly against " + room + " ly of room",
                        drift <= room);
                worstFraction = Math.max(worstFraction, drift / room);
                checked++;
            }
        }
        assertTrue(checked > 5);
        System.out.println("worst galaxy drift over the horizon: "
                + String.format("%.3e", worstFraction) + " of its available room");
    }

    @Test
    public void aGalaxyDrawsARealisticPeculiarVelocity() {
        GalaxyField f = field(1.0d);
        int checked = 0;
        for (long gx = -5L; gx <= 5L; gx++) {
            Optional<Galaxy> g = f.galaxyAtIndex(555L, gx, 3L, 0L);
            if (!g.isPresent() || GalaxyField.isHomeCell(gx, 3L, 0L)) {
                continue;
            }
            // 50..600 km/s, expressed in this layer's unit.
            double speed = g.get().peculiarVelocity().length();
            assertTrue("a galaxy must actually move", speed > 0d);
            assertTrue("and not faster than the band allows",
                    speed <= UniverseScale.lightYearsPerTick(600d) * 1.000001d);
            checked++;
        }
        assertTrue(checked > 3);
    }

    @Test
    public void aPointIsEitherBoundToItsGalaxyOrComovingInTheVoid() {
        // Two states and no third: there is no "nowhere". Every point belongs to exactly one galaxy
        // CELL, and inside that cell it is either in the galaxy or in the void of it.
        GalaxyField f = field(1.0d);
        Galaxy home = f.home(11L);
        GalacticCoord inside = GalacticCoord.ofSectorLocal(
                UniverseScale.cellsForLightYears(home.radiusLy() * 0.5d), 0L, 0L, 0L, 0L, 0L);
        GalacticCoord outside = GalacticCoord.ofSectorLocal(
                UniverseScale.cellsForLightYears(home.radiusLy() * 4d), 0L, 0L, 0L, 0L, 0L);

        assertEquals(GalacticFrame.GALACTIC, f.frameAt(11L, inside));
        assertEquals(GalacticFrame.COMOVING, f.frameAt(11L, outside));
    }

    @Test
    public void aBoundPointRotatesAndAVoidPointDoesNot() {
        // The two laws, told apart by what they DO. A bound point turns with the disc and keeps its
        // distance from the centre; a void point is carried by the Hubble flow and never rotates.
        GalaxyField f = field(1.0d);
        Galaxy home = f.home(11L);
        long boundCells = UniverseScale.cellsForLightYears(home.radiusLy() * 0.5d);
        GalacticCoord bound = GalacticCoord.ofSectorLocal(boundCells, 0L, 0L, 0L, 0L, 0L);
        long t = 200_000_000_000L; // long enough that the slow rotation is measurable

        LightYearVector at0 = f.positionAt(11L, bound, 0L);
        LightYearVector later = f.positionAt(11L, bound, t);
        assertTrue("a bound point must move with the disc", later.distanceTo(at0) > 0d);
        assertEquals("and keep its radius from the centre, because a galaxy does not expand",
                at0.distanceTo(home.centreAt(0L)), later.distanceTo(home.centreAt(t)),
                home.radiusLy() * 1e-9d);

        GalacticCoord voidCell = GalacticCoord.ofSectorLocal(
                UniverseScale.cellsForLightYears(home.radiusLy() * 4d), 0L, 0L, 0L, 0L, 0L);
        LightYearVector voidAt0 = f.positionAt(11L, voidCell, 0L);
        LightYearVector voidLater = f.positionAt(11L, voidCell, t);
        assertEquals("a void point is carried straight outwards, never sideways", 0d,
                voidLater.y(), 1e-9d);
        assertEquals("a void point is carried straight outwards, never sideways", 0d,
                voidLater.z(), 1e-9d);
        assertTrue("and it is carried by the Hubble flow", voidLater.x() > voidAt0.x());
        assertEquals("by exactly the scale factor", voidAt0.x() * Cosmology.scaleFactorAt(t),
                voidLater.x(), voidAt0.x() * 1e-12d);
    }

    // ─── Authored content is declared against a galaxy (R11) ───────────────────

    @Test
    public void aDeclaredGalaxyIsSeatedWhateverTheHashSays() {
        // A galaxy is a hash draw and may simply not be there under another seed, while authored
        // content must exist under EVERY seed. So naming a galaxy in the catalogue reserves its cell.
        long seed = 424242L;
        GalaxyField plain = field(0.2d);
        GalaxyKey empty = null;
        for (long gx = 1L; gx <= 40L && empty == null; gx++) {
            if (!plain.galaxyAtIndex(seed, gx, 0L, 0L).isPresent()) {
                empty = GalaxyKey.of(gx, 0L, 0L);
            }
        }
        assertNotNull("the sweep must find a void galaxy cell to reserve", empty);

        GalaxyGenConfig reserved = cfg(0.2d).withReservedGalaxies(Collections.singletonList(empty));
        GalaxyField withKey = new GalaxyField(reserved);
        assertTrue("a declared key must force its cell to hold a galaxy",
                withKey.galaxyAtIndex(seed, empty.gx(), empty.gy(), empty.gz()).isPresent());
        assertTrue(withKey.isReserved(empty.gx(), empty.gy(), empty.gz()));
        assertTrue("and it must be reachable by key", withKey.declarationOriginOf(seed, empty).isPresent());
    }

    @Test
    public void aGalaxyHoldingAuthoredContentIsDrawnBigEnoughForIt() {
        // The guarantee is a constraint on the type DRAW, never a clamp applied afterwards: a pack
        // that places a system 700 light years out must work on every seed.
        long seed = 909L;
        GalaxyKey key = GalaxyKey.of(6L, -2L, 3L);
        GalaxyField f = new GalaxyField(
                cfg(0.2d).withReservedGalaxies(Collections.singletonList(key)));
        Galaxy declared = f.galaxyAtIndex(seed, key.gx(), key.gy(), key.gz()).get();
        assertTrue("a reserved galaxy is only " + declared.radiusLy() + " ly across",
                declared.radiusLy() >= UniverseScale.MIN_AUTHORED_GALAXY_RADIUS_LY);
    }

    @Test
    public void aHomeDeclarationResolvesToItself() {
        // This is what centring the home galaxy on the ORIGIN buys, and it is the whole migration
        // story: a coordinate authored before galaxies existed means exactly what it used to.
        GalaxyField f = field(GalaxyGenConfig.DEFAULT_GALAXY_DENSITY);
        GalacticCoord local = GalacticCoord.ofSectorLocal(1_500_000L, -20_000L, 7L, 0L, 0L, 0L);
        GalacticAnchor anchor = GalacticAnchor.inHome(local);
        assertEquals(local.cellKey(),
                anchor.resolve(f.declarationOriginOf(3L, GalaxyKey.HOME)).cellKey());
    }

    @Test
    public void aDeclarationInAnotherGalaxyResolvesAgainstThatGalaxysCentre() {
        long seed = 77L;
        GalaxyKey key = GalaxyKey.of(2L, 0L, 0L);
        GalaxyField f = new GalaxyField(
                cfg(1.0d).withReservedGalaxies(Collections.singletonList(key)));
        GalacticCoord centre = f.centreOf(seed, key).get();
        GalacticCoord local = GalacticCoord.ofSectorLocal(500_000L, 0L, 0L, 0L, 0L, 0L);

        GalacticCoord resolved =
                GalacticAnchor.of(key, local).resolve(f.declarationOriginOf(seed, key));
        assertEquals(centre.sectorX() + 500_000L, resolved.sectorX());
        assertTrue("and it must land inside the galaxy it named",
                f.galaxyAtIndex(seed, key.gx(), key.gy(), key.gz()).get()
                        .containsSector(resolved.sectorX(), resolved.sectorY(), resolved.sectorZ()));
    }

    @Test
    public void withNoGalaxyTierADeclarationIsAlreadyAbsolute() {
        // An authored-only universe has nothing for a declaration to be local TO, so local and
        // absolute coincide — which is both the only reading that can be right and the behaviour that
        // existed before galaxies did.
        GalacticCoord local = GalacticCoord.ofSectorLocal(42L, -7L, 3L, 0L, 0L, 0L);
        assertEquals(local.cellKey(),
                GalacticAnchor.inHome(local).resolve(Optional.<GalacticCoord>empty()).cellKey());
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
