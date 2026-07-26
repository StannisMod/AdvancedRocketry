package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Assume;
import org.junit.Test;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Render-pose vs collision-pose consistency for a body standing on a ship.
 *
 * <p>A ship is DRAWN through the client's interpolated render transform, but every collision /
 * standing computation for a resolved body maps through the GAME-TICK transform. When the two
 * poses diverge (a hovering ship holding an attitude never stops moving), the surface the player
 * collides with sits visibly beside the surface he sees — the playtest report: "I walk not on the
 * blocks I see but about a block away from them; it seems to be the right surface, yet not quite".
 *
 * <p>The observable is {@code ShipFrameTravel.lastRenderSkew}: at every client-side commit, the
 * distance between the committed world position (tick pose) and where the renderer draws the same
 * subspace point (render pose). The contract under test: that gap stays imperceptible wherever a
 * body is resolved against a ship. A parked ship is the control — its render transform converges,
 * so a large control reading would mean the instrument, not the subject.
 *
 * <p>Gated on real VS — run with {@code -PwithVS}.</p>
 */
public class VSShipRenderPoseSkewE2ETest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern Q_X = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern Q_Z = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");
    private static final Pattern WORLD_X = Pattern.compile("\"worldX\":(-?[0-9.E\\-]+)");
    private static final Pattern WORLD_Y = Pattern.compile("\"worldY\":(-?[0-9.E\\-]+)");
    private static final Pattern WORLD_Z = Pattern.compile("\"worldZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-deck";
    private static final String SHIP_FRAME_TRAVEL =
            "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel";

    /** The gap a player can feel as "standing beside the blocks I see". The contract bound: the
     *  drawn surface and the collided surface must agree well under this. */
    private static final double VISIBLE_SKEW = 0.35;

    @Test
    public void theSurfaceABodyStandsOnIsTheSurfaceTheRendererDraws() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        // Surveyed natural site (flat plains, pinned world seed) - see HarnessFixtureSitesTest.
        final int bx = 268, by = 71, bz = 6176;

        // ---- Leg A (control): a PARKED ship's render transform converges onto its tick pose, so
        // the skew of a body standing on its deck bounds the instrument's noise floor. A large
        // reading here would indict the instrument (or a constant pose offset), not ship motion.
        double[] ship = buildShip(bx, by, bz);
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        bot().waitTicks(80);
        assertTrue("the client player must be captured on the parked deck before sampling: "
                        + exec("artest vs deck-capture"),
                exec("artest vs deck-capture").contains("\"verdict\":true"));
        long samples0 = (long) clientDouble(SHIP_FRAME_TRAVEL, "renderSkewSamples");
        double restMax = 0.0;
        StringBuilder restTrace = new StringBuilder();
        double restCrossMax = 0.0;
        for (int i = 0; i < 20; i++) {
            bot().waitTicks(2);
            double skew = clientDouble(SHIP_FRAME_TRAVEL, "lastRenderSkew");
            restMax = Math.max(restMax, skew);
            if (i % 4 == 0) {
                double cross = crossSideDelta(ship[0], ship[1], ship[2]);
                if (!Double.isNaN(cross)) restCrossMax = Math.max(restCrossMax, cross);
                restTrace.append(String.format(Locale.ROOT, "[t%d skew=%.4f cross=%.4f %s] ",
                        i * 2, skew, cross, clientString(SHIP_FRAME_TRAVEL, "lastRenderSkewMode")));
            }
        }
        long restSamples = (long) clientDouble(SHIP_FRAME_TRAVEL, "renderSkewSamples") - samples0;
        System.out.println("[poseskew] rest samples=" + restSamples + " max=" + restMax
                + " crossMax=" + restCrossMax + " :: " + restTrace);
        assertTrue("the skew instrument must fire while standing on the parked deck (samples="
                + restSamples + ")", restSamples > 0);
        assertTrue("on a PARKED ship the drawn pose must sit on the tick pose (control; max="
                + restMax + "): " + restTrace, restMax < VISIBLE_SKEW);

        // ---- Leg B (subject): the reported configuration — a ship HOVERING on an attitude hold
        // (inverted, so the world-top is a hull-stand surface). Gate on the MEASURED attitude,
        // never elapsed ticks.
        double h = Math.toRadians(160.0) / 2.0;
        assertTrue("attitude hold must accept the past-vertical roll",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        double upY = 1.0;
        String info = "";
        for (int i = 0; i < 60 && upY >= -0.3; i++) {
            bot().waitTicks(10);
            info = shipInfo(bx, by, bz);
            double qx = readDouble(info, Q_X), qz = readDouble(info, Q_Z);
            upY = 1.0 - 2.0 * (qx * qx + qz * qz);
        }
        assertTrue("the ship must reach the steep inversion before the hull leg (upY=" + upY + "): "
                + info, upY < -0.3);
        double sx = readDouble(info, POS_X), sy = readDouble(info, POS_Y), sz = readDouble(info, POS_Z);

        // Drop the bot onto the world-top of the inverted hull; gate on the fall beginning (a
        // freshly-teleported client may not tick until its chunks stream in).
        exec("tp @a " + sx + " " + (sy + 7) + " " + sz + " 0 0");
        double preY = bot().reportState().get("playerY").getAsDouble();
        // Event-gated fall detection (load-scaled ceiling + early exit): a fixed 60-iteration budget can
        // miss a slow chunk-stream / tick start under concurrent-fork load and red a healthy encounter.
        ClientPoll.Result<Double> fall = ClientPoll.until(bot()::waitTicks,
                () -> bot().reportState().get("playerY").getAsDouble(),
                y -> Math.abs(y - preY) > 0.4, 2, 60);
        assertTrue("the teleported client must start falling before the hull leg "
                + "(client tick/chunk-stream stall)", fall.satisfied);

        // Wait for the hull-stand hold to actually engage — the leg proves nothing otherwise.
        boolean hullHeld = false;
        String cap = "";
        for (int i = 0; i < 40 && !hullHeld; i++) {
            bot().waitTicks(3);
            cap = exec("artest vs deck-capture");
            hullHeld = cap.contains("\"alreadyTracked\":true") && cap.contains("\"hullStand\":true");
        }
        assertTrue("the encounter must engage the HULL-STAND hold before sampling"
                + " [client hullContact calls=" + (long) clientDouble(SHIP_FRAME_TRAVEL,
                        "hullContactCalls")
                + " maxObs=" + (int) clientDouble(SHIP_FRAME_TRAVEL, "hullContactMaxObstacles")
                + " touches=" + (long) clientDouble(SHIP_FRAME_TRAVEL, "hullContactTouches")
                + " y=" + bot().reportState().get("playerY").getAsDouble() + "]: " + cap, hullHeld);

        // Sample the skew across the hull-stand window; keep only samples the hull mode produced.
        long hullSamples0 = (long) clientDouble(SHIP_FRAME_TRAVEL, "renderSkewSamples");
        double hullMax = 0.0;
        int hullModeSeen = 0;
        StringBuilder hullTrace = new StringBuilder();
        double hullCrossMax = 0.0, hullMismatchMax = -1.0;
        for (int i = 0; i < 30; i++) {
            bot().waitTicks(3);
            double skew = clientDouble(SHIP_FRAME_TRAVEL, "lastRenderSkew");
            String mode = clientString(SHIP_FRAME_TRAVEL, "lastRenderSkewMode");
            if ("hull".equals(mode)) {
                hullMax = Math.max(hullMax, skew);
                hullModeSeen++;
                hullMismatchMax = Math.max(hullMismatchMax,
                        clientDouble(SHIP_FRAME_TRAVEL, "lastHullBoxMismatch"));
            }
            if (i % 5 == 0) {
                double cross = crossSideDelta(sx, sy, sz);
                if (!Double.isNaN(cross)) hullCrossMax = Math.max(hullCrossMax, cross);
                hullTrace.append(String.format(Locale.ROOT, "[t%d skew=%.4f cross=%.4f %s y=%.2f] ",
                        i * 3, skew, cross, mode,
                        bot().reportState().get("playerY").getAsDouble()));
            }
        }
        long hullSamples = (long) clientDouble(SHIP_FRAME_TRAVEL, "renderSkewSamples") - hullSamples0;
        String omega = shipInfo(bx, by, bz);
        System.out.println("[poseskew] hull samples=" + hullSamples + " hullModeSeen=" + hullModeSeen
                + " max=" + hullMax + " crossMax=" + hullCrossMax + " restMax=" + restMax
                + " :: " + hullTrace);
        System.out.println("[poseskew] hull ship-info=" + omega);

        // ---- Leg C (driver isolation, diagnostic): the SAME hull-stand configuration under
        // SUSTAINED ship motion. The in-client skew is expected to stay ~0 (the body is committed
        // through the same client pose the renderer draws); the discriminating number is the
        // CROSS-SIDE delta — the client's committed position vs the SERVER's mapping of the same
        // subspace point. A delta that scales with the commanded speed names the client pose LAG
        // as the driver; a speed-independent delta names a constant cross-side pose offset.
        for (double climb : new double[]{0.6, 1.2}) {
            String vinfo = shipInfo(bx, by, bz);
            double vx = readDouble(vinfo, POS_X), vy = readDouble(vinfo, POS_Y),
                    vz = readDouble(vinfo, POS_Z);
            assertTrue("velocity command must engage for the moving leg",
                    exec("artest vs force-vel 0 " + vx + " " + vy + " " + vz + " 0 " + climb + " 0")
                            .contains("\"commanded\":true"));
            double moveSkewMax = 0.0, moveCrossMax = 0.0, moveCrossSum = 0.0;
            int moveCrossN = 0;
            StringBuilder moveTrace = new StringBuilder();
            for (int i = 0; i < 24; i++) {
                bot().waitTicks(3);
                double skew = clientDouble(SHIP_FRAME_TRAVEL, "lastRenderSkew");
                String mode = clientString(SHIP_FRAME_TRAVEL, "lastRenderSkewMode");
                moveSkewMax = Math.max(moveSkewMax, skew);
                if (i % 3 == 0) {
                    String minfo = shipInfo(bx, by, bz);
                    double cross = crossSideDelta(readDouble(minfo, POS_X),
                            readDouble(minfo, POS_Y), readDouble(minfo, POS_Z));
                    if (!Double.isNaN(cross)) {
                        moveCrossMax = Math.max(moveCrossMax, cross);
                        moveCrossSum += cross;
                        moveCrossN++;
                    }
                    moveTrace.append(String.format(Locale.ROOT, "[t%d skew=%.4f cross=%.4f %s] ",
                            i * 3, skew, cross, mode));
                }
            }
            String after = shipInfo(bx, by, bz);
            System.out.println(String.format(Locale.ROOT,
                    "[poseskew] moving climb=%.1f skewMax=%.4f crossMax=%.4f crossMean=%.4f (n=%d)"
                            + " :: %s", climb, moveSkewMax, moveCrossMax,
                    moveCrossN == 0 ? -1.0 : moveCrossSum / moveCrossN, moveCrossN, moveTrace));
            System.out.println("[poseskew] moving ship-info=" + after);
        }
        exec("artest vs force-vel 0 " + sx + " " + sy + " " + sz + " 0 0 0");

        assertTrue("the skew instrument must fire in HULL mode (samples=" + hullSamples
                + " hullModeSeen=" + hullModeSeen + "): " + hullTrace, hullModeSeen > 0);
        // The contract: the hull a body stands against is the hull the player SEES. A skew past
        // the visible bound is the reported bug — walking beside the drawn blocks.
        assertTrue("a body hull-standing on a hovering ship must stand on the surface the renderer "
                + "draws — render-vs-collision pose skew max=" + hullMax + " (control at rest="
                + restMax + ", bound=" + VISIBLE_SKEW + "): " + hullTrace,
                hullMax < VISIBLE_SKEW);
        // The lateral-offset report (walking the hull of a ~135-inverted ship "about a block
        // beside the visible blocks"): a hull-stand body is a WORLD-upright capsule, but the
        // sweep collides a SUBSPACE-aligned box — the two volumes sit h*sin(tilt/2) apart, so
        // every contact happens that far from where the player sees himself. At this leg's
        // ~160 degrees that is ~1.77 blocks; the contract is that the collision solid IS the
        // body's real volume.
        assertTrue("a hull-stand body must collide as its real world-upright volume, not a "
                + "subspace-aligned phantom displaced by h*sin(tilt/2) — measured mismatch="
                + hullMismatchMax + " (visible bound=" + VISIBLE_SKEW + ", upY=" + upY + "): "
                + hullTrace, hullMismatchMax < VISIBLE_SKEW);
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /** One cross-side pose sample: the client's most recent committed world position vs the
     *  SERVER's mapping of the same held subspace point ({@code artest vs to-world}, resolved via
     *  the ship at {@code (x,y,z)}). The two reads are a few ticks apart, so on a ship moving at
     *  {@code v} the sample carries an error of roughly {@code v * 0.15s} — read it for signals
     *  well above that. NaN when either side has no sample/ship. */
    private double crossSideDelta(double x, double y, double z) throws Exception {
        double subX = clientDouble(SHIP_FRAME_TRAVEL, "lastSkewSubX");
        double subY = clientDouble(SHIP_FRAME_TRAVEL, "lastSkewSubY");
        double subZ = clientDouble(SHIP_FRAME_TRAVEL, "lastSkewSubZ");
        double cx = clientDouble(SHIP_FRAME_TRAVEL, "lastSkewCommitX");
        double cy = clientDouble(SHIP_FRAME_TRAVEL, "lastSkewCommitY");
        double cz = clientDouble(SHIP_FRAME_TRAVEL, "lastSkewCommitZ");
        if (subX == 0.0 && subY == 0.0 && subZ == 0.0) {
            return Double.NaN; // no client sample yet
        }
        String tw = exec("artest vs to-world 0 " + x + " " + y + " " + z + " "
                + subX + " " + subY + " " + subZ);
        if (!tw.contains("\"ok\":true")) {
            return Double.NaN;
        }
        return distance(new double[]{cx, cy, cz}, new double[]{
                readDouble(tw, WORLD_X), readDouble(tw, WORLD_Y), readDouble(tw, WORLD_Z)});
    }

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
        System.out.println("[poseskew] ship at (" + bx + "," + by + "," + bz + ") -> "
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
