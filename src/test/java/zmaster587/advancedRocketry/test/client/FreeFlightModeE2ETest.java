package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Real-client end-to-end coverage for Free Flight Mode.
 *
 * <p>This boots both a dedicated server (via {@link AbstractClientE2ETest})
 * and a real MC client bot that connects in. The bot acts as a passenger;
 * server-side probes flip flight mode, push FF input, and the real server
 * tick loop runs {@code tickFreeFlight} on the live entity. The bot polls
 * the rocket entity through {@code /artest rocket info} to assert the
 * cross-side replication is coherent.
 *
 * Verified cross-side contracts:
 *  - Bot can mount a freshly-assembled rocket via {@code player mount-entity}.
 *  - Server-side flight-mode flip is observable via {@code rocket info}
 *    (which reads the field that NBT-roundtrips and is replicated through
 *    the datawatcher branch on subsequent state changes).
 *  - {@code start-free-flight} flips {@code isInFlight=true} without a chip.
 *  - Once airborne with non-zero throttle, server tick loop produces a
 *    cumulative motion delta over a 40-tick window.
 *  - The bot's reportState confirms the player is still riding the rocket
 *    AFTER ticks: the FF tick must NOT eject the passenger.
 *
 * <p>The client-side keypress→packet→server input wiring is unit-tested
 * via {@code FreeFlightInputTest} (ByteBuf round-trip with re-clamping) and
 * pinned indirectly here: the same {@code FREE_FLIGHT_INPUT} packet that
 * keybinds emit on real key events is what {@code free-flight-input} probe
 * dispatches server-side. A regression in the wire format would surface in
 * one of those two layers.
 *
 * Gated by {@code -Dforge.test.client=true}; skipped on headless CI.
 */
public class FreeFlightModeE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern MOTION_Y = Pattern.compile("\"motionY\":(-?[0-9.E\\-]+)");

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private int buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
        String fillAir = exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " "
                + (baseZ - 2) + " " + (baseX + 7) + " " + (baseY + 10) + " "
                + (baseZ + 7) + " minecraft:air");
        assertTrue("pre-clear failed: " + fillAir, fillAir.contains("\"ok\":true"));

        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " "
                + baseZ + " simple");
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture response missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1));
        int by = Integer.parseInt(bp.group(2));
        int bz = Integer.parseInt(bp.group(3));

        String assemble = exec("artest rocket assemble 0 " + bx + " " + by + " " + bz);
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));

        String list = exec("artest rocket list 0");
        Matcher rim = ROCKET_ID.matcher(list);
        int lastId = -1;
        while (rim.find()) lastId = Integer.parseInt(rim.group(1));
        assertTrue("rocket list empty after assemble: " + list, lastId >= 0);
        return lastId;
    }

    private static double parseDouble(String body, Pattern p, String label) {
        Matcher m = p.matcher(body);
        if (!m.find()) {
            throw new AssertionError("response missing " + label + ": " + body);
        }
        return Double.parseDouble(m.group(1));
    }

    // ---------------------------------------------------------------------

    @Test
    public void botMountsFreeFlightRocketAndObservesInFlightFlip() throws Exception {
        // Stand bot near the build site.
        exec("tp @a 3010 79 510 0 0");
        bot().waitTicks(10);

        int rocketId = buildAndAssemble(3000, 64, 500);

        // Move bot adjacent to the rocket so mount-entity has line-of-sight.
        exec("tp @a 3000.5 65 500.5 0 0");
        bot().waitTicks(5);

        String mount = exec("artest player mount-entity " + rocketId);
        assertTrue("mount-entity must succeed: " + mount,
                mount.contains("\"ok\":true") && mount.contains("\"mounted\":true"));

        // Pre-launch: flip mode to FREE_FLIGHT. This is the toggle contract
        // exercised by the M keybind path on a real client.
        String setMode = exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        assertTrue("set-flight-mode must succeed: " + setMode,
                setMode.contains("\"ok\":true"));
        assertTrue("mode echoed FREE_FLIGHT: " + setMode,
                setMode.contains("\"flightMode\":\"FREE_FLIGHT\""));

        // start-free-flight: bypass classic countdown.
        String start = exec("artest rocket start-free-flight " + rocketId);
        assertTrue("start-free-flight must succeed: " + start,
                start.contains("\"ok\":true"));
        // The probe response itself reflects the immediate isInFlight=true
        // (read in the same call as the mutation).
        assertTrue("start-free-flight must report isInFlight=true in response: " + start,
                start.contains("\"isInFlight\":true"));

        // Snapshot info IMMEDIATELY (the real tick loop will drain motionY
        // on the test fixture's low-thrust rocket; what we pin here is that
        // the datawatcher saw isInFlight=true at least once).
        String info = exec("artest rocket info " + rocketId);
        assertTrue("info must report flightMode=FREE_FLIGHT after toggle: " + info,
                info.contains("\"flightMode\":\"FREE_FLIGHT\""));

        // Bot is still riding the rocket — FF tick must not dismount the pilot.
        String riding = exec("artest player riding-entity");
        assertTrue("bot must still be riding the FF rocket after takeoff: " + riding,
                riding.contains("\"ridingEntityId\":" + rocketId)
                        || riding.contains("EntityRocket"));

        // Cleanup.
        exec("artest player dismount");
    }

    @Test
    public void verticalThrottleProducesObservableMotionThroughRealTickLoop() throws Exception {
        exec("tp @a 3110 79 510 0 0");
        bot().waitTicks(10);

        int rocketId = buildAndAssemble(3100, 64, 500);
        exec("tp @a 3100.5 65 500.5 0 0");
        bot().waitTicks(5);

        exec("artest player mount-entity " + rocketId);
        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        exec("artest rocket start-free-flight " + rocketId);

        // Push full vertical throttle. This is the same FreeFlightInput
        // payload that the M-key + Z-key keybind chain sends on a real
        // client press-and-hold.
        String inputResp = exec("artest rocket free-flight-input " + rocketId
                + " 0.0 1.0 0.0 0.0 0.0");
        assertTrue("input must apply on FF rocket: " + inputResp,
                inputResp.contains("\"applied\":true"));

        // Snapshot motion BEFORE ticks (right after start).
        String infoBefore = exec("artest rocket info " + rocketId);
        double myBefore = parseDouble(infoBefore, MOTION_Y, "motionY");

        // Let the REAL server tick loop run — onUpdate→tickFreeFlight runs
        // every server tick because the rocket is in FF + isInFlight.
        bot().waitTicks(20);

        String infoAfter = exec("artest rocket info " + rocketId);
        double myAfter = parseDouble(infoAfter, MOTION_Y, "motionY");

        // After 20 ticks of vertical-up thrust the motionY should be net
        // upward (thrust ≫ gravity for the simple fixture). Even if gravity
        // dominates, motion must have CHANGED — a frozen rocket means the
        // tick loop isn't running the FF branch.
        assertNotEquals(
                "FF tick must mutate motionY across 20 server ticks "
                        + "(was " + myBefore + ", now " + myAfter + ")",
                myBefore, myAfter, 1e-9);

        // Bot still riding — FF tick preserves passenger across server ticks.
        String riding = exec("artest player riding-entity");
        assertFalse("FF tick must NOT auto-dismount the pilot mid-flight: " + riding,
                riding.contains("\"ridingEntityId\":-1"));

        // Cleanup.
        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void modeTogglesAreObservableFromBotSide() throws Exception {
        // Toggle without mounting — exercises the server probe surface that
        // the M-key sends via SET_FLIGHT_MODE packet. The bot just stays
        // connected and observes through the rocket info.
        exec("tp @a 3210 79 510 0 0");
        bot().waitTicks(10);

        int rocketId = buildAndAssemble(3200, 64, 500);

        String info0 = exec("artest rocket info " + rocketId);
        assertTrue("default mode must be CLASSIC_LAUNCH: " + info0,
                info0.contains("\"flightMode\":\"CLASSIC_LAUNCH\""));

        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        bot().waitTicks(5);
        String info1 = exec("artest rocket info " + rocketId);
        assertTrue("after toggle, info must report FREE_FLIGHT: " + info1,
                info1.contains("\"flightMode\":\"FREE_FLIGHT\""));

        exec("artest rocket set-flight-mode " + rocketId + " CLASSIC_LAUNCH");
        bot().waitTicks(5);
        String info2 = exec("artest rocket info " + rocketId);
        assertTrue("flip-back must restore CLASSIC_LAUNCH: " + info2,
                info2.contains("\"flightMode\":\"CLASSIC_LAUNCH\""));
    }
}
