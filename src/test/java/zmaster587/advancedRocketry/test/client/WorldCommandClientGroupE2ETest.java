package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The player types an {@code /ar} command into the real client chat, and reads the answer there.
 * Seven scenarios, one client.
 *
 * <p>Every member drives the same production entry: {@code ClientBot.sendChat} &rarr;
 * {@code CPacketChatMessage} &rarr; the server's command handler &rarr;
 * {@code zmaster587.advancedRocketry.command.WorldCommand}. The outcome is observed from the CLIENT
 * side — the dimension it renders ({@code reportWeather}), the inventory it draws
 * ({@code reportPlayerItems}), the reply on its chat overlay ({@code reportChat}) — with server
 * probes kept as cross-side oracles.</p>
 *
 * <h2>Why these seven share one harness</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML:
 * {@code WorldCommandPlayerEquippedE2ETest} 341.2 s over five client boots and
 * {@code WorldCommandFetchTest} 146.1 s over two — <b>8.1 minutes to type seven commands</b>.</p>
 *
 * <h2>The two channels this group has to be careful with</h2>
 *
 * <ul>
 *   <li><b>Chat is both the stimulus channel and the observation channel here.</b> Four scenarios
 *       prove "the command answered the player" by reading the last N lines, and the harness writes
 *       a {@code FORGE_TEST_DONE} marker into that same channel on every server command. Each of
 *       them therefore arms the channel immediately before typing, and nothing between the arm and
 *       the {@code sendChat} is a server command.</li>
 *   <li><b>Two scenarios leave the player in another dimension.</b> That is the shared base's job
 *       now: the reset returns him to his plot's world and asserts the world the client renders,
 *       rather than trusting a teleport that would have moved him to the right coordinates in the
 *       wrong world.</li>
 * </ul>
 *
 * <p>The bot is opped per scenario rather than in a {@code @Before}: the ops are part of each
 * scenario's arrangement and show up in its journal, and the de-op that the source classes did in
 * {@code @After} would have run before the failure watcher could print anything.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code WorldCommandPlayerEquippedE2ETest}, {@code WorldCommandFetchTest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class WorldCommandClientGroupE2ETest extends AbstractSharedClientE2ETest {

    private static final Pattern DIM_LINE = Pattern.compile("DIM(\\d+):");
    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");
    private static final Pattern STATION_ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?\\d+(?:\\.\\d+)?)");

    /** The space dim, where {@code /ar goto station} lands the player. */
    private static final int SPACE_DIM = -2;

    /** The registered name of AR's planet world type (WorldTypePlanetGen). */
    private static final String AR_PLANET_WORLD_TYPE = "PlanetGen";

    @Override
    protected String subsystem() {
        return "world-command";
    }

    // ── shared arrangement ────────────────────────────────────────────────────

    /** Ops the bot: every verb below is op-gated, and an un-opped run fails as a permission error
     *  that reads nothing like the contract under test. */
    private void opTheBot() throws Exception {
        String op = exec("artest player op-self");
        scenario().requireArranged("op-self must succeed: " + op, op.contains("\"opped\":true"));
    }

    private String botName() throws Exception {
        String health = exec("artest player health");
        Matcher m = PLAYER_NAME.matcher(health);
        scenario().requireArranged("player health must echo the player name: " + health, m.find());
        return m.group(1);
    }

    private static int newDimFromDiff(String before, String after) {
        Set<Integer> beforeIds = new HashSet<>();
        Matcher m = DIM_LINE.matcher(before);
        while (m.find()) beforeIds.add(Integer.parseInt(m.group(1)));
        Matcher m2 = DIM_LINE.matcher(after);
        while (m2.find()) {
            int id = Integer.parseInt(m2.group(1));
            if (!beforeIds.contains(id)) return id;
        }
        return -1;
    }

    /** Polls until the CLIENT world reports the expected dimension (~10 s cap). */
    private void waitForClientDim(int expectedDim) throws Exception {
        JsonObject last = null;
        for (int waited = 0; waited < 200; waited += 10) {
            bot().waitTicks(10);
            last = bot().reportWeather();
            if (last != null && last.has("dim") && last.get("dim").getAsInt() == expectedDim) {
                return;
            }
        }
        throw new AssertionError("client never reached dim " + expectedDim
                + " (last client report: " + last + ")");
    }

    /** Counts stacks of {@code itemId} in the CLIENT-rendered main inventory. */
    private int countClientItems(String itemId) throws Exception {
        JsonObject items = bot().reportPlayerItems();
        int count = 0;
        JsonArray main = items.getAsJsonArray("main");
        for (int i = 0; i < main.size(); i++) {
            if (itemId.equals(main.get(i).getAsJsonObject().get("id").getAsString())) {
                count += main.get(i).getAsJsonObject().get("count").getAsInt();
            }
        }
        return count;
    }

    /** Polls the CLIENT chat overlay until a line contains {@code needle}. */
    private boolean waitForChatContaining(String needle, int maxTicks) throws Exception {
        for (int waited = 0; waited < maxTicks; waited += 10) {
            bot().waitTicks(10);
            JsonArray lines = bot().reportChat(10).getAsJsonArray("lines");
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).getAsString().toLowerCase(Locale.ROOT)
                        .contains(needle.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double extractDouble(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern not found in: " + src, m.find());
        return Double.parseDouble(m.group(1));
    }

    // ── /ar addSealant ────────────────────────────────────────────────────────

    /** From {@code WorldCommandPlayerEquippedE2ETest}. A different block than {@code addTorch} uses,
     *  so the two do not share state through the sealed/torch lists. */
    @Test
    public void arAddSolidBlockOverrideAddsHeldBlockToSealedList() throws Exception {
        scenario().arranging("op the bot and put dirt in its hand");
        opTheBot();
        String give = exec("artest player give-held minecraft:dirt");
        scenario().requireArranged("give-held must succeed: " + give, give.contains("\"ok\":true"));

        scenario().measuring("arm the chat channel immediately before typing");
        armChatObservation();

        scenario().asserting("the sealed-block-list reply reaches the player's chat");
        bot().sendChat("/ar addSealant");
        assertTrue("client chat must show the sealed-block-list reply",
                waitForChatContaining("sealed block list", 100));
    }

    // ── /ar addTorch ──────────────────────────────────────────────────────────

    /**
     * From {@code WorldCommandPlayerEquippedE2ETest}. The command replies either "%s added to the
     * torch list" or "%s is already in the torch list" — idempotent re-runs hit the second branch
     * and the post-state is the same, so the assertion is on the shared substring, resolved through
     * the client's own lang.
     */
    @Test
    public void arAddTorchAddsHeldBlockToTorchList() throws Exception {
        scenario().arranging("op the bot and put cobblestone in its hand");
        opTheBot();
        String give = exec("artest player give-held minecraft:cobblestone");
        scenario().requireArranged("give-held must succeed: " + give, give.contains("\"ok\":true"));

        scenario().measuring("arm the chat channel immediately before typing");
        armChatObservation();

        scenario().asserting("the torch-list reply reaches the player's chat");
        bot().sendChat("/ar addTorch");
        assertTrue("client chat must show the torch-list reply",
                waitForChatContaining("torch list", 100));
    }

    // ── /ar station give ──────────────────────────────────────────────────────

    /** From {@code WorldCommandPlayerEquippedE2ETest}: the chip must appear in the inventory the
     *  CLIENT draws — that is what the player sees when he opens his inventory screen. */
    @Test
    public void arGiveStationAddsChipToPlayerInventory() throws Exception {
        scenario().arranging("op the bot and create a station for the chip to bind to");
        opTheBot();
        String create = exec("artest station create " + plot().dim);
        Matcher idM = STATION_ID.matcher(create);
        scenario().requireArranged("station create response must include id: " + create, idM.find());
        int stationId = Integer.parseInt(idM.group(1));
        scenario().record("stationId", stationId);

        // The shared reset clears the inventory, so this is a control rather than a hope: a chip
        // already in hand would make the poll below pass with no command behind it.
        scenario().measuring("the client-rendered inventory holds no station chip yet");
        int baseline = countClientItems("advancedrocketry:spacestationchip");
        scenario().requireArranged("baseline: the client inventory must hold no station chip, "
                + "or the verdict below is satisfiable without the command; saw " + baseline,
                baseline == 0);

        scenario().asserting("the chip appears in the inventory the client draws");
        bot().sendChat("/ar station give " + stationId);

        int count = -1;
        for (int waited = 0; waited < 100; waited += 10) {
            bot().waitTicks(10);
            count = countClientItems("advancedrocketry:spacestationchip");
            if (count >= 1) break;
        }
        assertTrue("/ar station give must add a station chip to the bot's client-rendered "
                + "inventory; client count=" + count, count >= 1);

        String post = exec("artest player inventory-contains advancedrocketry:spacestationchip");
        assertTrue("server inventory must also contain the chip: " + post,
                !post.contains("\"count\":0"));
    }

    // ── /ar goto dimension ────────────────────────────────────────────────────

    /** From {@code WorldCommandPlayerEquippedE2ETest}: the client must end up rendering the target
     *  dim. The base class's reset brings the player home afterwards. */
    @Test
    public void arGotoTransfersPlayerToTargetDim() throws Exception {
        scenario().arranging("op the bot and generate a planet to travel to");
        opTheBot();
        String before = exec("ar planet list");
        exec("ar planet generate 0 GotoTarget 10 10 10");
        String after = exec("ar planet list");
        int targetDim = newDimFromDiff(before, after);
        scenario().record("targetDim", targetDim);
        scenario().requireArranged("planet generate must yield a new dim id; before=" + before
                + " after=" + after, targetDim != -1);
        try {
            exec("artest dim load " + targetDim);

            scenario().asserting("the client renders the target dim after the command");
            bot().sendChat("/ar goto dimension " + targetDim);
            waitForClientDim(targetDim);

            String health = exec("artest player health");
            assertTrue("server must agree the player is in dim " + targetDim + ": " + health,
                    health.contains("\"dim\":" + targetDim));
        } finally {
            // The dimension itself must go: a generated planet outlives the scenario and the next
            // one's `ar planet list` diff would see it. The player's own return is the reset's.
            exec("artest tp " + plot().dim);
            exec("ar planet delete " + targetDim);
        }
    }

    /**
     * The planet's own world type has to reach the CLIENT, because client-side terrain code
     * identifies a world by it — and a secondary world's {@code WorldInfo} used to answer with the
     * SAVE's world type, so every planet a player entered claimed to be the overworld's kind.
     *
     * <p>The overworld's value is read first, in this same scenario, and the assertion is that the
     * value CHANGED on crossing. Asserting the planet's name alone would also pass on a build that
     * hard-codes one world type everywhere, which is the failure this is about.</p>
     */
    @Test
    public void arGotoMakesTheClientRenderThePlanetsOwnWorldType() throws Exception {
        scenario().arranging("op the bot and generate a planet to travel to");
        opTheBot();
        String home = clientWorldType();
        scenario().record("homeWorldType", home);
        scenario().requireArranged("the client must name the world type it starts in, else the"
                + " comparison below has nothing to change FROM; got '" + home + "'", !home.isEmpty());

        String before = exec("ar planet list");
        exec("ar planet generate 0 WorldTypeTarget 10 10 10");
        String after = exec("ar planet list");
        int targetDim = newDimFromDiff(before, after);
        scenario().record("targetDim", targetDim);
        scenario().requireArranged("planet generate must yield a new dim id; before=" + before
                + " after=" + after, targetDim != -1);
        try {
            exec("artest dim load " + targetDim);

            scenario().asserting("the client renders the planet's own world type after arriving");
            bot().sendChat("/ar goto dimension " + targetDim);
            waitForClientDim(targetDim);

            String onPlanet = clientWorldType();
            scenario().record("planetWorldType", onPlanet);
            assertEquals("the client must learn the planet's own world type on arrival, not the"
                    + " one the save was created with", AR_PLANET_WORLD_TYPE, onPlanet);
            assertNotEquals("the world type the client renders must differ between the overworld"
                    + " and a planet, or it is not per-dimension at all", home, onPlanet);
        } finally {
            exec("artest tp " + plot().dim);
            exec("ar planet delete " + targetDim);
        }
    }

    /** The world type the CLIENT believes it is in, by name. */
    private String clientWorldType() throws Exception {
        JsonObject state = bot().reportState();
        return state != null && state.has("worldType") ? state.get("worldType").getAsString() : "";
    }

    // ── /ar goto station ──────────────────────────────────────────────────────

    /** From {@code WorldCommandPlayerEquippedE2ETest}: a station's spawn is in the space dim, and
     *  the client must end up rendering it. */
    @Test
    public void arGotoStationTeleportsToStationSpawnInSpaceDim() throws Exception {
        scenario().arranging("op the bot and create a station to travel to");
        opTheBot();
        String create = exec("artest station create " + plot().dim);
        Matcher idM = STATION_ID.matcher(create);
        scenario().requireArranged("station create must succeed: " + create, idM.find());
        int stationId = Integer.parseInt(idM.group(1));
        scenario().record("stationId", stationId);

        exec("artest dim load " + SPACE_DIM);

        scenario().asserting("the client renders the space dim after the command");
        bot().sendChat("/ar goto station " + stationId);
        waitForClientDim(SPACE_DIM);
    }

    // ── /ar fetch ─────────────────────────────────────────────────────────────

    /**
     * From {@code WorldCommandFetchTest}. {@code /ar fetch <unknown-name>} must surface vanilla's
     * "player cannot be found" error ON THE PLAYER'S CHAT OVERLAY (i18n resolved) without crashing.
     * Pins the {@code getPlayer -> PlayerNotFoundException} branch at the layer the player reads it.
     */
    @Test
    public void fetchUnknownNameReportsInvalidPlayerName() throws Exception {
        scenario().arranging("op the bot");
        opTheBot();
        String bogus = "_no_such_player_xyz_";

        scenario().measuring("arm the chat channel immediately before typing");
        armChatObservation();

        scenario().asserting("the player-not-found error reaches the player's chat");
        bot().sendChat("/ar fetch " + bogus);
        assertTrue("client chat must show the vanilla player-not-found error for an unknown "
                + "fetch target", waitForChatContaining("cannot be found", 100));
    }

    /**
     * From {@code WorldCommandFetchTest}. Self-fetch: with sender == target the dim transfer is a
     * same-dim no-op and the setPosition copies the bot's coords onto itself, so the verb must
     * complete and leave the player where he was. Positive coverage of the whole resolve &rarr;
     * transfer &rarr; setPosition path without a second bot (the harness is single-client; fetching
     * a DIFFERENT connected player stays out of scope).
     */
    @Test
    public void selfFetchCompletesAndPreservesPosition() throws Exception {
        scenario().arranging("op the bot and read its own username back");
        opTheBot();
        String botName = botName();
        assertNotEquals("bot name must be non-empty", "", botName);
        scenario().record("botName", botName);

        scenario().measuring("the CLIENT-observed position before the command");
        JsonObject pre = bot().reportState();
        double preX = pre.get("playerX").getAsDouble();
        double preZ = pre.get("playerZ").getAsDouble();
        scenario().record("beforeXZ", preX + "," + preZ);

        scenario().asserting("a self-fetch leaves the client where it was");
        bot().sendChat("/ar fetch " + botName);
        bot().waitTicks(20);

        JsonObject post = bot().reportState();
        double postX = post.get("playerX").getAsDouble();
        double postZ = post.get("playerZ").getAsDouble();
        assertTrue("self-fetch must leave bot within 1 block of its prior position: "
                        + "preX=" + preX + " postX=" + postX, Math.abs(postX - preX) < 1.0);
        assertTrue("self-fetch must leave bot within 1 block of its prior position: "
                        + "preZ=" + preZ + " postZ=" + postZ, Math.abs(postZ - preZ) < 1.0);

        String postServer = exec("artest player health");
        assertTrue("server-side X must agree with the client view: " + postServer,
                Math.abs(extractDouble(postServer, POS_X) - postX) < 1.0);
    }
}
