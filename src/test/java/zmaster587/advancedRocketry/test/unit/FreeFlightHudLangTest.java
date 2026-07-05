package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins that every Free Flight HUD lang key ships in en_US.lang. A missing key
 * would surface to players as the raw {@code msg.ff.hud.*} string (I18n echoes
 * unknown keys), so this is a cheap regression guard that runs without booting
 * a client. The rendered-text contract itself is covered by
 * {@code FreeFlightModeE2ETest} (real client HUD readback).
 */
public class FreeFlightHudLangTest {

    private static final String[] KEYS = {
            "msg.ff.hud.title", "msg.ff.hud.prelaunch", "msg.ff.hud.active",
            "msg.ff.hud.fa.on", "msg.ff.hud.fa.off", "msg.ff.hud.move",
            "msg.ff.hud.yaw", "msg.ff.hud.vert",
            "msg.ff.hud.brake", "msg.ff.hud.assist",
            // Engine start
            "msg.ff.hud.engines.off", "msg.ff.hud.engines.starting",
            "msg.ff.hud.engines.on", "msg.ff.engines.started",
            "msg.ff.engines.stopped",
            // HUD indication
            "msg.ff.hud.vector", "msg.ff.hud.speed",
    };

    @Test
    public void enUsDefinesAllFreeFlightHudKeys() throws Exception {
        String body;
        try (java.io.InputStream is = getClass()
                .getResourceAsStream("/assets/advancedrocketry/lang/en_US.lang")) {
            assertNotNull("en_US.lang must be on the test classpath", is);
            java.util.Scanner sc = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            body = sc.hasNext() ? sc.next() : "";
        }
        for (String key : KEYS) {
            assertTrue("en_US.lang must define " + key, body.contains(key + "="));
        }
    }
}
