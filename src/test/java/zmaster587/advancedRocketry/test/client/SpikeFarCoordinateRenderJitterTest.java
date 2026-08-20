package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.Test;
import zmaster587.advancedRocketry.test.ServerTicks;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE — is the render actually quantized far from the origin, the thing the 4M cell was sized for?
 *
 * <p>The cell used to be 4,000,000 blocks, justified with "entity doubles / chunks / lighting degrade
 * past ~±2M blocks in 1.12.2". The server half of that (chunk generation, block storage) measured
 * CLEAN out to 28M. This measures the visual half.
 *
 * <h2>The stimulus, and why it is motion rather than a still frame</h2>
 * A float quantum does not produce a shimmer in a static scene — the error is CONSTANT, so a still
 * camera at 16M renders a still (if slightly displaced) image. Quantization shows up when the camera
 * moves by LESS than the quantum: the frame then refuses to change until the accumulated motion
 * crosses one step. So the camera is walked in {@value #STEP_BLOCKS}-block increments and the metric
 * is <b>how many consecutive frames come back byte-identical</b>.
 *
 * <p>Expected, if the render path carried absolute coordinates in float: at ±2M the quantum is 0.25
 * block, so ~5 frames repeat per step; at ±16M it is 2 blocks, so ~40 repeat. Expected, if the path
 * subtracts the viewer position in double before casting (which is what
 * {@code RenderManager.renderEntityStatic} and Valkyrien Skies' {@code PhysObjectRenderManager} both
 * appear to do): zero repeats at every coordinate.
 *
 * <h2>Two controls, because this instrument has a known way of lying</h2>
 * <ol>
 *   <li><b>The capture must contain a scene.</b> A framebuffer enabled at RUNTIME receives the HUD
 *       pass and not the world pass, so every capture comes back as the clear colour — which reads
 *       exactly like "the renderer drew nothing". The client must be started with
 *       {@code -PclientFbo=true}, and the first frame is checked for being more than one flat colour.</li>
 *   <li><b>The scene must be STATIC.</b> Two captures with no motion between them must be identical.
 *       If they are not, something in the frame is animating and "frames differ" can no longer mean
 *       "the camera moved" — the run is inconclusive and says so rather than producing a number.</li>
 * </ol>
 *
 * <h2>Why the first run of this class stopped at 4M, and why that was not the render</h2>
 * It delivered with plain {@code /tp} into an arena at {@code Z = 0}. The physics mod cancels,
 * silently, any teleport into its reserved shipyard quadrant — {@code chunkX >= 318401 && chunkZ >=
 * -1599}, i.e. <b>X ≥ 5,094,416 and Z ≥ -25,584</b> — while the command still reports success. Every
 * rung from 8M up was therefore refused by a mod constant, and the camera never left the previous
 * coordinate. The arena now sits at {@code Z = }{@value #ARENA_Z}, below the quadrant's Z edge, and
 * the long jump between rungs goes through {@code /artest player far-tp} (vanilla's own
 * dimension-change path, which is how a long jump escapes the speed check). The sub-block camera
 * steps stay on plain {@code /tp}: they are not long jumps, and they are outside the quadrant.
 *
 * <p>Designed to come back NO: if every coordinate shows zero repeats, the render is not the ceiling
 * and the cell bound has to be justified by something else or dropped.</p>
 */
public class SpikeFarCoordinateRenderJitterTest extends AbstractClientE2ETest {

    /**
     * The origin is carried as the CONTROL in the same run: "zero repeats at 16M" means nothing until
     * the same instrument has shown zero repeats where no one suspects a quantum. Then today's
     * half-cell, the ratified half-cell (16M) and the measured margin (24M).
     */
    private static final int[] X_LADDER = {0, 2_000_000, 8_000_000, 16_000_000, 24_000_000};

    /**
     * The arena's Z. The physics mod's reserved quadrant starts at {@code chunkZ >= -1599}
     * (Z ≥ -25,584); this sits well below it, so its teleport veto never fires and the only thing
     * under test is the coordinate's own magnitude.
     */
    private static final int ARENA_Z = -100_000;

    /** Sub-block camera step. Smaller than every quantum in the table, so a quantum shows as repeats. */
    private static final double STEP_BLOCKS = 0.05d;
    private static final int STEPS = 12;
    /** How many 20-tick waits the frame gets to stop changing on its own before a teleport. */
    private static final int SETTLE_ATTEMPTS = 15;
    /** How many (deliver, settle) rounds a rung gets before it is called undeliverable. */
    private static final int DELIVERY_ATTEMPTS = 4;

    private static final int OVERWORLD = 0;
    private static final int FLOOR_Y = 140;
    private static final int EYE_Y = FLOOR_Y + 1;

    private Path outDir;
    private String botName;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void howFarFromTheOriginDoesTheRenderStartToQuantize() throws Exception {
        outDir = Paths.get(System.getProperty("forge.test.client.screenshotDir", "build/test-screenshots"))
                .toAbsolutePath();
        Files.createDirectories(outDir);

        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");
        // Freeze everything that could change a pixel for a reason this spike did not cause.
        exec("gamerule doDaylightCycle false");
        exec("gamerule doMobSpawning false");
        exec("gamerule doWeatherCycle false");
        exec("weather clear");
        exec("time set 6000");

        String health = exec("artest player health");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"player\"\\s*:\\s*\"([^\"]+)\"").matcher(health);
        assertTrue("player health must echo the player name: " + health, m.find());
        botName = m.group(1);

        JsonObject fb = bot().setFramebuffer(true);
        assertTrue("this client's GL must support the framebuffer capture path: " + fb,
                fb.get("supported").getAsBoolean());
        bot().setHudHidden(true);
        bot().setRenderDistance(4);

        List<String> report = new ArrayList<>();
        List<String> inconclusive = new ArrayList<>();
        // Per rung: the longest run of byte-identical frames, i.e. the quantum in camera steps.
        java.util.Map<Integer, Integer> longestRunByX = new java.util.LinkedHashMap<>();

        for (int x : X_LADDER) {
            // A sealed stone box: the only thing in frame is a wall a few blocks away, so nothing in
            // the picture can move on its own (no sky, no sun, no clouds, no weather).
            exec("artest chunk forceload " + OVERWORLD + " " + (x >> 4) + " " + (ARENA_Z >> 4));
            // Put the player there FIRST so the chunks around him are live, and build the box only
            // then. The first two runs built into unloaded chunks, the player fell into an ocean, and
            // every frame was animated water — the controls caught it, but the arrangement had to be
            // read off a captured frame to see WHY. FLOOR_Y is well above sea level for the same
            // reason: 2M and 16M are both ocean.
            deliver(x, FLOOR_Y + 20);
            exec("artest fill " + OVERWORLD + " " + (x - 6) + " " + FLOOR_Y + " " + (ARENA_Z - 6) + " "
                    + (x + 6) + " " + (FLOOR_Y + 5) + " " + (ARENA_Z + 6) + " minecraft:stone");
            exec("artest fill " + OVERWORLD + " " + (x - 5) + " " + (FLOOR_Y + 1) + " " + (ARENA_Z - 5)
                    + " " + (x + 5) + " " + (FLOOR_Y + 4) + " " + (ARENA_Z + 5) + " minecraft:air");
            // A patterned wall: a flat surface gives a frame whose pixels barely move, and a
            // sub-block shift in a flat texture is exactly the change this must be able to see.
            exec("artest fill " + OVERWORLD + " " + (x - 5) + " " + (FLOOR_Y + 1) + " " + (ARENA_Z + 5)
                    + " " + (x + 5) + " " + (FLOOR_Y + 4) + " " + (ARENA_Z + 5) + " minecraft:bookshelf");

            // ARRANGEMENT CHECK, ON THE AXIS THAT CARRIES THE CONDITION. This used to test posY, and
            // posY is right whenever the player stands on ANY floor — so it passed while he was still
            // in the previous coordinate's box, and three separate readings were taken of a player who
            // was not there. Delivery is RETRIED until the server's own posX says he arrived, and
            // abandoned loudly if it never does.
            double actualX = deliver(x, EYE_Y);
            bot().setLook(0f, 0f); // face +Z, straight at the bookshelf wall
            bot().waitTicks(40);
            if (!(Math.abs(actualX - (x + 0.5d)) < 2d)) {
                inconclusive.add("x=" + x + " the player never arrived (posX=" + actualX
                        + ", wanted " + (x + 0.5d) + ") - delivery, not the render");
                continue;
            }

            BufferedImage first = capture("jitter_" + x + "_ctrl_a");
            if (isFlat(first)) {
                inconclusive.add("x=" + x + " capture is one flat colour " + describe(first)
                        + " - the framebuffer is not receiving the world pass (start with -PclientFbo=true)");
                continue;
            }
            // SETTLE. The first run said the scene was not static and it was right: chunk streaming,
            // lighting propagation and the client's own catch-up keep changing pixels for a while
            // after a teleport. Wait for the frame to stop moving ON ITS OWN before asking whether
            // MOTION moves it — an unsettled scene answers "the frame changed" to every question.
            BufferedImage second = null;
            int settleAttempts = 0;
            int lastDelta = Integer.MAX_VALUE;
            BufferedImage previousSettle = first;
            while (settleAttempts < SETTLE_ATTEMPTS) {
                settleAttempts++;
                bot().waitTicks(20);
                BufferedImage now = capture("jitter_" + x + "_settle" + settleAttempts);
                lastDelta = differingPixels(previousSettle, now);
                previousSettle = now;
                if (lastDelta == 0) {
                    second = now;
                    break;
                }
            }
            if (second == null) {
                inconclusive.add("x=" + x + " the frame never stopped changing on its own after "
                        + settleAttempts + " attempts (last delta " + lastDelta + "px) - the scene is "
                        + "not static, so frame differences cannot be attributed to camera motion");
                continue;
            }

            List<Double> movedOnServer = new ArrayList<>();
            int repeats = 0;
            int maxRun = 0;
            int run = 0;
            BufferedImage previous = second;
            for (int step = 1; step <= STEPS; step++) {
                double px = x + 0.5d + step * STEP_BLOCKS;
                exec("tp " + botName + " " + fmt(px) + " " + EYE_Y + " " + fmt(ARENA_Z + 0.5d));
                bot().waitTicks(8);
                // THE MISSING CONTROL. "The frame did not change" and "the player did not move" are
                // the same observation until the position is read back. The first version of this
                // spike read only the frame and concluded the RENDER quantizes — a conclusion its own
                // data could not support.
                double serverX = posXOf(exec("artest player health"));
                movedOnServer.add(serverX);
                BufferedImage now = capture("jitter_" + x + "_step" + step);
                if (identical(previous, now)) {
                    repeats++;
                    run++;
                    maxRun = Math.max(maxRun, run);
                } else {
                    run = 0;
                }
                previous = now;
            }
            double serverSpan = movedOnServer.isEmpty() ? 0d
                    : movedOnServer.get(movedOnServer.size() - 1) - movedOnServer.get(0);
            int distinctServerPositions = new java.util.HashSet<>(movedOnServer).size();
            double impliedQuantum = maxRun == 0 ? 0d : (maxRun + 1) * STEP_BLOCKS;
            report.add("x=" + x + " steps=" + STEPS
                    + " serverMoved=" + fmt(serverSpan) + "blk/" + distinctServerPositions + "distinct"
                    + " identicalFrames=" + repeats
                    + " longestRun=" + maxRun + (maxRun >= STEPS ? " quantum>=" + fmt(STEPS * STEP_BLOCKS) : " quantum~" + fmt(impliedQuantum))
                    + " blocks "
                    + describe(previous));
            // A repeat means "the render did not change". That is only about the RENDER if the camera
            // actually moved, so a rung whose stimulus did not land is inconclusive, never a finding.
            if (distinctServerPositions < STEPS) {
                inconclusive.add("x=" + x + " only " + distinctServerPositions + " of " + STEPS
                        + " camera steps landed on the server - the stimulus, not the render");
            } else {
                longestRunByX.put(x, maxRun);
            }
        }

        StringBuilder out = new StringBuilder(
                "[SPIKE far-coordinate render jitter] step=" + STEP_BLOCKS + " blocks\n");
        for (String line : report) {
            out.append("  ").append(line).append('\n');
        }
        for (String line : inconclusive) {
            out.append("  INCONCLUSIVE ").append(line).append('\n');
        }
        System.out.println(out);
        writeReport("far-coordinate-render-jitter.txt", out.toString());

        // The control decides whether anything else here is evidence: at the origin nobody suspects a
        // quantum, so if the instrument reports repeats THERE it cannot tell a quantized render from a
        // camera that did not move. Asserted first, and separately.
        Integer control = longestRunByX.get(0);
        assertTrue("the x=0 control produced no usable measurement, so no far rung is evidence:\n" + out,
                control != null);
        assertTrue("the x=0 control repeated " + control + " frames in a row - a sub-block camera step "
                + "does not change the picture even at the origin, so this instrument cannot see the "
                + "thing it was built to see:\n" + out, control == 0);

        List<String> quantized = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> e : longestRunByX.entrySet()) {
            if (e.getKey() != 0 && e.getValue() > control) {
                quantized.add("x=" + e.getKey() + " longestRun=" + e.getValue()
                        + " (~" + fmt((e.getValue() + 1) * STEP_BLOCKS) + " blocks)");
            }
        }
        assertTrue("the render quantizes further out than at the origin: " + quantized + "\n" + out,
                quantized.isEmpty());
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    /**
     * Puts the camera at {@code (x + 0.5, y, ARENA_Z + 0.5)} through the long-jump path and returns
     * the server's own reading of where he ended up. Retried, because the chunks are force-loaded on
     * the SERVER while the client has not received them yet — the first delivery of a rung routinely
     * lands in a world the client cannot see.
     */
    private double deliver(int x, int y) throws Exception {
        double actualX = Double.NaN;
        for (int attempt = 1; attempt <= DELIVERY_ATTEMPTS; attempt++) {
            exec("artest player far-tp " + fmt(x + 0.5d) + " " + y + " " + fmt(ARENA_Z + 0.5d));
            ServerTicks.await(serverClient(), OVERWORLD, 60);
            bot().waitTicks(20);
            actualX = posXOf(exec("artest player health"));
            if (Math.abs(actualX - (x + 0.5d)) < 2d) {
                break;
            }
        }
        return actualX;
    }

    private BufferedImage capture(String name) throws Exception {
        bot().setHudHidden(true);
        bot().waitTicks(4);
        JsonObject shot = bot().screenshot(name);
        assertTrue("screenshot must land on disk: " + shot, shot.get("exists").getAsBoolean());
        Path dst = outDir.resolve(name + ".png");
        Files.copy(Paths.get(shot.get("path").getAsString()), dst, StandardCopyOption.REPLACE_EXISTING);
        BufferedImage image = ImageIO.read(new File(dst.toString()));
        assertTrue("screenshot must decode: " + dst, image != null);
        return image;
    }

    private static boolean identical(BufferedImage a, BufferedImage b) {
        return differingPixels(a, b) == 0;
    }

    private static int differingPixels(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return Integer.MAX_VALUE;
        }
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if ((a.getRGB(x, y) & 0xFFFFFF) != (b.getRGB(x, y) & 0xFFFFFF)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** One flat colour = the framebuffer never received the world pass. */
    private static boolean isFlat(BufferedImage img) {
        int first = img.getRGB(0, 0) & 0xFFFFFF;
        for (int y = 0; y < img.getHeight(); y += 3) {
            for (int x = 0; x < img.getWidth(); x += 3) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != first) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String describe(BufferedImage img) {
        return "[" + img.getWidth() + "x" + img.getHeight() + "]";
    }

    /** The server's own reading of where the player is, so the stimulus can be shown to have landed. */
    private static double posXOf(String healthJson) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"posX\"\\s*:\\s*([-0-9.eE]+)").matcher(healthJson);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    /** The report is the deliverable, so it also lands on disk and survives a truncated console. */
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
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}
