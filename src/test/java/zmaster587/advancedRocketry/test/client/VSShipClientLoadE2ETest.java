package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Client-tier checkpoint for the tier-2 Valkyrien Skies ship. A ship assembled on a
 * headless dedicated server never loads (no observer near it to pull its chunks in),
 * so testServer cannot drive it — proven by {@code VSShipMotionServerTest}. This test
 * exists to answer the one question that unblocks the whole pilotable-ship e2e: does a
 * REAL connected client, standing at the ship, make it load and become physics-active?
 *
 * <p>Flow: assemble the ship, walk the bot onto it, force a load, and assert the ship
 * becomes <em>loaded</em> (the state testServer could never reach) and then translates
 * under a direct velocity setpoint (model A). The server {@code vs ship-info} is the
 * cross-side oracle for motion; the load is the client-observed contract (a ship only
 * loads because the client is there). If this passes, the full piloting e2e (seat +
 * real keys + client-observed ship motion) is built on top; if the ship still will not
 * load with a client present, the automated ceiling is confirmed.
 *
 * <p>Gated on real VS presence — run with {@code -PwithVS}; skips otherwise.
 */
/**
 * End-to-end for the tier-2 Valkyrien Skies ship at the client tier: assemble → load (a
 * real client pulls its chunks in — a headless server cannot) → FLY IT WITH FORCE. This is
 * the honest path after the runtime finding that a velocity setpoint does nothing (VS's
 * solver overwrites setLinearVelocity); the ship is driven by a per-physics-tick force
 * controller ({@code vs force-vel} → {@code VSShipController}) instead. Also exercises the
 * spawn-window workaround (keep the observer far during spawn, or VS crashes on a double
 * ship-load). Gated on real VS presence — run with {@code -PwithVS}.
 */
public class VSShipClientLoadE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern VEL_Y = Pattern.compile("\"velY\":(-?[0-9.E\\-]+)");
    private static final Pattern QW = Pattern.compile("\"qw\":(-?[0-9.E\\-]+)");
    private static final Pattern QX = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern QY = Pattern.compile("\"qy\":(-?[0-9.E\\-]+)");
    private static final Pattern QZ = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");

    private static final String VARIANT = "with-advanced-flight-computer";
    private static final int BX = 2200, BY = 64, BZ = 2200;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void assembledShipLoadsWithClientPresentAndFliesAndRotatesUnderForce() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                serverHasVs());

        // Keep the client FAR AWAY during assembly + spawn. VS crashes with
        // "Tried loading a ShipData that was already loaded?" if a player is near the
        // ship as it spawns (spawn-load and proximity-load collide in one server tick).
        // Assemble with no observer, let the ship settle, THEN approach so a single
        // proximity load runs.
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("with VS, the AFC build must route to a ship (no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));

        // Wait for the ship to appear in the queryable registry (async spawn), then let
        // it fully settle with no observer.
        int all = 0;
        for (int i = 0; i < 40 && all < 1; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a VS ship in the queryable registry (all=" + all + ")",
                all >= 1);
        bot().waitTicks(40); // settle before any observer approaches

        // Now walk the client ONTO the ship's projected location. A real client near the
        // ship pulls its chunks in and VS loads it — the thing testServer never does.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);

        // Poll for the ship to become LOADED (the state testServer could never reach).
        double zBefore = Double.NaN;
        StringBuilder loadTrace = new StringBuilder();
        int loaded = 0;
        for (int i = 0; i < 40 && Double.isNaN(zBefore); i++) {
            bot().waitTicks(5);
            loaded = count("ship-count");
            if (i % 4 == 0) {
                loadTrace.append(i / 4).append("=").append(loaded).append(' ');
            }
            if (loaded >= 1) {
                String info = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
                if (info.contains("\"managed\":true")) {
                    zBefore = shipPosZ(info);
                }
            }
        }
        assertTrue("a VS ship must LOAD with a client present — loaded trajectory: ["
                        + loadTrace.toString().trim() + "], all=" + all,
                !Double.isNaN(zBefore));

        // Now that it is loaded + physics-enabled, command a straight-UP velocity realized
        // as FORCE (the working path — a raw setpoint does nothing). Up isolates the result
        // from ground friction: the only thing to overcome is gravity, and the controller's
        // deadbeat force (F = mass·accel, clamped to thrust authority) exceeds it. Re-command
        // each tick and POLL for the climb — VS's physics-thread activation after a load can
        // lag a few ticks, so drive until it rises (bounded) rather than a fixed window.
        double yBefore = shipPosY(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ));
        double yAfter = yBefore;
        double velY = 0.0;
        for (int i = 0; i < 80 && yAfter - yBefore <= 1.5; i++) {
            String cmd = exec("artest vs force-vel 0 " + BX + " " + BY + " " + BZ + " 0 8 0");
            assertTrue("force-vel must find the loaded ship: " + cmd, cmd.contains("\"commanded\":true"));
            bot().waitTicks(1);
            String info = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
            yAfter = shipPosY(info);
            velY = readDouble(info, VEL_Y, "velY");
        }

        // Force actually integrates into motion: the ship must climb. (Model A's setLinearVelocity
        // left this flat with velY≈1.8; a force controller lifts it.)
        assertTrue("commanded +Y velocity (via force) must lift the loaded ship "
                        + "(yBefore=" + yBefore + " yAfter=" + yAfter + " velY=" + velY + ")",
                yAfter - yBefore > 1.0);

        // The same controller must also ROTATE the ship: command a yaw angular velocity,
        // realized as TORQUE (linear zeroed → the ship hovers while it turns). The ship's
        // body→world attitude quaternion must move meaningfully off where it started. Poll
        // for the turn (bounded) for the same activation-lag robustness.
        double[] qBefore = readQuat(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ));
        double dot = 1.0;
        for (int i = 0; i < 80 && dot >= 0.97; i++) {
            String cmd = exec("artest vs force-rot 0 " + BX + " " + BY + " " + BZ + " 0 1.0 0");
            assertTrue("force-rot must find the loaded ship: " + cmd, cmd.contains("\"commanded\":true"));
            bot().waitTicks(1);
            double[] qNow = readQuat(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ));
            // |dot| of two unit quaternions is cos(halfAngle); < 0.98 ⇒ rotated by more than ~23°.
            dot = Math.abs(qBefore[0] * qNow[0] + qBefore[1] * qNow[1]
                    + qBefore[2] * qNow[2] + qBefore[3] * qNow[3]);
        }
        assertTrue("commanded yaw (via torque) must rotate the loaded ship "
                        + "(|quat dot|=" + dot + ", 1.0 = unmoved)",
                dot < 0.98);

        // ATTITUDE HOLD: command an absolute target orientation (90° yaw about world Y) and the
        // controller must drive the ship's attitude TO it and converge — the interface Free
        // Flight feeds (its per-tick target quaternion). Poll for convergence (bounded).
        final double[] target = {0.70710678, 0.0, 0.70710678, 0.0}; // {w,x,y,z}
        double convDot = 0.0;
        for (int i = 0; i < 120 && convDot < 0.98; i++) {
            String cmd = exec("artest vs point 0 " + BX + " " + BY + " " + BZ
                    + " " + target[0] + " " + target[1] + " " + target[2] + " " + target[3]);
            assertTrue("point must find the loaded ship: " + cmd, cmd.contains("\"commanded\":true"));
            bot().waitTicks(1);
            double[] q = readQuat(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ));
            convDot = Math.abs(q[0] * target[0] + q[1] * target[1] + q[2] * target[2] + q[3] * target[3]);
        }
        assertTrue("attitude-hold must converge the ship to the commanded orientation "
                        + "(|dot to target|=" + convDot + ", 1.0 = exact)",
                convDot > 0.98);

        // FULL FREE FLIGHT PATH: hand the flight computer a held pilot input. Its server tick
        // reads the ship's attitude, runs FreeFlightPhysics, and publishes to the controller —
        // no probe touches the ship command directly here. A throttle input must MOVE the ship
        // through that path (throttle → body axis → world velocity → controller force). We assert
        // total displacement (not just altitude): the ship is left tilted by the earlier rotate
        // and attitude phases, so "body up" is not world up — moving at all is the contract here.
        double[] pBefore = readVec(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ));
        double disp = 0.0;
        for (int i = 0; i < 80 && disp <= 1.5; i++) {
            String cmd = exec("artest vs ff-input 0 1 0 0 0 0"); // throttleVertical = full up
            assertTrue("ff-input must be accepted: " + cmd, cmd.contains("\"ok\":true"));
            bot().waitTicks(1);
            double[] p = readVec(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ));
            double dx = p[0] - pBefore[0], dy = p[1] - pBefore[1], dz = p[2] - pBefore[2];
            disp = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        assertTrue("a Free Flight throttle input must move the ship through the AFC's FF path "
                        + "(displacement=" + disp + ")",
                disp > 1.0);
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private double shipPosZ(String shipInfoJson) {
        return readDouble(shipInfoJson, POS_Z, "posZ");
    }

    private double shipPosY(String shipInfoJson) {
        return readDouble(shipInfoJson, POS_Y, "posY");
    }

    private double[] readVec(String shipInfoJson) {
        return new double[]{
                readDouble(shipInfoJson, POS_X, "posX"),
                readDouble(shipInfoJson, POS_Y, "posY"),
                readDouble(shipInfoJson, POS_Z, "posZ")};
    }

    private double[] readQuat(String shipInfoJson) {
        return new double[]{
                readDouble(shipInfoJson, QW, "qw"),
                readDouble(shipInfoJson, QX, "qx"),
                readDouble(shipInfoJson, QY, "qy"),
                readDouble(shipInfoJson, QZ, "qz")};
    }

    private double readDouble(String json, Pattern p, String label) {
        Matcher m = p.matcher(json);
        assertTrue("ship-info must carry " + label + ": " + json, m.find());
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
        int bx = Integer.parseInt(bp.group(1)),
                by = Integer.parseInt(bp.group(2)),
                bz = Integer.parseInt(bp.group(3));
        String assemble = exec("artest rocket assemble 0 " + bx + " " + by + " " + bz);
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));
        return assemble;
    }
}
