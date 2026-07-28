package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;

import static org.junit.Assert.assertTrue;

/**
 * A craft that never became a ship takes NO orders from the player sitting in it: not steering, not
 * a Flight-Assist / auto-takeoff / jump command, and not the cockpit camera. The CLIENT half of the
 * rule that an unassembled craft does not accept pilot input.
 *
 * <p>The pilot seat's flight-computer link is a BUILD-TIME INTENTION: the assembler records it
 * before the physics mod has confirmed the spawn, and a rejected spawn (over-size flood, bedrock
 * contact) leaves it set forever. Every client gate that asked the link ALONE therefore asserted a
 * flight state that did not exist — the steering keys were scoped into the cockpit, the per-tick
 * input was shipped to the server, and each edge-triggered command key (Flight Assist, auto-takeoff,
 * jump) reached the ship's computer and was answered by its gate instead of being refused as "not a
 * ship". A player watched a craft that does nothing, forever, tell him it was flying.</p>
 *
 * <p>The test is deliberately built around its own CONTROL, because both of its instruments can
 * fail silent:</p>
 * <ul>
 *   <li><b>Leg 1 (control, a REAL ship)</b> — the same key presses and the same counters on an
 *       assembled ship MUST move. This proves the harness's injected key actually reaches an
 *       edge-triggered handler, and that the new gate answers TRUE on a real ship, i.e. that leg 2's
 *       zeroes are the production gate refusing and not the test failing to press anything. Without
 *       it, a harness that swallowed the key would make leg 2 pass on a completely broken build.</li>
 *   <li><b>Leg 2 (subject, a linked craft that never assembled)</b> — arranged by the
 *       {@code artest vs seat-link} probe, which links a seat WITHOUT assembling (the only
 *       deterministic way to reproduce the post-failure state), and verified to be linked-but-not-a-
 *       ship before anything is pressed. The seat's own "not assembled" notice is read off the
 *       action bar first, so the overlay instrument is known to speak before silence is used as
 *       evidence.</li>
 * </ul>
 *
 * <p>Gated on real Valkyrien Skies — run with {@code -PwithVS}.</p>
 */
public class VSUnassembledCraftTakesNoOrdersE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern RECEIVED = Pattern.compile("\"received\":(-?\\d+)");
    private static final Pattern COMMANDS = Pattern.compile("\"commandsReceived\":(-?\\d+)");

    /** The control ship's build site. */
    private static final int SHIP_X = 3600, SHIP_Y = 64, SHIP_Z = 3600;
    /** The subject craft, far enough that the control ship is unloaded while it is flown. */
    private static final int CRAFT_X = 4600, CRAFT_Y = 71, CRAFT_Z = 4600;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void aCraftThatNeverBecameAShipTakesNoOrdersFromItsPilot() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                serverHasVs());

        controlARealShipDoesTakeOrders();
        theUnassembledCraftDoesNot();
    }

    // ---- Leg 1: the control ---------------------------------------------------------------------

    /**
     * A REAL assembled ship, driven by the same keys and read through the same counters as leg 2.
     * Everything here must MOVE; it is the proof that leg 2's zeroes measure the production gate.
     */
    private void controlARealShipDoesTakeOrders() throws Exception {
        exec("tp @a " + (SHIP_X + 600) + " 120 " + (SHIP_Z + 600) + " 0 0");
        bot().waitTicks(10);

        String assemble = assembleShip();
        assertTrue("ARRANGEMENT: a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        int ships = 0;
        for (int i = 0; i < 40 && ships < 1; i++) {
            bot().waitTicks(5);
            ships = count("ship-count-all");
        }
        assertTrue("ARRANGEMENT: assembly must create a VS ship (all=" + ships + ")", ships >= 1);
        bot().waitTicks(40);

        exec("tp @a " + (SHIP_X + 0.5) + " " + (SHIP_Y + 6) + " " + (SHIP_Z + 0.5) + " 0 0");
        bot().waitTicks(20);
        for (int i = 0; i < 40 && count("ship-count") < 1; i++) {
            bot().waitTicks(5);
        }

        String mountInfo = exec("artest vs seat-mount 0");
        assertTrue("ARRANGEMENT: seat-mount must find the ship's pilot seat: " + mountInfo,
                mountInfo.contains("\"seatFound\":true"));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("ARRANGEMENT: seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("ARRANGEMENT: the bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10); // let the mount replicate and the client recognise the pilot seat

        // A command key: the jump key, chosen because a ship with no hyperdrive answers it with a
        // refusal and changes no flight state — so this control cannot perturb anything downstream.
        int commandsBefore = commandsReceived();
        tapKey(Keyboard.KEY_J);
        int commandsAfter = awaitAtLeast(this::commandsReceived, commandsBefore + 1, 20);
        assertTrue("CONTROL: pressing a command key while piloting a REAL ship must reach the ship "
                        + "(commandsReceived " + commandsBefore + " -> " + commandsAfter + "). If this "
                        + "fails, the harness's injected key never reaches the edge-triggered handler "
                        + "and leg 2 below proves nothing at all.",
                commandsAfter > commandsBefore);

        // And the per-tick steering path, the other gate leg 2 measures.
        int inputBefore = inputPacketsReceived();
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        int inputAfter;
        try {
            inputAfter = awaitAtLeast(this::inputPacketsReceived, inputBefore + 1, 20);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        assertTrue("CONTROL: holding a flight key while piloting a REAL ship must deliver pilot "
                        + "input to the server (received " + inputBefore + " -> " + inputAfter + ")",
                inputAfter > inputBefore);

        exec("artest player dismount");
        bot().waitTicks(10);
    }

    // ---- Leg 2: the subject ---------------------------------------------------------------------

    /** The craft whose assembly never produced a ship: linked, seated in, and completely deaf. */
    private void theUnassembledCraftDoesNot() throws Exception {
        exec("artest chunk warmup 0 " + ((CRAFT_X - 8) >> 4) + " " + ((CRAFT_Z - 8) >> 4)
                + " " + ((CRAFT_X + 8) >> 4) + " " + ((CRAFT_Z + 8) >> 4));
        exec("artest fill 0 " + (CRAFT_X - 3) + " " + (CRAFT_Y - 1) + " " + (CRAFT_Z - 3)
                + " " + (CRAFT_X + 3) + " " + (CRAFT_Y - 1) + " " + (CRAFT_Z + 3) + " minecraft:obsidian");
        exec("artest fill 0 " + (CRAFT_X - 3) + " " + CRAFT_Y + " " + (CRAFT_Z - 3)
                + " " + (CRAFT_X + 3) + " " + (CRAFT_Y + 4) + " " + (CRAFT_Z + 3) + " minecraft:air");
        String seat = exec("artest fill 0 " + CRAFT_X + " " + CRAFT_Y + " " + CRAFT_Z
                + " " + CRAFT_X + " " + CRAFT_Y + " " + CRAFT_Z + " advancedrocketry:pilotSeat");
        assertTrue("ARRANGEMENT: placing the pilot seat failed: " + seat, seat.contains("\"ok\":true"));
        String afc = exec("artest fill 0 " + CRAFT_X + " " + (CRAFT_Y + 2) + " " + CRAFT_Z
                + " " + CRAFT_X + " " + (CRAFT_Y + 2) + " " + CRAFT_Z
                + " advancedrocketry:advancedFlightComputer");
        assertTrue("ARRANGEMENT: placing the flight computer failed: " + afc, afc.contains("\"ok\":true"));

        // Link the two WITHOUT assembling: exactly what the assembler leaves behind when the
        // physics mod rejects the spawn. A real failed assembly cannot be arranged deterministically.
        String linked = exec("artest vs seat-link 0 " + CRAFT_X + " " + CRAFT_Y + " " + CRAFT_Z
                + " " + CRAFT_X + " " + (CRAFT_Y + 2) + " " + CRAFT_Z);
        assertTrue("ARRANGEMENT: the seat must end up LINKED — an unlinked seat is refused for a "
                        + "reason that has nothing to do with this bug: " + linked,
                linked.contains("\"linked\":true"));
        assertTrue("ARRANGEMENT CONTROL: and NO ship may manage it — otherwise the craft is simply "
                        + "a ship and the refusal under test would be wrong: " + linked,
                linked.contains("\"managedByShip\":false"));

        standBesideTheSeat();
        emptyTheHand();
        bot().interactBlock(CRAFT_X, CRAFT_Y, CRAFT_Z);
        JsonObject riding = awaitRiding(30, true);
        assertTrue("ARRANGEMENT: the right-click must seat the player: " + riding, isRiding(riding));

        // The seat's own notice — also the positive control for the action-bar instrument, so the
        // "no answer to a command key" assertion below is silence from a microphone known to work.
        String overlay = awaitOverlayContaining("not assembled", 30);
        assertTrue("sitting on a craft that never assembled must answer with the \"not assembled\" "
                        + "action-bar notice. overlay=\"" + overlay + "\"",
                overlay.toLowerCase(Locale.ROOT).contains("not assembled"));
        awaitOverlayExpired(200);

        int inputBefore = inputPacketsReceived();
        int commandsBefore = commandsReceived();

        // Command it: Flight Assist, auto-takeoff, jump — the three edge-triggered keys. Checked
        // FIRST so that a build where BOTH gates leak still reports the command leak (an assertion
        // that never runs measures nothing, and the steering leg alone would abort ahead of it).
        tapKey(Keyboard.KEY_N);
        tapKey(Keyboard.KEY_K);
        tapKey(Keyboard.KEY_J);
        bot().waitTicks(20);
        int commandsAfter = commandsReceived();
        assertTrue("a craft that never became a ship must not be COMMANDED: Flight Assist / "
                        + "auto-takeoff / jump reached its computer (commandsReceived "
                        + commandsBefore + " -> " + commandsAfter + "), which then answers the pilot "
                        + "from a flight state that does not exist.",
                commandsAfter == commandsBefore);

        // ...and steer it: hold the same flight keys that lift a real ship, for long enough that
        // the client's per-tick sampler has run many times over.
        bot().holdKey(Keyboard.KEY_R);
        bot().holdKey(Keyboard.KEY_W);
        try {
            bot().waitTicks(40);
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
            bot().releaseKey(Keyboard.KEY_R);
        }
        bot().waitTicks(10);
        int inputAfter = inputPacketsReceived();
        assertTrue("a craft that never became a ship must not be STEERED: holding the flight keys "
                        + "while seated in it delivered pilot input to the server (received "
                        + inputBefore + " -> " + inputAfter + "). The seat's link is a build-time "
                        + "intention that survives a rejected assembly; it is not a ship.",
                inputAfter == inputBefore);

        int overlayTicks = bot().reportChat(1).get("overlayTicks").getAsInt();
        assertTrue("and the player must get no answer from a gate that should never have been "
                        + "consulted — the only message this craft owes him is the \"not assembled\" "
                        + "notice at sit-down (overlayTicks=" + overlayTicks + ")",
                overlayTicks == 0);
        assertTrue("the refused pilot must still be SEATED — the craft is deaf, not ejecting",
                isRiding(bot().reportRidingEntity()));

        exec("artest player dismount");
    }

    // ---- Instruments ----------------------------------------------------------------------------

    /** Pilot-input packets that reached the server JVM, across all seats. */
    private int inputPacketsReceived() throws Exception {
        return readInt(exec("artest vs seat-delivery"), RECEIVED);
    }

    /** Edge-triggered command packets (Flight Assist / auto-takeoff / jump) that reached the server. */
    private int commandsReceived() throws Exception {
        return readInt(exec("artest vs seat-delivery"), COMMANDS);
    }

    /** One press-and-release of a key binding, as the keyboard would deliver it. */
    private void tapKey(int keyCode) throws Exception {
        bot().holdKey(keyCode);
        bot().waitTicks(2);
        bot().releaseKey(keyCode);
        bot().waitTicks(4);
    }

    /** Poll a counter until it reaches {@code target} (bounded); returns the last sample. */
    private int awaitAtLeast(Counter counter, int target, int samples) throws Exception {
        int value = counter.read();
        for (int i = 0; i < samples && value < target; i++) {
            bot().waitTicks(5);
            value = counter.read();
        }
        return value;
    }

    private interface Counter {
        int read() throws Exception;
    }

    // ---- Arrangement helpers ---------------------------------------------------------------------

    private void standBesideTheSeat() throws Exception {
        double distSq = Double.POSITIVE_INFINITY;
        JsonObject state = null;
        for (int attempt = 0; attempt < 6 && distSq >= 25.0; attempt++) {
            exec("tp @a " + (CRAFT_X + 0.5) + " " + CRAFT_Y + " " + (CRAFT_Z + 1.5) + " 0 0");
            bot().waitTicks(20);
            state = bot().reportState();
            if (state.has("worldReady") && state.get("worldReady").getAsBoolean()) {
                double dx = state.get("playerX").getAsDouble() - (CRAFT_X + 0.5);
                double dy = state.get("playerY").getAsDouble() - CRAFT_Y;
                double dz = state.get("playerZ").getAsDouble() - (CRAFT_Z + 0.5);
                distSq = dx * dx + dy * dy + dz * dz;
            }
        }
        assertTrue("ARRANGEMENT: the client must observably stand within reach of the seat, or the "
                + "right-click is dropped before the block sees it. state=" + state, distSq < 25.0);
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

    private String assembleShip() throws Exception {
        int cx1 = (SHIP_X - 2) >> 4, cz1 = (SHIP_Z - 2) >> 4;
        int cx2 = (SHIP_X + 7) >> 4, cz2 = (SHIP_Z + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (SHIP_X - 2) + " " + (SHIP_Y + 1) + " " + (SHIP_Z - 2)
                        + " " + (SHIP_X + 7) + " " + (SHIP_Y + 10) + " " + (SHIP_Z + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + SHIP_X + " " + SHIP_Y + " " + SHIP_Z
                + " with-pilot-seat");
        assertTrue("fixture (with-pilot-seat) failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    // ---- Observation helpers ---------------------------------------------------------------------

    private JsonObject awaitRiding(int samples, boolean want) throws Exception {
        JsonObject riding = null;
        for (int i = 0; i < samples; i++) {
            riding = bot().reportRidingEntity();
            if (isRiding(riding) == want) {
                break;
            }
            bot().waitTicks(5);
        }
        return riding;
    }

    private String awaitOverlayContaining(String needle, int samples) throws Exception {
        String overlay = "";
        for (int i = 0; i < samples; i++) {
            overlay = bot().reportChat(1).get("overlay").getAsString();
            if (overlay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                break;
            }
            bot().waitTicks(5);
        }
        return overlay;
    }

    private void awaitOverlayExpired(int maxTicks) throws Exception {
        for (int waited = 0; waited < maxTicks; waited += 10) {
            if (bot().reportChat(1).get("overlayTicks").getAsInt() <= 0) {
                return;
            }
            bot().waitTicks(10);
        }
        assertTrue("ARRANGEMENT: the previous action-bar message never expired within " + maxTicks
                + " ticks", false);
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private int readInt(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }
}
