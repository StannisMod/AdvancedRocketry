package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Which bodies the client draws standing on a ship, and which it leaves upright.
 *
 * <p>The local player's model/eye gate on the movement truth (resolved ABOARD a deck), but a
 * REMOTE body has no capture state on this side at all - the client resolves only its own
 * player's movement. The gate for everyone else therefore used to be world-AABB CONTAINMENT,
 * which is true across the whole air volume around the hull: a body standing on the ground beside
 * an inverted ship was drawn lying on its side. The contract under test is the spatial one - a
 * body is drawn ship-aligned when the SHIP CARRIES IT, not when it happens to be inside the
 * ship's box.
 *
 * <p>The observable is client-side and cumulative ({@code ShipFrameCamera.remoteModel*}): over a
 * window, how many model-rotation decisions were taken for remote bodies and how many of those
 * pushed a rotation. A per-frame decision for an arbitrary body is a transient - a first/last-call
 * snapshot would land on an arbitrary moment and say nothing.
 *
 * <p>The two legs are each other's control, and the pairing is what makes either meaningful:
 * leg A (body on terrain) asserts NO remote body is rotated while proving the instrument fires at
 * all; leg B (body on the deck) asserts the same instrument DOES report a rotation for a carried
 * body. Leg A alone would pass just as well if the gate rejected everything, or if the body were
 * never rendered.
 *
 * <p>Gated on real VS - run with {@code -PwithVS}.</p>
 */
public class VSRemoteBodyModelGateE2ETest extends AbstractClientE2ETest {

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern ENTITY_ID = Pattern.compile("\"entityId\":(-?\\d+)");
    private static final Pattern OBSTACLES = Pattern.compile("\"shipSupportObstacles\":(-?\\d+)");
    private static final Pattern Q_X = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern Q_Z = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-deck";
    private static final String SHIP_CAMERA = "zmaster587.advancedRocketry.client.ShipFrameCamera";

    /** A roll steep enough that a wrongly-rotated model is unmistakable (~160 deg): at a shallow
     *  tilt the identity and the ship attitude are nearly the same rotation, so a level ship
     *  cannot falsify anything here. */
    private static final String STEEP_ROLL = "0.17365 0.0 0.0 0.98481";

    // ---- Leg 0 (instrument calibration): does this client draw remote bodies at all? -----------

    /**
     * The precondition every other leg silently depends on: that a remote body in view produces
     * model-rotation decisions on THIS client. No ship is involved - a lone stand on open ground,
     * looked at.
     *
     * <p>Without it, {@code modelRotationCalls == 0} is ambiguous in exactly the way that cost a
     * run here: it reads identically for "the {@code require = 0} mixin never applied", "the
     * headless client draws no entity models", and "the subject was not there to be drawn". A
     * control that can come back "no" is what separates them, and it belongs in the suite
     * permanently - the day a render mod or an ordinal drift kills the hook, THIS is the test that
     * says so, instead of the contract legs failing for a reason that is not their subject.</p>
     */
    @Test
    public void theClientDrawsRemoteBodiesAtAll() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        // The subject is placed where the PLAYER is standing, not at a hand-picked coordinate.
        // A first draft used a fixed (7820,65,7820) with a stone block under it and measured
        // clientEntities=1 - the client world held only the player: the terrain height there was
        // never checked, so the cow was most likely spawned inside rock. The player's own settled
        // position is by construction a loaded, air, standable spot, and it is READ from the
        // client rather than assumed.
        exec("kill @e[type=cow]");
        exec("tp @a 7820.5 80 7820.5 0 0");
        bot().waitTicks(40);
        // Settle FIRST, then aim, then spawn in front of the settled camera: teleporting after the
        // spawn re-streams chunks and entities, and the subject's arrival on the client is a race
        // that a fixed wait wins only sometimes (measured: clientEntities 2, then 1, same code).
        double[] me = clientPos();
        double x = me[0] + 4.0, y = me[1], z = me[2];
        aimAt(x, y, z);
        int subjectId = spawnSubject(x, y, z);

        // Liveness TIMELINE: "never registered" and "registered then died" need different fixes.
        System.out.println("[modelgate] t0   : " + probeLine(subjectId));
        bot().waitTicks(20);
        System.out.println("[modelgate] t20  : " + probeLine(subjectId)
                + " clientEntities=" + (int) clientDouble(SHIP_CAMERA, "clientLoadedEntities"));
        bot().waitTicks(100);
        System.out.println("[modelgate] t120 : " + probeLine(subjectId)
                + " clientEntities=" + (int) clientDouble(SHIP_CAMERA, "clientLoadedEntities"));

        // Gate on the MEASURED arrival, never on elapsed ticks.
        int seen = 0;
        for (int i = 0; i < 20 && seen < 2; i++) {
            bot().waitTicks(10);
            seen = (int) clientDouble(SHIP_CAMERA, "clientLoadedEntities");
        }
        // Server-side liveness, printed either way: "the client never got it" and "it died on the
        // server" are different problems, and the client-side count alone cannot tell them apart.
        String alive = exec("artest vs deck-capture 0 " + subjectId);
        System.out.println("[modelgate] subject server-side: " + alive.replace('\n', ' '));
        assertTrue("the subject never arrived in the CLIENT world (clientEntities=" + seen
                + " after 200 ticks). Server-side probe for the same id: " + alive,
                seen > 1);

        long calls0 = (long) clientDouble(SHIP_CAMERA, "modelRotationCalls");
        long remote0 = (long) clientDouble(SHIP_CAMERA, "remoteModelSamples");
        long camera0 = (long) clientDouble(SHIP_CAMERA, "cameraHookCalls");
        bot().waitTicks(60);
        long calls = (long) clientDouble(SHIP_CAMERA, "modelRotationCalls") - calls0;
        long remote = (long) clientDouble(SHIP_CAMERA, "remoteModelSamples") - remote0;

        int installed = (int) clientDouble(SHIP_CAMERA, "modelGateInstalledFlag");
        long cameraFrames = (long) clientDouble(SHIP_CAMERA, "cameraHookCalls") - camera0;
        int clientEntities = (int) clientDouble(SHIP_CAMERA, "clientLoadedEntities");
        System.out.println("[modelgate] calibration: installed=" + installed
                + " cameraFrames=" + cameraFrames + " clientEntities=" + clientEntities
                + " calls=" + calls + " remote=" + remote);

        // Controls BEFORE the conclusion. A zero on the draw-stage counter has three causes and
        // they are not interchangeable; naming the wrong one sent an earlier version of this test
        // to the ledger as a "measured harness limit" it had not measured.
        assertTrue("no camera-stage frame ran at all in this window (cameraFrames=" + cameraFrames
                        + ") - the client is not rendering, which is a harness/environment problem, "
                        + "not a statement about the model gate",
                cameraFrames > 0);
        // > 1, not > 0: the client world always holds the player itself, so "at least one entity"
        // is satisfied even when the subject is absent - which is exactly how an earlier version
        // of this control passed while the cow was not there at all.
        assertTrue("the CLIENT world holds only the player (clientEntities=" + clientEntities
                        + ") - the subject never reached this side, so a draw-stage zero says "
                        + "nothing about whether models are drawn",
                clientEntities > 1);

        // Separate the two ways this can read zero, in order. Installed-but-silent and
        // never-installed are different defects with different owners, and a bare "no samples"
        // conflates them - which cost two runs here before this check existed.
        assertTrue("the model-roll hook is NOT woven into RenderLivingBase at all: the require = 0 "
                        + "mixin did not apply, so the deck-roll feature is silently absent on this "
                        + "client. That is a production defect, not a test-fixture problem",
                installed == 1);
        // MEASURED 2026-07-20: installed=1, calls=0. The hook is woven in and the client's camera
        // hooks demonstrably fire (other e2es read shipCamActive), but RenderLivingBase.applyRotations
        // never runs - this headless client does not draw entity MODELS. That is a harness limit,
        // not a verdict on the gate, so the contract legs below skip rather than lie in either
        // direction. They are written, compiled and ready: the day the harness draws entity models,
        // deleting nothing makes them run.
        Assume.assumeTrue("harness limit: this client does not draw entity models (hook installed="
                        + installed + ", applyRotations calls=" + calls + " over 60 ticks), so the "
                        + "remote-body model gate is not observable here",
                remote > 0);
    }

    // ---- Leg A: the bug - a body the ship does NOT carry must not be drawn ship-aligned --------

    @Test
    public void aBodyStandingOnTerrainBesideARolledShipIsNotDrawnShipAligned() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 7420, by = 64, bz = 7420;

        assumeEntityModelsAreDrawn();
        double[] ship = buildShip(bx, by, bz);
        rollShip(bx, by, bz);

        // The subject sits on WORLD TERRAIN beside the hull, close enough that the ship's grown
        // world box covers it - that overlap IS the bug's precondition, so it is measured, never
        // assumed: a body spawned merely "near" a ship would make a green run vacuous.
        // The valid spot is SEARCHED FOR, not assumed: the ship's world box at a 160 deg roll is
        // not where a hand-picked offset guesses (the first draft put the stand 3 blocks aside at
        // y=64 and it landed clean outside containment). Both halves of validity are load-bearing:
        //  - inside containment, or the OLD gate would never have rotated it and green is vacuous;
        //  - ZERO standing support, or it is a carried body and belongs to leg B.
        int subject = -1;
        String contact = "";
        StringBuilder tried = new StringBuilder();
        for (double[] spot : terrainSpotsBeside(ship)) {
            // Exactly ONE stand may exist while sampling: a rejected candidate left standing
            // somewhere supported would rotate legitimately and read as a red on the subject.
            exec("kill @e[type=cow]");
            // Snap to the block grid and stand the body exactly ON the placed floor: the previous
            // draft spawned at the raw (fractional) height with the block a full floor() below, so
            // the subject hung ~1.7 blocks above its own support and the probe honestly reported
            // supportedByWorldTerrain=false.
            spot[1] = Math.floor(spot[1]);
            floorUnder(spot);
            int candidate = spawnSubject(spot[0], spot[1], spot[2]);
            String probe = exec("artest vs deck-capture 0 " + candidate);
            tried.append(String.format(java.util.Locale.ROOT, "[%.1f,%.1f,%.1f contain=%s obst=%d]",
                    spot[0], spot[1], spot[2], probe.contains("\"aboardByContainment\":true"),
                    readInt(probe, OBSTACLES)));
            if (probe.contains("\"aboardByContainment\":true") && readInt(probe, OBSTACLES) == 0) {
                subject = candidate;
                contact = probe;
                break;
            }
        }
        assertTrue("no spot beside this ship was both INSIDE its containment and unsupported - "
                        + "leg A cannot be staged on this fixture; tried " + tried,
                subject >= 0);
        assertTrue("the chosen spot must sit on world terrain: " + contact,
                contact.contains("\"supportedByWorldTerrain\":true"));

        lookAt(ship[0], ship[1], ship[2]); // the model must actually be rendered to be decided about
        long[] before = remoteCounters();
        bot().waitTicks(60);
        long[] after = remoteCounters();

        long samples = after[1] - before[1];
        long rotated = after[2] - before[2];
        // Instrument-fires check FIRST, and split by cause: a zero here would otherwise make the
        // rotated==0 assertion below true for the wrong reason — prove the instrument fires before
        // believing the zero it reports.
        assertInstrumentFired(before, after);
        assertTrue("a body on world terrain beside a rolled ship must NOT be drawn ship-aligned: "
                        + rotated + "/" + samples + " decisions pushed a rotation; trace="
                        + clientString(SHIP_CAMERA, "remoteModelTrace"),
                rotated == 0);
    }

    // ---- Leg B (control): the gate must still rotate a body the ship DOES carry ----------------

    @Test
    public void aBodyCarriedByARolledDeckIsStillDrawnShipAligned() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 7620, by = 64, bz = 7620;

        assumeEntityModelsAreDrawn();
        double[] ship = buildShip(bx, by, bz);
        // Put the subject on the deck BEFORE the roll: it rides the deck up with the ship, which is
        // how a crew member gets to a steep deck in play. Spawning onto an already-inverted deck
        // would need a world point that is only derivable through the ship transform.
        int subject = spawnSubjectOnDeck(ship);
        rollShip(bx, by, bz);

        String contact = exec("artest vs deck-capture 0 " + subject);
        assertTrue("the subject must be CARRIED by the ship for the control to mean anything: " + contact,
                readInt(contact, OBSTACLES) > 0);

        lookAt(ship[0], ship[1], ship[2]);
        long[] before = remoteCounters();
        bot().waitTicks(60);
        long[] after = remoteCounters();

        long samples = after[1] - before[1];
        long rotated = after[2] - before[2];
        assertInstrumentFired(before, after);
        assertTrue("a body carried by a steeply rolled deck must still be drawn ship-aligned: "
                        + rotated + "/" + samples + " decisions pushed a rotation",
                rotated > 0);
        assertTrue("the pushed rotation must be the ship's real attitude, not a token tilt: max="
                        + clientDouble(SHIP_CAMERA, "maxRemoteModelRotationDeg"),
                clientDouble(SHIP_CAMERA, "maxRemoteModelRotationDeg") > 90.0);
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /** The contract legs are only meaningful where the client actually draws entity models. Measured
     *  false on this harness (see the calibration leg) - so they SKIP with a named reason instead of
     *  passing vacuously, which is what an unguarded "rotated == 0" would do here. */
    private void assumeEntityModelsAreDrawn() throws Exception {
        long calls0 = (long) clientDouble(SHIP_CAMERA, "modelRotationCalls");
        bot().waitTicks(20);
        long calls = (long) clientDouble(SHIP_CAMERA, "modelRotationCalls") - calls0;
        Assume.assumeTrue("harness limit: this client draws no entity models (applyRotations calls="
                + calls + "); the remote-body model gate is not observable here", calls > 0);
    }

    /** {@code {modelRotationCalls, remoteModelSamples, remoteModelRotatedSamples}} as the client
     *  holds them now. The first element is the mixin-applied discriminator. */
    private long[] remoteCounters() throws Exception {
        return new long[]{
                (long) clientDouble(SHIP_CAMERA, "modelRotationCalls"),
                (long) clientDouble(SHIP_CAMERA, "remoteModelSamples"),
                (long) clientDouble(SHIP_CAMERA, "remoteModelRotatedSamples")};
    }

    /** Fail with the RIGHT diagnosis when nothing was sampled: a silent {@code require = 0} mixin
     *  miss and "the body was never rendered" both present as zero remote samples, and they are
     *  different bugs. */
    private void assertInstrumentFired(long[] before, long[] after) {
        long calls = after[0] - before[0];
        long samples = after[1] - before[1];
        assertTrue("the applyRotations hook never ran in this window (calls=0) - the model gate is "
                        + "not installed at all (require = 0 mixin miss), so nothing here can be "
                        + "concluded about the gate's DECISION",
                calls > 0);
        assertTrue("the hook ran (" + calls + " calls) but decided about no REMOTE body - the "
                        + "subject was never drawn, so this leg proves nothing",
                samples > 0);
    }

    /** Hold the ship at a steep roll and wait for the attitude to actually CONVERGE - the stimulus
     *  depends on the fixture's dynamic state, so it gates on the measured attitude, never on a
     *  tick count (under suite load the slew takes longer than any fixed wait). */
    private void rollShip(int bx, int by, int bz) throws Exception {
        assertTrue("attitude hold must accept the steep roll",
                exec("artest vs point 0 " + bx + " " + by + " " + bz + " " + STEEP_ROLL)
                        .contains("\"commanded\":true"));
        double upY = 1.0;
        for (int i = 0; i < 60 && upY > -0.85; i++) {
            bot().waitTicks(10);
            // The ship's own up, world-frame, from the attitude quaternion the probe reports.
            String info = shipInfo(bx, by, bz);
            double qx = readDouble(info, Q_X), qz = readDouble(info, Q_Z);
            upY = 1.0 - 2.0 * (qx * qx + qz * qz);
        }
        assertTrue("the ship must reach the steep roll for either leg to mean anything (upY=" + upY + ")",
                upY < -0.85);
    }

    /** Candidate spots beside the ship, nearest first: the one that is inside the ship's world box
     *  AND unsupported is found by probing these, never assumed. A rolled 35-block ship's box is
     *  small and its position is not the base coordinate, so a single hand-picked offset misses. */
    private java.util.List<double[]> terrainSpotsBeside(double[] ship) {
        java.util.List<double[]> spots = new java.util.ArrayList<double[]>();
        // Sweep HEIGHT too. The first draft searched the terrain plane only and every one of 16
        // spots came back outside containment: an assembled, rolled ship sits well above the
        // ground, so at y=65 its box simply is not there. The body does not have to stand on
        // natural ground - a world block placed under it is world support just the same, and
        // world blocks inside a ship's world AABB are independent of it (ship blocks live in
        // subspace).
        for (double dy : new double[]{1.0, 2.0, 3.0, 4.0, 0.0, 5.0}) {
            for (double r : new double[]{1.5, 2.5, 3.5}) {
                for (double[] dir : new double[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    spots.add(new double[]{ship[0] + dir[0] * r, ship[1] + dy, ship[2] + dir[1] * r});
                }
            }
        }
        return spots;
    }

    /** Put a world block under {@code spot} so a body there is supported by the WORLD, whatever the
     *  ship's box does. Returns false when the fill did not take. */
    private boolean floorUnder(double[] spot) throws Exception {
        int fx = (int) Math.floor(spot[0]), fy = (int) Math.floor(spot[1]) - 1, fz = (int) Math.floor(spot[2]);
        return exec("artest fill 0 " + fx + " " + fy + " " + fz + " " + fx + " " + fy + " " + fz
                + " minecraft:stone").contains("\"ok\":true");
    }

    /** Spawn the subject mob on the fixture's deck and return its entity id. */
    private int spawnSubjectOnDeck(double[] ship) throws Exception {
        return spawnSubject(ship[0], ship[1] + 2.0, ship[2]);
    }

    private int spawnSubject(double x, double y, double z) throws Exception {
        // A COW is the subject, and the choice is load-bearing. The gate hooks
        // RenderLivingBase.applyRotations; RenderArmorStand OVERRIDES that method and never calls
        // super, so a stand is drawn without the hook ever running - a first draft used one and
        // measured a flat zero on a client that was rendering perfectly well. RenderCow inherits
        // the method (as does RenderPlayer on its normal branch), so a cow exercises the same code
        // path a remote crew member does.
        String spawned = exec("artest vs drop-living 0 minecraft:cow " + x + " " + y + " " + z);
        System.out.println("[modelgate] spawn raw: " + spawned.replace('\n', ' '));
        assertTrue("the subject mob must spawn: " + spawned, spawned.contains("\"ok\":true"));
        bot().waitTicks(20);
        Matcher m = ENTITY_ID.matcher(spawned);
        assertTrue("spawn must report an entity id: " + spawned, m.find());
        return Integer.parseInt(m.group(1));
    }

    /** Teleport beside a world position and aim at it. Used by the ship legs, where the camera has
     *  to be moved to the fixture first. */
    private void lookAt(double x, double y, double z) throws Exception {
        exec("tp @a " + (x + 8) + " " + (y + 3) + " " + (z + 8) + " 0 0");
        bot().waitTicks(20);
        aimAt(x, y, z);
    }

    /** Aim the client at a world position WITHOUT moving it, and verify the aim took. */
    private void aimAt(double x, double y, double z) throws Exception {
        double[] me = clientPos();
        double dx = x - me[0], dy = y - me[1], dz = z - me[2];
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        bot().setLook(yaw, pitch);
        bot().waitTicks(20);

        // Read the look BACK. Setting it is not the same as it taking effect, and an unverified
        // aim is one more way for a draw-stage zero to mean nothing: a subject behind the camera
        // is culled and never drawn, which looks identical to "models are not drawn at all".
        com.google.gson.JsonObject st = bot().reportState();
        double gotYaw = st.get("playerYaw").getAsDouble(), gotPitch = st.get("playerPitch").getAsDouble();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[modelgate] look: want=(%.1f,%.1f) got=(%.1f,%.1f) dist=%.1f", yaw, pitch, gotYaw, gotPitch, dist));
        assertTrue(String.format(java.util.Locale.ROOT,
                        "the client must actually be aimed at the subject: wanted yaw %.1f, got %.1f",
                        yaw, gotYaw),
                Math.abs(wrap180(gotYaw - yaw)) < 15.0);
    }

    private static double wrap180(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    /** One-line server-side liveness read for an entity id. */
    private String probeLine(int id) throws Exception {
        String r = exec("artest vs deck-capture 0 " + id);
        int b = r.indexOf('{');
        return b < 0 ? r : r.substring(b, Math.min(r.length(), b + 150));
    }

    private double[] clientPos() throws Exception {
        com.google.gson.JsonObject st = bot().reportState();
        return new double[]{st.get("playerX").getAsDouble(), st.get("playerY").getAsDouble(),
                st.get("playerZ").getAsDouble()};
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
        assertTrue("a " + VARIANT + " build must route to a ship: " + assemble,
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
        System.out.println("[modelgate] ship at (" + bx + "," + by + "," + bz + ") -> "
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
