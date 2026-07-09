package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * A harness-spawned test client must run SILENT. Automated client e2e boots a real client with
 * real audio on the dev box; {@code ClientProxy.muteTestClientSound} zeroes the master sound
 * level on the first client tick where the sound handler is up, gated on the
 * {@code -Dforge.test.client=true} marker that every {@code RealClientHarness} client carries
 * (and a manual {@code runClient} does not).
 *
 * <p>This observes the REAL client state: the proxy publishes the master level it read back
 * from {@code GameSettings} after muting, and this test asserts that value is 0 — so the test
 * fails if the mute is removed, mis-gated, or clamped, not merely if the code path is skipped.
 * No VS needed; runs on any {@code testClient} invocation.</p>
 */
public class TestClientSoundMutedE2ETest extends AbstractClientE2ETest {

    private static final String CLIENT_PROXY = "zmaster587.advancedRocketry.client.ClientProxy";

    @Test
    public void harnessTestClientHasMasterSoundMuted() throws Exception {
        bot().waitForWorld();

        // The mute lands on the first client tick with the sound handler up; poll until the
        // proxy has published the applied master level (NaN until then).
        String raw = "NaN";
        for (int i = 0; i < 40 && "NaN".equalsIgnoreCase(raw); i++) {
            bot().waitTicks(5);
            raw = bot().readStaticField(CLIENT_PROXY, "testClientMasterVolume")
                    .get("value").getAsString();
        }

        assertNotNull("proxy must publish the applied master volume", raw);
        float master = Float.parseFloat(raw);
        assertEquals("a harness test client must have master sound muted to 0",
                0.0f, master, 1e-6f);
    }
}
