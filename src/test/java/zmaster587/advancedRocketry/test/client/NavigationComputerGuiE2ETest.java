package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.openGuiByRightClick;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.screenOf;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.waitForNoScreen;

/**
 * The navigation console, driven the way a pilot drives it: right-click it open, then nothing but
 * button clicks. This is the one M1 step that is a GUI rather than a key, and it had no client
 * coverage at all — every existing assertion about the console called its methods server-side, which
 * cannot see whether a click reaches them, whether the console shows the pilot what his ship knows,
 * or whether it answers him.
 *
 * <p>What is pinned, in the order the pilot does it:</p>
 * <ol>
 *   <li><b>Arming with nowhere to go is refused, and said out loud.</b> The negative comes first
 *       because it doubles as the proof that a click on this GUI reaches the server at all — the
 *       refusal in the pilot's own chat is the click's receipt. Without it, every "nothing happened"
 *       below could equally mean "the button was never pressed".</li>
 *   <li><b>Copying a brought crystal does not empty it.</b> The ship learns the addresses AND the
 *       source keeps them: this console is where knowledge is exchanged, not moved.</li>
 *   <li><b>The console lists what the ship now knows.</b> Read off the real GUI's own buttons —
 *       their labels are what the pilot reads on screen.</li>
 *   <li><b>Picking a listed address aims the ship at it.</b></li>
 *   <li><b>Arm, then disarm</b> — each answered in chat, each reflected in the console's state.</li>
 * </ol>
 *
 * <p>Runs on a console standing in the world, NOT on an assembled ship: the harness's right-click
 * takes literal coordinates and has no raycast, so a block that lives in a ship's subspace cannot be
 * clicked. That is a limit of the instrument, not of the contract — and the pilot can legitimately
 * arm before assembly, which is what this test does. No Valkyrien Skies needed.</p>
 */
public class NavigationComputerGuiE2ETest extends AbstractClientE2ETest {

    private static final int NAV_X = 5100, NAV_Y = 71, NAV_Z = 5100;

    /** Button ids are the console's own module ids, which libVulpes puts straight on the GuiButton. */
    private static final int BUTTON_COPY = 0;
    private static final int BUTTON_ARM = 5;
    private static final int BUTTON_PICK_FIRST = 10;

    /** Addresses the probe seeds into the brought crystal: sectors 100..102, named {@code probe-N}. */
    private static final int SEEDED = 3;
    private static final int FIRST_SECTOR = 100;

    private static final Pattern SHIP_COUNT = Pattern.compile("\"ship\":(\\d+)");
    private static final Pattern SOURCE_COUNT = Pattern.compile("\"source\":(\\d+)");
    private static final Pattern TARGET = Pattern.compile("\"target\":(null|\"[^\"]*\")");
    private static final Pattern ARMED = Pattern.compile("\"armed\":(true|false)");

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void thePilotCopiesPicksAndArmsAtTheConsoleWithNothingButClicks() throws Exception {
        // ---- Arrange: a console on a platform, with a brought crystal in its source slot. --------
        exec("artest chunk warmup 0 " + ((NAV_X - 8) >> 4) + " " + ((NAV_Z - 8) >> 4)
                + " " + ((NAV_X + 8) >> 4) + " " + ((NAV_Z + 8) >> 4));
        exec("artest fill 0 " + (NAV_X - 3) + " " + (NAV_Y - 1) + " " + (NAV_Z - 3)
                + " " + (NAV_X + 3) + " " + (NAV_Y - 1) + " " + (NAV_Z + 3) + " minecraft:obsidian");
        exec("artest fill 0 " + (NAV_X - 3) + " " + NAV_Y + " " + (NAV_Z - 3)
                + " " + (NAV_X + 3) + " " + (NAV_Y + 4) + " " + (NAV_Z + 3) + " minecraft:air");
        String place = exec("artest fill 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z
                + " " + NAV_X + " " + NAV_Y + " " + NAV_Z + " advancedrocketry:navigationComputer");
        assertTrue("ARRANGEMENT: placing the navigation computer failed: " + place,
                place.contains("\"ok\":true"));

        String seed = exec("artest nav crystal 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z
                + " 0 " + SEEDED + " " + FIRST_SECTOR);
        assertTrue("ARRANGEMENT: the source slot must hold a crystal carrying " + SEEDED
                + " addresses: " + seed, seed.contains("\"addresses\":" + SEEDED));
        // The ship's own crystal is the DESTINATION, and the copy is add-only into it: with that slot
        // empty there is nowhere to copy to and the button is a silent no-op. A ship that can be
        // aimed has a crystal of its own, so give it a blank one.
        String shipCrystal = exec("artest nav crystal 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z + " 1 0");
        assertTrue("ARRANGEMENT: the ship slot must hold a (blank) crystal to copy INTO: "
                + shipCrystal, shipCrystal.contains("\"addresses\":0"));
        String before = exec("artest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
        assertTrue("ARRANGEMENT CONTROL: the ship's own crystal must start EMPTY, or the copy leg "
                        + "below cannot tell a successful copy from a pre-loaded console: " + before,
                readInt(before, SHIP_COUNT) == 0);

        standBesideTheConsole();
        emptyTheHand();

        // ---- 1) Open it, and try to arm with nowhere to go. -------------------------------------
        openConsole();

        bot().clickButtonById(BUTTON_ARM);
        String refusal = awaitChatContaining("no jump target", 30);
        assertTrue("arming with no destination chosen must be REFUSED and the pilot told why "
                        + "(this is also the receipt proving a click on this GUI reaches the "
                        + "server). chat=\"" + refusal + "\"",
                refusal.toLowerCase(Locale.ROOT).contains("no jump target"));
        String afterRefusal = exec("artest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
        assertTrue("and the console must not be armed: " + afterRefusal,
                !readBoolean(afterRefusal, ARMED));

        // ---- 2) Copy the brought crystal into the ship's own. -----------------------------------
        bot().clickButtonById(BUTTON_COPY);
        String copied = awaitStatusWhere(SHIP_COUNT, SEEDED, 30);
        assertTrue("clicking COPY must write the brought crystal's addresses into the ship's own "
                        + "crystal: " + copied,
                readInt(copied, SHIP_COUNT) == SEEDED);
        assertTrue("and the brought crystal must KEEP them — the console exchanges knowledge, it "
                        + "does not move it: " + copied,
                readInt(copied, SOURCE_COUNT) == SEEDED);

        // ---- 3) The console lists what the ship now knows. --------------------------------------
        // Reopened, because the address list is built when the screen is: a pilot who copies and
        // then looks at the list has closed and opened the console in between.
        bot().closeScreen();
        assertTrue("the console GUI must close", waitForNoScreen(bot(), 60).isEmpty());
        openConsole();

        JsonObject buttons = bot().reportButtons();
        int listed = countAddressButtons(buttons);
        assertTrue("the console must LIST the addresses the ship now knows — the pilot picks a "
                        + "destination off this list, so a copy he cannot see is a copy he cannot "
                        + "use (listed=" + listed + " expected=" + SEEDED + "): " + buttons,
                listed == SEEDED);
        // NOT asserted here: the labels themselves. Every libVulpes module button is a
        // GuiImageButton built with an empty displayString and draws its caption itself, so the
        // harness's button report is structurally blind to it — asserting on it would be asserting
        // through an instrument that cannot see. What the list CONTAINS is pinned below instead, by
        // picking off it.

        // ---- 4) Pick one: the ship is aimed at THAT address, not merely at something. ------------
        bot().clickButtonById(BUTTON_PICK_FIRST);
        String aimed = awaitStatusWhere(TARGET, 30);
        String expected = "\"" + FIRST_SECTOR + "_0_0\"";
        assertTrue("clicking the first listed address must aim the ship at THAT address (" + expected
                        + ") — the list's order is what the pilot picks by, so aiming at some other "
                        + "entry is the same defect as not aiming at all: " + aimed,
                expected.equals(readGroup(aimed, TARGET)));

        // ---- 5) Arm, and stand down again. Both answered. ---------------------------------------
        bot().clickButtonById(BUTTON_ARM);
        String armedChat = awaitChatContaining("jump armed", 30);
        assertTrue("arming a chosen destination must be accepted and confirmed to the pilot. chat=\""
                        + armedChat + "\"",
                armedChat.toLowerCase(Locale.ROOT).contains("jump armed"));
        String armedStatus = exec("artest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
        assertTrue("and the console must actually BE armed — the message is not the state: "
                        + armedStatus, readBoolean(armedStatus, ARMED));

        bot().clickButtonById(BUTTON_ARM);
        String disarmedChat = awaitChatContaining("jump disarmed", 30);
        assertTrue("pressing the same button again must stand the jump down, and say so. chat=\""
                        + disarmedChat + "\"",
                disarmedChat.toLowerCase(Locale.ROOT).contains("jump disarmed"));
        String disarmedStatus = exec("artest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
        assertTrue("a disarmed console must not stay armed: " + disarmedStatus,
                !readBoolean(disarmedStatus, ARMED));

        bot().closeScreen();
    }

    /**
     * Right-click the console open, and if it does not open, say what the click actually did. The
     * shared helper swallows the interaction's own {@code EnumActionResult}, which is the one thing
     * that separates "the click never landed" from "it landed and the GUI refused to open" — two
     * failures with the same empty screen.
     */
    private String openConsole() throws Exception {
        String screen = openGuiByRightClick(bot(), NAV_X, NAV_Y, NAV_Z);
        if (screen.startsWith("zmaster587.libVulpes.inventory.GuiModular")) {
            return screen;
        }
        JsonObject direct = bot().interactBlock(NAV_X, NAV_Y, NAV_Z);
        bot().waitTicks(20);
        assertTrue("right-clicking the navigation computer must open its GUI. screen=\"" + screen
                        + "\" afterDirectClick=\"" + screenOf(bot().reportState())
                        + "\" clickResult=" + direct
                        + " blockAtConsole=" + bot().blockState(NAV_X, NAV_Y, NAV_Z)
                        + " serverSideModuleBuild="
                        + exec("artest nav modules 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z)
                        + " navStatus=" + exec("artest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z),
                false);
        return screen;
    }

    // ---- Observation helpers ---------------------------------------------------------------------

    /** Poll the client's chat until a line contains {@code needle} (bounded); returns the last hit. */
    private String awaitChatContaining(String needle, int samples) throws Exception {
        String seen = "";
        for (int i = 0; i < samples; i++) {
            JsonObject chat = bot().reportChat(8);
            seen = chat.toString();
            if (seen.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                return seen;
            }
            bot().waitTicks(5);
        }
        return seen;
    }

    /** Poll {@code nav status} until the numeric group of {@code p} equals {@code want} (bounded). */
    private String awaitStatusWhere(Pattern p, int want, int samples) throws Exception {
        String status = "";
        for (int i = 0; i < samples; i++) {
            status = exec("artest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            Matcher m = p.matcher(status);
            if (m.find() && Integer.parseInt(m.group(1)) == want) {
                return status;
            }
            bot().waitTicks(5);
        }
        return status;
    }

    /** Poll {@code nav status} until {@code p}'s group is no longer {@code null} (bounded). */
    private String awaitStatusWhere(Pattern p, int samples) throws Exception {
        String status = "";
        for (int i = 0; i < samples; i++) {
            status = exec("artest nav status 0 " + NAV_X + " " + NAV_Y + " " + NAV_Z);
            Matcher m = p.matcher(status);
            if (m.find() && !"null".equals(m.group(1))) {
                return status;
            }
            bot().waitTicks(5);
        }
        return status;
    }

    /** How many address-pick buttons the open console shows. */
    private static int countAddressButtons(JsonObject reportButtons) {
        JsonArray list = reportButtons.getAsJsonArray("buttons");
        int found = 0;
        for (JsonElement element : list) {
            JsonObject button = element.getAsJsonObject();
            if (button.get("id").getAsInt() >= BUTTON_PICK_FIRST && button.get("visible").getAsBoolean()) {
                found++;
            }
        }
        return found;
    }

    private int readInt(String json, Pattern p) {
        return Integer.parseInt(readGroup(json, p));
    }

    private boolean readBoolean(String json, Pattern p) {
        return Boolean.parseBoolean(readGroup(json, p));
    }

    private String readGroup(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected " + p.pattern() + " in: " + json, m.find());
        return m.group(1);
    }

    // ---- Arrangement helpers ---------------------------------------------------------------------

    /** Teleport until the client OBSERVABLY stands within interaction reach of the console. */
    private void standBesideTheConsole() throws Exception {
        double distSq = Double.POSITIVE_INFINITY;
        JsonObject state = null;
        for (int attempt = 0; attempt < 6 && distSq >= 25.0; attempt++) {
            exec("tp @a " + (NAV_X + 0.5) + " " + NAV_Y + " " + (NAV_Z + 1.5) + " 0 0");
            bot().waitTicks(20);
            state = bot().reportState();
            if (state.has("worldReady") && state.get("worldReady").getAsBoolean()) {
                double dx = state.get("playerX").getAsDouble() - (NAV_X + 0.5);
                double dy = state.get("playerY").getAsDouble() - NAV_Y;
                double dz = state.get("playerZ").getAsDouble() - (NAV_Z + 0.5);
                distSq = dx * dx + dy * dy + dz * dz;
            }
        }
        assertTrue("ARRANGEMENT: the client must observably stand within reach of the console, or "
                + "the right-click is dropped before the block sees it. state=" + state, distSq < 25.0);
    }

    /** Server-side clear + client-observed empty hand (a held stack can eat the right-click). */
    private void emptyTheHand() throws Exception {
        exec("clear @a");
        bot().selectHotbar(0);
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonObject items = bot().reportPlayerItems();
            if (items.has("worldReady") && items.get("worldReady").getAsBoolean() && items.has("held")) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    return;
                }
            }
            bot().waitTicks(5);
        }
        assertTrue("ARRANGEMENT: the bot's hand must be observably empty; held=" + heldId,
                heldId != null && heldId.isEmpty());
    }
}
