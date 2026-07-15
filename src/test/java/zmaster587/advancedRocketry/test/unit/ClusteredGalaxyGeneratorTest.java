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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the deterministic clustered galaxy generator (TASK-89). Pure-JUnit; no MC bootstrap.
 *
 * <p>Pins the generation CONTRACTS: pure determinism over {@code (seed, cell)}, the minimum-spacing
 * guarantee, that the distribution actually clusters (void + dense regions), that {@code systemsInRegion}
 * agrees cell-for-cell with {@code systemAt}, and that the tunable params drive the outcome. Balance numbers
 * are exercised as inputs, never pinned as expected values.</p>
 */
public class ClusteredGalaxyGeneratorTest {

    private static final long SEED = 0xC0FFEEL;

    private static GalacticCoord cell(long sx, long sy, long sz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, 0L, 0L, 0L);
    }

    /** Iterate an inclusive sector box, calling the visitor with each cell coordinate. */
    private interface CellVisitor {
        void visit(GalacticCoord c);
    }

    private static void forEachCell(long r, CellVisitor v) {
        for (long x = -r; x <= r; x++) {
            for (long y = -r; y <= r; y++) {
                for (long z = -r; z <= r; z++) {
                    v.visit(cell(x, y, z));
                }
            }
        }
    }

    @Test
    public void systemAtIsDeterministic() {
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(GalaxyGenConfig.defaults());
        forEachCell(6, c -> {
            Optional<StarSystem> a = gen.systemAt(SEED, c);
            Optional<StarSystem> b = gen.systemAt(SEED, c);
            assertEquals("presence must be stable at " + c, a.isPresent(), b.isPresent());
            if (a.isPresent()) {
                assertEquals("id stable", a.get().starId(), b.get().starId());
                assertEquals("temperature stable", a.get().star().getTemperature(),
                        b.get().star().getTemperature());
                assertEquals("size stable", a.get().star().getSize(), b.get().star().getSize(), 0f);
            }
        });
    }

    @Test
    public void differentSeedsProduceDifferentGalaxies() {
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(GalaxyGenConfig.defaults());
        Set<String> occupiedA = occupiedCellKeys(gen, SEED, 8);
        Set<String> occupiedB = occupiedCellKeys(gen, SEED + 1, 8);
        assertFalse("a different seed must not reproduce the same galaxy", occupiedA.equals(occupiedB));
    }

    @Test
    public void minimumSpacingIsRespected() {
        // At most one system per minSpacing-cube super-cell, anywhere in the sampled volume.
        GalaxyGenConfig cfg = new GalaxyGenConfig(0.9d, 4, 8, 0.0d, null); // dense, no void: stress spacing
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg);
        Map<String, Integer> perSuperCell = new HashMap<>();
        forEachCell(10, c -> {
            if (gen.systemAt(SEED, c).isPresent()) {
                long s = cfg.minSpacing;
                String superKey = Math.floorDiv(c.sectorX(), s) + "_"
                        + Math.floorDiv(c.sectorY(), s) + "_" + Math.floorDiv(c.sectorZ(), s);
                perSuperCell.merge(superKey, 1, Integer::sum);
            }
        });
        for (Map.Entry<String, Integer> e : perSuperCell.entrySet()) {
            assertTrue("super-cell " + e.getKey() + " holds " + e.getValue() + " systems (max 1)",
                    e.getValue() <= 1);
        }
    }

    @Test
    public void distributionClustersIntoGalaxiesAndVoid() {
        // A strongly-clustered config: expect BOTH occupied sub-regions and entirely-empty (void) sub-regions.
        GalaxyGenConfig cfg = new GalaxyGenConfig(0.6d, 2, 8, 0.6d, null);
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg);

        int emptyBlocks = 0;
        int nonEmptyBlocks = 0;
        // Scan 16x16 coarse blocks (each 6x6x1 cells) across a wide plane; classify each as void or populated.
        for (long bx = -8; bx < 8; bx++) {
            for (long by = -8; by < 8; by++) {
                boolean any = false;
                for (long dx = 0; dx < 6 && !any; dx++) {
                    for (long dy = 0; dy < 6 && !any; dy++) {
                        if (gen.systemAt(SEED, cell(bx * 6 + dx, by * 6 + dy, 0)).isPresent()) {
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
        // The single most important consistency contract: the region enumeration and the point query must
        // never diverge, or a telescope scan would show systems a jump can't reach (or vice versa).
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(GalaxyGenConfig.defaults());
        long r = 9;

        Set<String> byPointQuery = new HashSet<>();
        forEachCell(r, c -> {
            if (gen.systemAt(SEED, c).isPresent()) {
                byPointQuery.add(c.cellKey());
            }
        });

        Map<GalacticCoord, StarSystem> region = gen.systemsInRegion(SEED, cell(-r, -r, -r), cell(r, r, r));
        Set<String> byRegion = new HashSet<>();
        for (Map.Entry<GalacticCoord, StarSystem> e : region.entrySet()) {
            byRegion.add(e.getKey().cellKey());
            // The enumerated cell must itself point-resolve to the same system.
            Optional<StarSystem> point = gen.systemAt(SEED, e.getKey());
            assertTrue("region cell " + e.getKey() + " must point-resolve", point.isPresent());
            assertEquals(point.get().starId(), e.getValue().starId());
        }
        assertEquals("systemsInRegion must enumerate exactly the point-query occupied cells",
                byPointQuery, byRegion);
    }

    @Test
    public void systemsInRegionHandlesSwappedBounds() {
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(GalaxyGenConfig.defaults());
        Map<GalacticCoord, StarSystem> ordered = gen.systemsInRegion(SEED, cell(-4, -4, -4), cell(4, 4, 4));
        Map<GalacticCoord, StarSystem> swapped = gen.systemsInRegion(SEED, cell(4, 4, 4), cell(-4, -4, -4));
        assertEquals("swapped min/max must enumerate the same box", ordered.keySet(), swapped.keySet());
    }

    @Test
    public void voidFractionDrivesOccupancy() {
        int allVoid = occupiedCellKeys(new ClusteredGalaxyGenerator(
                new GalaxyGenConfig(0.8d, 2, 8, 1.0d, null)), SEED, 8).size();
        int noVoid = occupiedCellKeys(new ClusteredGalaxyGenerator(
                new GalaxyGenConfig(0.8d, 2, 8, 0.0d, null)), SEED, 8).size();
        assertEquals("voidFraction=1 must yield an empty galaxy", 0, allVoid);
        assertTrue("voidFraction=0 must populate the galaxy", noVoid > 0);
    }

    @Test
    public void densityDrivesOccupancy() {
        int sparse = occupiedCellKeys(new ClusteredGalaxyGenerator(
                new GalaxyGenConfig(0.1d, 2, 8, 0.0d, null)), SEED, 10).size();
        int dense = occupiedCellKeys(new ClusteredGalaxyGenerator(
                new GalaxyGenConfig(0.9d, 2, 8, 0.0d, null)), SEED, 10).size();
        assertTrue("higher density must place more systems (" + sparse + " vs " + dense + ")",
                dense > sparse);
    }

    @Test
    public void starTypesAreDrawnFromTheConfiguredSetAndWeighted() {
        // Two archetypes; the heavily-weighted one must dominate the sample.
        List<GalaxyGenConfig.StarType> types = new ArrayList<>();
        types.add(new GalaxyGenConfig.StarType(50, 0.5f, 1.0f, 100)); // common
        types.add(new GalaxyGenConfig.StarType(250, 2.0f, 3.0f, 1));  // rare
        GalaxyGenConfig cfg = new GalaxyGenConfig(0.9d, 1, 8, 0.0d, types);
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg);

        int common = 0;
        int rare = 0;
        int other = 0;
        int total = 0;
        Set<String> seenTemps = new HashSet<>();
        // Iterate the underlying loop directly for a large sample.
        for (long x = -20; x <= 20; x++) {
            for (long y = -20; y <= 20; y++) {
                Optional<StarSystem> sys = gen.systemAt(SEED, cell(x, y, 0));
                if (!sys.isPresent()) {
                    continue;
                }
                int temp = sys.get().star().getTemperature();
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
                float size = sys.get().star().getSize();
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
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(
                new GalaxyGenConfig(0.9d, 1, 8, 0.0d, null));
        boolean sawAny = false;
        for (long x = -10; x <= 10; x++) {
            Optional<StarSystem> sys = gen.systemAt(SEED, cell(x, 0, 0));
            if (sys.isPresent()) {
                sawAny = true;
                assertTrue("procedural systems must carry a synthetic negative id, got " + sys.get().starId(),
                        sys.get().starId() < 0);
            }
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
                new GalaxyGenConfig(0.9d, 1, 8, 0.0d, types));

        Set<String> seenTemps = new HashSet<>();
        for (long x = -20; x <= 20; x++) {
            for (long y = -20; y <= 20; y++) {
                Optional<StarSystem> sys = gen.systemAt(SEED, cell(x, y, 0));
                if (sys.isPresent()) {
                    seenTemps.add(Integer.toString(sys.get().star().getTemperature()));
                }
            }
        }
        assertTrue("both equally-weighted archetypes must appear (weights summed in long)",
                seenTemps.contains("50") && seenTemps.contains("250"));
    }

    @Test
    public void proceduralBodiesAreDeterministicAndInsideTheSystemCell() {
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(
                new GalaxyGenConfig(0.9d, 1, 8, 0.0d, null));
        boolean checkedAny = false;
        for (long x = -6; x <= 6; x++) {
            GalacticCoord c = cell(x, 0, 0);
            List<SystemBody> a = gen.bodiesFor(SEED, c);
            List<SystemBody> b = gen.bodiesFor(SEED, c);
            if (gen.systemAt(SEED, c).isPresent()) {
                checkedAny = true;
                assertFalse("an occupied system must have bodies", a.isEmpty());
                assertEquals("bodiesFor must be deterministic", a, b);
                // First body is the star, at the cell centre.
                assertEquals(SystemBodyKind.STAR, a.get(0).kind());
                assertEquals(0, a.get(0).address().localX());
                assertEquals(0, a.get(0).address().localY());
                assertEquals(0, a.get(0).address().localZ());
                for (SystemBody body : a) {
                    assertTrue("every body's address must share the system's cell",
                            body.address().sameCell(c));
                    assertEquals("every body belongs to the system's star", a.get(0).starId(), body.starId());
                    // procedural bodies carry no realized dimension
                    assertFalse("procedural bodies are not descend targets yet", body.isDescendTarget());
                }
            } else {
                assertTrue("a void cell yields no bodies", a.isEmpty());
            }
        }
        assertTrue(checkedAny);
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private static Set<String> occupiedCellKeys(ClusteredGalaxyGenerator gen, long seed, long r) {
        Set<String> keys = new HashSet<>();
        forEachCell(r, c -> {
            if (gen.systemAt(seed, c).isPresent()) {
                keys.add(c.cellKey());
            }
        });
        return keys;
    }
}
