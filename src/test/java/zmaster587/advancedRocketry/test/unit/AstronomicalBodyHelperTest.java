package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-math astronomy helpers.
 *
 * Excluded from these tests: {@code getOrbitalTheta} / {@code getMoonOrbitalTheta} —
 * they call {@code AdvancedRocketry.proxy.getWorldTimeUniversal} which requires
 * the proxy to be initialized; loading {@code AdvancedRocketry.class} triggers
 * {@code FluidRegistry.enableUniversalBucket()} which can only run after Forge
 * bootstrap. Wrap-around coverage for those methods lives in
 * {@code integration/AstronomicalBodyHelperOrbitalThetaTest} where
 * {@code MinecraftBootstrap} has prepared the registry state.
 */
public class AstronomicalBodyHelperTest {

    private static StellarBody sunLikeStar() {
        StellarBody star = new StellarBody();
        // Defaults: size=1.0, blackHole=false, subStars=[]. Set temperature to a Sol-like value.
        star.setTemperature(100); // normalizedStarTemperature = 1.0 in getStellarBrightness math
        return star;
    }

    @Test
    public void bodySizeMultiplierIsInverselyProportionalToDistance() {
        // At 100 distance (1 AU equivalent) the multiplier is 1.
        assertEquals(1.0f, AstronomicalBodyHelper.getBodySizeMultiplier(100f), 1e-6);
        // Doubling the orbital distance halves the apparent size.
        assertEquals(0.5f, AstronomicalBodyHelper.getBodySizeMultiplier(200f), 1e-6);
        // Halving the distance doubles the apparent size.
        assertEquals(2.0f, AstronomicalBodyHelper.getBodySizeMultiplier(50f), 1e-6);
    }

    @Test
    public void orbitalPeriodAtEarthDistanceIsBaseline() {
        // At 100 distance and solarSize=1.0, the formula reduces to 48 days (one MC year).
        assertEquals(48.0, AstronomicalBodyHelper.getOrbitalPeriod(100, 1.0f), 1e-9);
    }

    @Test
    public void orbitalPeriodGrowsWithDistance() {
        double inner = AstronomicalBodyHelper.getOrbitalPeriod(50, 1.0f);
        double earth = AstronomicalBodyHelper.getOrbitalPeriod(100, 1.0f);
        double outer = AstronomicalBodyHelper.getOrbitalPeriod(200, 1.0f);

        assertTrue("inner planet must orbit faster than Earth", inner < earth);
        assertTrue("outer planet must orbit slower than Earth", outer > earth);
    }

    @Test
    public void moonOrbitalPeriodAtBaselineDistanceMatchesShortMonth() {
        // At distance 100 and planetary mass 1.0, the formula collapses to 8 MC days.
        assertEquals(8.0, AstronomicalBodyHelper.getMoonOrbitalPeriod(100f, 1.0f), 1e-9);
    }

    @Test
    public void stellarBrightnessMonotonicWithDistance() {
        StellarBody star = sunLikeStar();
        double atOneAu = AstronomicalBodyHelper.getStellarBrightness(star, 100);
        double atTwoAu = AstronomicalBodyHelper.getStellarBrightness(star, 200);
        double atHalfAu = AstronomicalBodyHelper.getStellarBrightness(star, 50);

        assertTrue("brightness must drop with distance", atTwoAu < atOneAu);
        assertTrue("brightness must rise as we approach the star", atHalfAu > atOneAu);
    }

    @Test
    public void stellarBrightnessAtEarthBaselineEqualsOne() {
        // sunLike: size=1.0, temperature=100 -> normalized=1.0, distance=100 -> AU=1.
        // Formula reduces to (1.0 * (1 * 1) / 1) = 1.0.
        assertEquals(1.0, AstronomicalBodyHelper.getStellarBrightness(sunLikeStar(), 100), 1e-9);
    }

    @Test
    public void blackHoleStarReducesBrightness() {
        StellarBody star = sunLikeStar();
        double normal = AstronomicalBodyHelper.getStellarBrightness(star, 100);

        StellarBody blackHole = sunLikeStar();
        blackHole.setBlackHole(true);
        double dimmed = AstronomicalBodyHelper.getStellarBrightness(blackHole, 100);

        // Implementation multiplies by 0.25 when the primary (and all sub-stars) are black holes.
        assertEquals(normal * 0.25, dimmed, 1e-9);
    }

    @Test
    public void planetaryLightLevelMultiplierBaselineIsOne() {
        assertEquals(1.0, AstronomicalBodyHelper.getPlanetaryLightLevelMultiplier(1.0), 1e-9);
    }

    @Test
    public void planetaryLightLevelGrowsSlowerThanInsolation() {
        // Eye-perceived brightness ~ 1.5x per 2x flux; the function is the natural log model.
        // Doubling flux must increase perceived brightness by ~1.5x.
        double doubleFlux = AstronomicalBodyHelper.getPlanetaryLightLevelMultiplier(2.0);
        assertEquals(1.5, doubleFlux, 1e-9);

        // Halving flux must drop perceived brightness to 1/1.5.
        double halfFlux = AstronomicalBodyHelper.getPlanetaryLightLevelMultiplier(0.5);
        assertEquals(1.0 / 1.5, halfFlux, 1e-9);
    }

    @Test
    public void averageTemperatureIsThicknessSensitive() {
        StellarBody star = sunLikeStar();
        int thinAtmosphereTemp = AstronomicalBodyHelper.getAverageTemperature(star, 100, 100);
        int thickAtmosphereTemp = AstronomicalBodyHelper.getAverageTemperature(star, 100, 1600);

        // A thick atmosphere heats the planet via the greenhouse multiplier in the formula.
        assertTrue("thicker atmosphere must imply higher surface temperature",
                thickAtmosphereTemp > thinAtmosphereTemp);
    }

    @Test
    public void averageTemperatureIsDistanceSensitive() {
        StellarBody star = sunLikeStar();
        int innerPlanet = AstronomicalBodyHelper.getAverageTemperature(star, 50, 100);
        int outerPlanet = AstronomicalBodyHelper.getAverageTemperature(star, 200, 100);

        assertTrue("planet farther from the star must be cooler", outerPlanet < innerPlanet);
    }

    @Test
    public void planetaryLightMultiplierWithinExpectedBounds() {
        // for a sun-like baseline, sweep across astronomical
        // distances and assert the eye-perceived light multiplier stays inside
        // a narrow band around the analytic value 1.5^log2(stellarBrightness).
        // The model collapses to PLM = 1.5^(2 * log2(100/d)) = (1.5)^(2*log2(100/d)).
        StellarBody star = sunLikeStar();
        int[] distances = {50, 100, 200, 400};
        double[] expectedMin = {2.20, 0.99, 0.440, 0.196};
        double[] expectedMax = {2.30, 1.01, 0.449, 0.199};
        for (int i = 0; i < distances.length; i++) {
            double sbm = AstronomicalBodyHelper.getStellarBrightness(star, distances[i]);
            double plm = AstronomicalBodyHelper.getPlanetaryLightLevelMultiplier(sbm);
            assertTrue(
                    "PLM at d=" + distances[i] + " was " + plm
                            + ", expected within [" + expectedMin[i] + ", " + expectedMax[i] + "]",
                    plm >= expectedMin[i] && plm <= expectedMax[i]);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // The reference frame, pinned by VALUE.
    //
    // The assertions above are mostly relative — thicker is warmer, farther is cooler — and a
    // relative assertion cannot notice that a scale constant moved: rescale the atmosphere axis and
    // "thicker is warmer" still holds while every temperature is wrong. These pin the absolute
    // numbers instead, each derived from the frame's own definitions (100 distance units = 1 AU,
    // 48 days = a year, 8 = a lunar month) rather than recorded from a run.
    //
    // They exist so that naming the scale constants can be shown to change nothing — and they stay
    // afterwards as the guard for the next edit. The temperature ones matter most: the distance
    // scale and the atmosphere scale are both 100 and live four lines apart, so a well-meant
    // search-and-replace can silently corrupt one of them.
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void orbitalPeriodFollowsTheThreeHalvesPowerLawExactly() {
        // Four times the distance is eight times the period.
        assertEquals(384.0, AstronomicalBodyHelper.getOrbitalPeriod(400, 1.0f), 1e-9);
        // A bigger star pulls the same distance into a shorter year.
        assertEquals(31.176914536239792, AstronomicalBodyHelper.getOrbitalPeriod(150, 2.0f), 1e-9);
    }

    @Test
    public void moonPeriodScalesWithParentMassAndDistanceExactly() {
        // Four times the parent mass halves the period.
        assertEquals(4.0, AstronomicalBodyHelper.getMoonOrbitalPeriod(100f, 4.0f), 1e-9);
        assertEquals(22.627416997969522, AstronomicalBodyHelper.getMoonOrbitalPeriod(200f, 1.0f), 1e-9);
    }

    @Test
    public void temperatureAtOneAuUnderOneAtmosphereIsPinned() {
        // 1 AU, one atmosphere: the radiative balance times the greenhouse term.
        assertEquals(287, AstronomicalBodyHelper.getAverageTemperature(sunLikeStar(), 100, 100));
    }

    @Test
    public void aVacuumWorldGetsTheBareRadiativeBalance() {
        // atmPressure 0 falls to the max(1, ...) floor — no greenhouse lift at all.
        assertEquals(255, AstronomicalBodyHelper.getAverageTemperature(sunLikeStar(), 100, 0));
    }

    @Test
    public void temperatureAtFourAuIsPinned() {
        assertEquals(143, AstronomicalBodyHelper.getAverageTemperature(sunLikeStar(), 400, 100));
    }

    @Test
    public void brightnessFallsWithTheSquareOfDistanceExactly() {
        assertEquals(0.25, AstronomicalBodyHelper.getStellarBrightness(sunLikeStar(), 200), 1e-9);
    }

    // The tick-taking overloads of the theta helpers do NOT touch the mod proxy — only the no-arg
    // forms do, which is what the class note above excludes. They carry the same law, so the wrap
    // is checkable here as well as in the integration test.

    @Test
    public void orbitalThetaWrapsOncePerPeriod() {
        long periodTicks = (long) (48.0 * 24000.0);
        assertEquals(0.0, AstronomicalBodyHelper.getOrbitalThetaAt(100, 1.0f, 0L), 1e-9);
        assertEquals(Math.PI / 2.0,
                AstronomicalBodyHelper.getOrbitalThetaAt(100, 1.0f, periodTicks / 4L), 1e-9);
        assertEquals(0.0, AstronomicalBodyHelper.getOrbitalThetaAt(100, 1.0f, periodTicks), 1e-9);
    }

    @Test
    public void aDegenerateOrbitStaysAddressableRatherThanNaN() {
        assertEquals(0.0, AstronomicalBodyHelper.getOrbitalThetaAt(0, 1.0f, 12345L), 1e-9);
        assertEquals(0.0, AstronomicalBodyHelper.getMoonOrbitalThetaAt(100, 0f, 12345L), 1e-9);
    }

}
