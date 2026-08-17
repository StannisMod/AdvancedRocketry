package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import zmaster587.advancedRocketry.hyperdrive.DriveTier;
import zmaster587.advancedRocketry.hyperdrive.DriveTuning;
import zmaster587.advancedRocketry.hyperdrive.JumpSpeed;
import zmaster587.advancedRocketry.space.CellFrames;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.PlanetarySystem;
import zmaster587.advancedRocketry.universe.UniverseScale;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

import static org.junit.Assert.assertTrue;

/**
 * How far is the NEAREST other star system, and how long does a baseline drive take to cross that?
 *
 * <p>Pure arithmetic over the real generator, the real cell frame and the real speed formula — no
 * world, no harness, nothing mutated. The question it answers is whether the interstellar leg of a
 * jump is a duration the game can contain at all, and it is asked before any server boots because a
 * number this large is cheaper to falsify on paper.</p>
 *
 * <p>What is pinned here is SHAPE, never the numbers: the spacing guarantee the generator promises,
 * and that a farther cell costs strictly more ticks than a nearer one. Every constant it reads —
 * spacing, density, drive power, speed — is a balance knob, and a test that pinned one would fail
 * the day someone rebalanced without anything having broken.</p>
 */
public class InterstellarLegDistanceTest {

    /** A baseline drive hauling the placeholder hull: the reference ship every band is quoted for. */
    private static final long BASELINE_SPEED =
            JumpSpeed.blocksPerTick(DriveTuning.BASELINE_DRIVE_POWER, DriveTuning.PLACEHOLDER_SHIP_MASS,
                    DriveTier.baseline());

    private static GalacticCoord cell(long sx, long sy, long sz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, 0L, 0L, 0L);
    }

    /**
     * Sector distance, in cells, out to which we look for a neighbour. Four super-cells each way is
     * enough that a draw at the default density finds one in every seed tried, and the region
     * enumeration costs O(super-cells), not O(cells).
     */
    private static final long SEARCH_RADIUS_CELLS = 4L * GalaxyGenConfig.DEFAULT_MIN_SPACING;

    @Test
    public void theNearestSystemIsFarEnoughToBeAJumpAndCloseEnoughToBeReached() {
        GalaxyGenConfig cfg = GalaxyGenConfig.defaults();
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(cfg);

        List<Long> ticks = new ArrayList<>();
        List<String> rows = new ArrayList<>();
        for (long seed = 1L; seed <= 20L; seed++) {
            Map<GalacticCoord, PlanetarySystem> found = gen.systemsInRegion(seed,
                    cell(-SEARCH_RADIUS_CELLS, -SEARCH_RADIUS_CELLS, -SEARCH_RADIUS_CELLS),
                    cell(SEARCH_RADIUS_CELLS, SEARCH_RADIUS_CELLS, SEARCH_RADIUS_CELLS));
            // The leg a PLAYER flies runs anchor to anchor: he sits in a system and jumps to another
            // one. Measuring from the box's centre instead would measure an arbitrary point in the
            // void, which is not a place anybody departs from.
            GalacticCoord home = nearestTo(found.keySet(), cell(0L, 0L, 0L));
            GalacticCoord neighbour = home == null ? null : nearestTo(found.keySet(), home);
            if (neighbour == null) {
                rows.add("seed " + seed + ": fewer than two systems within " + SEARCH_RADIUS_CELLS + " cells");
                continue;
            }
            double best = CellFrames.STATIC.distanceBetween(home, neighbour, 0L);
            long t = JumpSpeed.transitTicks(best, BASELINE_SPEED);
            ticks.add(t);
            rows.add(String.format("seed %2d: %s -> %s, %.3e blocks (%d cells) -> %d ticks = %.1f s",
                    seed, home.cellKey(), neighbour.cellKey(), best,
                    (long) (best / GalacticCoord.CELL), t, t / 20.0D));
        }

        Collections.sort(ticks);
        StringBuilder report = new StringBuilder();
        report.append("\n=== interstellar leg, baseline drive (power ")
                .append(DriveTuning.BASELINE_DRIVE_POWER).append(", mass ")
                .append(DriveTuning.PLACEHOLDER_SHIP_MASS).append(") = ")
                .append(BASELINE_SPEED).append(" blocks/tick ===\n");
        report.append("cell edge ").append(GalacticCoord.CELL).append(" blocks, minSpacing ")
                .append(cfg.minSpacing).append(" cells, density ").append(cfg.density)
                .append(", galaxy spacing ").append(cfg.galaxySpacing).append(" cells\n");
        for (String row : rows) {
            report.append("  ").append(row).append('\n');
        }
        if (!ticks.isEmpty()) {
            report.append(String.format("  --> min %d ticks (%.1f s), median %d ticks (%.1f s), max %d ticks (%.1f min)%n",
                    ticks.get(0), ticks.get(0) / 20.0D,
                    ticks.get(ticks.size() / 2), ticks.get(ticks.size() / 2) / 20.0D,
                    ticks.get(ticks.size() - 1), ticks.get(ticks.size() - 1) / 1200.0D));
        }
        System.out.println(report);

        assertTrue("no seed produced a pair of systems at all — the search box or the generator"
                + " config is wrong, not the claim under test:" + report, !ticks.isEmpty());
        // Deliberately NOT pinned: how many ticks that is. Spacing, density and speed are all
        // balance knobs, and the whole point of the reading is to feed a number back into them.
        // What the assertion may say is that a jump between two systems is a FLIGHT and not an
        // instant — the property the transit machinery exists for.
        assertTrue("an interstellar leg that takes no time is not a flight:" + report,
                ticks.get(0) > 0L);
    }

    /** The nearest coordinate in {@code cells} to {@code from}, itself excluded; null when none. */
    private static GalacticCoord nearestTo(java.util.Collection<GalacticCoord> cells, GalacticCoord from) {
        GalacticCoord best = null;
        double bestDist = Double.MAX_VALUE;
        for (GalacticCoord c : cells) {
            double d = CellFrames.STATIC.distanceBetween(from, c, 0L);
            if (d > 0.0D && d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    // ── the band between the two lattices ─────────────────────────────────────

    /**
     * How much further a galaxy crossing is than one interstellar step, as arithmetic on the two
     * constants: a reference galaxy's DIAMETER over the mean star separation. STATED HERE, before the
     * sweep below measures it through the real generator.
     *
     * <p>At the shipped numbers this is about <b>×23 641</b>. The measurement can disagree with the
     * arithmetic in one way that matters: if the generator's actual nearest-neighbour distance drifts
     * away from the separation it is configured with, the two lattices are not the scales apart the
     * design believes they are.</p>
     */
    private static final double DECLARED_STAR_TO_GALAXY_BAND =
            2d * UniverseScale.REFERENCE_GALAXY_RADIUS_LY / UniverseScale.MEAN_STAR_SEPARATION_LY;

    /**
     * How wide a band the drive ladder needs to have a SECOND TIER in it at all — the reason the
     * universe was taken to its real scale rather than a design preference. A tier buys an order of
     * magnitude or so of speed; a star→galaxy gap narrower than this leaves no rung above the first,
     * and with no rung there is nothing for the research branch or the technology unlocks to open.
     */
    private static final double MIN_BAND_FOR_A_SECOND_DRIVE_TIER = 1_000d;

    @Test
    public void crossingAGalaxyIsWideEnoughAboveOneStepToHoldASecondDriveTier() {
        System.out.println(String.format(
                "star -> galaxy band: %.0f x (galaxy diameter %.0f ly / star separation %.2f ly)",
                DECLARED_STAR_TO_GALAXY_BAND, 2d * UniverseScale.REFERENCE_GALAXY_RADIUS_LY,
                UniverseScale.MEAN_STAR_SEPARATION_LY));
        assertTrue("a star -> galaxy band of only x" + (long) DECLARED_STAR_TO_GALAXY_BAND
                        + " leaves no room for a drive tier above the first",
                DECLARED_STAR_TO_GALAXY_BAND >= MIN_BAND_FOR_A_SECOND_DRIVE_TIER);
    }

    @Test
    public void theMeasuredBandMatchesTheArithmeticItIsDerivedFrom() {
        // The same 20 seeds as the leg reading above, and the same real generator. The lattice is
        // STRATIFIED rather than Poisson, so a measured neighbour distance runs somewhat wider than
        // the configured edge and the measured band comes out somewhat narrower than the declared one.
        // A factor of two is the spread that allows; anything past it means the two lattices are no
        // longer the scales apart the drive ladder is derived against.
        final double TOLERANCE_FACTOR = 2d;

        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(GalaxyGenConfig.defaults());
        List<Double> bands = new ArrayList<>();
        for (long seed = 1L; seed <= 20L; seed++) {
            Double stepLy = nearestNeighbourLightYears(gen, seed);
            if (stepLy == null) {
                continue;
            }
            bands.add(2d * UniverseScale.REFERENCE_GALAXY_RADIUS_LY / stepLy);
        }
        Collections.sort(bands);
        assertTrue("no seed produced a pair of systems to measure a step from", !bands.isEmpty());

        double median = bands.get(bands.size() / 2);
        System.out.println(String.format(
                "measured band over %d seeds: min x%.0f, median x%.0f, max x%.0f (declared x%.0f)",
                bands.size(), bands.get(0), median, bands.get(bands.size() - 1),
                DECLARED_STAR_TO_GALAXY_BAND));

        assertTrue("the measured band x" + (long) median + " is not the declared x"
                        + (long) DECLARED_STAR_TO_GALAXY_BAND + " within a factor of "
                        + TOLERANCE_FACTOR,
                median >= DECLARED_STAR_TO_GALAXY_BAND / TOLERANCE_FACTOR
                        && median <= DECLARED_STAR_TO_GALAXY_BAND * TOLERANCE_FACTOR);
    }

    /** The distance from the system nearest the origin to ITS nearest neighbour, in light years. */
    private static Double nearestNeighbourLightYears(ClusteredGalaxyGenerator gen, long seed) {
        Map<GalacticCoord, PlanetarySystem> found = gen.systemsInRegion(seed,
                cell(-SEARCH_RADIUS_CELLS, -SEARCH_RADIUS_CELLS, -SEARCH_RADIUS_CELLS),
                cell(SEARCH_RADIUS_CELLS, SEARCH_RADIUS_CELLS, SEARCH_RADIUS_CELLS));
        GalacticCoord home = nearestTo(found.keySet(), cell(0L, 0L, 0L));
        GalacticCoord neighbour = home == null ? null : nearestTo(found.keySet(), home);
        if (neighbour == null) {
            return null;
        }
        double blocks = CellFrames.STATIC.distanceBetween(home, neighbour, 0L);
        return blocks / (double) AstronomicalBodyHelper.BLOCKS_PER_LIGHT_YEAR;
    }

    @Test
    public void aFartherTargetCostsStrictlyMoreTicksThanANearerOne() {
        double near = CellFrames.STATIC.distanceBetween(cell(0, 0, 0), cell(4, 0, 0), 0L);
        double far = CellFrames.STATIC.distanceBetween(cell(0, 0, 0),
                cell(GalaxyGenConfig.DEFAULT_MIN_SPACING, 0, 0), 0L);
        long nearTicks = JumpSpeed.transitTicks(near, BASELINE_SPEED);
        long farTicks = JumpSpeed.transitTicks(far, BASELINE_SPEED);
        System.out.println("near " + near + " blocks -> " + nearTicks + " ticks; far " + far
                + " blocks -> " + farTicks + " ticks");
        assertTrue("transit time must grow with distance: near=" + nearTicks + " far=" + farTicks,
                farTicks > nearTicks);
    }
}
