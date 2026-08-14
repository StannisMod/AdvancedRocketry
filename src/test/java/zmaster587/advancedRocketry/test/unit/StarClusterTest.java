package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.util.Optional;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusterField;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.Galaxy;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.StarCluster;
import zmaster587.advancedRocketry.universe.UniverseScale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for star clusters — the seat one level BELOW the star lattice.
 *
 * <p>What is pinned is the mechanism, not the richness: the fine lattice TILES the coarse cells it
 * replaces exactly (no seam, no partial cell, no overlap), membership is a property of the COARSE cell
 * so ownership stays one question with one answer, the separation floor follows the LOCAL lattice
 * level rather than a global constant, and a cluster cannot refine a cell below what a system needs.
 * The subdivisions and radii are balance knobs and are fed in as inputs.</p>
 */
public class StarClusterTest {

    private static final long SEED = 0x51A25L;

    private static GalaxyGenConfig cfg() {
        return GalaxyGenConfig.defaults();
    }

    private static GalaxyGenConfig.ClusterType type(int k) {
        return new GalaxyGenConfig.ClusterType("Test", k, 5d, 15d, 0.5d, 1);
    }

    // ─── The commensurate construction ─────────────────────────────────────────

    @Test
    public void theFineLatticeTilesACoarseCellExactly() {
        // The one word the whole mechanism rests on. If the sub-cells did not tile, every coarse cell
        // would carry a partial cell at its top face and the boundary would need a rule of its own —
        // which is exactly the cost a graded spacing was rejected for.
        for (int k : new int[] {2, 3, 4, 7, 14, 25, 215}) {
            for (long coarseEdge : new long[] {1_000L, 40_018_890L, 999_983L}) {
                StarCluster c = new StarCluster(type(k), 0L, 0L, 0L, 3L);
                long covered = 0L;
                long previousHigh = 0L;
                for (long i = 0; i < k; i++) {
                    long low = c.subCellLow(i, coarseEdge);
                    long edge = c.subCellEdge(i, coarseEdge);
                    assertEquals("sub-cell " + i + " must start where " + (i - 1) + " ended",
                            previousHigh, low);
                    previousHigh = low + edge;
                    covered += edge;
                }
                assertEquals("k=" + k + " over an edge of " + coarseEdge + " must tile it exactly",
                        coarseEdge, covered);
            }
        }
    }

    @Test
    public void everyOffsetLandsInExactlyOneSubCell() {
        // The inverse of the tiling: a coordinate must resolve to one sub-cell, and that sub-cell must
        // be the one whose bounds contain it. A mismatch here is a system addressed by a cell it does
        // not sit in.
        long coarseEdge = 40_018_890L;
        StarCluster c = new StarCluster(type(25), 0L, 0L, 0L, 3L);
        for (long offset : new long[] {0L, 1L, coarseEdge / 3L, coarseEdge / 2L, coarseEdge - 1L}) {
            long i = c.subCellIndex(offset, coarseEdge);
            assertTrue("index " + i + " out of range for offset " + offset, i >= 0 && i < 25);
            assertTrue("offset " + offset + " is not inside the sub-cell it resolved to",
                    offset >= c.subCellLow(i, coarseEdge)
                            && offset < c.subCellLow(i, coarseEdge) + c.subCellEdge(i, coarseEdge));
        }
    }

    @Test
    public void membershipIsAPropertyOfTheCoarseCell() {
        // Snapped to coarse cell faces, which is what makes the fine lattice tile and what keeps
        // "which lattice does this coordinate live on" an O(1) question with one answer. The shape
        // stays a ball, because the test is on the super-cell INDEX rather than on a box.
        StarCluster c = new StarCluster(type(4), 10L, 10L, 10L, 3L);
        assertTrue(c.containsSuperCell(10L, 10L, 10L));
        assertTrue(c.containsSuperCell(13L, 10L, 10L));
        assertFalse(c.containsSuperCell(14L, 10L, 10L));
        assertFalse("a ball, not a box: the corner is outside", c.containsSuperCell(13L, 13L, 13L));
    }

    // ─── Seating ───────────────────────────────────────────────────────────────

    @Test
    public void everyGalaxyHasANucleusAtItsOwnCentre() {
        // Not a special case: the nucleus is a cluster like the others, drawn at a known place instead
        // of a drawn one, and it is the richest of them.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg());
        ClusterField clusters = gen.clusters();
        Galaxy home = gen.galaxies().home(SEED);
        Optional<StarCluster> nucleus = clusters.nucleusOf(SEED, home);
        assertTrue(nucleus.isPresent());
        assertEquals(GalaxyGenConfig.NUCLEUS.subdivision, nucleus.get().subdivision());

        long s = cfg().minSpacing;
        assertTrue("the nucleus must cover the galaxy's own centre",
                nucleus.get().containsSuperCell(Math.floorDiv(home.centre().sectorX(), s),
                        Math.floorDiv(home.centre().sectorY(), s),
                        Math.floorDiv(home.centre().sectorZ(), s)));
        assertTrue("and it must be the richest cluster there is",
                nucleus.get().subdivision() > cfg().clusterTypes.get(0).subdivision);
    }

    @Test
    public void aClusterNeverStraddlesItsOwnLatticeCell() {
        // The same containment the galaxy tier needs, one level down and for the same reason: a
        // cluster reaching into a neighbouring cluster cell would make ownership ambiguous.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg());
        ClusterField clusters = gen.clusters();
        Galaxy home = gen.galaxies().home(SEED);
        long spacing = clusters.spacingSuperCells();
        int checked = 0;
        for (long cx = -3L; cx <= 3L; cx++) {
            for (long cy = -2L; cy <= 2L; cy++) {
                Optional<StarCluster> c = clusters.clusterAtIndex(SEED, home, cx, cy, 0L);
                if (!c.isPresent()) {
                    continue;
                }
                long r = c.get().radiusSuperCells();
                assertTrue("a cluster reaches past its cell's low face",
                        c.get().centreSuperX() - r >= cx * spacing);
                assertTrue("a cluster reaches past its cell's high face",
                        c.get().centreSuperX() + r <= cx * spacing + spacing - 1L);
                checked++;
            }
        }
        assertTrue("the sweep must find clusters", checked > 3);
    }

    @Test
    public void clustersOnlyExistWhereTheirGalaxyHasStars() {
        // One function, not a second rule: a cluster's occupancy is scaled by the same density profile
        // that placed the systems, so clusters stop where the galaxy does.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg());
        Galaxy home = gen.galaxies().home(SEED);
        long farSuper = UniverseScale.cellsForLightYears(home.radiusLy() * 4d) / cfg().minSpacing;
        long farCluster = farSuper / gen.clusters().spacingSuperCells() + 1L;
        assertFalse("a cluster turned up outside its own galaxy",
                gen.clusters().clusterAtIndex(SEED, home, farCluster, 0L, 0L).isPresent());
    }

    // ─── What the fine lattice does to the star field ──────────────────────────

    @Test
    public void aClusterHoldsFarMoreStarsThanTheFieldAroundit() {
        // The point of the whole tier: the stratified lattice caps density at about three times the
        // mean, while a real cluster runs tens of times the field. Measured as seats found in the same
        // volume, inside a cluster and beside it.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg());
        Galaxy home = gen.galaxies().home(SEED);
        ClusterField clusters = gen.clusters();
        long s = cfg().minSpacing;

        StarCluster found = null;
        for (long cx = -6L; cx <= 6L && found == null; cx++) {
            for (long cy = -4L; cy <= 4L && found == null; cy++) {
                Optional<StarCluster> c = clusters.clusterAtIndex(SEED, home, cx, cy, 0L);
                if (c.isPresent() && c.get().subdivision() > 1) {
                    found = c.get();
                }
            }
        }
        assertTrue("the sweep must find a cluster to measure", found != null);

        int inside = seatsInSuperCell(gen, found.centreSuperX(), found.centreSuperY(),
                found.centreSuperZ(), s);
        int outside = seatsInSuperCell(gen, found.centreSuperX() + 6L * found.radiusSuperCells(),
                found.centreSuperY(), found.centreSuperZ(), s);
        assertTrue("a cluster must be denser than the field beside it (" + inside + " vs " + outside
                + ") for " + found, inside > outside);
        assertTrue("and the field outside it must hold at most the one seat a coarse cell allows",
                outside <= 1);
    }

    @Test
    public void aClusterCannotRefineACellBelowWhatASystemNeeds() {
        // A cluster cannot conjure room its coarse cell never had. Refining below the smallest cell a
        // system can be more than a lone star in would produce a field of bare stars, which is the
        // opposite of a cluster — so a spacing too tight to refine simply is not refined, exactly as
        // too tight a spacing already degenerates rather than erroring.
        int tiny = 16;
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(new GalaxyGenConfig(tiny, 0.9d,
                GalaxyGenConfig.DEFAULT_GALAXY_SPACING, GalaxyGenConfig.DEFAULT_GALAXY_DENSITY,
                null, null));
        assertTrue("a spacing of " + tiny + " cells is below the refinement floor",
                tiny < UniverseScale.MIN_LATTICE_EDGE_CELLS * 2L);

        // At this spacing every seat must still attribute to its own COARSE super-cell, i.e. nothing
        // was subdivided into cells too small to hold anything.
        int checked = 0;
        for (long sup = 0; sup < 40; sup++) {
            Optional<GalacticCoord> anchor = gen.anchorAt(SEED,
                    GalacticCoord.ofSectorLocal(sup * tiny, 0L, 0L, 0L, 0L, 0L));
            if (!anchor.isPresent()) {
                continue;
            }
            assertEquals("a seat must stay in the coarse cell that was probed", sup,
                    Math.floorDiv(anchor.get().sectorX(), (long) tiny));
            checked++;
        }
        assertTrue(checked > 3);
    }

    @Test
    public void attributionNeverCrossesACoarseSuperCell() {
        // The invariant that survives the refinement: whatever lattice is in force locally, a cell is
        // attributed to a seat inside its OWN coarse super-cell. That is what keeps member attribution
        // exact and two systems' neighbourhoods from interleaving.
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg());
        long s = cfg().minSpacing;
        int checked = 0;
        for (long sup = -3L; sup <= 3L; sup++) {
            for (long offset : new long[] {0L, s / 4L, s / 2L, s - 1L}) {
                GalacticCoord probe = GalacticCoord.ofSectorLocal(sup * s + offset, 0L, 0L, 0L, 0L, 0L);
                Optional<GalacticCoord> anchor = gen.anchorAt(SEED, probe);
                if (!anchor.isPresent()) {
                    continue;
                }
                assertEquals("attribution crossed a coarse super-cell face", sup,
                        Math.floorDiv(anchor.get().sectorX(), s));
                checked++;
            }
        }
        assertTrue(checked > 3);
    }

    /**
     * Every seat inside ONE coarse super-cell, enumerated through the region query so the sub-lattice
     * is walked the way the generator itself walks it. Counting probes along a line would miss every
     * sub-cell off that line, and would read a refined cell as ordinary field.
     */
    private static int seatsInSuperCell(ClusteredGalaxyGenerator gen, long supX, long supY, long supZ,
                                        long s) {
        return gen.systemsInRegion(SEED,
                GalacticCoord.ofSectorLocal(supX * s, supY * s, supZ * s, 0L, 0L, 0L),
                GalacticCoord.ofSectorLocal(supX * s + s - 1L, supY * s + s - 1L, supZ * s + s - 1L,
                        0L, 0L, 0L)).size();
    }

}
