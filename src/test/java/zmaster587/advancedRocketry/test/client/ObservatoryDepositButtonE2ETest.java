package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.client.ClientGuiTestSupport.openGuiByRightClick;

/**
 * The one human act in the tier-1/tier-2 knowledge loop: a player standing at an observatory presses
 * <b>Deposit</b>, and the world he is standing on learns the addresses on the crystal in the machine.
 *
 * <p>The server tier already pins the path from the button's handler onwards. What it structurally
 * cannot pin is the CLICK: that the control exists on the survey tab, that it is enabled, and that
 * pressing it reaches the tile. That gap is a client e2e and not a playtest, so it lives here.</p>
 *
 * <p>The assertion is deliberately made on the SERVER's answer afterwards - `planet knowledge` asks
 * the production gate a rocket asks - rather than on anything the GUI says about itself. A button
 * that lights up and does nothing would satisfy a screen-scraping test and fail this one.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ObservatoryDepositButtonE2ETest extends AbstractSharedClientE2ETest {

    /** The survey tab of the observatory GUI: data, asteroid, region-scan. */
    private static final int TAB_REGION_SCAN = 2;
    /** The Deposit control's own id on that tab. */
    private static final int BUTTON_DEPOSIT = 10;

    private static final int X = 5200;
    private static final int Y = 64;
    private static final int Z = 5200;

    @Override
    protected String subsystem() {
        return "knowledge-deposit";
    }

    @Test
    public void pressingDepositTeachesTheWorldTheCrystalsAddresses() throws Exception {
        String where = "0 " + X + " " + Y + " " + Z;

        // The address on the crystal names a world MINTED for this test, and deliberately not one a
        // survey found: a survey teaches the world it is made from as it goes, so a crystal filled
        // by sweeping here would hold only things this world already knows - and the click could
        // then teach nothing and still look successful.
        exec("artest config set planetsMustBeDiscovered true");
        int fresh;
        try {
            String installed = exec("artest space gen-install 0.9 2000000 987654321");
            assertTrue("the procedural generator must install: " + installed,
                    installed.contains("\"ok\":true"));
            String found = exec("artest space find-procedural 4");
            assertTrue("a dense procedural galaxy must offer a landable body: " + found,
                    found.contains("\"ok\":true"));
            String realized = exec("artest space realize " + intOf(found, "sx") + " "
                    + intOf(found, "sy") + " " + intOf(found, "sz"));
            assertTrue("realization must mint a world: " + realized, realized.contains("\"ok\":true"));
            fresh = intOf(realized, "dim");
        } finally {
            exec("artest space gen-reset");
        }

        String before = exec("artest planet knowledge 0 " + fresh);
        assertTrue("arrangement: a just-minted world must be unknown here: " + before,
                before.contains("\"local\":false"));
        assertTrue("arrangement: and unknown to the pack: " + before,
                before.contains("\"global\":false"));
        // The COMPLETE multiblock, not a lone block: the survey tab is a machine's GUI, and a test
        // that opened a half-built one would be measuring the incomplete panel.
        // The COMPLETE multiblock, not a lone block: the survey tab is a machine's GUI, and a test
        // that opened a half-built one would be measuring the incomplete panel.
        String built = exec("artest fixture multiblock observatory 0 " + X + " " + Y + " " + Z);
        assertTrue("could not build an observatory: " + built, built.contains("\"ok\":true"));
        String crystal = exec("artest telescope crystal " + where + " " + fresh);
        assertTrue("the machine must hold a crystal naming exactly that world: " + crystal,
                crystal.contains("\"addresses\":1"));
        String info = exec("artest telescope info " + where);
        assertTrue("and the probe must see it there without depositing anything: " + info,
                info.contains("\"crystalDims\":[" + fresh + "]"));

        // Stand at the machine and open its GUI the way a player does.
        exec("tp @a " + (X + 0.5) + " " + (Y + 2) + " " + (Z + 2.5) + " 0 30");
        bot().waitTicks(20);
        String screen = openGuiByRightClick(bot(), X, Y, Z);
        assertTrue("right-clicking the observatory must open a GUI, got: " + screen,
                screen.contains("Gui"));

        bot().clickButtonById(TAB_REGION_SCAN);
        bot().waitTicks(10);
        bot().clickButtonById(BUTTON_DEPOSIT);
        bot().waitTicks(20);

        // The button's promise, asked of the server: the address the machine held is now something a
        // tier-1 pad standing on this world may be aimed at, and it is known LOCALLY - a deposit may
        // not touch the pack's global floor.
        String after = exec("artest planet knowledge 0 " + fresh);
        assertTrue("after the click a pad here must be offered that world: " + after,
                after.contains("\"known\":true"));
        assertTrue("and it must be known LOCALLY, not announced to the whole game: " + after,
                after.contains("\"local\":true"));
        assertTrue("the pack's own floor must be untouched: " + after,
                after.contains("\"global\":false"));
    }

    /** A numeric field of a probe reply. */
    private static int intOf(String json, String name) {
        String key = "\"" + name + "\":";
        int at = json.indexOf(key);
        assertTrue("probe reply has no field " + name + ": " + json, at >= 0);
        int from = at + key.length();
        int to = from;
        while (to < json.length() && "-0123456789".indexOf(json.charAt(to)) >= 0) {
            to++;
        }
        return Integer.parseInt(json.substring(from, to));
    }
}
