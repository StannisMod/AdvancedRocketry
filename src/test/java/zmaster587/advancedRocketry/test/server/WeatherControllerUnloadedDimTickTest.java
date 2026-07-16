package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 3 — C062 (FIND-031) reproduction + regression guard.
 *
 * <p>Contract under test: {@link zmaster587.advancedRocketry.satellite.SatelliteWeatherController#tickEntity()}
 * must not crash the server tick when its planet dimension is unloaded
 * ({@code net.minecraftforge.common.DimensionManager.getWorld(dimId) == null})
 * while {@code viable_positions} still has queued work.</p>
 *
 * <p>Why this is reachable: {@code DimensionManager.tickDimensions} iterates
 * {@code getLoadedDimensions()}, which returns {@code getRegisteredDimensions()}
 * — every registered AR planet, loaded or not — and calls {@code tickEntity} on
 * each satellite with NO surrounding try/catch. A player who floods a planet
 * (populating {@code viable_positions}, which is NOT persisted) and then leaves
 * so the world unloads before the queue drains hits a null {@code world} deref
 * → uncaught NPE in the {@code ServerTickEvent} handler → server-tick crash.</p>
 *
 * <p>The probe drives the real {@code tickEntity} against a genuinely unloaded
 * dim id. Pre-fix the deref throws (the probe reports the NPE via the top-level
 * {@code /artest} error envelope); post-fix an early null-world guard returns
 * cleanly and the queued position is preserved for the next reload.</p>
 */
public class WeatherControllerUnloadedDimTickTest extends AbstractSharedServerTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern LIST_AFTER = Pattern.compile("\"listSizeAfter\":(-?\\d+)");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private long createWeatherSat() throws Exception {
        String resp = ok(client().execute("artest satellite create 0 weatherController 100 1000 1000"));
        assertTrue("weather satellite create failed: " + resp, resp.contains("\"ok\":true"));
        Matcher m = ID.matcher(resp);
        assertTrue("no id in create response: " + resp, m.find());
        return Long.parseLong(m.group(1));
    }

    /**
     * Rain mode (0), one queued position, dimension unloaded → the tick must
     * complete without an NPE and leave the queued position untouched.
     *
     * <p>The probe seeds the queue and ticks atomically within one command so
     * the background {@code DimensionManager} tick on the loaded home dim cannot
     * drain the queue before the null-world deref path is reached.</p>
     */
    @Test
    public void tickWithUnloadedWorldDoesNotCrashAndPreservesQueue() throws Exception {
        long satId = createWeatherSat();

        String resp = ok(client().execute("artest satellite weather-tick-unloaded 0 " + satId));

        assertFalse("ticking a weather controller with an unloaded world must not "
                        + "NPE (C062): " + resp,
                resp.contains("NullPointerException"));
        assertTrue("weather-tick-unloaded must succeed post-fix: " + resp,
                resp.contains("\"ok\":true"));

        Matcher m = LIST_AFTER.matcher(resp);
        assertTrue("listSizeAfter missing: " + resp, m.find());
        int after = Integer.parseInt(m.group(1));
        assertEquals("the null-world guard must return before consuming any queued "
                        + "position (the queue drains when the world reloads): " + resp,
                1, after);
    }
}
