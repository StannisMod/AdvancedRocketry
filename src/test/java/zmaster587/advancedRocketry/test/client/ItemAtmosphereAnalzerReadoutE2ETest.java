package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonArray;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Player-visible side of
 * {@code ItemAtmosphereAnalzer#onItemRightClick}, driven through the REAL
 * client item-use path ({@code ClientBot.useItem}) and observed on the REAL
 * client chat overlay ({@code reportChat}) — i18n already resolved, exactly
 * the two lines the player reads.
 *
 * <p>Dim 0 has no AtmosphereHandler &rarr; production falls back to
 * {@code AtmosphereType.AIR}. Both lines must reach the player's screen:
 * "Atmosphere Type: …air…" and "Breathable: yes".</p>
 */
public class ItemAtmosphereAnalzerReadoutE2ETest extends AbstractClientE2ETest {

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    /** Polls until the CLIENT renders {@code itemId} in the main hand (~10 s cap)
     *  — server-side equips need a sync round-trip before the click. */
    private void waitForHeld(String itemId) throws Exception {
        String held = "";
        for (int waited = 0; waited < 200; waited += 5) {
            bot().waitTicks(5);
            held = bot().reportPlayerItems().getAsJsonObject("held").get("id").getAsString();
            if (itemId.equals(held)) return;
        }
        throw new AssertionError("client never rendered " + itemId
                + " in hand; held=" + held);
    }

    @Test
    public void rightClickInVanillaDimDispatchesAirReadoutToPlayerChat() throws Exception {
        bot().waitForWorld();
        String give = exec("artest player give-held advancedrocketry:atmanalyser");
        assertTrue("give-held atmanalyser must succeed: " + give,
                give.contains("\"ok\":true"));
        waitForHeld("advancedrocketry:atmanalyser");

        // The REAL right-click from the client.
        bot().useItem();

        // Both readout lines must land on the CLIENT chat overlay, with the
        // lang keys resolved (msg.atmanal.atmtype -> "Atmosphere Type: ",
        // msg.atmanal.canbreathe -> "Breathable: " + msg.yes -> "yes").
        boolean sawType = false;
        boolean sawBreathableYes = false;
        for (int waited = 0; waited < 100 && !(sawType && sawBreathableYes); waited += 10) {
            bot().waitTicks(10);
            JsonArray lines = bot().reportChat(10).getAsJsonArray("lines");
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).getAsString().toLowerCase(java.util.Locale.ROOT);
                if (line.contains("atmosphere type") && line.contains("air")) sawType = true;
                if (line.contains("breathable")) {
                    assertTrue("breathable line must read 'yes' for AIR, got: " + line,
                            line.contains("yes"));
                    sawBreathableYes = true;
                }
            }
        }
        assertTrue("client chat must show the resolved 'Atmosphere Type: …air' line", sawType);
        assertTrue("client chat must show the resolved 'Breathable: yes' line", sawBreathableYes);
    }
}
