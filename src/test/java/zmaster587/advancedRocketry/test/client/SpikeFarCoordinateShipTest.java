package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE — does a tier-2 ship survive a far world coordinate the way a bare player does?
 *
 * <p>A player walks, stands, collides, holds a sub-block position and is rendered without a quantum
 * out to 24M. None of that transfers: a ship's blocks live in the shipyard subspace while its pose
 * lives in the world, and the two are bridged by a transform of its own. So the ship is the last
 * subject that could still move the ratified half-cell, and this is the leg that measures it.</p>
 *
 * <h2>Why this ASSEMBLES at the coordinate instead of teleporting a ship to it</h2>
 * {@code VSShipExtremeCoordinatesE2ETest} reached extreme <b>Y</b> by rigid-teleporting an assembled
 * ship, and left the extreme-|X| leg unautomated for a reason recorded in its own javadoc: after a
 * SECOND relocation the physics goes inert — neither a pilot key nor a velocity setpoint moves the
 * ship — and the pilot-key path dies after a dismount and re-seat across the map. Those are
 * relocation-SEQUENCE findings. Teleporting to |X| would re-run straight into them and produce a red
 * that says nothing about the coordinate.
 *
 * <p>So the stimulus changes rather than the measurement: the fixture is built, and the ship
 * assembled, AT the far coordinate. There is exactly one relocation in the whole leg — the player's,
 * through {@code far-tp} — and the ship is never moved at all.</p>
 *
 * <h2>Where the arena sits, and why</h2>
 * {@code Z = }{@value #ARENA_Z}, below the physics mod's reserved quadrant
 * ({@code chunkX >= 318401 && chunkZ >= -1599}). Above that Z the quadrant would swallow the arena at
 * 16M: the blocks would be shipyard blocks, the player's delivery would be cancelled silently, and
 * the leg would measure the reservation instead of the coordinate.
 *
 * <h2>Acceptance, stated before the run</h2>
 * The {@code x = 0} rung is the control, assembled and flown in the same run by the same commands.
 * At every rung:
 * <ol>
 *   <li>assembly must produce a VS ship (the ship count rises), and it must LOAD ({@code managed});</li>
 *   <li>the pilot seat must be findable and mountable — crew retention through the far assembly;</li>
 *   <li>a real held vertical-up key must lift the server ship by more than
 *       {@value #MIN_LIFT_BLOCKS} block;</li>
 *   <li>the CLIENT-rendered rider must track that climb to within {@value #TRACK_TOLERANCE} blocks —
 *       a transform that has lost precision shows up here as divergence, and nowhere earlier.</li>
 * </ol>
 *
 * <p><b>Designed to come back NO.</b> A ship that will not assemble, will not load, will not lift or
 * whose rider drifts at 16M is a finding against the ratified half-cell, and the number moves.</p>
 */
public class SpikeFarCoordinateShipTest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern SHIP_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Bounds the ONE nearest-ship lookup this leg makes. The rungs are millions of blocks apart, so a
     * radius this size cannot reach a neighbour — and if this rung's own ship is missing, the lookup
     * says so instead of describing the other rung's.
     */
    private static final int SHIP_LOOKUP_RADIUS = 512;

    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    /** One command, then this many samples this many ticks apart, watching for motion to cease. */
    private static final int SURVIVAL_SAMPLES = 40;
    private static final int SURVIVAL_SAMPLE_TICKS = 10;
    /** Blocks per sample below which the ship counts as no longer being driven. */
    private static final double SURVIVAL_STEP_EPSILON = 0.05d;

    /** The control, then the ratified half-cell. 24M is not carried: one far rung is the question. */
    private static final int[] X_LADDER = {0, 16_000_000};

    /** Below the reserved quadrant's Z edge (Z ≥ -25,584), so the arena is ordinary world at every X. */
    private static final int ARENA_Z = -100_000;
    /** Well above sea level: 16M is ocean, and a fixture built into water is not a fixture. */
    private static final int BASE_Y = 140;

    private static final String VARIANT = "with-pilot-seat";
    private static final double MIN_LIFT_BLOCKS = 1.0d;
    private static final double TRACK_TOLERANCE = 3.0d;
    private static final double ARRIVAL_TOLERANCE = 1.0d;
    private static final int DELIVERY_ATTEMPTS = 4;
    /** 5-tick polls the CLIENT gets to agree it is riding the seat the server already mounted it on. */
    private static final int RIDING_ATTEMPTS = 24;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void doesAShipAssembleLoadAndFlyFarFromTheOrigin() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server", serverHasVs());

        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");
        exec("gamerule doMobSpawning false");
        exec("gamerule doDaylightCycle false");
        exec("gamerule doWeatherCycle false");
        exec("weather clear");
        // Headless has no player holding a distant ship loaded, and the client is one player in one
        // place while two ships exist in this run.
        assertTrue(exec("artest vs permaload true").contains("\"ok\":true"));

        Map<Integer, String> verdicts = new LinkedHashMap<>();
        // Which ship answered for which rung. Two rungs that report the same id measured one subject
        // twice, and two rungs agreeing to four decimals is what that looks like from the outside.
        Map<Integer, String> shipIds = new LinkedHashMap<>();
        List<String> report = new ArrayList<>();
        List<String> inconclusive = new ArrayList<>();
        StringBuilder out;

        try {
            for (int x : X_LADDER) {
                int before = count("ship-count-all");

                String arrangement = arrange(x);
                if (arrangement != null) {
                    inconclusive.add("x=" + x + " " + arrangement);
                    continue;
                }

                String assemble = assembleFixture(x);
                if (assemble == null) {
                    inconclusive.add("x=" + x + " the fixture did not build or did not assemble"
                            + " (arrangement, not the coordinate)");
                    continue;
                }
                if (!assemble.contains("\"rocketCount\":0")) {
                    verdicts.put(x, "the build did not route to a SHIP: " + oneLine(assemble));
                    continue;
                }

                int after = before;
                for (int i = 0; i < 40 && after <= before; i++) {
                    bot().waitTicks(5);
                    after = count("ship-count-all");
                }
                if (after <= before) {
                    verdicts.put(x, "assembly created no VS ship (count " + before + " -> " + after + ")");
                    continue;
                }

                // Put the pilot on the ship. This is the ONLY relocation in the leg, and it is the
                // player's, not the ship's.
                String delivery = deliver(x);
                if (delivery != null) {
                    inconclusive.add("x=" + x + " " + delivery);
                    continue;
                }

                // Capture the ship's IDENTITY once, here — the one moment this lookup is defensible,
                // with this rung's ship freshly assembled at this spot. Every later reading goes by
                // that id, which has no distance term to be wrong about.
                double y0 = Double.NaN;
                String shipId = null;
                String lastInfo = "";
                for (int i = 0; i < 40 && Double.isNaN(y0); i++) {
                    bot().waitTicks(5);
                    lastInfo = exec("artest vs ship-info 0 " + x + " " + BASE_Y + " " + ARENA_Z
                            + " " + SHIP_LOOKUP_RADIUS);
                    if (lastInfo.contains("\"managed\":true")) {
                        y0 = readDouble(lastInfo);
                        Matcher im = SHIP_ID.matcher(lastInfo);
                        shipId = im.find() ? im.group(1) : null;
                    }
                }
                if (Double.isNaN(y0)) {
                    verdicts.put(x, "the ship never LOADED with the client present: " + oneLine(lastInfo));
                    continue;
                }
                if (shipId == null) {
                    verdicts.put(x, "the ship loaded but reported no id, so no later reading can be "
                            + "attributed to it: " + oneLine(lastInfo));
                    continue;
                }
                if (shipIds.containsValue(shipId)) {
                    verdicts.put(x, "this rung's ship is the SAME ship a previous rung measured (id "
                            + shipId + ") - the ladder is measuring one subject twice");
                    continue;
                }
                shipIds.put(x, shipId);

                // NAME the ship. The bare form takes the first loaded pilot seat, and this ladder
                // keeps every rung's ship permanently loaded — so at 16M it mounted the pilot onto
                // the ORIGIN ship's seat, the client 16M away saw no entity to ride, and the reply
                // read exactly like a far-coordinate failure. It was not one.
                String mountInfo = exec("artest vs seat-mount 0 near " + x + " " + BASE_Y + " "
                        + ARENA_Z + " 512");
                if (!mountInfo.contains("\"seatFound\":true")) {
                    verdicts.put(x, "the pilot seat was not findable: " + oneLine(mountInfo));
                    continue;
                }
                Matcher dm = DUMMY_ID.matcher(mountInfo);
                if (!dm.find()) {
                    verdicts.put(x, "seat-mount reported no dummy id: " + oneLine(mountInfo));
                    continue;
                }
                String mounted = exec("artest player mount-entity " + dm.group(1));
                if (!mounted.contains("\"mounted\":true")) {
                    verdicts.put(x, "the bot could not mount the seat dummy: " + oneLine(mounted));
                    continue;
                }
                // "mounted":true is the SERVER's word. The climb measures the CLIENT-rendered rider,
                // so wait until the CLIENT agrees it is riding — the first run of this leg read the
                // rider's posY one tick too early and died on a missing field, which reads exactly
                // like a coordinate failure and is not one.
                String riding = awaitRiding(Integer.parseInt(dm.group(1)));
                if (riding != null) {
                    verdicts.put(x, riding + " (server said " + oneLine(mounted) + ")");
                    continue;
                }

                String flight = climbLeg(shipId, y0);
                // The seat's own position is a SUBSPACE coordinate — the shipyard is where a ship's
                // blocks actually live. Recording it makes the magnitude the ship's own math runs on
                // visible in the report, which is the only number that changes if the shipyard moves.
                report.add("x=" + x + " ship=" + shipId + " shipY0=" + fmt(y0)
                        + " subspaceSeatX=" + fmt(field(mountInfo, "seatX"))
                        + " subspaceSeatZ=" + fmt(field(mountInfo, "seatZ"))
                        + " " + flight);
                verdicts.put(x, flight.startsWith("OK") ? null : flight);

                exec("artest player dismount");
                bot().waitTicks(10);
            }
        } finally {
            // The report is the deliverable and it is worth MOST when the leg died mid-ladder, so it
            // is emitted before anything can escape. The first run of this leg threw past its own
            // report writer and left nothing on disk to read.
            for (Map.Entry<Integer, String> e : verdicts.entrySet()) {
                if (e.getValue() != null) {
                    report.add("x=" + e.getKey() + " FAILED " + e.getValue());
                }
            }
            StringBuilder built = new StringBuilder("[SPIKE far-coordinate VS ship]\n");
            for (String line : report) {
                built.append("  ").append(line).append('\n');
            }
            for (String line : inconclusive) {
                built.append("  INCONCLUSIVE ").append(line).append('\n');
            }
            for (int x : X_LADDER) {
                if (!verdicts.containsKey(x) && !hasPrefix(inconclusive, "x=" + x + " ")) {
                    built.append("  NOT REACHED x=").append(x).append('\n');
                }
            }
            System.out.println(built);
            writeReport("far-coordinate-ship.txt", built.toString());
            out = built;
            try {
                exec("artest player dismount");
                exec("artest vs permaload false");
            } catch (Exception ignored) {
                // teardown must not mask the finding
            }
        }

        // The control is asserted first and separately: a ship that will not fly at the ORIGIN makes
        // every far reading meaningless, and that is an instrument failure, not a coordinate ceiling.
        assertTrue("the x=0 control produced no measurement at all, so no far rung is evidence:\n" + out,
                verdicts.containsKey(0));
        assertTrue("the x=0 control failed - the instrument, not the coordinate: " + verdicts.get(0)
                + "\n" + out, verdicts.get(0) == null);

        List<String> failed = new ArrayList<>();
        for (Map.Entry<Integer, String> e : verdicts.entrySet()) {
            if (e.getKey() != 0 && e.getValue() != null) {
                failed.add("x=" + e.getKey() + ": " + e.getValue());
            }
        }
        assertTrue("a ship does not behave at a far coordinate as it does at the origin: " + failed
                + "\n" + out, failed.isEmpty());
        assertTrue("no far rung was measured at all - the leg answered nothing:\n" + out,
                verdicts.size() > 1);
    }

    /**
     * SPIKE — how long does a ONE-SHOT commanded setpoint survive, and does the shipyard's position
     * change that?
     *
     * <h2>The question this exists to settle</h2>
     * Two measurements of this tree disagree. Moving the shipyard to {@code CHUNK_X_START =
     * 1,200,000} makes {@code aStillCrewMemberOnAFastClimbingShipKeepsHisCapture} report
     * {@code travelled=0.0} on 3 of 3 runs while it is green on 3 of 3 at {@code 320000} — yet the
     * ladder above lifts a ship 4.7–5.1 blocks at that same subspace magnitude. Both cannot be
     * describing "a ship cannot move out there".
     *
     * <p>They stop disagreeing under one hypothesis: the failure is not in DELIVERING a command but
     * in its SURVIVAL. The ladder holds a real key, so it re-commands every tick and outlives any
     * loss of state; {@code seat-input} writes a setpoint ONCE, into
     * {@code TileAdvancedFlightComputer} — and a flight computer tile that is re-created underneath
     * the ship loses every live field it holds, {@code velocitySetpoint} included, while persistent
     * {@code stationKeeping} survives. A command that is silently dropped a few seconds in reads as
     * {@code travelled=0.0}.</p>
     *
     * <p>Independently, the registration is known to leak in this tree:
     * {@code ClaimedChunkCacheController:122} re-registers EVERY tile of a chunk each time the claim
     * cache loads it, {@code MixinChunk:48} adds on tile add, and {@code MixinChunk:53} removes only
     * when a tile is genuinely removed — so an unload/load cycle leaves the old instance registered
     * forever and adds a new one.</p>
     *
     * <h2>What this measures, and what would settle it</h2>
     * One command, then the ship's own position sampled until it stops moving. The number is the
     * SURVIVAL WINDOW in ticks. Run at both constants, on a wiped world, at ordinary world
     * coordinates so the shipyard's position is the only thing that differs.
     * <ul>
     *   <li>window shorter at {@code 1,200,000} → the two measurements are reconciled and the
     *       shipyard move is implicated through the recreation rate;</li>
     *   <li>window the same → the recreation story is still true but does NOT explain the red, and
     *       the cause of that red is still unnamed.</li>
     * </ul>
     * Prints, never asserts a threshold: there is no defensible number to assert before the first
     * pair of readings exists.
     */
    @Test
    public void howLongDoesAOneShotCommandSurvive() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server", serverHasVs());

        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");
        exec("gamerule doMobSpawning false");
        bot().setRenderDistance(4);
        assertTrue(exec("artest vs permaload true").contains("\"ok\":true"));

        StringBuilder out = new StringBuilder("[SPIKE one-shot command survival]\n");
        try {
            String arrangement = arrange(0);
            assertTrue("the arena did not build: " + arrangement, arrangement == null);
            String assemble = assembleFixture(0);
            assertTrue("the fixture did not assemble", assemble != null);
            assertTrue("the build must route to a ship: " + oneLine(assemble),
                    assemble.contains("\"rocketCount\":0"));
            for (int i = 0; i < 40 && count("ship-count-all") < 1; i++) {
                bot().waitTicks(5);
            }
            String delivery = deliver(0);
            assertTrue("the pilot was not delivered: " + delivery, delivery == null);

            String shipId = null;
            for (int i = 0; i < 40 && shipId == null; i++) {
                bot().waitTicks(5);
                String info = exec("artest vs ship-info 0 0 " + BASE_Y + " " + ARENA_Z + " "
                        + SHIP_LOOKUP_RADIUS);
                if (info.contains("\"managed\":true")) {
                    Matcher im = SHIP_ID.matcher(info);
                    shipId = im.find() ? im.group(1) : null;
                }
            }
            assertTrue("the ship never loaded", shipId != null);

            String mountInfo = exec("artest vs seat-mount 0 near 0 " + BASE_Y + " " + ARENA_Z + " 512");
            assertTrue("no seat: " + oneLine(mountInfo), mountInfo.contains("\"seatFound\":true"));
            Matcher dm = DUMMY_ID.matcher(mountInfo);
            assertTrue("no dummy id", dm.find());
            assertTrue("could not mount",
                    exec("artest player mount-entity " + dm.group(1)).contains("\"mounted\":true"));
            String riding = awaitRiding(Integer.parseInt(dm.group(1)));
            assertTrue("the client never began riding: " + riding, riding == null);

            // ONE command. Forward throttle rather than vertical: horizontal travel has no ceiling to
            // be mistaken for a command that stopped surviving.
            double[] before = shipXZ(shipId);
            String commanded = exec("artest vs seat-input 0 1 0 0 0 0 0");
            out.append("  commanded once: ").append(oneLine(commanded)).append('\n');
            out.append("  subspaceSeat=(").append(fmt(field(mountInfo, "seatX"))).append(',')
                    .append(fmt(field(mountInfo, "seatZ"))).append(")\n");

            double lastDist = 0d;
            int stoppedAtTick = -1;
            int quiet = 0;
            for (int sample = 1; sample <= SURVIVAL_SAMPLES; sample++) {
                bot().waitTicks(SURVIVAL_SAMPLE_TICKS);
                double[] now = shipXZ(shipId);
                double dist = Math.hypot(now[0] - before[0], now[1] - before[1]);
                double step = dist - lastDist;
                out.append("    t=").append(sample * SURVIVAL_SAMPLE_TICKS)
                        .append(" travelled=").append(fmt(dist))
                        .append(" step=").append(fmt(step)).append('\n');
                if (step < SURVIVAL_STEP_EPSILON) {
                    quiet++;
                    if (quiet >= 3 && stoppedAtTick < 0 && dist > 0.1d) {
                        stoppedAtTick = (sample - 2) * SURVIVAL_SAMPLE_TICKS;
                    }
                } else {
                    quiet = 0;
                }
                lastDist = dist;
            }
            out.append("  SURVIVAL WINDOW: ")
                    .append(stoppedAtTick < 0
                            ? "never stopped within " + (SURVIVAL_SAMPLES * SURVIVAL_SAMPLE_TICKS)
                                    + " ticks (total " + fmt(lastDist) + " blocks)"
                            : stoppedAtTick + " ticks, then motion ceased (total " + fmt(lastDist)
                                    + " blocks)")
                    .append('\n');
        } finally {
            System.out.println(out);
            writeReport("one-shot-command-survival.txt", out.toString());
            try {
                exec("artest player dismount");
                exec("artest vs permaload false");
            } catch (Exception ignored) {
                // teardown must not mask the reading
            }
        }
    }

    /** The ship's world X and Z, by id. */
    private double[] shipXZ(String shipId) {
        String last = "";
        for (int i = 0; i < 10; i++) {
            try {
                last = exec("artest vs ship-info 0 id " + shipId);
                Matcher mx = POS_X.matcher(last);
                Matcher mz = POS_Z.matcher(last);
                if (mx.find() && mz.find()) {
                    return new double[] {Double.parseDouble(mx.group(1)),
                            Double.parseDouble(mz.group(1))};
                }
                bot().waitTicks(2);
            } catch (Exception e) {
                throw new AssertionError("ship-info threw: " + e, e);
            }
        }
        throw new AssertionError("ship-info never returned a parseable position; last: " + last);
    }

    // ─── the measurement ────────────────────────────────────────────────────────

    /**
     * One controllability measurement where the ship already is: hold the REAL vertical-up key, the
     * SERVER ship must climb, and the CLIENT-rendered rider must climb with it. A transform that has
     * lost precision at a far coordinate shows up as divergence between those two and nowhere else.
     *
     * @return {@code "OK ..."} with the numbers, or the reason it failed
     */
    /**
     * Waits until the CLIENT reports it is riding something, and — if it never does — asks the three
     * questions that decide WHICH thing failed, because "the client is not riding" on its own cannot
     * tell a coordinate ceiling from an arrangement fault:
     * <ol>
     *   <li>where the CLIENT thinks the player is (a client that never arrived explains everything);</li>
     *   <li>what entities the CLIENT can see near him (an empty list means entity tracking never
     *       delivered the seat dummy — the mount had nothing to bind to);</li>
     *   <li>where the SERVER holds that same dummy (so a client/server split is visible as one).</li>
     * </ol>
     *
     * @return {@code null} once the client is riding, else the reason plus that diagnosis
     */
    private String awaitRiding(int dummyId) throws Exception {
        com.google.gson.JsonObject last = null;
        for (int i = 0; i < RIDING_ATTEMPTS; i++) {
            bot().waitTicks(5);
            last = bot().reportRidingEntity();
            if (last.has("riding") && last.get("riding").getAsBoolean() && last.has("posY")) {
                return null;
            }
        }
        String clientState;
        String clientEntities;
        try {
            clientState = String.valueOf(bot().reportState());
            clientEntities = String.valueOf(bot().reportEntities("", 128d));
        } catch (Exception e) {
            clientState = "unreadable: " + e;
            clientEntities = "unreadable";
        }
        return "the CLIENT never began riding the seat after " + (RIDING_ATTEMPTS * 5)
                + " ticks (last report: " + last + ")"
                + " | client state: " + oneLine(clientState)
                + " | client sees near him: " + oneLine(clientEntities)
                + " | server holds the dummy at: "
                + oneLine(exec("artest entity info 0 " + dummyId));
    }

    private double riderY() throws Exception {
        return bot().reportRidingEntity().get("posY").getAsDouble();
    }

    private String climbLeg(String shipId, double yBefore) throws Exception {
        double riderYBefore = riderY();
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        try {
            ClientPoll.until(bot()::waitTicks,
                    () -> shipY(shipId),
                    y -> y - yBefore > 1.5, 2, 100);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        bot().waitTicks(6);
        double serverDelta = shipY(shipId) - yBefore;
        double riderDelta = riderY() - riderYBefore;
        String numbers = "serverLift=" + fmt(serverDelta) + " riderLift=" + fmt(riderDelta)
                + " divergence=" + fmt(Math.abs(riderDelta - serverDelta));
        if (!(serverDelta > MIN_LIFT_BLOCKS)) {
            // A third witness separates "the seat glue died" from "the ship would not move".
            return "the vertical-up key did not lift the ship (" + numbers + "); server player: "
                    + oneLine(exec("artest player health"));
        }
        if (Math.abs(riderDelta - serverDelta) >= TRACK_TOLERANCE) {
            return "the CLIENT rider did not track the server ship (" + numbers + ")";
        }
        return "OK " + numbers;
    }

    // ─── arrangement ────────────────────────────────────────────────────────────

    /** @return {@code null} once the site is loaded and clear, else what is wrong with it */
    private String arrange(int x) throws Exception {
        int cx1 = (x - 32) >> 4, cz1 = (ARENA_Z - 32) >> 4;
        int cx2 = (x + 32) >> 4, cz2 = (ARENA_Z + 32) >> 4;
        String warm = exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        if (!warm.contains("\"ok\":true")) {
            return "chunk warmup failed: " + oneLine(warm);
        }
        // A stone pad at BASE_Y-1 and air above it: 16M is ocean, and the fixture must not be built
        // into water or into whatever the generator put there.
        exec("artest fill 0 " + (x - 8) + " " + (BASE_Y - 1) + " " + (ARENA_Z - 8) + " "
                + (x + 12) + " " + (BASE_Y - 1) + " " + (ARENA_Z + 12) + " minecraft:stone");
        String clear = exec("artest fill 0 " + (x - 8) + " " + BASE_Y + " " + (ARENA_Z - 8) + " "
                + (x + 12) + " " + (BASE_Y + 14) + " " + (ARENA_Z + 12) + " minecraft:air");
        if (!clear.contains("\"ok\":true")) {
            return "pre-clear failed: " + oneLine(clear);
        }
        String pad = exec("artest block at 0 " + x + " " + (BASE_Y - 1) + " " + ARENA_Z);
        if (!pad.contains("stone")) {
            return "the pad is not stone (" + oneLine(pad) + ")";
        }
        return null;
    }

    /** @return the assemble reply, or {@code null} if the fixture itself never landed */
    private String assembleFixture(int x) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + x + " " + BASE_Y + " " + ARENA_Z
                + " " + VARIANT);
        if (!fixture.contains("\"ok\":true")) {
            System.out.println("[SPIKE ship] fixture at x=" + x + " failed: " + oneLine(fixture));
            return null;
        }
        Matcher bp = BUILDER_POS.matcher(fixture);
        if (!bp.find()) {
            System.out.println("[SPIKE ship] fixture at x=" + x + " gave no builderPos: "
                    + oneLine(fixture));
            return null;
        }
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    /**
     * Puts the pilot on the ship through the long-jump path, retried: the chunks are loaded on the
     * SERVER while the client has not received them yet, and the first delivery of a far rung lands
     * in a world the client cannot see.
     *
     * @return {@code null} once he is there, or a reason string for the INCONCLUSIVE list
     */
    private String deliver(int x) throws Exception {
        double lastX = Double.NaN;
        for (int attempt = 1; attempt <= DELIVERY_ATTEMPTS; attempt++) {
            exec("artest player far-tp " + fmt(x + 0.5d) + " " + (BASE_Y + 6) + " "
                    + fmt(ARENA_Z + 0.5d));
            exec("artest server wait 0 40");
            bot().waitTicks(30);
            lastX = field(exec("artest player health"), "posX");
            if (Math.abs(lastX - (x + 0.5d)) < ARRIVAL_TOLERANCE) {
                return null;
            }
        }
        return "the pilot never arrived (server posX=" + lastX + ", wanted " + (x + 0.5d)
                + ") after " + DELIVERY_ATTEMPTS + " deliveries - delivery, not the ship";
    }

    // ─── instruments ────────────────────────────────────────────────────────────

    /**
     * The server ship's {@code posY}, asked BY ID and tolerant of unrelated console lines
     * interleaving with the probe's reply — at far coordinates a VS collision mixin can print into
     * the same window.
     *
     * <p>By id, not by position: a nearest-ship lookup has a distance term to be wrong about, and on
     * this ladder — two ships, one of them 16M away — a rung whose own ship had unloaded would
     * silently be answered with the OTHER rung's ship. That failure looks like two rungs agreeing to
     * four decimals, which is exactly what a clean far-coordinate result also looks like.</p>
     */
    private double shipY(String shipId) {
        String last = "";
        for (int i = 0; i < 10; i++) {
            try {
                last = exec("artest vs ship-info 0 id " + shipId);
                Matcher m = POS_Y.matcher(last);
                if (m.find()) {
                    return Double.parseDouble(m.group(1));
                }
                bot().waitTicks(2);
            } catch (Exception e) {
                throw new AssertionError("ship-info threw: " + e, e);
            }
        }
        throw new AssertionError("ship-info never returned a parseable posY; last reply: " + last);
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private double readDouble(String json) {
        Matcher m = POS_Y.matcher(json);
        assertTrue("expected a posY in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static double field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([-0-9.eE]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    /** The report is the deliverable, so it also lands on disk and survives a truncated console. */
    private static void writeReport(String name, String text) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("build", "spike-reports").toAbsolutePath();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(name), text.getBytes("UTF-8"));
        } catch (Exception e) {
            System.out.println("[SPIKE] could not write the report file: " + e);
        }
    }

    private static boolean hasPrefix(List<String> lines, String prefix) {
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String oneLine(String s) {
        return s.replace((char) 10, ' ').replace((char) 13, ' ').trim();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
