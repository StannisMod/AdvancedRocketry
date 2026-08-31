package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Where a friend is spared: <b>before there is a target, not at the trigger.</b>
 *
 * <p>A gun already declines to fire on somebody carrying the installation's code. This is the
 * stronger statement one level up — an ally never becomes a CONTACT, so their name is never written
 * anywhere a gun could read it, and no race, stale order or second console can talk the battery into
 * shooting them. The two are deliberately both there: the sensor keeps allies out of the list, and
 * the gun checks again at the trigger.</p>
 *
 * <h3>Why this is a client test</h3>
 * <p>The credential is CARRIED, and only a player can carry one. A dedicated server has no players,
 * so the whole mechanic is unreachable there.</p>
 *
 * <h3>Asked about the player by name</h3>
 * <p>"The contact list is empty" would be the wrong question — a mob in a cave eighty blocks away
 * would answer it without saying anything about this player. The probe is asked whether THIS player
 * is a contact, and the control flips only the code they are carrying.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class SensorFriendIsNeverAcquiredE2ETest extends AbstractClientE2ETest {

    /** The harness's single client always joins under this name. */
    private static final String PLAYER = "ForgeTestClient";

    /** Near enough to be held well by listening alone, far enough not to be inside the battery. */
    private static final int BATTERY_OFFSET = 20;

    private static final long TIMEOUT_MS = 25_000L;

    @Test
    public void aPlayerCarryingTheCodeNeverBecomesAContactAndOneWhoIsNotDoes() throws Exception {
        // Built around the player rather than the player moved to it: a tp into a cleared site drops
        // him, and a battery tracking a falling target is a different experiment.
        double[] player = playerPosition();
        int px = (int) Math.floor(player[0]);
        int py = (int) Math.floor(player[1]);
        int pz = (int) Math.floor(player[2]);
        int bx = px + BATTERY_OFFSET;

        server("gamerule doMobSpawning false");
        server("artest chunk warmup 0 " + ((px - 16) >> 4) + " " + ((pz - 16) >> 4) + " "
                + ((bx + 16) >> 4) + " " + ((pz + 16) >> 4));
        // The whole corridor, not just the battery's footprint: the muzzle sits five and a half
        // blocks along the aim and the line-of-fire check refuses a shot into terrain.
        server("artest fill 0 " + (px - 2) + " " + py + " " + (pz - 2) + " " + (bx + 4) + " "
                + (py + 8) + " " + (pz + 2) + " minecraft:air");
        server("artest chunk forceload 0 " + (bx >> 4) + " " + (pz >> 4));
        buildBattery(bx, py, pz);

        String built = awaitOperable(bx, py, pz);
        assertTrue("the gun never assembled, so nothing below would mean anything: " + built,
                built.contains("\"operable\":true"));
        server("artest turret charge 0 " + bx + " " + py + " " + pz);
        server("artest sensor charge 0 " + (bx + 1) + " " + py + " " + pz);
        server("artest turret code 0 " + bx + " " + py + " " + pz + " ALPHA");
        server("artest sensor code 0 " + (bx + 1) + " " + py + " " + pz + " ALPHA");

        // The player carries the installation's own code, and is therefore not a target at all.
        server("clear " + PLAYER);
        server("give " + PLAYER + " affs:code_device 1 0 {affs_code:\"ALPHA\"}");
        bot().waitTicks(80);

        String friendly = sees(bx + 1, py, pz);
        assertTrue("a player carrying the installation's code entered the target list: " + friendly,
                friendly.contains("\"seen\":false"));
        String gunOnFriend = read(bx, py, pz);
        assertEquals("the battery fired at a player carrying its own code: " + gunOnFriend, 0,
                extractInt(gunOnFriend, "shots"));

        // Same player, same place, same battery. Only the code changes.
        server("clear " + PLAYER);
        server("give " + PLAYER + " affs:code_device 1 0 {affs_code:\"BRAVO\"}");

        String hostile = await(() -> sees(bx + 1, py, pz), s -> s.contains("\"seen\":true"));
        assertTrue("the sensor would not acquire the player carrying somebody else's code either —"
                + " then the exclusion above was not about the credential: " + hostile,
                hostile.contains("\"seen\":true"));

        String engaged = await(() -> read(bx, py, pz), s -> extractInt(s, "shots") >= 1);
        assertTrue("the battery never fired on a contact its own sensor had acquired: " + engaged,
                extractInt(engaged, "shots") >= 1);
        assertTrue("it fired, but not on an acquisition: " + engaged,
                engaged.contains("\"acquired\":true"));
    }

    // ---- scenario construction

    private void buildBattery(int bx, int by, int bz) throws Exception {
        place("advancedrocketry:turret", bx, by, bz);
        for (int i = 1; i <= 4; i++) {
            place("advancedrocketry:gunBarrel", bx, by + i, bz);
        }
        place("advancedrocketry:gunCooling", bx, by, bz + 1);
        place("advancedrocketry:gunCooling", bx, by, bz - 1);
        place("advancedrocketry:fireControlSensor", bx + 1, by, bz);
    }

    /** Where the harness's player actually is. Nothing here moves him. */
    private double[] playerPosition() throws Exception {
        String json = server("artest player position-of " + PLAYER);
        return new double[] {readDouble(json, "playerPosX"), readDouble(json, "playerPosY"),
                readDouble(json, "playerPosZ")};
    }

    private String awaitOperable(int bx, int by, int bz) throws Exception {
        return await(() -> read(bx, by, bz), s -> s.contains("\"operable\":true"));
    }

    private String await(ProbeRead probe, java.util.function.Predicate<String> done) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String state = probe.read();
        while (System.currentTimeMillis() < deadline && !done.test(state)) {
            bot().waitTicks(10);
            state = probe.read();
        }
        return state;
    }

    private interface ProbeRead {
        String read() throws Exception;
    }

    private String sees(int bx, int by, int bz) throws Exception {
        return server("artest sensor sees 0 " + bx + " " + by + " " + bz + " " + PLAYER);
    }

    private String read(int bx, int by, int bz) throws Exception {
        return server("artest turret read 0 " + bx + " " + by + " " + bz);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = server("artest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + resp, resp.contains("\"placed\":true"));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double readDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[\\d.eE+]+)").matcher(json);
        assertTrue("no " + key + " in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private String server(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }
}
