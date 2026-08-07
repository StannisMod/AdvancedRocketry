package zmaster587.advancedRocketry.test.client;

import org.junit.Assume;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * The capture/release contract of the ship-frame crew - what makes a body ABOARD, what keeps it
 * there, and what leaves a bystander alone - pinned against a REAL CLIENT PLAYER — the subject that
 * broke in the inverted-boarding playtest. Two boundary behaviours that the world-AABB containment
 * gate got wrong:
 *
 * <ul>
 *   <li><b>Jumping on the TOP deck keeps the capture.</b> The hull's top surface sits at the
 *       ship's world-AABB ceiling; a jump apex from there crossed the old grown-box gate
 *       (`leftShipBox`) and the capture died MID-AIR — vanilla, blind to the subspace deck, then
 *       tunnelled the body through the whole ship. The stay region is measured in SUBSPACE with a
 *       real margin, so a jump must ride out and land back on the deck, still captured.</li>
 *   <li><b>A player walking on world TERRAIN near a ship is never captured.</b> A ground
 *       position mapped through a parked ship's transform can alias onto a subspace block, and the
 *       old first-contact gate then captured a walker who stood on plain ground beside the hull
 *       (the playtest's "entered the ship transform at a random place"). Terra firma always keeps
 *       world-frame movement.</li>
 * </ul>
 *
 * <p>Gated on real VS — run with {@code -PwithVS}. Each test builds its own ship at its own base so
 * a ship one leaves behind cannot poison the next.</p>
 *
 * <h2>One client for all eleven scenarios</h2>
 *
 * <p>Measured 2026-08-07: these eleven cost <b>19.3 minutes across eleven client boots</b> — 105 s
 * each, nearly all of it startup — and with {@code VSDeckCaptureAndDismountE2ETest} they were the
 * ship tier's wall-clock floor. The bases below (5220…6420, 100 blocks apart) are unchanged: they
 * are a plot allocator written by hand, and each is the ground its green runs were taken on.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSCrewCaptureContractE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-crew-capture";
    }

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-deck";

    // ---- Staying aboard: a jump from the top deck must not release the capture ------------------

    @Test
    public void jumpingOnTheTopDeckKeepsTheCaptureAndLandsBackOnIt() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 5220, by = 64, bz = 5220;

        // The subject is on the HARD side of the geometry: the fixture's walkable deck is the hull's
        // TOP surface, so the player's feet stand at the ship's world-AABB ceiling and a vanilla jump
        // apex (~1.25) pokes above the old grown-box gate. On the old gate this exact jump released
        // the capture mid-air; the contract is that it must not.
        double[] ship = buildShip(bx, by, bz);
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("the client player must be captured on the deck before the jump: "
                + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"verdict\":true"));
        double deckY = bot().reportState().get("playerY").getAsDouble();

        // A REAL jump: the space key on the real client. Sample the capture through the whole arc -
        // the failure mode is a release at the apex, which a single after-the-fact read can miss if
        // a fresh first-contact re-captured on landing.
        // Smoothness diagnostics (print-only): frames whose interpolated camera position repeats
        // name a dead prev->pos interpolation; PosLook applies name the server echo as its writer.
        long frames0 = (long) clientDouble(SHIP_CAMERA_CLASS, "aboardFramesRendered");
        long same0 = (long) clientDouble(SHIP_CAMERA_CLASS, "aboardFramesSamePos");
        long posLook0 = (long) clientDouble(SHIP_CAMERA_CLASS, "posLookApplies");
        long resolved0 = (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks");
        long declined0 = (long) clientDouble(SHIP_FRAME_TRAVEL, "declinedTicks");
        long jdrops0 = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops");
        int tracked = 0, samples = 0;
        double apex = deckY;
        StringBuilder trace = new StringBuilder();
        bot().holdKey(Keyboard.KEY_SPACE);
        try {
            for (int i = 0; i < 10; i++) {
                bot().waitTicks(2);
                samples++;
                String cap = exec("artest vs deck-capture");
                boolean t = cap.contains("\"alreadyTracked\":true");
                if (t) tracked++;
                double y = bot().reportState().get("playerY").getAsDouble();
                apex = Math.max(apex, y);
                trace.append(String.format("[%d y=%.2f tracked=%b] ", i, y, t));
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_SPACE);
        }
        bot().waitTicks(40); // land and settle
        String capture = exec("artest vs deck-capture");
        double settledY = bot().reportState().get("playerY").getAsDouble();
        long framesD = (long) clientDouble(SHIP_CAMERA_CLASS, "aboardFramesRendered") - frames0;
        long sameD = (long) clientDouble(SHIP_CAMERA_CLASS, "aboardFramesSamePos") - same0;
        long posLookD = (long) clientDouble(SHIP_CAMERA_CLASS, "posLookApplies") - posLook0;
        System.out.println("[crewcap] jump smoothness frames=" + framesD + " samePos=" + sameD
                + " (" + (framesD > 0 ? (100L * sameD / framesD) : -1) + "%) posLookApplies="
                + posLookD
                + " resolvedDelta=" + ((long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks") - resolved0)
                + " declinedDelta=" + ((long) clientDouble(SHIP_FRAME_TRAVEL, "declinedTicks") - declined0)
                + " dropsDelta=" + ((long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops") - jdrops0)
                + " windowTicks=60");
        System.out.println("[crewcap] jump deckY=" + deckY + " apex=" + apex + " settledY=" + settledY
                + " tracked=" + tracked + "/" + samples + " :: " + trace);
        System.out.println("[crewcap] jump capture=" + capture);

        assertTrue("the jump must actually leave the deck (apex=" + apex + " deckY=" + deckY + ")",
                apex - deckY > 0.5);
        assertTrue("the capture must survive the whole jump arc, not release mid-air (" + tracked + "/"
                + samples + " samples tracked): " + trace, tracked == samples);
        assertTrue("after the jump the player must be resolved back on the deck: " + capture,
                capture.contains("\"verdict\":true"));
        assertTrue("the player must land back ON the deck, not through it: deckY=" + deckY
                + " settledY=" + settledY, Math.abs(settledY - deckY) < 1.5);
    }

    // ---- Boarding vs bystanders: terra firma near a ship never captures -------------------------

    @Test
    public void walkingOnTheGroundBesideAParkedShipNeverEntersItsFrame() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 5320, by = 64, bz = 5320;

        double[] ship = buildShip(bx, by, bz);

        // Tilt the parked ship: an axis-aligned world box around a rotated hull over-includes a large
        // ground area, and a tilted transform is what aliased a GROUND position onto a subspace block
        // in the playtest (a walker was captured into a 44.7-degree ship's frame). This is the hard
        // side of the axis; an upright ship rarely aliases.
        double h = Math.toRadians(45.0) / 2.0;
        assertTrue("attitude hold must accept the tilt",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " 0.0 0.0 " + Math.sin(h)).contains("\"commanded\":true"));
        bot().waitTicks(120);
        String info = shipInfo(bx, by, bz);
        double sx = readDouble(info, POS_X), sz = readDouble(info, POS_Z);

        // Put the REAL client player on the GROUND beside the hull, inside the grown world box, and
        // WALK him along it with the real forward key. He stands on terra firma the whole way. The
        // "ground" is a deterministic flat platform: the assembled ship's world position varies run
        // to run, and natural terrain at (shipPos + offset) once dropped the walker into a gully -
        // failing the Y-stability check on scenery, not on the contract under test.
        int px = (int) Math.floor(sx), pz = (int) Math.floor(sz);
        assertTrue("walk platform fill failed",
                exec("artest fill 0 " + (px - 2) + " " + by + " " + (pz - 2) + " " + (px + 12) + " "
                        + by + " " + (pz + 12) + " minecraft:stone").contains("\"ok\":true"));
        assertTrue("walk headroom clear failed",
                exec("artest fill 0 " + (px - 2) + " " + (by + 1) + " " + (pz - 2) + " " + (px + 12)
                        + " " + (by + 4) + " " + (pz + 12) + " minecraft:air").contains("\"ok\":true"));
        // Face NORTH (yaw 180 looks along -Z in MC): the walk starts at the platform's south edge
        // and crosses its full depth without stepping off.
        exec("tp @a " + (px + 4) + " " + (by + 1) + " " + (pz + 11) + " 180 0");
        bot().waitTicks(30);
        double groundY = bot().reportState().get("playerY").getAsDouble();

        int captured = 0, samples = 0;
        double yMin = groundY, yMax = groundY;
        StringBuilder trace = new StringBuilder();
        bot().holdKey(Keyboard.KEY_W);
        try {
            for (int i = 0; i < 12; i++) {
                bot().waitTicks(4);
                samples++;
                String cap = exec("artest vs deck-capture");
                boolean t = cap.contains("\"alreadyTracked\":true");
                boolean terrain = cap.contains("\"supportedByWorldTerrain\":true");
                if (t) captured++;
                double y = bot().reportState().get("playerY").getAsDouble();
                yMin = Math.min(yMin, y);
                yMax = Math.max(yMax, y);
                trace.append(String.format("[%d y=%.2f cap=%b terra=%b] ", i, y, t, terrain));
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_W);
        }
        System.out.println("[crewcap] ground-walk groundY=" + groundY + " yMin=" + yMin + " yMax="
                + yMax + " captured=" + captured + "/" + samples + " :: " + trace);

        assertTrue("a player walking on world terrain beside a parked ship must NEVER be captured "
                + "into its frame (" + captured + "/" + samples + " samples captured): " + trace,
                captured == 0);
        assertTrue("his world-frame walk must stay on the ground - no ship-frame yank (y "
                + yMin + ".." + yMax + " around " + groundY + ")", yMax - yMin < 2.0);
    }

    // ---- #47: a still crew member on a steeply-rolled deck is not dragged sideways --------------

    private static final String SHIP_FRAME_TRAVEL =
            "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel";
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern SEAT_X = Pattern.compile("\"seatX\":(-?\\d+)");
    private static final Pattern SEAT_Y = Pattern.compile("\"seatY\":(-?\\d+)");
    private static final Pattern SEAT_Z = Pattern.compile("\"seatZ\":(-?\\d+)");

    @Test
    public void aStillCrewMemberOnASteeplyRolledDeckIsNotDraggedSideways() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 5420, by = 64, bz = 5420;

        // Board and stand up on the LEVEL deck (the dismount seed captures the ex-pilot), then roll
        // the unmanned ship past vertical and HOLD it - the closest headless stand-in for the live
        // "walking an inverted deck" configuration (the AFC caps commanded rolls near ~160; a true
        // 180 needs a free spin that VS damps). The playtest symptom: with NO input, the crew member
        // is dragged sideways while the CLIENT capture thrashes (drop + re-capture every few ticks).
        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);
        exec("artest player dismount");
        bot().waitTicks(30);

        assertTrue("attitude hold must accept the past-vertical roll",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " 0.17365 0.0 0.0 0.98481")
                        .contains("\"commanded\":true"));
        bot().waitTicks(200); // slew and settle - stationary, steeply rolled

        // The subject must be in the regime the symptom lives in, and the instrument must fire:
        // the ship really steeply rolled, and the CLIENT really resolving this body (all-zero
        // discriminator statics with a non-resolving client would be a vacuous pass).
        String info = shipInfo(bx, by, bz);
        double qx = readDouble(info, Pattern.compile("\"qx\":(-?[0-9.E\\-]+)"));
        double qz = readDouble(info, Pattern.compile("\"qz\":(-?[0-9.E\\-]+)"));
        double upY = 1.0 - 2.0 * (qx * qx + qz * qz);
        assertTrue("the ship must be steeply rolled for this test to mean anything (upY=" + upY + ")",
                upY < -0.3);
        long resolvedBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks");

        // Stillness window: NO input at all. Sample the client's own drift, capture churn and the
        // walk discriminators (all CLIENT-JVM statics - the client owns this body's movement).
        long dropsBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops");
        double x0 = bot().reportState().get("playerX").getAsDouble();
        double z0 = bot().reportState().get("playerZ").getAsDouble();
        double maxLateral = 0.0;
        float strafeSeen = 0f, forwardSeen = 0f;
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            bot().waitTicks(5);
            double mx = clientDouble(SHIP_FRAME_TRAVEL, "lastMotionShipX");
            double mz = clientDouble(SHIP_FRAME_TRAVEL, "lastMotionShipZ");
            float st = (float) clientDouble(SHIP_FRAME_TRAVEL, "lastInStrafe");
            float fw = (float) clientDouble(SHIP_FRAME_TRAVEL, "lastInForward");
            strafeSeen = Math.max(strafeSeen, Math.abs(st));
            forwardSeen = Math.max(forwardSeen, Math.abs(fw));
            maxLateral = Math.max(maxLateral, Math.max(Math.abs(mx), Math.abs(mz)));
            if (i % 4 == 0) {
                trace.append(String.format("[%d mShip=(%.3f,%.3f) in=(%.2f,%.2f)] ", i, mx, mz, st, fw));
            }
        }
        long dropsAfter = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops");
        long resolvedAfter = (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks");
        double x1 = bot().reportState().get("playerX").getAsDouble();
        double z1 = bot().reportState().get("playerZ").getAsDouble();
        double drift = Math.sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0));
        long churn = dropsAfter - dropsBefore;
        System.out.println("[crewcap] still-drift upY=" + upY + " drift=" + drift
                + " clientDropChurn=" + churn + " clientResolved=" + resolvedBefore + "->"
                + resolvedAfter + " maxLateralShipMotion=" + maxLateral + " inputsSeen=("
                + strafeSeen + "," + forwardSeen + ") :: " + trace);

        // Instrument-fires guard: the CLIENT must have been resolving this body through the window,
        // or every zero above is vacuous.
        assertTrue("the client must be resolving the crew member through the stillness window "
                + "(resolvedTicks " + resolvedBefore + " -> " + resolvedAfter + ")",
                resolvedAfter > resolvedBefore + 50);
        // Setup sanity: the window really was input-free (the discriminator data is only meaningful
        // for a still body).
        assertTrue("the stillness window must be input-free (saw strafe=" + strafeSeen + " forward="
                + forwardSeen + ")", strafeSeen == 0f && forwardSeen == 0f);
        // The contract (deck-frame walking - no input, no movement): a still crew member on a held,
        // stationary deck STAYS PUT - no sideways drag - and his capture does not churn.
        assertTrue("a still crew member must not be dragged sideways on a held rolled deck: drifted "
                + drift + " blocks in ~5s (client drop churn=" + churn + ", max lateral ship-frame "
                + "motion=" + maxLateral + "): " + trace, drift < 0.5);
        assertTrue("the client capture must not churn on a held rolled deck (drops in window=" + churn
                + "): " + trace, churn < 5);
    }

    // ---- #47 on the LIVE configuration: a station-keeping hover (never fully still, ledger #41) --

    @Test
    public void aStillCrewMemberOnAHoveringShipIsNotDraggedSideways() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 5520, by = 64, bz = 5520;

        // The playtest ship is not attitude-HELD by a probe - it HOVERS under station-keeping, which
        // never brings it fully to rest (a ~-0.01/tick vertical residual plus correction wobble).
        // The reported no-input sideways drag lives on that configuration, upright included - so the
        // subject here is a real hover: lift with the pilot's own vertical key, stand up, hold still.
        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);

        double startY = readDouble(shipInfo(bx, by, bz), POS_Y);
        bot().holdKey(Keyboard.KEY_R);
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated: hold the vertical thruster until the ship has actually climbed
            // 3 blocks, with a load-scaled ceiling + early exit. A fixed 200-iteration budget
            // under-lifts a frame-starved client under concurrent load and reds a healthy hover.
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> readDouble(shipInfo(bx, by, bz), POS_Y),
                    y -> y - startY >= 3.0, 2, 200);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double liftedY = lift.value;
        assertTrue("the pilot must lift the ship into a hover: " + startY + " -> " + liftedY,
                liftedY - startY > 2.0);
        exec("artest player dismount");
        bot().waitTicks(40);

        long resolvedBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks");
        long dropsBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops");
        double x0 = bot().reportState().get("playerX").getAsDouble();
        double z0 = bot().reportState().get("playerZ").getAsDouble();
        double maxLateral = 0.0;
        float strafeSeen = 0f, forwardSeen = 0f;
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            bot().waitTicks(5);
            double mx = clientDouble(SHIP_FRAME_TRAVEL, "lastMotionShipX");
            double mz = clientDouble(SHIP_FRAME_TRAVEL, "lastMotionShipZ");
            strafeSeen = Math.max(strafeSeen, Math.abs((float) clientDouble(SHIP_FRAME_TRAVEL, "lastInStrafe")));
            forwardSeen = Math.max(forwardSeen, Math.abs((float) clientDouble(SHIP_FRAME_TRAVEL, "lastInForward")));
            maxLateral = Math.max(maxLateral, Math.max(Math.abs(mx), Math.abs(mz)));
            if (i % 4 == 0) {
                trace.append(String.format(java.util.Locale.ROOT, "[%d mShip=(%.3f,%.3f)] ", i, mx, mz));
            }
        }
        long resolvedAfter = (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks");
        long churn = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops") - dropsBefore;
        double x1 = bot().reportState().get("playerX").getAsDouble();
        double z1 = bot().reportState().get("playerZ").getAsDouble();
        double drift = Math.sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0));
        System.out.println("[crewcap] hover-drift drift=" + drift + " clientDropChurn=" + churn
                + " clientResolved=" + resolvedBefore + "->" + resolvedAfter
                + " maxLateralShipMotion=" + maxLateral + " inputsSeen=(" + strafeSeen + ","
                + forwardSeen + ") :: " + trace);

        assertTrue("the client must be resolving the crew member through the window (resolvedTicks "
                + resolvedBefore + " -> " + resolvedAfter + ")", resolvedAfter > resolvedBefore + 50);
        assertTrue("the stillness window must be input-free (saw strafe=" + strafeSeen + " forward="
                + forwardSeen + ")", strafeSeen == 0f && forwardSeen == 0f);
        assertTrue("a still crew member must not be dragged sideways on a hovering ship: drifted "
                + drift + " blocks in ~5s (client drop churn=" + churn + ", max lateral ship-frame "
                + "motion=" + maxLateral + "): " + trace, drift < 0.5);
        assertTrue("the client capture must not churn on a hovering ship (drops in window=" + churn
                + "): " + trace, churn < 5);
    }

    // ---- #47: WALKING and JUMPING on a hovering ship must not churn the capture -----------------

    @Test
    public void walkingAndJumpingOnAHoveringShipDoesNotChurnTheCapture() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 5620, by = 64, bz = 5620;

        // The round-11 playtest drag happens on a NEARLY-LEVEL hovering ship while the crew member
        // is actively walking and jumping - the still-crew pins stayed green while the live drag
        // persisted, so ACTIVITY is the missing axis. Same arrangement as the still-hover pin, plus
        // real W walking and real SPACE jumps through the window.
        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);
        double startY = readDouble(shipInfo(bx, by, bz), POS_Y);
        bot().holdKey(Keyboard.KEY_R);
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated: hold the vertical thruster until the ship has actually climbed
            // 3 blocks, with a load-scaled ceiling + early exit. A fixed 200-iteration budget
            // under-lifts a frame-starved client under concurrent load and reds a healthy hover.
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> readDouble(shipInfo(bx, by, bz), POS_Y),
                    y -> y - startY >= 3.0, 2, 200);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double liftedY = lift.value;
        assertTrue("the pilot must lift the ship into a hover: " + startY + " -> " + liftedY,
                liftedY - startY > 2.0);
        exec("artest player dismount");
        bot().waitTicks(40);

        long resolvedBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks");
        long dropsBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops");

        // Walk in a tight square (short bursts each direction so the crew member stays on the small
        // deck) and jump twice - real client keys, the real activity of the playtest. Sample the
        // client's own state after every leg so a mid-window ejection names its leg and gate.
        // A PURE VERTICAL jump first (no walk key held): on any ship motion the jumper must arc and
        // land back on the deck, still captured - the kinematics pin (a carry double-count rocketed
        // him off a climbing hover). Sampled per 2 ticks.
        String dropBefore = clientString(SHIP_FRAME_TRAVEL, "lastDropReason");
        bot().holdKey(Keyboard.KEY_SPACE);
        StringBuilder arc = new StringBuilder();
        for (int t = 0; t < 3; t++) {
            bot().waitTicks(2);
            arc.append(String.format(java.util.Locale.ROOT, "[t%d y=%.2f mShipY=%s] ",
                    t * 2,
                    bot().reportState().get("playerY").getAsDouble(),
                    clientString(SHIP_FRAME_TRAVEL, "lastMotionShipY")));
        }
        bot().releaseKey(Keyboard.KEY_SPACE);
        for (int t = 3; t < 10; t++) {
            bot().waitTicks(2);
            arc.append(String.format(java.util.Locale.ROOT, "[t%d y=%.2f mShipY=%s] ",
                    t * 2,
                    bot().reportState().get("playerY").getAsDouble(),
                    clientString(SHIP_FRAME_TRAVEL, "lastMotionShipY")));
        }
        System.out.println("[crewcap] jump-arc " + arc);
        assertTrue("a vertical jump on a hovering ship must land back on the deck, still captured "
                + "(dropReason before='" + dropBefore + "' after='"
                + clientString(SHIP_FRAME_TRAVEL, "lastDropReason") + "'): " + arc,
                clientString(SHIP_FRAME_TRAVEL, "lastDropReason").equals(dropBefore));

        // Then a tight walk square - SHORT legs (3 ticks ≈ 0.65 blocks): the fixture deck is only
        // ~3x5, and a longer leg walks the crew member clean off its edge, a legitimate release
        // that says nothing about churn.
        int[] keys = {Keyboard.KEY_W, Keyboard.KEY_D, Keyboard.KEY_S, Keyboard.KEY_A};
        StringBuilder legs = new StringBuilder();
        for (int leg = 0; leg < 4; leg++) {
            bot().holdKey(keys[leg]);
            try {
                bot().waitTicks(3);
            } finally {
                bot().releaseKey(keys[leg]);
            }
            bot().waitTicks(5);
            legs.append(String.format(java.util.Locale.ROOT,
                    "[leg%d pos=(%.1f,%.1f,%.1f) resolved=%s dropReason='%s' worldMove='%s'] ",
                    leg,
                    bot().reportState().get("playerX").getAsDouble(),
                    bot().reportState().get("playerY").getAsDouble(),
                    bot().reportState().get("playerZ").getAsDouble(),
                    clientString(SHIP_FRAME_TRAVEL, "resolvedTicks"),
                    clientString(SHIP_FRAME_TRAVEL, "lastDropReason"),
                    clientString(SHIP_FRAME_TRAVEL, "lastWorldMove")));
        }
        bot().waitTicks(20);
        System.out.println("[crewcap] active-legs " + legs);

        long resolvedAfter = (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks");
        long churn = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops") - dropsBefore;
        String capture = exec("artest vs deck-capture");
        System.out.println("[crewcap] active-churn churn=" + churn + " clientResolved="
                + resolvedBefore + "->" + resolvedAfter + " capture=" + capture);

        assertTrue("the client must be resolving through the activity window (resolvedTicks "
                + resolvedBefore + " -> " + resolvedAfter + ")", resolvedAfter > resolvedBefore + 20);
        // The churn contract: activity must never cycle the capture through the external-move guard
        // (the drag war). A GEOMETRIC release (walked off the tiny fixture deck -> leftShipRegion /
        // steppedOntoTerrain) is legitimate and not this test's subject.
        assertTrue("walking and jumping on a hovering ship must not churn the capture (client drops "
                + "in window=" + churn + ")", churn < 5);
        String lastReason = clientString(SHIP_FRAME_TRAVEL, "lastDropReason");
        assertTrue("any release during deck activity must be geometric, never the external-move "
                + "guard (lastDropReason='" + lastReason + "')",
                !lastReason.startsWith("externalMove"));
    }

    // ---- #47 driver isolation: sustained fast ship motion vs the CLIENT external-move guard -----

    @Test
    public void aStillCrewMemberOnAFastClimbingShipKeepsHisCapture() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 5720, by = 64, bz = 5720;

        // The round-13 playtest thrash correlates with INVERSION, but the drop lines' real common
        // factor is fast per-tick SHIP MOTION (a freefall reaching 0.87 blocks/tick; a hunting
        // inverted hover stepping 0.2-0.4/tick) - the inverted attitude merely hunts harder. The
        // driver is reproduced here directly, at level attitude: a sustained full-stick climb. The
        // guard math says a smooth climb can NEVER trip it while the carry-widening sees the ship's
        // velocity (allowed grows 3x faster than the step); so any external-move churn in this window
        // means the CLIENT's velocity feed is blind and the widening never engaged.
        // Board by WALKING ON, never through the pilot seat: a seat-mount leaves a dismounted (empty)
        // dummy on the seat, and that dummy overwrites the AFC's pilot input every tick - the
        // seat-input probe below is then inert and the ship never moves (two voided runs found this).
        double[] ship = buildShip(bx, by, bz);
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("the client player must be captured on the deck before the drive: "
                + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"verdict\":true"));

        // CONTROL: a quiet parked window. The guard must be quiet here (the still-crew pins), or a
        // quiet driver window would prove nothing about the driver.
        long dropsBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops");
        bot().waitTicks(60);
        long controlChurn = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops") - dropsBefore;

        // DRIVER: sustained vertical motion, commanded SERVER-side through the seat->AFC path (the
        // crew member is standing on the deck, not sitting). The pilot input decays fast, so the
        // command is re-sent EVERY tick (the seat-drive e2e's cadence). Two phases: full-up first
        // (gains altitude; the fixture's thrust may or may not reach the guard-relevant rate), then
        // full-down from that altitude (thrust plus gravity - the fast regime the round-13 freefall
        // episode lived in), stopped well above the ground. The regime gate below asserts the peak
        // per-tick rate actually reached the guard's static epsilon, or the run is void.
        long resolvedBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks");
        dropsBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops");
        double shipY0 = readDouble(shipInfo(bx, by, bz), POS_Y);
        double maxFrameStep = 0.0, maxCarry = -1.0, maxRate = 0.0, travelled = 0.0;
        double yPrev = shipY0;
        StringBuilder samples = new StringBuilder();
        // The drive's thrust duty-cycle is cadence-bound: the pilot input decays between re-sends,
        // so when concurrent forks stretch the per-iteration round-trip the ship climbs LESS per
        // iteration (measured: a consistent ~3.1 blocks over the fixed budget at 8-12 forks vs >4
        // idle). Scale the phase budgets by the harness load factor and let the up phase exit as
        // soon as the instrument-fires target is comfortably met - an idle machine exits at the
        // same iteration it always did, a loaded one gets the iterations it actually needs.
        double loadFactor = com.github.stannismod.forge.testing.TestTimeouts.factor();
        int phaseDownFrom = (int) (120 * loadFactor);
        int totalIters = (int) (200 * loadFactor);
        for (int i = 0; i < totalIters; i++) {
            if (i < phaseDownFrom && travelled > 6.0) {
                phaseDownFrom = i; // target met - flip to descent, keep its budget proportional
                totalIters = i + (int) (80 * loadFactor);
            }
            boolean up = i < phaseDownFrom;
            String drive = exec("artest vs seat-input 0 0 " + (up ? "1" : "-1") + " 0 0 0 0");
            assertTrue("seat-input must resolve the seat's AFC: " + drive,
                    drive.contains("\"afcResolved\":true"));
            bot().waitTicks(1);
            if (i % 5 == 4) {
                double yNow = readDouble(shipInfo(bx, by, bz), POS_Y);
                double rate = Math.abs(yNow - yPrev) / 5.0;
                maxRate = Math.max(maxRate, rate);
                travelled = Math.max(travelled, Math.abs(yNow - shipY0));
                yPrev = yNow;
                double step = clientDouble(SHIP_FRAME_TRAVEL, "lastGuardFrameStep");
                double carry = clientDouble(SHIP_FRAME_TRAVEL, "lastGuardCarry");
                long drops = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops") - dropsBefore;
                maxFrameStep = Math.max(maxFrameStep, step);
                maxCarry = Math.max(maxCarry, carry);
                if (i % 20 == 19) {
                    samples.append(String.format(java.util.Locale.ROOT,
                            "[%d y=%.1f rate=%.3f step=%.3f carry=%.4f drops=%d] ",
                            i, yNow, rate, step, carry, drops));
                }
                // Descending: never ride it into the ground - a hull impact drops the capture for
                // legitimate reasons and would contaminate the churn count.
                if (!up && yNow - (by + 2) < 6.0) {
                    samples.append("[abort-descent y=").append(yNow).append("] ");
                    break;
                }
            }
        }
        exec("artest vs seat-input 0 0 0 0 0 0 0");
        double shipY1 = readDouble(shipInfo(bx, by, bz), POS_Y);
        long churn = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops") - dropsBefore;
        long resolvedAfter = (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks");
        String dropShape = String.format(java.util.Locale.ROOT,
                "lastDrop frameMoved=(%.3f,%.3f,%.3f) entityMoved=(%.3f,%.3f,%.3f) allowed=%.3f",
                clientDouble(SHIP_FRAME_TRAVEL, "lastDropFrameMovedX"),
                clientDouble(SHIP_FRAME_TRAVEL, "lastDropFrameMovedY"),
                clientDouble(SHIP_FRAME_TRAVEL, "lastDropFrameMovedZ"),
                clientDouble(SHIP_FRAME_TRAVEL, "lastDropEntityMovedX"),
                clientDouble(SHIP_FRAME_TRAVEL, "lastDropEntityMovedY"),
                clientDouble(SHIP_FRAME_TRAVEL, "lastDropEntityMovedZ"),
                clientDouble(SHIP_FRAME_TRAVEL, "lastDropAllowed"));
        System.out.println("[crewcap] climb-churn shipY=" + shipY0 + "->" + shipY1 + " travelled="
                + travelled + " maxRate=" + maxRate + " churn=" + churn
                + " control=" + controlChurn + " maxFrameStep=" + maxFrameStep + " maxCarry="
                + maxCarry + " resolved=" + resolvedBefore + "->" + resolvedAfter + " " + dropShape
                + " :: " + samples);

        // Instrument-fires guards: the ship really moved, fast enough to matter to the guard, the
        // client really resolved the body, and the control window was quiet - otherwise the churn
        // number below is vacuous.
        assertTrue("the commanded drive must actually move the ship (travelled=" + travelled
                + "); a wrong-seat seat-input or dead AFC voids the run", travelled > 4.0);
        assertTrue("the drive must reach the guard-relevant regime (maxRate=" + maxRate
                + " blocks/tick vs the 0.2 static epsilon); a slower ship cannot falsify the claim",
                maxRate > 0.2);
        assertTrue("the client must be resolving the crew member through the drive (resolvedTicks "
                + resolvedBefore + " -> " + resolvedAfter + ")", resolvedAfter > resolvedBefore + 40);
        assertTrue("the control (quiet hover) window must not churn (control=" + controlChurn + ")",
                controlChurn < 3);
        // The contract (deck-frame walking, along the ship-motion axis): smooth sustained ship
        // motion must never cycle the crew capture through the external-move guard - the deck's own
        // carry is the guard's to absorb. frameMoved >> entityMoved in the drop shape = the deck
        // stepped under an unmoved body and the widening was blind (carry ~0 = the client velocity
        // feed is empty).
        assertTrue("a fast-climbing ship must not churn its still crew member's capture: churn="
                + churn + " maxFrameStep=" + maxFrameStep + " maxCarry=" + maxCarry + " " + dropShape
                + " :: " + samples, churn == 0);
    }

    // ---- Excluded states: the dismount deck-hold must never snap a creative-flying ex-pilot -----

    @Test
    public void aCreativeFlyingExPilotIsNeverSnappedBackByTheDismountHold() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 5820, by = 64, bz = 5820;

        // The live war: dismount the pilot seat and start creative-FLYING within the dismount
        // hold's 20-tick window. The window re-sends the deck-capture seed every tick; a seed that
        // ignores excluded states snaps the flying player to the seat column and zeroes his motion,
        // handles() releases him right back (creativeFlight), and the next seed snaps him again -
        // the player is frozen mid-air, camera gates flickering, for the whole window. The
        // contract: an ex-pilot in an excluded state keeps world-frame movement - no snap, ever.
        buildAndBoardShip(bx, by, bz);
        exec("gamemode creative @a"); // flight needs creative; the harness default is not
        bot().waitTicks(20);
        exec("artest player dismount");
        // Double-tap space IMMEDIATELY - inside the hold window - to start creative flight.
        bot().holdKey(Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().releaseKey(Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().holdKey(Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().releaseKey(Keyboard.KEY_SPACE);

        // Hold space well past the hold window: a flying player RISES steadily and, on this
        // upright open fixture, soon leaves the ship's grown stay region entirely. The war's
        // signature is the opposite - position pinned to the seat column, isResolving flickering.
        //
        // Contract as amended by flying-aboard: a transient capture of the flyer while he is still
        // the deck's to claim (standing contact at the seat) is LEGAL - his flight then resolves
        // on deck axes and he still rises. What must NEVER happen is the old war: the body
        // frozen/yanked at the seat column. And once he has risen out of the ship's stay region he
        // is RELEASED - leaving that region ends the capture - and stays world-frame: never
        // re-captured, never snapped back down.
        double y0 = bot().reportState().get("playerY").getAsDouble();
        long dropsBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops");
        StringBuilder win = new StringBuilder();
        double yMax = y0;
        double maxDrop = 0.0;
        boolean trackedAtEnd = false;
        bot().holdKey(Keyboard.KEY_SPACE);
        try {
            for (int i = 0; i < 25; i++) {
                bot().waitTicks(2);
                double y = bot().reportState().get("playerY").getAsDouble();
                maxDrop = Math.max(maxDrop, yMax - y);
                yMax = Math.max(yMax, y);
                trackedAtEnd = exec("artest vs deck-capture").contains("\"alreadyTracked\":true");
                win.append(String.format(java.util.Locale.ROOT, "[t%d y=%.2f cap=%b] ",
                        i * 2, y, trackedAtEnd));
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_SPACE);
        }
        long churn = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops") - dropsBefore;
        exec("gamemode survival @a"); // leave the shared world as the other tests expect it
        System.out.println("[crewcap] fly-window y0=" + y0 + " yMax=" + yMax + " maxDrop=" + maxDrop
                + " trackedAtEnd=" + trackedAtEnd + " churn=" + churn + " :: " + win);

        // Instrument-fires: the double-tap really put the client into creative flight - a
        // non-flying player holding space would jump and land, never rising a full 1.5 blocks.
        assertTrue("the double-tap must actually start creative flight (y " + y0 + " -> max " + yMax
                + "): " + win, yMax - y0 > 1.5);
        // The war's signature: the body yanked back toward the seat. A steady ascent (deck-frame
        // or world-frame - this ship is upright) never gives back more than a fraction of a block.
        assertTrue("a flying ex-pilot must never be yanked back down (maxDrop=" + maxDrop + "): "
                + win, maxDrop < 0.75);
        // Risen far above the open fixture, he has left the stay region: released, world-frame,
        // and no re-capture pulling at him from below.
        assertTrue("a flyer who has left the ship must be RELEASED, not still captured: " + win,
                !trackedAtEnd);
    }

    // ---- The OUTER hull of an inverted ship is walkable, with WORLD-frame semantics -------------

    @Test
    public void standingOnTheWorldTopOfAnInvertedShipKeepsWorldFrameSemantics() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 5920, by = 64, bz = 5920;

        // The round-15 playtest residue: standing on the world-facing top of an inverted hull (its
        // former belly), the capture cycle chews - in subspace that surface has NO floor beneath
        // the body (shipObstacles=0), so ship-frame capture is structurally impossible there (first
        // contact demands standing support in the ship's OWN subspace), yet the post-drop
        // re-capture takes unconditionally and ship-frame gravity fights the world's hull collision
        // every tick. The outer-hull contract: that body is NOT ABOARD - it keeps world gravity and
        // movement and stands on the hull as on terrain, never tunneling.
        double[] ship = buildShip(bx, by, bz);
        double h = Math.toRadians(160.0) / 2.0;
        assertTrue("attitude hold must accept the past-vertical roll",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(200);
        String info = shipInfo(bx, by, bz);
        double qx = readDouble(info, Pattern.compile("\"qx\":(-?[0-9.E\\-]+)"));
        double qz = readDouble(info, Pattern.compile("\"qz\":(-?[0-9.E\\-]+)"));
        double upY = 1.0 - 2.0 * (qx * qx + qz * qz);
        assertTrue("the ship must be steeply inverted for the hull-top to exist (upY=" + upY + ")",
                upY < -0.3);
        double sx = readDouble(info, POS_X), sy = readDouble(info, POS_Y), sz = readDouble(info, POS_Z);

        // Fall onto the world-top of the inverted hull from a few blocks up.
        exec("tp @a " + sx + " " + (sy + 7) + " " + sz + " 0 0");
        // The freshly-teleported client may not tick until its destination chunks stream in (the
        // whole encounter would then sample a frozen body and prove nothing). Gate the window on
        // the fall actually beginning.
        double preY = bot().reportState().get("playerY").getAsDouble();
        // Event-gated fall detection with a load-scaled ceiling: a fixed 60-iteration
        // budget can miss a slow chunk-stream / tick start under concurrent load and red a healthy
        // encounter before it has even begun.
        ClientPoll.Result<Double> fall = ClientPoll.until(bot()::waitTicks,
                () -> bot().reportState().get("playerY").getAsDouble(),
                y -> Math.abs(y - preY) > 0.4, 2, 60);
        assertTrue("the teleported client must start falling before the encounter window "
                + "(client tick/chunk-stream stall)", fall.satisfied);
        long dropsBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops");
        StringBuilder land = new StringBuilder();
        double settledY = Double.NaN;
        long resolvedBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks");
        for (int i = 0; i < 30; i++) {
            bot().waitTicks(3);
            double py = bot().reportState().get("playerY").getAsDouble();
            if (i % 3 == 0) {
                land.append(String.format(java.util.Locale.ROOT,
                        "[t%d y=%.2f res=%d drop='%s'] ", i * 3, py,
                        (long) clientDouble(SHIP_FRAME_TRAVEL, "resolvedTicks") - resolvedBefore,
                        clientString(SHIP_FRAME_TRAVEL, "lastDropReason")));
            }
            settledY = py;
        }

        // (a) Never tunnels: he stands ON the hull-top, above the ship centre - not fallen through
        // to the terrain far below (by+1) and not inside the hull volume oscillating.
        assertTrue("the body must stand on the world-top of the inverted hull, not tunnel through "
                + "(settledY=" + settledY + " shipY=" + sy + " terrainY~" + (by + 1) + "): " + land,
                settledY > sy - 0.5);
        // (b) NOT ABOARD: the hull-top stander is held in HULL-STAND mode - world semantics,
        // ship-geometry collision - never in the deck frame.
        String cap = exec("artest vs deck-capture");
        assertTrue("a body on the OUTER hull must keep world-frame semantics - held as HULL-STAND, "
                + "never ABOARD: " + cap,
                !cap.contains("\"alreadyTracked\":true") || cap.contains("\"hullStand\":true"));
        // (c) His camera stays his own - the deck-levelled view never engages for a hull stander.
        boolean camActive = Boolean.parseBoolean(
                clientString("zmaster587.advancedRocketry.client.ShipFrameCamera", "shipCamActive"));
        assertTrue("the deck camera must never engage for a hull-top stander (the outer hull keeps "
                + "world-frame semantics)", !camActive);
        // (d) And the capture machinery must not churn against him.
        long churn = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops") - dropsBefore;
        System.out.println("[crewcap] hull-top settledY=" + settledY + " shipY=" + sy + " churn="
                + churn + " camActive=" + camActive + " :: " + land);
        assertTrue("the capture must not churn against a hull-top stander (drops=" + churn + "): "
                + land, churn < 3);
    }

    @Test
    public void aHullTopEncounterNeverEntersTheShipFrame() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 6020, by = 64, bz = 6020;

        // The verified outer-hull half (the round-15 residue): a body meeting the world-facing
        // surface of an inverted hull - where in subspace there is NO floor beneath it - must NEVER
        // be captured into the ship frame. The old support probe counted PENETRATING boxes (top above
        // the feet) as standing support, so a faller who punched slightly into the hull was
        // captured, ship-frame gravity (world-up at inversion) flung him off, and the post-drop
        // re-capture re-entered every tick: the round-15 log's obstacles=0 capture bursts.
        double[] ship = buildShip(bx, by, bz);
        double h = Math.toRadians(160.0) / 2.0;
        assertTrue("attitude hold must accept the past-vertical roll",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(200);
        String info = shipInfo(bx, by, bz);
        double qx = readDouble(info, Pattern.compile("\"qx\":(-?[0-9.E\\-]+)"));
        double qz = readDouble(info, Pattern.compile("\"qz\":(-?[0-9.E\\-]+)"));
        double upY = 1.0 - 2.0 * (qx * qx + qz * qz);
        assertTrue("the ship must be steeply inverted for the hull-top to exist (upY=" + upY + ")",
                upY < -0.3);
        double sx = readDouble(info, POS_X), sy = readDouble(info, POS_Y), sz = readDouble(info, POS_Z);

        exec("tp @a " + sx + " " + (sy + 7) + " " + sz + " 0 0");
        // The freshly-teleported client may not tick until its destination chunks stream in (the
        // whole encounter would then sample a frozen body and prove nothing). Gate the window on
        // the fall actually beginning.
        double preY = bot().reportState().get("playerY").getAsDouble();
        // Event-gated fall detection with a load-scaled ceiling: a fixed 60-iteration
        // budget can miss a slow chunk-stream / tick start under concurrent load and red a healthy
        // encounter before it has even begun.
        ClientPoll.Result<Double> fall = ClientPoll.until(bot()::waitTicks,
                () -> bot().reportState().get("playerY").getAsDouble(),
                y -> Math.abs(y - preY) > 0.4, 2, 60);
        assertTrue("the teleported client must start falling before the encounter window "
                + "(client tick/chunk-stream stall)", fall.satisfied);
        long dropsBefore = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops");
        int aboardSeen = 0, hullSeen = 0, samples = 0;
        StringBuilder enc = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            bot().waitTicks(3);
            samples++;
            String cap = exec("artest vs deck-capture");
            boolean tracked = cap.contains("\"alreadyTracked\":true");
            boolean hull = cap.contains("\"hullStand\":true");
            if (tracked && !hull) aboardSeen++;
            if (tracked && hull) hullSeen++;
            if (i % 5 == 0) {
                enc.append(String.format(java.util.Locale.ROOT, "[t%d y=%.2f cap=%b hull=%b] ",
                        i * 3, bot().reportState().get("playerY").getAsDouble(), tracked, hull));
            }
        }
        long churn = (long) clientDouble(SHIP_FRAME_TRAVEL, "externalMoveDrops") - dropsBefore;
        System.out.println("[crewcap] hull-top-mode aboard=" + aboardSeen + " hull=" + hullSeen
                + "/" + samples + " churn=" + churn + " :: " + enc);

        // The outer-hull mode contract: the hull encounter may be HELD (hull-stand), but it must
        // NEVER read as ABOARD - no deck frame, no deck camera, no deck mouse for a hull stander.
        assertTrue("a body meeting the OUTER hull of an inverted ship must never enter ABOARD/deck "
                + "mode: aboard " + aboardSeen + "/" + samples + " (hull-stand " + hullSeen
                + ") :: " + enc, aboardSeen == 0);
        // The encounter must actually exercise the hull-stand hold, or this run proved nothing.
        assertTrue("the encounter must engage the HULL-STAND hold (hull-stand seen " + hullSeen
                + "/" + samples + "): " + enc, hullSeen > 0);
        assertTrue("and the capture machinery must not churn against it (drops=" + churn + ")",
                churn == 0);
    }

    // ---- The crosshair picks the block the camera looks at, at any attitude ---------------------

    @Test
    public void theCrosshairPicksTheSameDeckBlockAtAnyAttitude() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 6120, by = 64, bz = 6120;

        // The raytrace origin (getPositionEyes) ran along WORLD up while the camera renders the eye
        // along the SHIP's up; on a rolled deck the two diverge by up to an eye height, so the
        // crosshair picked a block ~1.4 blocks beside the one the camera centred (the round-10
        // "crosshair does not match the look-HUD"). The interaction contract - the block outlined
        // under the crosshair is the block interacted with - in its attitude-invariance form: a
        // crew member held at the SAME deck point, looking straight down, must see the crosshair
        // resolve the SAME subspace deck block whatever the ship's roll - the deck under his feet
        // does not move in the ship frame when the ship rolls.
        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);
        exec("artest player dismount");
        bot().waitTicks(40);
        assertTrue("the ex-pilot must be captured on the deck: " + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));

        exec("tp @a ~ ~ ~ 0 90"); // look straight down at the deck underfoot
        bot().waitTicks(10);
        String level = clientString(
                "zmaster587.advancedRocketry.client.ShipFrameCamera", "lastMouseOverBlock");
        assertTrue("looking straight down on the LEVEL deck must resolve a block (got '" + level
                + "')", !level.isEmpty());

        // Roll choice is MEASURED against the stand geometry, not arbitrary: the dismounted crew
        // member stands at the SEAT column - the exact centre of the 5x5 deck, 2.5 blocks from
        // every deck face. A world-down ray from the 1.62-high eye leaves the deck's footprint
        // once 1.62*tan(roll) exceeds that half-width, i.e. past ~57 degrees the ray can only
        // miss REGARDLESS of the contract under test (at 60 deg it grazed past the far face by
        // ~0.2 and resolved nothing). 50 deg keeps ~0.6 blocks of landing margin while still
        // satisfying both instrument-fire gates below (upY = cos50 = 0.64 < 0.7; eye divergence
        // = 1.62*sin50 = 1.24 > 0.6).
        double h = Math.toRadians(50.0) / 2.0;
        assertTrue("attitude hold must accept the roll",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(150);
        String info = shipInfo(bx, by, bz);
        double qx = readDouble(info, Pattern.compile("\"qx\":(-?[0-9.E\\-]+)"));
        double qz = readDouble(info, Pattern.compile("\"qz\":(-?[0-9.E\\-]+)"));
        double upY = 1.0 - 2.0 * (qx * qx + qz * qz);
        assertTrue("the ship must be steeply rolled for the eyes to diverge (upY=" + upY + ")",
                upY < 0.7 && upY > 0.1);
        String capNow = exec("artest vs deck-capture");
        assertTrue("the crew member must still be captured after the roll: " + capNow,
                capNow.contains("\"alreadyTracked\":true"));

        exec("tp @a ~ ~ ~ 0 90");
        bot().waitTicks(10);
        String rolled = clientString(
                "zmaster587.advancedRocketry.client.ShipFrameCamera", "lastMouseOverBlock");
        String cam = "zmaster587.advancedRocketry.client.ShipFrameCamera";
        double rx = clientDouble(cam, "lastRayEyeX");
        double ry = clientDouble(cam, "lastRayEyeY");
        double rz = clientDouble(cam, "lastRayEyeZ");
        double cx = clientDouble(cam, "shipCamEyeX");
        double cy = clientDouble(cam, "shipCamEyeY");
        double cz = clientDouble(cam, "shipCamEyeZ");
        double px = bot().reportState().get("playerX").getAsDouble();
        double py = bot().reportState().get("playerY").getAsDouble();
        double pz = bot().reportState().get("playerZ").getAsDouble();
        double rayVsCam = Math.sqrt((rx - cx) * (rx - cx) + (ry - cy) * (ry - cy)
                + (rz - cz) * (rz - cz));
        double worldEyeVsCam = Math.sqrt((px - cx) * (px - cx)
                + (py + 1.62 - cy) * (py + 1.62 - cy) + (pz - cz) * (pz - cz));
        System.out.println("[crewcap] crosshair level='" + level + "' rolled='" + rolled
                + "' upY=" + upY + " rayVsCam=" + rayVsCam + " worldEyeVsCam=" + worldEyeVsCam);
        assertTrue("looking straight down on the ROLLED deck must still resolve a block (got '"
                + rolled + "')", !rolled.isEmpty());
        // Instrument-fires: at this roll the OLD world-up eye really diverges from the rendered
        // camera eye - otherwise agreement below would be vacuous.
        assertTrue("the world-up eye must diverge from the camera eye at this roll (worldEyeVsCam="
                + worldEyeVsCam + ", upY=" + upY + "); a level-ship run cannot falsify the "
                + "ray-origin claim below", worldEyeVsCam > 0.6);
        // The interaction contract: the crosshair ray originates from the SAME eye the camera
        // renders, so the outlined block is the block interacted with.
        assertTrue("the crosshair ray must originate from the rendered camera eye: ray=("
                + rx + "," + ry + "," + rz + ") cam=(" + cx + "," + cy + "," + cz + ") dist="
                + rayVsCam, rayVsCam < 0.25);
    }

    // ---- Deck-frame look: the walking crew's aim lives in the deck frame ------------------------

    @Test
    public void theMouseTurnsTheWalkingCrewsAimInTheDeckFrameAndTheAimRidesTheDeck() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 6420, by = 64, bz = 6420;

        // The walking-crew look contract, in its two player-visible halves, on a STEEPLY ROLLED
        // deck (the attitude where the old world-frame aim under a deck-levelled camera diverged
        // hardest):
        //   (1) a horizontal REAL-MOUSE move sweeps the aim about the DECK NORMAL - the angle
        //       between the aim and the ship's up does not change as he turns;
        //   (2) with the mouse STILL, the aim is glued to the deck - the ship rolling further
        //       carries the world aim with it.
        // The stimulus is the real client mouse path (Entity.turn); the observation is the
        // client's own world look; the ship attitude read server-side is the cross-side oracle.
        double[] ship = buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);
        exec("artest player dismount");
        bot().waitTicks(40);
        assertTrue("the ex-pilot must be captured on the deck: " + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));
        // Out of the cockpit pocket onto the OPEN top deck while the ship is still upright (the
        // dismount leaves the body beside the seat, walled in on all four sides - the walk legs
        // below need runway). The capture then carries this open-deck spot through the rolls.
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(40);
        assertTrue("the crew member must be captured on the OPEN deck before the roll: "
                + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));

        // Roll the ship to ~60 degrees about X and hold it there.
        double h = Math.toRadians(60.0) / 2.0;
        assertTrue("attitude hold must accept the roll",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(150);
        double[] up = shipUpFromInfo(shipInfo(bx, by, bz));
        assertTrue("the ship must be steeply rolled for the frames to diverge (upY=" + up[1] + ")",
                up[1] < 0.7 && up[1] > 0.1);
        assertTrue("the crew member must still be captured after the roll: "
                + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));

        // Baseline aim (a server re-aim, which must RE-SEED the deck look, not fight it).
        exec("tp @a ~ ~ ~ 20 10");
        bot().waitTicks(10);
        assertTrue("the deck-frame look must be engaged for a captured walking crew member "
                        + "(deckActive=false would make every assertion below vacuous)",
                Boolean.parseBoolean(clientString(DECK_LOOK, "active")));
        double[] look0 = clientLook();
        double cone0 = dot(up, look0);

        // (1) Four 30-degree horizontal REAL-MOUSE turns: each sweeps ~30 degrees about the deck
        // normal and none of them moves the aim off its cone around the ship's up. The old
        // world-frame aim fails both ways at this roll (the sweep bends toward the world poles and
        // the cone angle drifts), whether or not the delta itself is roll-rotated.
        double swept = 0.0;
        double[] prev = look0;
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            bot().turnLook(200f, 0f); // +30 degrees of deck yaw in vanilla mouse units
            bot().waitTicks(2);
            double[] look = clientLook();
            double cone = dot(up, look);
            double step = planeAngleDeg(up, prev, look);
            swept += step;
            steps.append(String.format(java.util.Locale.ROOT,
                    "[turn%d cone=%.3f step=%.1f] ", i, cone, step));
            assertTrue("a horizontal mouse move must not move the aim off its cone about the deck "
                            + "normal (start=" + cone0 + " now=" + cone + ") :: " + steps,
                    Math.abs(cone - cone0) < 0.05);
            prev = look;
        }
        System.out.println("[crewcap] deck-look sweep=" + swept + " :: " + steps);
        assertTrue("four 30-degree mouse turns must sweep ~120 degrees about the deck normal, "
                + "not stall or wrap (swept=" + swept + ") :: " + steps,
                swept > 100.0 && swept < 140.0);

        // (2) Mouse still: roll the ship 25 degrees further. The aim must RIDE THE DECK - same
        // cone about the deck normal, and the world look turns WITH the ship instead of staying
        // world-glued. Pin the aim to world +Z first (perpendicular to the X roll axis), so the
        // expected world turn of a deck-glued aim is exactly the extra roll angle.
        exec("tp @a ~ ~ ~ 0 0");
        bot().waitTicks(10);
        double[] lookBefore = clientLook();
        double coneBefore = dot(up, lookBefore);
        double h2 = Math.toRadians(85.0) / 2.0;
        assertTrue("attitude hold must accept the second roll",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h2) + " " + Math.sin(h2) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(150);
        double[] up2 = shipUpFromInfo(shipInfo(bx, by, bz));
        double rolledBy = Math.toDegrees(Math.acos(clampUnit(dot(up, up2))));
        assertTrue("the ship must actually roll further for this leg to prove anything (rolled "
                + rolledBy + " deg more, upY " + up[1] + " -> " + up2[1] + ")", rolledBy > 15.0);
        assertTrue("the crew member must still be captured on the further-rolled deck: "
                + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"alreadyTracked\":true"));
        double[] lookAfter = clientLook();
        double coneAfter = dot(up2, lookAfter);
        double aimTurned = Math.toDegrees(Math.acos(clampUnit(dot(lookBefore, lookAfter))));
        System.out.println("[crewcap] deck-glue rolledBy=" + rolledBy + " aimTurned=" + aimTurned
                + " cone " + coneBefore + " -> " + coneAfter);
        assertTrue("with the mouse still, the aim must stay on its cone about the deck normal as "
                + "the ship rolls (cone " + coneBefore + " -> " + coneAfter + ")",
                Math.abs(coneAfter - coneBefore) < 0.06);
        assertTrue("with the mouse still, the world aim must TURN WITH the deck by the extra roll, "
                + "not stay world-glued (ship rolled " + rolledBy + " deg, aim turned "
                + aimTurned + ")", Math.abs(aimTurned - rolledBy) < 8.0);

        // One transform for the view and the aim: the camera the renderer was handed points where
        // the derived world look points (two independent code paths on the client).
        double camYaw = clientDouble(SHIP_CAMERA_CLASS, "shipCamYaw");
        double camPitch = clientDouble(SHIP_CAMERA_CLASS, "shipCamPitch");
        double playerYaw = bot().reportState().get("playerYaw").getAsDouble();
        double playerPitch = bot().reportState().get("playerPitch").getAsDouble();
        double yawDiff = Math.abs(wrap180(camYaw - playerYaw));
        System.out.println("[crewcap] cam=(" + camYaw + "," + camPitch + ") player=("
                + playerYaw + "," + playerPitch + ")");
        assertTrue("the rendered camera must point along the derived world aim (yaw " + camYaw
                + " vs " + playerYaw + ", pitch " + camPitch + " vs " + playerPitch + ")",
                yawDiff < 3.0 && Math.abs(camPitch - playerPitch) < 3.0);

        // (3) The MOVEMENT half of the same transform: on this near-vertical (~85 degree) deck,
        // holding the REAL forward key must walk the body along the deck heading the mouse
        // steers. This is the exact attitude where a walk basis built from the world-yaw
        // projection of a deck-glued aim degenerates (the world look sits near the pole) and
        // walking decouples from the keys.
        // Where the body stands on the deck varies run to run, so any single fixed heading can
        // face a wall 0.1 blocks away (seen live: ~0.10 blocks and a horizontal collision), and
        // legs walked in sequence drift the body toward an edge until it walks OFF the deck (seen
        // live: an 8.7-block "leg" that was really a fall after the capture released). So: anchor
        // at the settled open-deck spot, tp BACK to the anchor before every leg, turn to the
        // leg's heading with the REAL mouse (re-exercising the deck-relative turn after the tp's
        // re-seed), walk briefly, and count a leg only if the capture held through it and the
        // displacement is walking-sized. Judge the heading contract on the best VALID leg.
        double[] q85 = shipQuatFromInfo(shipInfo(bx, by, bz));
        double[] anchor = clientPos();
        double bestMag = -1.0, bestWalkedYaw = 0.0, bestHeldYaw = 0.0;
        StringBuilder legs = new StringBuilder();
        for (int dir = 0; dir < 4; dir++) {
            exec("tp @a " + anchor[0] + " " + anchor[1] + " " + anchor[2] + " 0 0");
            bot().waitTicks(10); // settle + deck-look re-seed from the tp's world aim
            if (dir > 0) {
                bot().turnLook(600f * dir, 0f); // dir * 90 degrees of deck yaw
                bot().waitTicks(2);
            }
            double heldDeckYaw = clientDouble(DECK_LOOK, "deckYawDeg");
            String infoW0 = shipInfo(bx, by, bz);
            double[] p0 = clientPos();
            double[] s0 = {readDouble(infoW0, POS_X), readDouble(infoW0, POS_Y),
                    readDouble(infoW0, POS_Z)};
            try {
                for (int i = 0; i < 8; i++) {
                    bot().holdKey(Keyboard.KEY_W); // re-asserted per tick against key-state churn
                    bot().waitTicks(1);
                }
            } finally {
                bot().releaseKey(Keyboard.KEY_W);
            }
            bot().waitTicks(4);
            double[] p1 = clientPos();
            String infoW1 = shipInfo(bx, by, bz);
            double[] s1 = {readDouble(infoW1, POS_X), readDouble(infoW1, POS_Y),
                    readDouble(infoW1, POS_Z)};
            boolean stillAboard = Boolean.parseBoolean(clientString(DECK_LOOK, "active"));
            // The walk displacement, with the ship's own drift removed, in the DECK frame.
            double[] walkWorld = {p1[0] - p0[0] - (s1[0] - s0[0]), p1[1] - p0[1] - (s1[1] - s0[1]),
                    p1[2] - p0[2] - (s1[2] - s0[2])};
            double[] walkDeck = rotateByConjugate(q85, walkWorld);
            double planeMag = Math.sqrt(walkDeck[0] * walkDeck[0] + walkDeck[2] * walkDeck[2]);
            double walkedYaw = Math.toDegrees(Math.atan2(-walkDeck[0], walkDeck[2]));
            // Walking-sized displacement only: a sub-walk leg hit a wall, an over-walk leg fell
            // off the deck - neither can falsify the HEADING contract.
            boolean valid = stillAboard && planeMag > 0.3 && planeMag < 2.5;
            legs.append(String.format(java.util.Locale.ROOT,
                    "[dir%d held=%.1f walked=%.1f mag=%.2f aboard=%b valid=%b] ", dir, heldDeckYaw,
                    walkedYaw, planeMag, stillAboard, valid));
            if (valid && planeMag > bestMag) {
                bestMag = planeMag;
                bestWalkedYaw = walkedYaw;
                bestHeldYaw = heldDeckYaw;
            }
        }
        System.out.println("[crewcap] deck-walk best mag=" + bestMag + " walked=" + bestWalkedYaw
                + " held=" + bestHeldYaw + " :: " + legs);
        assertTrue("holding W must walk the body a walking-sized distance along the deck, still "
                + "captured, in at least one of four headings :: " + legs, bestMag > 0.3);
        assertTrue("on a ~90-degree deck the walk direction must match the deck heading the "
                + "mouse steers (walked " + bestWalkedYaw + " deg, held " + bestHeldYaw
                + " deg) :: " + legs, Math.abs(wrap180(bestWalkedYaw - bestHeldYaw)) < 30.0);

        // (diag, print-only) Jump-smoothness discriminators on THIS actively attitude-holding
        // (hunting) ship: per-frame step statistics of the ABSOLUTE body path vs the path
        // RELATIVE to a fixed deck point. A smooth path has max ~ mean step; a tick-stepped one
        // has max >> mean. Feeds the open jump-stutter residual; no contract asserted here.
        exec("tp @a " + anchor[0] + " " + anchor[1] + " " + anchor[2] + " 0 0");
        bot().waitTicks(15);
        bot().invokeStaticInt(SHIP_CAMERA_CLASS, "resetStepWindow");
        try {
            for (int i = 0; i < 20; i++) {
                bot().holdKey(Keyboard.KEY_SPACE);
                bot().waitTicks(1);
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_SPACE);
        }
        bot().waitTicks(5);
        double absMax = clientDouble(SHIP_CAMERA_CLASS, "absStepMax");
        double absSum = clientDouble(SHIP_CAMERA_CLASS, "absStepSum");
        long absN = (long) clientDouble(SHIP_CAMERA_CLASS, "absStepCount");
        double relMax = clientDouble(SHIP_CAMERA_CLASS, "relStepMax");
        double relSum = clientDouble(SHIP_CAMERA_CLASS, "relStepSum");
        long relN = (long) clientDouble(SHIP_CAMERA_CLASS, "relStepCount");
        System.out.println(String.format(java.util.Locale.ROOT,
                "[crewcap] jump-steps abs(max=%.4f mean=%.4f n=%d ratio=%.1f) rel(max=%.4f "
                        + "mean=%.4f n=%d ratio=%.1f)",
                absMax, absN > 0 ? absSum / absN : -1, absN,
                absN > 0 && absSum > 0 ? absMax / (absSum / absN) : -1,
                relMax, relN > 0 ? relSum / relN : -1, relN,
                relN > 0 && relSum > 0 ? relMax / (relSum / relN) : -1));
    }

    private static final String DECK_LOOK = "zmaster587.advancedRocketry.client.DeckLook";
    private static final String SHIP_CAMERA_CLASS = "zmaster587.advancedRocketry.client.ShipFrameCamera";

    /** The client's own world look direction, from the rotation it reports. */
    private double[] clientLook() throws Exception {
        com.google.gson.JsonObject st = bot().reportState();
        double yaw = Math.toRadians(st.get("playerYaw").getAsDouble());
        double pitch = Math.toRadians(st.get("playerPitch").getAsDouble());
        return new double[]{-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch)};
    }

    /** The ship's attitude quat {w,x,y,z} from the server-side ship-info (the cross-side oracle). */
    private double[] shipQuatFromInfo(String info) {
        return new double[]{
                readDouble(info, Pattern.compile("\"qw\":(-?[0-9.E\\-]+)")),
                readDouble(info, Pattern.compile("\"qx\":(-?[0-9.E\\-]+)")),
                readDouble(info, Pattern.compile("\"qy\":(-?[0-9.E\\-]+)")),
                readDouble(info, Pattern.compile("\"qz\":(-?[0-9.E\\-]+)"))};
    }

    /** The ship's up axis in world coordinates, from the server-side ship-info quat (the oracle). */
    private double[] shipUpFromInfo(String info) {
        double[] q = shipQuatFromInfo(info);
        double qw = q[0], qx = q[1], qy = q[2], qz = q[3];
        return new double[]{
                2.0 * (qx * qy - qw * qz),
                1.0 - 2.0 * (qx * qx + qz * qz),
                2.0 * (qy * qz + qw * qx)};
    }

    /** Rotate {@code v} by the CONJUGATE of quat {@code q} (world direction into the ship frame). */
    private static double[] rotateByConjugate(double[] q, double[] v) {
        double w = q[0], x = -q[1], y = -q[2], z = -q[3];
        double xx = x * x, yy = y * y, zz = z * z;
        double xy = x * y, xz = x * z, yz = y * z;
        double wx = w * x, wy = w * y, wz = w * z;
        return new double[]{
                v[0] * (1 - 2 * (yy + zz)) + v[1] * 2 * (xy - wz) + v[2] * 2 * (xz + wy),
                v[0] * 2 * (xy + wz) + v[1] * (1 - 2 * (xx + zz)) + v[2] * 2 * (yz - wx),
                v[0] * 2 * (xz - wy) + v[1] * 2 * (yz + wx) + v[2] * (1 - 2 * (xx + yy))};
    }

    /** The client's own position, from what it reports. */
    private double[] clientPos() throws Exception {
        com.google.gson.JsonObject st = bot().reportState();
        return new double[]{st.get("playerX").getAsDouble(), st.get("playerY").getAsDouble(),
                st.get("playerZ").getAsDouble()};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double clampUnit(double v) {
        return v < -1.0 ? -1.0 : v > 1.0 ? 1.0 : v;
    }

    private static double wrap180(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    /** The unsigned angle (degrees) between two directions' projections into the plane
     *  perpendicular to {@code axis}. */
    private static double planeAngleDeg(double[] axis, double[] a, double[] b) {
        double[] pa = {a[0] - axis[0] * dot(axis, a), a[1] - axis[1] * dot(axis, a),
                a[2] - axis[2] * dot(axis, a)};
        double[] pb = {b[0] - axis[0] * dot(axis, b), b[1] - axis[1] * dot(axis, b),
                b[2] - axis[2] * dot(axis, b)};
        double na = Math.sqrt(dot(pa, pa)), nb = Math.sqrt(dot(pb, pb));
        if (na < 1.0E-9 || nb < 1.0E-9) {
            return 0.0;
        }
        return Math.toDegrees(Math.acos(clampUnit(dot(pa, pb) / (na * nb))));
    }

    /** Build the ship and sit the bot on its pilot seat; returns the ship's world position. */
    private double[] buildAndBoardShip(int bx, int by, int bz) throws Exception {
        double[] ship = buildShip(bx, by, bz);
        // Located INSIDE this scenario's own ship: `vs seat-mount <dim>` takes the first pilot seat
        // in the world's loaded-tile list with no position filter, which is unambiguous only while
        // the world holds exactly one ship. On a shared client it would mount a neighbour's.
        String seat = exec("artest vs find-seat 0 " + bx + " " + by + " " + bz);
        assertTrue("find-seat must locate the pilot seat INSIDE the ship built at this base ("
                + bx + "," + by + "," + bz + "): " + seat, seat.contains("\"seatFound\":true"));
        String mountInfo = exec("artest vs seat-mount-at 0 " + readInt(seat, SEAT_X) + " "
                + readInt(seat, SEAT_Y) + " " + readInt(seat, SEAT_Z));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount-at must report a dummy id: " + mountInfo, dm.find());
        assertTrue("bot must mount the seat dummy: " + mountInfo,
                exec("artest player mount-entity " + dm.group(1)).contains("\"mounted\":true"));
        bot().waitTicks(10);
        return ship;
    }

    private String clientString(String className, String field) throws Exception {
        return bot().readStaticField(className, field).get("value").getAsString();
    }

    private double clientDouble(String className, String field) throws Exception {
        return Double.parseDouble(clientString(className, field));
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /** Build a ship at this base and wait for it to load with the client present; returns its world pos. */
    private double[] buildShip(int bx, int by, int bz) throws Exception {
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        bot().waitTicks(10);

        int shipsBefore = count("ship-count-all");
        String assemble = assembleFixture(bx, by, bz);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));

        // Event-gated async-VS assembly barrier: the physics mod assembles on its own
        // thread and its queue lags behind a loaded machine, so AWAIT the SPAWNED stage (the queryable
        // ship count rising past the pre-assembly baseline) with a load-scaled ceiling + early exit,
        // instead of a fixed tick budget that reds a healthy spawn under concurrent-fork load.
        ClientPoll.Result<Integer> spawned = ClientPoll.until(bot()::waitTicks,
                () -> count("ship-count-all"), n -> n > shipsBefore, 5, 40);
        int all = spawned.value;
        assertTrue("assembly must create a NEW VS ship (was " + shipsBefore + ", now " + all + ")",
                all > shipsBefore);
        bot().waitTicks(40);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);

        // Await the ship LOADING near this base (managed + within range) — same event-gated barrier.
        ClientPoll.Result<double[]> loaded = ClientPoll.until(bot()::waitTicks,
                () -> {
                    String si = shipInfo(bx, by, bz);
                    if (!si.contains("\"managed\":true")) {
                        return null;
                    }
                    double[] c = {readDouble(si, POS_X), readDouble(si, POS_Y), readDouble(si, POS_Z)};
                    return distance(c, new double[]{bx, by, bz}) < 24.0 ? c : null;
                },
                w -> w != null, 5, 40);
        double[] where = loaded.value;
        assertTrue("the ship built at this base must LOAD with the client present (base " + bx + ","
                + by + "," + bz + ")", where != null);
        System.out.println("[crewcap] ship at (" + bx + "," + by + "," + bz + ") -> "
                + java.util.Arrays.toString(where));
        return where;
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    private String shipInfo(int bx, int by, int bz) throws Exception {
        return exec("artest vs ship-info 0 " + bx + " " + by + " " + bz
                + " " + SHIP_QUERY_RADIUS);
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

    private int readInt(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected an integer in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
