package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Interior boarding of a non-upright ship (any-attitude crew contract C12/C13 territory).
 *
 * <p>The pinned contract: a body inside a ship's hull with a deck below it IN THE SHIP FRAME is
 * the DECK's to claim - stopping creative flight there seats it back on the deck (ship-frame
 * gravity carries it, at any attitude) with the ship camera engaged. Before the interior gate
 * existed, WORLD gravity owned that body instead: over this fixture's cockpit opening (facing
 * world-down at 170 degrees) it fell clean out of the ship to the terrain; in an enclosed
 * cavity it was pinned to the interior world-floor by the outer-hull fallback with a world
 * camera - the reported "captured, but the camera never flips" desync. Both flavors of that
 * gap are closed by the same gate this test pins.</p>
 *
 * <p>Gated on real VS - run with {@code -PwithVS}.</p>
 */
public class VSCrewInteriorBoardingE2ETest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(\\d+)");

    private static final String VARIANT = "with-pilot-deck";
    private static final String SHIP_CAMERA = "zmaster587.advancedRocketry.client.ShipFrameCamera";
    private static final String SHIP_FRAME_TRAVEL =
            "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel";

    @Test
    public void aBodyReleasedInsideAnInvertedShipIsSeatedBackOnTheDeck()
            throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 6620, by = 64, bz = 6620;

        // Seat the bot, invert the ship under him, dismount INSIDE: the dismount seed captures
        // him ABOARD in the cockpit of the inverted ship.
        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);
        double h = Math.toRadians(170.0) / 2.0;
        assertTrue("attitude hold must accept the inversion",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(200);
        exec("artest player dismount");
        boolean aboardBefore = false;
        for (int i = 0; i < 30 && !aboardBefore; i++) {
            bot().waitTicks(4);
            String cap = exec("artest vs deck-capture");
            aboardBefore = cap.contains("\"alreadyTracked\":true") && !cap.contains("\"hullStand\":true");
        }
        assertTrue("the dismounted pilot must be captured ABOARD inside the inverted ship: "
                + exec("artest vs deck-capture"), aboardBefore);
        double preY = bot().reportState().get("playerY").getAsDouble();

        // Subspace census at the QUIET STANDING phase (the ledgered obst=0 already shows here):
        // server = control (must be rich), client statics = the side under suspicion. Server rich +
        // client empty ==> the client never received the ship's subspace chunks.
        System.out.println("[interior] census standing: server=" + exec("artest vs subspace-census")
                + " client={ticks=" + censusStatic("censusTicks")
                + " ship=" + censusStatic("censusShipId")
                + " tracked=" + censusStatic("censusTracked")
                + " subPos=" + censusStatic("censusSubPos")
                + " chunkLoaded=" + censusStatic("censusChunkLoaded")
                + " nonAir=" + censusStatic("censusNonAir")
                + " boxes=" + censusStatic("censusCollisionBoxes")
                + " region=" + censusStatic("censusRegion")
                + " regionNonAir=" + censusStatic("censusRegionNonAir") + "}"
                + " seed={attempts=" + censusStatic("seedAttempts")
                + " oks=" + censusStatic("seedOks")
                + " refusals=" + censusStatic("seedRefusals")
                + " lastRefusal=" + censusStatic("lastSeedRefusal")
                + " notLoaded=" + censusStatic("seedNotLoaded") + "}");

        // Release the capture DETERMINISTICALLY, with the body still inside the hull: a small
        // world teleport reads as an external move, the guard drops the capture, and the body is
        // exactly the C12 subject - inside the ship's region, un-captured, under WORLD gravity.
        // (A creative-flight release is the report's flavor, but the flying body drifts
        // unpredictably and can leave the hull before flight ends - flight interaction belongs
        // to the flying-aboard contract's own test.)
        //
        // Direction is MEASURED, not assumed: at 170 deg world-UP maps to ship-DOWN (deeper
        // aboard, toward the deck) plus a subspace-Z step INTO the region. World-DOWN was the
        // opposite - the seat dismount can stand the body on the region's boundary block (the
        // cockpit doorway), 0.2 blocks from the face, and a world-down nudge carries a ~0.1
        // subspace-Z component that pushes it OUT through that face; an outside body is not
        // C12's subject at all (the interior gate rightly refuses it) and the test then measured
        // its own ejection, not the contract.
        exec("tp @a ~ ~0.6 ~");

        // Subject validity (fixture geometry by measurement): the released body must still BE
        // inside the ship's block region, or the run is measuring a doorway ejection.
        bot().waitTicks(2);
        String subAfterRelease = censusStatic("censusSubPos");
        String regionStr = censusStatic("censusRegion");
        assertTrue("the released body must remain INSIDE the ship's block region (sub="
                + subAfterRelease + " region=" + regionStr + ")",
                subInRegion(subAfterRelease, regionStr));

        // Sample the settle: which mode holds the fallen body, and what camera does the client own?
        long resolved0 = (long) Double.parseDouble(bot().readStaticField(
                SHIP_FRAME_TRAVEL, "resolvedTicks").get("value").getAsString());
        int aboardSeen = 0, hullSeen = 0, samples = 0;
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            bot().waitTicks(3);
            samples++;
            String cap = exec("artest vs deck-capture");
            boolean tracked = cap.contains("\"alreadyTracked\":true");
            boolean hull = cap.contains("\"hullStand\":true");
            if (tracked && !hull) aboardSeen++;
            if (tracked && hull) hullSeen++;
            trace.append(String.format(java.util.Locale.ROOT,
                    "[t%d y=%.2f cap=%b hull=%b cliRes=%s cliDrop=%s obst=%s onDeck=%s"
                            + " cSub=%s cLoaded=%s cAir=%s cBox=%s cRegAir=%s] ",
                    i * 3, bot().reportState().get("playerY").getAsDouble(), tracked, hull,
                    bot().readStaticField(SHIP_FRAME_TRAVEL, "resolvedTicks").get("value")
                            .getAsString(),
                    bot().readStaticField(SHIP_FRAME_TRAVEL, "lastDropReason").get("value")
                            .getAsString(),
                    bot().readStaticField(SHIP_FRAME_TRAVEL, "lastObstacleCount").get("value")
                            .getAsString(),
                    bot().readStaticField(SHIP_FRAME_TRAVEL, "lastOnDeck").get("value")
                            .getAsString(),
                    censusStatic("censusSubPos"),
                    censusStatic("censusChunkLoaded"),
                    censusStatic("censusNonAir"),
                    censusStatic("censusCollisionBoxes"),
                    censusStatic("censusRegionNonAir")));
        }
        long resolvedDelta = (long) Double.parseDouble(bot().readStaticField(
                SHIP_FRAME_TRAVEL, "resolvedTicks").get("value").getAsString()) - resolved0;
        System.out.println("[interior] client resolvedDelta=" + resolvedDelta + " over the window");
        boolean shipCam = Boolean.parseBoolean(
                bot().readStaticField(SHIP_CAMERA, "shipCamActive").get("value").getAsString());
        double settledY = bot().reportState().get("playerY").getAsDouble();
        String capEnd = exec("artest vs deck-capture");
        System.out.println("[interior] aboard=" + aboardSeen + " hull=" + hullSeen + "/" + samples
                + " shipCamActive=" + shipCam + " preY=" + preY + " settledY=" + settledY
                + " censusEnd(server)=" + exec("artest vs subspace-census")
                + " :: " + trace);

        // The interior-boarding contract: the deck reclaims the released body - it is carried
        // back by SHIP-frame gravity (never lost through the world-down cockpit opening to the
        // world below, never pinned by the outer-hull fallback), stays resolved ABOARD at its
        // deck spot, and the client's ship camera engages.
        assertTrue("a body released inside the ship must be re-seated ABOARD (saw aboard "
                + aboardSeen + "/" + samples + ", hull-stand " + hullSeen + "): " + trace,
                aboardSeen > samples / 2);
        assertTrue("the body must stay WITH the inverted ship at its deck spot, not fall out "
                + "(preY=" + preY + " settledY=" + settledY + ", cap=" + capEnd + "): " + trace,
                Math.abs(settledY - preY) < 2.5 && capEnd.contains("\"alreadyTracked\":true"));
        assertTrue("the client camera must engage for the re-seated interior body "
                + "(shipCamActive=" + shipCam + ")", shipCam);
    }

    // ---- Flying-aboard (contract C13): flight resolves in the DECK frame -----------------------

    @Test
    public void aFlyingCrewMemberAscendsAlongTheDeckNormalAndReseatsOnFlightOff() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 6720, by = 64, bz = 6720;

        // The flying-aboard contract on a steeply ROLLED ship: starting creative flight on the
        // deck keeps the body the deck's (no release, ship camera stays), the vertical fly
        // intent ascends along the DECK NORMAL - measured in SUBSPACE, where deck-up is plain +Y
        // regardless of the roll; a world-up ascent would instead leak most of its motion into
        // the subspace deck PLANE (at 60 deg: cos60 = 0.5 up, sin60 = 0.87 sideways) - and
        // turning flight off hands the body to deck gravity, which seats it back on the deck.
        buildAndBoardShip(bx, by, bz);
        exec("gamemode creative @a"); // flight needs creative; the harness default is not
        bot().waitTicks(20);
        double h = Math.toRadians(60.0) / 2.0;
        assertTrue("attitude hold must accept the roll",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(150);
        exec("artest player dismount");
        boolean aboard = false;
        for (int i = 0; i < 30 && !aboard; i++) {
            bot().waitTicks(4);
            String cap = exec("artest vs deck-capture");
            aboard = cap.contains("\"alreadyTracked\":true") && !cap.contains("\"hullStand\":true");
        }
        assertTrue("the dismounted pilot must be captured ABOARD on the rolled deck: "
                + exec("artest vs deck-capture"), aboard);
        double[] sub0 = parseSub(censusStatic("censusSubPos"));

        // Start creative flight: double-tap space (the first tap is a deck jump; the second,
        // within the toggle window, flips flight).
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(4);

        // The double-tap itself climbs a few blocks (a deck jump + held-space flight ticks), so
        // re-baseline AFTER flight is on: the pin measures the held-ascend phase alone. The hold
        // is SHORT deliberately - the stay region ends ~4 blocks above the hull top, and a climb
        // that exits it is C4's legitimate release, not this pin's subject.
        double[] subFly = parseSub(censusStatic("censusSubPos"));
        StringBuilder trace = new StringBuilder();
        int trackedSeen = 0, camSeen = 0, samples = 0;
        double[] subEnd = subFly;
        // Climb TO A TARGET RISE (+2 subspace blocks), not for a fixed time: the climb rate
        // varies run to run, and a timed hold can overshoot into the stay region's edge - whose
        // release is C4 doing its job, not this pin's subject. From a ~129-130 start the +2
        // target tops out well below that edge.
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        try {
            for (int i = 0; i < 10 && subEnd[1] - subFly[1] < 2.0; i++) {
                bot().waitTicks(2);
                samples++;
                String cap = exec("artest vs deck-capture");
                boolean tracked = cap.contains("\"alreadyTracked\":true")
                        && !cap.contains("\"hullStand\":true");
                if (tracked) trackedSeen++;
                if (Boolean.parseBoolean(bot().readStaticField(SHIP_CAMERA, "shipCamActive")
                        .get("value").getAsString())) {
                    camSeen++;
                }
                subEnd = parseSub(censusStatic("censusSubPos"));
                trace.append(String.format(java.util.Locale.ROOT, "[t%d sub=%.1f,%.1f,%.1f cap=%b] ",
                        i * 2, subEnd[0], subEnd[1], subEnd[2], tracked));
            }
        } finally {
            bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        }
        double dySub = subEnd[1] - subFly[1];
        double dxzSub = Math.sqrt((subEnd[0] - subFly[0]) * (subEnd[0] - subFly[0])
                + (subEnd[2] - subFly[2]) * (subEnd[2] - subFly[2]));
        System.out.println("[flyaboard] subFly=" + subFly[1] + " dySub=" + dySub + " dxzSub="
                + dxzSub + " tracked=" + trackedSeen + "/" + samples + " cam=" + camSeen
                + "/" + samples + " :: " + trace);

        // The contract, in its three player-visible parts:
        assertTrue("starting flight on the deck must NOT release the capture (tracked "
                + trackedSeen + "/" + samples + "): " + trace, trackedSeen == samples);
        assertTrue("the ship camera must stay engaged for a flying-aboard body (cam " + camSeen
                + "/" + samples + ")", camSeen == samples);
        // The census position is block-floored, so allow a block of lateral jitter; a WORLD-up
        // ascent at 60 deg would drift the deck plane by ~1.7x the climb (several blocks here).
        assertTrue("holding ascend must climb along the DECK NORMAL (subspace +Y): dySub=" + dySub
                + " dxzSub=" + dxzSub + " :: " + trace, dySub > 1.2 && dxzSub < 1.6);

        // Descend back toward the deck first - the flight-off double-tap itself adds a little
        // climb, and toggling at the stay region's edge exits it mid-flight (C4's legitimate
        // release, but then WORLD gravity owns the fall and the reseat below is not this
        // contract's). The descend leg also pins the OTHER vertical intent: sneak sinks along
        // the deck normal exactly as space climbs it.
        double[] subHigh = subEnd;
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_LSHIFT);
        bot().waitTicks(14);
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_LSHIFT);
        double[] subDown = parseSub(censusStatic("censusSubPos"));
        assertTrue("holding descend must sink along the DECK NORMAL (subspace -Y): "
                + subHigh[1] + " -> " + subDown[1], subDown[1] < subHigh[1]);

        // Flight off: double-tap again; deck gravity reclaims the airborne body and seats it.
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        boolean seated = false;
        String capEnd = "";
        double[] subSeated = subEnd;
        for (int i = 0; i < 40 && !seated; i++) {
            bot().waitTicks(3);
            capEnd = exec("artest vs deck-capture");
            subSeated = parseSub(censusStatic("censusSubPos"));
            // The descend leg parks the body over the SEAT column, so deck gravity may seat it on
            // the seat block's top - one block above the deck stand. Either landing is "seated on
            // the ship's geometry at the deck spot"; only staying airborne (or lost to the world)
            // fails.
            seated = capEnd.contains("\"alreadyTracked\":true")
                    && !capEnd.contains("\"hullStand\":true")
                    && subSeated[1] <= sub0[1] + 1.4;
        }
        exec("gamemode survival @a"); // leave the shared world as the other tests expect it
        assertTrue("turning flight off must hand the body to deck gravity and seat it back "
                + "(sub=" + subSeated[1] + " vs start " + sub0[1] + "): " + capEnd, seated);
    }

    /** "x,y,z" census position as doubles (block coords are integral; that is fine here). */
    private static double[] parseSub(String sub) {
        String[] s = sub.split(",");
        return new double[]{Double.parseDouble(s[0].trim()), Double.parseDouble(s[1].trim()),
                Double.parseDouble(s[2].trim())};
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

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

        // Fixture completeness by measurement: how many blocks did the assembled ship actually
        // get (region census + the ship's own blockPositions count + iron in the grown
        // neighbourhood)? Sampled twice a second apart to tell a stalled-but-progressing
        // relocation from a settled short count. The census probe resolves the ship by
        // containment, so stand the bot INSIDE the craft's world box for the reading.
        exec("tp @a " + (bx + 3.5) + " " + (by + 6) + " " + (bz + 3.5) + " 0 0");
        bot().waitTicks(4);
        String census1 = exec("artest vs subspace-census");
        bot().waitTicks(20);
        String census2 = exec("artest vs subspace-census");
        // The deck is built at (rocketX+-2, rocketY+3, rocketZ+-2) with rocket=(base+3,base+1,base+3),
        // i.e. world (bx+1..bx+5, by+4, bz+1..bz+5) before assembly relocates it into the ship.
        String leftover = exec("testforblock " + (bx + 3) + " " + (by + 4) + " " + (bz + 3)
                + " minecraft:iron_block")
                + " | " + exec("testforblock " + (bx + 5) + " " + (by + 4) + " " + (bz + 5)
                + " minecraft:iron_block");
        System.out.println("[interior] census postBuild#1=" + census1);
        System.out.println("[interior] census postBuild#2=" + census2);
        System.out.println("[interior] leftoverDeckAtBase=" + leftover);
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

    /** One CLIENT-side subspace-census static (ShipFrameTravel.census*), as a plain string. */
    private String censusStatic(String field) throws Exception {
        return bot().readStaticField(SHIP_FRAME_TRAVEL, field).get("value").getAsString();
    }

    /** Whether a census "x,y,z" block position lies inside a census "x,y,z..x,y,z" region. */
    private static boolean subInRegion(String sub, String region) {
        try {
            String[] s = sub.split(",");
            String[] r = region.split("\\.\\.");
            String[] lo = r[0].split(",");
            String[] hi = r[1].split(",");
            for (int a = 0; a < 3; a++) {
                int v = Integer.parseInt(s[a].trim());
                if (v < Integer.parseInt(lo[a].trim()) || v > Integer.parseInt(hi[a].trim())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException malformed) {
            return false;
        }
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
