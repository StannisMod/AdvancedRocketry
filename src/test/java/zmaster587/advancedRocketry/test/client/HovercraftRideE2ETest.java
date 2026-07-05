package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hovercraft mount / dismount / throttle / motion
 * contracts.
 *
 * <p>Pre-this-test the hovercraft had:</p>
 * <ul>
 *   <li>{@code HovercraftEntitySmokeTest} — spawn + tick alive
 *       (server tier, shallow).</li>
 *   <li>{@code ItemHovercraftSpawnE2ETest} — right-click ground
 *       spawns entity (testClient, item-side).</li>
 * </ul>
 *
 * <p>This test pins the <b>ride contracts</b>:</p>
 * <ol>
 *   <li>Player mounts via {@code startRiding} → ridingEntity matches.</li>
 *   <li>Player dismounts → ridingEntity is null.</li>
 *   <li>Player input {@code moveForward > 0} → hovercraft accelerates
 *       forward.</li>
 *   <li>No input → hovercraft hovers (lateral position stable).</li>
 * </ol>
 *
 * <p><b>Honest-e2e shape</b>: mounting stays a
 * server probe — the SOP explicitly allows "mount" as arrange. The RIDE
 * contracts are then driven through the real client input surface: the
 * forward key (W) feeds {@code MovementInput} → {@code CPacketInput} →
 * server {@code player.moveForward} → {@code getPassengerMovingForward()},
 * and sneak (LSHIFT) drives the vanilla wants-to-stop-riding dismount.
 * Observations read the CLIENT view via {@code reportRidingEntity}, with
 * server probes kept as cross-side oracles.</p>
 *
 * <p><b>No fuel test</b>: the EntityHoverCraft class has zero fuel
 * or energy logic — onUpdate only reads player input and applies
 * acceleration. The audit's "fuel drain" gap was based on assumed
 * (not actual) fuel mechanics. Documented in this class's javadoc
 * so a future addition of fuel logic must add a corresponding
 * contract pin.</p>
 */
public class HovercraftRideE2ETest extends AbstractClientE2ETest {

    /** LWJGL key codes for the vanilla default binds. */
    private static final int KEY_W = 17;
    private static final int KEY_LSHIFT = 42;

    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");
    private static final Pattern RIDING_ID = Pattern.compile("\"ridingEntityId(?:Now)?\":(-?\\d+)");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?\\d+(?:\\.\\d+)?)");

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    /** Spawns a fresh hovercraft entity near the bot's pad coords and
     *  returns its entity id. */
    private int spawnHovercraftAt(double x, double y, double z) throws Exception {
        String resp = exec("artest entity spawn 0 " + x + " " + y + " " + z
                + " advancedrocketry:ARHoverCraft");
        assertTrue("hovercraft spawn must succeed: " + resp,
                resp.contains("\"ok\":true") && resp.contains("\"spawned\":true"));
        Matcher m = ENTITY_ID.matcher(resp);
        assertTrue("spawn response must include entityId: " + resp, m.find());
        return Integer.parseInt(m.group(1));
    }

    @Test
    public void playerMountsHovercraftViaStartRiding() throws Exception {
        // Spawn craft adjacent to a known stone pad so the bot is
        // close enough to be in the same world tick + chunk.
        exec("artest place 0 8 78 8 minecraft:stone");
        exec("tp @a 8.5 79 8.5");
        bot().waitTicks(5);

        int craftId = spawnHovercraftAt(8.5, 79, 10.5);

        String mount = exec("artest player mount-entity " + craftId);
        assertTrue("mount-entity probe must succeed: " + mount,
                mount.contains("\"ok\":true"));
        assertTrue("mount-entity must report mounted:true: " + mount,
                mount.contains("\"mounted\":true"));

        // CLIENT truth: the bot's own client must render itself riding the
        // craft — datawatcher/mount sync reaching the rendered frame.
        com.google.gson.JsonObject clientRiding = waitForClientRiding(true);
        assertTrue("client must report riding=true after mount: " + clientRiding,
                clientRiding.get("riding").getAsBoolean());
        assertEquals("client-side ridden entity id must be the craft's id",
                craftId, clientRiding.get("entityId").getAsInt());
        assertTrue("client-side ridden entity class must be EntityHoverCraft: "
                        + clientRiding,
                clientRiding.get("entityClass").getAsString().contains("EntityHoverCraft"));

        // Cross-side oracle: the server agrees.
        String riding = exec("artest player riding-entity");
        assertEquals("after mount, riding-entity probe must report the craft's id",
                craftId, extract(riding, RIDING_ID));
    }

    @Test
    public void playerDismountClearsRidingEntity() throws Exception {
        exec("artest place 0 28 78 8 minecraft:stone");
        exec("tp @a 28.5 79 8.5");
        bot().waitTicks(5);

        int craftId = spawnHovercraftAt(28.5, 79, 10.5);
        exec("artest player mount-entity " + craftId);
        com.google.gson.JsonObject mounted = waitForClientRiding(true);
        assertEquals("arrange: client must be riding the craft first",
                craftId, mounted.get("entityId").getAsInt());

        // The REAL dismount input: hold sneak — EntityPlayerSP's
        // wants-to-stop-riding path sends the dismount to the server.
        bot().setKey(KEY_LSHIFT, true);
        try {
            com.google.gson.JsonObject clientRiding = waitForClientRiding(false);
            assertTrue("client must report riding=false after sneak-dismount: "
                            + clientRiding,
                    !clientRiding.get("riding").getAsBoolean());
        } finally {
            bot().setKey(KEY_LSHIFT, false);
        }

        // Cross-side oracle: the server agrees.
        String riding = exec("artest player riding-entity");
        assertEquals("after dismount, player must report no riding entity (-1)",
                -1, extract(riding, RIDING_ID));
    }

    @Test
    public void forwardThrottleMovesHovercraftLaterally() throws Exception {
        // Place the bot at a stable position with the hovercraft right
        // next to it. The craft's onUpdate reads player.moveForward
        // each tick — setting it via probe drives acceleration in the
        // direction of the craft's yaw.
        exec("artest place 0 48 78 8 minecraft:stone");
        exec("tp @a 48.5 79 8.5");
        bot().waitTicks(5);

        int craftId = spawnHovercraftAt(48.5, 79, 10.5);
        exec("artest player mount-entity " + craftId);
        waitForClientRiding(true);

        // Baseline lateral position as the CLIENT renders the ridden craft.
        com.google.gson.JsonObject pre = bot().reportRidingEntity();
        double xBefore = pre.get("posX").getAsDouble();
        double zBefore = pre.get("posZ").getAsDouble();

        // Drive forward with the REAL forward key: W feeds MovementInput →
        // CPacketInput → server player.moveForward, which EntityHoverCraft's
        // getPassengerMovingForward() reads each tick.
        bot().setKey(KEY_W, true);
        try {
            bot().waitTicks(40);
        } finally {
            bot().setKey(KEY_W, false);
        }

        com.google.gson.JsonObject post = bot().reportRidingEntity();
        assertTrue("client must still be riding after the throttle window: " + post,
                post.get("riding").getAsBoolean());
        double xAfter = post.get("posX").getAsDouble();
        double zAfter = post.get("posZ").getAsDouble();

        double dx = xAfter - xBefore;
        double dz = zAfter - zBefore;
        double lateralDist = Math.sqrt(dx * dx + dz * dz);
        assertTrue("throttled hovercraft must move at least 0.1 blocks "
                        + "laterally over 40 ticks (got " + lateralDist + "): "
                        + " before=(" + xBefore + "," + zBefore + ")"
                        + " after=(" + xAfter + "," + zAfter + ")",
                lateralDist > 0.1);

        // Cross-side oracle: the server's craft position agrees with the
        // client-rendered one (within interpolation tolerance).
        String postInfo = exec("artest entity info 0 " + craftId);
        assertTrue("server craft X must agree with the client view: " + postInfo,
                Math.abs(extractDouble(postInfo, POS_X) - xAfter) < 4.0);

        // Cleanup — dismount so subsequent tests start fresh.
        exec("artest player dismount");
    }

    @Test
    public void unmountedHovercraftDoesNotMoveLaterally() throws Exception {
        // Counter-test: an unmounted hovercraft has no passenger →
        // getPassengerMovingForward returns 0 → no lateral acceleration.
        // The Y position may drift (gravity/hover), but X+Z should
        // stay stable.
        exec("artest place 0 68 78 8 minecraft:stone");
        exec("tp @a 68.5 79 8.5");
        bot().waitTicks(5);

        int craftId = spawnHovercraftAt(68.5, 79, 10.5);
        // Confirm unmounted state.
        String riding = exec("artest player riding-entity");
        assertNotEquals("baseline: player must NOT be riding the craft "
                        + "(spawn doesn't auto-mount)",
                craftId, extract(riding, RIDING_ID));

        String preInfo = exec("artest entity info 0 " + craftId);
        double xBefore = extractDouble(preInfo, POS_X);
        double zBefore = extractDouble(preInfo, POS_Z);

        // Drive ticks WITHOUT mounting.
        exec("artest entity tick 0 " + craftId + " 40");

        String postInfo = exec("artest entity info 0 " + craftId);
        double xAfter = extractDouble(postInfo, POS_X);
        double zAfter = extractDouble(postInfo, POS_Z);
        double lateralDrift = Math.sqrt(Math.pow(xAfter - xBefore, 2)
                + Math.pow(zAfter - zBefore, 2));
        // Tolerance: the craft's motion damping (×0.9 per tick) lets
        // any latent motion bleed off within ~30 ticks. Lateral drift
        // over 40 ticks should be near zero.
        assertTrue("unmounted hovercraft must hover in place laterally; "
                        + "drift=" + lateralDrift + " before=(" + xBefore + ","
                        + zBefore + ") after=(" + xAfter + "," + zAfter + ")",
                lateralDrift < 0.5);
    }

    /** Polls until the CLIENT reports riding == expected (~10 s cap). */
    private com.google.gson.JsonObject waitForClientRiding(boolean expected) throws Exception {
        com.google.gson.JsonObject last = null;
        for (int waited = 0; waited < 200; waited += 5) {
            bot().waitTicks(5);
            last = bot().reportRidingEntity();
            if (last.get("riding").getAsBoolean() == expected) {
                return last;
            }
        }
        throw new AssertionError("client never reached riding=" + expected
                + "; last report: " + last);
    }

    private static int extract(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern not found in: " + src, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static double extractDouble(String src, Pattern pattern) {
        Matcher m = pattern.matcher(src);
        assertTrue("pattern not found in: " + src, m.find());
        return Double.parseDouble(m.group(1));
    }
}
