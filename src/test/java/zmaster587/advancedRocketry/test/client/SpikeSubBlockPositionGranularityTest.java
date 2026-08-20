package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.Test;
import zmaster587.advancedRocketry.test.ServerTicks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE — does a CONNECTED player's sub-block position survive the client↔server round trip far from
 * the origin?
 *
 * <h2>What this is NOT, and the retraction it carries</h2>
 * This class was first written around the observation "a 0.05-block move lands at 2M and 4M and does
 * not land at all at 8M, 12M or 16M", and it went looking for the coordinate at which precision runs
 * out. That observation was an artefact of its own delivery. The physics mod cancels, silently, any
 * teleport whose destination falls in its reserved shipyard quadrant — {@code chunkX >= 318401 &&
 * chunkZ >= -1599}, i.e. <b>X ≥ 5,094,416 and Z ≥ -25,584</b> — and the old arena sat at {@code Z = 0},
 * so every rung from 8M up was refused by a mod constant rather than by any property of the number.
 * The command still reported success. Nothing about precision was ever measured.
 *
 * <p>So the arena moves to {@code Z = }{@value #ARENA_Z}, below the quadrant's Z edge, where the
 * predicate is false at every X, and the long jump between rungs is delivered by
 * {@code /artest player far-tp} (vanilla's own dimension-change path, which is how a long jump escapes
 * the speed check). The short sub-block steps stay on plain {@code /tp}: they are not long jumps and
 * they are outside the quadrant.</p>
 *
 * <h2>The question that is actually left</h2>
 * Two neighbouring facts already exist and neither answers it:
 * <ul>
 *   <li>{@code SpikeFarCoordinateIntegrityTest} spawns armour stands 0.05 apart out to 28M and reads
 *       both back exactly — but a SPAWNED entity's position never crosses the wire.</li>
 *   <li>{@code SpikeFarCoordinatePlayabilityTest} measures a 0.3000 collision stand-off at 16M/20M/24M
 *       — a sub-block quantity, but one produced by the server's own physics, not asked for.</li>
 * </ul>
 * What remains is the round trip: a position ASKED for at a far coordinate, written by the server,
 * pushed to the client, and read back from both. If anything in that path narrows to a float, a
 * quantum of 1 or 2 blocks at 16M is what it would look like — and it would show here and nowhere else.
 *
 * <h2>Acceptance, stated before the run</h2>
 * At every rung, for every offset in the ladder {0, 0.05, 0.1, 0.25, 0.5, 1.0} from the same base:
 * <ol>
 *   <li>the SERVER's {@code posX} must equal the asked position within
 *       {@value #SERVER_TOLERANCE} blocks;</li>
 *   <li>the CLIENT's own {@code posX} must agree with it within {@value #CLIENT_TOLERANCE} blocks;</li>
 *   <li>every non-zero offset must read back DISTINCT from the offset-0 base — a quantum would
 *       collapse the small ones onto it.</li>
 * </ol>
 * {@code x = 0} is carried as the control in the same run, same arena shape, same commands: if the
 * control fails, the instrument is broken and no rung is evidence of anything.
 *
 * <p><b>Designed to come back NO.</b> If every offset resolves at 24M exactly as at the origin, the
 * wire is not the ceiling either, and "entity doubles degrade past ±2M" has nothing left holding it up
 * on any of its three legs.</p>
 */
public class SpikeSubBlockPositionGranularityTest extends AbstractClientE2ETest {

    /** The control first, then today's half-cell, the ratified half-cell, and the measured margin. */
    private static final int[] X_LADDER = {0, 2_000_000, 8_000_000, 16_000_000, 24_000_000};
    private static final double[] OFFSETS = {0d, 0.05d, 0.1d, 0.25d, 0.5d, 1.0d};

    /**
     * The arena's Z. The physics mod's reserved quadrant starts at {@code chunkZ >= -1599}
     * (Z ≥ -25,584); this sits well below it, so its teleport veto never fires and the only thing
     * under test is the coordinate's own magnitude.
     */
    private static final int ARENA_Z = -100_000;

    private static final int OVERWORLD = 0;
    /** Well above sea level: 2M and 16M are both ocean, and a delivery into water measures the water. */
    private static final int FLOOR_Y = 140;
    private static final int STAND_Y = FLOOR_Y + 1;

    private static final double SERVER_TOLERANCE = 0.001d;
    private static final double CLIENT_TOLERANCE = 0.05d;
    private static final double ARRIVAL_TOLERANCE = 1.0d;
    private static final double Y_TOLERANCE = 0.05d;
    /** How many (deliver, settle) rounds a rung gets before it is called undeliverable. */
    private static final int DELIVERY_ATTEMPTS = 4;

    private String botName;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void doesASubBlockPositionSurviveTheRoundTripFarFromTheOrigin() throws Exception {
        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");
        exec("gamerule doMobSpawning false");
        exec("gamerule doDaylightCycle false");
        exec("gamerule doWeatherCycle false");
        exec("weather clear");
        bot().setRenderDistance(4);

        String health = exec("artest player health");
        Matcher nm = Pattern.compile("\"player\"\\s*:\\s*\"([^\"]+)\"").matcher(health);
        assertTrue("player health must echo the player name: " + health, nm.find());
        botName = nm.group(1);

        List<String> report = new ArrayList<>();
        List<String> inconclusive = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        boolean controlHeld = false;

        // The cheapest competing explanation, asked once: a world border refuses a teleport past it
        // while reporting success, and it would produce this whole ladder with no precision story.
        report.add("worldborder: " + oneLine(exec("worldborder get")));

        for (int x : X_LADDER) {
            buildFloor(x);
            String floorFault = inspectFloor(x);
            if (floorFault != null) {
                buildFloor(x); // one retry: a fill can lose a race with chunk loading
                floorFault = inspectFloor(x);
            }
            if (floorFault != null) {
                inconclusive.add("x=" + x + " the floor did not build - " + floorFault
                        + " (arrangement, not the coordinate)");
                continue;
            }

            String delivery = deliverAndStand(x);
            if (delivery != null) {
                inconclusive.add("x=" + x + " " + delivery);
                continue;
            }

            double base = Double.NaN;
            List<String> rows = new ArrayList<>();
            List<String> rungFailures = new ArrayList<>();
            for (double offset : OFFSETS) {
                double target = x + 0.5d + offset;
                exec("tp " + botName + " " + fmt(target) + " " + STAND_Y + " " + fmt(ARENA_Z + 0.5d));
                ServerTicks.await(serverClient(), OVERWORLD, 6);
                bot().waitTicks(6);

                double gotServer = serverX();
                double gotClient = clientX();
                if (offset == 0d) {
                    base = gotServer;
                }
                double serverErr = Math.abs(gotServer - target);
                double clientErr = Math.abs(gotClient - gotServer);
                boolean distinct = offset == 0d || Math.abs(gotServer - base) > SERVER_TOLERANCE;

                rows.add("+" + fmt(offset) + " asked " + fmt(target)
                        + " server " + fmt(gotServer) + " (err " + fmt(serverErr) + ")"
                        + " client " + fmt(gotClient) + " (delta " + fmt(clientErr) + ")"
                        + " distinctFromBase=" + distinct);
                if (serverErr > SERVER_TOLERANCE) {
                    rungFailures.add("+" + fmt(offset) + " server missed by " + fmt(serverErr));
                }
                if (clientErr > CLIENT_TOLERANCE) {
                    rungFailures.add("+" + fmt(offset) + " client disagrees by " + fmt(clientErr));
                }
                if (!distinct) {
                    rungFailures.add("+" + fmt(offset) + " collapsed onto the base");
                }
            }

            report.add("x=" + x + (rungFailures.isEmpty() ? " OK" : " FAIL " + rungFailures));
            for (String r : rows) {
                report.add("      " + r);
            }
            if (x == 0) {
                controlHeld = rungFailures.isEmpty();
            } else if (!rungFailures.isEmpty()) {
                broken.add(x + rungFailures.toString());
            }
        }

        StringBuilder out = new StringBuilder("[SPIKE sub-block position round trip]\n");
        for (String line : report) {
            out.append("  ").append(line).append('\n');
        }
        for (String line : inconclusive) {
            out.append("  INCONCLUSIVE ").append(line).append('\n');
        }
        System.out.println(out);
        writeReport("far-coordinate-subblock-roundtrip.txt", out.toString());

        // The control decides whether anything else in this run is evidence. Asserted FIRST, so a
        // broken instrument reports as a broken instrument and not as a coordinate ceiling.
        assertTrue("the x=0 control did not resolve its own offset ladder - the instrument is broken, "
                + "so no rung here says anything about far coordinates:\n" + out, controlHeld);
        assertTrue("a sub-block position was lost at: " + broken + "\n" + out, broken.isEmpty());
    }

    // ─── arrangement ────────────────────────────────────────────────────────────

    private void buildFloor(int x) throws Exception {
        exec("artest chunk forceload " + OVERWORLD + " " + (x >> 4) + " " + (ARENA_Z >> 4));
        ServerTicks.await(serverClient(), OVERWORLD, 20);
        exec("artest fill " + OVERWORLD + " " + (x - 4) + " " + FLOOR_Y + " " + (ARENA_Z - 4) + " "
                + (x + 4) + " " + FLOOR_Y + " " + (ARENA_Z + 4) + " minecraft:stone");
        exec("artest fill " + OVERWORLD + " " + (x - 4) + " " + STAND_Y + " " + (ARENA_Z - 4) + " "
                + (x + 4) + " " + (STAND_Y + 2) + " " + (ARENA_Z + 4) + " minecraft:air");
    }

    /** @return {@code null} if the floor is where it must be, else what is wrong with it */
    private String inspectFloor(int x) throws Exception {
        for (int dx : new int[] {0, 1, 2}) {
            String at = exec("artest block at " + OVERWORLD + " " + (x + dx) + " " + FLOOR_Y + " "
                    + ARENA_Z);
            if (!at.contains("stone")) {
                return "the floor is not stone at x+" + dx + " (" + oneLine(at) + ")";
            }
            String above = exec("artest block at " + OVERWORLD + " " + (x + dx) + " " + STAND_Y + " "
                    + ARENA_Z);
            if (!above.contains("minecraft:air")) {
                return "the standing space is not air at x+" + dx + " (" + oneLine(above) + ")";
            }
        }
        return null;
    }

    /**
     * Delivers the player into the arena and does not return until he is STANDING in it. One delivery
     * is not enough: the chunks are force-loaded on the SERVER but the client has not received them
     * yet, so client-side physics see air and he falls through the floor. The loop converges rather
     * than guessing a settle time, and reports which of the two conditions it never met.
     *
     * @return {@code null} once he is standing, or a reason string for the INCONCLUSIVE list
     */
    private String deliverAndStand(int x) throws Exception {
        double lastX = Double.NaN;
        double lastY = Double.NaN;
        String lastReply = "";
        for (int attempt = 1; attempt <= DELIVERY_ATTEMPTS; attempt++) {
            lastReply = exec("artest player far-tp " + fmt(x + 0.5d) + " " + STAND_Y + " "
                    + fmt(ARENA_Z + 0.5d));
            ServerTicks.await(serverClient(), OVERWORLD, 40);
            bot().waitTicks(30);
            lastX = serverX();
            lastY = serverY();
            if (Math.abs(lastX - (x + 0.5d)) < ARRIVAL_TOLERANCE
                    && Math.abs(lastY - STAND_Y) < Y_TOLERANCE) {
                return null;
            }
        }
        boolean arrived = Math.abs(lastX - (x + 0.5d)) < ARRIVAL_TOLERANCE;
        return (arrived
                ? "he arrived but would not stand (posY=" + fmt(lastY) + ", floor top " + STAND_Y + ")"
                : "the player never arrived (server posX=" + lastX + ", wanted " + (x + 0.5d) + ")")
                + " after " + DELIVERY_ATTEMPTS + " deliveries - arrangement, not the coordinate."
                + " lastReply=" + oneLine(lastReply);
    }

    // ─── instruments ────────────────────────────────────────────────────────────

    private double serverX() throws Exception {
        return field(exec("artest player health"), "posX");
    }

    private double serverY() throws Exception {
        return field(exec("artest player health"), "posY");
    }

    /** The CLIENT's own record of where it thinks it is — the far end of the round trip. */
    private double clientX() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerX") ? state.get("playerX").getAsDouble() : Double.NaN;
    }

    private static double field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([-0-9.eE]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
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

    private static String oneLine(String s) {
        return s.replace((char) 10, ' ').replace((char) 13, ' ').trim();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
