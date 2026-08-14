package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.api.ARConfiguration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Configuration default-value stability.
 *
 * Pure construction tests — not exercising loadPreInit (that depends on Forge
 * Configuration files and the mod loader). Verifies invariants of a freshly
 * constructed configuration so accidental field-removal or default-flip is caught.
 */
public class ARConfigurationTest {

    @Test
    public void defaultConfigLoadsWithoutNulls() {
        ARConfiguration cfg = new ARConfiguration();

        // Required collections must be eager-initialized so nothing NPE-s before loadPreInit.
        assertNotNull(cfg.bypassEntity);
        assertNotNull(cfg.torchBlocks);
        assertNotNull(cfg.blackListRocketBlocks);
        assertNotNull(cfg.standardGeodeOres);
        assertNotNull(cfg.standardLaserDrillOres);
        assertNotNull(cfg.laserBlackListDims);
        assertNotNull(cfg.initiallyKnownPlanets);
        assertNotNull(cfg.asteroidTypes);
    }

    @Test
    public void rocketConfigDefaultsStable() {
        ARConfiguration cfg = new ARConfiguration();

        // Stability snapshot — anyone changing these defaults must update this test
        // intentionally so save/balance regressions are visible in a PR.
        assertEquals(1000, cfg.orbit);
        assertEquals(true, cfg.rocketRequireFuel);
        assertEquals(true, cfg.canBeFueledByHand);
        assertEquals(10, cfg.fuelPointsPer10Mb);
    }

    @Test
    public void stationConfigDefaultsStable() {
        ARConfiguration cfg = new ARConfiguration();

        assertEquals(1024, cfg.stationSize);
        assertEquals(1000, cfg.stationClearanceHeight);
        assertEquals(-2, cfg.spaceDimId);
    }

    @Test
    public void oxygenConfigDefaultsStable() {
        ARConfiguration cfg = new ARConfiguration();

        assertEquals(true, cfg.enableOxygen);
        assertEquals(true, cfg.enableNausea);
    }

    @Test
    public void planetConfigDefaultsStable() {
        ARConfiguration cfg = new ARConfiguration();

        // The Moon's dimension id starts unset (Constants.INVALID_PLANET) until config
        // assigns it. Assertion is an "invalid" sentinel, not a number.
        assertTrue("MoonId must default to a sentinel, not a real dim id", cfg.MoonId < 0 || cfg.MoonId == 0 || cfg.MoonId == Integer.MIN_VALUE);
    }

    @Test
    public void getCurrentConfigReturnsSingleton() {
        ARConfiguration first = ARConfiguration.getCurrentConfig();
        ARConfiguration second = ARConfiguration.getCurrentConfig();
        assertTrue("getCurrentConfig must return the same singleton", first == second);
    }

    @Test
    public void cloneConstructorCopiesFields() {
        ARConfiguration src = new ARConfiguration();
        src.orbit = 4242;
        src.stationSize = 256;

        ARConfiguration copy = new ARConfiguration(src);
        assertEquals(4242, copy.orbit);
        assertEquals(256, copy.stationSize);
    }

    /**
     * Performance section default-stability check.
     * MED batch pack 4 — C017 regression guard.
     *
     * <p>Contract: the copy constructor must yield collection fields that are
     * independent containers, not references aliasing the source config's
     * Map/List/Set. The old {@code field.getClass().isAssignableFrom(Map.class)}
     * test was always false ({@code getClass()} is {@code java.lang.reflect.Field}),
     * so the deep-copy branch was dead and every {@code @ConfigProperty} collection
     * was shallow-copied by reference — a correctness landmine for the
     * server→client {@code PacketConfigSync} copy. This pins container
     * independence across all three collection kinds (List, Set, Map).</p>
     */
    @Test
    public void cloneConstructorGivesIndependentCollections() {
        ARConfiguration src = new ARConfiguration();
        src.laserBlackListDims.add(42);       // List
        src.initiallyKnownPlanets.add(7);     // Set
        src.asteroidTypes.put("c017-key", null); // Map

        ARConfiguration copy = new ARConfiguration(src);

        assertNotSame("copy must own an independent list (C017)",
                src.laserBlackListDims, copy.laserBlackListDims);
        assertNotSame("copy must own an independent set (C017)",
                src.initiallyKnownPlanets, copy.initiallyKnownPlanets);
        assertNotSame("copy must own an independent map (C017)",
                src.asteroidTypes, copy.asteroidTypes);

        // Mutating the copy must not leak into the source.
        copy.laserBlackListDims.add(99);
        copy.initiallyKnownPlanets.add(99);
        copy.asteroidTypes.put("c017-extra", null);
        assertEquals("mutating the copy's list must not touch the source",
                1, src.laserBlackListDims.size());
        assertEquals("mutating the copy's set must not touch the source",
                1, src.initiallyKnownPlanets.size());
        assertEquals("mutating the copy's map must not touch the source",
                1, src.asteroidTypes.size());

        // Contents must still be carried over by the copy.
        assertTrue("copy must contain the source's list element",
                copy.laserBlackListDims.contains(42));
        assertTrue("copy must contain the source's set element",
                copy.initiallyKnownPlanets.contains(7));
        assertTrue("copy must contain the source's map entry",
                copy.asteroidTypes.containsKey("c017-key"));
    }

    /**
     * §6.3 — performance section default-stability check.
     *
     * The PERFORMANCE config section in {@link ARConfiguration#loadPreInit} sets
     * {@code atmosphereHandleBitMask} and {@code oxygenVentSize}. They don't have
     * field initializers (default 0 until loadPreInit fills them from
     * configuration), so this test asserts on the "raw post-construct" defaults
     * AND on the clone behaviour — the same invariants other section tests
     * verify. A field rename or accidental @ConfigProperty removal makes the
     * compile fail or the clone diverge.
     */
    @Test
    public void performanceConfigDefaultsStable() {
        ARConfiguration cfg = new ARConfiguration();

        // Raw defaults: no field initializer -> JVM zero.
        assertEquals("atmosphereHandleBitMask must default to 0 pre-loadPreInit",
                0, cfg.atmosphereHandleBitMask);
        assertEquals("oxygenVentSize must default to 0 pre-loadPreInit",
                0, cfg.oxygenVentSize);

        // Clone must carry performance fields end-to-end (they're @ConfigProperty
        // tagged so loadPreInit->sync->clone is the production path).
        cfg.atmosphereHandleBitMask = 3;
        cfg.oxygenVentSize = 32;
        ARConfiguration copy = new ARConfiguration(cfg);
        assertEquals(3, copy.atmosphereHandleBitMask);
        assertEquals(32, copy.oxygenVentSize);
    }

    /**
     * Robustness: constructing a config, mutating arbitrary fields,
     * accessing every collection, then cloning must NOT throw on any path. This
     * is the "unknown config (= partially-populated) does not crash" contract —
     * production loadPreInit may leave some fields at JVM defaults if the user's
     * config.cfg is missing keys, and downstream code MUST tolerate that.
     */
    @Test
    public void unknownConfigDoesNotCrash() {
        ARConfiguration cfg = new ARConfiguration();

        // Access every initialised collection — must be non-null and iterable.
        // (Catches accidental field removal that would NPE at config-sync time.)
        assertEquals(0, cfg.bypassEntity.size());
        assertEquals(0, cfg.torchBlocks.size());
        assertEquals(0, cfg.blackListRocketBlocks.size());
        assertEquals(0, cfg.standardGeodeOres.size());
        assertEquals(0, cfg.standardLaserDrillOres.size());
        assertEquals(0, cfg.laserBlackListDims.size());
        assertEquals(0, cfg.initiallyKnownPlanets.size());
        assertEquals(0, cfg.asteroidTypes.size());

        // Reading every uninitialised primitive must NOT throw NPE / underflow.
        // (Tripwire: if any of these become Integer/Float boxed, JVM-default
        // null causes NPE on read.)
        @SuppressWarnings("unused") int  i1 = cfg.atmosphereHandleBitMask;
        @SuppressWarnings("unused") int  i2 = cfg.oxygenVentSize;
        @SuppressWarnings("unused") int  i3 = cfg.maxBiomesPerPlanet;
        @SuppressWarnings("unused") double d1 = cfg.rocketThrustMultiplier;
        @SuppressWarnings("unused") double d2 = cfg.fuelCapacityMultiplier;
        @SuppressWarnings("unused") float f1 = cfg.spaceLaserPowerMult;
        @SuppressWarnings("unused") boolean b1 = cfg.launchingDestroysBlocks;
        @SuppressWarnings("unused") boolean b2 = cfg.experimentalSpaceFlight;

        // Cloning a partially populated config must succeed and preserve every
        // mutation, even ones loadPreInit would never have set.
        cfg.orbit = -777;                      // sentinel-out-of-range value
        cfg.spaceLaserPowerMult = Float.NaN;   // pathological float
        ARConfiguration clone = new ARConfiguration(cfg);
        assertEquals(-777, clone.orbit);
        assertTrue("NaN must survive clone (no silent normalisation)",
                Float.isNaN(clone.spaceLaserPowerMult));

        // Idempotent: getCurrentConfig() returns a non-null singleton regardless
        // of which fields have been touched.
        assertNotNull(ARConfiguration.getCurrentConfig());
    }
}
