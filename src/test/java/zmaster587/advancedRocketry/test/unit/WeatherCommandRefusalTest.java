package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.command.sub.redirect.WeatherCommand;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Decision table for {@code /advancedrocketry weather} refusals — the
 * better_weather ↔ 1.12 integration that makes the manual weather command aware
 * of the marker / atmosphere policy it would otherwise lose to.
 *
 * <p>{@code WorldProviderPlanet.updateWeather()} re-applies the planet's weather
 * markers and atmosphere gate every tick, so a manual command that fights them
 * is reverted on the next tick. {@link WeatherCommand#weatherRefusalKey} refuses
 * such commands up front; this pins that decision (a pure function, so no
 * harness needed).</p>
 *
 * <p>Marker convention: {@code -1} = never, {@code 0} = dynamic, {@code +1} =
 * always. {@code canRain} = atmosphere density ≥ {@code minAtmosphereDensityForRain}.</p>
 */
public class WeatherCommandRefusalTest {

    // ── rain ──────────────────────────────────────────────────────────────
    @Test
    public void rainAllowedWhenDynamicMarkerAndAtmosphereOk() {
        assertNull(WeatherCommand.weatherRefusalKey("rain", 0, 0, true));
    }

    @Test
    public void rainRefusedByNeverMarker() {
        assertEquals("commands.weather.cannot_rain",
                WeatherCommand.weatherRefusalKey("rain", -1, 0, true));
    }

    @Test
    public void rainRefusedByThinAtmosphere() {
        assertEquals("commands.weather.cannot_rain_atmosphere",
                WeatherCommand.weatherRefusalKey("rain", 0, 0, false));
    }

    @Test
    public void neverMarkerTakesPriorityOverAtmosphereMessageForRain() {
        // The marker is checked first, so its message wins even when the
        // atmosphere is ALSO too thin — both would refuse, the marker explains it.
        assertEquals("commands.weather.cannot_rain",
                WeatherCommand.weatherRefusalKey("rain", -1, 0, false));
    }

    // ── thunder (coupled to rain) ─────────────────────────────────────────
    @Test
    public void thunderAllowedWhenDynamicAndAtmosphereOk() {
        assertNull(WeatherCommand.weatherRefusalKey("thunder", 0, 0, true));
    }

    @Test
    public void thunderRefusedByNeverThunderMarker() {
        assertEquals("commands.weather.cannot_thunder",
                WeatherCommand.weatherRefusalKey("thunder", 0, -1, true));
    }

    @Test
    public void thunderRefusedWhenRainImpossibleByMarker() {
        assertEquals("commands.weather.cannot_thunder_norain",
                WeatherCommand.weatherRefusalKey("thunder", -1, 0, true));
    }

    @Test
    public void thunderRefusedWhenRainImpossibleByAtmosphere() {
        assertEquals("commands.weather.cannot_thunder_norain",
                WeatherCommand.weatherRefusalKey("thunder", 0, 0, false));
    }

    // ── clear ─────────────────────────────────────────────────────────────
    @Test
    public void clearAllowedWhenNoForcingMarker() {
        assertNull(WeatherCommand.weatherRefusalKey("clear", 0, 0, true));
        // A "never rain/thunder" planet is already clear — clear is allowed.
        assertNull(WeatherCommand.weatherRefusalKey("clear", -1, -1, false));
    }

    @Test
    public void clearRefusedByAlwaysRainMarker() {
        assertEquals("commands.weather.always_not_clear",
                WeatherCommand.weatherRefusalKey("clear", 1, 0, true));
    }

    @Test
    public void clearRefusedByAlwaysThunderMarker() {
        assertEquals("commands.weather.always_not_clear",
                WeatherCommand.weatherRefusalKey("clear", 0, 1, true));
    }
}
