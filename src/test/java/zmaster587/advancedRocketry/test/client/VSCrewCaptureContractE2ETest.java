package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * The capture/release contract of the ship-frame crew (any-attitude crew contract C1/C3/C9), pinned
 * against a REAL CLIENT PLAYER — the subject that broke in the inverted-boarding playtest. Two
 * boundary behaviours that the world-AABB containment gate got wrong:
 *
 * <ul>
 *   <li><b>Jumping on the TOP deck keeps the capture (C3).</b> The hull's top surface sits at the
 *       ship's world-AABB ceiling; a jump apex from there crossed the old grown-box gate
 *       (`leftShipBox`) and the capture died MID-AIR — vanilla, blind to the subspace deck, then
 *       tunnelled the body through the whole ship. The stay region is measured in SUBSPACE with a
 *       real margin, so a jump must ride out and land back on the deck, still captured.</li>
 *   <li><b>A player walking on world TERRAIN near a ship is never captured (C1/C9).</b> A ground
 *       position mapped through a parked ship's transform can alias onto a subspace block, and the
 *       old first-contact gate then captured a walker who stood on plain ground beside the hull
 *       (the playtest's "entered the ship transform at a random place"). Terra firma always keeps
 *       world-frame movement.</li>
 * </ul>
 *
 * <p>Gated on real VS — run with {@code -PwithVS}. Each test builds its own ship at its own base so
 * a ship one leaves behind cannot poison the next.</p>
 */
public class VSCrewCaptureContractE2ETest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-deck";

    // ---- C3: a jump from the top deck must not release the capture ------------------------------

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

    // ---- C1/C9: terra firma near a ship never captures ------------------------------------------

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
        // The contract (C6): a still crew member on a held, stationary deck STAYS PUT - no sideways
        // drag - and his capture does not churn.
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
        double liftedY = startY;
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int i = 0; i < 200 && liftedY - startY < 3.0; i++) {
                bot().waitTicks(2);
                liftedY = readDouble(shipInfo(bx, by, bz), POS_Y);
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
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
        double liftedY = startY;
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int i = 0; i < 200 && liftedY - startY < 3.0; i++) {
                bot().waitTicks(2);
                liftedY = readDouble(shipInfo(bx, by, bz), POS_Y);
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
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

    /** Build the ship and sit the bot on its pilot seat; returns the ship's world position. */
    private double[] buildAndBoardShip(int bx, int by, int bz) throws Exception {
        double[] ship = buildShip(bx, by, bz);
        String mountInfo = exec("artest vs seat-mount 0");
        assertTrue("seat-mount must find the pilot seat: " + mountInfo,
                mountInfo.contains("\"seatFound\":true"));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount must report a dummy id: " + mountInfo, dm.find());
        assertTrue("bot must mount the seat dummy",
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

        int all = shipsBefore;
        for (int i = 0; i < 40 && all <= shipsBefore; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly must create a NEW VS ship (was " + shipsBefore + ", now " + all + ")",
                all > shipsBefore);
        bot().waitTicks(40);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);

        String info = "";
        double[] where = null;
        for (int i = 0; i < 40 && where == null; i++) {
            bot().waitTicks(5);
            info = shipInfo(bx, by, bz);
            if (!info.contains("\"managed\":true")) {
                continue;
            }
            double[] candidate = {readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z)};
            if (distance(candidate, new double[]{bx, by, bz}) < 24.0) {
                where = candidate;
            }
        }
        assertTrue("the ship built at this base must LOAD with the client present; nearest was: " + info,
                where != null);
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
        return exec("artest vs ship-info 0 " + bx + " " + by + " " + bz);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
