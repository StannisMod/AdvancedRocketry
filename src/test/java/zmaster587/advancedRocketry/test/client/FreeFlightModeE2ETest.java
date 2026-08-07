package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
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
 * <p>The client-side keypress&rarr;packet&rarr;server input wiring is unit-tested
 * via {@code FreeFlightInputTest} (ByteBuf round-trip with re-clamping) and
 * pinned indirectly here: the same {@code FREE_FLIGHT_INPUT} packet that
 * keybinds emit on real key events is what {@code free-flight-input} probe
 * dispatches server-side. A regression in the wire format would surface in
 * one of those two layers.
 *
 * Gated by {@code -Dforge.test.client=true}; skipped on headless CI.
 *
 * <h2>Shared harness</h2>
 *
 * <p>This class is the single largest item in the client tier: 27 scenarios, each of which used to
 * boot its own dedicated-server JVM AND its own Minecraft client — about 27 x 110 s in ONE gradle
 * fork, which made it the wall-clock FLOOR of the whole tier while the other seven forks idled.
 * It now runs on one shared harness.</p>
 *
 * <p>Two things made the migration safe rather than merely cheap:</p>
 * <ul>
 *   <li><b>The lane is not moved.</b> These scenarios build on GROUND at y=64, so they inherit
 *       whatever the fixed seed generated. The x=3000.. / z=500 strip is what every green run of
 *       this file was taken on; relocating it onto the shared-harness default would have been a
 *       change of subject dressed as a refactor. See {@link #lane()}.</li>
 *   <li><b>The rocket is found by PLOT, not by "the last one in the world".</b> The old
 *       {@code buildAndAssemble} read {@code artest rocket list 0} and took the highest id it saw.
 *       That is safe when the world holds exactly one rocket and silently wrong when it holds 27 —
 *       precisely the object-answers-for-another-object failure a shared world creates. The lookup
 *       now filters on {@link Plot#contains}.</li>
 * </ul>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class FreeFlightModeE2ETest extends AbstractSharedClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_ID = Pattern.compile("\"id\":(-?\\d+)");
    /** One {@code rocket list} entry: id plus the x/y/z it stands at. */
    private static final Pattern ROCKET_ENTRY = Pattern.compile(
            "\\{\"id\":(-?\\d+),\"uuid\":\"[^\"]*\",\"dim\":-?\\d+,"
                    + "\"pos\":\\[(-?[0-9.E\\-]+),(-?[0-9.E\\-]+),(-?[0-9.E\\-]+)]}");
    private static final Pattern MOTION_Y = Pattern.compile("\"motionY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern YAW   = Pattern.compile("\"rotationYaw\":(-?[0-9.E\\-]+)");
    private static final Pattern FF_PITCH = Pattern.compile("\"freeFlightPitch\":(-?[0-9.E\\-]+)");
    private static final Pattern FF_ROLL  = Pattern.compile("\"freeFlightRoll\":(-?[0-9.E\\-]+)");
    private static final Pattern FUEL_PRIMARY_AMOUNT =
            Pattern.compile("\"primaryFuelType\":\"([^\"]+)\".*?\"\\1\":\\{\"amount\":(-?\\d+)");

    /** Ground level for every fixture here; the pad is built on terrain, not in air. */
    private static final int BASE_Y = 64;

    @Override
    protected String subsystem() {
        return "free-flight";
    }

    /**
     * The strip this file's scenarios have always flown on: x from 3000, z=500, 100 apart. Ground
     * level, therefore terrain-dependent, therefore NOT relocatable on the strength of a refactor.
     * 27 scenarios reach x=5600; the pre-migration file already used up to 5100 on this line.
     */
    @Override
    protected Plot.Lane lane() {
        return new Plot.Lane(3000, 500, 100);
    }

    private int baseX() {
        return plot().originX;
    }

    private int baseZ() {
        return plot().originZ;
    }

    /** Stand the bot above and beside its own plot's build site, clear of the pad. */
    private void tpNearBuildSite() throws Exception {
        exec("tp @a " + (baseX() + 10) + " " + (BASE_Y + 15) + " " + (baseZ() + 10) + " 0 0");
        bot().waitTicks(10);
    }

    /** Stand the bot on its own plot's pad, within {@code mount-entity} range of the rocket. */
    private void tpOntoPad() throws Exception {
        exec("tp @a " + (baseX() + 0.5) + " " + (BASE_Y + 1) + " " + (baseZ() + 0.5) + " 0 0");
        bot().waitTicks(5);
    }

    private int buildAndAssemble() throws Exception {
        final int baseX = baseX();
        final int baseY = BASE_Y;
        final int baseZ = baseZ();
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

        return rocketIdInThisPlot();
    }

    /**
     * The id of the rocket standing in THIS scenario's plot.
     *
     * <p>{@code artest rocket list 0} is a GLOBAL query: on a shared harness the world holds every
     * earlier scenario's rocket too. The pre-migration code took the highest id it saw, which is
     * correct only while exactly one rocket exists. Each entry carries its {@code pos}, so the
     * answer is narrowed to the plot that built it — and an ambiguous answer is an ARRANGEMENT
     * failure naming what it saw, never a silently-picked candidate.</p>
     */
    private int rocketIdInThisPlot() throws Exception {
        String list = exec("artest rocket list 0");
        Matcher entry = ROCKET_ENTRY.matcher(list);
        int found = -1;
        int matches = 0;
        StringBuilder seen = new StringBuilder();
        while (entry.find()) {
            int id = Integer.parseInt(entry.group(1));
            double px = Double.parseDouble(entry.group(2));
            double pz = Double.parseDouble(entry.group(4));
            seen.append(" id=").append(id).append('@').append(px).append(',').append(pz);
            if (plot().contains(px, pz)) {
                found = id;
                matches++;
            }
        }
        scenario().requireArranged("exactly one rocket must stand in " + plot()
                + " after assemble, found " + matches + " —" + seen, matches == 1);
        scenario().record("rocketId", found);
        return found;
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
        tpNearBuildSite();

        int rocketId = buildAndAssemble();

        // Move bot adjacent to the rocket so mount-entity has line-of-sight.
        tpOntoPad();

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
        tpNearBuildSite();

        int rocketId = buildAndAssemble();
        tpOntoPad();

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

        // Let the REAL server tick loop run — onUpdate->tickFreeFlight runs
        // every server tick because the rocket is in FF + isInFlight.
        bot().waitTicks(20);

        String infoAfter = exec("artest rocket info " + rocketId);
        double myAfter = parseDouble(infoAfter, MOTION_Y, "motionY");

        // After 20 ticks of commanded vertical-up thrust motionY must be net
        // UPWARD relative to the start — thrust ≫ gravity for the simple fixture.
        // Asserting strict increase (not merely "changed") is deliberate: a
        // mere "changed" check passes on gravity alone even if vertical thrust
        // is completely broken, so it would not actually pin the up-thrust
        // contract. A frozen rocket (tick loop not running the FF branch) also
        // fails this.
        assertTrue(
                "commanded vertical-up thrust must raise motionY across 20 server ticks "
                        + "(was " + myBefore + ", now " + myAfter + ")",
                myAfter > myBefore);

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
        tpNearBuildSite();

        int rocketId = buildAndAssemble();

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

    private int mountFreshFreeFlightRocket() throws Exception {
        final int baseX = baseX(), baseY = BASE_Y, baseZ = baseZ();
        exec("tp @a " + (baseX + 10) + " " + (baseY + 15) + " " + (baseZ + 10) + " 0 0");
        bot().waitTicks(10);
        int rocketId = buildAndAssemble();
        exec("tp @a " + (baseX + 0.5) + " " + (baseY + 1) + " " + (baseZ + 0.5) + " 0 0");
        bot().waitTicks(5);
        exec("artest player mount-entity " + rocketId);
        exec("artest rocket set-flight-mode " + rocketId + " FREE_FLIGHT");
        exec("artest rocket start-free-flight " + rocketId);
        // The v1 takeoff is a decaying kick + grace window; on a slow/contended
        // harness the bot round-trips can outlast it and the rocket re-lands
        // before the test's input arrives, failing on "never moved" instead of
        // the contract under test. Confirm we're airborne, retrying the start —
        // same pattern as the assemble retry above. (The
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
        int rocketId = mountFreshFreeFlightRocket();

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
        // Forward throttle at yaw=0 -> +Z. Vertical kept on so the rocket stays
        // airborne (doesn't auto-land mid-test).
        int rocketId = mountFreshFreeFlightRocket();

        String before = exec("artest rocket info " + rocketId);
        final double xb = parseDouble(before, POS_X, "posX");
        final double zb = parseDouble(before, POS_Z, "posZ");
        exec("artest rocket free-flight-input " + rocketId + " 1 1 0 0 0");
        // Server-driven displacement: the throttle reaches the flight computer a
        // tick or two after the probe returns, and under harness load the
        // takeoff-kick grace can delay the horizontal ramp past a fixed window.
        // Poll (load-scaled) until it has actually travelled, then measure — an
        // idle run still exits inside the old 30-tick budget.
        ClientPoll.Result<String> moved = ClientPoll.until(
                bot()::waitTicks,
                () -> exec("artest rocket info " + rocketId),
                info -> {
                    double dx = parseDouble(info, POS_X, "posX") - xb;
                    double dz = parseDouble(info, POS_Z, "posZ") - zb;
                    return Math.sqrt(dx * dx + dz * dz) > 1.0;
                },
                6, 10);
        double xa = parseDouble(moved.value, POS_X, "posX");
        double za = parseDouble(moved.value, POS_Z, "posZ");

        double horiz = Math.sqrt((xa - xb) * (xa - xb) + (za - zb) * (za - zb));
        assertTrue("forward thrust must move the rocket horizontally "
                        + "(horiz=" + horiz + "; " + moved + ")", horiz > 1.0);
        assertTrue("forward at yaw=0 must be predominantly +Z, got dz=" + (za - zb),
                (za - zb) > 0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void yawInputRotatesHeading() throws Exception {
        // Yaw input must steer the heading through the live loop. Vertical kept
        // on to stay airborne while yawing (yaw rotates regardless of thrust).
        int rocketId = mountFreshFreeFlightRocket();

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
        // (flightVerticalUp) drives KeyBindings.onClientTick -> a real
        // FREE_FLIGHT_INPUT packet -> server tickFreeFlight. We assert BOTH halves
        // that the probe tests could never see:
        //   1) the server rocket actually climbs (real packet path delivers thrust),
        //   2) the CLIENT-rendered rocket tracks the server (no poscorrection lag).
        int rocketId = mountFreshFreeFlightRocket();

        // Hold the real climb key. No artest free-flight-input here on purpose.
        bot().holdKey(Keyboard.KEY_R);

        double svrYBefore = parseDouble(exec("artest rocket info " + rocketId), POS_Y, "posY");
        // Event-gated: hold the climb key until the server rocket has actually climbed (load-scaled
        // ceiling + early exit; a fixed 40-tick budget can under-climb a frame-starved client under load).
        ClientPoll.until(bot()::waitTicks,
                () -> parseDouble(exec("artest rocket info " + rocketId), POS_Y, "posY"),
                y -> y - svrYBefore > 2.0, 4, 10);
        String svrInfo = exec("artest rocket info " + rocketId);
        double svrYAfter = parseDouble(svrInfo, POS_Y, "posY");

        JsonObject ride = bot().reportRidingEntity();
        assertTrue("client must still be riding the rocket: " + ride,
                ride.has("riding") && ride.get("riding").getAsBoolean());
        double cliY = ride.get("posY").getAsDouble();

        bot().releaseKey(Keyboard.KEY_R);

        // 1) Real key -> real packet -> server physics.
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
        int rocketId = mountFreshFreeFlightRocket();
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
        int rocketId = mountFreshFreeFlightRocket();
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
        tpNearBuildSite();
        int rocketId = buildAndAssemble();
        tpOntoPad();
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
        int rocketId = mountFreshFreeFlightRocket();

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
        tpNearBuildSite();
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
        int rocketId = mountFreshFreeFlightRocket();
        assertEquals("precondition: no GUI open while piloting", "", currentScreen());

        double xBefore = parseDouble(exec("artest rocket info " + rocketId), POS_X, "posX");

        // Hold vertical-up (R, keeps it airborne so the FF tick keeps running) AND
        // the inventory key (E). On foot E opens the inventory; here it must
        // strafe. E = strafe right, which after the polarity fix commands -X at
        // yaw 0 (world +X renders on the pilot's left out the nose).
        bot().holdKey(Keyboard.KEY_R);
        bot().holdKey(KEY_INVENTORY);
        // Event-gated strafe (load-scaled ceiling + early exit): a fixed 25-tick budget can under-strafe
        // a frame-starved client under concurrent-fork load and red a healthy strafe.
        ClientPoll.until(bot()::waitTicks,
                () -> parseDouble(exec("artest rocket info " + rocketId), POS_X, "posX"),
                x -> x - xBefore < -1.0, 5, 5);

        String screenDuring = currentScreen();
        String info = exec("artest rocket info " + rocketId);
        double xAfter = parseDouble(info, POS_X, "posX");

        bot().releaseKey(KEY_INVENTORY);
        bot().releaseKey(Keyboard.KEY_R);

        assertEquals("inventory key must NOT open the inventory while piloting "
                + "(would also freeze steering): " + screenDuring, "", screenDuring);
        assertTrue("inventory key must instead strafe the craft (-X at yaw 0) while piloting "
                        + "(xBefore=" + xBefore + " xAfter=" + xAfter + ")",
                xAfter - xBefore < -1.0);
        assertTrue("rocket must stay in flight (override must not have frozen control): " + info,
                info.contains("\"isInFlight\":true"));

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void strafeLeftKeyMovesPositiveX() throws Exception {
        // Q (strafe left) -> +X at yaw 0 after the polarity fix: with the camera
        // looking out the nose, world +X renders on the pilot's LEFT, so the
        // strafe-left key must push the craft toward +X to feel correct (the raw
        // body-right mapping felt inverted in playtest). E is the mirror.
        int rocketId = mountFreshFreeFlightRocket();
        double xBefore = parseDouble(exec("artest rocket info " + rocketId), POS_X, "posX");

        bot().holdKey(Keyboard.KEY_R);          // stay airborne
        bot().holdKey(Keyboard.KEY_Q);          // strafe left
        // Event-gated strafe (load-scaled ceiling + early exit): a fixed 25-tick budget can under-strafe
        // a frame-starved client under concurrent-fork load and red a healthy strafe.
        ClientPoll.until(bot()::waitTicks,
                () -> parseDouble(exec("artest rocket info " + rocketId), POS_X, "posX"),
                x -> x - xBefore > 1.0, 5, 5);
        double xAfter = parseDouble(exec("artest rocket info " + rocketId), POS_X, "posX");
        bot().releaseKey(Keyboard.KEY_Q);
        bot().releaseKey(Keyboard.KEY_R);

        assertTrue("Q must strafe +X at yaw 0 (xBefore=" + xBefore + " xAfter=" + xAfter + ")",
                xAfter - xBefore > 1.0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void verticalKeysClimbAndDescend() throws Exception {
        // R climbs (real altitude gain); F is the opposite vertical thrust, so it
        // must drive the vertical velocity down. We measure F by motionY (robust
        // to the climb's accumulated upward inertia, which a position check is not).
        int rocketId = mountFreshFreeFlightRocket();

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
        // Event-gated: hold downward thrust until it has reduced the vertical velocity (load-scaled
        // ceiling + early exit; a fixed 10-tick budget can under-brake a frame-starved client under load).
        ClientPoll.Result<Double> down = ClientPoll.until(bot()::waitTicks,
                () -> parseDouble(exec("artest rocket info " + rocketId), MOTION_Y, "motionY"),
                my -> my < myUp - 0.05, 5, 2);
        double myDown = down.value;
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
        int rocketId = mountFreshFreeFlightRocket();

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
        // X with Flight Assist on: zero the velocity setpoint —
        // the craft eases to a stop AND holds altitude. Build a climb via the
        // real R key first, then hold the real X.
        int rocketId = mountFreshFreeFlightRocket();

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
        // Event-gated: hold the cut until it has braked the climb into a hover (load-scaled ceiling +
        // early exit; a fixed 40-tick budget can leave the craft mid-brake under concurrent-fork load).
        ClientPoll.until(bot()::waitTicks,
                () -> parseDouble(exec("artest rocket info " + rocketId), MOTION_Y, "motionY"),
                my -> Math.abs(my) < 0.05, 5, 8);
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

    // ===== Engine start =====================================
    //
    // The REAL path: hold the actual jump key (Space) on the client for 3 s;
    // KeyBindings.onClientTick accumulates the hold and sends ENGINE_START;
    // the server validates and starts the hover. No probe shortcut here.

    /** Build + mount + flip to FREE_FLIGHT, but do NOT start the engines. */
    private int mountColdFreeFlightRocket() throws Exception {
        final int baseX = baseX(), baseY = BASE_Y, baseZ = baseZ();
        exec("tp @a " + (baseX + 10) + " " + (baseY + 15) + " " + (baseZ + 10) + " 0 0");
        bot().waitTicks(10);
        int rocketId = buildAndAssemble();
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
        int rocketId = mountColdFreeFlightRocket();
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
        int rocketId = mountColdFreeFlightRocket();

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
        int rocketId = mountFreshFreeFlightRocket();
        bot().waitTicks(30); // settle into the liftoff hover

        bot().holdKey(Keyboard.KEY_F);
        // Poll for landing rather than a fixed wait (descent into ground contact + landed-event latency
        // varies with load): event-gated with a load-scaled ceiling + early exit.
        ClientPoll.Result<Boolean> landing = ClientPoll.until(bot()::waitTicks,
                () -> exec("artest rocket info " + rocketId).contains("\"isInFlight\":false"),
                b -> b, 5, 40);
        bot().releaseKey(Keyboard.KEY_F);
        assertTrue("descending into the ground must shut the engines off", landing.satisfied);

        bot().waitTicks(5);
        String hud = bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                .get("value").getAsString();
        assertTrue("HUD must reflect the shutdown (stopped flash or ENGINES OFF): " + hud,
                hud.contains("Engines stopped") || hud.contains("ENGINES OFF"));

        exec("artest player dismount");
    }

    // ===== HUD indication ===============================

    private static final Pattern HUD_VRT =
            Pattern.compile("VRT ([+-][0-9.]+)/([+-][0-9.]+)");

    @Test
    public void hudVectorLineTracksSetpointAndVelocity() throws Exception {
        // The per-axis vector readout: holding R ramps the VRT setpoint and the
        // actual velocity follows; X zeroes the setpoint back. Read from the
        // REAL rendered HUD text.
        int rocketId = mountFreshFreeFlightRocket();

        bot().holdKey(Keyboard.KEY_R);
        // Event-gated: hold R until the rendered HUD VRT setpoint has ramped and the actual velocity is
        // chasing it (load-scaled ceiling + early exit; a fixed 25-tick budget can under-ramp under load).
        ClientPoll.Result<String> climbHud = ClientPoll.until(bot()::waitTicks,
                () -> bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                        .get("value").getAsString(),
                h -> {
                    Matcher mm = HUD_VRT.matcher(h);
                    return mm.find() && Double.parseDouble(mm.group(1)) > 0.4
                            && Double.parseDouble(mm.group(2)) > 0.1;
                }, 5, 5);
        String hudClimb = climbHud.value;
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
        // Event-gated: hold cut until the rendered VRT setpoint has returned to zero (load-scaled ceiling
        // + early exit; a fixed 15-tick budget can leave the setpoint mid-decay under load).
        ClientPoll.Result<String> cutHud = ClientPoll.until(bot()::waitTicks,
                () -> bot().readStaticField(ROCKET_EVENT_HANDLER, "lastFreeFlightHud")
                        .get("value").getAsString(),
                h -> {
                    Matcher mm = HUD_VRT.matcher(h);
                    return mm.find() && Math.abs(Double.parseDouble(mm.group(1))) <= 0.01;
                }, 5, 3);
        String hudCut = cutHud.value;
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
        int rocketId = mountFreshFreeFlightRocket();
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

    // ===== Camera-nose lock + mouse-as-rate =================
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
        int rocketId = mountFreshFreeFlightRocket();

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
        // (sent on the turn->idle edge) can be in flight for several ticks on a
        // loaded box — so poll for it instead of a single-shot read; what we
        // pin is that the divergence DOES settle under the tracker quantum.
        // Event-gated: the resync convergence is async (the final packet can be in flight for several
        // ticks on a loaded box); poll until the camera yaw has converged to the server heading
        // (load-scaled ceiling + early exit) instead of a fixed 20-iteration budget.
        ClientPoll.Result<Double> conv = ClientPoll.until(bot()::waitTicks,
                () -> {
                    double svrYaw = parseDouble(exec("artest rocket info " + rocketId), YAW, "rotationYaw");
                    double camYaw = bot().reportState().get("playerYaw").getAsDouble();
                    return angDiff(camYaw, svrYaw);
                },
                e -> e < 2.0, 4, 20);
        double convErr = conv.value;
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
        // key->packet path.
        int rocketId = mountFreshFreeFlightRocket();
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
                + "swipe->rate->server path (got " + nosePitch + "°)", nosePitch > 20.0);
        assertTrue("camera must stay locked to the nose during the drag "
                + "(worst frame divergence " + maxErr + "°)", maxErr < 20.0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    @Test
    public void mouseHorizontalBanksTheCraftNotItsHeading() throws Exception {
        // Deflection scheme: the mouse is a virtual cursor —
        // HORIZONTAL drives ROLL (bank), not yaw (yaw is A/D only). A real
        // rightward mouse drag must bank the craft (client camera roll grows) while
        // the heading (client yaw) stays put. Supersedes the pre-deflection
        // "fast mouse swipe yaws the craft" test, whose premise no longer holds.
        int rocketId = mountFreshFreeFlightRocket();
        bot().holdKey(Keyboard.KEY_R);   // climb clear of the pad while banking
        bot().waitTicks(5);

        double yaw0 = bot().reportRidingEntity().get("rotationYaw").getAsDouble();
        // A real rightward mouse drag: repeated +8° horizontal swipes saturate the
        // absolute roll cursor, which then holds a steady bank rate.
        for (int i = 0; i < 8; i++) {
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat() + 8f, st.get("playerPitch").getAsFloat());
            bot().waitTicks(1);
        }
        // Event-gated: let the held bank integrate until the client camera roll has grown (load-scaled
        // ceiling + early exit; a fixed 15-tick budget can under-integrate under concurrent-fork load).
        ClientPoll.Result<Double> bank = ClientPoll.until(bot()::waitTicks,
                () -> readClientDouble("ffClientCamRoll"),
                r -> Math.abs(r) > 15.0, 3, 5);
        double camRoll = bank.value;
        double yaw1 = bot().reportRidingEntity().get("rotationYaw").getAsDouble();
        bot().releaseKey(Keyboard.KEY_R);

        assertTrue("mouse-horizontal must BANK the craft — client camera roll must "
                + "grow (roll=" + camRoll + "°)", Math.abs(camRoll) > 15.0);
        assertTrue("mouse-horizontal must NOT change the heading — client yaw drifted "
                + angDiff(yaw1, yaw0) + "° (roll must not couple into yaw)",
                angDiff(yaw1, yaw0) < 12.0);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    /**
     * The headline Phase-7 perception contract: a sustained pitch input drives the
     * nose all the way OVER THE TOP (a loop), which the world-frame Euler ±85°
     * clamp made impossible. Witnessed on the CLIENT render thread: the nose Z the
     * camera actually pointed goes negative (points backwards) — a clamped attitude
     * can never do that (forward.z ≳ cos 85° ≈ 0.09). Also proves the client
     * survives rendering an inverted / looping craft.
     */
    @Test
    public void sustainedPitchLoopsPastVerticalWithNoClamp() throws Exception {
        int rocketId = mountFreshFreeFlightRocket();
        // Climb well clear of the pad, then CUT to a gravity-cancelled hover: with
        // a zero velocity setpoint FA holds position regardless of attitude, so the
        // craft can loop in place without body-up thrust flying it into the ground.
        bot().holdKey(Keyboard.KEY_R);
        bot().waitTicks(30);
        bot().releaseKey(Keyboard.KEY_R);
        bot().holdKey(Keyboard.KEY_X);   // cut -> hover (attitude-independent)
        bot().waitTicks(5);

        // Real downward mouse drag: saturate the absolute pitch cursor, which then
        // holds full pitch rate and carries the nose past vertical and over.
        for (int i = 0; i < 8; i++) {
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat(), st.get("playerPitch").getAsFloat() + 8f);
            bot().waitTicks(1);
        }
        // Event-gated: the held pitch cursor loops the nose over the top; poll the client min-forward-Z
        // accumulator until it has gone negative (load-scaled ceiling + early exit) instead of a fixed
        // 60-tick budget that can under-integrate under concurrent-fork load.
        ClientPoll.Result<Double> loop = ClientPoll.until(bot()::waitTicks,
                () -> readClientDouble("ffClientMinForwardZ"),
                z -> z < -0.5, 5, 12);
        double minFwdZ = loop.value;
        boolean stillRiding = bot().reportRidingEntity() != null;
        bot().releaseKey(Keyboard.KEY_X);

        assertTrue("the nose must loop past vertical — client min forward.z must go "
                + "negative (was " + minFwdZ + "; a ±85° clamp keeps it ≳ 0.09)",
                minFwdZ < -0.5);
        assertTrue("client must survive rendering the looping/inverted craft", stillRiding);

        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0");
        exec("artest player dismount");
    }

    /** Read a client-side double static from {@link #ROCKET_EVENT_HANDLER}. */
    private double readClientDouble(String field) throws Exception {
        return Double.parseDouble(
                bot().readStaticField(ROCKET_EVENT_HANDLER, field).get("value").getAsString());
    }

    /**
     * Roll DOF smoke: a commanded bank integrates server-side AND the real
     * client renders through it (the camera-roll mixin runs every frame) without
     * crashing. Pins the roll channel end-to-end; camera-bank direction/feel is
     * a manual-playtest perception check.
     */
    @Test
    public void rollChannelIntegratesAndClientRendersWithoutCrash() throws Exception {
        int rocketId = mountFreshFreeFlightRocket();
        bot().waitTicks(20);
        double roll0 = parseDouble(exec("artest rocket info " + rocketId), FF_ROLL, "freeFlightRoll");

        // Command a steady bank-right: probe args are
        // id fwd vert yaw pitch brake cut strafe roll -> roll = last (=+1).
        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0 0 0 1");
        bot().waitTicks(10);
        double roll1 = parseDouble(exec("artest rocket info " + rocketId), FF_ROLL, "freeFlightRoll");

        // Stop and let the client keep rendering the banked craft a moment.
        exec("artest rocket free-flight-input " + rocketId + " 0 0 0 0 0 0 0 0");
        bot().waitTicks(10);
        boolean stillRiding = bot().reportState().get("screen").getAsString() != null
                && bot().reportRidingEntity() != null;

        exec("artest player dismount");

        assertTrue("commanded roll must integrate server-side (roll0=" + roll0
                + " roll1=" + roll1 + ")", angDiff(roll1, roll0) > 3.0);
        assertTrue("client must survive rendering the banked craft (camera-roll mixin)",
                stillRiding);
    }
}
