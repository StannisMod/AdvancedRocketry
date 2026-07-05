package zmaster587.advancedRocketry.test.integration;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.WorldInfo;
import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;
import zmaster587.advancedRocketry.world.weather.ARDimensionWorldInfo;
import zmaster587.advancedRocketry.world.weather.PlanetWeatherState;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ARDimensionWorldInfo} delegation contract.
 *
 * <ul>
 *   <li>(4) non-weather getters route to the delegate;</li>
 *   <li>(5) weather setters route only to the state, not the delegate;</li>
 * <li>(6) time-of-day / world age are per-dimension: owned by the
 *       state, seeded from the delegate, independent of the overworld clock;</li>
 *   <li>(7) weather + per-dim time mutations fire the dirty callback.</li>
 * </ul>
 *
 * Lives in the integration layer because constructing a vanilla {@link WorldInfo}
 * touches {@code GameRules} which requires {@code Bootstrap.register()}.
 */
public class ARDimensionWorldInfoTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private static WorldInfo seededDelegate() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setLong("RandomSeed", 4242L);
        nbt.setString("LevelName", "DelegateLevel");
        nbt.setLong("Time", 17000L);
        nbt.setLong("DayTime", 17000L);
        nbt.setInteger("SpawnX", 11);
        nbt.setInteger("SpawnY", 64);
        nbt.setInteger("SpawnZ", 22);
        return new WorldInfo(nbt);
    }

    private static ARDimensionWorldInfo wrap(WorldInfo delegate, PlanetWeatherState state, Runnable dirty) {
        return new ARDimensionWorldInfo(delegate, state, dirty, /* weatherManaged */ true);
    }

    @Test
    public void arWeatherWorldInfoDelegatesNonWeatherFields() {
        WorldInfo delegate = seededDelegate();
        PlanetWeatherState state = new PlanetWeatherState();
        ARDimensionWorldInfo wrapper = wrap(delegate, state, () -> {});

        assertEquals("seed must come from delegate", 4242L, wrapper.getSeed());
        assertEquals("worldName must come from delegate", "DelegateLevel", wrapper.getWorldName());
        assertEquals("spawnX delegated", 11, wrapper.getSpawnX());
        assertEquals("spawnY delegated", 64, wrapper.getSpawnY());
        assertEquals("spawnZ delegated", 22, wrapper.getSpawnZ());
        assertNotNull("game rules delegated", wrapper.getGameRulesInstance());
        assertSame("same game rules instance as delegate (no fresh GameRules)",
                delegate.getGameRulesInstance(), wrapper.getGameRulesInstance());
    }

    @Test
    public void arWeatherWorldInfoOverridesOnlyWeatherFields() {
        WorldInfo delegate = seededDelegate();
        PlanetWeatherState state = new PlanetWeatherState();
        ARDimensionWorldInfo wrapper = wrap(delegate, state, () -> {});

        // Pre-seed delegate weather to a DIFFERENT value than the wrapper —
        // proves the wrapper reads state, not delegate.
        delegate.setRaining(true);
        delegate.setRainTime(99999);
        delegate.setThundering(true);
        delegate.setThunderTime(99999);
        delegate.setCleanWeatherTime(99999);

        state.setRaining(false);
        state.setRainTime(123);
        state.setThundering(false);
        state.setThunderTime(456);
        state.setCleanWeatherTime(789);

        assertFalse("wrapper.isRaining reads state, not delegate", wrapper.isRaining());
        assertEquals(123, wrapper.getRainTime());
        assertFalse("wrapper.isThundering reads state, not delegate", wrapper.isThundering());
        assertEquals(456, wrapper.getThunderTime());
        assertEquals(789, wrapper.getCleanWeatherTime());

        // Writes through the wrapper must update state, not the delegate.
        wrapper.setRaining(true);
        wrapper.setRainTime(42);
        wrapper.setThundering(true);
        wrapper.setThunderTime(43);
        wrapper.setCleanWeatherTime(44);

        assertTrue("state.raining after wrapper.setRaining(true)", state.isRaining());
        assertEquals(42, state.getRainTime());
        assertTrue(state.isThundering());
        assertEquals(43, state.getThunderTime());
        assertEquals(44, state.getCleanWeatherTime());

        // Delegate's weather is untouched (still the pre-seeded values).
        assertTrue("delegate.raining unchanged by wrapper write", delegate.isRaining());
        assertEquals(99999, delegate.getRainTime());
        assertTrue(delegate.isThundering());
        assertEquals(99999, delegate.getThunderTime());
        assertEquals(99999, delegate.getCleanWeatherTime());
    }

    @Test
    public void arWeatherWorldInfoServesPerDimTimeIndependentOfDelegate() {
        WorldInfo delegate = seededDelegate(); // DayTime=17000, Time=17000
        PlanetWeatherState state = new PlanetWeatherState();
        ARDimensionWorldInfo wrapper = wrap(delegate, state, () -> {});

        // Per-dim time is seeded from the delegate on construction so
        // existing saves don't jump...
        assertEquals("per-dim worldTime seeded from delegate", 17000L, wrapper.getWorldTime());
        assertEquals("per-dim worldTotalTime seeded from delegate", 17000L, wrapper.getWorldTotalTime());

        // ...but then it is OWNED by the state, not delegated.
        wrapper.setWorldTime(50_000L);
        wrapper.setWorldTotalTime(60_000L);
        assertEquals(50_000L, wrapper.getWorldTime());
        assertEquals(60_000L, wrapper.getWorldTotalTime());
        assertEquals("state holds per-dim worldTime", 50_000L, state.getWorldTime());
        assertEquals("state holds per-dim worldTotalTime", 60_000L, state.getWorldTotalTime());

        // The overworld (delegate) clock advancing must NOT leak into the planet.
        delegate.setWorldTime(99_000L);
        delegate.setWorldTotalTime(99_000L);
        assertEquals("planet worldTime independent of overworld", 50_000L, wrapper.getWorldTime());
        assertEquals("planet worldTotalTime independent of overworld", 60_000L, wrapper.getWorldTotalTime());
    }

    @Test
    public void perDimTimeSettersMarkDirty() {
        WorldInfo delegate = seededDelegate();
        AtomicInteger dirtyHits = new AtomicInteger();
        ARDimensionWorldInfo wrapper = wrap(delegate, new PlanetWeatherState(), dirtyHits::incrementAndGet);

        wrapper.setWorldTime(1L);
        wrapper.setWorldTotalTime(1L);

        assertEquals("per-dim time setters must mark the saved-data dirty", 2, dirtyHits.get());
    }

    @Test
    public void unmanagedWeatherDelegatesToVanilla() {
        // When custom weather is disabled the wrapper is still installed (for
        // per-dim time) but weather must pass through to the delegate, matching
        // vanilla shared-weather behaviour.
        WorldInfo delegate = seededDelegate();
        PlanetWeatherState state = new PlanetWeatherState();
        ARDimensionWorldInfo wrapper =
                new ARDimensionWorldInfo(delegate, state, () -> {}, /* weatherManaged */ false);

        delegate.setRaining(true);
        delegate.setRainTime(555);
        state.setRaining(false);
        state.setRainTime(111);

        assertTrue("unmanaged weather reads the delegate", wrapper.isRaining());
        assertEquals(555, wrapper.getRainTime());

        wrapper.setRainTime(777);
        assertEquals("unmanaged weather writes the delegate", 777, delegate.getRainTime());
        assertEquals("per-dim weather state untouched when unmanaged", 111, state.getRainTime());

        // Time is per-dim even when weather is unmanaged.
        wrapper.setWorldTime(40_000L);
        assertEquals(40_000L, state.getWorldTime());
    }

    @Test
    public void arWeatherWorldInfoMarksDirtyOnWeatherMutation() {
        WorldInfo delegate = seededDelegate();
        PlanetWeatherState state = new PlanetWeatherState();
        AtomicInteger dirtyHits = new AtomicInteger();
        ARDimensionWorldInfo wrapper = wrap(delegate, state, dirtyHits::incrementAndGet);

        wrapper.setRaining(true);
        wrapper.setRainTime(1);
        wrapper.setThundering(true);
        wrapper.setThunderTime(1);
        wrapper.setCleanWeatherTime(1);

        assertEquals("every weather setter must trigger the dirty callback exactly once",
                5, dirtyHits.get());
    }

    @Test
    public void arWeatherWorldInfoDoesNotFireDirtyOnNonWeatherCalls() {
        // Non-weather setters are no-op / pass-through and must not pretend
        // anything happened from the weather subsystem's POV.
        WorldInfo delegate = seededDelegate();
        AtomicInteger dirtyHits = new AtomicInteger();
        ARDimensionWorldInfo wrapper = wrap(delegate, new PlanetWeatherState(), dirtyHits::incrementAndGet);

        wrapper.setWorldName("ignored");
        wrapper.setSaveVersion(7);

        assertEquals("non-weather, non-time mutations must NOT mark saved-data dirty",
                0, dirtyHits.get());
    }

    @Test
    public void getDelegateExposesUnderlyingForUnwrap() {
        WorldInfo delegate = seededDelegate();
        ARDimensionWorldInfo wrapper = wrap(delegate, new PlanetWeatherState(), () -> {});
        assertSame(delegate, wrapper.getDelegate());
    }
}
