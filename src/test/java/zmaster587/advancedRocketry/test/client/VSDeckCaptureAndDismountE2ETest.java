package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * The two open tier-2 playtest bugs, pinned against a REAL CLIENT PLAYER on a REAL assembled ship -
 * the subject that can actually exhibit them. Both were reported from a hands-on playtest and neither
 * is reproducible by the earlier suite, which read an armour stand's position through a SERVER probe:
 * a server-only body cannot fall through a deck on the client, and a probe cannot see where the client
 * renders the player. This class drives and observes the client, so it can.
 *
 * <ul>
 *   <li><b>A walking client player on a GROUNDED ship's deck stays on it, not through it.</b> The
 *       maintainer stood on a docked ship and fell through the deck. The subject here is the bot
 *       itself - a walking client whose own client resolves its movement - and the observation is the
 *       Y its client renders, cross-checked against where the server holds it (the honest oracle).</li>
 *   <li><b>Standing up from the pilot seat while hovering keeps the ship up and the pilot aboard.</b>
 *       The maintainer hovered, stood up with Shift, and both fell: the ship dropped and he was left
 *       in the world. The ship's hold is driven by live pilot input, so it dies at dismount, and the
 *       dismounted player is handed to a capture path at the exact moment the deck starts falling.</li>
 * </ul>
 *
 * <p>Gated on real VS - run with {@code -PwithVS}. Each test builds its own ship at its own base so a
 * ship one leaves behind cannot poison the next. Both assert the DESIRED contract, so both are RED
 * until the station-keeping (hover survives dismount) and capture-handoff fixes land - they are the
 * repro, written where a client e2e belongs instead of a manual playtest.</p>
 */
public class VSDeckCaptureAndDismountE2ETest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern VEL_Y = Pattern.compile("\"velY\":(-?[0-9.E\\-]+)");
    private static final Pattern PLAYER_Y = Pattern.compile("\"playerY\":(-?[0-9.E\\-]+)");
    private static final Pattern OBSTACLES = Pattern.compile("\"shipSupportObstacles\":(-?\\d+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";

    // ---- Bug: a walking client player on a grounded deck falls through it -----------------------

    @Test
    public void aRealClientPlayerOnAGroundedDeckStaysOnItInsteadOfFallingThrough() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 3620, by = 64, bz = 3620;

        // Grounded on purpose: a freshly assembled ship has physics disabled, so it rests where it was
        // built. Its world AABB spans from the deck down to the keel and overlaps the terrain beneath -
        // the exact overlap the playtest fell through - and the deck sits several blocks above the ground,
        // so a fall-through is an unmistakable multi-block drop, not a one-block ambiguity.
        double[] ship = buildShip(bx, by, bz);

        // The subject is the REAL client player. Drop the bot onto the deck and let its OWN client
        // resolve the landing (this is the thing that breaks; an armour stand read via a server probe
        // is not). Mirrors the crew test's drop-and-settle.
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);

        // Server oracle: does the server capture the standing player on the deck at all, and is the deck
        // solid under his feet in the ship frame? deck-capture prints the whole handles() decision.
        String server = exec("artest vs player-ship-data");
        String capture = exec("artest vs deck-capture");
        double serverY = readDouble(server, PLAYER_Y);
        System.out.println("[deckcap] grounded server=" + server);
        System.out.println("[deckcap] grounded capture=" + capture);
        assertTrue("server must recognise the client player as aboard the grounded ship: " + server,
                server.contains("\"shipLoaded\":true"));
        assertTrue("server must resolve the player in the ship frame, not hand him to vanilla: " + capture,
                capture.contains("\"verdict\":true"));
        assertTrue("the deck must be solid under his feet in the ship frame (>0), else he falls "
                + "through: " + capture, readInt(capture, OBSTACLES) > 0);
        assertTrue("a client player standing on the deck must be on the ground: " + server,
                server.contains("\"playerOnGround\":true"));

        // Client observation: where does the player's OWN client render him? A client fall-through
        // leaves his client Y well below where the server is holding him on the deck.
        double clientY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[deckcap] grounded serverY=" + serverY + " clientY=" + clientY);
        assertTrue("the client must render the player ON the deck where the server holds him, not "
                + "fallen through it: serverY=" + serverY + " clientY=" + clientY,
                Math.abs(clientY - serverY) < 2.0);

        // And he must not keep sinking through it over time.
        bot().waitTicks(60);
        double clientYLater = bot().reportState().get("playerY").getAsDouble();
        assertTrue("the client player must stay on the deck, not sink through it: " + clientY + " -> "
                + clientYLater, clientY - clientYLater < 1.5);
    }

    // ---- Bug: dismounting mid-hover drops the ship and the pilot --------------------------------

    @Test
    public void standingUpWhileHoveringKeepsTheShipUpAndThePilotOnTheDeck() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 3720, by = 64, bz = 3720;

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20); // let the seated idle pilot's hold stabilise the ship

        // Lift into a real hover with the pilot's own vertical-up key.
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
        assertTrue("the pilot must be able to lift the ship off the ground: " + startY + " -> " + liftedY,
                liftedY - startY > 2.0);
        bot().waitTicks(10);

        double shipYPre = readDouble(shipInfo(bx, by, bz), POS_Y);

        // Dismount exactly as the maintainer did: the real sneak key. (While seated it also feeds the
        // flight brake, but a held sneak still triggers vanilla's dismount.) Confirm on the CLIENT that
        // the player left the seat; fall back to the server dismount only if the key path did not fire.
        boolean dismounted = false;
        bot().holdKey(Keyboard.KEY_LSHIFT);
        for (int i = 0; i < 40 && !dismounted; i++) {
            bot().waitTicks(2);
            dismounted = !bot().reportRidingEntity().get("riding").getAsBoolean();
        }
        bot().releaseKey(Keyboard.KEY_LSHIFT);
        if (!dismounted) {
            System.out.println("[deckcap] sneak key did not dismount; using server dismount");
            exec("artest player dismount");
            bot().waitTicks(5);
            dismounted = !bot().reportRidingEntity().get("riding").getAsBoolean();
        }
        assertTrue("the pilot must actually leave the seat", dismounted);

        // Let the now-unmanned ship reveal whether it holds or falls.
        bot().waitTicks(40);
        String info = shipInfo(bx, by, bz);
        double shipYPost = readDouble(info, POS_Y);
        double velYPost = readDouble(info, VEL_Y);
        String server = exec("artest vs player-ship-data");
        String capture = exec("artest vs deck-capture");
        double serverY = readDouble(server, PLAYER_Y);
        double clientY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[deckcap] dismount shipY " + shipYPre + "->" + shipYPost + " velYPost="
                + velYPost + " serverY=" + serverY + " clientY=" + clientY);
        System.out.println("[deckcap] dismount capture=" + capture);

        // The ship must keep hovering, not drop, when the pilot stands up.
        assertTrue("a hovering ship must not fall when the pilot dismounts: it dropped from " + shipYPre
                + " to " + shipYPost, shipYPre - shipYPost < 2.0);
        assertTrue("a hovering ship must not start falling when the pilot dismounts (velY=" + velYPost
                + ")", velYPost > -0.5);

        // The pilot must stay aboard: resolved on the deck in the ship frame, and rendered there by his
        // own client - not dropped into the world.
        assertTrue("the dismounted pilot must be resolved on the deck, not handed to vanilla: " + capture,
                capture.contains("\"verdict\":true") && readInt(capture, OBSTACLES) > 0);
        assertTrue("the client must render the dismounted pilot on the deck where the server holds him: "
                + "serverY=" + serverY + " clientY=" + clientY, Math.abs(clientY - serverY) < 2.5);

        exec("artest player dismount"); // clean state for any following test
    }

    // ---- Bug: a ship reloaded from a save drops a walking client player through its deck ---------

    @Test
    public void aClientPlayerReturningToASavedShipStandsOnItsDeckInsteadOfFallingThrough() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 3820, by = 64, bz = 3820;

        // The maintainer's "old ships" are ones from a PRIOR SESSION - assembled, the world saved and
        // unloaded, then loaded again. A freshly assembled ship (the grounded test above) is already
        // loaded and holds him fine; a ship loaded from disk starts in the registry, UNLOADED, until a
        // player brings it back. This drives that path in-harness: build, walk away until the ship's
        // chunks unload (VS saves it to the registry), then return to its deck.
        double[] ship = buildShip(bx, by, bz);
        assertTrue("the ship must be loaded before we unload it", count("ship-count") >= 1);

        // Walk away far enough that nothing tickets the ship's chunks; the harness warmup holds no
        // ticket, so idle chunks unload. Belt and braces: drop any tickets a prior step left.
        exec("artest chunk release-all");
        exec("tp @a " + (bx + 4000) + " 120 " + (bz + 4000) + " 0 0");
        int loaded = 1;
        for (int i = 0; i < 80 && loaded > 0; i++) {
            bot().waitTicks(10);
            loaded = count("ship-count");
        }
        // If the harness will not unload the ship, we cannot honestly exercise the reload path this way -
        // SKIP rather than pass vacuously (a false green is worse than no test). This flags that the
        // reload axis needs a different mechanism (or a real session restart), which is a real result.
        Assume.assumeTrue("harness did not unload the ship within the budget (loaded=" + loaded
                + "); the reload axis needs another mechanism", loaded == 0);
        assertTrue("the unloaded ship must survive in the registry (a saved ship): "
                + exec("artest vs ship-count-all 0"), count("ship-count-all") >= 1);

        // Return to the ship exactly as re-entering a docked ship from a saved world, and stand on it.
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);

        String server = exec("artest vs player-ship-data");
        String capture = exec("artest vs deck-capture");
        double serverY = readDouble(server, PLAYER_Y);
        double clientY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[deckcap] reloaded server=" + server);
        System.out.println("[deckcap] reloaded capture=" + capture);
        System.out.println("[deckcap] reloaded serverY=" + serverY + " clientY=" + clientY
                + " loadedNow=" + count("ship-count"));

        assertTrue("a reloaded ship must come back when the player returns to its deck: " + server,
                server.contains("\"shipLoaded\":true"));
        assertTrue("the player must be resolved on the reloaded deck, not fall through it: " + capture,
                capture.contains("\"verdict\":true") && readInt(capture, OBSTACLES) > 0);
        assertTrue("the client must render him ON the reloaded deck, not fallen through: serverY="
                + serverY + " clientY=" + clientY, Math.abs(clientY - serverY) < 2.0);
    }

    // ---- Bug: flying into a ship's airspace hijacks a walking player's camera ------------------

    private static final String SHIP_CAMERA = "zmaster587.advancedRocketry.client.ShipFrameCamera";

    @Test
    public void flyingIntoAShipsAirspaceWithoutStandingOnItDoesNotHijackTheCamera() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 3920, by = 64, bz = 3920;

        double[] ship = buildShip(bx, by, bz);

        // Roll the ship so its world AABB spans a large air volume with a tilted deck - the airspace you
        // cross flying up to a ship. Attitude hold does it with no pilot aboard.
        double h = Math.toRadians(45.0) / 2.0;
        assertTrue("attitude hold must accept the roll",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " 0.0 0.0 " + Math.sin(h)).contains("\"commanded\":true"));
        bot().waitTicks(120);
        String info = shipInfo(bx, by, bz);
        double sx = readDouble(info, POS_X), sy = readDouble(info, POS_Y), sz = readDouble(info, POS_Z);

        // NEGATIVE (the bug): a player who has NEVER stood on this deck flies into its airspace, off the
        // deck. He comes straight from far, so nothing has captured him (his ship-frame movement state is
        // empty). His view must stay his own - not snap to the tilted deck's horizon.
        exec("tp @a " + (sx + 200) + " 120 " + (sz + 200) + " 0 0");
        bot().waitTicks(10);
        exec("tp @a " + sx + " " + (sy + 3) + " " + sz + " 0 0");
        bot().waitTicks(1); // one render pass at the off-deck point before he can fall onto the deck
        String flyInCap = exec("artest vs deck-capture");
        boolean inAABB = flyInCap.contains("\"aboardByContainment\":true");
        boolean onShipBlock = flyInCap.contains("\"supportedByShip\":true");
        boolean tracked = flyInCap.contains("\"alreadyTracked\":true");
        boolean flyInCam = Boolean.parseBoolean(clientString(SHIP_CAMERA, "shipCamActive"));
        double flyInRoll = clientDouble(SHIP_CAMERA, "shipCamRoll");
        System.out.println("[deckcap] cam fly-in active=" + flyInCam + " roll=" + flyInRoll + " inAABB="
                + inAABB + " onShipBlock=" + onShipBlock + " tracked=" + tracked + " cap=" + flyInCap);
        assertTrue("setup: the fly-in point must be inside the ship's AABB, off any deck block, with the "
                + "player not already resolved on it: " + flyInCap, inAABB && !onShipBlock && !tracked);
        assertTrue("a player flying through a ship's airspace, not standing on its deck, must keep his "
                + "own view; the deck camera must not hijack it (active=" + flyInCam + " roll="
                + flyInRoll + ")", !flyInCam);

        // POSITIVE control: level the ship and land him ON the deck. Now the deck camera SHOULD engage -
        // so the negative above is a real on-deck/off-deck discrimination, not the camera never firing.
        assertTrue("attitude hold must accept levelling",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " 1.0 0.0 0.0 0.0")
                        .contains("\"commanded\":true"));
        bot().waitTicks(120);
        String lvl = shipInfo(bx, by, bz);
        exec("tp @a " + readDouble(lvl, POS_X) + " " + (readDouble(lvl, POS_Y) + 5) + " "
                + readDouble(lvl, POS_Z) + " 0 0");
        boolean onDeckCam = false;
        for (int i = 0; i < 40 && !onDeckCam; i++) {
            bot().waitTicks(5);
            onDeckCam = Boolean.parseBoolean(clientString(SHIP_CAMERA, "shipCamActive"));
        }
        System.out.println("[deckcap] cam on-deck active=" + onDeckCam
                + " cap=" + exec("artest vs deck-capture"));
        assertTrue("a player actually standing on the deck must get the deck camera", onDeckCam);
    }

    // ---- Bug: a hovering ship falls (and tumbles inverted) after a world reload -----------------

    @Test
    public void aHoveringShipKeepsHoveringAcrossAReloadInsteadOfFalling() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 4320, by = 64, bz = 4320;

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);

        // Fly it into a hover, then stand up: it is now an unmanned, station-keeping, hovering ship -
        // exactly the state a saved hovering ship is in on disk.
        double startY = readDouble(shipInfo(bx, by, bz), POS_Y);
        double liftedY = startY;
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_R);
        try {
            for (int i = 0; i < 200 && liftedY - startY < 3.0; i++) {
                bot().waitTicks(2);
                liftedY = readDouble(shipInfo(bx, by, bz), POS_Y);
            }
        } finally {
            bot().releaseKey(org.lwjgl.input.Keyboard.KEY_R);
        }
        assertTrue("the pilot must lift the ship into a hover: " + startY + " -> " + liftedY,
                liftedY - startY > 2.0);
        exec("artest player dismount");
        bot().waitTicks(40);
        double hoverY = readDouble(shipInfo(bx, by, bz), POS_Y);
        assertTrue("the unmanned ship must still be hovering off the ground: " + hoverY,
                hoverY - startY > 1.0);

        // Simulate a world reload: unload the ship (its flight-computer tile is written to NBT, its LIVE
        // attitudeReference lost) and load it again. The persisted station-keeping flag must bring the
        // hold back so the ship does NOT fall - the live playtest's "hovering ship survived a restart,
        // then fell and flipped".
        exec("artest chunk release-all");
        exec("tp @a " + (bx + 4000) + " 120 " + (bz + 4000) + " 0 0");
        int loaded = 1;
        for (int i = 0; i < 80 && loaded > 0; i++) {
            bot().waitTicks(10);
            loaded = count("ship-count");
        }
        Assume.assumeTrue("harness did not unload the ship (loaded=" + loaded + "); cannot exercise the "
                + "reload path", loaded == 0);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        for (int i = 0; i < 40 && count("ship-count") < 1; i++) {
            bot().waitTicks(5);
        }
        bot().waitTicks(80); // give a ship that lost its hold time to visibly fall

        double afterY = readDouble(shipInfo(bx, by, bz), POS_Y);
        System.out.println("[deckcap] reload-hover startY=" + startY + " hoverY=" + hoverY
                + " afterReloadY=" + afterY);
        assertTrue("a hovering ship must KEEP hovering across a reload, not fall out of the sky: it was "
                + "at " + hoverY + " and after reload is at " + afterY, hoverY - afterY < 3.0);
    }

    // ---- Bug: camera/capture instability on a steeply tilted, HELD deck ------------------------

    @Test
    public void aClientPlayerRidingASteeplyTiltedDeckHasStableCaptureAndCamera() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 4120, by = 64, bz = 4120;

        double[] ship = buildShip(bx, by, bz);

        // Stand the client player on the UPRIGHT deck first (capture works there), then tilt the ship
        // and hold it. The player rides the deck; the camera and capture must stay STABLE while the ship
        // is stationary at a steep angle - not jitter frame-to-frame (RC-2 Euler pole) nor flicker the
        // capture (which alternates gravity and drags the body "back and forth through the deck").
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("client must be captured on the upright deck first: " + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"verdict\":true"));

        double h = Math.toRadians(90.0) / 2.0; // 90deg roll about the nose (+Z): deck on its side
        assertTrue("attitude hold must accept the tilt",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " 0.0 0.0 " + Math.sin(h)).contains("\"commanded\":true"));
        bot().waitTicks(160); // slew to the tilt and settle - the ship is now HELD stationary

        // Sample across frames while the ship is stationary. Any variation is instability, not motion.
        int n = 5;
        double rollMin = Double.MAX_VALUE, rollMax = -Double.MAX_VALUE;
        double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        int captured = 0, camOn = 0;
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < n; i++) {
            bot().waitTicks(4);
            boolean active = Boolean.parseBoolean(clientString(SHIP_CAMERA, "shipCamActive"));
            double roll = clientDouble(SHIP_CAMERA, "shipCamRoll");
            boolean verdict = exec("artest vs deck-capture").contains("\"verdict\":true");
            double y = bot().reportState().get("playerY").getAsDouble();
            if (active) { camOn++; rollMin = Math.min(rollMin, roll); rollMax = Math.max(rollMax, roll); }
            if (verdict) captured++;
            yMin = Math.min(yMin, y); yMax = Math.max(yMax, y);
            trace.append(String.format("[%d act=%b roll=%.1f verd=%b y=%.2f] ", i, active, roll, verdict, y));
        }
        double rollJitter = camOn > 0 ? rollMax - rollMin : 0.0;
        double yOsc = yMax - yMin;
        System.out.println("[deckcap] tilted-stability n=" + n + " captured=" + captured + " camOn=" + camOn
                + " rollJitter=" + rollJitter + " yOsc=" + yOsc + " :: " + trace);

        assertTrue("capture must stay STABLE on a held tilted deck, not flicker (captured " + captured
                + "/" + n + "): " + trace, captured == n);
        assertTrue("the deck camera must stay engaged on a held tilted deck (camOn " + camOn + "/" + n
                + "): " + trace, camOn == n);
        assertTrue("the levelled camera roll must be STABLE while the ship is stationary, not jitter at "
                + "the Euler pole (jitter=" + rollJitter + " deg): " + trace, rollJitter < 5.0);
        assertTrue("the client player must not be dragged through the deck (Y oscillation=" + yOsc
                + "): " + trace, yOsc < 1.0);
    }

    // ---- Bug: coordinate transforms break at extreme (inverted) attitudes ----------------------

    @Test
    public void anInvertedShipsMovementAndCameraFramesStayConsistent() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 4020, by = 64, bz = 4020;

        double[] ship = buildShip(bx, by, bz);

        // Flip the ship nearly upside-down: a 160-degree roll about its nose (+Z) - past inverted, but
        // shy of the exact 180 axis-angle singularity so the controller converges cleanly. Quaternion
        // (w,x,y,z) = (cos80, 0, 0, sin80). This is the regime the playtest saw break.
        assertTrue("attitude hold must accept the flip",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " 0.17365 0.0 0.0 0.98481")
                        .contains("\"commanded\":true"));
        bot().waitTicks(200); // slew all the way over and settle

        String info = shipInfo(bx, by, bz);
        double sx = readDouble(info, POS_X), sy = readDouble(info, POS_Y), sz = readDouble(info, POS_Z);
        exec("tp @a " + sx + " " + (sy + 1) + " " + sz + " 0 0"); // inside the AABB so the probe resolves
        bot().waitTicks(2);

        String tc = exec("artest vs ship-frame-check");
        System.out.println("[deckcap] inverted transform-check=" + tc);
        // The attitude controller converges shy of a full 180 (axis-angle is singular there), settling
        // near 135deg - deck-up well past horizontal and pointing downward. That is a strongly non-trivial
        // attitude, which is all the consistency check needs.
        assertTrue("ship must be strongly inverted (deck-up points well below horizontal): " + tc,
                readDouble(tc, Pattern.compile("\"upQuatY\":(-?[0-9.E\\-]+)")) < -0.5);

        // THE decisive check: the MOVEMENT frame (VS vector rotate, used by ShipFrameTravel) and the
        // CAMERA/gravity frame (the attitude quaternion) must describe the SAME rotation. A disagreement
        // here is the root of "the inverted ship drags me through the deck while the camera never turns
        // over" - movement resolving in one frame, the camera reading another.
        double upDis = readDouble(tc, Pattern.compile("\"upDisagreement\":(-?[0-9.E\\-]+)"));
        double fwdDis = readDouble(tc, Pattern.compile("\"fwdDisagreement\":(-?[0-9.E\\-]+)"));
        double posRt = readDouble(tc, Pattern.compile("\"posRoundTripErr\":(-?[0-9.E\\-]+)"));
        double rotRt = readDouble(tc, Pattern.compile("\"rotRoundTripErr\":(-?[0-9.E\\-]+)"));
        System.out.println("[deckcap] inverted upDis=" + upDis + " fwdDis=" + fwdDis
                + " posRt=" + posRt + " rotRt=" + rotRt);
        assertTrue("movement rotate and camera quaternion must agree on ship-up (disagree=" + upDis
                + "): " + tc, upDis < 0.02);
        assertTrue("movement rotate and camera quaternion must agree on ship-forward (disagree=" + fwdDis
                + ")", fwdDis < 0.02);
        assertTrue("world<->subspace position round-trip must be exact (err=" + posRt + ")", posRt < 0.02);
        assertTrue("world<->subspace rotation round-trip must be exact (err=" + rotRt + ")", rotRt < 0.02);
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    private String clientString(String className, String field) throws Exception {
        return bot().readStaticField(className, field).get("value").getAsString();
    }

    private double clientDouble(String className, String field) throws Exception {
        return Double.parseDouble(clientString(className, field));
    }

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
        System.out.println("[deckcap] ship at (" + bx + "," + by + "," + bz + ") -> "
                + java.util.Arrays.toString(where));
        return where;
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
        bot().waitTicks(10); // let the mount replicate and the client recognise the pilot seat
        return ship;
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
