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
 * guarantee, the separation floor between two seats, that the distribution actually clusters (void +
 * dense regions), that {@code systemsInRegion} agrees with {@code systemAt}, and that the tunable
 * params drive the outcome. Balance numbers are exercised as inputs, never pinned as expected
 * values.</p>
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

    private static GalaxyGenConfig cfg(double density, int spacing, int clusterScale, double voidFraction) {
        return new GalaxyGenConfig(density, spacing, clusterScale, voidFraction, null);
    }

    private static GalaxyGenConfig defaultsCfg() {
        return cfg(0.35d, SPACING, 16, 0.6d);
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
        GalaxyGenConfig config = cfg(0.9d, SPACING, 8, 0.0d); // dense, no void: stress spacing
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
        GalaxyGenConfig config = cfg(1.0d, SPACING, 8, 0.0d); // every cube occupied: the tightest case
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
        GalaxyGenConfig config = cfg(1.0d, SPACING, 8, 0.0d);
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
    public void distributionClustersIntoGalaxiesAndVoid() {
        // A strongly-clustered config: expect BOTH occupied sub-regions and entirely-empty (void) ones.
        GalaxyGenConfig config = cfg(0.6d, SPACING, 8, 0.6d);
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(config);

        int emptyBlocks = 0;
        int nonEmptyBlocks = 0;
        // Scan 16x16 coarse blocks (each 6x6x1 super-cells) across a wide plane.
        for (long bx = -8; bx < 8; bx++) {
            for (long by = -8; by < 8; by++) {
                boolean any = false;
                for (long dx = 0; dx < 6 && !any; dx++) {
                    for (long dy = 0; dy < 6 && !any; dy++) {
                        if (gen.anchorAt(SEED, cell((bx * 6 + dx) * SPACING, (by * 6 + dy) * SPACING, 0))
                                .isPresent()) {
                            any = true;
                        }
                    }
                }
                if (any) {
                    nonEmptyBlocks++;
                } else {
                    emptyBlocks++;
                }
            }
        }
        assertTrue("clustering must leave genuinely empty void regions", emptyBlocks > 0);
        assertTrue("clustering must leave genuinely populated regions", nonEmptyBlocks > 0);
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
    public void voidFractionDrivesOccupancy() {
        int allVoid = occupiedSeats(new ClusteredGalaxyGenerator(cfg(0.8d, SPACING, 8, 1.0d)),
                SEED, SPACING, 6).size();
        int noVoid = occupiedSeats(new ClusteredGalaxyGenerator(cfg(0.8d, SPACING, 8, 0.0d)),
                SEED, SPACING, 6).size();
        assertEquals("voidFraction=1 must yield an empty galaxy", 0, allVoid);
        assertTrue("voidFraction=0 must populate the galaxy", noVoid > 0);
    }

    @Test
    public void densityDrivesOccupancy() {
        int sparse = occupiedSeats(new ClusteredGalaxyGenerator(cfg(0.1d, SPACING, 8, 0.0d)),
                SEED, SPACING, 7).size();
        int dense = occupiedSeats(new ClusteredGalaxyGenerator(cfg(0.9d, SPACING, 8, 0.0d)),
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
        GalaxyGenConfig config = new GalaxyGenConfig(0.9d, SPACING, 8, 0.0d, types);
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
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(0.9d, SPACING, 8, 0.0d));
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
        GalaxyGenConfig c = new GalaxyGenConfig(5.0d, -3, 0, -1.0d, null);
        assertEquals("density clamps to [0,1]", 1.0d, c.density, 0d);
        assertEquals("voidFraction clamps to [0,1]", 0.0d, c.voidFraction, 0d);
        assertTrue("minSpacing floors at 1", c.minSpacing >= 1);
        assertTrue("clusterScale floors at 1", c.clusterScale >= 1);
        assertFalse("empty star types fall back to defaults", c.starTypes.isEmpty());
    }

    @Test
    public void configClampsNaNToZero() {
        // A NaN attribute (Double.parseDouble accepts "NaN") must not poison the density/void gates.
        GalaxyGenConfig c = new GalaxyGenConfig(Double.NaN, 1, 1, Double.NaN, null);
        assertEquals("NaN density clamps to 0", 0.0d, c.density, 0d);
        assertEquals("NaN voidFraction clamps to 0", 0.0d, c.voidFraction, 0d);
    }

    @Test
    public void hugeStarWeightsDoNotCollapseTheDistribution() {
        // Two near-Integer.MAX weights must not overflow the weight sum into a collapsed "first type only".
        List<GalaxyGenConfig.StarType> types = new ArrayList<>();
        types.add(new GalaxyGenConfig.StarType(50, 0.5f, 1.0f, Integer.MAX_VALUE));
        types.add(new GalaxyGenConfig.StarType(250, 2.0f, 3.0f, Integer.MAX_VALUE));
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(
                new GalaxyGenConfig(0.9d, SPACING, 8, 0.0d, types));

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
        GalaxyGenConfig config = cfg(0.9d, SPACING, 8, 0.0d);
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
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(0.9d, SPACING, 8, 0.0d));
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
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(1.0d, SPACING, 8, 0.0d));
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
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg(0.9d, 1, 8, 0.0d));
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
        GalaxyGenConfig config = cfg(0.9d, SPACING, 8, 0.0d);
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

    private static Set<String> occupiedSeats(ClusteredGalaxyGenerator gen, long seed, long spacing,
                                             long r) {
        Set<String> keys = new HashSet<>();
        for (GalacticCoord a : anchors(gen, seed, spacing, r)) {
            keys.add(a.cellKey());
        }
        return keys;
    }
}
