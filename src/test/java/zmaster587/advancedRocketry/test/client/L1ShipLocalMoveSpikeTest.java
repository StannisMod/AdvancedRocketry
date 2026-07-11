package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE, NOT A CONTRACT TEST. Delete once its question is answered.
 *
 * <p>Question (L1): can AR claim {@code Entity.move} <em>before</em> the physics mod and suppress
 * its world-frame collision handling? That is the prerequisite for resolving an aboard entity's
 * collision in the ship's own frame.</p>
 *
 * <p>Subject is a dropped ITEM, not the player: the physics mod also feeds a player's ship
 * association from the client movement packet, so a player cannot tell us who owns the server-side
 * {@code Entity.move}. An item's movement is driven only by the server tick.</p>
 *
 * <p>Reads, in order:</p>
 * <ol>
 *   <li>control - item resting on the deck with the hook off: it must be associated with the ship
 *       and its "ticks since it touched a ship" must be 0 (the mod's hook is refreshing it);</li>
 *   <li>observe - hook armed but not cancelling: the fire counter must rise, which is the only
 *       honest proof the injection exists at all (a failed @Inject is silent in dev);</li>
 *   <li>takeover - hook cancels the vanilla move: if OUR callback runs first, the mod's callback
 *       never executes, it stops refreshing the association, and the counter starts climbing.</li>
 * </ol>
 */
public class L1ShipLocalMoveSpikeTest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");
    private static final Pattern FIRES = Pattern.compile("\"fires\":(-?\\d+)");
    private static final Pattern TICKS_SINCE = Pattern.compile("\"ticksSinceTouchedShip\":(-?\\d+)");
    private static final Pattern SUBJ_Y = Pattern.compile("\"playerY\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 2900, BY = 64, BZ = 2900;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private int readInt(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected an integer in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    @Test
    public void canArClaimEntityMoveBeforeThePhysicsMod() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());

        exec("artest vs shiplocal off");
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        int all = 0;
        for (int i = 0; i < 40 && all < 1; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a ship (all=" + all + ")", all >= 1);
        bot().waitTicks(40);

        // Stay near enough to keep the ship loaded, far enough not to touch the subject item.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 8) + " " + (BZ + 0.5) + " 0 0");
        int loaded = 0;
        for (int i = 0; i < 40 && loaded < 1; i++) {
            bot().waitTicks(5);
            loaded = count("ship-count");
        }
        assertTrue("the ship must LOAD with the client present", loaded >= 1);

        String where = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
        assertTrue("ship must be managed: " + where, where.contains("\"managed\":true"));
        double sx = readDouble(where, POS_X), sy = readDouble(where, POS_Y), sz = readDouble(where, POS_Z);

        // The deck surface: drop a probe item and let it settle, so we know where "resting on the
        // hull" actually is before spawning the two witnesses there.
        String settle = exec("artest vs drop-item 0 " + sx + " " + (sy + 3) + " " + sz);
        int settleId = readInt(settle, ENTITY_ID);
        bot().waitTicks(60);
        String settled = exec("artest vs player-ship-data 0 " + settleId);
        System.out.println("[L1] settled probe item: " + settled);
        assertTrue("an item resting on the deck must be associated with the ship: " + settled,
                settled.contains("\"lastTouchedShip\":\""));
        double deckY = readDouble(settled, SUBJ_Y);

        // 1. OBSERVE: arm the hook on the settled item WITHOUT cancelling. This is the only honest
        //    proof the injection exists at all (a failed @Inject is silent in dev).
        exec("artest vs shiplocal observe " + settleId);
        bot().waitTicks(40);
        String observeStatus = exec("artest vs shiplocal status");
        System.out.println("[L1] shiplocal status after observe: " + observeStatus);
        int firesObserved = readInt(observeStatus, FIRES);
        assertTrue("our Entity.move injection must actually fire; fires=" + firesObserved
                + ": " + observeStatus, firesObserved > 0);
        exec("artest vs shiplocal off");

        // 2. CONTROL WITNESS: a FRESH item spawned already resting on the deck, hook OFF. Its ship
        //    association starts null; if the physics mod's hook runs it becomes non-null. This proves
        //    the witness is sensitive (association does happen without any falling).
        String ctrlDrop = exec("artest vs drop-item 0 " + sx + " " + deckY + " " + sz);
        int ctrlId = readInt(ctrlDrop, ENTITY_ID);
        bot().waitTicks(30);
        String ctrl = exec("artest vs player-ship-data 0 " + ctrlId);
        System.out.println("[L1] control witness (hook OFF, fresh item on deck): " + ctrl);
        assertTrue("the witness must be sensitive: with the hook off, a fresh item resting on the"
                + " deck MUST acquire a ship association: " + ctrl,
                ctrl.contains("\"lastTouchedShip\":\""));

        // 3. TAKEOVER WITNESS: an identical FRESH item, but the hook is armed to cancel BEFORE it is
        //    ever spawned. If our callback runs first, the physics mod's callback never executes and
        //    the association can never be set.
        String takeDrop = exec("artest vs drop-item 0 " + sx + " " + deckY + " " + sz + " takeover");
        int takeId = readInt(takeDrop, ENTITY_ID);
        System.out.println("[L1] takeover witness spawned armed: " + takeDrop);
        bot().waitTicks(60);
        String taken = exec("artest vs player-ship-data 0 " + takeId);
        String afterStatus = exec("artest vs shiplocal status");
        System.out.println("[L1] takeover witness: " + taken);
        System.out.println("[L1] shiplocal status: " + afterStatus);
        exec("artest vs shiplocal off");

        int firesAfter = readInt(afterStatus, FIRES);
        assertTrue("the armed hook must have fired for the takeover witness; fires=" + firesAfter,
                firesAfter > 0);

        boolean physicsModRan = taken.contains("\"lastTouchedShip\":\"");
        System.out.println("[L1] VERDICT: physics-mod hook ran despite our cancel = " + physicsModRan
                + " (false means AR's callback runs FIRST and can suppress it)");

        assertTrue("AR must be able to claim Entity.move BEFORE the physics mod: with our HEAD"
                + " callback cancelling from the entity's very first tick, the physics mod must never"
                + " get to associate it with a ship."
                + "\n  control witness (hook off) = " + ctrl
                + "\n  takeover witness           = " + taken,
                !physicsModRan);
    }
}
