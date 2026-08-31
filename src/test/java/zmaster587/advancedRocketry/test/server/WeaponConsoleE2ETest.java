package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a console buys, and what it must never buy.
 *
 * <p>It buys CONVENIENCE: one target for a whole battery, and a way to say "track but do not shoot"
 * without walking to each gun. It must never buy CAPABILITY — every gun here fires perfectly well
 * alone, which is what {@link TurretStandaloneE2ETest} pins, so nothing in this class may be the
 * reason a gun works.</p>
 *
 * <p>The last test is the one that would be easy to leave out: a console that is destroyed must not
 * leave its battery firing at a point nobody can retract.</p>
 */
public class WeaponConsoleE2ETest extends AbstractSharedServerTest {

    /** This class's own site. */
    private static final int X = 9800, Y = 80, Z = 9800;

    private static final long TIMEOUT_MS = 20_000L;

    /**
     * A console points two guns at once, and the guns were not commanded individually.
     *
     * <p>The layout is a chain over block adjacency — gun, console, gun — because that is what makes
     * one network out of three nodes. No cable is involved: two touching nodes are one network, and
     * a cable is a reach tool rather than a requirement.</p>
     */
    @Test
    public void aConsolePointsEveryGunOnItsNetwork() throws Exception {
        int base = X;
        buildSite(base);
        buildGun(base);
        place("advancedrocketry:weaponConsole", base + 1, Y, Z);
        buildGun(base + 2);
        awaitOperable(base);
        awaitOperable(base + 2);
        exec("artest turret charge 0 " + base + " " + Y + " " + Z);
        exec("artest turret charge 0 " + (base + 2) + " " + Y + " " + Z);

        String seen = awaitGuns(base + 1, 2);
        assertEquals("the console is not commanding both guns — the three blocks did not form one"
                + " network: " + seen, 2, extractInt(seen, "guns"));

        String applied = exec("artest weaponconsole target 0 " + (base + 1) + " " + Y + " " + Z + " "
                + (base + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));
        assertTrue("the console refused the target: " + applied, applied.contains("\"applied\":true"));

        assertTrue("the first gun never fired on the console's target",
                awaitShots(base, 1) >= 1);
        assertTrue("the second gun never fired on the console's target: one console must point the"
                + " whole battery, not the nearest gun", awaitShots(base + 2, 1) >= 1);
    }

    /** Hold fire stops the shooting without losing the target. */
    @Test
    public void holdFireStopsTheShootingAndKeepsTheTarget() throws Exception {
        int base = X + 100;
        buildSite(base);
        buildGun(base);
        place("advancedrocketry:weaponConsole", base + 1, Y, Z);
        awaitOperable(base);
        exec("artest turret charge 0 " + base + " " + Y + " " + Z);
        awaitGuns(base + 1, 1);

        exec("artest weaponconsole target 0 " + (base + 1) + " " + Y + " " + Z + " "
                + (base + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));
        assertTrue("the gun never fired before hold-fire, so the test would prove nothing",
                awaitShots(base, 1) >= 1);

        exec("artest weaponconsole holdfire 0 " + (base + 1) + " " + Y + " " + Z + " true");
        exec("artest turret charge 0 " + base + " " + Y + " " + Z);
        int before = shotsOf(base);
        Thread.sleep(4_000L);
        int after = shotsOf(base);
        assertEquals("the battery kept firing while holding fire: " + before + " -> " + after,
                before, after);

        String state = exec("artest weaponconsole read 0 " + (base + 1) + " " + Y + " " + Z);
        assertTrue("holding fire lost the target: tracking and shooting are separate decisions, so a"
                + " battery watching an approaching ship must not have to forget it to stop"
                + " shooting: " + state, state.contains("\"hasTarget\":true"));

        // And releasing it resumes, which is what says the hold was the reason.
        exec("artest weaponconsole holdfire 0 " + (base + 1) + " " + Y + " " + Z + " false");
        assertTrue("the battery did not resume when hold-fire was released",
                awaitShots(base, after + 1) > after);
    }

    /**
     * Breaking the last console clears the target rather than leaving the battery firing at a point
     * nobody can retract — the one failure a player cannot fix by breaking something.
     */
    @Test
    public void losingTheLastConsoleClearsTheTarget() throws Exception {
        int base = X + 200;
        buildSite(base);
        buildGun(base);
        place("advancedrocketry:weaponConsole", base + 1, Y, Z);
        awaitOperable(base);
        exec("artest turret charge 0 " + base + " " + Y + " " + Z);
        awaitGuns(base + 1, 1);

        exec("artest weaponconsole target 0 " + (base + 1) + " " + Y + " " + Z + " "
                + (base + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));
        assertTrue("the gun never fired on the console's target, so its removal proves nothing",
                awaitShots(base, 1) >= 1);

        assertTrue("could not remove the console", exec("artest fill 0 " + (base + 1) + " " + Y + " "
                + Z + " " + (base + 1) + " " + Y + " " + Z + " minecraft:air").contains("\"ok\":true"));
        // The rebuild that notices the console is gone happens on the network's own tick.
        Thread.sleep(3_000L);
        exec("artest turret charge 0 " + base + " " + Y + " " + Z);
        int before = shotsOf(base);
        Thread.sleep(4_000L);
        int after = shotsOf(base);

        assertEquals("the battery is still firing at the target of a console that no longer exists: "
                + before + " -> " + after, before, after);
        String gun = exec("artest turret read 0 " + base + " " + Y + " " + Z);
        assertTrue("the gun still holds a target it cannot be told to drop: " + gun,
                gun.contains("\"hasTarget\":false"));
    }

    // ---- scenario construction

    private void buildGun(int bx) throws Exception {
        place("advancedrocketry:turret", bx, Y, Z);
        for (int i = 1; i <= 4; i++) {
            place("advancedrocketry:gunBarrel", bx, Y + i, Z);
        }
        place("advancedrocketry:gunCooling", bx, Y, Z + 1);
        place("advancedrocketry:gunCooling", bx, Y, Z - 1);
    }

    private void buildSite(int bx) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + ((bx - 16) >> 4) + " "
                + ((Z - 16) >> 4) + " " + ((bx + 64) >> 4) + " " + ((Z + 16) >> 4))
                .contains("\"ok\":true"));
        assertTrue("could not clear the site", exec("artest fill 0 " + (bx - 4) + " " + (Y - 2) + " "
                + (Z - 4) + " " + (bx + 60) + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air")
                .contains("\"ok\":true"));
        assertTrue("could not hold the chunk", exec("artest chunk forceload 0 " + (bx >> 4) + " "
                + (Z >> 4)).contains("\"ok\":true"));
    }

    private String awaitOperable(int bx) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String state = exec("artest turret read 0 " + bx + " " + Y + " " + Z);
        while (System.currentTimeMillis() < deadline && !state.contains("\"operable\":true")) {
            Thread.sleep(250L);
            state = exec("artest turret read 0 " + bx + " " + Y + " " + Z);
        }
        assertTrue("a gun at " + bx + " never assembled: " + state, state.contains("\"operable\":true"));
        return state;
    }

    /** Wait until the console reports it is commanding at least this many guns. */
    private String awaitGuns(int consoleX, int wanted) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String state = exec("artest weaponconsole read 0 " + consoleX + " " + Y + " " + Z);
        while (System.currentTimeMillis() < deadline && extractInt(state, "guns") < wanted) {
            Thread.sleep(250L);
            state = exec("artest weaponconsole read 0 " + consoleX + " " + Y + " " + Z);
        }
        return state;
    }

    private int awaitShots(int bx, int wanted) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int shots = shotsOf(bx);
        while (System.currentTimeMillis() < deadline && shots < wanted) {
            Thread.sleep(250L);
            shots = shotsOf(bx);
        }
        return shots;
    }

    private int shotsOf(int bx) throws Exception {
        return extractInt(exec("artest turret read 0 " + bx + " " + Y + " " + Z), "shots");
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = exec("artest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
