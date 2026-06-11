package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Coverage-audit gap (Tier 3 #12, client slice) — {@code ItemOreScanner}
 * right-click, driven through the REAL client item-use path
 * ({@code ClientBot.useItem} → {@code CPacketPlayerTryUseItem}) with the
 * outcome read from the CLIENT screen state.
 *
 * <p>Arrange uses the arrange-only {@code artest player equip-orescanner}
 * probe (register satellite + seed NBT + equip — no click); the click itself
 * is the client's.</p>
 *
 * <ul>
 *   <li><b>Empty satellite-ID branch</b> — held OreScanner has no NBT →
 *       early-out: no GUI opens on the client, no crash.</li>
 *   <li><b>Resolved satellite-ID branch</b> — a registered
 *       SatelliteOreMapping on dim 0 → the OreMapping GUI must actually
 *       OPEN on the client. (The old probe-driven test only pinned
 *       "no crash" — it could not see whether the GUI opened.)</li>
 * </ul>
 */
public class OreScannerRightClickClientE2ETest extends AbstractClientE2ETest {

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
    public void rightClickWithEmptySatelliteIdOpensNoGuiAndDoesNotCrash() throws Exception {
        bot().waitForWorld();
        String equip = exec("artest player equip-orescanner none");
        assertTrue("equip-orescanner must succeed: " + equip, equip.contains("\"ok\":true"));
        assertTrue("empty branch must report hadSatelliteId:false: " + equip,
                equip.contains("\"hadSatelliteId\":false"));
        waitForHeld("advancedrocketry:orescanner");

        // The REAL right-click from the client.
        bot().useItem();
        bot().waitTicks(20);

        // CLIENT truth: no GUI opened, client still alive and responsive.
        assertEquals("empty-satellite right-click must not open any screen",
                "", bot().reportState().get("screen").getAsString());
    }

    @Test
    public void rightClickWithRegisteredSatelliteIdOpensOreMappingGui() throws Exception {
        bot().waitForWorld();
        String equip = exec("artest player equip-orescanner 0");
        assertTrue("equip-orescanner must succeed: " + equip, equip.contains("\"ok\":true"));
        assertTrue("resolved branch must report hadSatelliteId:true: " + equip,
                equip.contains("\"hadSatelliteId\":true"));
        waitForHeld("advancedrocketry:orescanner");

        // The REAL right-click from the client.
        bot().useItem();

        // CLIENT truth: the OreMapping GUI actually opens on screen.
        String screen = "";
        for (int waited = 0; waited < 100; waited += 10) {
            bot().waitTicks(10);
            screen = bot().reportState().get("screen").getAsString();
            if (!screen.isEmpty()) break;
        }
        assertTrue("right-click with a resolved SatelliteOreMapping must open the "
                + "OreMapping GUI on the client; screen='" + screen + "'",
                screen.contains("OreMapping"));

        bot().closeScreen();
    }
}
