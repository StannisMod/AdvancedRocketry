package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.TestTimeouts;

import org.junit.Assume;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

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
 * leg A (body on terrain) asserts NO remote body is rotated; leg B (body on the deck) asserts the
 * same instrument DOES report a rotation for a carried body. Each leg proves the instrument fired
 * for ITS OWN subject via {@link #assertInstrumentFired} (samples &gt; 0) BEFORE trusting the
 * rotation count, so a zero can never pass either leg vacuously - leg A would otherwise pass just as
 * well if the gate rejected everything, or if the body were never rendered.
 *
 * <p>An earlier "leg 0" spawned a LONE cow on open ground as a separate control that the client
 * draws remote bodies at all. It was removed: each contract leg's {@code assertInstrumentFired}
 * already covers "the hook fired", and it covers the real gameplay case (a body near a ship) rather
 * than a no-ship scenario that never occurs in play. That lone-body leg was also the only flaky one
 * here - a lone subject on open ground was not reliably sampled when this class runs its methods in
 * one shared client after the ship-building legs (a teleport/render-settle race), while the
 * ship-anchored legs sample reliably.
 *
 * <p>Gated on real VS - run with {@code -PwithVS}.</p>
 *
 * <p><b>Known limitation, REVISIT WHEN VALKYRIEN SKIES SOURCE IS AVAILABLE.</b> Under the parallel
 * client gate (many client JVMs contending for one GPU) leg A's subject was intermittently never
 * DRAWN: measured, a red window rendered 543 frames yet ran {@code RenderLivingBase.applyRotations}
 * zero times for any living body, while the same client drew leg B's carried cow fine. So a world
 * entity standing inside a steeply-rolled ship's world AABB (leg A's precondition) is sometimes absent
 * from the vanilla living-render dispatch. The camera→subject offset is fixed, so it is not a frustum
 * miss; the suspected cause is Valkyrien Skies' per-frame handling of world entities that fall inside a
 * ship's world box - but VS is jar-only here, so the exact render path was not opened. Leg A works
 * around it by re-staging the subject until the client provably draws it (see {@link #MAX_STAGINGS});
 * that keeps the test honest without root-causing a symptom that needs GPU contention to appear and so
 * does not reach a single-client player. When VS is vendored as source, root-cause the render path and
 * decide whether the re-stage workaround can be retired.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSRemoteBodyModelGateE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-remote-body-render";
    }


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

    /**
     * THIS scenario's ship, by identity — captured by {@code buildShip} at the one moment its base
     * provably holds no other, and the address every later question and command uses. A radius bound
     * is a mitigation, not an identity: these scenarios roll, hover and drop the ship on purpose, and
     * a shared client always has a neighbour in candidacy.
     */
    private String scenarioShipId;
    private static final String SHIP_CAMERA = "zmaster587.advancedRocketry.client.ShipFrameCamera";

    /** A roll steep enough that a wrongly-rotated model is unmistakable (~160 deg): at a shallow
     *  tilt the identity and the ship attitude are nearly the same rotation, so a level ship
     *  cannot falsify anything here. */
    private static final String STEEP_ROLL = "0.17365 0.0 0.0 0.98481";

    // ---- Leg A: the bug - a body the ship does NOT carry must not be drawn ship-aligned --------

    @Test
    public void aBodyStandingOnTerrainBesideARolledShipIsNotDrawnShipAligned() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 7420, by = 64, bz = 7420;

        double[] ship = buildShip(bx, by, bz);
        rollShip(bx, by, bz);

        // Collect EVERY spot beside the hull that is valid for leg A - sitting on WORLD TERRAIN, inside
        // the ship's grown world box (the bug's precondition) with ZERO ship support (a carried body
        // belongs to leg B). The valid set is SEARCHED FOR, not assumed: the ship's world box at a
        // 160 deg roll is not where a hand-picked offset guesses (the first draft put the stand 3 blocks
        // aside at y=64 and it landed clean outside containment). Both halves of validity are measured
        // on the server, never assumed - a body merely "near" a ship, or one the ship actually carries,
        // would make a green run vacuous. Collecting the WHOLE set (not the first match) gives the
        // re-stage loop below fresh spots to try.
        java.util.List<double[]> valid = new java.util.ArrayList<double[]>();
        StringBuilder tried = new StringBuilder();
        for (double[] spot : terrainSpotsBeside(ship)) {
            // Exactly ONE stand may exist while probing: a rejected candidate left standing somewhere
            // supported would rotate legitimately and read as a red on the subject.
            exec("kill @e[type=cow]");
            // Snap to the block grid and stand the body exactly ON the placed floor: the previous draft
            // spawned at the raw (fractional) height with the block a full floor() below, so the subject
            // hung ~1.7 blocks above its own support and the probe honestly reported
            // supportedByWorldTerrain=false.
            spot[1] = Math.floor(spot[1]);
            floorUnder(spot);
            int candidate = spawnSubject(spot[0], spot[1], spot[2]);
            String probe = exec("artest vs deck-capture 0 " + candidate);
            boolean contained = probe.contains("\"aboardByContainment\":true");
            boolean unsupported = readInt(probe, OBSTACLES) == 0;
            boolean onTerrain = probe.contains("\"supportedByWorldTerrain\":true");
            tried.append(String.format(java.util.Locale.ROOT,
                    "[%.1f,%.1f,%.1f contain=%s obst=%d terr=%s]", spot[0], spot[1], spot[2],
                    contained, readInt(probe, OBSTACLES), onTerrain));
            if (contained && unsupported && onTerrain) {
                valid.add(spot);
            }
        }
        assertTrue("no spot beside this ship was INSIDE its containment, unsupported AND on world "
                        + "terrain - leg A cannot be staged on this fixture; tried " + tried,
                !valid.isEmpty());

        // Stage the MEASURED body in front of an already-settled camera, and require the client to
        // actually DRAW it before measuring the gate. That a body inside a ship's world AABB is drawn
        // through the vanilla living path is a SETUP precondition here, not the contract under test:
        // under concurrent-fork load it is intermittently NOT drawn at all (measured: 543 frames
        // rendered, ZERO applyRotations on the only living body in the window), which is a
        // render-observability gap on the subject, not the gate deciding to rotate it. The
        // camera-to-subject offset is fixed, so this is not a framing/frustum miss; it tracks the
        // subject's spot and VS's per-frame state for a world entity inside a ship box. Re-stage at a
        // FRESH valid spot until one is drawn, under a bounded budget; the rotation contract below is
        // then measured only on a body the client provably rendered. Each staging spawns AFTER the
        // camera settles (a teleport re-streams entities; spawning in front of a settled camera removes
        // that race at its source) and aims at the SUBJECT (the decision under test is about THIS body's
        // model, off to one side of a steeply rolled hull).
        long[] before = null, after = null;
        StringBuilder staging = new StringBuilder();
        int drawAttempts = 0;
        for (double[] spot : valid) {
            exec("kill @e[type=cow]");
            lookAt(spot[0], spot[1], spot[2]);
            int subject = spawnSubject(spot[0], spot[1], spot[2]);
            // Re-probe the FINAL body: validity was established while probing candidates; it is this
            // entity the assertions speak about. VS jitters a ship's world box between the collect loop
            // and here, so a spot valid a moment ago can drift off precondition - skip it WITHOUT
            // spending a draw attempt (no render was staged), a green here would be vacuous.
            String contact = exec("artest vs deck-capture 0 " + subject);
            if (!(contact.contains("\"aboardByContainment\":true") && readInt(contact, OBSTACLES) == 0)) {
                staging.append(String.format(java.util.Locale.ROOT,
                        "[%.1f,%.1f,%.1f precondition-drifted]", spot[0], spot[1], spot[2]));
                System.out.println(String.format(java.util.Locale.ROOT,
                        "[modelgate] legA spot [%.1f,%.1f,%.1f] drifted off precondition, trying next",
                        spot[0], spot[1], spot[2]));
                continue;
            }
            drawAttempts++;
            Sampling s = awaitRemoteSampling();
            // Print every DRAW attempt so a GREEN run still proves whether the re-stage FIRED for the
            // render cull: a lone "DRAWN" is a natural first-try render (fix idle), while a
            // "not drawn ...subject-culled..." line FOLLOWED by a later "DRAWN" is the re-stage
            // recovering a would-be-red run - the direct evidence the cull is per-spot, not run-global
            // (ledger #101). Without it a pass is silent about the fix and could be the muffler, not the cure.
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[modelgate] legA draw attempt %d at [%.1f,%.1f,%.1f] -> %s",
                    drawAttempts, spot[0], spot[1], spot[2], s.drawn ? "DRAWN" : "not drawn " + s.diagnostic));
            if (s.drawn) {
                before = remoteCounters();
                bot().waitTicks(60);
                after = remoteCounters();
                break;
            }
            staging.append(String.format(java.util.Locale.ROOT, "[attempt %d %s]", drawAttempts, s.diagnostic));
            if (drawAttempts >= MAX_STAGINGS) {
                break;
            }
        }
        // Summarise on PASS too, so a recovered cull is on the record even when the assertion is green.
        System.out.println("[modelgate] legA staging summary: "
                + (after != null ? "DREW after " + drawAttempts + " draw-attempt(s)" : "NEVER DREW")
                + " | " + staging);
        assertTrue("no valid terrain spot beside this ship had its body DRAWN by the client within the "
                        + "load-scaled window after " + drawAttempts + " draw attempt(s) - a render-"
                        + "observability gap for a world body inside a ship box, NOT a gate decision. "
                        + "Per-spot: " + staging + " | client cows=" + safeReportCows(),
                after != null);

        long samples = after[1] - before[1];
        long rotated = after[2] - before[2];
        // Instrument-fires check FIRST, and split by cause: a zero here would otherwise make the
        // rotated==0 assertion below true for the wrong reason (spike-experiment-design: prove the
        // instrument fires before believing its zero).
        assertInstrumentFired(before, after);
        assertTrue("a body on world terrain beside a rolled ship must NOT be drawn ship-aligned: "
                        + rotated + "/" + samples + " decisions pushed a rotation; trace="
                        + clientString(SHIP_CAMERA, "remoteModelTrace"),
                rotated == 0);
    }

    /** Re-stage the subject at most this many times when the client does not draw it (ledger #101). At
     *  the measured ~2/3 per-spot draw rate under load, three fresh spots drive a spurious "never drawn"
     *  below ~4 %, while a run-GLOBAL cull still exhausts the budget and self-reports it. */
    private static final int MAX_STAGINGS = 3;

    // ---- Leg B (control): the gate must still rotate a body the ship DOES carry ----------------

    @Test
    public void aBodyCarriedByARolledDeckIsStillDrawnShipAligned() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        final int bx = 7620, by = 64, bz = 7620;

        double[] ship = buildShip(bx, by, bz);
        // Put the subject on the deck BEFORE the roll: it rides the deck up with the ship, which is
        // how a crew member gets to a steep deck in play. Spawning onto an already-inverted deck
        // would need a world point that is only derivable through the ship transform.
        int subject = spawnSubjectOnDeck(bx, by, bz);
        rollShip(bx, by, bz);

        String contact = exec("artest vs deck-capture 0 " + subject);
        assertTrue("the subject must be CARRIED by the ship for the control to mean anything: " + contact,
                readInt(contact, OBSTACLES) > 0);

        lookAt(ship[0], ship[1], ship[2]);
        Sampling s = awaitRemoteSampling();
        assertTrue("the carried subject was never drawn by the client, so this control proves nothing: "
                        + s.diagnostic + " | client cows=" + safeReportCows(),
                s.drawn);
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

    /** The outcome of {@link #awaitRemoteSampling}: whether the client actually DREW the staged subject
     *  (the model-rotation hook sampled a remote body), and — when it did not — a self-classifying
     *  diagnostic naming which render stage was dead so a caller need not hypothesise. */
    private static final class Sampling {
        final boolean drawn;
        final String diagnostic;

        Sampling(boolean drawn, String diagnostic) {
            this.drawn = drawn;
            this.diagnostic = diagnostic;
        }
    }

    /** PRECONDITION gate: wait until the model-rotation hook is actually SAMPLING the staged remote
     *  body, so the measurement window that follows opens on a subject the client is already drawing.
     *  Under shared-harness load the subject's first rendered frame can lag the look, and a window
     *  opened before that reads zero samples on a client that draws models perfectly well.
     *
     *  <p>Returns {@link Sampling#drawn}=false rather than asserting, so the caller can RE-STAGE at a
     *  fresh spot (ledger #101: a world body inside a ship box is intermittently not drawn under load).
     *  When it returns false the diagnostic classifies the miss over the polled window from the two
     *  render-stage controls — {@code cameraHookCalls} (frames) and {@code modelRotationCalls} (every
     *  living model, player included) — so a red run names its own failure stage:
     *  frames==0 → the draw stage is dead; frames&gt;0,models==0 → frames ran but no living model was
     *  drawn (applyRotations unreached); frames&gt;0,models&gt;0 → models ARE drawn but this subject is
     *  not (culled / absent from the render list).
     *
     *  <p>Only the precondition is polled — the measurement window the caller opens afterwards stays a
     *  FIXED wait, deliberately. The value polled here ({@code remoteModelSamples}) is NOT what either
     *  leg asserts on: {@code remoteModelRotatedSamples} is, read from that later window. Ending it
     *  early on a samples predicate would move what the assertion sees — leg A's {@code rotated == 0}
     *  gets easier the fewer samples it saw, and leg B's {@code rotated > 0} can exit before the first
     *  ROTATED frame lands. That is exactly the case in which the fixed wait must stay.</p> */
    private Sampling awaitRemoteSampling() throws Exception {
        // First the subject must have ARRIVED on this side. Both legs spawn it and only then move the
        // camera, and a teleport re-streams chunks AND entities - so the body reaches the client after
        // a race a fixed wait wins only sometimes. > 1 because the client world always holds the player.
        ClientPoll.Result<Long> arrived = ClientPoll.until(bot()::waitTicks,
                () -> (long) clientDouble(SHIP_CAMERA, "clientLoadedEntities"),
                v -> v > 1, 10, 12);
        if (!arrived.satisfied) {
            return new Sampling(false, "[subject never reached the CLIENT world (" + arrived + ")]");
        }

        final long start = (long) clientDouble(SHIP_CAMERA, "remoteModelSamples");
        final long framesBefore = (long) clientDouble(SHIP_CAMERA, "cameraHookCalls");
        final long modelsBefore = (long) clientDouble(SHIP_CAMERA, "modelRotationCalls");
        ClientPoll.Result<Long> r = ClientPoll.until(bot()::waitTicks,
                () -> (long) clientDouble(SHIP_CAMERA, "remoteModelSamples"),
                v -> v > start, 15, 8);
        if (r.satisfied) {
            return new Sampling(true, "");
        }
        long frames = (long) clientDouble(SHIP_CAMERA, "cameraHookCalls") - framesBefore;
        long models = (long) clientDouble(SHIP_CAMERA, "modelRotationCalls") - modelsBefore;
        long loaded = (long) clientDouble(SHIP_CAMERA, "clientLoadedEntities");
        String verdict = frames == 0 ? "draw-stage-dead(no frames)"
                : models == 0 ? "no-living-model-drawn(applyRotations unreached)"
                : "subject-culled(models drawn, subject absent from render list)";
        return new Sampling(false, String.format(java.util.Locale.ROOT,
                "[%s %s frames+=%d models+=%d loaded=%d]", verdict, r, frames, models, loaded));
    }

    /** Client-side positions of every cow the client currently sees, for a red-run diagnostic. Best
     *  effort: a probe failure must not mask the assertion it is annotating. */
    private String safeReportCows() {
        try {
            return bot().reportEntities("Cow", 80.0).toString();
        } catch (Exception e) {
            return "reportEntities(Cow) failed: " + e;
        }
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
                exec("artest vs point-by-id 0 " + scenarioShipId + " " + STEEP_ROLL)
                        .contains("\"commanded\":true"));
        double upY = 1.0;
        for (int i = 0; i < 60 && upY > -0.85; i++) {
            bot().waitTicks(10);
            // The ship's own up, world-frame, from the attitude quaternion the probe reports.
            String info = shipInfo();
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

    /** Spawn the subject mob ON the fixture's iron deck (built at {@code rocketY+3 = baseY+4}, walkable
     *  top at {@code baseY+5}, centred on {@code baseX+3 / baseZ+3}) and return its entity id.
     *
     *  <p>The deck's WORLD position is derived from the base, not from the ship-info reference point
     *  ({@code ship[1]} is the physics object's origin, not the deck floor — a {@code +2} offset off it
     *  floated the subject 3 blocks under the deck and read zero support). VS assembles the ship in
     *  place, so the deck blocks stay at their world coordinates until the roll. The derived height is
     *  then VERIFIED by the support probe (a small sweep tolerates a one-block VS settle), never
     *  assumed — a subject the ship does not actually carry would make this control leg vacuous.</p> */
    private int spawnSubjectOnDeck(int bx, int by, int bz) throws Exception {
        double cx = bx + 3 + 0.5, cz = bz + 3 + 0.5;
        int chosen = -1;
        StringBuilder tried = new StringBuilder();
        for (double y : new double[]{by + 5, by + 5.2, by + 6, by + 4.5, by + 7}) {
            exec("kill @e[type=cow]");
            int candidate = spawnSubject(cx, y, cz);
            String probe = exec("artest vs deck-capture 0 " + candidate);
            int obst = readInt(probe, OBSTACLES);
            tried.append(String.format(java.util.Locale.ROOT, "[y=%.1f obst=%d]", y, obst));
            if (obst > 0) {
                chosen = candidate;
                break;
            }
        }
        assertTrue("no height over the deck put the subject ON it (ship carries it, >=1 support "
                        + "obstacle); tried " + tried, chosen >= 0);
        return chosen;
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

        // Scale the assembly-convergence window by the fork factor (load-tail family): the VS assembly
        // queue lags past a fixed 200-tick wait on a loaded machine (measured: "was 0, now 0" red at 8
        // forks), and the early exit means an idle run still leaves at the same iteration it always did.
        int assembleIters = (int) Math.ceil(40 * TestTimeouts.factor());
        int all = shipsBefore;
        for (int i = 0; i < assembleIters && all <= shipsBefore; i++) {
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
        int loadIters = (int) Math.ceil(40 * TestTimeouts.factor());
        for (int i = 0; i < loadIters && where == null; i++) {
            bot().waitTicks(5);
            // The scenario's ONE positional lookup, at the only moment it is defensible: the ship
            // was just assembled here and has not moved. It yields an IDENTITY, and everything
            // afterwards is keyed on that.
            info = exec("artest vs ship-info 0 " + bx + " " + by + " " + bz
                    + " " + SHIP_QUERY_RADIUS);
            if (!info.contains("\"managed\":true")) {
                continue;
            }
            double[] candidate = {readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z)};
            String foundId = readShipId(info);
            if (distance(candidate, new double[]{bx, by, bz}) < 24.0 && foundId != null) {
                where = candidate;
                scenarioShipId = foundId;
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

    /** This scenario's ship, asked by identity — no distance term to be wrong about. */
    private String shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return shipInfoById(scenarioShipId);
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
