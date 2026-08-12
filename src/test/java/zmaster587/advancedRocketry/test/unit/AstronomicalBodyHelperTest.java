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

        // A black hole emits a quarter of what its size and temperature would otherwise give.
        assertEquals(normal * 0.25, dimmed, 1e-9);
    }

    /**
     * Every star in a system lights the worlds in it. Before this was true, the companion list was
     * walked only to decide a boolean and no companion ever contributed a photon.
     */
    @Test
    public void everyStarInASystemContributesItsOwnLight() {
        double alone = AstronomicalBodyHelper.getStellarBrightness(sunLikeStar(), 100);

        StellarBody binary = sunLikeStar();
        binary.addSubStar(sunLikeStar());

        assertEquals("two identical stars light a world twice as brightly as one does",
                2 * alone, AstronomicalBodyHelper.getStellarBrightness(binary, 100), 1e-9);
    }

    /**
     * A companion does not repeal the primary's nature.
     *
     * <p>The case this pins used to invert: any ordinary companion cleared the black-hole flag, after
     * which the luminosity was taken from the BLACK HOLE's own size and temperature at FULL strength —
     * so a black hole with a companion came out brighter than a bare one and lit by the wrong body,
     * while the companion contributed nothing.</p>
     */
    @Test
    public void aCompanionDoesNotTurnABlackHoleBackIntoAStar() {
        double sunAlone = AstronomicalBodyHelper.getStellarBrightness(sunLikeStar(), 100);

        StellarBody bareHole = sunLikeStar();
        bareHole.setBlackHole(true);
        double holeAlone = AstronomicalBodyHelper.getStellarBrightness(bareHole, 100);

        StellarBody holeWithCompanion = sunLikeStar();
        holeWithCompanion.setBlackHole(true);
        holeWithCompanion.addSubStar(sunLikeStar());
        double together = AstronomicalBodyHelper.getStellarBrightness(holeWithCompanion, 100);

        assertEquals("a black hole and its companion each light the world on their own terms",
                holeAlone + sunAlone, together, 1e-9);
        assertTrue("the hole stays dimmed: the pair is never as bright as two ordinary stars",
                together < 2 * sunAlone);
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

    /**
     * A world's temperature follows its ALBEDO, which its type states. The formula used to hard-code
     * 0.3 for every surface, so an ice world and a lava world at the same distance were the same
     * temperature — and the physical direction matters: more reflective means colder, which is what
     * keeps ice being ice.
     */
    @Test
    public void albedoCoolsAWorldAndTheDefaultIsEarths() {
        StellarBody star = sunLikeStar();
        int dark = AstronomicalBodyHelper.getAverageTemperature(star, 100, 0, 0.10d);
        int earthLike = AstronomicalBodyHelper.getAverageTemperature(star, 100, 0, 0.30d);
        int icy = AstronomicalBodyHelper.getAverageTemperature(star, 100, 0, 0.60d);

        assertTrue("a darker surface absorbs more and runs hotter", dark > earthLike);
        assertTrue("a more reflective surface runs colder", icy < earthLike);
        assertEquals("the albedo-less form must still mean Earth's albedo",
                AstronomicalBodyHelper.getAverageTemperature(star, 100, 0), earthLike);
    }

    @Test
    public void orbitalPeriodFollowsTheThreeHalvesPowerLawExactly() {
        // Four times the distance is eight times the period.
        assertEquals(384.0, AstronomicalBodyHelper.getOrbitalPeriod(400, 1.0f), 1e-9);
        // A heavier star pulls the same distance into a shorter year, as sqrt(M) — Kepler's third law,
        // P = 48 * a^1.5 / sqrt(M) = 48 * 1.5^1.5 / sqrt(2). The second argument is a MASS in solar
        // masses; while it was read as a RADIUS this line expected 31.176914536239792, i.e. 1.5^1.5/2^1.5.
        assertEquals(62.353829072479584, AstronomicalBodyHelper.getOrbitalPeriod(150, 2.0f), 1e-9);
    }

    /**
     * A star's year is set by its MASS. A star that states no mass supplies one from its radius through
     * the main-sequence relation, which is exact for Sol — and is emphatically not the radius itself.
     */
    @Test
    public void aYearIsKeyedOnStellarMassAndAStarWithoutOneDerivesItFromItsRadius() {
        StellarBody sol = sunLikeStar(); // size 1.0
        assertEquals("Sol's mass and radius are both 1, so nothing can tell them apart here",
                1.0, sol.getMass(), 1e-6);
        assertEquals(48.0, AstronomicalBodyHelper.getOrbitalPeriod(100, sol.getMass()), 1e-9);

        StellarBody big = sunLikeStar();
        big.setSize(2.0f);
        // R = 2 gives M = 2^1.25 = 2.3784, so the year is 48/sqrt(2.3784) days. The mass is a float, so
        // the exact figure below carries that narrowing — deliberately, per this file's header.
        assertEquals(2.378414230005442, big.getMass(), 1e-6);
        assertEquals(31.124149808586335, AstronomicalBodyHelper.getOrbitalPeriod(100, big.getMass()), 1e-9);
        // A star two Sol-radii across is HEAVIER than two solar masses, so keying the year on its mass
        // gives a shorter year than substituting the radius would. Any star but Sol separates the two.
        assertTrue("a two-radius star masses more than two Suns", big.getMass() > big.getSize());
        assertTrue("so its year is shorter than a radius substitution gives",
                AstronomicalBodyHelper.getOrbitalPeriod(100, big.getMass())
                        < AstronomicalBodyHelper.getOrbitalPeriod(100, big.getSize()));

        StellarBody stated = sunLikeStar();
        stated.setSize(2.0f);
        stated.setMass(4.0f);
        assertEquals("a stated mass wins over the derivation", 4.0, stated.getMass(), 1e-6);
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
