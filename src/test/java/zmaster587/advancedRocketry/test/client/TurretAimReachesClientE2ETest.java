package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Whether a player can see which way a turret is pointing.
 *
 * <h3>Why this is a client test and not a unit one</h3>
 * <p>A block cannot be turned — it sits in a grid cell at one of a handful of fixed orientations — so
 * a turret's bearing exists only as numbers on the server and as a drawing on the client. The whole
 * question is therefore whether those numbers arrive, and that is answerable only on a real client.
 * What is asserted is the state the renderer draws FROM (the client tile's own update tag), because a
 * renderer's output cannot be read from a test; what is NOT asserted is that the barrel looks right,
 * which stays a human's judgement.</p>
 *
 * <h3>The command travels, not the pose</h3>
 * <p>The client runs the same traverse the server does, from the command it was sent. So the test
 * waits for the client's bearing to converge on the direction the gun was pointed rather than
 * expecting a particular angle at a particular tick — the pose is the client's own arithmetic, and
 * pinning it would be pinning the harness's timing.</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 */
public class TurretAimReachesClientE2ETest extends AbstractClientE2ETest {

    private static final int X = 120, Y = 79, Z = 120;

    /** Long enough for the mount to swing 90 degrees at the reference gun's rate, with room to spare. */
    private static final long AIM_TIMEOUT_MS = 25_000L;

    @Test
    public void theBearingTheServerCommandsArrivesAtTheClient() throws Exception {
        server("artest chunk warmup 0 " + ((X - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((X + 16) >> 4) + " " + ((Z + 16) >> 4));
        server("artest fill 0 " + (X - 3) + " " + (Y - 1) + " " + (Z - 3) + " " + (X + 3) + " "
                + (Y + 6) + " " + (Z + 3) + " minecraft:air");
        server("artest place 0 " + X + " " + Y + " " + Z + " advancedrocketry:turret");
        for (int i = 1; i <= 4; i++) {
            server("artest place 0 " + X + " " + (Y + i) + " " + Z + " advancedrocketry:gunBarrel");
        }
        // Stand next to it, so the client is tracking this chunk and its tile.
        server("tp @a " + (X + 4) + ".5 " + Y + " " + (Z + 0.5D));
        bot().waitTicks(20);

        String before = clientMountNbt();
        assertTrue("the client has no turret tile to draw: " + before, before.contains("mount"));
        double startYaw = tagDouble(before, "yaw");

        // Point it hard to one side: a bearing the mount has to travel to, not one it is already at.
        server("artest turret target 0 " + X + " " + Y + " " + Z + " " + (X + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D));

        long deadline = System.currentTimeMillis() + AIM_TIMEOUT_MS;
        String nbt = clientMountNbt();
        while (System.currentTimeMillis() < deadline && Math.abs(tagDouble(nbt, "yaw") - startYaw) < 45.0D) {
            bot().waitTicks(20);
            nbt = clientMountNbt();
        }

        double yaw = tagDouble(nbt, "yaw");
        assertNotEquals("the client's turret never turned: the server commanded a bearing 90 degrees"
                + " away and the client is still at its start. A gun whose barrel does not move is a"
                + " gun a player cannot read: " + nbt, startYaw, yaw, 45.0D);
        // -90 is due +X in Minecraft's yaw convention, which is where the target was put.
        assertTrue("the client turned, but not towards the target (yaw=" + yaw + ", expected about"
                + " -90): " + nbt, Math.abs(yaw + 90.0D) < 15.0D);
    }

    private String clientMountNbt() throws Exception {
        JsonObject tile = bot().tileNbt(X, Y, Z);
        return tile.has("nbt") ? tile.get("nbt").getAsString() : "";
    }

    /** Pull one double out of a stringified NBT compound ({@code key:12.5d}). */
    private static double tagDouble(String nbt, String key) {
        Matcher m = Pattern.compile(key + ":(-?[\\d.eE+]+)d?").matcher(nbt);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    private void server(String command) throws Exception {
        serverClient().execute(command);
    }
}
