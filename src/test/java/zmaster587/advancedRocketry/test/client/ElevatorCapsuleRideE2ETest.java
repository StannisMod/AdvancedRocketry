package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * EntityElevatorCapsule mount / dismount
 * contracts.
 *
 * <p>Mirrors the methodological pattern from
 * {@link HovercraftRideE2ETest}: the testClient bot has no exposed
 * "right-click on entity" interaction, so player&rarr;capsule mounting
 * is driven through server-side probe verbs
 * ({@code /artest player mount-entity}, {@code dismount}) which call
 * {@code startRiding} / {@code dismountRidingEntity} respectively.
 * The observable result is identical to the production mount path
 * triggered from {@code EntityElevatorCapsule.onEntityUpdate} when
 * the capsule is in motion and a player enters its AABB
 * ({@code line 230-234, 313-317} call the same
 * {@code ent.startRiding(this)}).</p>
 *
 * <p>Pinned contracts:</p>
 * <ol>
 *   <li>{@code player.startRiding(capsule)} succeeds + observable
 *       ridingEntity matches the capsule's id and class.</li>
 *   <li>{@code dismountRidingEntity()} clears the riding relationship.</li>
 * </ol>
 *
 * <p>Deferred (heavy fixture cost):
 * the full ascent/descent loop with stand-time accrual requires a
 * built and powered TileSpaceElevator multiblock tethered to a
 * peer in a different dimension on a station in geostationary
 * orbit, plus a properly anchored {@code dstTilePos} that makes
 * {@code TileSpaceElevator.isDestinationValid} return true. That
 * lives behind the same gating as the existing
 * {@code SpaceElevatorMultiblockTest} but layered with station
 * fixtures we do not yet have.</p>
 */
public class ElevatorCapsuleRideE2ETest extends AbstractClientE2ETest {

    /** LWJGL key code for the vanilla default sneak bind (LSHIFT). */
    private static final int KEY_LSHIFT = 42;

    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");
    private static final Pattern RIDING_ID = Pattern.compile("\"ridingEntityId(?:Now)?\":(-?\\d+)");

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    /** Spawns a fresh elevator capsule via the entity-spawn probe.
     *  The capsule uses the {@code (World)} ctor, so the reflective
     *  spawn helper falls through to the no-coord branch and calls
     *  setPosition manually. */
    private int spawnCapsuleAt(double x, double y, double z) throws Exception {
        String resp = exec("artest entity spawn 0 " + x + " " + y + " " + z
                + " advancedrocketry:ARSpaceElevatorCapsule");
        assertTrue("capsule spawn must succeed: " + resp,
                resp.contains("\"ok\":true") && resp.contains("\"spawned\":true"));
        Matcher m = ENTITY_ID.matcher(resp);
        assertTrue("spawn response must include entityId: " + resp, m.find());
        return Integer.parseInt(m.group(1));
    }

    @Test
    public void playerMountsElevatorCapsuleViaStartRiding() throws Exception {
        // Site + tp pattern is from HovercraftRideE2ETest — keeps the
        // bot in the same chunk as the spawned entity so id resolution
        // through world.getEntityByID stays in-tick.
        exec(HarnessPlayerSite.tpCommand());
        bot().waitTicks(5);

        int capsuleId = spawnCapsuleAt(HarnessPlayerSite.standX(),
                HarnessPlayerSite.STAND_Y, HarnessPlayerSite.frontZ());

        String mount = exec("artest player mount-entity " + capsuleId);
        assertTrue("mount-entity probe must succeed: " + mount,
                mount.contains("\"ok\":true"));
        assertTrue("mount-entity must report mounted:true: " + mount,
                mount.contains("\"mounted\":true"));

        // CLIENT truth: the bot's own client renders itself riding the capsule.
        com.google.gson.JsonObject clientRiding = waitForClientRiding(true);
        assertEquals("client-side ridden entity id must be the capsule's id",
                capsuleId, clientRiding.get("entityId").getAsInt());
        assertTrue("client-side ridden entity class must be EntityElevatorCapsule: "
                        + clientRiding,
                clientRiding.get("entityClass").getAsString().contains("EntityElevatorCapsule"));

        // Cross-side oracle: the server agrees.
        String riding = exec("artest player riding-entity");
        assertEquals("after mount, riding-entity probe must report the capsule's id",
                capsuleId, extract(riding, RIDING_ID));

        // Cleanup — dismount so subsequent tests in the same JVM start fresh.
        exec("artest player dismount");
    }

    @Test
    public void playerDismountClearsRidingEntity() throws Exception {
        exec(HarnessPlayerSite.tpCommand());
        bot().waitTicks(5);

        int capsuleId = spawnCapsuleAt(HarnessPlayerSite.standX(),
                HarnessPlayerSite.STAND_Y, HarnessPlayerSite.frontZ());
        exec("artest player mount-entity " + capsuleId);
        com.google.gson.JsonObject mounted = waitForClientRiding(true);
        assertEquals("arrange: client must be riding the capsule first",
                capsuleId, mounted.get("entityId").getAsInt());

        // The REAL dismount input: hold sneak — the vanilla
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
}
