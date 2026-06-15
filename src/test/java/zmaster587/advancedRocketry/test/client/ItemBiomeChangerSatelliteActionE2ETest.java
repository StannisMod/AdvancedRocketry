package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Player-visible side of {@code ItemBiomeChanger#onItemRightClick}, driven
 * through the REAL client item-use path ({@code ClientBot.useItem}).
 *
 * <p>Arrange uses the arrange-only {@code artest player equip-biomechanger}
 * probe (register SatelliteBiomeChanger + equip the NBT-bound chip — no
 * click). The client performs the actual right-click; the satellite's
 * queued-position list is then read back through the
 * {@code artest satellite poslist-size} oracle — server state is the
 * contract here (save-format posList), the CLIENT contributes the stimulus
 * and the held-chip view.</p>
 */
public class ItemBiomeChangerSatelliteActionE2ETest extends AbstractClientE2ETest {

    private static final Pattern SAT_ID = Pattern.compile("\"satId\":(-?\\d+)");
    private static final Pattern POSLIST_SIZE = Pattern.compile("\"posListSize\":(-?\\d+)");

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
    public void rightClickQueuesPositionsIntoSatellitePosList() throws Exception {
        bot().waitForWorld();

        String equip = exec("artest player equip-biomechanger 0");
        assertTrue("equip-biomechanger must succeed: " + equip, equip.contains("\"ok\":true"));
        Matcher m = SAT_ID.matcher(equip);
        assertTrue("equip response must carry satId: " + equip, m.find());
        long satId = Long.parseLong(m.group(1));

        // CLIENT view of the arrange: the chip is in hand (poll — the
        // server-side equip needs a sync round-trip).
        waitForHeld("advancedrocketry:biomechanger");

        String before = exec("artest satellite poslist-size 0 " + satId);
        int posBefore = extractInt(before, POSLIST_SIZE);

        // The REAL right-click from the client.
        bot().useItem();

        // Oracle: the satellite queued at least one (x,y,z) triple.
        int posAfter = -1;
        for (int waited = 0; waited < 100; waited += 10) {
            bot().waitTicks(10);
            posAfter = extractInt(exec("artest satellite poslist-size 0 " + satId), POSLIST_SIZE);
            if (posAfter > posBefore) break;
        }
        assertTrue("right-click must queue positions into the satellite's posList "
                        + "(before=" + posBefore + ", after=" + posAfter + ")",
                posAfter > posBefore);
        assertEquals("posList stores (x,y,z) triples — length must be divisible by 3, got "
                + posAfter, 0, posAfter % 3);
    }

    private static int extractInt(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + src, m.find());
        return Integer.parseInt(m.group(1));
    }
}
