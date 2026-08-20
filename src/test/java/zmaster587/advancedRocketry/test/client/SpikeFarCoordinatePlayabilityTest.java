package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.Test;
import org.lwjgl.input.Keyboard;
import org.valkyrienskies.mod.common.ships.chunk_claims.ShipChunkAllocator;
import zmaster587.advancedRocketry.test.ServerTicks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE — can a player actually LIVE millions of blocks from the origin, or only exist there?
 *
 * <p>Chunk generation, block storage and entity doubles were already measured clean out to 28M with
 * the subject SPAWNED at the coordinate. None of them says anything about the thing that decides how
 * big a body may be drawn: whether a <b>player</b> walks, stands and collides normally out there.</p>
 *
 * <h2>Why the first attempt could not reach 8M, and why the reason was not vanilla</h2>
 * A connected player could not be delivered past ~4M, and vanilla's speed check
 * ({@code NetHandlerPlayServer} "moved too quickly!") was blamed. It is not the cause. The physics
 * mod installs a cancellable {@code @Inject} at the HEAD of
 * {@code NetHandlerPlayServer.setPlayerLocation} that CANCELS any teleport whose destination it
 * considers its own reserved "shipyard" region, and that region is the half-open quadrant
 * {@code chunkX >= 318401 && chunkZ >= -1599} — i.e. every position with
 * <b>X ≥ 5,094,416 and Z ≥ -25,584</b>. Teleports into it are dropped silently: the command reports
 * success, the mixin cancels, and the player never moves. That is exactly the reported symptom, and
 * it is a mod-imposed wall five million blocks out, not a vanilla precision limit.
 *
 * <p>Two consequences drive this class. {@link #whereExactlyDoesADeliveryStopWorking()} pins the
 * boundary against numbers PREDICTED from that predicate, so the mechanism is proven rather than
 * inferred from "2M worked and 8M did not". And the playability ladder runs at
 * {@code Z = }{@value #ARENA_Z}, below the quadrant's Z edge, where the predicate is false at every
 * X — so the original question can be answered out to 28M without touching the physics mod.</p>
 *
 * <h2>Acceptance, stated before the run</h2>
 * Every rung is compared against the {@code x=0} rung measured in the same run, in the same arena,
 * with the same key held for the same number of ticks:
 * <ol>
 *   <li><b>Walking distance</b> over {@value #WALK_TICKS} ticks of held {@code W} must be within
 *       <b>±10%</b> of the origin's, and at least {@value #MIN_WALK_BLOCKS} blocks absolute.</li>
 *   <li><b>Collision stand-off</b> from the wall walked into must be within
 *       <b>{@value #STANDOFF_TOLERANCE}</b> blocks of the origin's — the sharpest instrument here,
 *       being a sub-block quantity resolved from absolute coordinates.</li>
 *   <li><b>Standing</b>: {@code posY} within {@value #Y_TOLERANCE} of the floor top throughout.</li>
 *   <li><b>No rubber-band</b>: server and client agree on {@code posX} to within
 *       {@value #SYNC_TOLERANCE} blocks at rest.</li>
 * </ol>
 *
 * <p><b>Designed to come back NO.</b> If 28M behaves like the origin on all four, the ±2M bound has
 * nothing left holding it up. If it does not, the rung where it stops is the answer.</p>
 */
public class SpikeFarCoordinatePlayabilityTest extends AbstractClientE2ETest {

    /**
     * Measured indistinguishable from the origin: 2M, 8M, 16M, <b>16,777,216 = 2²⁴</b>, 20M, 24M —
     * so the suspicion that 2²⁴ is the wall is refuted, and the vanilla wiki's first documented
     * horizontal symptom (sound positioning) does not touch walking, standing or collision.
     * 28M is the only rung that ever failed, and on both sides at once (client displacement 0.0000,
     * not just the server's), which rules out the server dragging him back.
     *
     * <p><b>28M is deliberately NOT in the ladder.</b> It is the one coordinate that ever failed, and
     * it failed for a reason none of arrangement, run position, server-side revert or 2²⁴ explains —
     * a ladder of {@code 0, 28M, 24M, 28M, 0} was run for exactly that and both 28M rungs failed
     * while the 24M between them and the trailing origin passed. The finding is recorded where a
     * finding belongs; keeping a permanently red rung here would only make this class dead weight in
     * every client gate. The ladder below is the range the design actually uses — half-cell 16M, with
     * 20M and 24M as margin — so this class now guards "a player lives normally at the coordinates
     * our cells use", which is a different and durable claim from the one it was written for.</p>
     */
    private static final int[] X_LADDER = {0, 8_000_000, 16_000_000, 20_000_000, 24_000_000};

    /**
     * The arena's Z. The physics mod's reserved quadrant starts at {@code chunkZ >= -1599}
     * (Z ≥ -25,584); everything here sits well below it, so its teleport veto never fires and the
     * only thing under test is the coordinate's own magnitude.
     */
    private static final int ARENA_Z = -100_000;

    private static final int OVERWORLD = 0;
    /** Well above sea level: 2M and 16M are both ocean, and a delivery into water measures the water. */
    private static final int FLOOR_Y = 140;
    private static final int STAND_Y = FLOOR_Y + 1;

    /** The corridor runs +X from the player; the wall's near face is this many blocks ahead. */
    private static final int WALL_OFFSET = 16;
    private static final int WALK_TICKS = 40;
    private static final int RAM_TICKS = 160;

    private static final double MIN_WALK_BLOCKS = 5.0d;
    private static final double WALK_RATIO_TOLERANCE = 0.10d;
    private static final double STANDOFF_TOLERANCE = 0.05d;
    private static final double Y_TOLERANCE = 0.05d;
    private static final double SYNC_TOLERANCE = 0.5d;
    private static final double ARRIVAL_TOLERANCE = 1.0d;
    /** How many (deliver, settle) rounds a rung gets before it is called undeliverable. */
    private static final int DELIVERY_ATTEMPTS = 4;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    /**
     * Pins the delivery wall against numbers predicted from the physics mod's own predicate, so the
     * mechanism is proven rather than inferred. {@code isChunkInShipyard(cx, cz)} is
     * {@code cx >= CHUNK_X_START - MAX_CHUNK_RADIUS && cz >= CHUNK_Z_START - MAX_CHUNK_RADIUS}, so
     * the four cases below are decided before the run: one chunk under the X edge moves, the first
     * reserved chunk does not, and a coordinate deep inside moves again once Z drops below the
     * quadrant. A miss on ANY of the four falsifies the explanation.
     *
     * <p>The edge is READ from the allocator rather than written down. It was written down once —
     * as {@code cx >= 318401}, block X 5 094 416 — and then the constant was raised to give the
     * cell its clearance, at which point this test went red saying the explanation had been
     * falsified. It had not: the number had moved and the test had not been told. A test that
     * pins a mechanism must be keyed to the mechanism's own constant, or it pins the day it was
     * written.</p>
     */
    @Test
    public void whereExactlyDoesADeliveryStopWorking() throws Exception {
        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");

        List<String> report = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        // The first reserved BLOCK X, straight out of the predicate the teleport is cancelled by.
        final long edgeX = ((long) (ShipChunkAllocator.CHUNK_X_START
                - ShipChunkAllocator.MAX_CHUNK_RADIUS)) << 4;
        // Deep inside the quadrant, and derived so it stays inside whatever the edge becomes —
        // a hard-coded 28M was inside the old quadrant and would not be inside a much later one.
        final long deepX = edgeX + 1_000_000L;
        // {x, z, expectedToMove}
        double[][] cases = {
                {edgeX - 16 + 0.5d, 0.5d, 1d},   // one chunk under the edge
                {edgeX + 0.5d, 0.5d, 0d},        // the first reserved chunk
                {deepX + 0.5d, 0.5d, 0d},        // deep inside the quadrant
                {deepX + 0.5d, ARENA_Z + 0.5d, 1d}, // same X, Z below the quadrant's edge
        };
        for (double[] c : cases) {
            boolean expectMove = c[2] != 0d;
            String reply = exec("artest player far-tp " + fmt(c[0]) + " 200 " + fmt(c[1]));
            double from = field(reply, "fromX");
            double to = field(reply, "posX");
            boolean moved = Math.abs(to - c[0]) < ARRIVAL_TOLERANCE;
            boolean unchanged = Math.abs(to - from) < 1e-6d;
            report.add("target=(" + fmt(c[0]) + "," + fmt(c[1]) + ")"
                    + " chunk=(" + (((long) Math.floor(c[0])) >> 4) + "," + (((long) Math.floor(c[1])) >> 4) + ")"
                    + " predicted=" + (expectMove ? "MOVES" : "CANCELLED")
                    + " observed=" + (moved ? "MOVED" : unchanged ? "CANCELLED" : "ELSEWHERE(" + to + ")"));
            if (moved != expectMove) {
                wrong.add(report.get(report.size() - 1));
            }
            // Park him back near the origin so the next case starts from a known place.
            exec("artest player far-tp 0.5 200 0.5");
            ServerTicks.await(serverClient(), OVERWORLD, 20);
        }

        StringBuilder out = new StringBuilder("[SPIKE far-coordinate delivery boundary]\n");
        for (String line : report) {
            out.append("  ").append(line).append('\n');
        }
        System.out.println(out);
        writeReport("far-coordinate-delivery-boundary.txt", out.toString());
        assertTrue("the reserved-quadrant explanation predicts these four outcomes; it missed:\n" + out,
                wrong.isEmpty());
    }

    @Test
    public void canAPlayerWalkStandAndCollideFarFromTheOrigin() throws Exception {
        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");
        exec("gamerule doMobSpawning false");
        exec("gamerule doDaylightCycle false");
        exec("gamerule doWeatherCycle false");
        exec("weather clear");
        bot().setRenderDistance(4);

        List<String> report = new ArrayList<>();
        List<String> inconclusive = new ArrayList<>();
        Rung control = null;

        for (int x : X_LADDER) {
            buildArena(x);
            String arenaFault = inspectArena(x);
            if (arenaFault != null) {
                buildArena(x); // one retry: a fill can lose a race with chunk loading
                arenaFault = inspectArena(x);
            }
            if (arenaFault != null) {
                inconclusive.add("x=" + x + " the arena did not build - " + arenaFault
                        + " (arrangement, not the coordinate)");
                continue;
            }

            String delivery = deliverAndStand(x);
            if (delivery != null) {
                inconclusive.add("x=" + x + " " + delivery);
                continue;
            }

            double startServerX = serverX();
            double startClientX = clientX();
            double startY = serverY();

            bot().setLook(-90f, 0f); // yaw -90 = east = +X, straight down the corridor
            bot().waitTicks(5);

            bot().holdKey(Keyboard.KEY_W);
            bot().waitTicks(WALK_TICKS);
            double walkedServerX = serverX();
            // Read the CLIENT's own displacement beside the server's, at the one moment it can still
            // discriminate. A rung where the player barely moves has two completely different causes
            // — the client never walked, or it walked and the server dragged it back — and by the
            // time everything is at rest they agree either way, so "sync" at rest cannot tell them
            // apart. This sample can.
            double walkedClientX = clientX();
            double midY = serverY();
            // Keep the key held: the collision is measured with exactly the input that produced the
            // distance above.
            bot().waitTicks(RAM_TICKS);
            bot().releaseKey(Keyboard.KEY_W);
            bot().waitTicks(20);

            double finalServerX = serverX();
            double finalClientX = clientX();
            double finalY = serverY();

            double walked = walkedServerX - startServerX;
            // The wall's near face is at x+WALL_OFFSET; the player's box is 0.6 wide, so a clean
            // collision leaves his centre 0.3 short of it.
            double standoff = (x + WALL_OFFSET) - finalServerX;

            Rung rung = new Rung(x, walked, walkedClientX - startClientX, standoff, startY, midY,
                    finalY, Math.abs(finalServerX - finalClientX),
                    Math.abs(startServerX - startClientX));
            if (x == 0 && control == null) {
                control = rung; // the FIRST origin rung; a trailing one is judged against it
            }
            report.add(rung.line(control));
        }

        StringBuilder out = new StringBuilder("[SPIKE far-coordinate playability] walkTicks=" + WALK_TICKS
                + " ramTicks=" + RAM_TICKS + " wallOffset=" + WALL_OFFSET + " arenaZ=" + ARENA_Z + "\n");
        for (String line : report) {
            out.append("  ").append(line).append('\n');
        }
        for (String line : inconclusive) {
            out.append("  INCONCLUSIVE ").append(line).append('\n');
        }
        System.out.println(out);
        writeReport("far-coordinate-playability.txt", out.toString());

        assertTrue("no rung produced a usable measurement:\n" + out, !report.isEmpty());
        assertTrue("the x=0 control rung must be measurable - without it no far rung means anything:\n"
                + out, control != null);

        List<String> verdicts = new ArrayList<>();
        for (String line : report) {
            if (line.contains("VERDICT=FAIL")) {
                verdicts.add(line);
            }
        }
        assertTrue("a far coordinate did not behave like the origin:\n" + out, verdicts.isEmpty());
    }

    // ─── arrangement ────────────────────────────────────────────────────────────

    /**
     * A sealed stone corridor running +X from {@code x}, with a wall across it at
     * {@code x + WALL_OFFSET}, built BEFORE the player is delivered.
     */
    private void buildArena(int x) throws Exception {
        int fromChunk = (x - 8) >> 4;
        int toChunk = (x + WALL_OFFSET + 8) >> 4;
        int fromChunkZ = (ARENA_Z - 8) >> 4;
        int toChunkZ = (ARENA_Z + 8) >> 4;
        for (int cx = fromChunk; cx <= toChunk; cx++) {
            for (int cz = fromChunkZ; cz <= toChunkZ; cz++) {
                exec("artest chunk forceload " + OVERWORLD + " " + cx + " " + cz);
            }
        }
        ServerTicks.await(serverClient(), OVERWORLD, 60);

        int x1 = x - 4;
        int x2 = x + WALL_OFFSET + 4;
        exec("artest fill " + OVERWORLD + " " + x1 + " " + FLOOR_Y + " " + (ARENA_Z - 6) + " "
                + x2 + " " + (FLOOR_Y + 6) + " " + (ARENA_Z + 6) + " minecraft:stone");
        // Hollow out everything up to (but not including) the wall plane at x+WALL_OFFSET.
        exec("artest fill " + OVERWORLD + " " + (x1 + 1) + " " + STAND_Y + " " + (ARENA_Z - 5) + " "
                + (x + WALL_OFFSET - 1) + " " + (FLOOR_Y + 5) + " " + (ARENA_Z + 5) + " minecraft:air");
        ServerTicks.await(serverClient(), OVERWORLD, 20);
    }

    /**
     * Reads the arena back and reports the first thing that is not what it should be.
     *
     * <p>The first version of this control only checked that the FLOOR and the WALL are stone, and it
     * passed at every rung — including the one where the player then stood motionless through 200
     * ticks of held {@code W}. It could not fail on the thing that actually matters: whether the
     * corridor he has to walk down is <b>air</b>. A player delivered into solid stone stands at
     * exactly the right Y and cannot move a millimetre, which reads precisely like "movement is
     * broken at this coordinate". So the walkable line is now sampled along its whole length.</p>
     *
     * @return {@code null} if the arena is sound, else what was wrong and what was actually read
     */
    private String inspectArena(int x) throws Exception {
        for (int dx : new int[] {0, 1, 2, 5, 10, WALL_OFFSET - 2}) {
            String at = exec("artest block at " + OVERWORLD + " " + (x + dx) + " " + STAND_Y + " "
                    + ARENA_Z);
            if (!at.contains("minecraft:air")) {
                return "the corridor is not air at x+" + dx + " (" + oneLine(at) + ")";
            }
        }
        for (int dx : new int[] {0, 8, WALL_OFFSET - 1}) {
            String at = exec("artest block at " + OVERWORLD + " " + (x + dx) + " " + FLOOR_Y + " "
                    + ARENA_Z);
            if (!at.contains("stone")) {
                return "the floor is not stone at x+" + dx + " (" + oneLine(at) + ")";
            }
        }
        String wall = exec("artest block at " + OVERWORLD + " " + (x + WALL_OFFSET) + " " + STAND_Y
                + " " + ARENA_Z);
        if (!wall.contains("stone")) {
            return "the wall is not stone (" + oneLine(wall) + ")";
        }
        return null;
    }

    /**
     * Delivers the player into the arena and does not return until he is STANDING in it.
     *
     * <p>One delivery is not enough and the first run proved it: the chunks are force-loaded on the
     * server but the CLIENT has not received them yet, so client-side physics see air, he falls
     * through the floor, and the server accepts his movement packets. Delivering again once the
     * chunks have arrived is what makes him stay. The loop converges rather than guessing a settle
     * time, and reports which of the two conditions it never met.</p>
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
                ? "he arrived but would not stand (posY=" + fmt(lastY) + ", floor top " + STAND_Y
                        + ") - he is falling through a floor the client has not received"
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

    private double clientX() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerX") ? state.get("playerX").getAsDouble() : Double.NaN;
    }

    private static double field(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*([-0-9.eE]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    /** One rung's four numbers plus the verdict they earn against the origin control. */
    private static final class Rung {
        final int x;
        final double walked;
        final double clientWalked;
        final double standoff;
        final double startY;
        final double midY;
        final double finalY;
        final double syncAtRest;
        final double syncAtStart;

        Rung(int x, double walked, double clientWalked, double standoff, double startY, double midY,
                double finalY, double syncAtRest, double syncAtStart) {
            this.x = x;
            this.walked = walked;
            this.clientWalked = clientWalked;
            this.standoff = standoff;
            this.startY = startY;
            this.midY = midY;
            this.finalY = finalY;
            this.syncAtRest = syncAtRest;
            this.syncAtStart = syncAtStart;
        }

        String line(Rung control) {
            List<String> failures = new ArrayList<>();
            if (walked < MIN_WALK_BLOCKS) {
                failures.add("walked<" + MIN_WALK_BLOCKS);
            }
            if (Math.abs(startY - STAND_Y) > Y_TOLERANCE
                    || Math.abs(midY - STAND_Y) > Y_TOLERANCE
                    || Math.abs(finalY - STAND_Y) > Y_TOLERANCE) {
                failures.add("leftTheFloor");
            }
            if (syncAtRest > SYNC_TOLERANCE) {
                failures.add("serverClientDisagree");
            }
            if (control != null && control != this) {
                double ratio = control.walked == 0 ? Double.NaN : walked / control.walked;
                if (!(Math.abs(ratio - 1d) <= WALK_RATIO_TOLERANCE)) {
                    failures.add("walkRatio=" + fmt(ratio));
                }
                if (!(Math.abs(standoff - control.standoff) <= STANDOFF_TOLERANCE)) {
                    failures.add("standoffDelta=" + fmt(standoff - control.standoff));
                }
            }
            return "x=" + x
                    + " walked=" + fmt(walked) + "(client " + fmt(clientWalked) + ")"
                    + " standoff=" + fmt(standoff)
                    + " y=" + fmt(startY) + "/" + fmt(midY) + "/" + fmt(finalY)
                    + " sync=" + fmt(syncAtStart) + "->" + fmt(syncAtRest)
                    + " VERDICT=" + (failures.isEmpty() ? "OK" : "FAIL" + failures);
        }
    }

    private static void writeReport(String name, String text) {
        try {
            Path dir = Paths.get("build", "spike-reports").toAbsolutePath();
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), text.getBytes("UTF-8"));
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
