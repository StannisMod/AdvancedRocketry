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
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern VEL_Y = Pattern.compile("\"velY\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");

    private static final String VARIANT = "with-advanced-flight-computer";
    private static final int BX = 2200, BY = 64, BZ = 2200;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void assembledShipLoadsWithClientPresentAndFliesUnderForce() throws Exception {
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
        // each tick to mirror how the flight computer will drive it.
        double yBefore = shipPosY(exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ));
        for (int i = 0; i < 30; i++) {
            String cmd = exec("artest vs force-vel 0 " + BX + " " + BY + " " + BZ + " 0 8 0");
            assertTrue("force-vel must find the loaded ship: " + cmd, cmd.contains("\"commanded\":true"));
            bot().waitTicks(1);
        }
        String lastInfo = exec("artest vs ship-info 0 " + BX + " " + BY + " " + BZ);
        double yAfter = shipPosY(lastInfo);
        double velY = readDouble(lastInfo, VEL_Y, "velY");

        // Force actually integrates into motion: the ship must climb. (Model A's setLinearVelocity
        // left this flat with velY≈1.8; a force controller lifts it.)
        assertTrue("commanded +Y velocity (via force) must lift the loaded ship "
                        + "(yBefore=" + yBefore + " yAfter=" + yAfter + " velY=" + velY + ")",
                yAfter - yBefore > 1.0);
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
