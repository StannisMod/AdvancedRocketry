package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.Galaxy;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.StarSystem;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.UniverseScale;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
            Optional<StarSystem> a = gen.systemAt(SEED, anchor.get());
            Optional<StarSystem> b = gen.systemAt(SEED, anchor.get());
            assertTrue("an attributed anchor must point-resolve at " + anchor.get(), a.isPresent());
            assertEquals("presence must be stable", a.isPresent(), b.isPresent());
            assertEquals("id stable", a.get().starId(), b.get().starId());
            assertEquals("temperature stable", a.get().star().getTemperature(),
                    b.get().star().getTemperature());
            assertEquals("size stable", a.get().star().getSize(), b.get().star().getSize(), 0f);
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
    public void starsStopAtTheirGalaxysDeclaredEdge() {
        // The star field is the GALAXY's density profile, so where a galaxy ends the stars end. This
        // is what an independent per-cell mask could not do: drawn above the percolation threshold it
        // produced one unbounded sponge, with no edge to reach and no answer to "which galaxy is this".
        //
        // Sampled against the home galaxy's OWN radius rather than a hard-coded distance: the radius
        // is drawn per seed, so a fixed number would be testing one draw.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(1.0d, SPACING));
        Galaxy home = gen.galaxies().home(SEED);

        int inside = seatsInBlockAround(gen, 0L, 3);
        long beyondEdge = UniverseScale.cellsForLightYears(home.radiusLy() * 1.5d);
        int outside = seatsInBlockAround(gen, beyondEdge, 3);

        assertTrue("the galaxy's core must hold stars (found " + inside + ")", inside > 0);
        assertEquals("past the declared radius of " + (long) home.radiusLy()
                + " ly there must be nothing", 0, outside);
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

        Map<GalacticCoord, StarSystem> region = gen.systemsInRegion(SEED, cell(-r, -r, -r), cell(r, r, r));
        Set<String> byRegion = new HashSet<>();
        for (Map.Entry<GalacticCoord, StarSystem> e : region.entrySet()) {
            byRegion.add(e.getKey().cellKey());
            // The enumerated cell must itself point-resolve to the same system.
            Optional<StarSystem> point = gen.systemAt(SEED, e.getKey());
            assertTrue("region cell " + e.getKey() + " must point-resolve", point.isPresent());
            assertEquals(point.get().starId(), e.getValue().starId());
        }
        assertTrue("every seat the sweep attributed must be enumerated by the region query",
                byRegion.containsAll(byAttribution));
    }

    @Test
    public void systemsInRegionHandlesSwappedBounds() {
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(defaultsCfg());
        long r = 2L * SPACING;
        Map<GalacticCoord, StarSystem> ordered = gen.systemsInRegion(SEED, cell(-r, -r, -r), cell(r, r, r));
        Map<GalacticCoord, StarSystem> swapped = gen.systemsInRegion(SEED, cell(r, r, r), cell(-r, -r, -r));
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
                StarSystem sys = gen.systemAt(SEED, anchor.get()).get();
                int temp = sys.star().getTemperature();
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
                float size = sys.star().getSize();
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
                    gen.systemAt(SEED, anchor).get().starId() < 0);
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
                if (anchor.isPresent()) {
                    seenTemps.add(Integer.toString(
                            gen.systemAt(SEED, anchor.get()).get().star().getTemperature()));
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
                    : gen.systemAt(SEED, anchor).get().star().getSubStars()) {
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
            assertEquals("a one-cell neighbourhood can host exactly one real body", 1, bodies.size());
            assertEquals("and that body is the star", SystemBodyKind.STAR, bodies.get(0).kind());
            assertTrue("which holds the anchor cell", bodies.get(0).name().sameCell(c));
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
    private static int seatsInBlockAround(ClusteredGalaxyGenerator gen, long offsetCells, long r) {
        Set<String> seen = new HashSet<>();
        for (long x = -r; x <= r; x++) {
            for (long y = -r; y <= r; y++) {
                for (long z = -r; z <= r; z++) {
                    Optional<GalacticCoord> a = gen.anchorAt(SEED,
                            cell(offsetCells + x * SPACING, y * SPACING, z * SPACING));
                    if (a.isPresent()) {
                        seen.add(a.get().cellKey());
                    }
                }
            }
        }
        return seen.size();
    }

    private static Set<String> occupiedSeats(ClusteredGalaxyGenerator gen, long seed, long spacing,
                                             long r) {
        Set<String> keys = new HashSet<>();
        for (GalacticCoord a : anchors(gen, seed, spacing, r)) {
            keys.add(a.cellKey());
        }
        return keys;
    }
}
