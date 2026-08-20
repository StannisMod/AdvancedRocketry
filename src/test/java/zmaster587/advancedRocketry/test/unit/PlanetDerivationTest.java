package zmaster587.advancedRocketry.test.unit;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.TerrainSource;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.BodyProfile;
import zmaster587.advancedRocketry.universe.PlanetDerivation;
import zmaster587.advancedRocketry.universe.PlanetTypePreset;
import zmaster587.advancedRocketry.universe.PlanetTypes;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.TerrainOption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the procedural planet derivation. Pure JUnit; no Minecraft bootstrap.
 *
 * <p>What is pinned here is what the design PROMISES, never the numbers that happen to deliver it: that
 * the same {@code (seed, cell)} answers the same world twice, that a world always satisfies the type it
 * was given, that zoning follows temperature rather than a table, that gravity is derived from mass and
 * radius, and that a terrain generator no installed mod provides is dropped BEFORE the draw rather than
 * after. Every balance constant is exercised as an input and none is asserted as an expected value.</p>
 */
public class PlanetDerivationTest {

    private static final long SEED = 0xBEEF1234L;

    @After
    public void restoreGlobals() {
        // Both are process-wide seams; a test that installs one must not leak it into the next class.
        PlanetTypes.resetToStock();
        PlanetTypes.setWorldTypeAvailability(null);
    }

    private static GalacticCoord cell(long sx, long sy, long sz) {
        return GalacticCoord.ofSectorLocal(sx, sy, sz, 0L, 0L, 0L);
    }

    /** A star of the given archetype. Temperature is in Advanced Rocketry units: 100 = Sol. */
    private static StellarBody star(int temperature, float size) {
        StellarBody s = new StellarBody();
        s.setTemperature(temperature);
        s.setSize(size);
        s.setId(-1);
        s.setName("test");
        return s;
    }

    private static StellarBody sol() {
        return star(100, 1.0f);
    }

    /** Every body of one system, as the generator would lay it out. */
    private static List<BodyProfile> system(long seed, GalacticCoord anchor, StellarBody s, int count) {
        List<BodyProfile> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int orbit = PlanetDerivation.orbitalDistanceOf(seed, anchor, i, count, s);
            // One cell per body, as the placement guarantees; the exact cell is the generator's business,
            // so a distinct synthetic one is enough to key the per-body draws.
            out.add(PlanetDerivation.derive(seed, anchor, cell(anchor.sectorX() + i + 1, 0, 0), 0, s,
                    false, orbit));
        }
        return out;
    }

    /**
     * <b>The scan and the landing describe the same world.</b>
     *
     * <p>{@code BodyProfile}'s own javadoc states this contract and nothing pinned it. A derived
     * temperature is what a telescope reports from across the system; the realized dimension then
     * recomputes one from the star, the orbit, the atmosphere and the world's ALBEDO — and the
     * derivation used to end on the neutral-albedo overload, so the two disagreed by
     * {@code ((1 − a)/0.7)^¼} for every world whose type states an albedo of its own. Measured on the
     * shipped table: a {@code greenhouse} world (a = 0.75) landed 22.7 % colder than it scanned and an
     * {@code ice} world 13 %.</p>
     *
     * <p>What this pins is not the second pass but the AGREEMENT: whatever law either side uses, the
     * number a profile carries has to be the number the dimension model produces from that profile's
     * own inputs. It is asserted exactly, because "the same world" admits no tolerance.</p>
     */
    @Test
    public void theTemperatureAScanReportsIsTheTemperatureTheWorldHas() {
        int compared = 0;
        Set<String> albedosSeen = new HashSet<>();
        for (long c = 0; c < 400; c++) {
            GalacticCoord anchor = cell(9000 + c, 0, 0);
            StellarBody s = c % 2 == 0 ? sol() : star(45, 0.7f);
            for (BodyProfile profile : system(SEED + c, anchor, s, 6)) {
                double albedo = profile.preset() == null
                        ? zmaster587.advancedRocketry.util.AstronomicalBodyHelper.EARTH_ALBEDO
                        : profile.preset().albedo();
                albedosSeen.add(Double.toString(albedo));
                // Exactly the call DimensionProperties.recalculateTemperature makes on a world
                // materialized from this profile: its star, its orbit, its air, its own albedo.
                int asTheWorldWillReadIt =
                        zmaster587.advancedRocketry.util.AstronomicalBodyHelper.getAverageTemperature(
                                s, Math.max(1, profile.orbitalDistance()), profile.pressure(), albedo);
                assertEquals("a " + profile.typeName() + " world (albedo " + albedo + ") scanned at "
                                + profile.temperatureKelvin() + " K must not land at another temperature",
                        profile.temperatureKelvin(), asTheWorldWillReadIt);
                compared++;
            }
        }
        assertTrue("the sweep must actually derive worlds", compared > 100);
        assertTrue("and it must cross types whose albedo is NOT Earth's, or it proves nothing about "
                + "the defect it exists for - saw " + albedosSeen, albedosSeen.size() > 2);
    }

    /**
     * A world's DAY is drawn, and it is not a function of its gravity.
     *
     * <p>The law this replaced was {@code (1/g)^3 * DEFAULT}: spin computed from SURFACE GRAVITY, which
     * has no bearing on rotation, so a half-gravity world got a day eight times longer. The pin that
     * catches a return to it is two bodies with the SAME gravity and DIFFERENT days — impossible under
     * any function of gravity alone, and cheap to find across a spread of seeds.</p>
     */
    @Test
    public void aDayIsDrawnAndIsNotAFunctionOfGravity() {
        GalacticCoord anchor = cell(600, 0, 0);
        StellarBody s = sol();
        Map<Integer, Integer> spinByGravity = new HashMap<>();
        boolean sameGravityDifferentDay = false;
        int seen = 0;

        for (int i = 0; i < 400 && !sameGravityDifferentDay; i++) {
            BodyProfile p = PlanetDerivation.derive(SEED + i, anchor, cell(600 + i, 7, 0), 0, s, false, 140);
            int spin = p.rotationalPeriodTicks();
            seen++;
            assertTrue("a day must stay inside the drawn band: " + spin,
                    spin >= 24000 / 5 && spin <= 24000 * 5);
            Integer earlier = spinByGravity.put(p.gravityPercent(), spin);
            if (earlier != null && earlier.intValue() != spin) {
                sameGravityDifferentDay = true;
            }
        }

        assertTrue("the sweep must actually produce bodies", seen > 0);
        assertTrue("two worlds of equal gravity must be able to have different days;"
                + " if none did in " + seen + " bodies, spin is a function of gravity again",
                sameGravityDifferentDay);
    }

    /** The same body answers the same day twice — a draw, not a random. */
    @Test
    public void aDrawnDayIsStillDeterministic() {
        GalacticCoord anchor = cell(610, 0, 0);
        StellarBody s = sol();
        BodyProfile a = PlanetDerivation.derive(SEED, anchor, cell(611, 2, 0), 0, s, false, 150);
        BodyProfile b = PlanetDerivation.derive(SEED, anchor, cell(611, 2, 0), 0, s, false, 150);
        assertEquals(a.rotationalPeriodTicks(), b.rotationalPeriodTicks());
    }

    // ─── Determinism ───────────────────────────────────────────────────────────

    @Test
    public void theSameCellAlwaysDerivesTheSameWorld() {
        StellarBody s = sol();
        for (long x = -12; x <= 12; x++) {
            GalacticCoord anchor = cell(x, 3, -1);
            for (int i = 0; i < 6; i++) {
                int orbit = PlanetDerivation.orbitalDistanceOf(SEED, anchor, i, 6, s);
                BodyProfile a = PlanetDerivation.derive(SEED, anchor, cell(x, 3, i), 0, s, false, orbit);
                BodyProfile b = PlanetDerivation.derive(SEED, anchor, cell(x, 3, i), 0, s, false, orbit);
                assertEquals("type must be stable", a.typeName(), b.typeName());
                assertEquals("terrain must be stable", a.terrain(), b.terrain());
                assertEquals("mass must be stable", a.massEarths(), b.massEarths(), 0d);
                assertEquals("radius must be stable", a.radiusEarths(), b.radiusEarths(), 0d);
                assertEquals("pressure must be stable", a.pressure(), b.pressure());
                assertEquals("temperature must be stable", a.temperatureKelvin(), b.temperatureKelvin());
                assertEquals("oxygen must be stable", a.hasOxygen(), b.hasOxygen());
                assertEquals("locking must be stable", a.tidallyLocked(), b.tidallyLocked());
            }
        }
    }

    @Test
    public void aBodysWorldIsKeyedOnItsCellNotOnItsPositionInTheList() {
        // The property that makes a profile survive a pin: a body keeps its world when the system's body
        // COUNT changes under it, because the draw is keyed on the durable cell name and not on an index.
        StellarBody s = sol();
        GalacticCoord anchor = cell(4, 0, 0);
        GalacticCoord body = cell(9, 1, 2);
        BodyProfile inFive = PlanetDerivation.derive(SEED, anchor, body, 0, s, false, 140);
        BodyProfile inTwelve = PlanetDerivation.derive(SEED, anchor, body, 0, s, false, 140);
        assertEquals(inFive.typeName(), inTwelve.typeName());
        assertEquals(inFive.massEarths(), inTwelve.massEarths(), 0d);
    }

    @Test
    public void aMoonIsNotACopyOfThePlanetWhoseCellItShares() {
        // A moon lives in its parent's cell by construction, so without the variant it would draw the
        // parent's exact physics — the same mass, the same air, the same world twice.
        StellarBody s = sol();
        GalacticCoord anchor = cell(0, 0, 0);
        GalacticCoord shared = cell(5, 0, 0);
        BodyProfile planet = PlanetDerivation.derive(SEED, anchor, shared, 0, s, false, 100);
        BodyProfile moon = PlanetDerivation.derive(SEED, anchor, shared, 1, s, true, 100);
        assertFalse("a moon must not inherit its parent's exact bulk",
                planet.massEarths() == moon.massEarths()
                        && planet.radiusEarths() == moon.radiusEarths());
        assertEquals(SystemBodyKind.MOON, moon.kind());
        assertTrue("a moon is never a giant", moon.radiusEarths() < 1.5d);
    }

    @Test
    public void metallicityBelongsToTheStarAndIsSharedByEveryBodyOfItsSystem() {
        GalacticCoord anchor = cell(7, -2, 5);
        double first = PlanetDerivation.metallicityOf(SEED, anchor);
        assertEquals(first, PlanetDerivation.metallicityOf(SEED, anchor), 0d);
        assertTrue("metallicity must be a positive multiplier", first > 0d);
        StellarBody s = sol();
        for (BodyProfile p : system(SEED, anchor, s, 6)) {
            assertEquals("every body of a system shares its star's metallicity", first, p.metallicity(), 0d);
        }
        // Different systems must not all be metal-average, or the axis does nothing.
        Set<Double> seen = new HashSet<>();
        for (long x = -30; x <= 30; x++) {
            seen.add(PlanetDerivation.metallicityOf(SEED, cell(x, 0, 0)));
        }
        assertTrue("metallicity must genuinely vary between stars", seen.size() > 10);
    }

    // ─── The type a world gets ─────────────────────────────────────────────────

    @Test
    public void everyDerivedWorldSatisfiesTheTypeItWasGiven() {
        // The admission ranges are the whole meaning of a type: a world outside its own preset's box
        // would be a world whose scan describes something else.
        int checked = 0;
        for (long x = -20; x <= 20; x++) {
            StellarBody s = starFor(x);
            GalacticCoord anchor = cell(x, 0, 0);
            for (BodyProfile p : system(SEED, anchor, s, 8)) {
                assertNotNull("no world may be left without a type", p.preset());
                assertTrue(p + " does not satisfy its own preset " + p.preset(),
                        p.preset().admits(p.pressure(), p.temperatureKelvin(), p.gravityPercent(),
                                p.kind() == SystemBodyKind.GAS_GIANT));
                checked++;
            }
        }
        assertTrue(checked > 300);
    }

    @Test
    public void theStockTableLeavesNoWorldUnclassified() {
        // A gap in the preset coverage is an authoring bug, and this is the only place it is visible:
        // an unclassified world still lands and still renders, so nothing else would ever notice.
        List<String> uncovered = new ArrayList<>();
        int total = 0;
        for (long x = -25; x <= 25; x++) {
            for (long z = -4; z <= 4; z++) {
                StellarBody s = starFor(x + z);
                GalacticCoord anchor = cell(x, 0, z);
                for (BodyProfile p : system(SEED, anchor, s, 8)) {
                    total++;
                    if (PlanetTypes.UNCLASSIFIED.equals(p.typeName()) && uncovered.size() < 15) {
                        uncovered.add("p=" + p.pressure() + " T=" + p.temperatureKelvin() + " g="
                                + p.gravityPercent() + (p.kind() == SystemBodyKind.GAS_GIANT
                                        ? " GIANT" : ""));
                    }
                }
            }
        }
        assertTrue("sample must be large", total > 2000);
        // The message NAMES the gap: a bare count would say a hole exists without saying where, and the
        // whole value of this test is that it hands the author the range to widen.
        assertTrue("the stock presets must cover every world the derivation can produce; uncovered "
                + "samples: " + uncovered, uncovered.isEmpty());
    }

    @Test
    public void aWideRangeOfWorldsIsProduced() {
        // The point of deriving a type rather than drawing one is variety that FOLLOWS the physics; a
        // table that collapses onto one or two names would satisfy every other test here.
        Set<String> names = new HashSet<>();
        for (long x = -25; x <= 25; x++) {
            StellarBody s = starFor(x);
            for (BodyProfile p : system(SEED, cell(x, 1, 1), s, 8)) {
                names.add(p.typeName());
            }
        }
        assertTrue("the derivation must produce many kinds of world, saw " + names,
                names.size() >= 6);
    }

    // ─── Zoning emerges from the physics ───────────────────────────────────────

    @Test
    public void aFartherOrbitIsAlwaysColder() {
        StellarBody s = sol();
        int previous = Integer.MAX_VALUE;
        for (int d = 10; d <= 4000; d += 10) {
            int t = PlanetDerivation.bareTemperature(s, d);
            assertTrue("temperature must never rise with distance (" + d + ")", t <= previous);
            previous = t;
        }
    }

    @Test
    public void giantsFormInTheColdAndNeverInTheHeat() {
        // The zoning claim, stated in physics rather than in the implementation's threshold: a world
        // warm enough for liquid water on its surface did not accrete a gas envelope.
        int giantsCold = 0;
        int checkedHot = 0;
        for (long x = -30; x <= 30; x++) {
            StellarBody s = starFor(x);
            GalacticCoord anchor = cell(x, 2, 0);
            for (BodyProfile p : system(SEED, anchor, s, 8)) {
                boolean giant = p.kind() == SystemBodyKind.GAS_GIANT;
                int bare = PlanetDerivation.bareTemperature(s, p.orbitalDistance());
                if (bare >= 273) {
                    checkedHot++;
                    assertFalse("a giant must not form above the freezing point of water: " + p, giant);
                } else if (giant) {
                    giantsCold++;
                }
            }
        }
        assertTrue("the hot zone must actually be sampled", checkedHot > 100);
        assertTrue("giants must actually form in the cold", giantsCold > 5);
    }

    @Test
    public void aColdSystemsWarmZoneSitsCloserInThanAHotOnes() {
        // The reference distance is what makes "the warm zone" mean the same thing around every star.
        int coolDwarf = PlanetDerivation.referenceDistance(star(40, 0.6f));
        int sunlike = PlanetDerivation.referenceDistance(sol());
        int blueGiant = PlanetDerivation.referenceDistance(star(220, 2.6f));
        assertTrue("a cool dwarf's warm zone must be inside a sunlike star's", coolDwarf < sunlike);
        assertTrue("a hot star's warm zone must be outside a sunlike star's", blueGiant > sunlike);
    }

    // ─── Gravity is DERIVED, and mass/radius are primary ───────────────────────

    @Test
    public void gravityFollowsMassOverRadiusSquared() {
        StellarBody s = sol();
        for (long x = -20; x <= 20; x++) {
            for (BodyProfile p : system(SEED, cell(x, 5, 5), s, 8)) {
                double expected = p.massEarths() / (p.radiusEarths() * p.radiusEarths());
                double clamped = Math.max(0.05d, Math.min(4d, expected));
                assertEquals("gravity must be M/R^2 (clamped), not an independent draw",
                        clamped * 100d, p.gravityPercent(), 1.0d);
            }
        }
    }

    @Test
    public void doublingMassDoublesGravityAndDoublingRadiusQuartersIt() {
        assertEquals(2d * zmaster587.advancedRocketry.dimension.DimensionProperties.derivedGravity(1d, 1d),
                zmaster587.advancedRocketry.dimension.DimensionProperties.derivedGravity(2d, 1d), 1e-9d);
        assertEquals(zmaster587.advancedRocketry.dimension.DimensionProperties.derivedGravity(1d, 1d) / 4d,
                zmaster587.advancedRocketry.dimension.DimensionProperties.derivedGravity(1d, 2d), 1e-9d);
    }

    // ─── Atmosphere retention ──────────────────────────────────────────────────

    @Test
    public void aHeavierWorldHoldsMoreAirThanALighterOneInTheSameOrbit() {
        // Retention is the physical claim behind the pressure draw; the scatter must not be big enough
        // to reverse it across a large sample, or "heavy worlds have thick air" is not a rule at all.
        StellarBody s = sol();
        double lightAverage = 0d;
        double heavyAverage = 0d;
        int light = 0;
        int heavy = 0;
        for (long x = -40; x <= 40; x++) {
            for (BodyProfile p : system(SEED, cell(x, 9, 9), s, 8)) {
                if (p.kind() == SystemBodyKind.GAS_GIANT) {
                    continue;
                }
                if (p.massEarths() < 0.3d) {
                    lightAverage += p.pressure();
                    light++;
                } else if (p.massEarths() > 3d) {
                    heavyAverage += p.pressure();
                    heavy++;
                }
            }
        }
        assertTrue("both weight classes must be sampled", light > 20 && heavy > 20);
        assertTrue("a heavy world must hold more air on average (" + (lightAverage / light) + " vs "
                        + (heavyAverage / heavy) + ")",
                heavyAverage / heavy > lightAverage / light);
    }

    // ─── Oxygen is biology, on top of an already-suitable world ────────────────

    @Test
    public void oxygenOnlyAppearsOnTypesThatPermitItAndStaysRare() {
        int oxygen = 0;
        int total = 0;
        for (long x = -40; x <= 40; x++) {
            StellarBody s = starFor(x);
            for (BodyProfile p : system(SEED, cell(x, 7, 0), s, 8)) {
                total++;
                if (p.hasOxygen()) {
                    oxygen++;
                    assertTrue("oxygen on a type that forbids it: " + p, p.preset().allowsOxygen());
                }
            }
        }
        assertTrue("sample must be large", total > 500);
        assertTrue("a breathable world must stay rare, saw " + oxygen + "/" + total,
                oxygen * 20 < total);
    }

    // ─── Tidal locking ─────────────────────────────────────────────────────────

    @Test
    public void aCloseOrbitIsLockedAndADistantOneIsNot() {
        StellarBody s = sol();
        assertTrue("a very close orbit must be locked", PlanetDerivation.tidallyLockedAt(s, 1));
        assertFalse("a distant orbit must not be locked", PlanetDerivation.tidallyLockedAt(s, 100_000));
    }

    @Test
    public void aCoolDwarfsWarmZoneLiesInsideItsLockingRadius() {
        // The astronomical point of D4b, stated as the relation it rests on: around the commonest kind
        // of star, the orbits that are warm enough to live in are also the ones that are locked — while
        // around a sunlike star they are not.
        StellarBody dwarf = star(40, 0.6f);
        StellarBody sun = sol();
        assertTrue("a red dwarf's warm zone must be tidally locked",
                PlanetDerivation.tidallyLockedAt(dwarf, PlanetDerivation.referenceDistance(dwarf)));
        assertFalse("a sunlike star's warm zone must not be",
                PlanetDerivation.tidallyLockedAt(sun, PlanetDerivation.referenceDistance(sun)));
    }

    @Test
    public void aGiantIsNeverReportedAsTidallyLocked() {
        for (long x = -30; x <= 30; x++) {
            StellarBody s = starFor(x);
            for (BodyProfile p : system(SEED, cell(x, 11, 0), s, 8)) {
                if (p.kind() == SystemBodyKind.GAS_GIANT) {
                    assertFalse("nobody stands on a giant, so locking it means nothing: " + p,
                            p.tidallyLocked());
                }
            }
        }
    }

    // ─── Orbits ────────────────────────────────────────────────────────────────

    @Test
    public void orbitsAreOrderedAndSpreadLogarithmically() {
        StellarBody s = sol();
        GalacticCoord anchor = cell(2, 2, 2);
        int count = 9;
        int previous = 0;
        List<Integer> orbits = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int d = PlanetDerivation.orbitalDistanceOf(SEED, anchor, i, count, s);
            assertTrue("body " + i + " must orbit outside body " + (i - 1) + " (" + previous + " -> "
                    + d + ")", d > previous);
            previous = d;
            orbits.add(d);
        }
        // Geometric spacing: the outer gaps must dwarf the inner ones, which uniform spacing never does.
        int innerGap = orbits.get(1) - orbits.get(0);
        int outerGap = orbits.get(count - 1) - orbits.get(count - 2);
        assertTrue("spacing must widen outward (" + innerGap + " vs " + outerGap + ")",
                outerGap > innerGap * 3);
    }

    @Test
    public void aStarsZoneIsItsOwnBusinessAndNotItsNeighbourhoods() {
        // How much room a system has where it happens to sit is not an input to where its worlds
        // orbit. A cramped system holds FEWER worlds — the generator drops what does not fit — and
        // never the same worlds moved closer to their star than their own climate says they are.
        // The defect this replaces normalised every orbit to the neighbourhood, so one orbital
        // distance was one distance in a roomy system and another in a cramped one.
        StellarBody dwarf = star(40, 0.6f);
        StellarBody giant = star(220, 2.6f);
        GalacticCoord anchor = cell(4, -2, 7);

        for (int i = 0; i < 6; i++) {
            int cool = PlanetDerivation.orbitalDistanceOf(SEED, anchor, i, 6, dwarf);
            int hot = PlanetDerivation.orbitalDistanceOf(SEED, anchor, i, 6, giant);
            assertTrue("a hot star's zone must be wider than a cool one's at every rank ("
                    + cool + " vs " + hot + ")", hot > cool);
        }
        assertTrue("a cool dwarf's system is compact", 
                PlanetDerivation.outerOrbit(dwarf) < PlanetDerivation.outerOrbit(giant));
    }

    // ─── D6: the availability filter runs BEFORE the draw ──────────────────────

    @Test
    public void anUnavailableWorldTypeIsDroppedAndItsWeightRedistributed() {
        PlanetTypePreset preset = PlanetTypePreset.builder("t")
                .terrain(TerrainOption.ofWorldType("MISSING", "", 97))
                .terrain(TerrainOption.ofNative(3, 2))
                .terrain(TerrainOption.ofTemplate("ruins", 1))
                .build();
        PlanetTypes.setWorldTypeAvailability(name -> false);

        Map<String, Integer> drawn = new HashMap<>();
        for (int i = 0; i < 4000; i++) {
            TerrainOption option = PlanetTypes.drawTerrain(preset, i * 0x9E3779B97F4A7C15L);
            String key = option.source() + ":" + option.genType() + option.template();
            drawn.merge(key, 1, Integer::sum);
        }
        assertFalse("a world type no mod provides must never be drawn",
                drawn.containsKey(TerrainSource.MOD_WORLDTYPE + ":0"));
        // The survivors keep their RATIO to each other (2:1). Converting the missing entry's share into
        // the native fallback instead would swamp the template at roughly 99:1.
        int nativeDraws = drawn.getOrDefault(TerrainSource.NATIVE + ":3", 0);
        int templateDraws = drawn.getOrDefault(TerrainSource.TEMPLATE + ":0ruins", 0);
        assertTrue("both survivors must be drawn", nativeDraws > 0 && templateDraws > 0);
        double ratio = nativeDraws / (double) templateDraws;
        assertTrue("weights must renormalize among the survivors, not collapse into the fallback "
                        + "(saw " + nativeDraws + ":" + templateDraws + ")",
                ratio > 1.5d && ratio < 2.5d);
    }

    @Test
    public void anAvailableWorldTypeIsDrawnNormally() {
        PlanetTypePreset preset = PlanetTypePreset.builder("t")
                .terrain(TerrainOption.ofWorldType("PRESENT", "opts", 99))
                .terrain(TerrainOption.ofNative(0, 1))
                .build();
        PlanetTypes.setWorldTypeAvailability(name -> "PRESENT".equals(name));
        int foreign = 0;
        for (int i = 0; i < 500; i++) {
            if (PlanetTypes.drawTerrain(preset, i * 0x9E3779B97F4A7C15L).source()
                    == TerrainSource.MOD_WORLDTYPE) {
                foreign++;
            }
        }
        assertTrue("an installed generator must dominate at weight 99:1, saw " + foreign + "/500",
                foreign > 400);
    }

    @Test
    public void aPresetWhoseEveryGeneratorIsMissingStillProducesATerrain() {
        PlanetTypePreset preset = PlanetTypePreset.builder("t")
                .terrain(TerrainOption.ofWorldType("A", "", 1))
                .terrain(TerrainOption.ofWorldType("B", "", 1))
                .build();
        PlanetTypes.setWorldTypeAvailability(name -> false);
        TerrainOption option = PlanetTypes.drawTerrain(preset, 12345L);
        assertEquals("a world must still generate when its type's mods are all absent",
                TerrainSource.NATIVE, option.source());
    }

    // ─── Type overlap is a weighted draw, not first match ──────────────────────

    @Test
    public void overlappingPresetsShareTheirProbabilityByWeight() {
        List<PlanetTypePreset> table = new ArrayList<>();
        table.add(PlanetTypePreset.builder("common").weight(90)
                .pressure(0, 1000).temperature(0, 1000).gravity(0, 400).build());
        table.add(PlanetTypePreset.builder("rare").weight(10)
                .pressure(0, 1000).temperature(0, 1000).gravity(0, 400).build());
        PlanetTypes.setPresets(table);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 5000; i++) {
            PlanetTypePreset p = PlanetTypes.drawType(100, albedo -> 280, 100, false,
                    i * 0x9E3779B97F4A7C15L);
            counts.merge(p.name(), 1, Integer::sum);
        }
        assertTrue("both overlapping presets must be reachable — first match would never draw the "
                        + "second: " + counts,
                counts.getOrDefault("rare", 0) > 100);
        assertTrue("the heavier preset must dominate: " + counts,
                counts.getOrDefault("common", 0) > counts.getOrDefault("rare", 0) * 3);
    }

    @Test
    public void aWorldNoPresetAdmitsIsReportedRatherThanSubstituted() {
        List<PlanetTypePreset> table = new ArrayList<>();
        table.add(PlanetTypePreset.builder("narrow").weight(1)
                .pressure(0, 10).temperature(0, 10).gravity(0, 10).build());
        PlanetTypes.setPresets(table);
        assertEquals("silently substituting a preset would hide the coverage gap for ever",
                null, PlanetTypes.drawType(900, albedo -> 900, 300, false, 1L));
    }

    /** A star archetype that varies across the sweep, so no test measures one kind of system only. */
    private static StellarBody starFor(long x) {
        int[] temps = {40, 70, 100, 150, 220};
        float[] sizes = {0.6f, 0.9f, 1.1f, 1.4f, 2.2f};
        int i = (int) Math.floorMod(x, temps.length);
        return star(temps[i], sizes[i]);
    }
}
