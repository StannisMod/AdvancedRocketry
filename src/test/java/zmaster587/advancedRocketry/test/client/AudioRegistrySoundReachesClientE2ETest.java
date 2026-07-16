package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * C035 (FIND-014) — a declared AR sound played by the server must reach the
 * real client's {@code SoundManager}.
 *
 * <p><b>What this validates end-to-end</b>: the server plays
 * {@code AudioRegistry.combustionRocket} at the player's feet via the
 * {@code artest sound play} probe — the same static-field
 * {@code world.playSound} call the production sites use (rocket engine,
 * railgun, machine loops). Vanilla encodes the event's registry id into
 * {@code SPacketSoundEffect}; the client decodes it and asks its
 * {@code SoundManager} to play. The client-side {@code PlaySoundEvent}
 * recorder ({@code report_sounds}, forge-test-framework) observes exactly
 * that hand-off on the real client — the honest "did it reach the player's
 * speakers" surface.</p>
 *
 * <p>An UNREGISTERED SoundEvent encodes as registry id -1, decodes to
 * {@code null}, and the client's scheduled-task executor swallows the
 * resulting NPE — the sound silently never plays (and the client must stay
 * connected). That is today's player-facing symptom for 14 of the 15
 * declared AR sounds.</p>
 *
 * <p>Repro history: pre-fix this test pinned the wrong behaviour
 * ({@code combustionRocket} unregistered, the client SoundManager never asked
 * to play it, client surviving the null-sound packet); flipped to the
 * corrected contract with the C035 fix (bug-report-workflow Path B).</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class AudioRegistrySoundReachesClientE2ETest extends AbstractClientE2ETest {

    /** ResourceLocation lowercases paths in this MC build, so the client-side
     *  observation (ISound.getSoundLocation) is all-lowercase regardless of the
     *  mixed-case sounds.json key. */
    private static final String COMBUSTION = "advancedrocketry:combustionrocket";

    @Test
    public void serverPlayedArSoundReachesClientSoundManager() throws Exception {
        // Pin the player at a known spot so the 16-block sound broadcast
        // radius trivially covers the play position.
        serverClient().execute("tp @a 8.5 79 8.5");
        bot().waitTicks(5);
        bot().clearSounds();

        // Precondition: PlaySoundEvent only fires when the client sound system
        // initialised (SoundManager.loaded). Without an audio device NOTHING is
        // ever recorded — skip instead of misdiagnosing that as a registration
        // regression (flake-diagnosis SOP).
        org.junit.Assume.assumeTrue(
                "client sound system not loaded (no audio device?) — PlaySoundEvent "
                        + "cannot be observed on this host",
                bot().reportSounds().get("managerLoaded").getAsBoolean());

        String played = String.join("\n", serverClient().execute(
                "artest sound play 0 8 79 8 combustionRocket"));
        assertTrue("sound play probe failed: " + played, played.contains("\"ok\":true"));
        assertTrue("combustionRocket must be present in ForgeRegistries at send time: "
                + played, played.contains("\"registered\":true"));

        // Contract: the sound reaches the real client's SoundManager — the
        // client-side PlaySoundEvent recorder observes the play request.
        boolean seen = false;
        for (int waited = 0; waited < 100 && !seen; waited += 20) {
            bot().waitTicks(20);
            seen = soundsContain(bot().reportSounds(), COMBUSTION);
        }
        assertTrue("server-played " + COMBUSTION + " never reached the client "
                + "SoundManager (PlaySoundEvent recorder saw: "
                + bot().reportSounds() + ")", seen);

        // And the round-trip must leave the client healthy.
        JsonObject state = bot().reportState();
        assertNotNull("client bridge should still respond after the sound packet",
                state);
    }

    private static boolean soundsContain(JsonObject reportSounds, String location) {
        JsonArray sounds = reportSounds.getAsJsonArray("sounds");
        if (sounds == null) {
            return false;
        }
        for (JsonElement element : sounds) {
            if (location.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }
}
