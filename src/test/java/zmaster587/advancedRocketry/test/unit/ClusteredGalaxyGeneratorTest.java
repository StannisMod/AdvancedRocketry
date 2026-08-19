package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.BodyDerivationV0;
import zmaster587.advancedRocketry.universe.BodyProfile;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.Cosmology;
import zmaster587.advancedRocketry.universe.Galaxy;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.IBodyDerivation;
import zmaster587.advancedRocketry.universe.PlanetDerivation;
import zmaster587.advancedRocketry.universe.PlanetarySystem;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.UniverseScale;
import zmaster587.advancedRocketry.universe.UniverseSchemas;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Contract tests for the deterministic clustered galaxy generator. Pure-JUnit; no MC bootstrap.
 *
 * <p>Pins the generation CONTRACTS: pure determinism over {@code (seed, cell)}, the minimum-spacing
 * guarantee, the separation floor between two seats, that the star field is its GALAXY's density
 * profile (it thins outwards and stops at the declared radius), that {@code systemsInRegion} agrees
 * with {@code systemAt}, and that the tunable params drive the outcome. Balance numbers are exercised
 * as inputs, never pinned as expected values.</p>
 *
 * <p><b>Every sweep here sits near the ORIGIN</b>, which is the home galaxy's centre — the one place
 * guaranteed to be inside a galaxy under every seed. A sweep elsewhere would be sampling whatever the
 * seed happened to put there, which is a different claim. The galaxy lattice itself is
 * {@code GalaxyFieldTest}'s subject.</p>
 *
 * <p><b>Sampling is by SUPER-CELL, never by cell.</b> A star seat is one cell in a cube of tens of
 * millions, so sweeping cells finds nothing whatever the galaxy holds — and a spacing small enough to
 * sweep is a spacing with no room for a system in it, which is a different generator from the shipped
 * one. Every sweep here walks the partition the generator itself walks.</p>
 */
public class ClusteredGalaxyGeneratorTest {

    private static final long SEED = 0xC0FFEEL;

    private static GalacticCoord cell(long sx, long sy, long sz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, 0L, 0L, 0L);
    }

    /** The shipped spacing: what the sampled galaxy is is what the game ships. */
    private static final int SPACING = GalaxyGenConfig.DEFAULT_MIN_SPACING;

    /**
     * A config at the shipped galaxy lattice, varying only how full a galaxy's densest point is. Every
     * sweep in this class sits near the ORIGIN, which is the home galaxy's centre, so {@code density}
     * is the whole of what decides whether the sampled sky has stars in it.
     */
    private static GalaxyGenConfig cfg(double density, int spacing) {
        return new GalaxyGenConfig(spacing, density, GalaxyGenConfig.DEFAULT_GALAXY_SPACING,
                GalaxyGenConfig.DEFAULT_GALAXY_DENSITY, null, null);
    }

    private static GalaxyGenConfig defaultsCfg() {
        return cfg(0.35d, SPACING);
    }

    /** Iterate an inclusive box of SUPER-CELLS, calling the visitor with each one's probe cell. */
    private interface CellVisitor {
        void visit(GalacticCoord c);
    }

    private static void forEachSuperCell(long r, long spacing, CellVisitor v) {
        for (long x = -r; x <= r; x++) {
            for (long y = -r; y <= r; y++) {
                for (long z = -r; z <= r; z++) {
                    v.visit(cell(x * spacing, y * spacing, z * spacing));
                }
            }
        }
    }

    @Test
    public void systemAtIsDeterministic() {
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(defaultsCfg());
        forEachSuperCell(6, SPACING, probe -> {
            Optional<GalacticCoord> anchor = gen.anchorAt(SEED, probe);
            if (!anchor.isPresent()) {
                return;
            }
            Optional<PlanetarySystem> a = gen.systemAt(SEED, anchor.get());
            Optional<PlanetarySystem> b = gen.systemAt(SEED, anchor.get());
            assertTrue("an attributed anchor must point-resolve at " + anchor.get(), a.isPresent());
            assertEquals("presence must be stable", a.isPresent(), b.isPresent());
            assertEquals("id stable", a.get().systemId(), b.get().systemId());
            assertEquals("primary kind stable", a.get().primaryKind(), b.get().primaryKind());
            assertEquals("star presence stable", a.get().star().isPresent(), b.get().star().isPresent());
            if (!a.get().star().isPresent()) {
                return; // a starless system: it has no temperature or size to be stable
            }
            assertEquals("temperature stable", a.get().star().get().getTemperature(),
                    b.get().star().get().getTemperature());
            assertEquals("size stable", a.get().star().get().getSize(), b.get().star().get().getSize(), 0f);
        });
    }

    @Test
    public void onlyTheSeatCellItselfHoldsTheSystem() {
        // The anchor NAMES the system; its neighbours are ordinary space that merely attributes to it.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(defaultsCfg());
        int checked = 0;
        for (GalacticCoord anchor : anchors(gen, SEED, SPACING, 3)) {
            assertTrue(gen.systemAt(SEED, anchor).isPresent());
            assertFalse("a cell beside the seat must not itself be the system",
                    gen.systemAt(SEED, anchor.plusLocal(GalacticCoord.CELL, 0L, 0L)).isPresent());
            checked++;
        }
        assertTrue(checked > 5);
    }

    @Test
    public void differentSeedsProduceDifferentGalaxies() {
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(defaultsCfg());
        Set<String> occupiedA = occupiedSeats(gen, SEED, SPACING, 6);
        Set<String> occupiedB = occupiedSeats(gen, SEED + 1, SPACING, 6);
        assertFalse("a different seed must not reproduce the same galaxy", occupiedA.equals(occupiedB));
    }

    @Test
    public void minimumSpacingIsRespected() {
        // At most one system per minSpacing-cube super-cell, anywhere in the sampled volume.
        GalaxyGenConfig config = cfg(0.9d, SPACING); // dense, no void: stress spacing
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(config);
        Map<String, Integer> perSuperCell = new HashMap<>();
        for (GalacticCoord anchor : anchors(gen, SEED, SPACING, 4)) {
            long s = config.minSpacing;
            String superKey = Math.floorDiv(anchor.sectorX(), s) + "_"
                    + Math.floorDiv(anchor.sectorY(), s) + "_" + Math.floorDiv(anchor.sectorZ(), s);
            perSuperCell.merge(superKey, 1, Integer::sum);
        }
        assertFalse("the sweep must find systems", perSuperCell.isEmpty());
        for (Map.Entry<String, Integer> e : perSuperCell.entrySet()) {
            assertTrue("super-cell " + e.getKey() + " holds " + e.getValue() + " systems (max 1)",
                    e.getValue() <= 1);
        }
    }

    @Test
    public void noTwoStarsStandCloserThanTheSeparationFloor() {
        // The floor is what makes a near-pair of seats impossible, and it is what stops two unrelated
        // systems — two names, two frames, no gravitational relation — from being read as a binary.
        // Multiplicity is something a system states about itself, never something the lattice fakes.
        GalaxyGenConfig config = cfg(1.0d, SPACING); // every cube occupied: the tightest case
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(config);
        List<GalacticCoord> seats = anchors(gen, SEED, SPACING, 2);
        assertTrue("the sweep must find systems", seats.size() > 10);
        double floorBlocks = UniverseScale.SEPARATION_FLOOR_AU * AstronomicalBodyHelper.BLOCKS_PER_AU;
        for (int i = 0; i < seats.size(); i++) {
            for (int j = i + 1; j < seats.size(); j++) {
                double d = seats.get(i).staticFrameDistanceTo(seats.get(j));
                assertTrue("seats " + seats.get(i).cellKey() + " and " + seats.get(j).cellKey()
                        + " stand " + d + " blocks apart, inside the floor of " + floorBlocks,
                        d >= floorBlocks);
            }
        }
    }

    @Test
    public void aSeatIsNotConfinedToTheMiddleOfItsCube() {
        // The seat used to be pinned into the middle quarter per axis — 1.6 % of the cube's volume —
        // which reads as a lattice of tight clumps with guaranteed-empty walls. What replaces it is a
        // margin sized by what a system NEEDS, so most of the cube is reachable.
        GalaxyGenConfig config = cfg(1.0d, SPACING);
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(config);
        long s = config.minSpacing;
        double nearestFaceFraction = 1d;
        int checked = 0;
        for (GalacticCoord anchor : anchors(gen, SEED, SPACING, 2)) {
            long offset = Math.floorMod(anchor.sectorX(), s);
            nearestFaceFraction = Math.min(nearestFaceFraction, offset / (double) s);
            nearestFaceFraction = Math.min(nearestFaceFraction, (s - offset) / (double) s);
            checked++;
        }
        assertTrue(checked > 10);
        assertTrue("some seat must sit well outside the middle quarter, nearest face fraction was "
                + nearestFaceFraction, nearestFaceFraction < 0.25d);
    }

    @Test
    public void starFormationStopsAtTheGalaxysDeclaredEdge() {
        // The star field is the GALAXY's density profile, so where a galaxy ends, star FORMATION ends.
        // This is what an independent per-cell mask could not do: drawn above the percolation threshold
        // it produced one unbounded sponge, with no edge to reach and no answer to "which galaxy is
        // this".
        //
        // It is star FORMATION and not "anything at all", and the distinction is the whole of the void
        // content: what is out past the edge got there by being thrown, and the material that carries
        // it is the ejecta halo rather than the profile. So the reading is the BOUND term — what a
        // star needs to condense out of — and it is zero past the radius on the nose.
        //
        // Sampled against the home galaxy's OWN radius rather than a hard-coded distance: the radius
        // is drawn per seed, so a fixed number would be testing one draw.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(1.0d, SPACING));
        Galaxy home = gen.galaxies().home(SEED);

        assertTrue("the galaxy's core must hold stars (found " + seatsInBlockAround(gen, 0L, 3) + ")",
                seatsInBlockAround(gen, 0L, 3) > 0);
        assertTrue("inside the galaxy there must be material a star can form out of",
                gen.galaxies().materialAtSector(SEED, 0L, 0L, 0L).bound > 0d);

        long beyondEdge = UniverseScale.cellsForLightYears(home.radiusLy() * 1.5d);
        for (long d = 0; d <= 3; d++) {
            long sector = beyondEdge + d * SPACING;
            assertEquals("past the declared radius of " + (long) home.radiusLy()
                            + " ly nothing may FORM, at " + sector,
                    0d, gen.galaxies().materialAtSector(SEED, sector, 0L, 0L).bound, 0d);
        }
    }

    @Test
    public void systemsInRegionAgreesWithSystemAt() {
        // The single most important consistency contract: the region enumeration and the point query
        // must never diverge, or a telescope scan would show systems a jump can't reach (or vice versa).
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(defaultsCfg());
        // The sweep is one super-cell narrower than the box, because a seat sits at an offset INSIDE
        // its cube: the outermost swept cube's seat would fall outside a box cut at that cube's face.
        long r = 3L * SPACING;

        Set<String> byAttribution = new HashSet<>();
        forEachSuperCell(2, SPACING, probe -> {
            Optional<GalacticCoord> anchor = gen.anchorAt(SEED, probe);
            if (anchor.isPresent()) {
                byAttribution.add(anchor.get().cellKey());
            }
        });
        assertFalse("the sweep must find systems", byAttribution.isEmpty());

        Map<GalacticCoord, PlanetarySystem> region = gen.systemsInRegion(SEED, cell(-r, -r, -r), cell(r, r, r));
        Set<String> byRegion = new HashSet<>();
        for (Map.Entry<GalacticCoord, PlanetarySystem> e : region.entrySet()) {
            byRegion.add(e.getKey().cellKey());
            // The enumerated cell must itself point-resolve to the same system.
            Optional<PlanetarySystem> point = gen.systemAt(SEED, e.getKey());
            assertTrue("region cell " + e.getKey() + " must point-resolve", point.isPresent());
            assertEquals(point.get().systemId(), e.getValue().systemId());
        }
        assertTrue("every seat the sweep attributed must be enumerated by the region query",
                byRegion.containsAll(byAttribution));
    }

    @Test
    public void systemsInRegionHandlesSwappedBounds() {
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(defaultsCfg());
        long r = 2L * SPACING;
        Map<GalacticCoord, PlanetarySystem> ordered = gen.systemsInRegion(SEED, cell(-r, -r, -r), cell(r, r, r));
        Map<GalacticCoord, PlanetarySystem> swapped = gen.systemsInRegion(SEED, cell(r, r, r), cell(-r, -r, -r));
        assertEquals("swapped min/max must enumerate the same box", ordered.keySet(), swapped.keySet());
    }

    @Test
    public void aGalaxysProfileThinsTheStarFieldOutwards() {
        // The profile is not a mask with two states. A galaxy is densest at its nucleus and thins with
        // radius, so the same density knob has to place more stars near the centre than out at the rim
        // — that gradient is the whole difference between a galaxy and a uniform fog.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(1.0d, SPACING));
        Galaxy home = gen.galaxies().home(SEED);

        int core = seatsInBlockAround(gen, 0L, 4);
        int rim = seatsInBlockAround(gen, UniverseScale.cellsForLightYears(home.radiusLy() * 0.8d), 4);
        assertTrue("the core must be denser than the rim (" + core + " vs " + rim + ")", core > rim);
    }

    @Test
    public void densityDrivesOccupancy() {
        int sparse = occupiedSeats(new ClusteredGalaxyGenerator(cfg(0.1d, SPACING)),
                SEED, SPACING, 7).size();
        int dense = occupiedSeats(new ClusteredGalaxyGenerator(cfg(0.9d, SPACING)),
                SEED, SPACING, 7).size();
        assertTrue("higher density must place more systems (" + sparse + " vs " + dense + ")",
                dense > sparse);
    }

    @Test
    public void starTypesAreDrawnFromTheConfiguredSetAndWeighted() {
        // Two archetypes; the heavily-weighted one must dominate the sample.
        List<GalaxyGenConfig.StarType> types = new ArrayList<>();
        types.add(new GalaxyGenConfig.StarType(50, 0.5f, 1.0f, 100)); // common
        types.add(new GalaxyGenConfig.StarType(250, 2.0f, 3.0f, 1));  // rare
        GalaxyGenConfig config = new GalaxyGenConfig(SPACING, 0.9d,
                GalaxyGenConfig.DEFAULT_GALAXY_SPACING, GalaxyGenConfig.DEFAULT_GALAXY_DENSITY,
                types, null);
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(config);

        int common = 0;
        int rare = 0;
        int other = 0;
        int total = 0;
        Set<String> seenTemps = new HashSet<>();
        for (long x = -20; x <= 20; x++) {
            for (long y = -20; y <= 20; y++) {
                Optional<GalacticCoord> anchor = gen.anchorAt(SEED, cell(x * SPACING, y * SPACING, 0));
                if (!anchor.isPresent()) {
                    continue;
                }
                PlanetarySystem sys = gen.systemAt(SEED, anchor.get()).get();
                if (!sys.star().isPresent()) {
                    continue; // a starless system draws no star archetype, which is this test's subject
                }
                int temp = sys.star().get().getTemperature();
                seenTemps.add(Integer.toString(temp));
                total++;
                if (temp == 50) {
                    common++;
                } else if (temp == 250) {
                    rare++;
                } else {
                    other++;
                }
                // size must lie in the archetype's range
                float size = sys.star().get().getSize();
                if (temp == 50) {
                    assertTrue(size >= 0.5f && size <= 1.0f);
                } else if (temp == 250) {
                    assertTrue(size >= 2.0f && size <= 3.0f);
                }
            }
        }
        assertTrue("sample must contain systems", total > 20);
        assertEquals("every star temperature must come from the configured archetypes", 0, other);
        assertTrue("the weighted-100 archetype must dominate the weighted-1 one", common > rare);
        assertTrue("both archetypes should appear in a large sample", seenTemps.contains("50"));
    }

    @Test
    public void proceduralSystemIdsAreNegative() {
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(0.9d, SPACING));
        boolean sawAny = false;
        for (GalacticCoord anchor : anchors(gen, SEED, SPACING, 2)) {
            sawAny = true;
            assertTrue("procedural systems must carry a synthetic negative id",
                    gen.systemAt(SEED, anchor).get().systemId() < 0);
        }
        assertTrue(sawAny);
    }

    @Test
    public void configClampsAndDefaults() {
        GalaxyGenConfig c = new GalaxyGenConfig(-3, 5.0d, -7L, -1.0d, null, null);
        assertEquals("density clamps to [0,1]", 1.0d, c.density, 0d);
        assertEquals("galaxyDensity clamps to [0,1]", 0.0d, c.galaxyDensity, 0d);
        assertTrue("minSpacing floors at 1", c.minSpacing >= 1);
        assertTrue("galaxySpacing floors at 1", c.galaxySpacing >= 1L);
        assertFalse("empty star types fall back to defaults", c.starTypes.isEmpty());
        assertFalse("empty galaxy types fall back to defaults", c.galaxyTypes.isEmpty());
    }

    @Test
    public void configClampsNaNToZero() {
        // A NaN attribute (Double.parseDouble accepts "NaN") must not poison either occupancy gate.
        GalaxyGenConfig c = new GalaxyGenConfig(1, Double.NaN, 1L, Double.NaN, null, null);
        assertEquals("NaN density clamps to 0", 0.0d, c.density, 0d);
        assertEquals("NaN galaxyDensity clamps to 0", 0.0d, c.galaxyDensity, 0d);
    }

    @Test
    public void hugeStarWeightsDoNotCollapseTheDistribution() {
        // Two near-Integer.MAX weights must not overflow the weight sum into a collapsed "first type only".
        List<GalaxyGenConfig.StarType> types = new ArrayList<>();
        types.add(new GalaxyGenConfig.StarType(50, 0.5f, 1.0f, Integer.MAX_VALUE));
        types.add(new GalaxyGenConfig.StarType(250, 2.0f, 3.0f, Integer.MAX_VALUE));
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(
                new GalaxyGenConfig(SPACING, 0.9d, GalaxyGenConfig.DEFAULT_GALAXY_SPACING,
                        GalaxyGenConfig.DEFAULT_GALAXY_DENSITY, types, null));

        Set<String> seenTemps = new HashSet<>();
        for (long x = -20; x <= 20; x++) {
            for (long y = -20; y <= 20; y++) {
                Optional<GalacticCoord> anchor = gen.anchorAt(SEED, cell(x * SPACING, y * SPACING, 0));
                if (!anchor.isPresent()) {
                    continue;
                }
                PlanetarySystem sys = gen.systemAt(SEED, anchor.get()).get();
                if (sys.star().isPresent()) {
                    seenTemps.add(Integer.toString(sys.star().get().getTemperature()));
                }
            }
        }
        assertTrue("both equally-weighted archetypes must appear (weights summed in long)",
                seenTemps.contains("50") && seenTemps.contains("250"));
    }

    @Test
    public void proceduralBodiesGetTheirOwnCellsInsideTheSuperCell() {
        // A system is an anchored NEIGHBOURHOOD — the star holds the anchor cell, each planet/belt its
        // own cell (snapped to that cell's centre), all inside the anchor's minSpacing super-cell.
        GalaxyGenConfig config = cfg(0.9d, SPACING);
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(config);
        long s = config.minSpacing;
        boolean checkedAny = false;
        for (GalacticCoord anchor : anchors(gen, SEED, SPACING, 1)) {
            if (!gen.systemAt(SEED, anchor).get().star().isPresent()) {
                continue; // a starless system has no star at its anchor, which is what this pins
            }
            checkedAny = true;
            List<SystemBody> a = gen.bodiesFor(SEED, anchor);
            assertEquals("bodiesFor must be deterministic", a, gen.bodiesFor(SEED, anchor));
            assertEquals("bodiesFor must accept a member cell and answer for the whole system",
                    a, gen.bodiesFor(SEED, anchor.plusLocal(GalacticCoord.CELL, 0L, 0L)));
            assertFalse("an occupied system must have bodies", a.isEmpty());

            assertEquals("first body is the star at the anchor", SystemBodyKind.STAR, a.get(0).kind());
            assertTrue(a.get(0).name().sameCell(anchor));
            assertEquals(0, a.get(0).name().localX());

            // Every body names a star OF THIS SYSTEM — the primary, or one of its companions, which
            // are stars in their own right with ids of their own.
            Set<Integer> systemStars = new HashSet<>();
            systemStars.add(a.get(0).starId());
            for (zmaster587.advancedRocketry.api.dimension.solar.StellarBody companion
                    : gen.systemAt(SEED, anchor).get().star().get().getSubStars()) {
                systemStars.add(companion.getId());
            }

            boolean sawOwnCell = false;
            for (SystemBody body : a) {
                assertTrue("body names star " + body.starId() + ", which is not one of this system's",
                        systemStars.contains(body.starId()));
                assertFalse("procedural bodies are not descend targets yet", body.isDescendTarget());
                // Snapped to its own cell's centre.
                assertEquals(0, body.name().localX());
                assertEquals(0, body.name().localY());
                assertEquals(0, body.name().localZ());
                // Inside the anchor's super-cell (member attribution by floorDiv stays exact).
                assertEquals(Math.floorDiv(anchor.sectorX(), s), Math.floorDiv(body.name().sectorX(), s));
                assertEquals(Math.floorDiv(anchor.sectorY(), s), Math.floorDiv(body.name().sectorY(), s));
                assertEquals(Math.floorDiv(anchor.sectorZ(), s), Math.floorDiv(body.name().sectorZ(), s));
                if (body.kind() != SystemBodyKind.STAR && !body.name().sameCell(anchor)) {
                    sawOwnCell = true;
                }
            }
            assertTrue("planets/belts must sit in their OWN cells, not the anchor's", sawOwnCell);
        }
        assertTrue(checkedAny);
    }

    @Test
    public void aBodyStandsExactlyWhereItsOrbitalDistanceSaysItDoes() {
        // The acceptance the whole scale rework exists for: ONE law, ONE constant. A body at orbital
        // distance d is d units from its star, in blocks, and its cell NAME is a reading of that same
        // position rather than a second layout arithmetic beside it. When those two came apart, the
        // science said one thing and the flight time said another.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(0.9d, SPACING));
        int checked = 0;
        for (GalacticCoord anchor : anchors(gen, SEED, SPACING, 1)) {
            List<SystemBody> bodies = gen.bodiesFor(SEED, anchor);
            SystemBody star = bodies.get(0);
            for (SystemBody body : bodies) {
                if (body.kind() == SystemBodyKind.STAR || body.kind() == SystemBodyKind.MOON
                        || body.kind() == SystemBodyKind.ASTEROID_BELT) {
                    continue;
                }
                double expected = (double) body.orbitalDistance()
                        * AstronomicalBodyHelper.BLOCKS_PER_ORBIT_UNIT;
                double placed = body.absoluteAt(0L).distanceTo(star.absoluteAt(0L));
                assertEquals("body at orbit " + body.orbitalDistance() + " of system "
                                + anchor.cellKey() + " must stand that far from its star",
                        expected, placed, expected * 1e-6d + 2d);
                // And the cell it is NAMED by is a reading of that same place, to within a cell.
                double named = body.name().staticFrameDistanceTo(anchor);
                assertTrue("the body's cell name (" + named + " blocks out) must agree with where it "
                                + "is (" + placed + ")",
                        Math.abs(named - placed) <= 2d * GalacticCoord.CELL);
                checked++;
            }
        }
        assertTrue("the sweep must find bodies", checked > 10);
    }

    @Test
    public void aSystemNeverReachesPastItsOwnClearSpace() {
        // The bound that replaces "a system is a fraction of the distance to the next star": named
        // bodies stay inside half the separation floor, whatever a star's own zone would have drawn.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(1.0d, SPACING));
        int checked = 0;
        for (GalacticCoord anchor : anchors(gen, SEED, SPACING, 1)) {
            for (SystemBody body : gen.bodiesFor(SEED, anchor)) {
                assertTrue("body at orbit " + body.orbitalDistance() + " reaches past its system's "
                                + "clear space of " + UniverseScale.MAX_NAMED_ORBIT_UNITS + " units",
                        body.orbitalDistance() <= UniverseScale.MAX_NAMED_ORBIT_UNITS);
                checked++;
            }
        }
        assertTrue(checked > 10);
    }

    @Test
    public void tinySpacingDegeneratesIntoALoneStar() {
        // minSpacing=1: the super-cell IS one cell, and the star already holds it. A second real body
        // would have to share that cell, which at most one real body per cell forbids — so the system
        // degenerates to its star alone. Degenerate but CONSISTENT: attribution stays exact, nothing
        // escapes the box, and no cell ends up with two destinations in it.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(0.9d, 1));
        boolean checkedAny = false;
        for (long x = -6; x <= 6; x++) {
            GalacticCoord c = cell(x, 0, 0);
            if (!gen.systemAt(SEED, c).isPresent()) {
                assertTrue("a void cell yields no bodies", gen.bodiesFor(SEED, c).isEmpty());
                continue;
            }
            checkedAny = true;
            List<SystemBody> bodies = gen.bodiesFor(SEED, c);
            int real = 0;
            for (SystemBody body : bodies) {
                assertTrue("nothing may escape the one cell this system has",
                        body.name().sameCell(c));
                if (body.definesFrame()) {
                    real++;
                }
            }
            assertEquals("a one-cell neighbourhood can host exactly one real body", 1, real);
            assertTrue("and that body is the system's primary",
                    bodies.get(0).definesFrame() && bodies.get(0).name().sameCell(c));
        }
        assertTrue(checkedAny);
    }

    @Test
    public void anchorAtAttributesEveryCellOfAnOccupiedSuperCell() {
        GalaxyGenConfig config = cfg(0.9d, SPACING);
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(config);
        long s = config.minSpacing;
        boolean checkedAny = false;
        for (long sup = -2; sup <= 2; sup++) {
            Optional<GalacticCoord> anchor = gen.anchorAt(SEED, cell(sup * s, 0, 0));
            if (!anchor.isPresent()) {
                continue;
            }
            checkedAny = true;
            // Every cell of the super-cell attributes to the SAME anchor (corners included).
            for (long dx : new long[] {0, s - 1}) {
                for (long dy : new long[] {0, s - 1}) {
                    GalacticCoord member = cell(sup * s + dx, dy, 0);
                    assertEquals("member " + member + " must attribute to the super-cell's anchor",
                            anchor, gen.anchorAt(SEED, member));
                }
            }
            // The anchor itself point-resolves to the system.
            assertTrue(gen.systemAt(SEED, anchor.get()).isPresent());
        }
        assertTrue(checkedAny);
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    /** Every distinct seat in a sweep of super-cells. */
    private static List<GalacticCoord> anchors(ClusteredGalaxyGenerator gen, long seed, long spacing,
                                               long r) {
        Set<String> seen = new HashSet<>();
        List<GalacticCoord> out = new ArrayList<>();
        forEachSuperCell(r, spacing, probe -> {
            Optional<GalacticCoord> a = gen.anchorAt(seed, probe);
            if (a.isPresent() && seen.add(a.get().cellKey())) {
                out.add(a.get());
            }
        });
        return out;
    }

    /**
     * How many seats a {@code (2r+1)³} block of super-cells holds, centred {@code offsetCells} out
     * along +X from the origin — the origin being the home galaxy's centre. Sampling a BLOCK rather
     * than a single super-cell is what makes the count a reading of the density there instead of one
     * coin toss.
     */
    /**
     * How many STAR seats a block of super-cells holds — never how many seats of any kind.
     *
     * <p>The difference is load-bearing at the shipped tuning. Free-floating worlds are drawn on the
     * same lattice at a MEASURED twenty-one per star, which saturates it: past {@code 1/density} every
     * cube the star draw passed over holds something, so a count of occupied seats is the constant
     * "all of them" and discriminates neither the density nor the galaxy profile. Both of the tests
     * below exist to show that those two DO drive the star field, so both must count stars.</p>
     */
    private static int seatsInBlockAround(ClusteredGalaxyGenerator gen, long offsetCells, long r) {
        Set<String> seen = new HashSet<>();
        for (long x = -r; x <= r; x++) {
            for (long y = -r; y <= r; y++) {
                for (long z = -r; z <= r; z++) {
                    Optional<GalacticCoord> a = gen.anchorAt(SEED,
                            cell(offsetCells + x * SPACING, y * SPACING, z * SPACING));
                    if (a.isPresent() && gen.systemAt(SEED, a.get()).get().star().isPresent()) {
                        seen.add(a.get().cellKey());
                    }
                }
            }
        }
        return seen.size();
    }

    /**
     * The STAR seats of a sweep, by cell key — see {@link #seatsInBlockAround} for why it is stars and
     * not seats of any kind: the unbound draw saturates the lattice at the shipped tuning, so a count
     * of everything is the constant "every cube" and measures nothing.
     */
    private static Set<String> occupiedSeats(ClusteredGalaxyGenerator gen, long seed, long spacing,
                                             long r) {
        Set<String> keys = new HashSet<>();
        for (GalacticCoord a : anchors(gen, seed, spacing, r)) {
            if (gen.systemAt(seed, a).get().star().isPresent()) {
                keys.add(a.cellKey());
            }
        }
        return keys;
    }

    // ─── the retinue an AUTHORED system gets: one generator, never two ─────────

    private static zmaster587.advancedRocketry.api.dimension.solar.StellarBody authoredStar() {
        zmaster587.advancedRocketry.api.dimension.solar.StellarBody star =
                new zmaster587.advancedRocketry.api.dimension.solar.StellarBody();
        star.setName("Authored");
        star.setId(0);
        star.setSize(1f);
        star.setTemperature(100);
        return star;
    }

    @Test
    public void anAuthoredSystemsDerivedRetinueIsTheSameEverySave() {
        // The whole reason the legacy generator had to go: it drew from
        // new Random(System.currentTimeMillis()), so two saves of one seed held different worlds and
        // nothing about a system could be predicted, reproduced or reported.
        ClusteredGalaxyGenerator g = new ClusteredGalaxyGenerator(defaultsCfg());
        GalacticCoord anchor = cell(0, 0, 0);

        List<SystemBody> first = g.authoredRetinueFor(SEED, anchor, authoredStar(), 0, 6,
                java.util.Collections.<String>emptySet());
        List<SystemBody> again = g.authoredRetinueFor(SEED, anchor, authoredStar(), 0, 6,
                java.util.Collections.<String>emptySet());

        assertEquals("the same seed must produce the same system, body for body", first, again);
        assertTrue("...and it must actually produce one", first.size() > 1);

        List<SystemBody> otherSeed = g.authoredRetinueFor(SEED + 1L, anchor, authoredStar(), 0, 6,
                java.util.Collections.<String>emptySet());
        assertNotEquals("a different seed must produce a different system, or the derivation ignores"
                + " its seed and determinism is vacuous", first, otherSeed);
    }

    @Test
    public void aPacksBodyCountBoundsWhatItsStarGets() {
        // The pack-facing knob the legacy generator consumed: a pack that asks for more worlds gets
        // more of them. Stated as a bound rather than an equality, because the drawn orbits still
        // decide how many FIT — a system squeezed by its neighbours holds fewer worlds rather than
        // the same worlds at the wrong distances.
        ClusteredGalaxyGenerator g = new ClusteredGalaxyGenerator(defaultsCfg());
        GalacticCoord anchor = cell(0, 0, 0);

        int few = majorBodies(g.authoredRetinueFor(SEED, anchor, authoredStar(), 0, 2,
                java.util.Collections.<String>emptySet()));
        int many = majorBodies(g.authoredRetinueFor(SEED, anchor, authoredStar(), 0, 10,
                java.util.Collections.<String>emptySet()));

        assertTrue("asking for two must not hand out more than two worlds, got " + few, few <= 2);
        assertTrue("asking for ten must hand out more than asking for two (" + few + " -> " + many
                + ")", many > few);
    }

    @Test
    public void anAuthoredWorldsCellIsNeverTakenByADerivedOne() {
        // The authored system wins: a pack's own world may not be displaced, or shadowed, by a body
        // the generator drew.
        ClusteredGalaxyGenerator g = new ClusteredGalaxyGenerator(defaultsCfg());
        GalacticCoord anchor = cell(0, 0, 0);
        List<SystemBody> free = g.authoredRetinueFor(SEED, anchor, authoredStar(), 0, 6,
                java.util.Collections.<String>emptySet());
        assertTrue("arrangement: the free draw must place something to reserve", free.size() > 1);

        java.util.Set<String> reserved = new java.util.HashSet<>();
        for (SystemBody b : free) {
            reserved.add(b.name().cellKey());
        }
        for (SystemBody b : g.authoredRetinueFor(SEED, anchor, authoredStar(), 0, 6, reserved)) {
            assertFalse("a derived body landed on a cell the authored system holds: "
                    + b.name().cellKey(), reserved.contains(b.name().cellKey()));
        }
    }

    private static int majorBodies(List<SystemBody> bodies) {
        int n = 0;
        for (SystemBody b : bodies) {
            if (b.kind() == SystemBodyKind.PLANET || b.kind() == SystemBodyKind.GAS_GIANT) {
                n++;
            }
        }
        return n;
    }

    // ── the derivation is part of the world model ─────────────────────────────

    /** A derivation that differs from version 1 in one law, and delegates the rest. */
    private static final class ShiftedDerivation implements IBodyDerivation {
        private final IBodyDerivation base = BodyDerivationV0.INSTANCE;

        @Override
        public double metallicityOf(long seed, GalacticCoord anchor) {
            return base.metallicityOf(seed, anchor);
        }

        @Override
        public int referenceDistance(zmaster587.advancedRocketry.api.dimension.solar.StellarBody star) {
            return base.referenceDistance(star);
        }

        @Override
        public int orbitalDistanceOf(long seed, GalacticCoord anchor, int index, int count,
                                     zmaster587.advancedRocketry.api.dimension.solar.StellarBody star) {
            return base.orbitalDistanceOf(seed, anchor, index, count, star) + 7;
        }

        @Override
        public double innerOrbit(zmaster587.advancedRocketry.api.dimension.solar.StellarBody star) {
            return base.innerOrbit(star);
        }

        @Override
        public double outerOrbit(zmaster587.advancedRocketry.api.dimension.solar.StellarBody star) {
            return base.outerOrbit(star);
        }

        @Override
        public int bareTemperature(zmaster587.advancedRocketry.api.dimension.solar.StellarBody star,
                                   int orbitalDistance) {
            return base.bareTemperature(star, orbitalDistance);
        }

        @Override
        public boolean tidallyLockedAt(zmaster587.advancedRocketry.api.dimension.solar.StellarBody star,
                                       int orbitalDistance) {
            return base.tidallyLockedAt(star, orbitalDistance);
        }

        @Override
        public boolean isGiantAt(long seed, GalacticCoord anchor, int index, int bareTemperatureK) {
            return base.isGiantAt(seed, anchor, index, bareTemperatureK);
        }

        @Override
        public BodyProfile derive(long seed, GalacticCoord anchor, GalacticCoord bodyCell, int variant,
                                  zmaster587.advancedRocketry.api.dimension.solar.StellarBody star,
                                  boolean moon, int orbitalDistance) {
            return base.derive(seed, anchor, bodyCell, variant, star, moon, orbitalDistance);
        }

        @Override
        public BodyProfile deriveRogue(long seed, GalacticCoord bodyCell, int variant,
                                       double giantFraction) {
            return base.deriveRogue(seed, bodyCell, variant, giantFraction);
        }

        @Override
        public int residualTemperature(double massEarths, double radiusEarths) {
            return base.residualTemperature(massEarths, radiusEarths);
        }
    }

    @Test
    public void aGeneratorDerivesItsBodiesThroughTheDerivationItWasGiven() {
        // The point of the seam: a later schema can change what a body IS while the placement stands.
        // If this passes with an unused parameter somewhere, the seam is decoration.
        GalaxyGenConfig config = defaultsCfg();
        ClusteredGalaxyGenerator stock = new ClusteredGalaxyGenerator(config);
        ClusteredGalaxyGenerator shifted = new ClusteredGalaxyGenerator(config, new ShiftedDerivation());

        GalacticCoord anchor = null;
        for (int i = 0; i < 64 && anchor == null; i++) {
            GalacticCoord probe = GalacticCoord.ofSectorLocal((long) i * config.minSpacing, 0, 0, 0, 0, 0);
            java.util.Optional<GalacticCoord> found = stock.anchorAt(SEED, probe);
            if (found.isPresent() && !stock.bodiesFor(SEED, found.get()).isEmpty()) {
                anchor = found.get();
            }
        }
        assertTrue("arrangement: a system with bodies must be found near the origin", anchor != null);

        List<SystemBody> stockBodies = stock.bodiesFor(SEED, anchor);
        List<SystemBody> shiftedBodies = shifted.bodiesFor(SEED, anchor);

        // The retinue does not merely change VALUES, it changes SHAPE — a body's cell follows its
        // orbital distance, so moving the orbit law moves which seats are claimed and how many fit.
        // That is the strongest form of the claim being made here: the derivation is not a decoration
        // on top of a fixed layout, it is part of what the world model IS, and it therefore has to
        // travel with the schema version rather than with the jar.
        assertNotEquals("a generator handed a different derivation must produce a different system — "
                        + "otherwise the derivation is not reachable from the schema at all",
                describe(stockBodies), describe(shiftedBodies));
    }

    /** A system as a comparable string: every body's cell, kind and orbit, in a stable order. */
    private static String describe(List<SystemBody> bodies) {
        List<String> lines = new ArrayList<>();
        for (SystemBody b : bodies) {
            lines.add(b.name().cellKey() + ':' + b.kind() + ':' + b.orbitalDistance());
        }
        Collections.sort(lines);
        return lines.toString();
    }

    @Test
    public void aGeneratorHandsOutTheDerivationItUses() {
        // How everything outside this package reaches the world's derivation. Asking the class directly
        // would pin version 1 forever, whatever schema the save is owed.
        IBodyDerivation mine = new ShiftedDerivation();

        assertSame("a generator must hand out the derivation it was built with",
                mine, new ClusteredGalaxyGenerator(defaultsCfg(), mine).derivation());
        assertSame("and the stock one hands out version 1's", BodyDerivationV0.INSTANCE,
                new ClusteredGalaxyGenerator(defaultsCfg()).derivation());
    }

    // ── the golden corpus ─────────────────────────────────────────────────────

    /**
     * The released world model, rendered and compared byte for byte against a checked-in fixture.
     *
     * <p><b>This is not a regression test, it is a VERSION DECISION.</b> A save keeps what has been
     * touched and re-derives everything else, so any change to what this renders moves systems in worlds
     * that already exist. The fixture is what makes that visible before it ships:</p>
     *
     * <ul>
     *   <li><b>No diff</b> — the world model is unchanged; the release is a minor one and existing saves
     *       carry on under the same schema version.</li>
     *   <li><b>A diff, on a version that has REACHED A RELEASE</b> — the world model has moved under
     *       worlds that exist, so the change needs a NEW schema version registered in
     *       {@code UniverseSchemas}, and this fixture is regenerated alongside it. Not a discussion:
     *       a diff here IS the definition of a different universe.</li>
     *   <li><b>A diff, on a version that has not shipped yet</b> — the version is edited IN PLACE and
     *       the fixture regenerated with it. A model nobody outside the branch has ever generated a
     *       world under owes nobody compatibility, and minting a version for it would fill the registry
     *       with universes that never existed. <b>"Shipped" means merged to the release branch, not
     *       landed on a feature branch.</b></li>
     * </ul>
     *
     * <p>Regenerate deliberately, never to make a red test green:
     * {@code ./gradlew testUnit -Dadvancedrocketry.universe.corpus.write=true}</p>
     */
    @Test
    public void theGoldenCorpusIsByteIdentical() throws Exception {
        byte[] rendered = UniverseCorpus.render().getBytes(StandardCharsets.UTF_8);

        if (Boolean.getBoolean("advancedrocketry.universe.corpus.write")) {
            File out = new File(UniverseCorpus.FIXTURE_PATH);
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
            byte[] tmp = rendered;
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(tmp);
            }
            fail("corpus rewritten to " + out.getPath() + " (" + tmp.length + " bytes). This is a "
                    + "DELIBERATE act: if the content changed, the world model changed, and the release "
                    + "needs a new universe schema version. Re-run without the write flag.");
        }

        byte[] expected;
        try (InputStream in = getClass().getResourceAsStream(UniverseCorpus.FIXTURE_RESOURCE)) {
            assertNotNull("the golden corpus fixture is missing from the test resources: "
                    + UniverseCorpus.FIXTURE_RESOURCE, in);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buf.write(chunk, 0, read);
            }
            expected = buf.toByteArray();
        }

        if (!Arrays.equals(expected, rendered)) {
            fail("THE WORLD MODEL HAS MOVED. " + firstDifference(
                    new String(expected, StandardCharsets.UTF_8),
                    new String(rendered, StandardCharsets.UTF_8))
                    + "\nEvery system nobody has visited moves with it, in every save generated under "
                    + "this version. If the change is NOT intended, this is the bug. If it is: a version "
                    + "that has already reached a release needs a NEW schema version in UniverseSchemas "
                    + "beside it, while a version that has not shipped yet is edited in place — it owes "
                    + "nobody compatibility. Either way the fixture is regenerated deliberately, with "
                    + "-Dadvancedrocketry.universe.corpus.write=true.");
        }
    }

    /** The first line that differs, quoted — a byte offset alone says nothing about what moved. */
    private static String firstDifference(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        for (int i = 0; i < Math.max(e.length, a.length); i++) {
            String le = i < e.length ? e[i] : "<end of fixture>";
            String la = i < a.length ? a[i] : "<end of rendering>";
            if (!le.equals(la)) {
                return "line " + (i + 1) + ":\n  fixture: " + le + "\n  now:     " + la;
            }
        }
        return "the two differ in length only (" + expected.length() + " vs " + actual.length() + ")";
    }

    /**
     * Renders the observable universe of a fixed set of seeds over a fixed region — the whole schema,
     * not the generator alone.
     *
     * <p>Four members, and each is sampled where a change to it would show:</p>
     * <ul>
     *   <li>{@code IGalaxyGenerator} — which territories hold a system, its identity, and the cells its
     *       bodies stand in;</li>
     *   <li>{@code PlanetDerivation} — a profile derived at each body's real inputs. Deliberately a
     *       SAMPLE at a fixed variant rather than a claim about what the generator built internally:
     *       its purpose is to be a canary on the derivation, and a canary that reproduced the
     *       generator's private choices would be pinning implementation instead;</li>
     *   <li>{@code UniverseScale} — the metric constants and both conversions, because a light year
     *       that becomes a different number of cells relocates everything at once;</li>
     *   <li>{@code Cosmology} — the expansion factor at fixed ticks.</li>
     * </ul>
     *
     * <p>Rendering rules: LF only, every list sorted, doubles through {@link Double#toString} (exact and
     * locale-free — a formatted number would hide a change in its last digits and change with a locale).</p>
     */
    static final class UniverseCorpus {

        static final String FIXTURE_RESOURCE = "/universe/golden-corpus-v1.txt";
        static final String FIXTURE_PATH = "src/test/resources/universe/golden-corpus-v1.txt";

        /** Fixed seeds. Arbitrary, and that is the point — they are frozen, not chosen for an outcome. */
        private static final long[] SEEDS = {
                1L, 42L, 1337L, 8675309L, -1L, 6_942_069L, 2_147_483_647L,
        };

        /** Territories swept per axis, centred on the origin — the home galaxy's centre. */
        private static final int SPAN = 1;

        private UniverseCorpus() {
        }

        static String render() {
            GalaxyGenConfig config = GalaxyGenConfig.defaults();
            StringBuilder sb = new StringBuilder(64 * 1024);
            sb.append("# universe golden corpus - schema ").append(UniverseSchemas.CURRENT).append('\n');
            sb.append("config ").append(config.fingerprint()).append('\n');
            renderScale(sb);
            renderCosmology(sb);
            for (long seed : SEEDS) {
                renderSeed(sb, config, seed);
            }
            return sb.toString();
        }

        private static void renderScale(StringBuilder sb) {
            sb.append("scale spacingCells=").append(UniverseScale.DEFAULT_SPACING_CELLS)
                    .append(" galaxySpacingCells=").append(UniverseScale.DEFAULT_GALAXY_SPACING_CELLS)
                    .append(" seatMarginCells=").append(UniverseScale.SEAT_MARGIN_CELLS).append('\n');
            double[] lightYears = {0.1d, 1d, 4.23d, 100d, 50_000d};
            for (double ly : lightYears) {
                long cells = UniverseScale.cellsForLightYears(ly);
                sb.append("scale ly=").append(Double.toString(ly))
                        .append(" cells=").append(cells)
                        .append(" backLy=").append(Double.toString(UniverseScale.lightYearsForCells(cells)))
                        .append('\n');
            }
        }

        private static void renderCosmology(StringBuilder sb) {
            long[] ticks = {0L, 24_000L, 24_000_000L};
            for (long tick : ticks) {
                sb.append("cosmology tick=").append(tick)
                        .append(" scaleFactor=").append(Double.toString(Cosmology.scaleFactorAt(tick)))
                        .append('\n');
            }
        }

        private static void renderSeed(StringBuilder sb, GalaxyGenConfig config, long seed) {
            ClusteredGalaxyGenerator g = new ClusteredGalaxyGenerator(config);
            long step = config.minSpacing;
            Set<String> seen = new HashSet<>();
            List<String> lines = new ArrayList<>();
            for (int i = -SPAN; i <= SPAN; i++) {
                for (int j = -SPAN; j <= SPAN; j++) {
                    for (int k = -SPAN; k <= SPAN; k++) {
                        GalacticCoord probe = GalacticCoord.ofSectorLocal(i * step, j * step, k * step,
                                0L, 0L, 0L);
                        Optional<GalacticCoord> anchor = g.anchorAt(seed, probe);
                        if (!anchor.isPresent() || !seen.add(anchor.get().cellKey())) {
                            continue;
                        }
                        renderSystem(lines, g, seed, anchor.get());
                    }
                }
            }
            Collections.sort(lines);
            sb.append("seed ").append(seed).append(" systems=").append(seen.size()).append('\n');
            for (String line : lines) {
                sb.append(line).append('\n');
            }
        }

        private static void renderSystem(List<String> out, ClusteredGalaxyGenerator g, long seed,
                                         GalacticCoord anchor) {
            Optional<PlanetarySystem> systemOpt = g.systemAt(seed, anchor);
            if (!systemOpt.isPresent()) {
                return;
            }
            PlanetarySystem system = systemOpt.get();
            StringBuilder head = new StringBuilder();
            head.append("  system ").append(anchor.cellKey())
                    .append(" id=").append(system.systemId())
                    .append(" kind=").append(system.primaryKind())
                    .append(" name=").append(system.name());
            if (system.star().isPresent()) {
                head.append(" starTemp=").append(system.star().get().getTemperature())
                        .append(" starSize=").append(Double.toString(system.star().get().getSize()));
            } else {
                head.append(" starless");
            }
            out.add(head.toString());

            List<SystemBody> bodies = new ArrayList<>(g.bodiesFor(seed, anchor));
            List<String> bodyLines = new ArrayList<>();
            // One derivation sample per distinct CELL, not per body: a moon stands in its parent's
            // cell, so a per-body sample would render every profile twice and cover nothing extra.
            Map<String, SystemBody> byCell = new java.util.TreeMap<>();
            for (SystemBody body : bodies) {
                bodyLines.add(renderBody(anchor, body));
                String key = body.name().cellKey();
                if (!byCell.containsKey(key)) {
                    byCell.put(key, body);
                }
            }
            Collections.sort(bodyLines);
            out.addAll(bodyLines);
            for (Map.Entry<String, SystemBody> e : byCell.entrySet()) {
                out.add(renderDerivation(g, seed, anchor, system, e.getValue()));
            }
        }

        private static String renderBody(GalacticCoord anchor, SystemBody body) {
            return "  body " + anchor.cellKey() + ' ' + body.name().cellKey()
                    + " kind=" + body.kind()
                    + " orbit=" + body.orbitalDistance()
                    + " radius=" + Double.toString(body.radiusEarths())
                    + " starId=" + body.starId()
                    + " frame=" + body.definesFrame();
        }

        private static String renderDerivation(ClusteredGalaxyGenerator g, long seed,
                                               GalacticCoord anchor, PlanetarySystem system,
                                               SystemBody body) {
            BodyProfile profile = system.star().isPresent()
                    ? PlanetDerivation.derive(seed, anchor, body.name(), 0, system.star().get(), false,
                            body.orbitalDistance())
                    : PlanetDerivation.deriveRogue(seed, body.name(), 0,
                            g.config().rogue.giantFraction);
            return "  derived " + anchor.cellKey() + ' ' + body.name().cellKey()
                    + " type=" + profile.typeName()
                    + " mass=" + Double.toString(profile.massEarths())
                    + " radius=" + Double.toString(profile.radiusEarths())
                    + " gravity=" + profile.gravityPercent()
                    + " pressure=" + profile.pressure()
                    + " tempK=" + profile.temperatureKelvin()
                    + " oxygen=" + profile.hasOxygen()
                    + " locked=" + profile.tidallyLocked()
                    + " rings=" + profile.hasRings()
                    + " rotation=" + profile.rotationalPeriodTicks()
                    + " metallicity=" + Double.toString(profile.metallicity())
                    + " terrain=" + profile.terrain();
        }
    }
}
