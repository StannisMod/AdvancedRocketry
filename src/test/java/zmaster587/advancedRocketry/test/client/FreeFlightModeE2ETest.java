package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
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
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern YAW   = Pattern.compile("\"rotationYaw\":(-?[0-9.E\\-]+)");
    private static final Pattern FF_PITCH = Pattern.compile("\"freeFlightPitch\":(-?[0-9.E\\-]+)");
    private static final Pattern FUEL_PRIMARY_AMOUNT =
            Pattern.compile("\"primaryFuelType\":\"([^\"]+)\".*?\"\\1\":\\{\"amount\":(-?\\d+)");

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private int buildAndAssemble(int baseX, int baseY, int baseZ) throws Exception {
        // Clear the full flight column, not just the build site. The world is
        // generated with a RANDOM seed each run; a hill or tree overhanging the
        // pad above the old +10 ceiling pins the assembled rocket in place
        // (Entity.move() zeroes motionY on the vertical collision) and every
        // thrust assertion downstream reads an exactly-0.0 motion. Caught via
        // collidedVertically=true after a run-to-run flaky "rocket never moves".
        String fillAir = exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " "
                + (baseZ - 2) + " " + (baseX + 7) + " " + (baseY + 50) + " "
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

        // Pad-bounds detection occasionally races chunk/structure state on the
        // shared world; retry the assemble a couple of times before failing.
        String assemble = exec("artest rocket assemble 0 " + bx + " " + by + " " + bz);
        for (int attempt = 0; attempt < 3 && !assemble.contains("\"ok\":true"); attempt++) {
            bot().waitTicks(5);
            exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple");
            assemble = exec("artest rocket assemble 0 " + bx + " " + by + " " + bz);
        }
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

    // ===== FF flight controls (TWR-based thrust) =========================

    private int mountFreshFreeFlightRocket(int baseX, int baseY, int baseZ) throws Exception {
        exec("tp @a " + (baseX + 10) + " " + (baseY + 15) + " " + (baseZ + 10) + " 0 0");
        bot().waitTicks(10);
        int rocketId = buildAndAssemble(baseX, baseY, baseZ);
        exec("tp @a " + (baseX + 0.5) + " " + (baseY + 1) + " " + (baseZ + 0.5) + " 0 0");
        bot().waitTicks(5);
        exec("artest player mount-entity " + rocketId);
        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        exec("artest rocket start-free-flight " + rocketId);
        // The v1 takeoff is a decaying kick + grace window; on a slow/contended
        // harness the bot round-trips can outlast it and the rocket re-lands
        // before the test's input arrives, failing on "never moved" instead of
        // the contract under test. Confirm we're airborne, retrying the start —
        // same pattern as the assemble retry above. (The TASK-46 Phase 2
        // engine-start hover removes the kick and this crutch with it.)
        for (int attempt = 0; attempt < 3; attempt++) {
            if (exec("artest rocket info " + rocketId).contains("\"isInFlight\":true")) {
                return rocketId;
            }
            exec("artest rocket start-free-flight " + rocketId);
            bot().waitTicks(2);
        }
        assertTrue("rocket must be in flight after start-free-flight (retried)",
                exec("artest rocket info " + rocketId).contains("\"isInFlight\":true"));
        return rocketId;
    }

    @Test
    public void verticalThrustGainsAltitude() throws Exception {
        // The core "can take off" contract: with TWR-based thrust, a
        // launch-capable fixture rocket (TWR ≫ 1) must actually CLIMB under
        // full vertical throttle through the live server tick loop — not just
        // hop and re-land like the old /10000-scaled thrust did.
        int rocketId = mountFreshFreeFlightRocket(3300, 64, 500);

        String inputResp = exec("artest rocket free-flight-input " + rocketId + " 0 1 0 0 0");
        assertTrue("vertical input must apply: " + inputResp, inputResp.contains("\"applied\":true"));

        double yBefore = parseDouble(exec("artest rocket info " + rocketId), POS_Y, "posY");
        bot().waitTicks(30);
        String after = exec("artest rocket info " + rocketId);
        double yAfter = parseDouble(after, POS_Y, "posY");

        assertTrue("FF rocket must gain real altitude under vertical thrust "
                        + "(yBefore=" + yBefore + " yAfter=" + yAfter + ")",
                yAfter - yBefore > 2.0);
        assertTrue("rocket must still be in flight while climbing: " + after,
                after.contains("\"isInFlight\":true"));

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void forwardThrustDisplacesHorizontally() throws Exception {
        // Forward throttle at yaw=0 → +Z. Vertical kept on so the rocket stays
        // airborne (doesn't auto-land mid-test).
        int rocketId = mountFreshFreeFlightRocket(3400, 64, 500);

        exec("artest rocket free-flight-input " + rocketId + " 1 1 0 0 0");
        String before = exec("artest rocket info " + rocketId);
        double xb = parseDouble(before, POS_X, "posX");
        double zb = parseDouble(before, POS_Z, "posZ");
        bot().waitTicks(30);
        String after = exec("artest rocket info " + rocketId);
        double xa = parseDouble(after, POS_X, "posX");
        double za = parseDouble(after, POS_Z, "posZ");

        double horiz = Math.sqrt((xa - xb) * (xa - xb) + (za - zb) * (za - zb));
        assertTrue("forward thrust must move the rocket horizontally over 30 ticks "
                        + "(horiz=" + horiz + ")", horiz > 1.0);
        assertTrue("forward at yaw=0 must be predominantly +Z, got dz=" + (za - zb),
                (za - zb) > 0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void yawInputRotatesHeading() throws Exception {
        // Yaw input must steer the heading through the live loop. Vertical kept
        // on to stay airborne while yawing (yaw rotates regardless of thrust).
        int rocketId = mountFreshFreeFlightRocket(3500, 64, 500);

        exec("artest rocket free-flight-input " + rocketId + " 0 1 1 0 0");
        double yawBefore = parseDouble(exec("artest rocket info " + rocketId), YAW, "rotationYaw");
        bot().waitTicks(8);
        double yawAfter = parseDouble(exec("artest rocket info " + rocketId), YAW, "rotationYaw");

        assertTrue("yaw input must rotate heading over 8 ticks "
                        + "(before=" + yawBefore + " after=" + yawAfter + ")",
                Math.abs(yawAfter - yawBefore) > 10.0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    // ===== REAL keypress path (no server input probe) ====================

    @Test
    public void realVerticalKeyThrustClimbsServerAndClientTracks() throws Exception {
        // The honest end-to-end: FF entry via probes (M/space are event-driven and
        // not the broken part), but STEERING via a REAL injected key. Holding R
        // (flightVerticalUp) drives KeyBindings.onClientTick → a real
        // FREE_FLIGHT_INPUT packet → server tickFreeFlight. We assert BOTH halves
        // that the probe tests could never see:
        //   1) the server rocket actually climbs (real packet path delivers thrust),
        //   2) the CLIENT-rendered rocket tracks the server (no poscorrection lag).
        int rocketId = mountFreshFreeFlightRocket(3700, 64, 500);

        // Hold the real climb key. No artest free-flight-input here on purpose.
        bot().holdKey(Keyboard.KEY_R);

        double svrYBefore = parseDouble(exec("artest rocket info " + rocketId), POS_Y, "posY");
        bot().waitTicks(40);
        String svrInfo = exec("artest rocket info " + rocketId);
        double svrYAfter = parseDouble(svrInfo, POS_Y, "posY");

        JsonObject ride = bot().reportRidingEntity();
        assertTrue("client must still be riding the rocket: " + ride,
                ride.has("riding") && ride.get("riding").getAsBoolean());
        double cliY = ride.get("posY").getAsDouble();

        bot().releaseKey(Keyboard.KEY_R);

        // 1) Real key → real packet → server physics.
        assertTrue("holding real R must drive a server-side climb via the packet path "
                        + "(before=" + svrYBefore + " after=" + svrYAfter + ")",
                svrYAfter - svrYBefore > 2.0);
        assertTrue("rocket must stay in flight while climbing: " + svrInfo,
                svrInfo.contains("\"isInFlight\":true"));

        // 2) Client render tracks server — would be ~150 blocks behind with the old
        //    ct=50 poscorrection smoothing that this fix bypasses for FF.
        assertTrue("client-rendered rocket Y must track server Y within a few blocks "
                        + "(client=" + cliY + " server=" + svrYAfter + ")",
                Math.abs(cliY - svrYAfter) < 6.0);

        exec("artest player dismount");
    }

    @Test
    public void freeFlightClientRenderAdvancesEveryTickNoStutter() throws Exception {
        // Render smoothness: the client must dead-reckon every tick, so the
        // rendered rocket advances on (almost) every single client tick. The
        // snap-only approach froze between the every-3-tick tracker updates and
        // jumped on update ticks — here that shows up as many zero-delta samples.
        int rocketId = mountFreshFreeFlightRocket(4000, 64, 500);
        // Drive a reliable, sustained server-side climb. Probe input is
        // authoritative and not subject to key-injection timing; the bot holds
        // no keys, so onClientTick stays quiet and doesn't override it. (The real
        // keypress path is covered by realZKeyThrustClimbsServerAndClientTracks.)
        // This isolates the actual contract under test: given server motion, does
        // the CLIENT render advance smoothly every tick?
        exec("artest rocket free-flight-input " + rocketId + " 0 1 0 0 0");
        bot().waitTicks(8); // past the launch-kick transient, into a steady climb

        int samples = 8;
        int moved = 0;
        double prev = bot().reportRidingEntity().get("posY").getAsDouble();
        for (int i = 0; i < samples; i++) {
            bot().waitTicks(1);
            double cur = bot().reportRidingEntity().get("posY").getAsDouble();
            if (cur - prev > 1e-4) moved++;
            prev = cur;
        }
        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");

        assertTrue("FF client render must advance on (almost) every tick rather than "
                        + "freeze-then-jump (moved " + moved + "/" + samples + ")",
                moved >= samples - 2);

        exec("artest player dismount");
    }

    // ===== HUD =========================================================

    private static final String ROCKET_EVENT_HANDLER =
            "zmaster587.advancedRocketry.event.RocketEventHandler";

    @Test
    public void freeFlightHudInFlightShowsIndicatorAndControlLegend() throws Exception {
        // Riding a FF rocket in flight: the HUD must render the mode indicator,
        // a control legend keyed to the pilot's bindings, and the FA state. Read
        // the actually-rendered text from the client (reflective static), so a
        // missing lang key (which I18n echoes back raw) fails these assertions.
        int rocketId = mountFreshFreeFlightRocket(3800, 64, 500);
        bot().waitTicks(10); // let the overlay render a few frames

        String hud = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();

        assertTrue("FF HUD must show the active-mode indicator: " + hud,
                hud.contains("FREE FLIGHT"));
        assertTrue("FF HUD must show a vertical-thrust control hint: " + hud,
                hud.contains("Up / Down"));
        assertTrue("FF HUD must show the Flight Assist state: " + hud,
                hud.contains("Flight Assist"));

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void freeFlightHudPreLaunchShowsLaunchHint() throws Exception {
        // FF mode, mounted but NOT launched: HUD shows the title + how to launch /
        // switch back to classic — distinct from the in-flight legend.
        exec("tp @a 3910 79 510 0 0");
        bot().waitTicks(10);
        int rocketId = buildAndAssemble(3900, 64, 500);
        exec("tp @a 3900.5 65 500.5 0 0");
        bot().waitTicks(5);
        exec("artest player mount-entity " + rocketId);
        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        bot().waitTicks(10);

        String hud = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();

        assertTrue("pre-launch FF HUD must show the mode title: " + hud,
                hud.contains("Free Flight Mode"));
        assertTrue("pre-launch FF HUD must show the engine-start hint: " + hud,
                hud.contains("ENGINES OFF"));
        assertTrue("pre-launch FF HUD must show the classic-mode toggle hint: " + hud,
                hud.contains("Classic mode"));

        exec("artest player dismount");
    }

    @Test
    public void verticalThrustDrainsFuelThroughLiveLoop() throws Exception {
        // Fuel must burn classic-style (getFuelConsumptionRate, gated by
        // rocketRequireFuel) while thrust is applied across real server ticks.
        int rocketId = mountFreshFreeFlightRocket(3600, 64, 500);

        Matcher mb = FUEL_PRIMARY_AMOUNT.matcher(exec("artest rocket fuel " + rocketId));
        assertTrue("rocket must report a primary fuel amount", mb.find());
        int fuelBefore = Integer.parseInt(mb.group(2));
        assertTrue("start-free-flight must auto-fill fuel, got " + fuelBefore, fuelBefore > 0);

        exec("artest rocket free-flight-input " + rocketId + " 0 1 0 0 0");
        bot().waitTicks(20);

        Matcher ma = FUEL_PRIMARY_AMOUNT.matcher(exec("artest rocket fuel " + rocketId));
        assertTrue(ma.find());
        int fuelAfter = Integer.parseInt(ma.group(2));
        assertTrue("FF thrust must drain primary fuel through the live loop; "
                        + "before=" + fuelBefore + " after=" + fuelAfter,
                fuelAfter < fuelBefore);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    // ===== Key-conflict resolution (ARKeyConflictContext) =================
    // Steering keys share defaults with vanilla (E inventory, Q drop, A/D
    // strafe). These honest client tests prove the two halves of the contract:
    // the standard key behaves vanilla on foot, and is overridden — not merely
    // shadowed — while piloting. Both observe the REAL client (GUI screen +
    // client-driven server state), never a server probe stand-in.

    /** Default inventory key (E) — the vanilla binding pitch-down shares a key with. */
    private static final int KEY_INVENTORY = Keyboard.KEY_E;

    private String currentScreen() throws Exception {
        return bot().reportState().get("screen").getAsString();
    }

    @Test
    public void inventoryKeyOpensInventoryWhenNotPiloting() throws Exception {
        // On foot (not piloting any AR craft), pressing the inventory key must
        // open the survival inventory exactly like vanilla — i.e. the FF key
        // override does NOT leak into normal gameplay.
        exec("tp @a 4200 79 510 0 0");
        bot().waitTicks(10);
        // Guarantee the precondition: not riding, no GUI up.
        exec("artest player dismount");
        bot().closeScreen();
        bot().waitTicks(3);
        assertEquals("precondition: no screen should be open before pressing E",
                "", currentScreen());

        bot().setKey(KEY_INVENTORY, true);
        bot().waitTicks(3);
        String screen = currentScreen();
        bot().setKey(KEY_INVENTORY, false);
        bot().closeScreen();

        // Survival opens GuiInventory; creative opens GuiContainerCreative —
        // both are the vanilla inventory-key action, which is the point.
        assertTrue("pressing the inventory key on foot must open the inventory GUI, got: "
                        + screen,
                screen.endsWith("GuiInventory") || screen.endsWith("GuiContainerCreative"));
    }

    @Test
    public void inventoryKeyIsOverriddenToStrafeWhilePiloting() throws Exception {
        // Same physical key (E), while piloting in Free Flight: it must NOT open
        // the inventory (which would also freeze steering) and must instead drive
        // the lateral strafe control through the real key->packet->server path.
        int rocketId = mountFreshFreeFlightRocket(4300, 64, 500);
        assertEquals("precondition: no GUI open while piloting", "", currentScreen());

        double xBefore = parseDouble(exec("artest rocket info " + rocketId), POS_X, "posX");

        // Hold vertical-up (R, keeps it airborne so the FF tick keeps running) AND
        // the inventory key (E). On foot E opens the inventory; here it must
        // strafe (right = +X at yaw 0).
        bot().holdKey(Keyboard.KEY_R);
        bot().holdKey(KEY_INVENTORY);
        bot().waitTicks(25);

        String screenDuring = currentScreen();
        String info = exec("artest rocket info " + rocketId);
        double xAfter = parseDouble(info, POS_X, "posX");

        bot().releaseKey(KEY_INVENTORY);
        bot().releaseKey(Keyboard.KEY_R);

        assertEquals("inventory key must NOT open the inventory while piloting "
                + "(would also freeze steering): " + screenDuring, "", screenDuring);
        assertTrue("inventory key must instead strafe the craft (+X at yaw 0) while piloting "
                        + "(xBefore=" + xBefore + " xAfter=" + xAfter + ")",
                xAfter - xBefore > 1.0);
        assertTrue("rocket must stay in flight (override must not have frozen control): " + info,
                info.contains("\"isInFlight\":true"));

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void strafeLeftKeyMovesNegativeX() throws Exception {
        // Q (strafe left) → -X at yaw 0, the mirror of E. Drop key on foot;
        // strafe in the cockpit.
        int rocketId = mountFreshFreeFlightRocket(4400, 64, 500);
        double xBefore = parseDouble(exec("artest rocket info " + rocketId), POS_X, "posX");

        bot().holdKey(Keyboard.KEY_R);          // stay airborne
        bot().holdKey(Keyboard.KEY_Q);          // strafe left
        bot().waitTicks(25);
        double xAfter = parseDouble(exec("artest rocket info " + rocketId), POS_X, "posX");
        bot().releaseKey(Keyboard.KEY_Q);
        bot().releaseKey(Keyboard.KEY_R);

        assertTrue("Q must strafe -X at yaw 0 (xBefore=" + xBefore + " xAfter=" + xAfter + ")",
                xAfter - xBefore < -1.0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void verticalKeysClimbAndDescend() throws Exception {
        // R climbs (real altitude gain); F is the opposite vertical thrust, so it
        // must drive the vertical velocity down. We measure F by motionY (robust
        // to the climb's accumulated upward inertia, which a position check is not).
        int rocketId = mountFreshFreeFlightRocket(4500, 64, 500);

        double y0 = parseDouble(exec("artest rocket info " + rocketId), POS_Y, "posY");
        bot().holdKey(Keyboard.KEY_R);
        bot().waitTicks(20);
        String climbInfo = exec("artest rocket info " + rocketId);
        double y1 = parseDouble(climbInfo, POS_Y, "posY");
        double myUp = parseDouble(climbInfo, MOTION_Y, "motionY");
        bot().releaseKey(Keyboard.KEY_R);
        assertTrue("R must climb (y0=" + y0 + " y1=" + y1 + ")", y1 - y0 > 2.0);

        // F is downward thrust: it must reduce the vertical velocity vs the climb.
        bot().holdKey(Keyboard.KEY_F);
        bot().waitTicks(10);
        double myDown = parseDouble(exec("artest rocket info " + rocketId), MOTION_Y, "motionY");
        bot().releaseKey(Keyboard.KEY_F);
        assertTrue("F must reduce vertical velocity vs the climb (myUp=" + myUp
                + " myDown=" + myDown + ")", myDown < myUp - 0.05);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void throttleCutKeyNeutralisesThrust() throws Exception {
        // X (cut) with FA on zeroes the velocity setpoint even while R is held:
        // a climbing craft stops accelerating and eases back toward hover.
        int rocketId = mountFreshFreeFlightRocket(4600, 64, 500);

        // Establish a climb.
        bot().holdKey(Keyboard.KEY_R);
        bot().waitTicks(12);
        double myClimb = parseDouble(exec("artest rocket info " + rocketId), MOTION_Y, "motionY");
        assertTrue("precondition: R must be producing upward motion, got " + myClimb,
                myClimb > 0.01);

        // Now also hold X (cut) — vertical input is zeroed; with FA-off coast the
        // craft no longer accelerates upward (motionY stops growing).
        bot().holdKey(Keyboard.KEY_X);
        bot().waitTicks(12);
        double myCut = parseDouble(exec("artest rocket info " + rocketId), MOTION_Y, "motionY");
        bot().releaseKey(Keyboard.KEY_X);
        bot().releaseKey(Keyboard.KEY_R);

        assertTrue("throttle-cut must stop upward acceleration (climb=" + myClimb
                + " afterCut=" + myCut + ")", myCut <= myClimb + 1e-3);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void cutKeyBrakesToAGravityCancelledHover() throws Exception {
        // X with Flight Assist on (TASK-46 D4): zero the velocity setpoint —
        // the craft eases to a stop AND holds altitude. Build a climb via the
        // real R key first, then hold the real X.
        int rocketId = mountFreshFreeFlightRocket(4700, 64, 500);

        bot().holdKey(Keyboard.KEY_R);
        bot().waitTicks(15);
        bot().releaseKey(Keyboard.KEY_R);
        String preInfo = exec("artest rocket info " + rocketId);
        double myMoving = parseDouble(preInfo, MOTION_Y, "motionY");
        if (!(Math.abs(myMoving) > 0.02)) {
            // Diagnose before failing: one SYNCHRONOUS physics step shows whether
            // the physics produces thrust and what immediately eats it.
            String singleStep = exec("artest rocket free-flight-tick " + rocketId + " 1");
            String postStep = exec("artest rocket info " + rocketId);
            throw new AssertionError("precondition: rocket must be climbing before the cut, got "
                    + myMoving + "\n  state: " + preInfo
                    + "\n  single-step: " + singleStep
                    + "\n  after-step: " + postStep);
        }

        bot().holdKey(Keyboard.KEY_X);
        bot().waitTicks(40);
        String info = exec("artest rocket info " + rocketId);
        double myCut = parseDouble(info, MOTION_Y, "motionY");
        bot().releaseKey(Keyboard.KEY_X);

        assertTrue("cut must brake the climb into a hover (was " + myMoving
                + ", now " + myCut + ")", Math.abs(myCut) < 0.05);
        assertTrue("the hover must hold altitude, not land: " + info,
                info.contains("\"isInFlight\":true"));

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    // ===== Engine start (TASK-46 D3) =====================================
    //
    // The REAL path: hold the actual jump key (Space) on the client for 3 s;
    // KeyBindings.onClientTick accumulates the hold and sends ENGINE_START;
    // the server validates and starts the hover. No probe shortcut here.

    /** Build + mount + flip to FREE_FLIGHT, but do NOT start the engines. */
    private int mountColdFreeFlightRocket(int baseX, int baseY, int baseZ) throws Exception {
        exec("tp @a " + (baseX + 10) + " " + (baseY + 15) + " " + (baseZ + 10) + " 0 0");
        bot().waitTicks(10);
        int rocketId = buildAndAssemble(baseX, baseY, baseZ);
        exec("tp @a " + (baseX + 0.5) + " " + (baseY + 1) + " " + (baseZ + 0.5) + " 0 0");
        bot().waitTicks(5);
        exec("artest player mount-entity " + rocketId);
        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        // Fuel up (a freshly-assembled fixture is empty): the ENGINE_START
        // validation honestly rejects a dry rocket, which is its own contract —
        // these tests exercise the start RITUAL, so they fly fuelled.
        String fuel = exec("artest rocket fill-fuel " + rocketId);
        assertTrue("fill-fuel must succeed: " + fuel, fuel.contains("\"ok\":true"));
        bot().waitTicks(5);
        return rocketId;
    }

    @Test
    public void realSpaceHoldStartsEnginesAndHoversOneBlock() throws Exception {
        int rocketId = mountColdFreeFlightRocket(5100, 64, 500);
        String before = exec("artest rocket info " + rocketId);
        assertTrue("precondition: engines off before the hold: " + before,
                before.contains("\"isInFlight\":false"));
        double y0 = parseDouble(before, POS_Y, "posY");

        bot().holdKey(Keyboard.KEY_SPACE);
        bot().waitTicks(75);             // 60-tick hold + margin
        bot().releaseKey(Keyboard.KEY_SPACE);
        bot().waitTicks(40);             // let the liftoff hover settle

        String info = exec("artest rocket info " + rocketId);
        assertTrue("3 s Space hold must start the engines (isInFlight=true): " + info,
                info.contains("\"isInFlight\":true"));
        double y = parseDouble(info, POS_Y, "posY");
        double my = parseDouble(info, MOTION_Y, "motionY");
        assertTrue("craft must hover ~1 block above the pad (y0=" + y0 + " y=" + y + ")",
                y > y0 + 0.5 && y < y0 + 1.6);
        assertTrue("hover must be near-stationary (motionY=" + my + ")",
                Math.abs(my) < 0.06);

        // The pilot SEES the engine state: the rendered HUD reports ENGINES ON
        // (or the transient "Engines started" flash right after the start).
        String hud = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();
        assertTrue("HUD must show the engines running: " + hud,
                hud.contains("ENGINES ON") || hud.contains("Engines started"));

        exec("artest player dismount");
    }

    @Test
    public void spaceEarlyReleaseCancelsEngineStart() throws Exception {
        int rocketId = mountColdFreeFlightRocket(5200, 64, 500);

        bot().holdKey(Keyboard.KEY_SPACE);
        bot().waitTicks(25);             // well under the 60-tick requirement

        // Mid-hold the pilot must SEE the start progress.
        String hudMidHold = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();
        assertTrue("HUD must show engine-start progress while holding: " + hudMidHold,
                hudMidHold.contains("STARTING ENGINES"));

        bot().releaseKey(Keyboard.KEY_SPACE);
        bot().waitTicks(20);

        String info = exec("artest rocket info " + rocketId);
        assertTrue("early release must cancel the start (still not in flight): " + info,
                info.contains("\"isInFlight\":false"));
        String hud = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();
        assertTrue("HUD must be back to ENGINES OFF after the cancel: " + hud,
                hud.contains("ENGINES OFF"));

        exec("artest player dismount");
    }

    @Test
    public void descendKeyLandsAndShutsEnginesOff() throws Exception {
        // Full cycle through real keys: start via probe (covered above), then
        // descend with the real F key until touchdown — engines must shut off
        // and the HUD must say so.
        int rocketId = mountFreshFreeFlightRocket(5300, 64, 500);
        bot().waitTicks(30); // settle into the liftoff hover

        bot().holdKey(Keyboard.KEY_F);
        // Poll for landing rather than a fixed wait (descent into ground
        // contact + landed-event latency varies with load).
        boolean landed = false;
        for (int i = 0; i < 40 && !landed; i++) {
            bot().waitTicks(5);
            landed = exec("artest rocket info " + rocketId).contains("\"isInFlight\":false");
        }
        bot().releaseKey(Keyboard.KEY_F);
        assertTrue("descending into the ground must shut the engines off", landed);

        bot().waitTicks(5);
        String hud = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();
        assertTrue("HUD must reflect the shutdown (stopped flash or ENGINES OFF): " + hud,
                hud.contains("Engines stopped") || hud.contains("ENGINES OFF"));

        exec("artest player dismount");
    }

    // ===== HUD indication (TASK-46 Phase 4) ===============================

    private static final Pattern HUD_VRT =
            Pattern.compile("VRT ([+-][0-9.]+)/([+-][0-9.]+)");

    @Test
    public void hudVectorLineTracksSetpointAndVelocity() throws Exception {
        // The per-axis vector readout: holding R ramps the VRT setpoint and the
        // actual velocity follows; X zeroes the setpoint back. Read from the
        // REAL rendered HUD text.
        int rocketId = mountFreshFreeFlightRocket(5400, 64, 500);

        bot().holdKey(Keyboard.KEY_R);
        bot().waitTicks(25);
        String hudClimb = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();
        bot().releaseKey(Keyboard.KEY_R);

        Matcher m = HUD_VRT.matcher(hudClimb);
        assertTrue("HUD must render the VRT setpoint/actual pair: " + hudClimb, m.find());
        double sp = Double.parseDouble(m.group(1));
        double act = Double.parseDouble(m.group(2));
        assertTrue("VRT setpoint must have ramped up while R held (got " + sp + ")",
                sp > 0.4);
        assertTrue("actual velocity must chase the setpoint (got " + act + ")",
                act > 0.1);
        assertTrue("HUD must show the speed readout: " + hudClimb,
                hudClimb.contains("SPD"));

        // Cut: the setpoint marker must return to zero on the rendered HUD.
        bot().holdKey(Keyboard.KEY_X);
        bot().waitTicks(15);
        String hudCut = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();
        bot().releaseKey(Keyboard.KEY_X);
        Matcher m2 = HUD_VRT.matcher(hudCut);
        assertTrue("HUD must still render the VRT pair after the cut: " + hudCut, m2.find());
        assertEquals("cut must zero the rendered VRT setpoint",
                0.0, Double.parseDouble(m2.group(1)), 0.01);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void hudShowsNewtonianLabelWhenFlightAssistIsOff() throws Exception {
        // FA state is part of the perception contract: with FA off the HUD
        // must say so (the N keybind path is edge-driven and not injectable —
        // the probe flips the same server state the key would).
        int rocketId = mountFreshFreeFlightRocket(5500, 64, 500);
        bot().waitTicks(5);

        exec("artest rocket set-flight-assist " + rocketId + " off");
        bot().waitTicks(5);
        String hud = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();
        assertTrue("HUD must label the Newtonian mode when FA is off: " + hud,
                hud.contains("Newtonian"));

        exec("artest rocket set-flight-assist " + rocketId + " on");
        bot().waitTicks(5);
        String hudOn = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();
        assertFalse("HUD must drop the Newtonian label when FA is back on: " + hudOn,
                hudOn.contains("Newtonian"));

        exec("artest player dismount");
    }

    // ===== Camera-nose lock + mouse-as-rate (TASK-46 D1) =================
    //
    // THE perception contract that v1 missed: the view and the nose must
    // never diverge. These tests read BOTH sides from the real client
    // (reportState for the player camera, reportRidingEntity for the craft)
    // and inject look changes the way the mouse produces them (setLook).

    /** Wrapped angular distance on the circle, degrees in [0, 180]. */
    private static double angDiff(double a, double b) {
        return Math.abs(((a - b + 540) % 360) - 180);
    }

    @Test
    public void cameraIsLockedToCraftYawAndPitchWhileManeuvering() throws Exception {
        // While actively maneuvering (climb + yaw key + mouse swipes), the
        // client camera must stay pinned to the craft axes on every sampled
        // tick (loose eps: the two bot reads aren't atomic, one tick may pass
        // between them — max craft turn is 6°/tick). Then, with all input
        // released and corrections bled out, the lock must be exact.
        int rocketId = mountFreshFreeFlightRocket(4800, 64, 500);

        bot().holdKey(Keyboard.KEY_R);   // stay airborne
        bot().holdKey(Keyboard.KEY_D);   // yaw the nose
        for (int i = 0; i < 8; i++) {
            // A mouse swipe on top of the key yaw: down-right each tick.
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat() + 4f,
                          st.get("playerPitch").getAsFloat() + 3f);
            bot().waitTicks(1);
        }
        bot().releaseKey(Keyboard.KEY_D);
        bot().releaseKey(Keyboard.KEY_R);
        // Wait for the craft rotation to actually settle (the client bleeds the
        // server correction geometrically; under load the residual takes longer
        // than a fixed tick count, and a non-atomic read pair straddling the
        // bleed reads as a phantom lock error).
        double prevYaw = Double.NaN;
        for (int i = 0; i < 20; i++) {
            bot().waitTicks(2);
            double yawNow = bot().reportRidingEntity().get("rotationYaw").getAsDouble();
            if (!Double.isNaN(prevYaw) && angDiff(yawNow, prevYaw) < 0.02) break;
            prevYaw = yawNow;
        }

        // Frame-time lock telemetry: the worst divergence the pilot SAW on any
        // rendered frame of this flight (sampled atomically on the render
        // thread — immune to the bot's non-atomic read pairs). The legitimate
        // transient is one frame straddling an injected look swipe (≤6°) plus
        // a slow tick or two of craft turn (6°/tick) ≈ up to ~18°, consumed by
        // the very next pin. What this pins is "no runaway": v1's broken look
        // detached by tens of degrees and STAYED detached.
        double maxErr = Double.parseDouble(bot()
                .readStaticField(ROCKET_EVENT_HANDLER, "maxCameraLockErrorDeg")
                .get("value").getAsString());
        assertTrue("camera must never detach from the craft on any rendered frame "
                + "(worst frame divergence " + maxErr + "°)", maxErr < 20.0);

        // At-rest exactness, measured atomically on the render thread (a bot
        // reading camera and craft in two calls can straddle a tracker
        // quantisation-bleed tick and see a phantom 1-2° gap): the CURRENT
        // frame divergence must be sub-degree once input stops.
        double restErr = Double.parseDouble(bot()
                .readStaticField(ROCKET_EVENT_HANDLER, "lastCameraLockErrorDeg")
                .get("value").getAsString());
        assertTrue("at rest the camera lock must be exact on the rendered frame "
                + "(current divergence " + restErr + "°)", restErr < 1.0);
        JsonObject cam = bot().reportState();

        // And the camera attitude must CONVERGE to the SERVER craft heading
        // (replication). Convergence is asynchronous — the final resync packet
        // (sent on the turn→idle edge) can be in flight for several ticks on a
        // loaded box — so poll for it instead of a single-shot read; what we
        // pin is that the divergence DOES settle under the tracker quantum.
        double convErr = Double.MAX_VALUE;
        for (int i = 0; i < 20 && convErr >= 2.0; i++) {
            double svrYaw = parseDouble(exec("artest rocket info " + rocketId), YAW, "rotationYaw");
            double camYaw = bot().reportState().get("playerYaw").getAsDouble();
            convErr = angDiff(camYaw, svrYaw);
            if (convErr >= 2.0) bot().waitTicks(4);
        }
        assertTrue("camera yaw must converge to the server craft heading "
                + "(residual " + convErr + "°)", convErr < 2.0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void mouseSwipesPitchTheNoseAndCameraFollows() throws Exception {
        // Mouse-as-rate: repeated downward swipes (a real drag) pitch the nose
        // down tick by tick; the camera never detaches from the craft. The
        // server nose pitch must integrate the swipes through the real
        // key→packet path.
        int rocketId = mountFreshFreeFlightRocket(4900, 64, 500);
        bot().holdKey(Keyboard.KEY_R); // keep airborne so tickFreeFlight integrates pitch

        // A real mouse drag: repeated +6° swipes (above MAX_PITCH_RATE=4, so
        // each tick integrates at the rate cap and discards the excess). Loop
        // until the nose passes 20° or we run out of budget — under load the
        // bot round-trips can skip ticks, so a fixed count undershoots.
        double nosePitch = 0;
        for (int i = 0; i < 30 && nosePitch <= 20.0; i++) {
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat(),
                          st.get("playerPitch").getAsFloat() + 6f);
            bot().waitTicks(1);
            nosePitch = parseDouble(exec("artest rocket info " + rocketId),
                    FF_PITCH, "freeFlightPitch");
        }
        double maxErr = Double.parseDouble(bot()
                .readStaticField(ROCKET_EVENT_HANDLER, "maxCameraLockErrorDeg")
                .get("value").getAsString());
        bot().releaseKey(Keyboard.KEY_R);

        assertTrue("mouse drag must pitch the nose down through the real "
                + "swipe→rate→server path (got " + nosePitch + "°)", nosePitch > 20.0);
        assertTrue("camera must stay locked to the nose during the drag "
                + "(worst frame divergence " + maxErr + "°)", maxErr < 20.0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void fastMouseSwipeIsRateCappedAndExcessDiscarded() throws Exception {
        // One violent 90° yaw flick in a single tick: the craft must turn at
        // most a few times MAX_YAW_RATE (6°/tick) over the next couple of
        // ticks, and the camera must be re-pinned to the craft — NOT jump the
        // full 90° (the discarded excess is the Elite-style "mouse slip").
        int rocketId = mountFreshFreeFlightRocket(5000, 64, 500);
        bot().holdKey(Keyboard.KEY_R);
        bot().waitTicks(5);

        JsonObject before = bot().reportRidingEntity();
        double yaw0 = before.get("rotationYaw").getAsDouble();
        bot().setLook((float) yaw0 + 90f, 0f);  // one-tick flick
        bot().waitTicks(3);

        JsonObject craft = bot().reportRidingEntity();
        JsonObject cam = bot().reportState();
        bot().releaseKey(Keyboard.KEY_R);

        double turned = angDiff(craft.get("rotationYaw").getAsDouble(), yaw0);
        assertTrue("craft must have started turning after the flick (turned=" + turned + ")",
                turned > 3.0);
        assertTrue("craft turn must be rate-capped, excess discarded (turned=" + turned
                + " over ~4 ticks, cap 6°/tick)", turned < 30.0);
        assertTrue("camera must be re-pinned to the craft after the flick (cam="
                        + cam.get("playerYaw") + " craft=" + craft.get("rotationYaw") + ")",
                angDiff(cam.get("playerYaw").getAsDouble(),
                        craft.get("rotationYaw").getAsDouble()) < 7.0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    /**
     * MEASUREMENT harness (feature/true_rcs, temporary) — not a contract test.
     * Prints the live seat geometry (storage sizes, seat block, passenger offset
     * from the craft) at level flight and at a nose-down attitude so the
     * camera-in-seat transform can be tuned from real numbers instead of
     * eyeballed screenshots. Deliberately throws so the JSON lands in the report.
     */
    @Test
    public void measureSeatOffset() throws Exception {
        int rocketId = mountFreshFreeFlightRocket(5600, 64, 500);
        bot().waitTicks(30); // let the liftoff hover settle
        String serverLevel = exec("artest rocket info " + rocketId);

        // CLIENT-side truth: where the local camera actually sits vs the craft
        // the client renders. If this differs from the server dPos, the local
        // player isn't being placed by updateFreeFlightPassenger on the client.
        JsonObject cam = bot().reportState();
        JsonObject craft = bot().reportRidingEntity();
        double cDX = cam.get("playerX").getAsDouble() - craft.get("posX").getAsDouble();
        double cDY = cam.get("playerY").getAsDouble() - craft.get("posY").getAsDouble();
        double cDZ = cam.get("playerZ").getAsDouble() - craft.get("posZ").getAsDouble();

        throw new AssertionError(String.format(
                "@@SEAT@@%nSERVER=%s%nCLIENT dPos = %.3f / %.3f / %.3f  (playerY=%.3f craftY=%.3f)",
                serverLevel, cDX, cDY, cDZ,
                cam.get("playerY").getAsDouble(), craft.get("posY").getAsDouble()));
    }

    /**
     * MEASUREMENT harness (feature/true_rcs, temporary) — not a contract test.
     * Samples the CLIENT camera position tick-by-tick during a steady forward
     * cruise and reports the per-tick displacement (velocity), its jerk
     * (tick-to-tick variation = the visible shiver), the vertical bob, and the
     * client-vs-server craft divergence. Steady cruise ⇒ constant displacement;
     * any jerk / divergence swing is the motion jitter. Throws to surface data.
     */
    @Test
    public void measureMotionJitter() throws Exception {
        int rocketId = mountFreshFreeFlightRocket(5700, 64, 500);
        bot().waitTicks(30); // settle hover
        // Steady forward cruise: FA ramps the setpoint to max over ~3 s.
        exec("artest rocket free-flight-input " + rocketId + " 1 0 0 0 0");
        bot().waitTicks(80); // reach steady-state cruise before sampling

        int N = 40;
        double[] px = new double[N], py = new double[N], pz = new double[N];
        double[] crx = new double[N], svx = new double[N];
        for (int i = 0; i < N; i++) {
            JsonObject cam = bot().reportState();
            JsonObject craft = bot().reportRidingEntity();
            px[i] = cam.get("playerX").getAsDouble();
            py[i] = cam.get("playerY").getAsDouble();
            pz[i] = cam.get("playerZ").getAsDouble();
            crx[i] = craft.get("posX").getAsDouble();
            svx[i] = parseDouble(exec("artest rocket info " + rocketId), POS_X, "posX");
            bot().waitTicks(1);
        }
        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");

        StringBuilder sb = new StringBuilder("@@JITTER@@\n");
        double prevD = Double.NaN, maxJerk = 0, sumD = 0, minD = 1e9, maxD = -1e9;
        int cnt = 0;
        for (int i = 1; i < N; i++) {
            double dx = px[i] - px[i - 1], dz = pz[i] - pz[i - 1];
            double d = Math.hypot(dx, dz);           // per-tick camera displacement
            double bob = py[i] - py[i - 1];           // vertical wobble
            double div = crx[i] - svx[i];             // client-vs-server craft X gap
            if (!Double.isNaN(prevD)) {
                double jerk = Math.abs(d - prevD);
                if (jerk > maxJerk) maxJerk = jerk;
            }
            prevD = d; sumD += d; cnt++;
            minD = Math.min(minD, d); maxD = Math.max(maxD, d);
            sb.append(String.format("t%02d dSpd=%.4f bob=%+.4f cli-svrX=%+.4f%n", i, d, bob, div));
        }
        sb.append(String.format("mean=%.4f min=%.4f max=%.4f spread=%.4f maxJerk=%.4f%n",
                sumD / cnt, minD, maxD, maxD - minD, maxJerk));
        throw new AssertionError(sb.toString());
    }

    /**
     * MEASUREMENT harness (feature/true_rcs, temporary) — not a contract test.
     * Simulates a STEADY mouse turn (the reported jitter case) by nudging the
     * camera-locked look a fixed amount every tick, and samples tick-by-tick:
     * client craft yaw advance + jerk (uneven turn = rotation shiver), camera
     * vs craft lock error, client-vs-server yaw divergence, and the camera
     * position sweep + jerk (the seat sits off the spin axis, so yaw noise is
     * levered into position wobble). Throws to surface the data.
     */
    @Test
    public void measureTurnJitter() throws Exception {
        int rocketId = mountFreshFreeFlightRocket(5800, 64, 500);
        bot().waitTicks(30); // settle hover

        int N = 40;
        double[] cyaw = new double[N], syaw = new double[N], camyaw = new double[N];
        double[] px = new double[N], pz = new double[N];
        for (int i = 0; i < N; i++) {
            JsonObject s0 = bot().reportState();
            // Steady "mouse" turn through the real camera-lock path: +5°/tick.
            bot().setLook(s0.get("playerYaw").getAsFloat() + 5f, s0.get("playerPitch").getAsFloat());
            bot().waitTicks(1);
            JsonObject cam = bot().reportState();
            JsonObject craft = bot().reportRidingEntity();
            camyaw[i] = cam.get("playerYaw").getAsDouble();
            cyaw[i]   = craft.get("rotationYaw").getAsDouble();
            px[i]     = cam.get("playerX").getAsDouble();
            pz[i]     = cam.get("playerZ").getAsDouble();
            syaw[i]   = parseDouble(exec("artest rocket info " + rocketId), YAW, "rotationYaw");
        }
        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");

        StringBuilder sb = new StringBuilder("@@TURNJIT@@\n");
        double prevStep = Double.NaN, maxYawJerk = 0;
        double prevPos = Double.NaN, maxPosJerk = 0;
        for (int i = 1; i < N; i++) {
            double yawStep = angDiff(cyaw[i], cyaw[i - 1]);      // per-tick craft turn
            double lockErr = angDiff(camyaw[i], cyaw[i]);        // camera vs craft
            double divYaw  = angDiff(cyaw[i], syaw[i]);          // client vs server
            double posStep = Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
            if (!Double.isNaN(prevStep)) maxYawJerk = Math.max(maxYawJerk, Math.abs(yawStep - prevStep));
            if (!Double.isNaN(prevPos))  maxPosJerk = Math.max(maxPosJerk, Math.abs(posStep - prevPos));
            prevStep = yawStep; prevPos = posStep;
            sb.append(String.format("t%02d yawStep=%.3f lockErr=%.3f cli-svrYaw=%+.3f posStep=%.4f%n",
                    i, yawStep, lockErr, divYaw, posStep));
        }
        sb.append(String.format("maxYawJerk=%.3f  maxPosJerk=%.4f%n", maxYawJerk, maxPosJerk));
        throw new AssertionError(sb.toString());
    }
}
