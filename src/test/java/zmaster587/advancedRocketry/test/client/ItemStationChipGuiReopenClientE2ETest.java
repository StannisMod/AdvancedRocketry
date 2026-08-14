package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Repro for finding C010 (HIGH),
 * the MANDATORY player-visible client side.
 *
 * <p>Two GUI handlers are registered on the same {@code AdvancedRocketry
 * .instance} mod container (AdvancedRocketry.java:993 libVulpes, :995 AR);
 * Forge keys the handler map by mod container, so the AR handler (last)
 * wins and it serves only {@code guiId.OreMappingSatellite}. When a player
 * presses any button in the Space-Station-Chip GUI, {@code
 * ItemStationChip.useNetworkData:208-210} unconditionally runs
 * {@code closeScreen()} then {@code openGui(AdvancedRocketry.instance,
 * MODULARFULLSCREEN)} — but the AR handler returns null for
 * MODULARFULLSCREEN (ordinal 3 ≠ OreMappingSatellite), so no GUI packet is
 * sent and the screen stays closed. The chip GUI becomes unusable: every
 * button press dismisses it instead of refreshing it.</p>
 *
 * <p>The initial open ({@code ItemStationChip.onItemRightClick:81}) targets
 * {@code LibVulpes.instance} and works — so the bug only shows on the
 * button-press RE-open. Stimulus is the real client (sneak-right-click to
 * open, then a real GUI button click); observation is real client screen
 * state via {@code reportState().screen}.</p>
 *
 * <p><b>Corrected contract, pinned here (C010 fix, Path B)</b>: after a
 * button press the GUI re-opens as {@code GuiModularFullScreen} — the AR
 * GuiHandler now delegates non-AR (libVulpes MODULAR*) ids to the libVulpes
 * handler instead of returning null. This test previously pinned the empty
 * screen (polarity flipped when the fix landed). Recorded as a known defect.</p>
 */
public class ItemStationChipGuiReopenClientE2ETest extends AbstractClientE2ETest {

    private static final int LSHIFT = 42;
    private static final String CHIP = "advancedrocketry:spacestationchip";

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    /** Poll the CLIENT-rendered held item until it is {@code itemId} (~10 s). */
    private void waitForHeld(String itemId) throws Exception {
        String held = "";
        for (int waited = 0; waited < 200; waited += 5) {
            bot().waitTicks(5);
            held = bot().reportPlayerItems().getAsJsonObject("held").get("id").getAsString();
            if (itemId.equals(held)) return;
        }
        throw new AssertionError("client never rendered " + itemId + " in hand; held=" + held);
    }

    /** Poll the client screen until it is non-empty (open) or empty (closed). */
    private String pollScreen(boolean untilOpen) throws Exception {
        String screen = "";
        for (int waited = 0; waited < 10000; waited += 200) {
            bot().waitTicks(4);
            screen = bot().reportState().get("screen").getAsString();
            if (untilOpen && !screen.isEmpty()) return screen;
            if (!untilOpen && screen.isEmpty()) return screen;
        }
        return screen;
    }

    @Test
    public void chipButtonPressReopensGuiAsFullScreen() throws Exception {
        bot().waitForWorld();

        String equip = exec("artest player equip-stationchip");
        assertTrue("equip-stationchip must succeed: " + equip, equip.contains("\"ok\":true"));
        waitForHeld(CHIP);

        // Sneak + right-click opens the chip's libVulpes modular GUI (this open
        // targets LibVulpes.instance, so it works even on the buggy build).
        bot().setKey(LSHIFT, true);
        bot().waitTicks(6);
        bot().useItem();
        String opened = pollScreen(true);
        bot().setKey(LSHIFT, false);
        assertTrue("chip GUI must open on sneak-right-click; screen=" + opened,
                opened.startsWith("zmaster587.libVulpes.inventory.GuiModular"));

        // Press the chip's DELETE button (BUTTON_ID_DELETE == 1). With the
        // default selection (0) DELETE is a no-op except the unconditional
        // re-open at useNetworkData:208-210 — so it isolates the re-open without
        // mutating the landing list. (clickButtonById targets the ModuleButton
        // id, as in RocketBuilderGuiE2ETest; arr.get(0) is a libVulpes chrome
        // button that does not route to the item's useNetworkData.)
        JsonObject buttons = bot().reportButtons();
        JsonArray arr = buttons.getAsJsonArray("buttons");
        assertTrue("chip GUI must render buttons: " + buttons,
                arr != null && arr.size() > 0);
        bot().clickButtonById(1);

        // Fixed: the button press re-opens the GUI as GuiModularFullScreen (the
        // AR GuiHandler now delegates MODULARFULLSCREEN to libVulpes). Note
        // "GuiModular" is a substring of "GuiModularFullScreen" — match the full
        // name so the initial centered GuiModular does not satisfy this.
        String after = "";
        for (int waited = 0; waited < 10000; waited += 200) {
            bot().waitTicks(4);
            after = bot().reportState().get("screen").getAsString();
            if (after.contains("GuiModularFullScreen")) break;
        }
        assertTrue("after the C010 fix, pressing a Space-Station-Chip button must "
                + "re-open the GUI as GuiModularFullScreen — the AR GuiHandler now "
                + "delegates MODULARFULLSCREEN (opened on AdvancedRocketry.instance) "
                + "to the libVulpes handler instead of returning null. after=" + after,
                after.contains("GuiModularFullScreen"));
    }
}
