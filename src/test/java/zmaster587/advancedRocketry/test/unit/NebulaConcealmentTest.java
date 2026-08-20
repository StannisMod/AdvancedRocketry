package zmaster587.advancedRocketry.test.unit;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.junit.After;
import org.junit.Test;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.navigation.CrystalMemory;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.EmptyGalaxyGenerator;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.IGalaxyGenerator;
import zmaster587.advancedRocketry.universe.Nebula;
import zmaster587.advancedRocketry.universe.PlanetarySystem;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.TelescopeScan;
import zmaster587.advancedRocketry.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * What a cloud between an observer and a system costs the look.
 *
 * <p>These pin the player-facing promise and the physics it is stated in: a survey through dust
 * still learns that something is THERE (the address), and stops being able to say what (the bodies).
 * The mechanic is a reason to fly somewhere rather than survey it from home, so what it may never do
 * is make a system vanish — that is indistinguishable from an empty sky, which is the exact defect
 * this instrument carried until a survey learned to resolve a look through the system that OWNS the
 * cell it looked at.</p>
 *
 * <p>The THRESHOLD is a tunable and nothing here pins its shipped value; what is pinned is that the
 * threshold is read in magnitudes, that it is honoured, and that turning it off restores the clear
 * sky exactly.</p>
 */
public class NebulaConcealmentTest {

    private static final long STEP = GalaxyGenConfig.DEFAULT_MIN_SPACING;

    /** Where the observer stands, and where the system it is looking at is seated. */
    private static final GalacticCoord HOME = GalacticCoord.ORIGIN;
    private static final GalacticCoord TARGET = GalacticCoord.ofSectorLocal(4 * STEP, 0, 0, 0, 0, 0);

    private double previousThreshold;

    private static StellarBody star(int id) {
        StellarBody s = new StellarBody();
        s.setId(id);
        s.setName("Star-" + id);
        return s;
    }

    /**
     * A generator that reports a stated column of dust between ANY two points, and no systems of its
     * own — so what a look loses is decided by the column alone.
     */
    private static IGalaxyGenerator dustyBy(final double columnDensityLightYears) {
        return new IGalaxyGenerator() {
            @Override
            public Optional<PlanetarySystem> systemAt(long seed, GalacticCoord coord) {
                return Optional.empty();
            }

            @Override
            public Map<GalacticCoord, PlanetarySystem> systemsInRegion(long seed, GalacticCoord min,
                                                                  GalacticCoord max) {
                return Collections.emptyMap();
            }

            @Override
            public double columnDensityBetween(long seed, GalacticCoord from, GalacticCoord to) {
                return columnDensityLightYears;
            }
        };
    }

    /** A registry holding one system with a named planet, seated at {@link #TARGET}. */
    private static UniverseRegistry oneSystem() {
        UniverseRegistry.setStarLookup(NebulaConcealmentTest::star);
        UniverseRegistry registry = new UniverseRegistry();
        registry.place(TARGET, 4);
        registry.addPoi(SystemBody.fixedAt(TARGET, SystemBodyKind.STAR, Constants.INVALID_PLANET, 4));
        registry.addPoi(SystemBody.fixedAt(TARGET, SystemBodyKind.PLANET, 401, 4));
        return registry;
    }

    private static int look(UniverseRegistry registry, CrystalMemory crystal) {
        // An aperture nothing in this fixture can fall below, because what is under test is the
        // DUST and not the brightness: a limit that also gated the look would make "the dusty case
        // named nothing" true for two reasons and pin neither.
        return TelescopeScan.resolveLook(registry, TARGET, crystal, 7_000L,
                dimId -> "Body-" + dimId, HOME, Double.POSITIVE_INFINITY, true);
    }

    /** The column, in density-light-years, that the shipped threshold sits at. */
    private static double columnAtThreshold() {
        return zmaster587.advancedRocketry.api.ARConfiguration.getCurrentConfig()
                .telescopeObscuredAtMagnitudes / Nebula.MAGNITUDES_PER_DENSITY_LIGHT_YEAR;
    }

    @org.junit.Before
    public void armThreshold() {
        previousThreshold = zmaster587.advancedRocketry.api.ARConfiguration.getCurrentConfig()
                .telescopeObscuredAtMagnitudes;
        // A stated threshold, so nothing here depends on the shipped default staying put.
        zmaster587.advancedRocketry.api.ARConfiguration.getCurrentConfig()
                .telescopeObscuredAtMagnitudes = 5d;
    }

    @After
    public void restoreSeams() {
        zmaster587.advancedRocketry.api.ARConfiguration.getCurrentConfig()
                .telescopeObscuredAtMagnitudes = previousThreshold;
        UniverseRegistry.setGenerator(null);
        UniverseRegistry.setStarLookup(null);
    }

    @Test
    public void aClearSightLineNamesTheBodies() {
        // The control. Without it "the dusty case names nothing" would be a statement about a
        // fixture that never named anything.
        UniverseRegistry.setGenerator(new EmptyGalaxyGenerator());
        UniverseRegistry registry = oneSystem();
        CrystalMemory crystal = new CrystalMemory();

        look(registry, crystal);

        assertNotNull("a look through clear space must name the system's planet", crystal.forBody(401));
    }

    @Test
    public void aLookThroughThickDustLearnsTheADDRESSAndNotTheBODIES() {
        // THE mechanic. The operator is left knowing there is something out there and having to go
        // and see what — which is the reason to fly rather than survey.
        UniverseRegistry.setGenerator(dustyBy(columnAtThreshold() * 2d));
        UniverseRegistry registry = oneSystem();
        CrystalMemory crystal = new CrystalMemory();

        int written = look(registry, crystal);

        assertTrue("an obscured look must still write something: a system that VANISHES is"
                + " indistinguishable from empty sky, which is the defect this whole path had",
                written >= 1);
        assertEquals("and what it writes is one bare address, not a body list", 1, crystal.size());
        assertTrue("the system's planet must NOT be named through the dust",
                crystal.forBody(401) == null);
    }

    @Test
    public void thinDustDoesNotHideAnything() {
        // The other side of the threshold, so "obscured" is a property of how much dust there is and
        // not of there being any.
        UniverseRegistry.setGenerator(dustyBy(columnAtThreshold() * 0.5d));
        UniverseRegistry registry = oneSystem();
        CrystalMemory crystal = new CrystalMemory();

        look(registry, crystal);

        assertNotNull("a cloud below the threshold must not cost the look its detail",
                crystal.forBody(401));
    }

    @Test
    public void theThresholdIsReadInMagnitudes() {
        // The unit is the contract: the config states extinction, and the calibration from this
        // model's density to magnitudes lives in one place.
        UniverseRegistry.setGenerator(dustyBy(columnAtThreshold()));
        UniverseRegistry registry = oneSystem();

        double magnitudes = registry.extinctionBetween(HOME, TARGET);
        assertEquals("a column at the threshold must read as the configured magnitudes",
                zmaster587.advancedRocketry.api.ARConfiguration.getCurrentConfig()
                        .telescopeObscuredAtMagnitudes,
                magnitudes, 1.0E-6d);
        assertTrue("and must be judged obscured at exactly that reading",
                TelescopeScan.isObscured(registry, HOME, TARGET));
    }

    @Test
    public void turningTheThresholdOffRestoresTheClearSky() {
        // A config flag has to REMOVE its mechanic, not soften it. Zero is the off switch, because
        // "obscured at zero magnitudes" would otherwise mean everything is always hidden.
        UniverseRegistry.setGenerator(dustyBy(columnAtThreshold() * 100d));
        UniverseRegistry registry = oneSystem();
        zmaster587.advancedRocketry.api.ARConfiguration.getCurrentConfig()
                .telescopeObscuredAtMagnitudes = 0d;
        CrystalMemory crystal = new CrystalMemory();

        assertFalse("with the mechanic off nothing is obscured, however thick the dust",
                TelescopeScan.isObscured(registry, HOME, TARGET));
        look(registry, crystal);
        assertNotNull("and the survey names bodies exactly as it did before the feature existed",
                crystal.forBody(401));
    }

    @Test
    public void aLookWithNoStatedObserverIsNeverObscured() {
        // A caller that cannot say where it is standing cannot claim a sight line either. This is
        // what keeps every pre-existing call site behaving exactly as it did.
        UniverseRegistry.setGenerator(dustyBy(columnAtThreshold() * 100d));
        UniverseRegistry registry = oneSystem();
        CrystalMemory crystal = new CrystalMemory();

        TelescopeScan.resolveCell(registry, TARGET, crystal, 7_000L, dimId -> "Body-" + dimId);

        assertNotNull("an observer-less look must resolve as it always did", crystal.forBody(401));
    }

    @Test
    public void extinctionIsZeroInAUniverseWithNoClouds() {
        // The negative leg for the physics itself: no clusters, no gas, no dimming — and no
        // fabricated column from a generator that has none.
        UniverseRegistry.setGenerator(new EmptyGalaxyGenerator());
        UniverseRegistry registry = oneSystem();

        assertEquals("clear space dims nothing", 0d, registry.extinctionBetween(HOME, TARGET),
                1.0E-9d);
    }
}
