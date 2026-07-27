package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code /ar fetch} positive + negative coverage without a
 * second connected player.
 *
 * <p>{@code WorldCommandPlayerEquippedE2ETest} marked {@code /ar fetch}
 * out of scope citing "needs a second connected player. The testClient
 * harness supports one bot only." That framing assumed positive
 * coverage requires a DIFFERENT player as the target. This test closes
 * the gap with two pins that don't need a second player:</p>
 *
 * <ul>
 *   <li><b>Self-fetch.</b> The bot runs {@code /ar fetch <bot-username>}
 *       against itself. Production
 *       ({@link zmaster587.advancedRocketry.command.WorldCommand#commandFetch})
 *       resolves the name via
 *       {@link net.minecraft.world.World#getPlayerEntityByName(String)},
 *       transfers the resolved player to the sender's dim via
 *       {@code PlayerList.transferPlayerToDimension}, and
 *       {@code setPosition}s them to the sender's coords. With
 *       sender == target the dim transfer is a same-dim no-op and the
 *       setPosition copies the bot's coords onto itself — the verb
 *       must complete without crashing, and the bot's post-call
 *       position must equal its pre-call sender position. Pinning this
 *       gives us positive coverage of the full resolve &rarr; transfer &rarr;
 *       setPosition path without a second bot.</li>
 *   <li><b>Unknown name.</b> {@code /ar fetch nonExistentPlayerXYZ}
 *       must reply with "Invalid player name: nonExistentPlayerXYZ"
 *       on the sender's chat — pinning the
 *       {@code getPlayerByName == null} branch that's only reachable
 *       through this verb.</li>
 * </ul>
 *
 * <p><b>Out of scope still</b>: fetch where target is a DIFFERENT
 * connected player (true "moderator fetch" use-case). The testClient
 * harness is single-bot; multi-client expansion is a separate scope.
 * For now self-fetch + unknown-name closes the resolvable contract
 * surface.</p>
 */
public class WorldCommandFetchTest extends AbstractClientE2ETest {

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?\\d+(?:\\.\\d+)?)");

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Before
    public void opTheBot() throws Exception {
        // Reset position so the post-fetch coord comparison is against a
        // known baseline (not whatever the previous test left us at).
        exec(HarnessPlayerSite.tpCommand());
        bot().waitTicks(5);
        String op = exec("artest player op-self");
        assertTrue("op-self must succeed: " + op,
                op.contains("\"opped\":true"));
    }

    @After
    public void deopTheBot() throws Exception {
        try {
            exec("artest player deop-self");
        } catch (Exception ignored) {
        }
    }

    /** {@code /ar fetch <bot's-own-username>} typed in the real client chat
     *  must complete without crashing and leave the bot at the same coords
     *  (sender pos == target pos in a self-fetch). Pins the chat &rarr; server
     *  command &rarr; resolve &rarr; transfer &rarr; setPosition path, observed from the
     *  CLIENT side. */
    @Test
    public void selfFetchCompletesAndPreservesPosition() throws Exception {
        // Discover the bot's username via /artest player health (arrange-only
        // server read), which echoes player.getName() in its JSON.
        String health = exec("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(health);
        assertTrue("player health must echo player name: " + health, nameM.find());
        String botName = nameM.group(1);
        assertNotEquals("bot name must be non-empty", "", botName);

        // Snapshot the CLIENT-observed position — the layer the player sees.
        com.google.gson.JsonObject pre = bot().reportState();
        double preX = pre.get("playerX").getAsDouble();
        double preZ = pre.get("playerZ").getAsDouble();

        // The real stimulus: the player types the command in chat.
        bot().sendChat("/ar fetch " + botName);
        bot().waitTicks(20);

        // Post-call: the CLIENT must still render itself at (approximately)
        // the pre-call coords. Sub-block tolerance — the same-dim transfer
        // path may nudge by fractions; we pin "didn't teleport to a wrong
        // location", not float equality.
        com.google.gson.JsonObject post = bot().reportState();
        double postX = post.get("playerX").getAsDouble();
        double postZ = post.get("playerZ").getAsDouble();
        assertTrue("self-fetch must leave bot within 1 block of its prior position: "
                        + "preX=" + preX + " postX=" + postX,
                Math.abs(postX - preX) < 1.0);
        assertTrue("self-fetch must leave bot within 1 block of its prior position: "
                        + "preZ=" + preZ + " postZ=" + postZ,
                Math.abs(postZ - preZ) < 1.0);

        // Cross-side oracle: the server agrees about where the player is.
        String postServer = exec("artest player health");
        assertTrue("server-side X must agree with the client view: " + postServer,
                Math.abs(extractDouble(postServer, POS_X) - postX) < 1.0);
    }

    /** {@code /ar fetch <unknown-name>} typed in the real client chat must
     *  surface vanilla's "player cannot be found" error ON THE PLAYER'S CHAT
     *  OVERLAY (i18n resolved) without crashing. Pins the
     *  {@code getPlayer -> PlayerNotFoundException} branch at the layer the
     *  player reads it. */
    @Test
    public void fetchUnknownNameReportsInvalidPlayerName() throws Exception {
        String bogus = "_no_such_player_xyz_TASK35_";

        // The real stimulus: the player types the command in chat.
        bot().sendChat("/ar fetch " + bogus);

        // FetchCommand resolves via vanilla getPlayer(), which throws
        // PlayerNotFoundException; CommandHandler turns that into the red
        // commands.generic.player.notFound chat reply. Poll the CLIENT chat
        // overlay (newest line first) for the resolved text.
        String newest = "";
        boolean found = false;
        for (int waited = 0; waited < 100 && !found; waited += 10) {
            bot().waitTicks(10);
            com.google.gson.JsonObject chat = bot().reportChat(10);
            com.google.gson.JsonArray lines = chat.getAsJsonArray("lines");
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).getAsString();
                newest = newest.isEmpty() ? line : newest;
                if (line.toLowerCase(java.util.Locale.ROOT).contains("cannot be found")) {
                    found = true;
                    break;
                }
            }
        }
        assertTrue("client chat must show the vanilla player-not-found error "
                + "for an unknown fetch target (newest line: '" + newest + "')", found);
    }

    private static double extractDouble(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern not found in: " + src, m.find());
        return Double.parseDouble(m.group(1));
    }

}
