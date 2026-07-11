package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import static org.junit.Assert.assertTrue;

/**
 * The full-path tier-2 flight e2e: a bot flies a real Valkyrien Skies ship with real keys, a real
 * mouse and a real camera, and every assertion reads something the CLIENT itself produced.
 *
 * <p>Each test pins one behaviour a hands-on playtest found broken, so each would have failed before
 * the fix that carries it:</p>
 * <ul>
 *   <li><b>The flight panel shows the ship's real speed.</b> The HUD is rendered from the client's own
 *       snapshot of the craft; a tier-1 rocket has always drawn a three-axis velocity + Flight-Assist
 *       setpoint panel, and a tier-2 ship drew nothing because neither number reached the client.</li>
 *   <li><b>Centring the flight cursor stops the ship turning.</b> A ship is a rigid body carrying
 *       angular momentum; unlike a rocket it does not stop just because the pilot stopped asking it to
 *       turn. Its controller must actively brake the residual spin.</li>
 *   <li><b>The camera turns over with the ship, and the eye stays out of the deck.</b> Vanilla adds the
 *       eye height along the WORLD up, so on an inverted ship the pilot's eye ends up inside the deck
 *       hanging above his seat and he sees nothing at all.</li>
 *   <li><b>A crew member stays on a steeply rolled deck.</b> Vanilla's vertical drag (0.98) and its
 *       horizontal friction (0.91) are not the same number, so a deck-down pull with world X/Z
 *       components is bent steeply toward world +Y: the crew member is flung up a wall.</li>
 * </ul>
 *
 * <p>Gated on real VS - run with {@code -PwithVS}. Each test builds its own ship at its own base, so a
 * ship left rolled by one cannot poison the next.</p>
 */
public class VSShipFlightTelemetryE2ETest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern OMEGA = Pattern.compile("\"omega\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern LOCAL_X = Pattern.compile("\"localX\":(-?[0-9.E\\-]+)");
    private static final Pattern LOCAL_Y = Pattern.compile("\"localY\":(-?[0-9.E\\-]+)");
    private static final Pattern LOCAL_Z = Pattern.compile("\"localZ\":(-?[0-9.E\\-]+)");
    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");
    private static final Pattern RESOLVED = Pattern.compile("\"resolvedTicks\":(-?\\d+)");
    private static final Pattern OBSTACLES = Pattern.compile("\"lastObstacleCount\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final String KEY_BINDINGS = "zmaster587.advancedRocketry.client.KeyBindings";
    private static final String SHIP_CAMERA = "zmaster587.advancedRocketry.client.ShipFrameCamera";
    private static final String ROCKET_EVENTS = "zmaster587.advancedRocketry.event.RocketEventHandler";
    /** The client's own flight-cursor dead-zone: inside it the ship is commanded no rotation at all. */
    private static final double CURSOR_DEADZONE = 0.05;

    // ---- Test 1: the flight panel + the spin brake -------------------------------------------

    @Test
    public void seatedPilotSeesLiveVelocityAndACentredCursorStopsTheShipTurning() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 3120, by = 64, bz = 3120;

        double[] ship = buildAndBoardShip(bx, by, bz);

        // --- The HUD panel. Climb, then read the text the CLIENT actually rendered. Before the ship's
        // velocity reached the client the panel had no speed line at all, and no bars.
        String hudBefore = clientString(ROCKET_EVENTS, "lastFreeFlightHud");
        assertTrue("a seated tier-2 pilot must get a Free Flight HUD at all: '" + hudBefore + "'",
                !hudBefore.isEmpty());

        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        double climbed = ship[1];
        try {
            for (int i = 0; i < 100 && climbed - ship[1] <= 2.0; i++) {
                bot().waitTicks(2);
                climbed = readDouble(shipInfo(bx, by, bz), POS_Y);
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        assertTrue("holding vertical-up must lift the ship: " + ship[1] + " -> " + climbed,
                climbed - ship[1] > 1.0);

        // The client's own velocity readout must be non-zero while the ship is moving. Read it from the
        // rendered HUD text: that is the string the pilot is looking at, not an internal field.
        String hudMoving = "";
        boolean sawSpeed = false;
        for (int i = 0; i < 30 && !sawSpeed; i++) {
            bot().waitTicks(2);
            hudMoving = clientString(ROCKET_EVENTS, "lastFreeFlightHud");
            sawSpeed = hasNonZeroSpeedReadout(hudMoving);
        }
        assertTrue("the tier-2 flight HUD must show the ship's real speed while it is moving; "
                + "the client rendered: '" + hudMoving + "'", sawSpeed);

        // --- The spin brake. Deflect the flight cursor sideways through the client's OWN raw-mouse
        // entry point, so the ship rolls, then centre the cursor and watch the spin die.
        for (int i = 0; i < 12; i++) {
            mouseDelta(60, 0);
            bot().waitTicks(2);
        }
        double cursorDeflected = clientDouble(KEY_BINDINGS, "flightCursorX");
        assertTrue("a raw mouse delta must deflect the client's flight cursor (got "
                + cursorDeflected + ")", Math.abs(cursorDeflected) > 0.2);

        double spinning = 0.0;
        for (int i = 0; i < 60 && spinning < 0.05; i++) {
            bot().waitTicks(2);
            spinning = readDouble(shipInfo(bx, by, bz), OMEGA);
        }
        assertTrue("a deflected flight cursor must actually spin the ship (omega=" + spinning + ")",
                spinning > 0.05);

        double cursorCentred = centreFlightCursor();
        assertTrue("the client's flight cursor must return to centre (got " + cursorCentred + ")",
                Math.abs(cursorCentred) < CURSOR_DEADZONE);

        // With the cursor centred the controller must brake the ship to rest. Poll with a ceiling.
        double settled = spinning;
        for (int i = 0; i < 150 && settled > 0.05; i++) {
            bot().waitTicks(2);
            settled = readDouble(shipInfo(bx, by, bz), OMEGA);
        }
        String controller = exec("artest vs afc-debug");
        System.out.println("[tier2] omega spinning=" + spinning + " settled=" + settled
                + " controller=" + controller);
        assertTrue("with the flight cursor centred the ship must STOP turning, not coast: it was "
                + "spinning at " + spinning + " rad/s and is still at " + settled
                + "; controller=" + controller, settled <= 0.05);

        exec("artest player dismount");
    }

    // ---- Test 2: the camera turns with the ship, and the eye stays out of the deck ------------

    @Test
    public void anInvertedShipTurnsThePilotsCameraOverAndKeepsHisEyeOutOfTheDeck() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 3220, by = 64, bz = 3220;

        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);

        double rollUpright = clientDouble(SHIP_CAMERA, "shipCamRoll");
        assertTrue("an upright ship must leave the camera level (roll=" + rollUpright + ")",
                Math.abs(rollUpright) < 15.0);

        // Roll the ship all the way over. The pilot's own attitude reference owns the angular channel
        // while he is seated, so steer it the way he does: hold the cursor hard over until it is there.
        rollShipUpsideDownWithTheMouse(bx, by, bz);

        // Where exactly a rigid body coasts to is not the contract; that it went over, and that the
        // camera went with it, is. Read the pair adjacently so they describe the same instant.
        double shipUpY = clientDouble(SHIP_CAMERA, "shipUpY");
        double rollInverted = clientDouble(SHIP_CAMERA, "shipCamRoll");
        assertTrue("the ship must actually have rolled past vertical (its up points " + shipUpY + ")",
                shipUpY < -0.3);

        // 1. The camera turns over with the deck. Vanilla has no roll for a player camera at all, so it
        //    is zero unless AR supplies it - and it must be the SHIP's roll, not merely some roll: for a
        //    craft rolled about its nose, the cosine of the camera roll IS the world Y of the ship's up.
        assertTrue("an inverted ship must turn the pilot's camera over with it (roll=" + rollInverted
                + " deg)", Math.abs(rollInverted) > 100.0);
        double impliedUpY = Math.cos(Math.toRadians(rollInverted));
        assertTrue("the camera roll must BE the ship's roll: a camera rolled " + rollInverted
                        + " deg implies a ship up of " + impliedUpY + ", but the ship's is " + shipUpY,
                Math.abs(impliedUpY - shipUpY) < 0.15);

        // 2. The eye follows the SHIP's up, not the world's. This is the "camera sinks into the floor"
        //    bug: with the eye pinned to world +Y, an inverted pilot's eye is a metre and a half INSIDE
        //    the deck above his seat. The contract: the eye is displaced along the ship's up.
        double eyeY = clientDouble(SHIP_CAMERA, "shipCamEyeY");
        double playerY = bot().reportState().get("playerY").getAsDouble();
        System.out.println("[tier2] shipUpY=" + shipUpY + " playerY=" + playerY + " eyeY=" + eyeY);
        assertTrue("the eye must be offset along the SHIP's up, not the world's: shipUpY=" + shipUpY
                        + " but the eye sits " + (eyeY - playerY) + " above the body",
                (eyeY - playerY) * shipUpY > 0.0);
        assertTrue("the eye offset must be about an eye height (" + Math.abs(eyeY - playerY) + ")",
                Math.abs(eyeY - playerY) > 0.8 && Math.abs(eyeY - playerY) < 2.5);

        // 3. And the client is actually DRAWING something: capture the frame. An eye buried in a solid
        //    block renders a single flat colour; a cockpit does not. The capture needs the framebuffer,
        //    which the harness leaves off for driver safety; turn it on for these few frames, then back.
        boolean framebufferWasOn = bot().setFramebuffer(true).get("previous").getAsBoolean();
        JsonObject shot;
        try {
            bot().waitTicks(10); // let frames render into the freshly bound framebuffer
            shot = bot().screenshot("tier2-inverted");
        } finally {
            bot().setFramebuffer(framebufferWasOn);
        }
        assertTrue("the client must write the screenshot: " + shot, shot.get("exists").getAsBoolean());
        assertTrue("the capture must come from the framebuffer, or its pixels prove nothing: " + shot,
                shot.get("framebuffer").getAsBoolean());
        File png = new File(shot.get("path").getAsString());
        BufferedImage frame = ImageIO.read(png);
        assertTrue("the screenshot must decode as an image", frame != null);
        System.out.println("[tier2] captured " + png + " (" + frame.getWidth() + "x" + frame.getHeight()
                + ", distinct colours=" + distinctColours(frame) + ")");
        assertTrue("a rendered frame from inside a solid block is one flat colour; the pilot must see "
                + "the world (distinct colours=" + distinctColours(frame) + ")", distinctColours(frame) > 8);

        exec("artest player dismount");
    }

    // ---- Test 3: a crew member stays on a steeply rolled deck ---------------------------------

    @Test
    public void crewStaysOnASteeplyRolledDeckInsteadOfBeingFlungIntoACorner() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 3320, by = 64, bz = 3320;

        double[] ship = buildShip(bx, by, bz);

        // Stand a living body on the level deck and let it settle. An armour stand is a living entity
        // with a player's movement rules, and unlike a player it has no client sending positions - so
        // what happens to it is purely what the server's movement frame does.
        int crewId = readInt(exec("artest vs drop-stand 0 " + ship[0] + " " + (ship[1] + 3)
                + " " + ship[2]), ENTITY_ID);
        bot().waitTicks(70);

        // The movement frame must actually be running, and its deck-frame sweep must be finding the
        // deck. A hook that never applied and a hook that applied and declined look the same from here.
        String stats = exec("artest vs shipframe-stats");
        System.out.println("[tier2] ship-frame stats after settling: " + stats);
        assertTrue("the ship-frame movement hook must run for an aboard crew member: " + stats,
                readInt(stats, RESOLVED) > 0);
        assertTrue("the deck-frame sweep must see the deck's blocks, or bodies fall through it: " + stats,
                readInt(stats, OBSTACLES) > 0);
        assertTrue("the crew member must come to rest ON the deck: " + stats,
                stats.contains("\"lastOnDeck\":true"));

        double[] restingOnDeck = localOf(crewId);

        // Roll the deck steeply. Past 45 degrees the world-frame drag anisotropy dominates: the pull
        // toward the deck acquires a world X/Z component damped four times harder than its world Y one.
        double half = Math.toRadians(75.0) / 2.0;
        String point = exec("artest vs point 0 " + bx + " " + by + " " + bz
                + " " + Math.cos(half) + " 0.0 0.0 " + Math.sin(half));
        assertTrue("attitude hold must accept the roll: " + point, point.contains("\"commanded\":true"));
        bot().waitTicks(200);

        double[] afterRoll = localOf(crewId);
        double drift = distance(restingOnDeck, afterRoll);
        System.out.println("[tier2] crew on rolled deck: start=" + java.util.Arrays.toString(restingOnDeck)
                + " end=" + java.util.Arrays.toString(afterRoll) + " drift=" + drift);

        // The measurement is in the SHIP's coordinates: a body that genuinely rides the deck barely
        // moves there, whatever the ship does in the world. Before the movement frame followed the
        // deck, this body slid off and lodged in a corner metres away.
        assertTrue("a crew member must stay where he stands on a deck rolled 75 degrees; he moved "
                + drift + " blocks across it", drift < 2.0);

        // And he must still be standing on it, not falling.
        String data = exec("artest vs player-ship-data 0 " + crewId);
        assertTrue("the crew member must still be resting on the deck: " + data,
                data.contains("\"playerOnGround\":true"));
    }

    // ---- Test 4: an entity on world terrain near a ship is NOT swallowed by its subspace ------

    @Test
    public void anEntityStandingOnWorldTerrainNearAShipIsNotDroppedThroughIt() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 3420, by = 64, bz = 3420;

        // Playtest report: walking up to a docked tier-2 ship, the player fell through it and the 1-2
        // blocks of ground beside it. Cause: a ship's world bounding box is axis-aligned and, resting
        // near the ground, it OVERLAPS the terrain around it; movement of anything inside that box was
        // being resolved in the ship's subspace, where that terrain does not exist. The fix: an entity
        // supported by real world ground stays with vanilla, whatever ship box it happens to be inside.
        double[] ship = buildShip(bx, by, bz);
        int cx = (int) Math.floor(ship[0]);
        int cy = (int) Math.floor(ship[1]);
        int cz = (int) Math.floor(ship[2]);

        // The world space inside a ship's box is empty - the ship's own blocks live in a far subspace,
        // never at these coordinates - so a block placed at the ship's centre is genuine WORLD terrain
        // sitting squarely inside the ship's (grown) bounding box: exactly the overlap that bit the
        // playtest. A body dropped onto it must rest on it.
        assertTrue("must place the world block inside the ship box",
                exec("artest fill 0 " + cx + " " + cy + " " + cz + " " + cx + " " + cy + " " + cz
                        + " minecraft:stone").contains("\"ok\":true"));
        // Set it down right on the block's top face - no long fall to build up speed - so the gate is
        // exercised while it rests, which is the reported situation (a player walking up and standing).
        int standId = readInt(exec("artest vs drop-stand 0 " + (cx + 0.5) + " " + (cy + 1.05)
                + " " + (cz + 0.5)), ENTITY_ID);
        bot().waitTicks(60);

        String handles = exec("artest vs would-take-over 0 " + standId);
        System.out.println("[tier2] terrain-in-box would-take-over: " + handles);
        assertTrue("an entity standing on world terrain must NOT have its movement taken into the "
                + "ship frame, even inside the ship box: " + handles, handles.contains("\"handles\":false"));

        String data = exec("artest vs player-ship-data 0 " + standId);
        double standY = readDouble(data, Pattern.compile("\"playerY\":(-?[0-9.E\\-]+)"));
        System.out.println("[tier2] stand rests at y=" + standY + " on a block whose top is " + (cy + 1)
                + " (data=" + data + ")");
        assertTrue("the body must rest ON the world block (top y=" + (cy + 1) + "), not fall through it "
                + "into the ship's empty subspace: it is at y=" + standY, standY > cy + 0.5);
        assertTrue("and it must be on the ground, not falling: " + data,
                data.contains("\"playerOnGround\":true"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * Build a ship at this test's own base and wait for it to load with the client present; returns its
     * world position.
     *
     * <p>The probes answer for the ship NEAREST a point, and the harness server is shared by every test
     * method - a ship another method left drifting is a real ship and will be found. So the wait is for
     * the ship COUNT to rise, and the position is checked against the base before it is trusted.</p>
     */
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
            double[] candidate = new double[]{
                    readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z)};
            // Only OUR ship counts: another method's craft, drifting a hundred blocks away, is still
            // the nearest thing the probe can find once ours has failed to appear.
            if (distance(candidate, new double[]{bx, by, bz}) < 24.0) {
                where = candidate;
            }
        }
        assertTrue("the ship built at this base must LOAD with the client present; nearest was: " + info,
                where != null);
        System.out.println("[tier2] ship at base (" + bx + "," + by + "," + bz + ") -> "
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

    /**
     * Steer the ship all the way over with the pilot's own controls: hold the flight cursor hard to one
     * side until the ship's up points down, then centre it so the controller stops the roll there.
     */
    private void rollShipUpsideDownWithTheMouse(int bx, int by, int bz) throws Exception {
        for (int i = 0; i < 240; i++) {
            // Stop asking for roll BEFORE the ship is over: it is a rigid body turning at more than a
            // radian a second, and it coasts on into the brake. Aiming early lands it near inverted.
            if (clientDouble(SHIP_CAMERA, "shipUpY") < -0.45) {
                break;
            }
            if (Math.abs(clientDouble(KEY_BINDINGS, "flightCursorX")) < 0.9) {
                mouseDelta(60, 0);
            }
            bot().waitTicks(2);
        }
        centreFlightCursor();
        bot().waitTicks(40); // let the spin brake settle the ship where the pilot left it
    }

    /**
     * Bring the client's flight cursor back inside its centre dead-zone, the way a pilot does: shove the
     * mouse the other way, coarsely at first and then in small nudges, watching the client's own value.
     * Inside the dead-zone the ship is commanded zero rotation, which is what "centred" means to it.
     */
    private double centreFlightCursor() throws Exception {
        double cursor = clientDouble(KEY_BINDINGS, "flightCursorX");
        for (int i = 0; i < 200 && Math.abs(cursor) >= CURSOR_DEADZONE * 0.5; i++) {
            int step = Math.abs(cursor) > 0.2 ? 30 : 2;
            mouseDelta(cursor > 0 ? -step : step, 0);
            bot().waitTicks(1);
            cursor = clientDouble(KEY_BINDINGS, "flightCursorX");
        }
        return cursor;
    }

    /** Feed a raw mouse delta to the client's own ship-pilot handler, as the window's mouse would. */
    private void mouseDelta(int dx, int dy) throws Exception {
        bot().invokeStaticInt(KEY_BINDINGS, "acceptShipPilotMouseDelta", dx, dy);
    }

    /** Whether the rendered HUD carries a speed readout with a non-zero value. */
    private static boolean hasNonZeroSpeedReadout(String hud) {
        Matcher m = Pattern.compile("([0-9]+\\.[0-9]+)").matcher(hud);
        while (m.find()) {
            if (Double.parseDouble(m.group(1)) > 0.05) {
                return true;
            }
        }
        return false;
    }

    /** How many distinct colours a captured frame contains - one means nothing was drawn. */
    private static int distinctColours(BufferedImage image) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int stepX = Math.max(1, image.getWidth() / 64);
        int stepY = Math.max(1, image.getHeight() / 64);
        for (int x = 0; x < image.getWidth(); x += stepX) {
            for (int y = 0; y < image.getHeight(); y += stepY) {
                seen.add(image.getRGB(x, y));
                if (seen.size() > 64) {
                    return seen.size();
                }
            }
        }
        return seen.size();
    }

    private String clientString(String className, String field) throws Exception {
        return bot().readStaticField(className, field).get("value").getAsString();
    }

    private double clientDouble(String className, String field) throws Exception {
        return Double.parseDouble(clientString(className, field));
    }

    private String shipInfo(int bx, int by, int bz) throws Exception {
        return exec("artest vs ship-info 0 " + bx + " " + by + " " + bz);
    }

    private double[] localOf(int entityId) throws Exception {
        String json = exec("artest vs player-ship-data 0 " + entityId);
        assertTrue("entity " + entityId + " must report a ship-frame position: " + json,
                json.contains("\"localX\""));
        return new double[]{readDouble(json, LOCAL_X), readDouble(json, LOCAL_Y), readDouble(json, LOCAL_Z)};
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
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

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
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
}
