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
 *
 * <h2>One client for all twelve scenarios</h2>
 *
 * <p>Measured 2026-08-07 over the whole ship client tier: these twelve scenarios cost <b>19.4
 * minutes across twelve client boots</b> — 97 s each, of which the body is a small minority. They
 * were the tier's wall-clock floor. Sharing one harness is what removes it, and the base coordinates
 * below are unchanged: the scenarios already stood 100 blocks apart, which is a plot allocator by
 * hand, and each one's ground is the ground its green runs were taken on.</p>
 *
 * <p>Two gates had to become scoped to survive a shared world, both marked at their call sites: the
 * pilot seat is now located INSIDE this scenario's own ship ({@code vs find-seat} at its base)
 * instead of taking the first pilot seat in the world, and "has the ship unloaded?" reads this
 * scenario's own base rather than a whole-dimension ship count that a neighbour's ship answers.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSDeckCaptureAndDismountE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-deck-capture";
    }

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
    private static final Pattern SEAT_X = Pattern.compile("\"seatX\":(-?\\d+)");
    private static final Pattern SEAT_Y = Pattern.compile("\"seatY\":(-?\\d+)");
    private static final Pattern SEAT_Z = Pattern.compile("\"seatZ\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-deck";

    /** Ships in dim 0's registry immediately after {@link #buildShip} created this scenario's. */
    private int shipsInRegistryAfterBuild;

    /**
     * THIS scenario's ship, by identity — captured by {@link #buildShip} at the one moment the base
     * provably holds no other, and the address every later question uses.
     *
     * <p>Every question here used to be "the ship nearest my base, within a radius". That radius is a
     * mitigation and not an identity: these scenarios deliberately tumble, invert and hover their
     * ship, and on a shared client the neighbour built by another scenario is a candidate the whole
     * time. An id has no distance term to be wrong about.</p>
     */
    private String scenarioShipId;

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
        double startY = readDouble(shipInfo(), POS_Y);
        bot().holdKey(Keyboard.KEY_R);
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated hover-lift (load-scaled ceiling + early exit): a fixed 200-iteration budget
            // under-lifts a frame-starved client under concurrent-fork load and reds a healthy climb.
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> readDouble(shipInfo(), POS_Y),
                    y -> y - startY >= 3.0, 2, 200);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double liftedY = lift.value;
        assertTrue("the pilot must be able to lift the ship off the ground: " + startY + " -> " + liftedY,
                liftedY - startY > 2.0);
        bot().waitTicks(10);

        double shipYPre = readDouble(shipInfo(), POS_Y);

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
        String info = shipInfo();
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
        int registryAfterBuild = shipsInRegistryAfterBuild;
        assertTrue("the ship must be loaded before we unload it", shipLoadedAt(bx, by, bz));

        // Walk away far enough that nothing tickets the ship's chunks; the harness warmup holds no
        // ticket, so idle chunks unload. Belt and braces: drop any tickets a prior step left.
        exec("artest chunk release-all");
        exec("tp @a " + (bx + 4000) + " 120 " + (bz + 4000) + " 0 0");
        // Scoped to THIS ship: a whole-dimension "no ship is loaded" gate would wait on every
        // neighbour scenario's ship as well, and would answer about theirs rather than ours.
        boolean stillLoaded = true;
        for (int i = 0; i < 80 && stillLoaded; i++) {
            bot().waitTicks(10);
            stillLoaded = shipLoadedAt(bx, by, bz);
        }
        // If the harness will not unload the ship, we cannot honestly exercise the reload path this way -
        // SKIP rather than pass vacuously (a false green is worse than no test). This flags that the
        // reload axis needs a different mechanism (or a real session restart), which is a real result.
        Assume.assumeTrue("harness did not unload this scenario's ship within the budget (still"
                + " loaded at " + bx + "," + by + "," + bz + "); the reload axis needs another"
                + " mechanism", !stillLoaded);
        // The registry must not have LOST it. Compared against the count taken right after this
        // scenario's own assembly, so it stays a statement about this ship on a shared world.
        assertTrue("the unloaded ship must survive in the registry (a saved ship): "
                        + exec("artest vs ship-count-all 0") + " after build it was "
                        + registryAfterBuild,
                count("ship-count-all") >= registryAfterBuild);

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
                + " loadedNow=" + shipLoadedAt(bx, by, bz));

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
                exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " 0.0 0.0 " + Math.sin(h)).contains("\"commanded\":true"));
        bot().waitTicks(120);
        String info = shipInfo();
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
        // Being TRACKED is not by itself a broken arrangement here, and demanding otherwise is what
        // made this scenario intermittent. A body that touches the outer hull is taken into HULL-STAND
        // mode - which is tracked, and is precisely the mode that keeps world gravity, world movement
        // and the WORLD camera - and the fly-in point sits a few blocks over a deck rolled 45deg, so
        // whether that contact happens inside the single tick this samples is a race with the server's
        // own capture pass. Measured 2026-08-14, one red in a four-run sweep:
        // alreadyTracked:true hullStand:true supportedByShip:false shipSupportObstacles:0 - a
        // hull-stand body, asserted against as though it were a deck capture.
        // What must NOT be true is the thing this leg is about: he must not be ABOARD on a deck, since
        // only that engages the deck camera. That is tracked-and-not-hull-standing.
        boolean hullStand = flyInCap.contains("\"hullStand\":true");
        boolean aboardOnADeck = tracked && !hullStand;
        boolean flyInCam = Boolean.parseBoolean(clientString(SHIP_CAMERA, "shipCamActive"));
        double flyInRoll = clientDouble(SHIP_CAMERA, "shipCamRoll");
        System.out.println("[deckcap] cam fly-in active=" + flyInCam + " roll=" + flyInRoll + " inAABB="
                + inAABB + " onShipBlock=" + onShipBlock + " tracked=" + tracked + " hullStand="
                + hullStand + " cap=" + flyInCap);
        assertTrue("setup: the fly-in point must be inside the ship's AABB, off any deck block, and the "
                + "player must not be ABOARD on one (hull-stand is allowed - it keeps world-frame "
                + "semantics, which is what this leg asserts): " + flyInCap,
                inAABB && !onShipBlock && !aboardOnADeck);
        assertTrue("a player flying through a ship's airspace, not standing on its deck, must keep his "
                + "own view; the deck camera must not hijack it (active=" + flyInCam + " roll="
                + flyInRoll + ")", !flyInCam);

        // POSITIVE control: level the ship and land him ON the deck. Now the deck camera SHOULD engage -
        // so the negative above is a real on-deck/off-deck discrimination, not the camera never firing.
        assertTrue("attitude hold must accept levelling",
                exec("artest vs point-by-id 0 " + scenarioShipId + " 1.0 0.0 0.0 0.0")
                        .contains("\"commanded\":true"));
        bot().waitTicks(120);
        String lvl = shipInfo();
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
        double startY = readDouble(shipInfo(), POS_Y);
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_R);
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated hover-lift (load-scaled ceiling + early exit): a fixed 200-iteration budget
            // under-lifts a frame-starved client under concurrent-fork load and reds a healthy climb.
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> readDouble(shipInfo(), POS_Y),
                    y -> y - startY >= 3.0, 2, 200);
        } finally {
            bot().releaseKey(org.lwjgl.input.Keyboard.KEY_R);
        }
        double liftedY = lift.value;
        assertTrue("the pilot must lift the ship into a hover: " + startY + " -> " + liftedY,
                liftedY - startY > 2.0);
        exec("artest player dismount");
        bot().waitTicks(40);
        double hoverY = readDouble(shipInfo(), POS_Y);
        assertTrue("the unmanned ship must still be hovering off the ground: " + hoverY,
                hoverY - startY > 1.0);

        // Simulate a world reload: unload the ship (its flight-computer tile is written to NBT, its LIVE
        // attitudeReference lost) and load it again. The persisted station-keeping flag must bring the
        // hold back so the ship does NOT fall - the live playtest's "hovering ship survived a restart,
        // then fell and flipped".
        exec("artest chunk release-all");
        exec("tp @a " + (bx + 4000) + " 120 " + (bz + 4000) + " 0 0");
        // Scoped to THIS ship, like the saved-ship scenario above: on a shared world a whole-dimension
        // count answers about whichever neighbour's ship is loaded.
        boolean stillLoaded = true;
        for (int i = 0; i < 80 && stillLoaded; i++) {
            bot().waitTicks(10);
            stillLoaded = shipLoadedAt(bx, by, bz);
        }
        Assume.assumeTrue("harness did not unload this scenario's ship at " + bx + "," + by + ","
                + bz + "; cannot exercise the reload path", !stillLoaded);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        // Event-gated VS reload barrier (load-scaled ceiling + early exit): a fixed 40-iteration budget
        // can miss a slow async reload under concurrent-fork load and hard-fail the downstream parse.
        ClientPoll.until(bot()::waitTicks, () -> shipLoadedAt(bx, by, bz) ? 1 : 0, n -> n >= 1, 5, 40);
        bot().waitTicks(80); // give a ship that lost its hold time to visibly fall

        double afterY = readDouble(shipInfo(), POS_Y);
        System.out.println("[deckcap] reload-hover startY=" + startY + " hoverY=" + hoverY
                + " afterReloadY=" + afterY);
        assertTrue("a hovering ship must KEEP hovering across a reload, not fall out of the sky: it was "
                + "at " + hoverY + " and after reload is at " + afterY, hoverY - afterY < 3.0);
    }

    // ---- Bug: entering / leaving the seat on a truly INVERTED ship (the maintainer's live scenario) --

    private static final String KEY_BINDINGS = "zmaster587.advancedRocketry.client.KeyBindings";
    private static final Pattern OMEGA = Pattern.compile("\"omega\":(-?[0-9.E\\-]+)");
    private static final Pattern RESOLVED_TICKS = Pattern.compile("\"resolvedTicks\":(-?[0-9]+)");
    private static final Pattern QX = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern QZ = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");

    private double shipUpYFromInfo(String info) {
        double qx = readDouble(info, QX), qz = readDouble(info, QZ);
        return 1.0 - 2.0 * (qx * qx + qz * qz); // world-Y of the ship's local +Y
    }

    private double[] readShipInfoXYZ(String info) {
        return new double[]{readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z)};
    }

    @Test
    public void standingUpFromASeatOnASteeplyTiltedShipKeepsThePilotOnTheDeck() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 4620, by = 64, bz = 4620;

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);

        // Roll the ship to a steep tilt with the pilot's mouse, then stand up FROM the seat while it is
        // tilted - the maintainer's "after leaving, I fall through" is on a non-upright ship, which the
        // upright dismount test never exercised. Stop rolling EARLY (at ~40deg): angular momentum keeps
        // rolling the ship after the cursor centres, so a low break target overshoots to a near-vertical
        // wall; breaking at ~40 lands the settle in the steep-but-standable envelope.
        for (int i = 0; i < 220; i++) {
            if (shipUpYFromInfo(shipInfo()) < 0.76) {
                break;
            }
            if (Math.abs(clientDouble(KEY_BINDINGS, "flightCursorX")) < 0.5) {
                mouseDelta(20, 0);
            }
            bot().waitTicks(2);
        }
        centreFlightCursor();
        bot().waitTicks(30);
        double tilted = shipUpYFromInfo(shipInfo());
        // A steep but standable tilt is the subject. The client-side capture packet snaps the fresh
        // dismount onto the deck and holds it there (like a crew member who rode in and holds at 90deg).
        // Skip only if the mouse-roll under- or overshot the envelope (no tilt, or a near-vertical wall
        // where standing is not physical) - the harness's angular momentum makes the landing variable.
        Assume.assumeTrue("could not tilt the ship into the steep-but-standable envelope (upY=" + tilted
                + ")", tilted >= 0.25 && tilted < 0.80);

        double[] seat = readShipInfoXYZ(shipInfo());
        String statsBefore = exec("artest vs shipframe-stats");
        exec("artest player dismount");
        StringBuilder traj = new StringBuilder();
        double settledMin = Double.MAX_VALUE;
        for (int i = 0; i < 22; i++) {
            bot().waitTicks(2);
            double y = bot().reportState().get("playerY").getAsDouble();
            traj.append(String.format("%.1f ", y));
            if (i >= 14) { // last ~8 samples, once the dismount motion has settled
                settledMin = Math.min(settledMin, y);
            }
        }
        String statsAfter = exec("artest vs shipframe-stats");
        String capture = exec("artest vs deck-capture");
        double clientY = bot().reportState().get("playerY").getAsDouble();
        double serverY = readDouble(exec("artest vs player-ship-data"), PLAYER_Y);
        System.out.println("[deckcap] tilted-dismount upY=" + tilted + " shipPosY=" + seat[1]
                + " settledMinY=" + settledMin + " Ytraj=" + traj);
        System.out.println("[deckcap] tilted-dismount statsBefore=" + statsBefore + " statsAfter="
                + statsAfter);
        System.out.println("[deckcap] tilted-dismount capture=" + capture + " clientY=" + clientY
                + " serverY=" + serverY);

        // The ship hovers well above the by=64 ground (its solid top at y=65). The contract is that the
        // pilot does NOT fall through/off to the ground: his SETTLED height must stay up on the ship, not
        // drop to ~65. A single-instant "aboard" read is unreliable (it can catch him mid-fall while still
        // nominally inside the AABB), so we assert the settled trajectory instead.
        assertTrue("standing up on a tilted ship must keep the pilot UP on it, not drop him to the ~65 "
                + "ground: settledMinY=" + settledMin + " shipPosY=" + seat[1] + " Ytraj=" + traj,
                settledMin > 66.0);
        assertTrue("the client and server must agree on the ex-pilot's height on the tilted ship: serverY="
                + serverY + " clientY=" + clientY, Math.abs(clientY - serverY) < 3.0);
    }

    @Test
    public void aFreshlyDismountedPilotStaysCapturedWhenTheShipThenRollsNinetyDegrees() throws Exception {
        double h = Math.toRadians(90.0) / 2.0; // deck on its side (upY ~ 0)
        assertDismountThenRollHolds(4720, 64, 4720, Math.cos(h), Math.sin(h), -0.35, 0.35, "90deg");
    }

    @Test
    public void aFreshlyDismountedPilotStaysCapturedWhenTheShipThenRollsPastVertical() throws Exception {
        // Command 160deg; the attitude hold settles well PAST vertical on this fixture (measured deck-up
        // ~ -0.93, i.e. ~160deg - nearly inverted, the ex-pilot hanging below the deck). An EXACT 180deg is
        // the axis-angle singularity the controller cannot converge to, and a free spin to it is VS-damped
        // in a headless run - so the last few degrees to full inversion are a manual-playtest item.
        assertDismountThenRollHolds(4820, 64, 4820, 0.17365, 0.98481, -1.01, -0.4, "past-vertical");
    }

    /**
     * The fresh-dismount capture must survive the ship SUBSEQUENTLY rolling to a steep/inverted attitude:
     * stand up from the seat on a LEVEL deck (the client-side {@code PacketDeckCapture} seeds the ex-pilot
     * on the deck), THEN command the ship to a fixed roll about its nose and hold it, and assert the
     * ex-pilot rode the deck over - still resolved on it, held at deck height, client and server agreeing -
     * instead of being dropped through the hull or left behind in the world.
     *
     * <p>Reliable because the roll is a commanded quaternion on an UNMANNED ship ({@code artest vs point}):
     * a seated pilot's own input overwrites the attitude target every tick, and a free spin is VS-damped,
     * so commanding the attitude after the dismount is the only way to put a walking ex-pilot on a
     * steep/inverted deck in the headless harness. Thresholds are deck-relative (derived from the measured
     * ship Y), never a magic absolute.</p>
     */
    private void assertDismountThenRollHolds(int bx, int by, int bz, double qw, double qz,
            double upYLo, double upYHi, String label) throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);

        // Stand up on the LEVEL deck: the dismount capture packet seeds the ex-pilot on the deck.
        exec("artest player dismount");
        bot().waitTicks(30);
        int resolvedAfterDismount = (int) readDouble(exec("artest vs shipframe-stats"), RESOLVED_TICKS);
        assertTrue("the fresh dismount must engage the ship-frame capture on the level deck (resolvedTicks="
                + resolvedAfterDismount + ")", resolvedAfterDismount > 0);

        // Roll the now-UNMANNED ship (a mounted pilot would overwrite the target) to the commanded attitude.
        assertTrue("attitude hold must accept the " + label + " roll command",
                exec("artest vs point-by-id 0 " + scenarioShipId + " " + qw + " 0.0 0.0 " + qz)
                        .contains("\"commanded\":true"));
        bot().waitTicks(200); // slew to the roll and settle - stationary, not a transient
        double tilted = shipUpYFromInfo(shipInfo());
        // Reliable command -> a HARD assert that the regime was reached (fail loudly, not a silent skip).
        assertTrue("the ship must reach the " + label + " regime for the test to mean anything (upY="
                + tilted + " expected [" + upYLo + "," + upYHi + "])", tilted >= upYLo && tilted <= upYHi);

        double shipPosY = readShipInfoXYZ(shipInfo())[1];
        StringBuilder traj = new StringBuilder();
        double settledMin = Double.MAX_VALUE, settledMax = -Double.MAX_VALUE;
        for (int i = 0; i < 22; i++) {
            bot().waitTicks(2);
            double y = bot().reportState().get("playerY").getAsDouble();
            traj.append(String.format("%.1f ", y));
            if (i >= 14) { // last ~8 samples, once the roll has settled
                settledMin = Math.min(settledMin, y);
                settledMax = Math.max(settledMax, y);
            }
        }
        double osc = settledMax - settledMin;
        int resolvedOnRoll = (int) readDouble(exec("artest vs shipframe-stats"), RESOLVED_TICKS);
        String capture = exec("artest vs deck-capture");
        double clientY = bot().reportState().get("playerY").getAsDouble();
        double serverY = readDouble(exec("artest vs player-ship-data"), PLAYER_Y);
        System.out.println("[deckcap] dismount-then-roll " + label + " upY=" + tilted + " shipPosY="
                + shipPosY + " settledMin=" + settledMin + " osc=" + osc + " resolvedOnRoll=" + resolvedOnRoll
                + " capture=" + capture + " clientY=" + clientY + " serverY=" + serverY + " Ytraj=" + traj);

        // Still captured while the deck is steep/inverted - the ship frame keeps resolving him, not vanilla.
        assertTrue("the ex-pilot must stay resolved on the " + label + " deck, not be handed to vanilla: "
                + capture, capture.contains("\"verdict\":true"));
        // Deck-relative hold: he must not slide down toward the ~" + (by + 1) + " ground - his settled
        // height stays within a body of the measured ship, not 2.5+ blocks below it.
        assertTrue("the ex-pilot must ride the " + label + " deck over, not drop to the ground: settledMin="
                + settledMin + " shipPosY=" + shipPosY + " Ytraj=" + traj, settledMin > shipPosY - 2.5);
        // Held, not sliding: a captured body is stationary on the stationary rolled ship (small tail swing);
        // a body sliding off shows a large monotonic settle.
        assertTrue("the captured ex-pilot must be HELD on the " + label + " deck, not sliding (settled Y "
                + "oscillation=" + osc + "): " + traj, osc < 1.5);
        assertTrue("the client and server must agree on the ex-pilot's height (serverY=" + serverY
                + " clientY=" + clientY + ")", Math.abs(clientY - serverY) < 3.0);
    }

    @Test
    public void enteringAndLeavingTheSeatOnAnInvertedShipWorks() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 4520, by = 64, bz = 4520;

        double[] ship = buildShip(bx, by, bz);

        // Spin the FRESH (never-piloted -> no controller torque) ship to a full inversion with free
        // physics. This reaches a real 180 flip that the attitude-hold controller stalls short of, and
        // matches the maintainer's ship, which fell and tumbled fully inverted.
        // VS strongly damps angular velocity, so a one-shot set decays before a full flip - RE-APPLY it
        // every tick to sustain the spin until the ship is inverted.
        double upY = 1.0;
        for (int i = 0; i < 240 && upY > -0.9; i++) {
            exec("artest vs spin-ship-by-id 0 " + scenarioShipId + " 5.0 0.0 0.0");
            bot().waitTicks(1);
            upY = shipUpYFromInfo(shipInfo());
        }
        exec("artest vs spin-ship-by-id 0 " + scenarioShipId + " 0.0 0.0 0.0");
        bot().waitTicks(10);
        String info0 = shipInfo();
        double invertedUpY = shipUpYFromInfo(info0);
        System.out.println("[deckcap] force-invert upY=" + invertedUpY + " info=" + info0);
        Assume.assumeTrue("could not spin the ship inverted (upY=" + invertedUpY + ")", invertedUpY < -0.85);

        // ENTER the seat on the inverted ship — located inside THIS ship, not "the first seat in
        // the world" (see mountPilotSeatOfShipAt).
        mountPilotSeatOfShipAt(bx, by, bz);
        bot().waitTicks(20);

        // SYMPTOM "after entering, the ship does not react": a turn command must actually move it.
        for (int i = 0; i < 15; i++) {
            mouseDelta(60, 0);
            bot().waitTicks(2);
        }
        double omegaAfter = 0.0;
        for (int i = 0; i < 20; i++) {
            bot().waitTicks(2);
            omegaAfter = Math.max(omegaAfter, readDouble(shipInfo(), OMEGA));
        }
        System.out.println("[deckcap] force-invert control cursor="
                + clientDouble(KEY_BINDINGS, "flightCursorX") + " omegaAfter=" + omegaAfter);

        // SYMPTOM "after leaving, I fall through": dismount, the pilot must stay on the inverted deck.
        exec("artest player dismount");
        bot().waitTicks(40);
        String capture = exec("artest vs deck-capture");
        double clientY = bot().reportState().get("playerY").getAsDouble();
        double serverY = readDouble(exec("artest vs player-ship-data"), PLAYER_Y);
        System.out.println("[deckcap] force-invert dismount capture=" + capture + " clientY=" + clientY
                + " serverY=" + serverY);

        assertTrue("after ENTERING an inverted ship, a turn command must move it, not leave it dead "
                + "(omega=" + omegaAfter + ")", omegaAfter > 0.1);
        assertTrue("after LEAVING an inverted ship, the pilot must stay resolved on the deck, not fall "
                + "through: " + capture, capture.contains("\"verdict\":true"));
    }

    @Test
    public void aSeatedPilotCanStillTurnTheShipWhenItIsInverted() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 4420, by = 64, bz = 4420;

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);

        // Roll the ship past inverted with the real mouse, as the pilot does (this reaches inversion when
        // the controls WORK on the way over - the maintainer says they stop working once there).
        for (int i = 0; i < 240; i++) {
            if (clientDouble(SHIP_CAMERA, "shipUpY") < -0.45) {
                break;
            }
            if (Math.abs(clientDouble(KEY_BINDINGS, "flightCursorX")) < 0.9) {
                mouseDelta(60, 0);
            }
            bot().waitTicks(2);
        }
        centreFlightCursor();
        bot().waitTicks(40); // let the spin brake settle it inverted, omega -> ~0
        double shipUpY = clientDouble(SHIP_CAMERA, "shipUpY");
        Assume.assumeTrue("could not roll the ship inverted to test control there (shipUpY=" + shipUpY
                + ")", shipUpY < -0.4);
        double omegaSettled = readDouble(shipInfo(), OMEGA);
        System.out.println("[deckcap] inverted-control shipUpY=" + shipUpY + " omegaSettled=" + omegaSettled);

        // Now, WHILE inverted, command a fresh turn. The ship must respond - its angular velocity must
        // rise - just as it does upright. If it stays at rest, the controls are dead at inversion.
        for (int i = 0; i < 20; i++) {
            mouseDelta(60, 0);
            bot().waitTicks(2);
        }
        double cursor = clientDouble(KEY_BINDINGS, "flightCursorX");
        double omegaTurning = 0.0;
        for (int i = 0; i < 30; i++) {
            bot().waitTicks(2);
            omegaTurning = Math.max(omegaTurning, readDouble(shipInfo(), OMEGA));
        }
        System.out.println("[deckcap] inverted-control cursor=" + cursor + " omegaTurning=" + omegaTurning);

        assertTrue("a hard flight-cursor deflection must register on the client even when inverted "
                + "(cursor=" + cursor + ")", Math.abs(cursor) > 0.2);
        assertTrue("a seated pilot must still be able to TURN the ship when it is inverted - commanding a "
                + "turn must spin it up, not leave it dead (omega=" + omegaTurning + ")", omegaTurning > 0.1);
    }

    /** Feed a raw mouse delta to the client's own ship-pilot handler, as the window's mouse would. */
    private void mouseDelta(int dx, int dy) throws Exception {
        bot().invokeStaticInt(KEY_BINDINGS, "acceptShipPilotMouseDelta", dx, dy);
    }

    /** Bring the client's flight cursor back inside its centre dead-zone. */
    private void centreFlightCursor() throws Exception {
        double cursor = clientDouble(KEY_BINDINGS, "flightCursorX");
        for (int i = 0; i < 200 && Math.abs(cursor) >= 0.03; i++) {
            int step = Math.abs(cursor) > 0.2 ? 30 : 2;
            mouseDelta(cursor > 0 ? -step : step, 0);
            bot().waitTicks(1);
            cursor = clientDouble(KEY_BINDINGS, "flightCursorX");
        }
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
                exec("artest vs point-by-id 0 " + scenarioShipId + " "
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
                exec("artest vs point-by-id 0 " + scenarioShipId + " 0.17365 0.0 0.0 0.98481")
                        .contains("\"commanded\":true"));
        bot().waitTicks(200); // slew all the way over and settle

        String info = shipInfo();
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

        // Event-gated async-VS assembly barrier (load-scaled ceiling + early exit): AWAIT the SPAWNED
        // stage instead of a fixed tick budget that reds a healthy spawn under concurrent-fork load.
        ClientPoll.Result<Integer> spawned = ClientPoll.until(bot()::waitTicks,
                () -> count("ship-count-all"), n -> n > shipsBefore, 5, 40);
        int all = spawned.value;
        assertTrue("assembly must create a NEW VS ship (was " + shipsBefore + ", now " + all + ")",
                all > shipsBefore);
        // Recorded so a later "is my ship still in the registry?" can be a statement about THIS
        // scenario's ship on a world that also holds its neighbours'.
        shipsInRegistryAfterBuild = all;
        bot().waitTicks(40);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);

        // Await the ship LOADING near this base and take its IDENTITY in the same step. This is the
        // scenario's ONE positional lookup and the only one it can defend: the ship has just been
        // built here and has not moved. Everything after this asks by id — these scenarios hover,
        // tumble and invert their ship on purpose, and a distance bound cannot follow it there.
        scenarioShipId = captureShipIdAt(bx, by, bz);
        String si = shipInfo();
        double[] where = {readDouble(si, POS_X), readDouble(si, POS_Y), readDouble(si, POS_Z)};
        System.out.println("[deckcap] ship at (" + bx + "," + by + "," + bz + ") -> "
                + java.util.Arrays.toString(where));
        return where;
    }

    /** Build the ship and sit the bot on its pilot seat; returns the ship's world position. */
    private double[] buildAndBoardShip(int bx, int by, int bz) throws Exception {
        double[] ship = buildShip(bx, by, bz);
        mountPilotSeatOfShipAt(bx, by, bz);
        bot().waitTicks(10); // let the mount replicate and the client recognise the pilot seat
        return ship;
    }

    /**
     * Sit the bot on the pilot seat of the ship built AT THIS BASE.
     *
     * <p>{@code artest vs seat-mount <dim>} takes the first {@code TilePilotSeat} in the world's
     * loaded-tile list, with no position filter — unambiguous when the world holds exactly one ship,
     * and a scenario mounting a NEIGHBOUR's ship once several scenarios share a world. {@code
     * find-seat} resolves the shipyard bounds at a given world anchor and searches inside that ship
     * only, so the seat is located by identity rather than by being first.</p>
     */
    private void mountPilotSeatOfShipAt(int bx, int by, int bz) throws Exception {
        String seat = exec("artest vs find-seat 0 " + bx + " " + by + " " + bz);
        assertTrue("find-seat must locate the pilot seat INSIDE the ship built at this base ("
                + bx + "," + by + "," + bz + "): " + seat, seat.contains("\"seatFound\":true"));
        String mountInfo = exec("artest vs seat-mount-at 0 " + readInt(seat, SEAT_X) + " "
                + readInt(seat, SEAT_Y) + " " + readInt(seat, SEAT_Z));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount-at must report a dummy id: " + mountInfo, dm.find());
        assertTrue("bot must mount the seat dummy: " + mountInfo,
                exec("artest player mount-entity " + dm.group(1)).contains("\"mounted\":true"));
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

    /** This scenario's ship, asked by identity. */
    private String shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return exec("artest vs ship-info 0 id " + scenarioShipId);
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Is THIS scenario's ship loaded at its own base? The scoped replacement for a whole-dimension
     * {@code vs ship-count}: with one boot per test that count had exactly one ship to report on,
     * and on a shared client it answers with whichever neighbour's ship happens to be loaded.
     */
    private boolean shipLoadedAt(int bx, int by, int bz) throws Exception {
        return shipInfo().contains("\"managed\":true");
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
