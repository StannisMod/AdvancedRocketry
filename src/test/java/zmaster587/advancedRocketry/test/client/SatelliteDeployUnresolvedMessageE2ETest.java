package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Repro (bug-report-workflow Step 1) for finding C151 / FIND-200 (LOW) — the
 * player-visible client side.
 *
 * <p>On orbit reach {@code EntityRocket.unpackSatellites} deploys each satellite
 * hatch. A hatch holding a chassis whose type no longer resolves
 * ({@code getSatellite()} returns null and it is not a station) fell through
 * every branch — the satellite was silently not deployed and the pilot got no
 * feedback.</p>
 *
 * <p>The natural trigger (a rocket reaching orbit with such a chassis) is not
 * producible in a stable single-config game, so the deploy path is driven one
 * hatch at a time via the {@code artest satellite deploy-unresolved} probe: it
 * mounts this real client on a rocket and calls the extracted, public
 * {@code EntityRocket.deploySatelliteFromHatch} with a bare (unresolvable)
 * satellite chassis. Observation is REAL client state — the pilot's chat, read
 * i18n-resolved via {@code report_chat}.</p>
 *
 * <p><b>Corrected contract, pinned here (C151 fix, Path B)</b>: the pilot is
 * told the satellite could not be deployed (instead of silence).</p>
 */
public class SatelliteDeployUnresolvedMessageE2ETest extends AbstractClientE2ETest {

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void unresolvedSatelliteDeployNotifiesPilot() throws Exception {
        bot().waitForWorld();

        String resp = exec("artest satellite deploy-unresolved");
        assertTrue("deploy-unresolved probe must succeed: " + resp, resp.contains("\"ok\":true"));
        assertTrue("the probe must have mounted the pilot: " + resp, resp.contains("\"mounted\":true"));

        boolean seen = false;
        String chat = "";
        for (int waited = 0; waited < 8000 && !seen; waited += 500) {
            bot().waitTicks(5);
            JsonObject c = bot().reportChat(30);
            chat = c.toString();
            // en_US: "A satellite could not be deployed: its type is no longer available..."
            if (chat.contains("could not be deployed") || chat.contains("no longer available")) {
                seen = true;
            }
        }
        assertTrue("pilot must receive the satellite-deploy-failed message in chat; got " + chat,
                seen);
    }
}
