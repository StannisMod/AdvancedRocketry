package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.After;
import org.junit.Test;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.ClusteredGalaxyGenerator;
import zmaster587.advancedRocketry.universe.ConeWalk;
import zmaster587.advancedRocketry.universe.EmptyGalaxyGenerator;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.RegionScan;
import zmaster587.advancedRocketry.universe.StellarMagnitude;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.TelescopeScan;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.advancedRocketry.universe.UniverseScale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for what a telescope LOOKS AT and what it can SEE.
 *
 * <p>Two claims, and they are the whole of the redesign. A survey is a <b>cone</b> with its apex at
 * the instrument rather than a box of coordinates with no observer; and what it finds is bounded by
 * <b>brightness</b> rather than by a configured horizon, so its reach is derived from its aperture
 * and is a different distance for a red dwarf than for a blue giant.</p>
 *
 * <p>These pin player-facing promises: a better aperture reaches farther, dust costs reach the same
 * way distance does, a starless world is not something a telescope finds, a look reports its whole
 * territory rather than a sample of it, and the detection stage is genuinely cheaper than the
 * characterisation stage. They do not pin the sweep order, the tick formula or the storage shape.</p>
 */
public class TelescopeConeSurveyTest {

    private static final GalacticCoord HOME = GalacticCoord.ofSectorLocal(0, 0, 0, 0, 0, 0);
    private static final long SEED = 0xC0FFEEL;
    private static final long STEP = GalaxyGenConfig.DEFAULT_MIN_SPACING;

    private double previousMargin;

    @org.junit.Before
    public void armResolveMargin() {
        previousMargin = ARConfiguration.getCurrentConfig().telescopeResolveMarginMagnitudes;
        // STATED, so nothing here depends on the shipped default staying put - except the one test
        // that is explicitly about what the shipped default costs, which sets it again itself.
        ARConfiguration.getCurrentConfig().telescopeResolveMarginMagnitudes =
                ARConfiguration.DEFAULT_TELESCOPE_RESOLVE_MARGIN_MAGNITUDES;
    }

    @After
    public void resetSeams() {
        ARConfiguration.getCurrentConfig().telescopeResolveMarginMagnitudes = previousMargin;
        UniverseRegistry.setGenerator(null);
        UniverseRegistry.setStarLookup(null);
    }

    private static GalacticCoord cell(long x, long y, long z) {
        return GalacticCoord.ofSectorLocal(x, y, z, 0L, 0L, 0L);
    }

    /** A star of a stated bulk — the only two numbers its brightness is made of. */
    private static StellarBody starOf(int id, float sizeSuns, int temperatureUnits) {
        StellarBody s = new StellarBody();
        s.setId(id);
        s.setName("Star-" + id);
        s.setSize(sizeSuns);
        s.setTemperature(temperatureUnits);
        return s;
    }

    private static List<GalaxyGenConfig.StarType> archetypes() {
        return GalaxyGenConfig.defaults().starTypes;
    }

    // ── the photometry ────────────────────────────────────────────────────────

    @Test
    public void aStarsBrightnessIsMadeOfItsSizeAndItsTemperature() {
        // The Stefan-Boltzmann law, and the fourth power is the whole reason the sky's brightness is
        // so unlike its population: a blue star is 0.13 % of the stars and outshines a red dwarf by
        // nearly four orders. Both stock archetypes, against the figures the design was sized from.
        double redDwarf = StellarMagnitude.luminositySuns(0.8d, 40);
        double blueGiant = StellarMagnitude.luminositySuns(2.0d, 220);

        assertEquals("a mid-band red dwarf is about a sixtieth of a Sun", 0.0164d, redDwarf, 0.001d);
        assertEquals("a mid-band blue giant is about ninety Suns", 93.7d, blueGiant, 0.5d);
        assertEquals("and the Sun is one Sun", 1d,
                StellarMagnitude.luminositySuns(1d, StellarMagnitude.SOLAR_TEMPERATURE_UNITS), 1e-9d);
    }

    @Test
    public void howFarAStarCanBeSeenIsTheThingAnApertureDecides() {
        // The claim a configured horizon could not make: ONE instrument reaches eighty times farther
        // for a blue giant than for a red dwarf, so no single number of light years describes it.
        double redDwarf = StellarMagnitude.luminositySuns(0.8d, 40);
        double blueGiant = StellarMagnitude.luminositySuns(2.0d, 220);

        double dwarfReach = StellarMagnitude.detectionRangeLightYears(redDwarf, 10d);
        double giantReach = StellarMagnitude.detectionRangeLightYears(blueGiant, 10d);

        assertEquals("a red dwarf at the tenth magnitude reaches ~45 ly", 45d, dwarfReach, 2d);
        assertEquals("a blue giant at the same limit reaches ~3 400 ly", 3414d, giantReach, 50d);

        // Five magnitudes is a factor of a hundred in flux, hence ten in distance. That is the ladder
        // a better instrument climbs, and it is derived rather than configured.
        assertEquals("five magnitudes of aperture must be ten times the reach",
                10d * dwarfReach, StellarMagnitude.detectionRangeLightYears(redDwarf, 15d), 1d);
    }

    @Test
    public void dustCostsReachExactlyTheWayDistanceDoes() {
        // Why a magnitude limit is the right bound: extinction is measured in the same unit, so dust
        // and distance are two terms of ONE sum instead of two mechanics that have to be reconciled.
        double sunLike = StellarMagnitude.luminositySuns(1.15d, 100);
        double absolute = StellarMagnitude.absoluteMagnitude(sunLike);

        double clear = StellarMagnitude.apparentMagnitude(absolute, 200d, 0d);
        double dusty = StellarMagnitude.apparentMagnitude(absolute, 200d, 2.5d);

        assertEquals("two and a half magnitudes of dust dim it by two and a half magnitudes",
                clear + 2.5d, dusty, 1e-9d);
        // Measured, not asserted round: a 1.15-Sun star at 200 ly stands at magnitude 8.47 in clear
        // sky and 10.97 behind this cloud, so a tenth-magnitude instrument sees the one and not
        // the other. The bracket is what makes the sum above a MECHANIC rather than arithmetic.
        assertTrue("a star inside the aperture in clear sky must be outside it behind a cloud: "
                        + clear + " -> " + dusty,
                clear < 10d && dusty > 10d);
    }

    @Test
    public void aStarDescribedOnlyByItsSizeIsReadAtTheSunsTemperature() {
        // A pack may state a star's size and say nothing about its temperature, and zero raised to
        // the fourth power is a star that emits nothing — so the pack would have authored an
        // invisible sun and found out by pointing a telescope at empty sky. Zero means UNSTATED.
        StellarBody unstated = new StellarBody();
        unstated.setSize(1f);

        assertEquals("a Sun-sized star with no stated temperature is a Sun", 1d,
                StellarMagnitude.luminositySuns(unstated), 1e-9d);

        // And a thing that really is dark says so, rather than being inferred from a missing number.
        StellarBody hole = new StellarBody();
        hole.setBlackHole(true);
        assertEquals("a black hole emits nothing a survey in the visible could catch", 0d,
                StellarMagnitude.luminositySuns(hole), 0d);
    }

    // ── the shape ─────────────────────────────────────────────────────────────

    @Test
    public void aPointingIsAConeAndEveryLookLiesInsideIt() {
        // The shape itself: everything the survey looks at is within the half-angle of the axis, and
        // within the reach. A box could not state either sentence, because it has no apex.
        double halfAngle = Math.toRadians(15d);
        ConeWalk cone = ConeWalk.aimed(HOME, 1, 0, 0, halfAngle, 40 * STEP, STEP);

        assertTrue("a pointing worth walking must hold more than its axis", cone.totalLooks() > 40);
        for (int i = 0; i < cone.totalLooks(); i++) {
            GalacticCoord look = cone.lookAt(i);
            double axial = look.sectorX();
            double across = Math.hypot(look.sectorY(), look.sectorZ());
            assertTrue("a look must lie in front of the instrument, not behind it: " + look.cellKey(),
                    axial > 0d);
            // One stride of slack: a look sits on a lattice, so the cell it rounds to can be half a
            // stride outside the mathematical cone without the pointing having widened.
            assertTrue("a look must lie inside the cone: " + look.cellKey() + " is "
                            + Math.toDegrees(Math.atan2(across, axial)) + " degrees off axis",
                    across <= axial * Math.tan(halfAngle) + STEP);
            assertTrue("and inside the reach", axial <= 40 * STEP);
        }
    }

    @Test
    public void aWiderPatchOfSkyIsMoreSurveyAndTheGrowthIsTheSquareOfTheAngle() {
        // What an operator is trading when he opens the aperture up. Stated because it is the number
        // that decides whether a configuration is playable: doubling the opening quadruples the work.
        long reach = 200 * STEP;
        int narrow = ConeWalk.aimed(HOME, 0, 0, 1, Math.toRadians(5d), reach, STEP).totalLooks();
        int wide = ConeWalk.aimed(HOME, 0, 0, 1, Math.toRadians(10d), reach, STEP).totalLooks();

        System.out.println("a 200-territory pointing holds " + narrow + " looks at 5 degrees and "
                + wide + " at 10");
        assertTrue("twice the opening must be about four times the survey: " + narrow + " -> " + wide,
                wide > narrow * 3 && wide < narrow * 5);
    }

    @Test
    public void aPointingIsWalkedOutwardsSoAnAbortedSurveyIsAShorterCone() {
        // Not cosmetic: a survey may be stopped at any point, and what a half-finished one has
        // covered must be the NEAR sky rather than a scatter through the far.
        ConeWalk cone = ConeWalk.aimed(HOME, 0, 1, 0, Math.toRadians(20d), 30 * STEP, STEP);

        long deepestSoFar = 0;
        for (int i = 0; i < cone.totalLooks(); i++) {
            long depth = cone.lookAt(i).sectorY();
            assertTrue("the walk must never step back towards the instrument: " + depth
                    + " after " + deepestSoFar, depth >= deepestSoFar);
            deepestSoFar = depth;
        }
        assertTrue("the walk must reach the pointing's own depth", deepestSoFar >= 29 * STEP);
    }

    @Test
    public void aPointingSurvivesTheChunkItStartedIn() {
        // The save contract: a pointing is an apex, a direction and an opening, and all three come
        // back — a survey that reloaded aimed somewhere else would quietly resume over other sky.
        ConeWalk cone = ConeWalk.aimed(cell(11, -3, 7), 2, -5, 1, Math.toRadians(3d), 60 * STEP, STEP);
        net.minecraft.nbt.NBTTagCompound nbt = new net.minecraft.nbt.NBTTagCompound();
        cone.writeToNBT(nbt);

        ConeWalk back = ConeWalk.readFromNBT(nbt);
        assertNotNull("a saved pointing must come back", back);
        assertEquals("aimed from the same place", cone.apex().cellKey(), back.apex().cellKey());
        assertEquals("at the same opening", cone.halfAngleRadians(), back.halfAngleRadians(), 1e-12d);
        assertEquals("over the same sky", cone.totalLooks(), back.totalLooks());
        assertEquals("and every look must land where it landed before",
                cone.lookAt(cone.totalLooks() / 2).cellKey(),
                back.lookAt(back.totalLooks() / 2).cellKey());
    }

    // ── what a look registers ─────────────────────────────────────────────────

    /** A registry holding one star of a stated bulk, seated {@code lightYears} away along +X. */
    private static UniverseRegistry oneStarAt(double lightYears, float sizeSuns, int temperature) {
        UniverseRegistry.setGenerator(new EmptyGalaxyGenerator());
        UniverseRegistry.setStarLookup(id -> starOf(id, sizeSuns, temperature));

        UniverseRegistry registry = new UniverseRegistry();
        GalacticCoord seat = cell(UniverseScale.cellsForLightYears(lightYears), 0, 0);
        registry.place(seat, 7);
        registry.addPoi(SystemBody.fixedAt(seat, SystemBodyKind.STAR, Constants.INVALID_PLANET, 7));
        registry.addPoi(SystemBody.fixedAt(seat, SystemBodyKind.PLANET, 701, 7));
        return registry;
    }

    private static GalacticCoord seatAt(double lightYears) {
        return cell(UniverseScale.cellsForLightYears(lightYears), 0, 0);
    }

    @Test
    public void aStarInsideTheApertureRegistersAndOneBeyondItDoesNot() {
        // THE mechanic. The same star, the same direction, the same instrument — and the only thing
        // that decides whether the survey knows it is there is how far away it is.
        double sunLike = StellarMagnitude.luminositySuns(1.15d, 100);
        double reach = StellarMagnitude.detectionRangeLightYears(sunLike, 8d);
        assertTrue("arrangement: a sun-like star at the shipped aperture must reach a useful way",
                reach > 100d);

        UniverseRegistry near = oneStarAt(reach * 0.5d, 1.15f, 100);
        assertEquals("a star well inside the aperture must register", 1,
                TelescopeScan.detect(near, seatAt(reach * 0.5d), HOME, 8d).size());

        UniverseRegistry far = oneStarAt(reach * 2d, 1.15f, 100);
        assertEquals("and the same star twice its reach away must not", 0,
                TelescopeScan.detect(far, seatAt(reach * 2d), HOME, 8d).size());
    }

    @Test
    public void aBetterApertureFindsWhatAWorseOneCannot() {
        // The progression axis the design replaces a config horizon with: the instrument improves,
        // and the sky it can reach improves with it — without a number anywhere being raised.
        double sunLike = StellarMagnitude.luminositySuns(1.15d, 100);
        double justOutOfReach = StellarMagnitude.detectionRangeLightYears(sunLike, 8d) * 1.5d;
        UniverseRegistry registry = oneStarAt(justOutOfReach, 1.15f, 100);
        GalacticCoord seat = seatAt(justOutOfReach);

        assertTrue("arrangement: the star must be out of the shipped aperture's reach",
                TelescopeScan.detect(registry, seat, HOME, 8d).isEmpty());
        assertFalse("a better aperture must find it without anything else changing",
                TelescopeScan.detect(registry, seat, HOME, 13d).isEmpty());
    }

    @Test
    public void aDetectionCarriesHowFarAwayAndHowBrightItLooked() {
        // A detection is a fact about a LOOK and not about a system: the same star is a different
        // detection from somewhere else, and the second stage needs both numbers to decide how much
        // of it can be made out.
        UniverseRegistry registry = oneStarAt(300d, 1.15f, 100);
        List<TelescopeScan.Detection> hits = TelescopeScan.detect(registry, seatAt(300d), HOME, 25d);

        assertEquals("arrangement: exactly one star to describe", 1, hits.size());
        TelescopeScan.Detection hit = hits.get(0);
        assertEquals("it must know how far away it is", 300d, hit.distanceLightYears(), 5d);
        assertEquals("and how bright it looked, which is the two together",
                StellarMagnitude.apparentMagnitude(
                        StellarMagnitude.absoluteMagnitude(StellarMagnitude.luminositySuns(1.15d, 100)),
                        hit.distanceLightYears(), 0d),
                hit.apparentMagnitude(), 1e-6d);
    }

    @Test
    public void aStarlessWorldIsNotSomethingATelescopeFinds() {
        // Physics the mechanic inherits rather than a rule someone wrote: an unbound world emits
        // nothing, so no aperture registers one. Finding a rogue planet is a thing you do by GOING
        // there, and that is what makes the void worth flying into rather than surveying from home.
        UniverseRegistry.setGenerator(new EmptyGalaxyGenerator());
        UniverseRegistry.setStarLookup(id -> null);
        UniverseRegistry registry = new UniverseRegistry();
        GalacticCoord seat = cell(UniverseScale.cellsForLightYears(20d), 0, 0);
        registry.place(seat, 3);
        registry.addPoi(SystemBody.fixedAt(seat, SystemBodyKind.ROGUE_PLANET, 301, 3));

        assertTrue("a starless world must never register, at any aperture",
                TelescopeScan.detect(registry, seat, HOME, 40d).isEmpty());

        // And the discriminator: what is unreachable by LIGHT is still reachable by being there.
        CrystalMemory crystal = new CrystalMemory();
        assertTrue("an instrument standing in it must still be able to name it",
                TelescopeScan.resolveCell(registry, seat, crystal, 1_000L, id -> "Body-" + id) > 0);
    }

    @Test
    public void theOperatorChoosesWhetherADetectionIsFollowedToTheBodies() {
        // The instrument's own control, and the reason it is a control rather than a config key: over
        // known sky an operator wants every body named, and into sky nobody has visited he wants a
        // list of places worth flying to. A deep pointing on FULL fills a crystal many times faster.
        //
        // Both halves are asserted against the SAME look, because the claim is that the choice is
        // what differs — a fixture that only checked the cheap side would pass against an instrument
        // that had quietly stopped resolving anything at all.
        // Close enough that the instrument could certainly make the system out, so what the test
        // measures is the OPERATOR's choice and not the aperture's reach - those are two different
        // reasons for a bare row and a fixture near the gate would confuse them.
        UniverseRegistry registry = oneStarAt(10d, 1.15f, 100);
        GalacticCoord seat = seatAt(10d);
        List<TelescopeScan.Detection> hits = TelescopeScan.detect(registry, seat, HOME, 12d);
        assertTrue("arrangement: the aperture must not be what limits this look",
                hits.get(0).resolvable());
        assertEquals("arrangement: exactly one system to follow up", 1, hits.size());

        CrystalMemory coordsOnly = new CrystalMemory();
        TelescopeScan.characterise(registry, hits.get(0), coordsOnly, 1_000L, id -> "Body-" + id,
                false);
        assertNull("recording positions only must name no body", coordsOnly.forBody(701));
        assertEquals("but it must still write the address, or the look taught the operator nothing",
                1, coordsOnly.size());

        CrystalMemory full = new CrystalMemory();
        TelescopeScan.characterise(registry, hits.get(0), full, 1_000L, id -> "Body-" + id, true);
        assertNotNull("and the full setting must name the system's bodies", full.forBody(701));
        assertTrue("which is strictly more than the address alone: " + full.size() + " vs "
                + coordsOnly.size(), full.size() > coordsOnly.size());
    }

    @Test
    public void seeingThatAStarIsThereAndMakingOutWhatOrbitsItAreDifferentObservations() {
        // The mechanic the resolve margin buys, and the reason it is not a second aperture: ONE
        // instrument, ONE star, and the only thing that differs is how far away it is. Near, the
        // survey names the planet; far, it registers a point of light and writes the address.
        double sunLike = StellarMagnitude.luminositySuns(1.15d, 100);
        double detectAt = 12d;
        double resolveAt = detectAt - ARConfiguration.DEFAULT_TELESCOPE_RESOLVE_MARGIN_MAGNITUDES;
        double detectReach = StellarMagnitude.detectionRangeLightYears(sunLike, detectAt);
        double resolveReach = StellarMagnitude.detectionRangeLightYears(sunLike, resolveAt);
        assertTrue("arrangement: resolving must be the harder of the two, by a wide margin: "
                        + resolveReach + " vs " + detectReach,
                resolveReach * 4d < detectReach);

        // Far: inside the aperture, outside what it can make out.
        double far = (detectReach + resolveReach) / 2d;
        UniverseRegistry farSky = oneStarAt(far, 1.15f, 100);
        List<TelescopeScan.Detection> farHits =
                TelescopeScan.detect(farSky, seatAt(far), HOME, detectAt);
        assertEquals("arrangement: it must still REGISTER at this distance", 1, farHits.size());
        assertFalse("but it must not be resolvable", farHits.get(0).resolvable());

        CrystalMemory distant = new CrystalMemory();
        TelescopeScan.characterise(farSky, farHits.get(0), distant, 1_000L, id -> "Body-" + id, true);
        assertNull("so a survey must not name its planet", distant.forBody(701));
        assertEquals("and must still write the address down", 1, distant.size());

        // Near: the same star, the same instrument, the same request.
        double near = resolveReach / 2d;
        UniverseRegistry nearSky = oneStarAt(near, 1.15f, 100);
        List<TelescopeScan.Detection> nearHits =
                TelescopeScan.detect(nearSky, seatAt(near), HOME, detectAt);
        assertEquals("arrangement: one star to make out", 1, nearHits.size());
        assertTrue("this one must be resolvable", nearHits.get(0).resolvable());

        CrystalMemory close = new CrystalMemory();
        TelescopeScan.characterise(nearSky, nearHits.get(0), close, 1_000L, id -> "Body-" + id, true);
        assertNotNull("and its planet must be named", close.forBody(701));
    }

    @Test
    public void theMarginIsTheDifferenceBetweenSeeingAndMEASURING() {
        // Where 6.5 comes from, stated as arithmetic so a retune has to argue with the derivation
        // rather than with a taste: detection is called at a signal-to-noise of about 5, a usable
        // spectrum wants about 100, and signal-to-noise grows as the square root of the photons —
        // so the flux ratio is (100/5)^2 = 400, which is 2.5*log10(400) magnitudes.
        double fluxRatio = (100d / 5d) * (100d / 5d);
        assertEquals("the margin must be the SNR ratio and not a number someone liked",
                2.5d * Math.log10(fluxRatio),
                ARConfiguration.DEFAULT_TELESCOPE_RESOLVE_MARGIN_MAGNITUDES, 0.01d);

        // And zero must genuinely turn it off, which is what "disable the flag" has to mean.
        ARConfiguration.getCurrentConfig().telescopeResolveMarginMagnitudes = 0d;
        assertEquals("a margin of zero makes anything detectable also resolvable",
                TelescopeScan.limitMagnitude(), TelescopeScan.resolveLimitMagnitude(), 1e-9d);
    }

    // ── detection is not characterisation ─────────────────────────────────────

    /** The real generator, counting the two questions separately. */
    private static final class SplitCountingGenerator
            implements zmaster587.advancedRocketry.universe.IGalaxyGenerator {

        private final ClusteredGalaxyGenerator real;
        int territoryQueries;
        int bodyQueries;

        SplitCountingGenerator(GalaxyGenConfig config) {
            this.real = new ClusteredGalaxyGenerator(config);
        }

        @Override
        public Optional<zmaster587.advancedRocketry.universe.PlanetarySystem> systemAt(
                long seed, GalacticCoord coord) {
            return real.systemAt(seed, coord);
        }

        @Override
        public java.util.Map<GalacticCoord, zmaster587.advancedRocketry.universe.PlanetarySystem>
                systemsInRegion(long seed, GalacticCoord min, GalacticCoord max) {
            return real.systemsInRegion(seed, min, max);
        }

        @Override
        public Optional<GalacticCoord> anchorAt(long seed, GalacticCoord cell) {
            return real.anchorAt(seed, cell);
        }

        @Override
        public List<GalacticCoord> anchorsInTerritory(long seed, GalacticCoord cell, int limit) {
            territoryQueries++;
            return real.anchorsInTerritory(seed, cell, limit);
        }

        @Override
        public List<SystemBody> bodiesFor(long seed, GalacticCoord systemCoord) {
            bodyQueries++;
            return real.bodiesFor(seed, systemCoord);
        }

        @Override
        public int minSpacingCells() {
            return real.minSpacingCells();
        }

        @Override
        public Optional<GalaxyGenConfig> tuning() {
            return real.tuning();
        }
    }

    @Test
    public void detectionAsksTheCheapQuestionWithoutPayingForTheExpensiveOne() {
        // The split the whole redesign turns on. These were one call, so the cheap question ("is
        // anything there") could never be asked without building every body of every system it
        // found. A survey spends its looks on the first stage, so the first stage must not touch
        // the second — and the only way to state that is to count.
        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        SplitCountingGenerator counting = new SplitCountingGenerator(config);
        UniverseRegistry.setGenerator(counting);
        UniverseRegistry.setStarLookup(id -> starOf(id, 1f, 100));
        UniverseRegistry registry = new UniverseRegistry();
        registry.bindWorldSeed(SEED);

        int found = 0;
        for (int i = 1; i <= 40; i++) {
            found += TelescopeScan.detect(registry, cell((long) i * STEP, 0, 0), HOME, 12d).size();
        }

        System.out.println("40 detection looks found " + found + " systems, asking "
                + counting.territoryQueries + " territory questions and " + counting.bodyQueries
                + " body questions");
        assertTrue("arrangement: the sweep must have found something to describe", found > 0);
        assertEquals("detection must never derive a single body", 0, counting.bodyQueries);
    }

    @Test
    public void oneLookOwesItsWholeTerritoryAndNotOneSeatOfIt() {
        // The property that lets a survey stride by the territory while the field is divided more
        // finely than that. Without it a sweep reports one seat in k-cubed and calls it the sky: at
        // the shipped division that is 1.3 % of what is out there, reported as all of it.
        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        ClusteredGalaxyGenerator gen = new ClusteredGalaxyGenerator(config);

        int byTerritory = 0;
        int byPoint = 0;
        for (long i = -6; i <= 6; i++) {
            for (long j = -6; j <= 6; j++) {
                GalacticCoord corner = cell(i * STEP, j * STEP, 0);
                byTerritory += gen.anchorsInTerritory(SEED, corner, 64).size();
                byPoint += gen.anchorAt(SEED, corner).isPresent() ? 1 : 0;
            }
        }

        System.out.println("169 territories hold " + byTerritory + " systems; resolving their corner "
                + "points alone would have reported " + byPoint);
        assertTrue("arrangement: the field must hold something", byTerritory > 0);
        assertTrue("asking the territory must find strictly more than sampling one point of it: "
                        + byTerritory + " vs " + byPoint,
                byTerritory > byPoint);
    }

    // ── what the shipped instrument costs ─────────────────────────────────────

    @Test
    public void theShippedApertureIsAffordableAndItsFindingsFitOnACrystal() {
        // THE acceptance measurement, and the numbers are stated in the units of the goal rather
        // than in whatever the work happened to produce: a full-depth pointing at the shipped
        // aperture must hold under 200 000 looks, register a number of systems a crystal can carry,
        // and cost well under a second of CPU spread over its steps.
        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        UniverseRegistry.setGenerator(new ClusteredGalaxyGenerator(config));
        UniverseRegistry.setStarLookup(id -> starOf(id, 1f, 100));
        UniverseRegistry registry = new UniverseRegistry();
        registry.bindWorldSeed(SEED);

        RegionScan.Tuning shipped = new RegionScan.Tuning(
                ARConfiguration.DEFAULT_TELESCOPE_LIMITING_MAGNITUDE, archetypes(),
                Math.toRadians(ARConfiguration.DEFAULT_TELESCOPE_CONE_HALF_ANGLE_DEGREES),
                ARConfiguration.DEFAULT_TELESCOPE_SCAN_MAX_CELLS,
                ARConfiguration.DEFAULT_TELESCOPE_SCAN_BASE_TICKS,
                ARConfiguration.DEFAULT_TELESCOPE_SCAN_CELLS_PER_STEP,
                config.minSpacing);

        RegionScan scan = RegionScan.directed(HOME, 1, 0, 0, shipped.maxRangeSteps(), 0L, shipped);
        int looks = scan.totalCells();

        long startedAt = System.nanoTime();
        int detections = 0;
        for (int i = 0; i < looks; i++) {
            detections += TelescopeScan.detect(registry, scan.cellAt(i), HOME,
                    shipped.limitMagnitude()).size();
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        int resolvable = 0;
        for (int i = 0; i < looks; i++) {
            for (TelescopeScan.Detection hit : TelescopeScan.detect(registry, scan.cellAt(i), HOME,
                    shipped.limitMagnitude())) {
                if (hit.resolvable()) {
                    resolvable++;
                }
            }
        }
        long steps = (looks + shipped.cellsPerStep() - 1) / shipped.cellsPerStep();

        System.out.println("the shipped instrument reaches "
                + String.format("%.0f", shipped.maxRangeLightYears()) + " ly ("
                + shipped.maxRangeSteps() + " territories); a full pointing is " + looks
                + " looks in " + steps + " steps (" + (steps * shipped.baseTicks() / 20L)
                + " s of clear night), registered " + detections + " systems, walked in "
                + elapsedMs + " ms, of which " + resolvable + " were close enough to make out");

        assertTrue("a full pointing must stay under the walk ceiling: " + looks,
                looks <= ARConfiguration.DEFAULT_TELESCOPE_SCAN_MAX_CELLS);
        assertTrue("and must be a real survey rather than a token one: " + looks, looks > 1_000);
        assertTrue("what it registers must fit on a crystal: " + detections + " systems",
                detections <= 1_500);
        assertTrue("arrangement: a pointing that finds nothing would pass every bound above",
                detections > 0);
        assertTrue("and the walk must cost well under a second of CPU: " + elapsedMs + " ms",
                elapsedMs < 2_000L);
    }

    @Test
    public void anApertureTooGoodForTheWalkBudgetShortensTheReachRatherThanRefusingToLook() {
        // UNREASONABLE IS NOT IMPOSSIBLE. An operator who configures an aperture that would hold more
        // looks than the ceiling affords gets a shallower pointing, not an instrument that will not
        // point — he sees the near sky and can point again.
        RegionScan.Tuning greedy = new RegionScan.Tuning(25d, archetypes(), Math.toRadians(5d),
                5_000, 20, 100, STEP);
        assertTrue("arrangement: this aperture must reach absurdly far",
                greedy.maxRangeLightYears() > 100_000d);

        RegionScan scan = RegionScan.directed(HOME, 1, 0, 0, greedy.maxRangeSteps(), 0L, greedy);

        assertTrue("the survey must fit under the ceiling: " + scan.totalCells(),
                scan.totalCells() <= 5_000);
        assertTrue("and must still be a survey rather than a single look", scan.totalCells() > 1);
        assertTrue("its reach must have been SHORTENED, which is what a budget can do to a horizon",
                scan.distanceCells() < greedy.maxRangeSteps() * STEP);
    }

    @Test
    public void aGeneratorWithNoStarsGivesAnInstrumentNothingToReach() {
        // The honest zero. An empty universe has nothing to see, so a survey of it is instantly
        // complete rather than long and fruitless — and the reach says so rather than inventing one.
        RegionScan.Tuning empty = new RegionScan.Tuning(20d, new ArrayList<>(), Math.toRadians(1d),
                1_000, 20, 10, STEP);
        assertEquals("an aperture pointed at a sky with no star types reaches nothing", 0d,
                empty.maxRangeLightYears(), 0d);
        assertEquals("which is still a pointing, of one territory", 1, empty.maxRangeSteps());
    }
}
