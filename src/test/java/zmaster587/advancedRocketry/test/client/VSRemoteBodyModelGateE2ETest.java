package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.TestTimeouts;
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
 * <p><b>On the re-stage loop.</b> Under the parallel client gate (many client JVMs contending for one
 * GPU) leg A's subject was once intermittently never DRAWN: a red window rendered 543 frames yet ran
 * {@code RenderLivingBase.applyRotations} zero times for any living body, while the same client drew
 * leg B's carried cow fine. That was read as Valkyrien Skies doing something per-frame with world
 * entities inside a ship's world box. Re-examined against the VS sources, no such path exists: VS's
 * {@code Frustum} overwrite is the identity for anything outside the ship-chunk region, its
 * {@code RenderGlobal} mixin touches neither {@code setupTerrain} nor {@code renderEntities}, and its
 * entity drag needs a real collision against ship blocks rather than mere containment in the box. The
 * symptom was also measured on a fixture at other coordinates, before the harness pinned its world
 * seed - i.e. on terrain that was regenerated per run. On the surveyed site this class uses now, 15
 * staged subjects across serial and 8-fork gate runs were drawn every time, with the render-stage
 * probe green on every gate ({@link #subjectRenderStage}) and zero drift from the spot.
 *
 * <p>So the re-stage loop stays (see {@link #MAX_STAGINGS}) as cheap insurance against a symptom that
 * cannot be shown absent, not as a workaround for a known defect - and it is no longer silent: every
 * attempt prints which render stage the subject passed and where the client held it, so a recurrence
 * names its own cause instead of needing this investigation again.</p>
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

    // ---- Leg A: the bug - a body the ship does NOT carry must not be drawn ship-aligned --------

    @Test
    public void aBodyStandingOnTerrainBesideARolledShipIsNotDrawnShipAligned() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        // Surveyed natural site (flat plains, pinned world seed) - see HarnessFixtureSitesTest.
        final int bx = 212, by = 64, bz = 7488;

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
        // it was once measured NOT drawn at all under concurrent-fork load (543 frames rendered, ZERO
        // applyRotations on the only living body in the window), which is a render-observability gap on
        // the subject, not the gate deciding to rotate it. That measurement is not reproducible on this
        // surveyed site (see the class javadoc), but absence cannot be shown, and staging costs little.
        // Re-stage at a FRESH valid spot until one is drawn, under a bounded budget; the contract is
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
            Sampling s = awaitRemoteSampling(subject, spot);
            // Print every DRAW attempt so a GREEN run still proves whether the re-stage FIRED for the
            // render cull: a lone "DRAWN" is a natural first-try render (fix idle), while a
            // "not drawn ...subject-culled..." line FOLLOWED by a later "DRAWN" is the re-stage
            // recovering a would-be-red run - the direct evidence the cull is per-spot, not run-global.
            // Without it a pass is silent about the staging and could be the muffler, not the cure.
            // The sightline prints on BOTH outcomes on purpose: a DRAWN attempt's near-zero drift is
            // the control that makes a not-drawn attempt's drift mean something.
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[modelgate] legA draw attempt %d at [%.1f,%.1f,%.1f] -> %s %s",
                    drawAttempts, spot[0], spot[1], spot[2],
                    s.drawn ? "DRAWN" : "not drawn " + s.diagnostic, s.sightline));
            if (s.drawn) {
                before = remoteCounters();
                bot().waitTicks(60);
                after = remoteCounters();
                break;
            }
            staging.append(String.format(java.util.Locale.ROOT, "[attempt %d %s %s]",
                    drawAttempts, s.diagnostic, s.sightline));
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

    /** Re-stage the subject at most this many times when the client does not draw it. Sized when the
     *  per-spot draw rate under load measured ~2/3: three fresh spots drove a spurious "never drawn"
     *  below ~4 %, while a run-GLOBAL cull still exhausts the budget and self-reports it. On the
     *  surveyed site the class uses now, 12 consecutive staged spots under an 8-fork gate were all
     *  drawn, so the budget is slack rather than load-bearing - kept because a rate measured on one
     *  fixture is not a guarantee about a machine under different contention. */
    private static final int MAX_STAGINGS = 3;

    // ---- Leg B (control): the gate must still rotate a body the ship DOES carry ----------------

    @Test
    public void aBodyCarriedByARolledDeckIsStillDrawnShipAligned() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)", serverHasVs());
        // Surveyed natural site (flat plains, pinned world seed) - see HarnessFixtureSitesTest.
        final int bx = 128, by = 63, bz = 7524;

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
        // The reference point here is the SHIP, not a fixed stand spot: this subject rides the deck
        // through the roll, so "drift" reads as where the client holds it relative to the hull it is
        // aimed at - the same sightline question, asked of a body that is supposed to move.
        Sampling s = awaitRemoteSampling(subject, ship);
        assertTrue("the carried subject was never drawn by the client, so this control proves nothing: "
                        + s.diagnostic + " " + s.sightline + " | client cows=" + safeReportCows(),
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
        /** Where the CLIENT held the subject when the window closed - see {@link #subjectSightline}.
         *  Carried on BOTH outcomes: the drawn case is the control that gives the not-drawn case its
         *  meaning ("when it IS drawn the subject sits on its spot"). */
        final String sightline;

        Sampling(boolean drawn, String diagnostic, String sightline) {
            this.drawn = drawn;
            this.diagnostic = diagnostic;
            this.sightline = sightline;
        }
    }

    /** PRECONDITION gate: wait until the model-rotation hook is actually SAMPLING the staged remote
     *  body, so the measurement window that follows opens on a subject the client is already drawing.
     *  Under shared-harness load the subject's first rendered frame can lag the look, and a window
     *  opened before that reads zero samples on a client that draws models perfectly well.
     *
     *  <p>Returns {@link Sampling#drawn}=false rather than asserting, so the caller can RE-STAGE at a
     *  fresh spot (a world body inside a ship box was once intermittently not drawn under load).
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
    private Sampling awaitRemoteSampling(int subjectId, double[] stagedAt) throws Exception {
        // First the subject must have ARRIVED on this side. Both legs spawn it and only then move the
        // camera, and a teleport re-streams chunks AND entities - so the body reaches the client after
        // a race a fixed wait wins only sometimes. > 1 because the client world always holds the player.
        ClientPoll.Result<Long> arrived = ClientPoll.until(bot()::waitTicks,
                () -> (long) clientDouble(SHIP_CAMERA, "clientLoadedEntities"),
                v -> v > 1, 10, 12);
        if (!arrived.satisfied) {
            return new Sampling(false, "[subject never reached the CLIENT world (" + arrived + ")]",
                    subjectSightline(subjectId, stagedAt));
        }

        final long start = (long) clientDouble(SHIP_CAMERA, "remoteModelSamples");
        final long framesBefore = (long) clientDouble(SHIP_CAMERA, "cameraHookCalls");
        final long modelsBefore = (long) clientDouble(SHIP_CAMERA, "modelRotationCalls");
        ClientPoll.Result<Long> r = ClientPoll.until(bot()::waitTicks,
                () -> (long) clientDouble(SHIP_CAMERA, "remoteModelSamples"),
                v -> v > start, 15, 8);
        if (r.satisfied) {
            return new Sampling(true, "", subjectSightline(subjectId, stagedAt));
        }
        long frames = (long) clientDouble(SHIP_CAMERA, "cameraHookCalls") - framesBefore;
        long models = (long) clientDouble(SHIP_CAMERA, "modelRotationCalls") - modelsBefore;
        long loaded = (long) clientDouble(SHIP_CAMERA, "clientLoadedEntities");
        String verdict = frames == 0 ? "draw-stage-dead(no frames)"
                : models == 0 ? "no-living-model-drawn(applyRotations unreached)"
                : "subject-culled(models drawn, subject absent from render list)";
        return new Sampling(false, String.format(java.util.Locale.ROOT,
                "[%s %s frames+=%d models+=%d loaded=%d]", verdict, r, frames, models, loaded),
                subjectSightline(subjectId, stagedAt));
    }

    /** Where the CLIENT actually holds the staged subject right now, how far that is from the spot it
     *  was staged at, and how far off the camera's own axis it sits.
     *
     *  <p>This is the DISCRIMINATOR a "not drawn" window was missing. The standing reading of that
     *  window - "the camera-to-spot offset is fixed, so a zero draw count cannot be a framing miss" -
     *  holds only while the subject STAYS on its spot, and nothing here ever measured that. Three
     *  mechanisms move it without any render path being at fault: a cow spawned on a ONE-BLOCK floor
     *  beside a rolled hull can walk off it and fall; VS drags any body whose recent move touched a
     *  ship ({@code EntityDraggable.tickAddedVelocityForWorld}, applied every world tick to a body
     *  whose {@code lastTouchedShip} is younger than {@code VSConfig.ticksToStickToShip}) by the
     *  ship's between-tick transform, which for a steeply ROLLING hull is a swing about its centre;
     *  and a body pushed out of the hull leaves the aimed cone in a few ticks. An {@code offAxis}
     *  beyond ~45 deg is outside any sane FOV, so a zero needs no render explanation at all.
     *
     *  <p>Read from the CLIENT (the only side whose render list matters) and best effort: a probe
     *  failure must never mask the measurement it annotates. */
    private String subjectSightline(int subjectId, double[] stagedAt) {
        return subjectRenderStage(subjectId) + " " + subjectPosition(subjectId, stagedAt);
    }

    /** Which stage of the vanilla entity-render dispatch the subject passes on the client RIGHT NOW.
     *  The sightline above answers "is it where we aimed"; this answers "and if it is, why was it not
     *  drawn". Vanilla draws entities per VISIBLE render-chunk section, not from the loaded-entity
     *  list, so a body can be loaded, in frame and still never dispatched because its section is not
     *  in the visible set - three causes one model counter cannot separate. */
    private String subjectRenderStage(int subjectId) {
        try {
            return "[" + bot().invokeStaticInt(SHIP_CAMERA, "renderStageReport", subjectId)
                    .get("returned").getAsString() + "]";
        } catch (Exception e) {
            return "[render-stage probe failed: " + e + "]";
        }
    }

    private String subjectPosition(int subjectId, double[] stagedAt) {
        try {
            com.google.gson.JsonObject report = bot().reportEntities("Cow", 160.0);
            com.google.gson.JsonArray seen = report.getAsJsonArray("entities");
            for (int i = 0; i < seen.size(); i++) {
                com.google.gson.JsonObject e = seen.get(i).getAsJsonObject();
                if (e.get("id").getAsInt() != subjectId) {
                    continue;
                }
                double x = e.get("x").getAsDouble(), y = e.get("y").getAsDouble(), z = e.get("z").getAsDouble();
                double dx = x - stagedAt[0], dy = y - stagedAt[1], dz = z - stagedAt[2];

                com.google.gson.JsonObject st = bot().reportState();
                double px = st.get("playerX").getAsDouble(), py = st.get("playerY").getAsDouble(),
                        pz = st.get("playerZ").getAsDouble();
                double ry = Math.toRadians(st.get("playerYaw").getAsDouble());
                double rp = Math.toRadians(st.get("playerPitch").getAsDouble());
                // The camera forward vector in MC's yaw/pitch convention, and the angle between it
                // and the line to the subject's body centre.
                double fx = -Math.sin(ry) * Math.cos(rp), fy = -Math.sin(rp), fz = Math.cos(ry) * Math.cos(rp);
                double tx = x - px, ty = (y + 0.7) - py, tz = z - pz;
                double dist = Math.sqrt(tx * tx + ty * ty + tz * tz);
                double off = dist < 1.0E-6 ? 0.0 : Math.toDegrees(Math.acos(
                        Math.max(-1.0, Math.min(1.0, (fx * tx + fy * ty + fz * tz) / dist))));
                return String.format(java.util.Locale.ROOT,
                        "[client-pos=%.2f,%.2f,%.2f staged=%.2f,%.2f,%.2f drift=%.2f,%.2f,%.2f "
                                + "|drift|=%.2f dist=%.1f offAxis=%.1fdeg]",
                        x, y, z, stagedAt[0], stagedAt[1], stagedAt[2], dx, dy, dz,
                        Math.sqrt(dx * dx + dy * dy + dz * dz), dist, off);
            }
            return "[subject id=" + subjectId + " ABSENT from the client world within 160 blocks; "
                    + "cows the client sees=" + report.get("count") + "]";
        } catch (Exception e) {
            return "[sightline probe failed: " + e + "]";
        }
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
