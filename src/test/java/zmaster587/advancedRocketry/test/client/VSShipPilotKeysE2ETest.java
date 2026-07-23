package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;

import static org.junit.Assert.assertTrue;

/**
 * The honest, full-path tier-2 pilot e2e: a bot SITS on the pilot seat and drives the ship with
 * REAL Free Flight keys, observing the ship move. This exercises everything a hands-on playtest
 * does — client key sampling ({@code KeyBindings.handleShipPilotInput}) &rarr; {@code PacketMachine} &rarr;
 * seat {@code useNetworkData} (the pilot guard) &rarr; AFC per-tile input &rarr; force — so a break anywhere
 * in the CLIENT path (which the server-side {@link VSShipSeatDriveE2ETest} bisection cannot reach)
 * fails here. It also covers the {@code ARKeyConflictContext} pilot-seat fix, since the vertical-up
 * key is one of the cockpit-scoped keys.
 *
 * <p>The bot cannot right-click a ship block to sit, so {@code vs seat-mount} spawns the seat's
 * dummy and {@code player mount-entity} rides it — identical observable state to a real sit. Gated
 * on real VS — run with {@code -PwithVS}.</p>
 *
 * <p>Beyond the ship's own motion, this pins the full <em>pilot</em> path from the real client's
 * point of view — the same two properties the rocket Free Flight e2e pins for its craft:
 * <ul>
 *   <li><b>The rider travels with the ship.</b> The CLIENT-rendered mount position
 *       ({@code reportRidingEntity}) and the player camera ({@code reportState.playerY}) climb with
 *       the ship, and their climb tracks the server ship's — so the seated pilot is glued to the
 *       moving craft, not left at the spawn point.</li>
 *   <li><b>The mouse steers, it never free-looks.</b> A hard sideways mouse look injected through
 *       the real client ({@code setLook}) does NOT swing the camera; the view stays locked to the
 *       ship's nose heading (derived from the server ship attitude with the same quat&rarr;Euler the
 *       production camera lock uses), exactly like the rocket cockpit.</li>
 * </ul>
 */
public class VSShipPilotKeysE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern QW = Pattern.compile("\"qw\":(-?[0-9.E\\-]+)");
    private static final Pattern QX = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern QY = Pattern.compile("\"qy\":(-?[0-9.E\\-]+)");
    private static final Pattern QZ = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 2800, BY = 64, BZ = 2800;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void seatedPilotFliesShipTravelsWithItAndCameraLocksToNose() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                serverHasVs());

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
        assertTrue("assembly must create a VS ship (all=" + all + ")", all >= 1);
        bot().waitTicks(40);

        // Approach so the client loads the ship (and its seat/AFC tiles).
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);

        double yBefore = Double.NaN;
        for (int i = 0; i < 40 && Double.isNaN(yBefore); i++) {
            bot().waitTicks(5);
            if (count("ship-count") >= 1) {
                String info = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
                if (info.contains("\"managed\":true")) {
                    yBefore = readDouble(info, POS_Y);
                }
            }
        }
        assertTrue("the ship must LOAD with the client present", !Double.isNaN(yBefore));

        // Sit the bot on the pilot seat (spawn its dummy + ride it).
        String mountInfo = exec("artest vs seat-mount 0");
        assertTrue("seat-mount must find the pilot seat: " + mountInfo,
                mountInfo.contains("\"seatFound\":true"));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10); // let the mount replicate and the client recognise the pilot seat

        // Baseline the CLIENT pilot position BEFORE the climb: the mount the bot rides (its dummy)
        // and the player camera. A pilot glued to the ship rises with it; a detached one stays here.
        double riderYBefore = bot().reportRidingEntity().get("posY").getAsDouble();
        double camYBefore = bot().reportState().get("playerY").getAsDouble();

        // Drive REAL keys: hold vertical-up. The client samples it, sends it to the seat, and the
        // AFC lifts the ship. Up isolates from ground friction; poll for the climb (bounded).
        final double y0 = yBefore;
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated hover-lift (load-scaled ceiling + early exit): a fixed 100-iteration budget
            // under-lifts a frame-starved client under concurrent-fork load and reds a healthy climb.
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> readDouble(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ), POS_Y),
                    y -> y - y0 > 1.5, 2, 100);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double yAfter = lift.value;

        assertTrue("holding the vertical-up key while seated must lift the ship through the FULL "
                        + "client path (key -> packet -> seat -> AFC -> force): yBefore=" + yBefore
                        + " yAfter=" + yAfter,
                yAfter - yBefore > 1.0);

        // --- The seated pilot must TRAVEL with the ship (client-observed). Read the CLIENT rider +
        // camera again: both must have climbed, and the rider's climb must track the server ship's.
        // Before the fix that glues the seat dummy to the moving ship, the dummy stays at spawn while
        // the ship departs, so these client deltas would be ~0 even though the server ship moved.
        bot().waitTicks(6); // let the client ship transform settle at the new altitude
        double serverYAfter = readDouble(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ), POS_Y);
        double riderYAfter = bot().reportRidingEntity().get("posY").getAsDouble();
        double camYAfter = bot().reportState().get("playerY").getAsDouble();
        assertTrue("the CLIENT-rendered rider must climb with the ship (it stayed behind): "
                        + "riderYBefore=" + riderYBefore + " riderYAfter=" + riderYAfter,
                riderYAfter - riderYBefore > 1.0);
        assertTrue("the pilot's CLIENT camera must climb with the ship: camYBefore=" + camYBefore
                        + " camYAfter=" + camYAfter,
                camYAfter - camYBefore > 1.0);
        assertTrue("the client rider climb must TRACK the server ship climb (client="
                        + (riderYAfter - riderYBefore) + " server=" + (serverYAfter - yBefore) + ")",
                Math.abs((riderYAfter - riderYBefore) - (serverYAfter - yBefore)) < 3.0);

        // --- The mouse must STEER the ship, never free-look the camera (the FF cockpit contract).
        // The ship is now hovering roughly upright. Inject a hard SIDEWAYS mouse look each tick
        // (horizontal mouse -> roll cursor; pure roll leaves the nose direction fixed). A camera that
        // free-looks would accumulate tens of degrees off; a nose-locked one is re-pinned to the
        // (unmoved) ship nose every client tick. Read BOTH the client camera and the server ship
        // attitude, converting the latter to a heading with the SAME quat->Euler the lock uses.
        double camYawBefore = bot().reportState().get("playerYaw").getAsDouble();
        for (int i = 0; i < 6; i++) {
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat() + 30f, st.get("playerPitch").getAsFloat());
            bot().waitTicks(1);
        }
        bot().waitTicks(4);
        double camYawAfter = bot().reportState().get("playerYaw").getAsDouble();
        float shipNoseYaw = shipNoseYaw(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ));
        assertTrue("a hard sideways mouse look must NOT free-look the camera — the view stays locked "
                        + "(camYawBefore=" + camYawBefore + " camYawAfter=" + camYawAfter + ")",
                angDiff(camYawAfter, camYawBefore) < 15.0);
        assertTrue("the CLIENT camera yaw must be LOCKED to the ship nose, not where the mouse pointed "
                        + "(camYawAfter=" + camYawAfter + " shipNose=" + shipNoseYaw + ")",
                angDiff(camYawAfter, shipNoseYaw) < 12.0);

        exec("artest player dismount");
    }

    /** The ship nose heading (MC yaw, degrees) from the attitude quaternion in {@code vs ship-info},
     *  using the SAME quat&rarr;Euler conversion the production camera lock uses (no convention drift). */
    private float shipNoseYaw(String shipInfoJson) {
        return FreeFlightPhysics.eulerFromQuat(new FreeFlightPhysics.Quat(
                readDouble(shipInfoJson, QW), readDouble(shipInfoJson, QX),
                readDouble(shipInfoJson, QY), readDouble(shipInfoJson, QZ)))[0];
    }

    /** Wrapped angular distance on the circle, degrees in [0, 180]. */
    private static double angDiff(double a, double b) {
        return Math.abs(((a - b + 540) % 360) - 180);
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
}
