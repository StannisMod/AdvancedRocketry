package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 3 — C048 (FIND-022) reproduction + regression guard.
 *
 * <p>Contract under test: {@link zmaster587.advancedRocketry.item.ItemWeatherController#useNetworkData}
 * is the server-authoritative apply point for a {@code PacketItemModifcation}
 * carrying a weather-controller mode + flood level. The 1..180 flood clamp and
 * the {0,1,2} mode clamp exist ONLY on the {@code @SideOnly(Side.CLIENT)} button
 * path — the server must re-validate, because a modified client can send any
 * value.</p>
 *
 * <p>The probe encodes arbitrary ints into a {@code ByteBuf} exactly as the
 * client would and runs the real {@code readDataFromNetwork -> useNetworkData}
 * server receive path. Pre-fix the server stores the raw client values (an
 * out-of-range flood level then drives the unbounded flood loop in
 * {@code performAction}, a main-thread hang / OOM DoS, and corrupts the saved
 * satellite state); post-fix the server clamps mode to {0,1,2} and flood to
 * [1,180] before applying.</p>
 */
public class WeatherControllerPacketValidationTest extends AbstractSharedServerTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern MODE = Pattern.compile("\"mode_id\":(-?\\d+)");
    private static final Pattern FLOOD = Pattern.compile("\"floodlevel\":(-?\\d+)");

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

    private static int intField(Pattern p, String src, String name) {
        Matcher m = p.matcher(src);
        assertTrue(name + " missing in: " + src, m.find());
        return Integer.parseInt(m.group(1));
    }

    /** An out-of-range flood level (and an out-of-range mode) from the wire
     *  must be clamped to the valid domain before being applied to the live
     *  satellite. */
    @Test
    public void serverClampsOutOfRangeFloodLevelAndMode() throws Exception {
        long satId = createWeatherSat();

        String apply = ok(client().execute(
                "artest satellite weather-apply 0 " + satId + " 5 100000"));
        assertTrue("weather-apply failed: " + apply, apply.contains("\"ok\":true"));

        int mode = intField(MODE, apply, "mode_id");
        int flood = intField(FLOOD, apply, "floodlevel");

        assertTrue("server must clamp the flood level to <= 180 (got " + flood
                        + "); an out-of-range value drives the unbounded flood "
                        + "loop DoS (C048): " + apply,
                flood <= 180);
        assertTrue("server must clamp the flood level to >= 1 (got " + flood + "): " + apply,
                flood >= 1);
        assertTrue("server must reject an out-of-range mode id, keeping it in "
                        + "{0,1,2} (got " + mode + "): " + apply,
                mode >= 0 && mode <= 2);
    }

    /** A negative flood level from the wire must be clamped up to the minimum. */
    @Test
    public void serverClampsNegativeFloodLevel() throws Exception {
        long satId = createWeatherSat();

        String apply = ok(client().execute(
                "artest satellite weather-apply 0 " + satId + " 1 -50"));
        assertTrue("weather-apply failed: " + apply, apply.contains("\"ok\":true"));

        int flood = intField(FLOOD, apply, "floodlevel");
        assertTrue("a negative flood level must clamp to >= 1 (got " + flood + "): " + apply,
                flood >= 1);
    }

    /** Counter-test: an in-range packet must pass through unchanged — the
     *  server-side clamp must not over-constrain legitimate values. */
    @Test
    public void serverAcceptsInRangeValuesUnchanged() throws Exception {
        long satId = createWeatherSat();

        String apply = ok(client().execute(
                "artest satellite weather-apply 0 " + satId + " 2 90"));
        assertTrue("weather-apply failed: " + apply, apply.contains("\"ok\":true"));

        int mode = intField(MODE, apply, "mode_id");
        int flood = intField(FLOOD, apply, "floodlevel");
        assertTrue("an in-range mode (2) must be preserved, got " + mode + ": " + apply,
                mode == 2);
        assertTrue("an in-range flood level (90) must be preserved, got " + flood + ": " + apply,
                flood == 90);
    }
}
