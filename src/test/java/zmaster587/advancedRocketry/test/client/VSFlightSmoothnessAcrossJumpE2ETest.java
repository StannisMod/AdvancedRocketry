package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Does a ship fly as SMOOTHLY after a jump as it did before one?
 *
 * <p>The report this test exists for is a pilot's: after the first jump the craft "flies in jerks",
 * in a cell and back over a planet alike. Nothing in the session log could see it — the log records
 * what the game DECIDED, and a jerk is not a decision, it is a distribution: the same held key, the
 * same commanded velocity, and a clock somewhere underneath that stopped arriving evenly.</p>
 *
 * <p><b>Why the answer needs four clocks, not one.</b> A ship's motion passes through the physics
 * loop's own thread (where the velocity integrates), the server world tick (where the command is
 * republished), the client tick (where the arriving pose is smoothed) and the rendered frame (where
 * the pilot finally sees it). Each of those can stutter alone, they are fixed by different things,
 * and a measurement on only one of them cannot tell which. So both legs below read all four, and
 * the failure message carries every one — a red here should say WHICH clock broke, not merely that
 * something did.</p>
 *
 * <p><b>The control is the same ship, minutes earlier.</b> A smoothness number has no absolute
 * meaning: it depends on the box, the fork count, the ship's mass. So the pre-jump leg is not
 * arrangement — it is the control arm, flown by the same pilot holding the same key on the same
 * craft in a cell it reached the same way. What the post-jump leg is measured against is that, and
 * a green means "the jump did not make it worse", which is exactly the reported claim's negation.
 * Both legs also carry their own stimulus control: a craft that never moved reads as perfectly
 * smooth, so each leg asserts it actually flew before it is allowed to say anything about how.</p>
 *
 * <p>Gated on real Valkyrien Skies — run with {@code -PwithVS}.</p>
 */
public class VSFlightSmoothnessAcrossJumpE2ETest extends AbstractClientE2ETest {

    private static final String MOTION_TRACE = "zmaster587.advancedRocketry.util.MotionTrace";

    /**
     * How long one measured leg holds the key. Long enough that the Flight-Assist setpoint has
     * finished ramping well before the measured window opens, so the window sees a craft at a
     * settled cruise rather than one still accelerating — a ramp is a legitimately uneven
     * displacement and would read as roughness that is not the subject.
     *
     * <p>A GAME-TICK count, deliberately not scaled by the fork factor: scaling it would change how
     * far the ship flies rather than how long the test waits, making the experiment itself a
     * function of machine load.</p>
     */
    private static final int FLY_TICKS = 140;

    /** The trailing window every channel is summarised over. Matches a window the client offers. */
    private static final int WINDOW_MS = 3000;

    /**
     * How long the settled leg waits after the immediate one, in client ticks. Several times the
     * length of a measured window, so an arrival transient that merely SLID into leg B's window
     * cannot still be inside leg C's.
     */
    private static final int SETTLE_TICKS = 300;

    /** A leg that moved less than this over its window did not fly, and measures nothing. */
    private static final double MIN_LEG_TRAVEL = 5.0;
    /**
     * How far the craft must have got before the CONTROL leg's window opens, and how long it is
     * given to manage it.
     *
     * <p>Leg A is the only one measured from a craft standing on its build site; legs B and C are
     * measured on a craft already floating in a cell. A rocket that has not yet cleared the pad
     * pushes at the authority ceiling and goes nowhere — the physics channel shows acceleration
     * pinned and a net move under a tenth of a block — so the control arm was measuring the
     * release, not flight, on roughly one run in three. Lifting clear first removes that, and it
     * also makes the control arm what the subject arms already are: steady-state flight.</p>
     */
    private static final double LIFT_CLEAR_BLOCKS = 5.0;
    private static final int LIFT_POLL_TICKS = 40;
    private static final int LIFT_ATTEMPTS = 8;

    /**
     * How much rougher the post-jump leg is allowed to be than the control before this test calls
     * it a regression. Generous on purpose: the quantity is a ratio of hitch time between two
     * windows on a loaded developer box, and the claim being tested is "jerks", not "1.4x rougher".
     */
    private static final double MAX_ROUGHNESS_RATIO = 4.0;
    /** Below this many milliseconds of lost time a leg is smooth outright and no ratio is taken. */
    private static final double HITCH_NOISE_FLOOR_MS = 40.0;

    /**
     * How much more the ground covered between beats may vary after the jump than before it.
     *
     * <p>PROVISIONAL. The first calibration run read 1.06 before and 1.63 after on the frame
     * channel, from a single run — and one sample of a distribution is not a measurement of it, so
     * this bound is set generously enough to be a guard against a real regression rather than a
     * coin toss on run-to-run noise. It tightens when the spread across repeated runs is known.</p>
     */
    private static final double MAX_SURGE_RATIO = 2.0;
    /** A spread at or below this is even enough that no ratio against the control is meaningful. */
    private static final double EVENNESS_FLOOR = 1.5;

    /**
     * The frame cap this test runs its measurement at. The harness default is 30, which is a sampler
     * too coarse to resolve a hitch a player sees at his own frame rate — the reported symptom is
     * about what the pilot SEES, so the frame clock has to run at something like what he runs at.
     */
    private static final int MEASURED_FPS = 120;

    /** The client's frame cap before this test raised it, so cleanup can put it back. */
    private int previousFrameRate = -1;

    @Test
    public void aShipFliesAsSmoothlyAfterAJumpAsBeforeOne() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)", serverHasVs());

        // The rendered frame is one of the four clocks this test reads, and the harness seeds
        // maxFps:30. A pilot sees his stutter at 120; a 30 Hz sampler averages that away, so the
        // frame channel would return a clean number off an instrument that cannot resolve the thing
        // it is looking for. Raised for this test only and read BACK off the client's own field,
        // never assumed — the same discipline the sky-pass gate gets in the sibling jump e2e.
        com.google.gson.JsonObject fps = bot().setFrameRate(MEASURED_FPS);
        previousFrameRate = fps.get("previous").getAsInt();
        assertTrue("ARRANGEMENT: the frame cap must actually be raised, read back off the client's "
                        + "own field - every frame-channel number below is measured through it: " + fps,
                fps.get("effectiveLimit").getAsInt() >= MEASURED_FPS);
        assertTrue("ARRANGEMENT: vsync must be off, or the driver caps the frame clock below the "
                + "limit that was just set and the frame channel is throttled by something this "
                + "test cannot see: " + fps, !fps.get("vsync").getAsBoolean());

        // Headless: nothing but the client holds a ship loaded, and the probe calls below run
        // between client ticks. This affordance touches ship LOADING, never the timing of the
        // physics loop or of the render thread, which is all this test reads.
        exec("artest vs permaload true");

        // ---- ARRANGE: the transit stack over an empty origin cell, and a real flyable ship built
        // in it by the real assembler. The piloted setup fixture's bare deck has no propulsion, so
        // a held key could move nothing and every number below would describe a parked craft. ----
        String setup = exec("artest space transit-setup-empty");
        assertTrue("ARRANGEMENT: the empty transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");

        int bx = 40, by = 64, bz = 40;
        assertTrue("ARRANGEMENT: chunk warmup failed",
                exec("artest chunk warmup " + originDim + " " + ((bx - 2) >> 4) + " " + ((bz - 2) >> 4)
                        + " " + ((bx + 7) >> 4) + " " + ((bz + 7) >> 4)).contains("\"ok\":true"));
        // The BIGGEST flyable tier-2 variant the catalogue has, not the bare one. Mass and block
        // count are on the causal path for every one of the four clocks — the physics step's cost,
        // the volume of ship state synced per tick, the chunk work a moving hull does — and the
        // report is about a real ship, not a builder's minimum. Using the largest existing variant
        // rather than hand-placing a new one keeps the fixture inside the rules the catalogue
        // already enforces (tower-bounded scan, anchor connectivity, flyability).
        String fixture = exec("artest fixture rocket " + originDim + " " + bx + " " + by + " " + bz
                + " with-pilot-seat");
        assertTrue("ARRANGEMENT: fixture (with-pilot-seat) failed: " + fixture,
                fixture.contains("\"ok\":true"));
        Matcher bp = Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]").matcher(fixture);
        assertTrue("ARRANGEMENT: fixture missing builderPos: " + fixture, bp.find());
        assertTrue("ARRANGEMENT: a with-pilot-seat build must route to a ship: ",
                exec("artest rocket assemble " + originDim + " " + bp.group(1) + " " + bp.group(2)
                        + " " + bp.group(3)).contains("\"rocketCount\":0"));
        assertTrue("ARRANGEMENT: the origin ship never assembled/loaded in dim " + originDim,
                waitForLoadedShip(originDim) >= 1);

        String seat = exec("artest vs find-seat " + originDim
                + " " + (bx + 3) + " " + (by + 3) + " " + (bz + 3));
        assertTrue("ARRANGEMENT: the pilot seat must be found in the assembled ship: " + seat,
                readBool(seat, "seatFound"));
        int seatX = readInt(seat, "seatX"), seatY = readInt(seat, "seatY"), seatZ = readInt(seat, "seatZ");
        int[] afcOrigin = {readInt(seat, "afcX"), readInt(seat, "afcY"), readInt(seat, "afcZ")};
        int sx = (int) Math.round(readDouble(seat, "shipWorldX"));
        int sy = (int) Math.round(readDouble(seat, "shipWorldY"));
        int sz = (int) Math.round(readDouble(seat, "shipWorldZ"));

        String enter = exec("artest space enter " + botName() + " " + originDim
                + " " + sx + " " + sy + " " + sz);
        assertTrue("ARRANGEMENT: space enter into the origin cell must succeed: " + enter,
                readBool(enter, "ok"));
        bot().waitTicks(20);
        assertTrue("ARRANGEMENT: the client must have followed into the origin cell",
                bot().reportWeather().get("dim").getAsInt() == originDim);
        mountTheSeat(originDim, seatX, seatY, seatZ);

        // ---- LEG A (the control): fly in the origin cell, before anything has jumped. -----------
        liftClear(originDim, afcOrigin);
        Leg before = measure("before-jump", originDim, afcOrigin);

        // ---- ACT: the jump. Probe-driven so the park cannot race the measurement either side. ---
        bot().waitTicks(40); // let the station-hold settle, so the departure anchor is a still pose
        String shipNow = exec("artest vs ship-info " + originDim + " " + sx + " " + sy + " " + sz);
        assertTrue("ARRANGEMENT: the ship must still be managed at its berth: " + shipNow,
                shipNow.contains("\"managed\":true"));
        String begin = exec("artest space transit-begin " + originDim
                + " " + (int) Math.round(readDouble(shipNow, "posX"))
                + " " + (int) Math.round(readDouble(shipNow, "posY"))
                + " " + (int) Math.round(readDouble(shipNow, "posZ")));
        assertTrue("ARRANGEMENT: the transit must begin (departure crossing): " + begin,
                readBool(begin, "began"));

        int targetDim = -1;
        String lastTick = "";
        int arriveBudget = (int) (120 * TestTimeouts.factor());
        for (int i = 0; i < arriveBudget && targetDim < 0; i++) {
            lastTick = exec("artest space transit-tick");
            if (readIntOr(lastTick, "inTransit", -1) == 0) {
                targetDim = readIntOr(lastTick, "targetDim", -1);
                break;
            }
            bot().waitTicks(2);
        }
        assertTrue("ARRANGEMENT: the jump never completed (still in transit); last tick=" + lastTick,
                targetDim >= 0);

        boolean seatedOnArrival = false;
        int reseatBudget = (int) (60 * TestTimeouts.factor());
        String lastReseatTick = "";
        for (int i = 0; i < reseatBudget && !seatedOnArrival; i++) {
            lastReseatTick = exec("artest space transit-tick");
            bot().waitTicks(2);
            seatedOnArrival = bot().reportRidingEntity().get("riding").getAsBoolean()
                    && bot().reportWeather().get("dim").getAsInt() == targetDim;
        }
        // The arrival re-seat gives up WITHOUT logging (only the departure boarding leg reports on
        // exhaustion), so a red here would otherwise name no step. Carry the server's own account:
        // whether the retry loop was still running when we stopped ticking (`reseating`), where the
        // seat match stopped (`reseatBlock`), and who wrote the rider's position last.
        assertTrue("ARRANGEMENT: the pilot must arrive SEATED in the target cell, or the post-jump "
                        + "leg has no pilot and measures a drifting hulk. riding="
                        + bot().reportRidingEntity() + " clientDim="
                        + bot().reportWeather().get("dim").getAsInt() + " targetDim=" + targetDim
                        + " lastTick=" + lastReseatTick
                        + " arrival=" + exec("artest vs arrival-trace"),
                seatedOnArrival);

        // The crossing re-pastes the ship, so its flight computer is at a NEW subspace block: read
        // the arrived one rather than reusing the departure's, which would key the recorder to a
        // ring nothing writes and report a silent, perfectly smooth nothing.
        String arrivedSeat = "";
        int[] afcArrived = null;
        for (int i = 0; i < 20 && afcArrived == null; i++) {
            arrivedSeat = exec("artest vs find-seat " + targetDim + " 0 200 0");
            if (readBool(arrivedSeat, "seatFound") && arrivedSeat.contains("\"afcX\"")) {
                afcArrived = new int[]{readInt(arrivedSeat, "afcX"), readInt(arrivedSeat, "afcY"),
                        readInt(arrivedSeat, "afcZ")};
            } else {
                bot().waitTicks(10);
            }
        }
        assertTrue("ARRANGEMENT: the arrived ship must expose its flight computer, or the post-jump "
                + "recorder has nothing to key on: " + arrivedSeat, afcArrived != null);

        // ---- LEG B (the subject): the same pilot, the same key, the same craft, after a jump. ---
        Leg after = measure("after-jump", targetDim, afcArrived);

        // ---- LEG C: the same again, once the arrival has had time to finish whatever it is still
        // doing. Leg B alone cannot tell an arrival TRANSIENT from a lasting change: both put a
        // stalled tick inside the measured window, and the reported symptom ("it flies in jerks
        // afterwards") is the lasting one. A stall that survives here is the ship's new normal; a
        // stall that vanishes is the arrival finishing up, which is a different and much smaller
        // defect. Measured 6 of 6 in leg B before this leg existed: one 199-266 ms tick, with ZERO
        // chunks arriving in the window — so whatever it is, it is not the loading the report
        // suspected.
        bot().waitTicks(SETTLE_TICKS);
        Leg settled = measure("after-jump-settled", targetDim, afcArrived);

        // The raw client channels beside the summary. A ratio says the pose stream is uneven; only
        // the beat GAPS beside the per-beat STEPS can say whether the tick arrived late (a stalled
        // clock) or arrived on time carrying an uneven pose (a stuttering pose stream), and only the
        // step series can say whether that unevenness is periodic.
        System.out.println("[SMOOTH-RAW] before:  " + before.clientJson);
        System.out.println("[SMOOTH-RAW] settled: " + settled.clientJson);
        System.out.println("[SMOOTH] control (before jump):    " + before);
        System.out.println("[SMOOTH] subject (after jump):     " + after);
        System.out.println("[SMOOTH] subject (settled):        " + settled);

        // The instrument spoke on every clock, in both legs. A mute channel is not a smooth one,
        // and without this the comparison below can be satisfied by two silences.
        assertInstrumentSpoke("control", before);
        assertInstrumentSpoke("subject", after);
        assertInstrumentSpoke("settled", settled);

        // And the stimulus fired: a craft that did not move is trivially smooth on every channel.
        assertTrue("CONTROL LEG: the ship must actually FLY before the jump — a leg that did not "
                        + "move reads as perfectly smooth on all four clocks and measures nothing. "
                        + before,
                before.travel >= MIN_LEG_TRAVEL);
        assertTrue("SUBJECT LEG: the ship must actually FLY after the jump. A red here is a control "
                        + "failure, NOT a smoothness finding: the pilot's key stopped reaching the "
                        + "arrived ship's computer, which is a delivery defect with its own tests. "
                        + after + " delivery=" + exec("artest vs seat-delivery"),
                after.travel >= MIN_LEG_TRAVEL);
        // And on the leg every claim below is actually judged on. Without this the two surge
        // assertions are unfalsifiable: evenness() degenerates to 0.0 for a craft that did not
        // move, and 0.0 is under every threshold, so a settled leg that never flew would read as
        // the smoothest run this test has ever seen.
        assertTrue("CONTROL LEG: the ship must actually FLY on the SETTLED leg, which is the one "
                        + "every claim below is judged on. A craft that stopped moving reads as "
                        + "perfectly smooth on all four clocks, so a red here is a control failure "
                        + "and not a smoothness finding. " + settled,
                settled.travel >= MIN_LEG_TRAVEL);

        // The claim under test, on each clock in turn, so a red names the one that broke.
        //
        // Two shapes, because a jerk can arrive as either and they look nothing alike in the data:
        // a LATE BEAT (the clock itself stalled) and a SURGE (the clock is metronomic but the craft
        // covered wildly different ground between beats). The first calibration run had exactly one
        // of each — a 260 ms server tick and a per-frame displacement spread that went from 1.06 to
        // 1.63 — with the frame RATE untouched at 30 fps on both sides.
        // Judged on the SETTLED leg. The reported symptom is how the ship flies AFTERWARDS, not the
        // one tick an arrival spends tidying up, and a test that failed on the transient would be
        // pinning a different, smaller thing under this one's name. Leg B is printed either way and
        // its numbers are in every failure message.
        assertNotRougher("the physics loop's own clock (where the ship's velocity integrates)",
                before.physHitchMs, settled.physHitchMs, before, settled, after);
        assertNotRougher("the server tick that republishes the flight command",
                before.gameHitchMs, settled.gameHitchMs, before, settled, after);
        assertNotRougher("the client tick that smooths the arriving ship pose",
                before.clientTickHitchMs, settled.clientTickHitchMs, before, settled, after);
        assertNotRougher("the rendered frame — what the pilot actually looks at",
                before.frameHitchMs, settled.frameHitchMs, before, settled, after);

        assertNoSurge("the pilot's own view", before.frameEvenness, settled.frameEvenness,
                before, settled, after);
        assertNoSurge("the ship pose the client renders from",
                before.clientTickEvenness, settled.clientTickEvenness, before, settled, after);
    }

    @After
    public void cleanup() {
        try {
            if (previousFrameRate > 0) {
                bot().setFrameRate(previousFrameRate);
            }
            if (serverHasVs()) {
                exec("artest player dismount");
                exec("artest vs permaload false");
                exec("artest vs motion-trace reset");
            }
        } catch (Exception ignored) {
        }
    }

    // --- the measurement ------------------------------------------------------------------------

    /** One measured leg: the four clocks' summaries plus the numbers the assertions read. */
    private static final class Leg {
        String label = "";
        String serverJson = "";
        String clientJson = "";
        double travel;
        double physHitchMs;
        double gameHitchMs;
        double clientTickHitchMs;
        double frameHitchMs;
        int physSamples;
        int gameSamples;
        int clientTickSamples;
        int frameSamples;
        /**
         * How UNEVEN the pilot's own view moved: the 95th percentile of per-frame eye displacement
         * over its median. One means every frame carried the craft the same distance; the further
         * above one, the more the picture surges and pauses at a constant frame rate — which is
         * what "flies in jerks" describes when the frame TIMING is clean.
         */
        double frameEvenness;
        /** The same ratio on the client tick, for the case where the surge is in the pose stream. */
        double clientTickEvenness;
        /** Each server clock's measured rate — the guard against two ships sharing one ring. */
        double physHz;
        double gameHz;
        /** Distinct CONTROLLER objects that drove this flight computer in the window. Must be 1. */
        int physWriters;
        /** Distinct SHIPS those controllers belonged to — which of the two duplicate shapes it is. */
        int physWriterShips;
        /** Chunks that arrived DURING the window, differenced across it rather than between legs. */
        long chunksInWindow;
        long serverChunkLoads;
        long clientChunkLoads;

        @Override
        public String toString() {
            return label + " travel=" + round(travel)
                    + " physWriters=" + physWriters + " onShips=" + physWriterShips
                    + " chunksInWindow=" + chunksInWindow
                    + " hz{phys=" + round(physHz) + " game=" + round(gameHz) + "}"
                    + " frameEvenness=" + round(frameEvenness)
                    + " clientTickEvenness=" + round(clientTickEvenness)
                    + " hitchMs{phys=" + round(physHitchMs) + " game=" + round(gameHitchMs)
                    + " clientTick=" + round(clientTickHitchMs) + " frame=" + round(frameHitchMs)
                    + "} n{phys=" + physSamples + " game=" + gameSamples
                    + " clientTick=" + clientTickSamples + " frame=" + frameSamples + "}"
                    + " chunkLoads{server=" + serverChunkLoads + " client=" + clientChunkLoads + "}"
                    + " server=" + serverJson + " client=" + clientJson;
        }

        private static double round(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }

    /**
     * Hold the vertical key for a fixed number of client ticks, then read every clock's account of
     * the trailing window. The server rings are cleared first; the client rings cannot be (they
     * live in the other JVM and the harness can only READ a static), which is why the client half
     * is summarised over trailing windows and read immediately after the key is released.
     */
    /**
     * Hold the climb key until the craft is genuinely under way, and say so plainly if it never is.
     *
     * <p>A red here is an ARRANGEMENT failure, not a smoothness finding: the craft never left its
     * build site, so there is nothing to measure the jump against.</p>
     */
    private void liftClear(int dim, int[] afc) throws Exception {
        double moved = 0.0;
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int attempt = 0; attempt < LIFT_ATTEMPTS && moved < LIFT_CLEAR_BLOCKS; attempt++) {
                exec("artest vs motion-trace reset");
                bot().waitTicks(LIFT_POLL_TICKS);
                moved = netMoveLength(section(exec("artest vs motion-trace " + dim + " " + afc[0]
                        + " " + afc[1] + " " + afc[2] + " " + WINDOW_MS), "\"phys\":"));
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        assertTrue("ARRANGEMENT: the craft never got under way on its own pad. It was given "
                        + (LIFT_ATTEMPTS * LIFT_POLL_TICKS) + " ticks of held climb key and moved "
                        + Leg.round(moved) + " blocks, against " + LIFT_CLEAR_BLOCKS + " asked for. "
                        + "A craft pushing at the authority ceiling while its net move stays near "
                        + "zero is being held by its surroundings, so nothing below would be a "
                        + "reading about the jump.",
                moved >= LIFT_CLEAR_BLOCKS);
    }

    private Leg measure(String label, int dim, int[] afc) throws Exception {
        exec("artest vs motion-trace reset");
        bot().holdKey(Keyboard.KEY_R);
        try {
            bot().waitTicks(FLY_TICKS);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        Leg leg = new Leg();
        leg.label = label;
        leg.serverJson = exec("artest vs motion-trace " + dim + " " + afc[0] + " " + afc[1]
                + " " + afc[2] + " " + WINDOW_MS);
        leg.clientJson = bot().readStaticField(MOTION_TRACE, "CLIENT_SUMMARY")
                .get("value").getAsString();

        String phys = section(leg.serverJson, "\"phys\":");
        String game = section(leg.serverJson, "\"game\":");
        String window = section(leg.clientJson, "\"w" + WINDOW_MS + "\":");
        String clientTick = section(window, "\"tick\":");
        String frame = section(window, "\"frame\":");

        leg.physSamples = readIntOr(phys, "n", 0);
        leg.gameSamples = readIntOr(game, "n", 0);
        leg.clientTickSamples = readIntOr(clientTick, "n", 0);
        leg.frameSamples = readIntOr(frame, "n", 0);
        leg.physHitchMs = readDoubleOr(phys, "hitchMs", 0);
        leg.gameHitchMs = readDoubleOr(game, "hitchMs", 0);
        leg.clientTickHitchMs = readDoubleOr(clientTick, "hitchMs", 0);
        leg.frameHitchMs = readDoubleOr(frame, "hitchMs", 0);
        leg.frameEvenness = evenness(frame);
        leg.clientTickEvenness = evenness(clientTick);
        leg.physHz = readDoubleOr(phys, "hz", 0);
        leg.gameHz = readDoubleOr(game, "hz", 0);
        leg.physWriters = readIntOr(phys, "writers", 0);
        leg.physWriterShips = readIntOr(phys, "writerShips", 0);
        // Column 3 of the server-tick channel is the cumulative server chunk count; differencing it
        // across the window is what turns "chunks arrived between the legs" into "chunks arrived
        // while that tick was stalled".
        leg.chunksInWindow = (long) (column(game, "\"last\":", 3) - column(game, "\"first\":", 3));
        leg.serverChunkLoads = readIntOr(leg.serverJson, "serverChunkLoads", 0);
        leg.clientChunkLoads = readIntOr(leg.clientJson, "chunkLoads", 0);
        // How far the SHIP itself went over the window, from the physics channel's own net move —
        // the one displacement in the set that no interpolation filter has touched.
        leg.travel = netMoveLength(phys);
        return leg;
    }

    /**
     * The 95th percentile of a channel's per-sample displacement over its median: 1.0 is a craft
     * covering the same ground every beat, and the excess over 1.0 is the surge-and-pause the pilot
     * sees. Read from {@code stepBlocks}, whose two numbers this deliberately keeps as a RATIO —
     * the absolute distances differ with cruise speed and with the channel's own rate, and the
     * question is evenness, not speed.
     */
    private static double evenness(String channelJson) {
        String step = section(channelJson, "\"stepBlocks\":");
        double p50 = readDoubleOr(step, "p50", 0);
        double p95 = readDoubleOr(step, "p95", 0);
        if (p50 <= 1.0e-6) {
            // A parked craft has no evenness to speak of. This value is BELOW every threshold, so
            // it can only ever read as a pass — which is why each leg this test judges carries its
            // own travel control, including the settled one.
            return 0.0;
        }
        return p95 / p50;
    }

    private static void assertInstrumentSpoke(String which, Leg leg) {
        assertTrue("INSTRUMENT CONTROL (" + which + "): the physics-thread channel must have "
                + "recorded samples. A mute channel cannot be distinguished from a perfectly "
                + "smooth one, so every reading below it would be a silence read as a pass. " + leg,
                leg.physSamples >= 10);
        assertTrue("INSTRUMENT CONTROL (" + which + "): the server-tick channel must have recorded "
                + "samples. " + leg, leg.gameSamples >= 10);
        assertTrue("INSTRUMENT CONTROL (" + which + "): the CLIENT tick channel must have recorded "
                + "samples — this is the harness reading a static in the other JVM, so a zero here "
                + "usually means the read found the wrong class rather than a stalled client. " + leg,
                leg.clientTickSamples >= 10);
        assertTrue("INSTRUMENT CONTROL (" + which + "): the rendered-FRAME channel must have "
                + "recorded samples. This is the only clock that sees what the pilot looks at; "
                + "without it the test cannot answer the half of the report that is about the "
                + "picture rather than the motion. " + leg, leg.frameSamples >= 10);
        // TWO physics bodies driving one flight computer. Not an instrument fault — the recorder
        // keys its rings by dimension AND block, so a second writer here is a second SHIP claiming
        // the same computer, which is a state no build should be able to reach. It is checked among
        // the instrument controls because until it is excluded, every displacement below is a
        // difference between two different craft and means nothing.
        assertTrue("ONE flight computer was driven by " + leg.physWriters + " controllers on "
                        + leg.physWriterShips + " ships (" + which + " leg), so the physics channel "
                        + "sampled at " + Leg.round(leg.physHz) + " Hz against a 60 Hz clock. "
                        + (leg.physWriterShips > 1
                            ? "TWO SHIPS claim this computer: the arrival left a duplicate craft "
                              + "behind, and two rigid bodies sharing a hull collide every step."
                            : "One ship, several controller objects: a stale tile instance is still "
                              + "in the ship's controller set after its replacement was installed, "
                              + "so the ship is commanded twice per step by two copies of itself.")
                        + " Either way the pilot feels it as jerks, and the travel figure here shows "
                        + "a craft that barely moved. " + leg,
                leg.physWriters == 1);
        assertTrue("INSTRUMENT CONTROL (" + which + "): the physics channel must run near its "
                        + "declared 60 Hz — it read " + Leg.round(leg.physHz) + " Hz. " + leg,
                leg.physHz > 40.0 && leg.physHz < 85.0);
        assertTrue("INSTRUMENT CONTROL (" + which + "): the server-tick channel must run near its "
                        + "declared 20 Hz — it read " + Leg.round(leg.gameHz) + " Hz. " + leg,
                leg.gameHz > 13.0 && leg.gameHz < 28.0);
    }

    private static void assertNotRougher(String clock, double control, double subject,
                                         Leg before, Leg after, Leg transient_) {
        if (subject <= HITCH_NOISE_FLOOR_MS) {
            return; // smooth outright; a ratio against near-zero says nothing
        }
        double allowed = Math.max(HITCH_NOISE_FLOOR_MS, control * MAX_ROUGHNESS_RATIO);
        assertTrue("a jump must not make the ship rougher to fly. On " + clock + " the pilot lost "
                        + Leg.round(subject) + " ms to late beats after the jump against "
                        + Leg.round(control) + " ms before it, over the same " + WINDOW_MS
                        + " ms window with the same key held on the same craft — more than the "
                        + MAX_ROUGHNESS_RATIO + "x this test allows. The control leg is the one to "
                        + "read first: if it is itself rough, this box is loaded and the comparison "
                        + "is weak; if it is clean, the jump did this.\n  control: " + before
                        + "\n  subject: " + after,
                subject <= allowed);
    }

    private static void assertNoSurge(String what, double control, double subject,
                                      Leg before, Leg after, Leg transient_) {
        double allowed = Math.max(EVENNESS_FLOOR, control * MAX_SURGE_RATIO);
        assertTrue("a jump must not make the ship SURGE. On " + what + ", the ground covered between "
                        + "beats went from a spread of " + Leg.round(control) + " to "
                        + Leg.round(subject) + " (95th percentile over median; 1.0 is a craft "
                        + "covering the same distance every beat). The clock's own rate is reported "
                        + "beside it — if the rate is steady and this is not, the beats are arriving "
                        + "on time carrying uneven amounts of movement, which is the pose stream "
                        + "stuttering rather than the renderer. Chunk arrivals during each leg are "
                        + "on the legs below; a surge that tracks them is the loading, not the jump."
                        + "\n  control: " + before + "\n  subject: " + after,
                subject <= allowed);
    }

    /** One column of a channel's {@code "first"} or {@code "last"} sample row. */
    private static double column(String channelJson, String rowKey, int index) {
        int at = channelJson.indexOf(rowKey);
        if (at < 0) {
            return 0.0;
        }
        int open = channelJson.indexOf('[', at);
        int close = channelJson.indexOf(']', open);
        if (open < 0 || close < 0) {
            return 0.0;
        }
        String[] parts = channelJson.substring(open + 1, close).split(",");
        if (index >= parts.length) {
            return 0.0;
        }
        try {
            return Double.parseDouble(parts[index].trim());
        } catch (NumberFormatException notANumber) {
            return 0.0;
        }
    }

    /** The length of a channel's {@code netMove} vector, or 0 when it reported none. */
    private static double netMoveLength(String json) {
        Matcher m = Pattern.compile(
                "\"netMove\":\\[(-?[0-9.E\\-]+),(-?[0-9.E\\-]+),(-?[0-9.E\\-]+)]").matcher(json);
        if (!m.find()) {
            return 0.0;
        }
        double x = Double.parseDouble(m.group(1));
        double y = Double.parseDouble(m.group(2));
        double z = Double.parseDouble(m.group(3));
        return Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * The balanced JSON object that follows {@code key} in {@code json}. Written by hand rather
     * than parsed because the probe envelope is a flat string and a regex for a nested object stops
     * at the first closing brace, which here is always the wrong one.
     */
    private static String section(String json, String key) {
        int at = json.indexOf(key);
        if (at < 0) {
            return "";
        }
        int start = json.indexOf('{', at + key.length() - 1);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    // --- arrangement helpers (mirroring the tier-2 client e2e classes) ---------------------------

    private void mountTheSeat(int dim, int seatX, int seatY, int seatZ) throws Exception {
        String mount = "";
        boolean mounted = false;
        for (int attempt = 0; attempt < 5 && !mounted; attempt++) {
            String mountAt = exec("artest vs seat-mount-at " + dim
                    + " " + seatX + " " + seatY + " " + seatZ);
            assertTrue("ARRANGEMENT: seat-mount-at must spawn the seat dummy: " + mountAt,
                    readBool(mountAt, "ok"));
            mount = exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
            mounted = mount.contains("\"mounted\":true");
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        assertTrue("ARRANGEMENT: the bot must mount the pilot-seat dummy: " + mount, mounted);
        bot().waitTicks(10);
        assertTrue("ARRANGEMENT: the bot must be seated before the control leg: "
                + bot().reportRidingEntity(),
                bot().reportRidingEntity().get("riding").getAsBoolean());
    }

    private String botName() throws Exception {
        String health = exec("artest player health");
        Matcher nameM = Pattern.compile("\"player\":\"([^\"]+)\"").matcher(health);
        assertTrue("ARRANGEMENT: player health must echo the player name: " + health, nameM.find());
        return nameM.group(1);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private int waitForLoadedShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (readIntOr(exec("artest vs ship-count-all " + dim), "count", -1) >= 1) {
                exec("artest vs load-ships " + dim);
                int loaded = readIntOr(exec("artest vs ship-count " + dim), "count", -1);
                if (loaded >= 1) {
                    return loaded;
                }
            }
            bot().waitTicks(5);
        }
        return 0;
    }

    private static boolean readBool(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(true|false)").matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    private static int readInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("expected int \"" + key + "\" in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static int readIntOr(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static double readDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.E\\-]+)").matcher(json);
        assertTrue("expected number \"" + key + "\" in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static double readDoubleOr(String json, String key, double def) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?[0-9.E\\-]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : def;
    }
}
