package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Regression guard for the Free Flight launch gate.
 *
 * <p>Entering Free Flight must require the same fuel + climb authority (TWR &gt; 1)
 * the engine-start ritual enforces. {@code prepareLaunch()} is reachable WITHOUT
 * that ritual — a redstone monitoring station calls it directly — so a fuel-less
 * or underpowered FF craft launched that way used to call {@code startFreeFlight()}
 * unconditionally: it set isInFlight but never thrust, never left the ground, and
 * therefore never re-landed — a permanent on-pad in-flight dead-state with no way
 * to restart the engine.
 *
 * <p>This pins that a fuel-drained FF rocket driven through {@code prepareLaunch()}
 * stays grounded ({@code isInFlight == false}). Before the gate was shared into
 * {@code prepareLaunch()} this assertion failed (the rocket entered flight).
 */
public class FreeFlightLaunchGateTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
        String fillAir = ok(client().execute(
                "artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7)
                        + " minecraft:air"));
        assertTrue("pre-clear failed: " + fillAir, fillAir.contains("\"ok\":true"));

        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1));
        int by = Integer.parseInt(bp.group(2));
        int bz = Integer.parseInt(bp.group(3));

        String assemble = ok(client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));

        String list = ok(client().execute("artest rocket list 0"));
        Matcher rim = ROCKET_LIST_ID.matcher(list);
        int lastId = -1;
        while (rim.find()) lastId = Integer.parseInt(rim.group(1));
        assertTrue("rocket list empty after assemble: " + list, lastId >= 0);
        return lastId;
    }

    @Test
    public void fuellessFreeFlightRocketStaysGroundedOnPrepareLaunch() throws Exception {
        int id = buildAndAssemble(3380, 64, 700);

        String mode = ok(client().execute("artest rocket set-flight-mode " + id + " FREE_FLIGHT"));
        assertTrue("set FREE_FLIGHT failed: " + mode, mode.contains("\"flightMode\":\"FREE_FLIGHT\""));

        // Remove all fuel so canStartFreeFlight() must reject (rocketRequireFuel
        // defaults true).
        String drain = ok(client().execute("artest rocket drain-fuel " + id));
        assertTrue("drain-fuel failed: " + drain, drain.contains("\"ok\":true"));

        // Drive prepareLaunch through the redstone-equivalent server entry.
        String resp = ok(client().execute("artest rocket ff-prepare-launch " + id));
        assertTrue("ff-prepare-launch probe failed: " + resp, resp.contains("\"ok\":true"));
        assertTrue("rocket must be in FREE_FLIGHT mode for this pin: " + resp,
                resp.contains("\"isFreeFlight\":true"));

        // The gate: no fuel => must NOT enter flight (no on-pad dead-state).
        assertTrue("fuel-less FF rocket must stay grounded after prepareLaunch "
                        + "(gate regression — it entered flight): " + resp,
                resp.contains("\"isInFlight\":false"));
    }
}
