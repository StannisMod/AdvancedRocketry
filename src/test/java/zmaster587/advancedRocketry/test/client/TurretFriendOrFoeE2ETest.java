package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Whether a gun can tell a friend from a target.
 *
 * <h3>Why this is a client test</h3>
 * <p>The credential is CARRIED, not held about somebody: an entity is friendly for exactly as long
 * as it has the installation's code on it, and the only entity that can carry one is a player. A
 * dedicated-server test has no players, so the whole mechanic is unreachable there — which is
 * precisely why it stayed unbuilt while everything around it was pinned.</p>
 *
 * <h3>Both halves, or neither means anything</h3>
 * <p>A gun that never fires passes "does not shoot friendlies" trivially. So the test makes the same
 * gun, pointed at the same player, fire once the code stops matching — the refusal is only evidence
 * if the shot is the control.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class TurretFriendOrFoeE2ETest extends AbstractClientE2ETest {

    /** The harness's single client always joins under this name. */
    private static final String PLAYER = "ForgeTestClient";

    /** Far enough that the gun is not firing into the player's own block, near enough to track. */
    private static final int GUN_OFFSET = 20;

    private static final long TIMEOUT_MS = 25_000L;

    @Test
    public void aGunHoldsFireOnAPlayerCarryingItsCodeAndFiresOnOneWhoIsNot() throws Exception {
        // Build the gun AROUND the player rather than teleporting the player to the gun. A tp into
        // a freshly cleared site drops him, and a gun tracking a falling target pins its elevation
        // arc and stops firing — which is indistinguishable from the refusal this test is about.
        // (That is exactly how the first two runs failed: pitch +20, saturated, shots 0.)
        double[] player = playerPosition();
        int px = (int) Math.floor(player[0]);
        int py = (int) Math.floor(player[1]);
        int pz = (int) Math.floor(player[2]);
        int gx = px + GUN_OFFSET;

        server("artest chunk warmup 0 " + ((px - 16) >> 4) + " " + ((pz - 16) >> 4) + " "
                + ((gx + 16) >> 4) + " " + ((pz + 16) >> 4));
        // Clear the whole corridor between the player and the gun, not just the gun's own footprint.
        // The muzzle sits `reach + 1.5` blocks along the aim — about five and a half blocks towards
        // the player — and the line-of-fire check refuses a shot into terrain, so a two-block
        // clearing leaves the gun holding fire for a reason that has nothing to do with the target.
        server("artest fill 0 " + (px - 2) + " " + py + " " + (pz - 2) + " " + (gx + 4) + " "
                + (py + 8) + " " + (pz + 2) + " minecraft:air");
        server("artest chunk forceload 0 " + (gx >> 4) + " " + (pz >> 4));
        buildGun(gx, py, pz);

        String built = awaitOperable(gx, py, pz);
        assertTrue("the gun never assembled: " + built, built.contains("\"operable\":true"));
        server("artest turret charge 0 " + gx + " " + py + " " + pz);
        server("artest turret code 0 " + gx + " " + py + " " + pz + " ALPHA");

        // The player carries the installation's own code, and is therefore a friend.
        server("clear " + PLAYER);
        server("give " + PLAYER + " affs:code_device 1 0 {affs_code:\"ALPHA\"}");
        bot().waitTicks(10);
        String targeted = server("artest turret target-player 0 " + gx + " " + py + " " + pz + " " + PLAYER);
        assertTrue("the probe could not point the gun at the player: " + targeted,
                targeted.contains("\"ok\":true"));

        bot().waitTicks(80);
        String tracking = read(gx, py, pz);
        assertEquals("the gun shot a player carrying its own access code: " + tracking, 0,
                shots(gx, py, pz));
        assertTrue("the gun is not tracking the player, so its silence says nothing about"
                + " friend-or-foe: " + tracking, tracking.contains("\"trackingEntity\":true"));
        assertTrue("the gun is not even pointing at him (saturated arc or lost bearing), so the"
                + " silence is about geometry rather than the credential: " + tracking,
                tracking.contains("\"onTarget\":true"));

        // Same gun, same player, same target — only the credential changes.
        server("clear " + PLAYER);
        server("give " + PLAYER + " affs:code_device 1 0 {affs_code:\"BRAVO\"}");
        bot().waitTicks(10);
        server("artest turret charge 0 " + gx + " " + py + " " + pz);

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int fired = shots(gx, py, pz);
        while (System.currentTimeMillis() < deadline && fired == 0) {
            bot().waitTicks(20);
            fired = shots(gx, py, pz);
        }
        assertTrue("the gun would not fire on a player carrying somebody else's code either — the"
                + " hold was not about the credential: " + read(gx, py, pz), fired >= 1);
    }

    /** Where the harness's player actually is. Nothing here moves him. */
    private double[] playerPosition() throws Exception {
        String json = server("artest player position-of " + PLAYER);
        return new double[] {readDouble(json, "playerPosX"), readDouble(json, "playerPosY"),
                readDouble(json, "playerPosZ")};
    }

    private void buildGun(int gx, int gy, int gz) throws Exception {
        place("advancedrocketry:turret", gx, gy, gz);
        for (int i = 1; i <= 4; i++) {
            place("advancedrocketry:gunBarrel", gx, gy + i, gz);
        }
        place("advancedrocketry:gunCooling", gx, gy, gz + 1);
        place("advancedrocketry:gunCooling", gx, gy, gz - 1);
    }

    private String awaitOperable(int gx, int gy, int gz) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String state = read(gx, gy, gz);
        while (System.currentTimeMillis() < deadline && !state.contains("\"operable\":true")) {
            bot().waitTicks(10);
            state = read(gx, gy, gz);
        }
        return state;
    }

    private int shots(int gx, int gy, int gz) throws Exception {
        Matcher m = Pattern.compile("\"shots\":(-?\\d+)").matcher(read(gx, gy, gz));
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private String read(int gx, int gy, int gz) throws Exception {
        return server("artest turret read 0 " + gx + " " + gy + " " + gz);
    }

    private static double readDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[\\d.eE+]+)").matcher(json);
        assertTrue("no " + key + " in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = server("artest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + resp, resp.contains("\"placed\":true"));
    }

    private String server(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }
}
