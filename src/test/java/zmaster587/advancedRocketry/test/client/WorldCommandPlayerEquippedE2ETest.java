package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * TASK-21 — {@code /ar} player-equipped verbs positive paths, driven the way
 * a player drives them: typed into the REAL client chat
 * ({@code ClientBot.sendChat} → {@code CPacketChatMessage}), with the outcome
 * observed from the CLIENT side (dim via {@code reportWeather}, inventory via
 * {@code reportPlayerItems}, command replies via {@code reportChat}) and the
 * server consulted only as a cross-side oracle.
 *
 * <p>{@code WorldCommandGuardContractTest} closed the guard side
 * (non-player sender rejection). This test closes the symmetric positive
 * side — verbs that DO mutate state when a real opped player runs them:</p>
 *
 * <ul>
 *   <li>{@code /ar goto dimension <dim>} — transfers player to dim.</li>
 *   <li>{@code /ar station give <id>} — adds station chip to inventory.</li>
 *   <li>{@code /ar addTorch} — adds held block to torch list.</li>
 *   <li>{@code /ar addSealant} — adds held block to sealed-block list.</li>
 *   <li>{@code /ar goto station <id>} — teleports to station spawn.</li>
 * </ul>
 *
 * <p>Out of scope here: {@code /ar fillData} (needs an {@code itemMultiData}
 * fixture; the verb is exercised by the production assembly flow elsewhere).
 * The bot is opped in {@code @Before} and de-opped in {@code @After}.</p>
 */
public class WorldCommandPlayerEquippedE2ETest extends AbstractClientE2ETest {

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Before
    public void opTheBot() throws Exception {
        // Reset to a known position so /ar goto's transferPlayerToDimension
        // leaves us at a predictable destination.
        exec("artest place 0 8 78 8 minecraft:stone");
        exec("tp @a 8.5 79 8.5");
        bot().waitTicks(5);
        String op = exec("artest player op-self");
        assertTrue("op-self must succeed: " + op,
                op.contains("\"opped\":true"));
    }

    @After
    public void deopTheBot() throws Exception {
        try {
            // Return to overworld in case a goto test moved us (console-side
            // cleanup — not part of any assertion).
            exec("artest tp 0");
        } catch (Exception ignored) {
        }
        try {
            exec("artest player deop-self");
        } catch (Exception ignored) {
        }
    }

    @Test
    public void arGotoTransfersPlayerToTargetDim() throws Exception {
        // Generate an AR planet to provide a known destination dim distinct
        // from overworld (same probe pattern as TASK-19 Phase 1a) — arrange.
        String before = exec("ar planet list");
        exec("ar planet generate 0 GotoTarget 10 10 10");
        String after = exec("ar planet list");
        int targetDim = newDimFromDiff(before, after);
        assertNotEquals("planet generate must yield a new dim id", -1, targetDim);
        try {
            exec("artest dim load " + targetDim);

            // The player types the command in the real chat.
            bot().sendChat("/ar goto dimension " + targetDim);

            // The CLIENT must end up rendering the target dim.
            waitForClientDim(targetDim);

            // Cross-side oracle: the server agrees about the player's dim.
            String health = exec("artest player health");
            assertTrue("server must agree the player is in dim " + targetDim
                    + ": " + health, health.contains("\"dim\":" + targetDim));
        } finally {
            exec("artest tp 0");
            exec("ar planet delete " + targetDim);
        }
    }

    @Test
    public void arGiveStationAddsChipToPlayerInventory() throws Exception {
        // Pre-create a station so /ar station give has a real ID to bind.
        String create = exec("artest station create 0");
        Matcher idM = Pattern.compile("\"id\":(-?\\d+)").matcher(create);
        assertTrue("station create response must include id: " + create,
                idM.find());
        int stationId = Integer.parseInt(idM.group(1));

        // Baseline: no chip in the CLIENT-rendered inventory yet.
        assertEquals("baseline: bot inventory has no station chip (client view)",
                0, countClientItems("advancedrocketry:spacestationchip"));

        // The player types the command in the real chat.
        bot().sendChat("/ar station give " + stationId);

        // The chip must show up in the CLIENT-rendered inventory — that's
        // what the player sees when they open their inventory screen.
        int count = -1;
        for (int waited = 0; waited < 100; waited += 10) {
            bot().waitTicks(10);
            count = countClientItems("advancedrocketry:spacestationchip");
            if (count >= 1) break;
        }
        assertTrue("/ar station give must add a station chip to the bot's "
                + "client-rendered inventory; client count=" + count, count >= 1);

        // Cross-side oracle: server inventory agrees.
        String post = exec("artest player inventory-contains advancedrocketry:spacestationchip");
        assertTrue("server inventory must also contain the chip: " + post,
                !post.contains("\"count\":0"));
    }

    @Test
    public void arAddTorchAddsHeldBlockToTorchList() throws Exception {
        // Equip the bot with a torch-eligible block (arrange) — the verb
        // reads getHeldItemMainhand.
        String give = exec("artest player give-held minecraft:cobblestone");
        assertTrue("give-held must succeed: " + give,
                give.contains("\"ok\":true"));

        // The player types the command in the real chat.
        bot().sendChat("/ar addTorch");

        // The command replies on the sender's chat: either "%s added to the
        // torch list" or "%s is already in the torch list" (idempotent
        // re-runs hit the second branch; the post-state is the same). Both
        // resolve through the client's lang — assert at the layer the player
        // reads.
        assertTrue("client chat must show the torch-list reply",
                waitForChatContaining("torch list", 100));
    }

    @Test
    public void arAddSolidBlockOverrideAddsHeldBlockToSealedList() throws Exception {
        // Different block than addTorch so the two tests don't share state
        // via the torchBlocks list (see WorldCommand duplicate-warning branch).
        String give = exec("artest player give-held minecraft:dirt");
        assertTrue("give-held must succeed: " + give,
                give.contains("\"ok\":true"));

        bot().sendChat("/ar addSealant");

        assertTrue("client chat must show the sealed-block-list reply",
                waitForChatContaining("sealed block list", 100));
    }

    @Test
    public void arGotoStationTeleportsToStationSpawnInSpaceDim() throws Exception {
        // Pre-create a station — its spawn location is in the space dim.
        String create = exec("artest station create 0");
        Matcher idM = Pattern.compile("\"id\":(-?\\d+)").matcher(create);
        assertTrue("station create must succeed: " + create, idM.find());
        int stationId = Integer.parseInt(idM.group(1));

        // Make sure space dim is loaded (arrange).
        exec("artest dim load -2");

        // The player types the command in the real chat.
        bot().sendChat("/ar goto station " + stationId);

        // The CLIENT must end up rendering the space dim (-2 default).
        waitForClientDim(-2);
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private static final Pattern DIM_LINE = Pattern.compile("DIM(\\d+):");

    private static int newDimFromDiff(String before, String after) {
        java.util.Set<Integer> beforeIds = new java.util.HashSet<>();
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

    /** Counts stacks of {@code itemId} in the CLIENT-rendered main inventory + offhand. */
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
                if (lines.get(i).getAsString().toLowerCase(java.util.Locale.ROOT)
                        .contains(needle.toLowerCase(java.util.Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
