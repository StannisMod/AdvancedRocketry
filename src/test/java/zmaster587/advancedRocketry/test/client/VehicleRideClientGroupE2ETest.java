package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The player gets on an AR vehicle, drives it, and gets off. Six scenarios, one client.
 *
 * <p>Two vehicles, one contract family: {@code startRiding} is observable on the client that renders
 * the rider, a held sneak key is the real dismount input, and a held forward key is the real
 * throttle. Mounting stays a server probe — an honest client e2e allows "mount" as arrange (the bot
 * has no right-click-on-entity), but everything after it is driven through the real client input
 * surface and read back from the client's own view.</p>
 *
 * <h2>Why these six share one harness</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML: {@code HovercraftRideE2ETest} 459.9 s over
 * four client boots and {@code ElevatorCapsuleRideE2ETest} 232.7 s over two — <b>11.5 minutes for
 * six mounts</b>. The six scenarios' own work is seconds each; the rest was six clients starting up
 * to be told to sit down.</p>
 *
 * <h2>What the sharing makes dangerous here, and how it is handled</h2>
 *
 * <ul>
 *   <li><b>A rider left mounted.</b> Every scenario that mounts ends dismounted — either because
 *       that IS its contract or through an explicit dismount — because the next scenario's teleport
 *       would otherwise drag a vehicle around with it.</li>
 *   <li><b>A held key left down.</b> Both key-driven scenarios release in a {@code finally}; the
 *       shared reset then releases everything again, so a scenario that dies mid-hold cannot arm
 *       the next one's input.</li>
 *   <li><b>Someone else's vehicle answering.</b> Each scenario spawns its craft inside its own plot
 *       and addresses it by the entity id the spawn returned — never by "the nearest hovercraft".</li>
 * </ul>
 *
 * <p>Each scenario builds its own stone platform rather than standing on generated terrain: the
 * original classes worked at y≈79, ordinary overworld height, and both carried a {@code fill air}
 * pre-clear because on some seeds a hillside filled the player's body volume there. A platform in
 * open air at {@link Plot#DEFAULT_Y} has no seed dependency at all.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code HovercraftRideE2ETest}, {@code ElevatorCapsuleRideE2ETest}. The two classes each declared
 * their own {@code playerDismountClearsRidingEntity}; the elevator's is renamed
 * {@link #playerDismountClearsRidingEntityOnTheCapsule()} because two methods cannot share a name,
 * and the hovercraft's keeps the original.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VehicleRideClientGroupE2ETest extends AbstractSharedClientE2ETest {

    /** LWJGL key codes for the vanilla default binds. */
    private static final int KEY_W = 17;
    private static final int KEY_LSHIFT = 42;

    private static final int PAD_Y = Plot.DEFAULT_Y;
    /** North-west corner of this scenario's platform, inside its plot. */
    private static final int PAD_DX = 16;
    private static final int PAD_DZ = 16;
    /** Edge of the platform. Wide enough that a throttled craft cannot drive off it in 40 ticks. */
    private static final int PAD_EDGE = 16;
    /** Where the player stands, and where the vehicle is spawned, relative to the platform corner. */
    private static final int STAND_DX = PAD_DX + 4;
    private static final int STAND_DZ = PAD_DZ + 4;
    private static final int VEHICLE_DZ = STAND_DZ + 2;

    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");
    private static final Pattern RIDING_ID = Pattern.compile("\"ridingEntityId(?:Now)?\":(-?\\d+)");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?\\d+(?:\\.\\d+)?)");

    @Override
    protected String subsystem() {
        return "vehicle-ride";
    }

    // ── shared arrangement ────────────────────────────────────────────────────

    /**
     * Builds this scenario's platform and stands the player on it. Returns nothing: every
     * coordinate a scenario needs comes from {@link #standX()} / {@link #standZ()}, so no scenario
     * can accidentally address a neighbour's pad.
     */
    private void buildPadAndStand() throws Exception {
        int dim = plot().dim;
        scenario().arranging("build the platform and stand on it");
        String fill = exec("artest fill " + dim + " " + plot().x(PAD_DX) + " " + PAD_Y + " "
                + plot().z(PAD_DZ) + " " + plot().x(PAD_DX + PAD_EDGE - 1) + " " + PAD_Y + " "
                + plot().z(PAD_DZ + PAD_EDGE - 1) + " minecraft:stone");
        scenario().requireArranged("platform fill must succeed: " + fill, fill.contains("\"ok\":true"));
        exec("tp @a " + (standX() + 0.5) + " " + (PAD_Y + 1) + " " + (standZ() + 0.5));
        bot().waitTicks(5);
    }

    private int standX() {
        return plot().x(STAND_DX);
    }

    private int standZ() {
        return plot().z(STAND_DZ);
    }

    /** Spawns the named vehicle two blocks north of the player, on this scenario's platform. */
    private int spawnVehicle(String entityId) throws Exception {
        double vx = standX() + 0.5;
        double vz = plot().z(VEHICLE_DZ) + 0.5;
        String resp = exec("artest entity spawn " + plot().dim + " " + vx + " " + (PAD_Y + 1)
                + " " + vz + " " + entityId);
        scenario().requireArranged(entityId + " spawn must succeed: " + resp,
                resp.contains("\"ok\":true") && resp.contains("\"spawned\":true"));
        Matcher m = ENTITY_ID.matcher(resp);
        scenario().requireArranged("spawn response must include entityId: " + resp, m.find());
        int id = Integer.parseInt(m.group(1));
        scenario().record("vehicleEntityId", id)
                .describeOnFailureWith("artest entity info " + plot().dim + " " + id,
                        "artest player riding-entity");
        return id;
    }

    private void mount(int vehicleId) throws Exception {
        String mount = exec("artest player mount-entity " + vehicleId);
        scenario().requireArranged("mount-entity probe must succeed: " + mount,
                mount.contains("\"ok\":true"));
        scenario().requireArranged("mount-entity must report mounted:true: " + mount,
                mount.contains("\"mounted\":true"));
    }

    /** Polls until the CLIENT reports riding == expected (~10 s cap). */
    private JsonObject waitForClientRiding(boolean expected) throws Exception {
        JsonObject last = null;
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

    // ── hovercraft ────────────────────────────────────────────────────────────

    /** From {@code HovercraftRideE2ETest}: {@code startRiding} reaches the client that renders the
     *  rider, with the server agreeing as the cross-side oracle. */
    @Test
    public void playerMountsHovercraftViaStartRiding() throws Exception {
        buildPadAndStand();
        int craftId = spawnVehicle("advancedrocketry:ARHoverCraft");

        scenario().asserting("the client renders itself riding the craft it was mounted on");
        mount(craftId);

        JsonObject clientRiding = waitForClientRiding(true);
        assertTrue("client must report riding=true after mount: " + clientRiding,
                clientRiding.get("riding").getAsBoolean());
        assertEquals("client-side ridden entity id must be the craft's id",
                craftId, clientRiding.get("entityId").getAsInt());
        assertTrue("client-side ridden entity class must be EntityHoverCraft: " + clientRiding,
                clientRiding.get("entityClass").getAsString().contains("EntityHoverCraft"));

        String riding = exec("artest player riding-entity");
        assertEquals("after mount, riding-entity probe must report the craft's id",
                craftId, extract(riding, RIDING_ID));

        // Leave the shared player on his own feet.
        exec("artest player dismount");
    }

    /** From {@code HovercraftRideE2ETest}: the REAL dismount input is a held sneak — EntityPlayerSP's
     *  wants-to-stop-riding path sends it to the server. */
    @Test
    public void playerDismountClearsRidingEntity() throws Exception {
        buildPadAndStand();
        int craftId = spawnVehicle("advancedrocketry:ARHoverCraft");
        mount(craftId);
        JsonObject mounted = waitForClientRiding(true);
        scenario().requireArranged("arrange: client must be riding the craft first; got " + mounted,
                craftId == mounted.get("entityId").getAsInt());

        scenario().asserting("a held sneak key dismounts the rider, on both sides");
        bot().setKey(KEY_LSHIFT, true);
        try {
            JsonObject clientRiding = waitForClientRiding(false);
            assertTrue("client must report riding=false after sneak-dismount: " + clientRiding,
                    !clientRiding.get("riding").getAsBoolean());
        } finally {
            bot().setKey(KEY_LSHIFT, false);
        }

        String riding = exec("artest player riding-entity");
        assertEquals("after dismount, player must report no riding entity (-1)",
                -1, extract(riding, RIDING_ID));
    }

    /** From {@code HovercraftRideE2ETest}: W feeds MovementInput &rarr; CPacketInput &rarr; server
     *  {@code player.moveForward}, which {@code EntityHoverCraft.getPassengerMovingForward()} reads
     *  each tick. */
    @Test
    public void forwardThrottleMovesHovercraftLaterally() throws Exception {
        buildPadAndStand();
        int craftId = spawnVehicle("advancedrocketry:ARHoverCraft");
        mount(craftId);
        waitForClientRiding(true);

        scenario().measuring("the craft's lateral position as the CLIENT renders it, before input");
        JsonObject pre = bot().reportRidingEntity();
        double xBefore = pre.get("posX").getAsDouble();
        double zBefore = pre.get("posZ").getAsDouble();
        scenario().record("beforeXZ", xBefore + "," + zBefore);

        scenario().asserting("a held forward key drives the craft laterally");
        bot().setKey(KEY_W, true);
        try {
            bot().waitTicks(40);
        } finally {
            bot().setKey(KEY_W, false);
        }

        JsonObject post = bot().reportRidingEntity();
        assertTrue("client must still be riding after the throttle window: " + post,
                post.get("riding").getAsBoolean());
        double xAfter = post.get("posX").getAsDouble();
        double zAfter = post.get("posZ").getAsDouble();
        double dx = xAfter - xBefore;
        double dz = zAfter - zBefore;
        double lateralDist = Math.sqrt(dx * dx + dz * dz);
        scenario().record("lateralDist", lateralDist);
        assertTrue("throttled hovercraft must move at least 0.1 blocks laterally over 40 ticks "
                        + "(got " + lateralDist + "): before=(" + xBefore + "," + zBefore + ")"
                        + " after=(" + xAfter + "," + zAfter + ")",
                lateralDist > 0.1);

        String postInfo = exec("artest entity info " + plot().dim + " " + craftId);
        assertTrue("server craft X must agree with the client view: " + postInfo,
                Math.abs(extractDouble(postInfo, POS_X) - xAfter) < 4.0);

        exec("artest player dismount");
    }

    /** From {@code HovercraftRideE2ETest}: the counter-test. No passenger &rarr;
     *  {@code getPassengerMovingForward} returns 0 &rarr; no lateral acceleration. */
    @Test
    public void unmountedHovercraftDoesNotMoveLaterally() throws Exception {
        buildPadAndStand();
        int craftId = spawnVehicle("advancedrocketry:ARHoverCraft");

        scenario().measuring("confirm nobody is aboard, then read the craft's resting position");
        String riding = exec("artest player riding-entity");
        assertNotEquals("baseline: player must NOT be riding the craft (spawn doesn't auto-mount)",
                craftId, extract(riding, RIDING_ID));

        String preInfo = exec("artest entity info " + plot().dim + " " + craftId);
        double xBefore = extractDouble(preInfo, POS_X);
        double zBefore = extractDouble(preInfo, POS_Z);

        scenario().asserting("40 ticks with no passenger move it nowhere laterally");
        exec("artest entity tick " + plot().dim + " " + craftId + " 40");

        String postInfo = exec("artest entity info " + plot().dim + " " + craftId);
        double xAfter = extractDouble(postInfo, POS_X);
        double zAfter = extractDouble(postInfo, POS_Z);
        double lateralDrift = Math.sqrt(Math.pow(xAfter - xBefore, 2) + Math.pow(zAfter - zBefore, 2));
        scenario().record("lateralDrift", lateralDrift);
        // Tolerance: the craft's motion damping (x0.9 per tick) lets any latent motion bleed off
        // within ~30 ticks, so drift over 40 ticks should be near zero.
        assertTrue("unmounted hovercraft must hover in place laterally; drift=" + lateralDrift
                        + " before=(" + xBefore + "," + zBefore + ") after=(" + xAfter + ","
                        + zAfter + ")",
                lateralDrift < 0.5);
    }

    // ── elevator capsule ──────────────────────────────────────────────────────

    /**
     * From {@code ElevatorCapsuleRideE2ETest}: {@code EntityElevatorCapsule} mount.
     *
     * <p>The observable result is identical to the production mount path triggered from
     * {@code EntityElevatorCapsule.onEntityUpdate} when the capsule is in motion and a player enters
     * its AABB — both call the same {@code ent.startRiding(this)}.</p>
     *
     * <p>Deferred (heavy fixture cost): the full ascent/descent loop with stand-time accrual needs a
     * built and powered TileSpaceElevator multiblock tethered to a peer in a different dimension on
     * a station in geostationary orbit, plus an anchored {@code dstTilePos} that makes
     * {@code TileSpaceElevator.isDestinationValid} return true.</p>
     */
    @Test
    public void playerMountsElevatorCapsuleViaStartRiding() throws Exception {
        buildPadAndStand();
        int capsuleId = spawnVehicle("advancedrocketry:ARSpaceElevatorCapsule");

        scenario().asserting("the client renders itself riding the capsule");
        mount(capsuleId);

        JsonObject clientRiding = waitForClientRiding(true);
        assertEquals("client-side ridden entity id must be the capsule's id",
                capsuleId, clientRiding.get("entityId").getAsInt());
        assertTrue("client-side ridden entity class must be EntityElevatorCapsule: " + clientRiding,
                clientRiding.get("entityClass").getAsString().contains("EntityElevatorCapsule"));

        String riding = exec("artest player riding-entity");
        assertEquals("after mount, riding-entity probe must report the capsule's id",
                capsuleId, extract(riding, RIDING_ID));

        exec("artest player dismount");
    }

    /** From {@code ElevatorCapsuleRideE2ETest}, where it was also called
     *  {@code playerDismountClearsRidingEntity} — renamed because the hovercraft's keeps that name. */
    @Test
    public void playerDismountClearsRidingEntityOnTheCapsule() throws Exception {
        buildPadAndStand();
        int capsuleId = spawnVehicle("advancedrocketry:ARSpaceElevatorCapsule");
        mount(capsuleId);
        JsonObject mounted = waitForClientRiding(true);
        scenario().requireArranged("arrange: client must be riding the capsule first; got " + mounted,
                capsuleId == mounted.get("entityId").getAsInt());

        scenario().asserting("a held sneak key dismounts the rider, on both sides");
        bot().setKey(KEY_LSHIFT, true);
        try {
            JsonObject clientRiding = waitForClientRiding(false);
            assertTrue("client must report riding=false after sneak-dismount: " + clientRiding,
                    !clientRiding.get("riding").getAsBoolean());
        } finally {
            bot().setKey(KEY_LSHIFT, false);
        }

        String riding = exec("artest player riding-entity");
        assertEquals("after dismount, player must report no riding entity (-1)",
                -1, extract(riding, RIDING_ID));
    }
}
