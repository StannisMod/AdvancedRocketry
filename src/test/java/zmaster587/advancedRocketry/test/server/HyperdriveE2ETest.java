package zmaster587.advancedRocketry.test.server;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The hyperdrive family in a real world: what a player gets for what he builds, and what the helm
 * does when he presses the key.
 *
 * <p>Everything here goes through production — the generator's own scan, the capacitor's own charge
 * arithmetic, the real jump gate, and the same {@code onJumpKey} the pilot seat calls when the key
 * is pressed. The probe places blocks and reads answers; it never computes one.</p>
 *
 * <p>No balance number is asserted. What is asserted is what a player can rely on while building:
 * more coils is more drive, more cells is more bank, more sinks is a shorter wait, a machine that
 * belongs to another ship is not yours, and the pilot is never charged for a jump that did not
 * happen.</p>
 */
public class HyperdriveE2ETest extends AbstractSharedServerTest {

    // Build sites well clear of every other fixture in the shared world, and far enough apart that
    // one fixture's footprint can never reach into another's. Each family of tests gets its own
    // ship: the hull tests write a hull extent that persists on the flight computer, and a helm test
    // inheriting an oversized hull would meet an advisory it never asked for.
    private static final String SHIP_A = "2600 82 2600";   // what the build is worth
    private static final String SHIP_B = "2660 82 2660";   // the neighbour that must lend nothing
    private static final String SHIP_C = "2720 82 2720";   // the window against the hull
    private static final String SHIP_D = "2780 82 2780";   // the helm
    private static final String NAV_C = "0 2720 80 2720";
    private static final String NAV_D = "0 2780 80 2780";

    private static long field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(-?\\d+)").matcher(json);
        assertTrue("expected a numeric field " + name + " in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private String buildDrive(String afc, int coils, int cells, int sinks,
                              int emitters, int dampeners) throws Exception {
        return exec("artest drive build 0 " + afc + " " + coils + " " + cells + " " + sinks
                + " " + emitters + " " + dampeners);
    }

    // ─── What the build is worth ───────────────────────────────────────────────

    @Test
    public void aBiggerGeneratorIsAStrongerDrive() throws Exception {
        buildDrive(SHIP_A, 2, 2, 1, 0, 0);
        long small = field(exec("artest drive info 0 " + SHIP_A), "drivePower");

        buildDrive(SHIP_A, 8, 2, 1, 0, 0);
        long large = field(exec("artest drive info 0 " + SHIP_A), "drivePower");

        assertTrue("welding more coils to the generator must make the ship's drive stronger: "
                + small + " -> " + large, large > small);
    }

    @Test
    public void aStrongerDriveIsFasterAndCostsMoreToStart() throws Exception {
        buildDrive(SHIP_A, 2, 8, 1, 0, 0);
        String weak = exec("artest drive info 0 " + SHIP_A);

        buildDrive(SHIP_A, 10, 8, 1, 0, 0);
        String strong = exec("artest drive info 0 " + SHIP_A);

        assertTrue("a bigger drive crosses faster",
                field(strong, "speedBlocksPerTick") > field(weak, "speedBlocksPerTick"));
        assertTrue("and asks for a bigger burst to open the window",
                field(strong, "burstCost") > field(weak, "burstCost"));
        assertTrue("and draws more while it holds the window open",
                field(strong, "inFlightDraw") > field(weak, "inFlightDraw"));
    }

    @Test
    public void moreCellsIsMoreBankAndMoreSinksIsAShorterWait() throws Exception {
        buildDrive(SHIP_A, 4, 1, 1, 0, 0);
        String lean = exec("artest drive info 0 " + SHIP_A);

        buildDrive(SHIP_A, 4, 6, 1, 0, 0);
        String bigBank = exec("artest drive info 0 " + SHIP_A);

        assertTrue("cells are what the bank holds",
                field(bigBank, "capacity") > field(lean, "capacity"));

        // Same drive, same bank, more cooling: the wait for the next window must shrink. The
        // cooldown is not a timer anywhere - it is how long this bank takes to reach this burst.
        exec("artest drive charge 0 " + SHIP_A + " empty");
        long slowCooldown = field(exec("artest drive info 0 " + SHIP_A), "cooldownTicks");
        buildDrive(SHIP_A, 4, 6, 6, 0, 0);
        exec("artest drive charge 0 " + SHIP_A + " empty");
        long fastCooldown = field(exec("artest drive info 0 " + SHIP_A), "cooldownTicks");

        assertTrue("precondition: an empty bank really does have a wait", slowCooldown > 0L);
        assertTrue("heat sinks are the whole of the cooling system: " + slowCooldown
                + " -> " + fastCooldown, fastCooldown < slowCooldown);
    }

    @Test
    public void aChargedBankHasNoCooldownAtAll() throws Exception {
        buildDrive(SHIP_A, 4, 8, 2, 0, 0);
        exec("artest drive charge 0 " + SHIP_A + " full");

        String info = exec("artest drive info 0 " + SHIP_A);

        assertEquals("a ship ready to jump is not waiting for anything", 0L,
                field(info, "cooldownTicks"));
        assertTrue("and its bank holds at least the burst",
                field(info, "charge") >= field(info, "burstCost"));
    }

    @Test
    public void anotherShipsMachinesAreNotYours() throws Exception {
        // The two ships stand well apart and each machine is linked to its own flight computer.
        // Without that ownership rule a ship could park beside a friend and borrow his capacitor.
        buildDrive(SHIP_A, 6, 6, 2, 0, 0);
        buildDrive(SHIP_B, 1, 1, 1, 0, 0);

        long a = field(exec("artest drive info 0 " + SHIP_A), "drivePower");
        long b = field(exec("artest drive info 0 " + SHIP_B), "drivePower");

        assertTrue("the big ship keeps its own power", a > b);
        assertTrue("and the small one gains nothing from the neighbour", b > 0L);
    }

    // ─── The window and the hull ───────────────────────────────────────────────

    @Test
    public void aSmallHullFitsInsideTheGeneratorsOwnWindow() throws Exception {
        buildDrive(SHIP_C, 4, 4, 2, 0, 0);
        // A starter craft: a couple of blocks either side of the generator.
        exec("artest drive hull 0 " + SHIP_C + " 0 0 0 3 1 1");

        String info = exec("artest drive info 0 " + SHIP_C);

        assertEquals("a first ship with no emitters at all must still be able to jump", 0L,
                field(info, "hullOutsideWindow"));
        assertTrue(info.contains("\"hullMeasured\":true"));
    }

    @Test
    public void aHullTooBigForTheWindowWarnsAndStillLetsThePilotGo() throws Exception {
        buildDrive(SHIP_C, 4, 8, 2, 0, 0);
        exec("artest drive charge 0 " + SHIP_C + " full");
        exec("artest nav place " + NAV_C);
        exec("artest nav link " + NAV_C + " " + SHIP_C);
        exec("artest nav target " + NAV_C + " 7 0 0");
        // A hull far longer than a bare generator can wrap.
        exec("artest drive hull 0 " + SHIP_C + " -30 -4 -4 30 4 4");

        String info = exec("artest drive info 0 " + SHIP_C);

        assertTrue("part of this hull is outside the window: " + info,
                field(info, "hullOutsideWindow") > 0L);
        assertTrue("which is a warning, never a veto - leaving part of the ship behind is the "
                + "pilot's decision to make: " + info, info.contains("\"allowed\":true"));
        assertTrue("and he is told before he makes it: " + info,
                info.contains("\"confirm\":true"));
        assertTrue(info.contains("msg.jumpgate.windowundersized"));
    }

    @Test
    public void emittersAreWhatMakeALongHullFit() throws Exception {
        buildDrive(SHIP_C, 4, 8, 2, 0, 0);
        exec("artest drive hull 0 " + SHIP_C + " -30 -4 -4 30 4 4");
        long bare = field(exec("artest drive info 0 " + SHIP_C), "hullOutsideWindow");

        buildDrive(SHIP_C, 4, 8, 2, 6, 0);
        long withEmitters = field(exec("artest drive info 0 " + SHIP_C), "hullOutsideWindow");

        assertTrue("precondition: the bare generator leaves this hull sticking out", bare > 0L);
        assertTrue("emitters are an extension for a big hull, and this is what they buy: "
                + bare + " -> " + withEmitters, withEmitters < bare);
    }

    // ─── The helm ──────────────────────────────────────────────────────────────

    @Test
    public void thePilotCannotFireAJumpNobodyArmed() throws Exception {
        buildDrive(SHIP_D, 4, 8, 2, 0, 0);
        exec("artest drive charge 0 " + SHIP_D + " full");
        exec("artest nav place " + NAV_D);
        exec("artest nav link " + NAV_D + " " + SHIP_D);
        exec("artest nav target " + NAV_D + " 7 0 0");
        exec("artest drive arm 0 " + SHIP_D + " off");

        String pressed = exec("artest drive press 0 " + SHIP_D);

        assertTrue("choosing a destination and choosing to go are two separate acts: " + pressed,
                pressed.contains("\"spooling\":false"));
    }

    @Test
    public void armingAtTheConsoleAndPressingAtTheHelmWindsTheDriveUp() throws Exception {
        buildDrive(SHIP_D, 4, 8, 2, 0, 0);
        exec("artest drive charge 0 " + SHIP_D + " full");
        exec("artest nav place " + NAV_D);
        exec("artest nav link " + NAV_D + " " + SHIP_D);
        exec("artest nav target " + NAV_D + " 7 0 0");

        String armed = exec("artest drive arm 0 " + SHIP_D + " on");
        String pressed = exec("artest drive press 0 " + SHIP_D);

        assertTrue("the console is where the pilot commits to a destination: " + armed,
                armed.contains("\"armed\":true"));
        assertTrue("and the helm is where he commits to going: " + pressed,
                pressed.contains("\"spooling\":true"));
    }

    @Test
    public void pressingAgainDuringTheWindUpAbortsItAndCostsNothing() throws Exception {
        buildDrive(SHIP_D, 4, 8, 2, 0, 0);
        exec("artest drive charge 0 " + SHIP_D + " full");
        exec("artest nav place " + NAV_D);
        exec("artest nav link " + NAV_D + " " + SHIP_D);
        exec("artest nav target " + NAV_D + " 7 0 0");
        exec("artest drive arm 0 " + SHIP_D + " on");

        long chargeBefore = field(exec("artest drive info 0 " + SHIP_D), "charge");
        exec("artest drive press 0 " + SHIP_D);
        String aborted = exec("artest drive press 0 " + SHIP_D);
        long chargeAfter = field(exec("artest drive info 0 " + SHIP_D), "charge");

        assertTrue("a second press during the wind-up stops it: " + aborted,
                aborted.contains("\"spooling\":false"));
        assertEquals("and it costs the pilot nothing - the burst is the only thing ever spent, "
                + "and it has not fired", chargeBefore, chargeAfter);
    }

    @Test
    public void aRefusedJumpNeverSpendsTheCharge() throws Exception {
        // Armed, charged, but aimed at nothing: the gate refuses. Asking must stay free, because a
        // pilot is expected to press the key to find out where he stands.
        buildDrive(SHIP_D, 4, 8, 2, 0, 0);
        exec("artest drive charge 0 " + SHIP_D + " full");
        exec("artest nav place " + NAV_D);
        exec("artest nav link " + NAV_D + " " + SHIP_D);
        exec("artest nav target " + NAV_D + " 7 0 0");
        exec("artest drive arm 0 " + SHIP_D + " on");
        exec("artest nav cleartarget " + NAV_D);

        long before = field(exec("artest drive info 0 " + SHIP_D), "charge");
        String pressed = exec("artest drive press 0 " + SHIP_D);
        long after = field(exec("artest drive info 0 " + SHIP_D), "charge");

        assertTrue("a ship with no destination does not wind up: " + pressed,
                pressed.contains("\"spooling\":false"));
        assertEquals("and a refusal is never a loss", before, after);
    }

    @Test
    public void clearingTheTargetDisarmsTheJump() throws Exception {
        // Re-aiming must never leave a ship armed at the answer to a question the pilot has already
        // changed his mind about.
        buildDrive(SHIP_D, 4, 8, 2, 0, 0);
        exec("artest nav place " + NAV_D);
        exec("artest nav link " + NAV_D + " " + SHIP_D);
        exec("artest nav target " + NAV_D + " 7 0 0");
        String armed = exec("artest drive arm 0 " + SHIP_D + " on");

        exec("artest nav target " + NAV_D + " 9 0 0");
        String pressed = exec("artest drive press 0 " + SHIP_D);

        assertTrue("precondition: it really was armed: " + armed, armed.contains("\"armed\":true"));
        assertTrue("a new destination is a new decision: " + pressed,
                pressed.contains("\"spooling\":false"));
    }

    // ─── Dampeners ─────────────────────────────────────────────────────────────

    @Test
    public void dampenersAreFoundAndReportPowered() throws Exception {
        buildDrive(SHIP_A, 4, 4, 2, 0, 3);

        String info = exec("artest drive info 0 " + SHIP_A);

        assertEquals("all three belong to this ship", 3L, field(info, "dampeners"));
        assertEquals("and a dampener with power in its buffer is one that will protect somebody",
                3L, field(info, "poweredDampeners"));
    }
}
