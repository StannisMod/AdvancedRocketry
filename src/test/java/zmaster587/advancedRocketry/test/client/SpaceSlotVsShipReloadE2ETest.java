package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * The hardening check the data-survival server test could not reach: with a real client present, a
 * VS ship in a space pool slot must physically RE-LOAD as a live physics object after the slot is
 * unloaded and reloaded (a rebind). This exercises the riskiest path — a synchronous world removal
 * while VS has actually loaded the ship and is ticking its physics — which headless cannot, since VS
 * only loads a ship when an observer is near it.
 *
 * <p>Flow: assemble a ship in a pool slot; a bot enters the pool dimension so VS loads the ship;
 * the bot leaves (a world with a player cannot be unloaded); the slot is rebound (unload saves the
 * ship, reload restores its cell); the bot returns and the ship must load again. Gated on real VS —
 * run with {@code -PwithVS}.</p>
 */
public class SpaceSlotVsShipReloadE2ETest extends AbstractClientE2ETest {

    private static final Pattern SLOT = Pattern.compile("\"slot\":(-?\\d+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");

    /** Enter the pool dim, then move the bot right on top of the ship (assembled around 0..2,64..66)
     *  so VS proximity-loads it. */
    private void enterPoolNearShip(int slot) throws Exception {
        exec("artest tp " + slot);
        exec("tp @a 1 68 1");
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    /** Poll the loaded-ship count in the pool dim until >= 1 (bounded). */
    private int pollLoaded(int dim, int tries) throws Exception {
        int c = -1;
        for (int i = 0; i < tries && c < 1; i++) {
            bot().waitTicks(5);
            Matcher m = COUNT.matcher(exec("artest vs ship-count " + dim));
            c = m.find() ? Integer.parseInt(m.group(1)) : -1;
        }
        return c;
    }

    @Test
    public void vsShipReloadsLiveAfterASlotRebind() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());

        // Assemble a ship in a fresh pool slot (cell "deep").
        String asm = exec("artest space vs-assemble deep");
        Matcher m = SLOT.matcher(asm);
        assertTrue("vs-assemble must report a slot dim: " + asm, m.find());
        int slot = Integer.parseInt(m.group(1));
        bot().waitTicks(40); // let VS process the async spawn queue

        // Sanity: the ship must exist in VS's queryable registry (assembly succeeded).
        Matcher cm = COUNT.matcher(exec("artest vs ship-count-all " + slot));
        int queryable = cm.find() ? Integer.parseInt(cm.group(1)) : -1;
        assertTrue("a ship must be created in the pool world's registry: count-all=" + queryable,
                queryable >= 1);

        // Bot enters the pool dim ON the ship so VS proximity-loads it (physics active).
        enterPoolNearShip(slot);
        int loaded = pollLoaded(slot, 60);
        assertTrue("the ship must LOAD (physics) with a client on it in the pool dim: loaded=" + loaded,
                loaded >= 1);

        // Bot leaves (a world with a player cannot unload), then rebind the slot.
        exec("artest tp 0");
        bot().waitTicks(20);
        assertTrue("slot must reload after the ship's world is unloaded: ",
                exec("artest space reload " + slot + " deep").contains("\"present\":true"));
        bot().waitTicks(20);

        // Bot returns onto the ship; it must RE-LOAD live after the rebind.
        enterPoolNearShip(slot);
        int loadedAfter = pollLoaded(slot, 60);
        assertTrue("the ship must RE-LOAD live after the slot rebind: loadedAfter=" + loadedAfter,
                loadedAfter >= 1);

        exec("artest tp 0");
    }
}
