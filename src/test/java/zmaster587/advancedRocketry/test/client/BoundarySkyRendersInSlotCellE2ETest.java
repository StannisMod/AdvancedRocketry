package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The sky a pilot actually SEES inside a space slot cell, measured in PIXELS off a real client.
 *
 * <p>Everything upstream of rasterization already had coverage: the server producer, the broadcast, and
 * the client store {@code PacketSystemBodiesSync.CLIENT_BODIES}. None of it could tell a pilot staring at
 * an empty sky which half was empty, because a primitive that is emitted and then discarded by the
 * rasterizer is indistinguishable from one that was never emitted. So this test looks at the frame.</p>
 *
 * <p>Honest client e2e: the stimulus is a real look ({@code set_look}) on the real client and the
 * observation is a real capture of the real framebuffer ({@code screenshot}). Delete the client and there
 * are no pixels to count. Server probes only arrange (register the slot, settle a ship, register the
 * body) and act as the cross-side oracle for what SHOULD be drawn.</p>
 *
 * <h2>Why the harness needs three settings changed</h2>
 * <ul>
 *   <li>{@code setRenderDistance(8)} — vanilla runs the whole sky pass only when
 *       {@code renderDistanceChunks >= 4} ({@code EntityRenderer.renderWorldPass}); the harness pins it
 *       at 2, so without this the sky renderer never runs and every frame is honestly empty for the
 *       wrong reason. 8 also puts the sky far plane at 256, clear of the ~100-unit sky geometry.</li>
 *   <li>{@code setFramebuffer(true)} — without the FBO a capture reads a back buffer the driver may
 *       already have discarded. <b>Run this test with {@code -PclientFbo=true}</b>: enabling the FBO at
 *       RUNTIME is not enough, because the recreated framebuffer receives only the HUD pass and not the
 *       world pass, so every capture comes back as the framebuffer's own white clear colour. The
 *       liveness control below is what makes that failure loud instead of a false accusation against the
 *       renderer.</li>
 *   <li>{@code setHudHidden(true)} — the HUD is not part of the subject and actively corrupts it. The
 *       chat overlay carries the harness's own per-command completion markers, which sit across the
 *       middle of the frame and CHANGE between two captures. Hiding also drains toasts, which vanilla
 *       draws outside the hideGUI gate.</li>
 * </ul>
 * All three are restored afterwards.
 *
 * <h2>Nothing here assumes a background colour</h2>
 * The world pass clears to the dimension's FOG colour, and for a slot dim that is
 * {@code fogColor * sunBrightness} — so it is white by day and dark by night, not the black the defect
 * report describes. Every measurement below is therefore expressed as "differs from the background this
 * frame actually has", with the background measured off the frame itself, and the world time is pinned
 * so it cannot drift mid-test.
 *
 * <h2>The measurements, and why none can be satisfied trivially</h2>
 * <ol>
 *   <li><b>Sky pass liveness (control, and it must run FIRST)</b> — the same capture pipeline pointed at
 *       a dimension whose sky is known-good: AR's own overworld sky, high enough that it renders as a
 *       starfield. If that frame is uniform, the sky pass is not running at all and every verdict below
 *       would be an empty frame blamed on production. This is what separates "the renderer draws
 *       nothing" from "the renderer is never called", and it has already earned its place twice — once
 *       catching a capture that held only the HUD, once catching an altitude-dependent sky.</li>
 *   <li><b>Boundary ring</b> — at the horizon, pixels differing from the background in a thin band
 *       across the middle of the frame, against the SAME measurement in a strip near the top. The ring
 *       is a band at the camera's horizon, so a uniformly tinted frame scores equally in both strips and
 *       fails the difference.</li>
 *   <li><b>Body billboard, exact cancellation</b> — two captures from the IDENTICAL camera direction,
 *       one before the body exists and one after. The sky here is camera-centred, so every other pixel
 *       (stars, ring) is bit-identical between them and the differing pixels are the billboard and
 *       nothing else. A far-corner control box must stay unchanged.</li>
 *   <li><b>Body billboard, aimed vs aimed away</b> — the same body, camera on its bearing vs 180 degrees
 *       off it, compared inside a box the billboard covers.</li>
 *   <li><b>Starfield</b> — pixels differing from the background in the upper part of a frame that holds
 *       no ring and no body, i.e. the cell is not an empty void.</li>
 * </ol>
 *
 * <h2>Setup shortcuts, and what human action each replaces</h2>
 * A player reaches this view by flying a ship into space; the arrival settles it in the ledger and the
 * cell's contents come from the generated universe. Here {@code ledger-settle} injects the settled entry
 * and {@code add-poi} registers the body. Both change only WHICH data the producer has, not which
 * object, frame or lifecycle stage the renderer reads — the renderer is fed through the identical
 * production broadcast — so the rendering path under test is the real one.
 */
public class BoundarySkyRendersInSlotCellE2ETest extends AbstractClientE2ETest {

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");
    private static final Pattern FIRST_DIM = Pattern.compile("\"dims\":\\[(-?\\d+)");
    private static final String CLIENT_BODIES_CLASS =
            "zmaster587.advancedRocketry.network.PacketSystemBodiesSync";

    /** Cell the ship settles in. sy=5000 dodges the fallback stars (all at sy=sz=0). */
    private static final String CELL = "0 5000 0";
    /** The body sits straight UP from the ship, so aiming at it is a pitch and nothing else. */
    private static final String BODY_LOCAL = "0 1000 0";

    /** Per-channel delta above which two pixels count as different. Well above PNG/GL noise. */
    private static final int DIFF = 24;

    /**
     * Render distance held while capturing. Must be >= 4 or vanilla skips the sky pass entirely; it also
     * sets the sky projection's far plane to twice this many blocks, which has to clear the ~100-unit
     * radius the sky geometry is drawn at.
     */
    private static final int SKY_RENDER_DISTANCE = 8;

    /**
     * Altitude for the overworld control frame. High enough that the atmosphere is thin, so AR's own
     * overworld sky renders as a dark sky with a dense starfield rather than a bright noon blue — a
     * signal that does not depend on where the sun happens to be.
     */
    private static final int OVERWORLD_CAPTURE_Y = 400;

    private Path outDir;
    private String botName;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @After
    public void tearDownStack() {
        try {
            exec("artest space entry-clear");
        } catch (Exception ignored) {
        }
    }

    @Test
    public void aPilotInASlotCellSeesTheRingTheBodyAndStars() throws Exception {
        outDir = Paths.get(System.getProperty("forge.test.client.screenshotDir", "build/test-screenshots"))
                .toAbsolutePath();
        Files.createDirectories(outDir);

        // Command feedback must not reach the player's chat overlay: a chat line appearing between two
        // captures would be a difference this test did not cause.
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");
        // Pin the sky: the fog clear colour is fogColor * sunBrightness, so a drifting clock would move
        // the background under the measurements.
        exec("gamerule doDaylightCycle false");
        exec("weather clear");
        exec("time set 6000");

        String health = exec("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(health);
        assertTrue("player health must echo the player name: " + health, nameM.find());
        botName = nameM.group(1);

        JsonObject rd = bot().setRenderDistance(SKY_RENDER_DISTANCE);
        int previousRenderDistance = rd.get("previous").getAsInt();
        assertTrue("the sky pass gate must be open, read back off the client's own field: " + rd,
                rd.get("skyPassEnabled").getAsBoolean());
        JsonObject fb = bot().setFramebuffer(true);
        boolean previousFbo = fb.get("previous").getAsBoolean();
        assertTrue("this client's GL must support the framebuffer capture path: " + fb,
                fb.get("supported").getAsBoolean());
        boolean previousHud = bot().setHudHidden(true).get("previous").getAsBoolean();

        BufferedImage overworldZenith;
        BufferedImage horizon;
        BufferedImage zenithNoBody;
        BufferedImage zenithBody;
        BufferedImage nadirBody;
        int slotDim;
        try {
            // --- Control FIRST: is the sky pass running at all? Above the clouds so nothing but sky is
            // in frame, at noon so the sun is at the zenith.
            overworldZenith = capture(0, OVERWORLD_CAPTURE_Y, 0f, -90f, "overworld_zenith");
            int owBackground = modalColour(overworldZenith, 0, overworldZenith.getWidth(),
                    0, overworldZenith.getHeight());
            long owSky = differsCount(overworldZenith, 0, overworldZenith.getWidth(),
                    0, overworldZenith.getHeight(), owBackground);
            // Keyed on the STARFIELD, not the sun: stars cover the whole celestial sphere, so this holds
            // for any look direction and any time of day, whereas the sun's position is AR's own orbital
            // maths and can simply be out of frame. Only the sky pass paints them - with the pass off,
            // the frame is the fog clear and this is 0.
            assertTrue("HARNESS CONTROL: the client is not running the sky pass at all - the overworld"
                    + " sky at altitude " + OVERWORLD_CAPTURE_Y + " must be a starfield, but only " + owSky
                    + "px differ from the background " + rgb(owBackground) + " " + describe(overworldZenith)
                    + ". Nothing below this line could mean anything. renderDistance=" + rd
                    + " (" + outDir.resolve("overworld_zenith.png") + ")", owSky >= 200);

            // Arrange the space stack + a slot dim the client will be told about (runtime pool growth).
            String setup = exec("artest space entry-setup 1");
            Matcher dimM = FIRST_DIM.matcher(setup);
            assertTrue("entry-setup must return a slot dim: " + setup, dimM.find());
            slotDim = Integer.parseInt(dimM.group(1));
            exec("artest space load " + slotDim + " skycell");

            // A settled ship in the cell, so the producer has a slotDim -> bodies mapping to broadcast.
            String settle = exec("artest space ledger-settle " + CELL + " " + slotDim);
            assertTrue("ledger-settle must succeed: " + settle, settle.contains("\"ok\":true"));

            // Night, so the cell's fog clear is dark and a white starfield can be seen against it.
            exec("time set 18000");

            enterHigh(slotDim);
            bot().waitTicks(20);

            JsonObject clientWorld = bot().reportWeather();
            assertTrue("client must have a world after the transfer",
                    clientWorld.get("worldReady").getAsBoolean());
            assertEquals("the client must be rendering the slot dim itself", slotDim,
                    clientWorld.get("dim").getAsInt());

            // No body has been registered yet: the client store must be empty for this slot, or the
            // "before" capture is not a before.
            assertTrue("no body may be synced for the slot yet, got: " + clientBodies(),
                    !clientBodies().contains(slotDim + "=[RenderBody{"));

            horizon = capture(slotDim, 200, 90f, 0f, "horizon_ring");
            zenithNoBody = capture(slotDim, 200, 0f, -90f, "zenith_no_body");

            String poi = exec("artest space add-poi " + CELL + " " + BODY_LOCAL + " PLANET 0 7");
            assertTrue("add-poi must register a descend target: " + poi,
                    poi.contains("\"ok\":true") && poi.contains("\"descendTarget\":true"));

            String bodies = null;
            boolean got = false;
            for (int i = 0; i < 24 && !got; i++) {
                bot().waitTicks(5);
                bodies = clientBodies();
                got = bodies.contains(slotDim + "=[") && bodies.contains("dir=0,1000,0");
            }
            assertTrue("the client must have the body before it can be asked to draw it, got: " + bodies,
                    got);

            // Cross-side oracle: the SERVER agrees this ship has exactly one body to draw.
            String serverBodies = exec("artest space bodies");
            assertTrue("the server must report one body for the settled ship: " + serverBodies,
                    serverBodies.contains("\"bodyCount\":1"));

            zenithBody = capture(slotDim, 200, 0f, -90f, "zenith_body");
            nadirBody = capture(slotDim, 200, 0f, 90f, "nadir_body");
        } finally {
            bot().setHudHidden(previousHud);
            bot().setFramebuffer(previousFbo);
            bot().setRenderDistance(previousRenderDistance);
        }

        int w = horizon.getWidth();
        int h = horizon.getHeight();
        assertTrue("captures must be a real frame, got " + w + "x" + h, w >= 320 && h >= 240);

        // ---------------------------------------------------------------- Leg 1: the boundary ring.
        // The ring is a band at the camera's horizon: BOUNDARY_HEIGHT/BOUNDARY_RADIUS subtends about
        // +-3.4 degrees, which on a 70-degree vertical FOV is about +-4.9% of the frame height. Sample
        // well inside that (+-3.5%), and control against a strip near the top the band cannot reach.
        int aboveTop = (int) (0.10 * h);
        int aboveBottom = (int) (0.17 * h);
        int background = modalColour(horizon, 0, w, aboveTop, aboveBottom);
        double ringBand = differsFrom(horizon, 0, w, (int) (0.465 * h), (int) (0.535 * h), background);
        double ringAbove = differsFrom(horizon, 0, w, aboveTop, aboveBottom, background);
        String ringWitness = "band=" + pct(ringBand) + " above=" + pct(ringAbove)
                + " background=" + rgb(background) + " " + describe(horizon)
                + " (" + outDir.resolve("horizon_ring.png") + ")";
        assertTrue("the descent boundary ring must mark the horizon band; " + ringWitness,
                ringBand >= 0.30);
        assertTrue("the ring must be a BAND, not a uniformly tinted frame; " + ringWitness,
                ringBand - ringAbove >= 0.20);

        // ------------------------------------------- Leg 2: the body, against an exact-cancellation control.
        // Same camera, same starfield, same ring - only the body data changed, so any pixel that differs
        // is the billboard. The bottom rows stay excluded as belt-and-braces against any HUD element that
        // outlives the hide (they are all anchored to the bottom of the screen).
        long appeared = diffCount(zenithNoBody, zenithBody, 0, w, 0, (int) (0.62 * h));
        // The billboard: TARGET_HALF_SIZE/BODY_DISTANCE subtends about 6.3 degrees, so a disc of radius
        // about 9% of the frame height - roughly 5900px at 640x480. Require a quarter of that.
        long minBillboard = Math.round(0.25 * Math.PI * Math.pow(0.09 * h, 2));
        String bodyWitness = "changed=" + appeared + "px, required>=" + minBillboard
                + " (" + outDir.resolve("zenith_no_body.png") + " vs "
                + outDir.resolve("zenith_body.png") + ")";
        assertTrue("registering a body must change what the client draws in that direction; " + bodyWitness,
                appeared >= minBillboard);

        // …and the change is where the body is, not everywhere: a far corner box is untouched.
        int boxHalf = (int) (0.045 * h);
        double centreChanged = diffFraction(zenithNoBody, zenithBody,
                w / 2 - boxHalf, w / 2 + boxHalf, h / 2 - boxHalf, h / 2 + boxHalf);
        double cornerChanged = diffFraction(zenithNoBody, zenithBody,
                (int) (0.05 * w), (int) (0.05 * w) + 2 * boxHalf,
                (int) (0.10 * h), (int) (0.10 * h) + 2 * boxHalf);
        String localWitness = "centre=" + pct(centreChanged) + " corner=" + pct(cornerChanged);
        assertTrue("the billboard must cover the aimed centre of the frame; " + localWitness,
                centreChanged >= 0.40);
        assertTrue("nothing outside the billboard may change; " + localWitness, cornerChanged <= 0.05);

        // ------------------------------------------------- Leg 3: aimed at the body vs aimed away from it.
        double aimedVsAway = diffFraction(zenithBody, nadirBody,
                w / 2 - boxHalf, w / 2 + boxHalf, h / 2 - boxHalf, h / 2 + boxHalf);
        double awayCorner = diffFraction(zenithBody, nadirBody,
                (int) (0.05 * w), (int) (0.05 * w) + 2 * boxHalf,
                (int) (0.10 * h), (int) (0.10 * h) + 2 * boxHalf);
        String aimWitness = "aimed-vs-away centre=" + pct(aimedVsAway) + " corner=" + pct(awayCorner)
                + " (" + outDir.resolve("nadir_body.png") + ")";
        assertTrue("aiming at the body must not look like aiming away from it; " + aimWitness,
                aimedVsAway >= 0.40);
        assertTrue("the two aims must differ WHERE THE BODY IS, not across the whole frame; " + aimWitness,
                aimedVsAway - awayCorner >= 0.30);

        // ------------------------------------------------------------------------ Leg 4: the starfield.
        // The nadir frame holds no ring (the band is at the horizon, outside a straight-down FOV) and no
        // body, and the HUD is hidden - so anything that is not the background up here is the sky itself.
        int nadirTop = (int) (0.40 * h);
        int starBackground = modalColour(nadirBody, 0, w, 0, nadirTop);
        long stars = differsCount(nadirBody, 0, w, 0, nadirTop, starBackground);
        assertTrue("an orbit cell must not be an empty void - the sky must carry stars; differing="
                + stars + "px against background " + rgb(starBackground) + " " + describe(nadirBody)
                + " (" + outDir.resolve("nadir_body.png") + ")", stars >= 25);
    }

    // ------------------------------------------------------------------------------------ helpers

    /** Re-seat the falling player, so no capture is taken near the void-death floor. */
    private void enterHigh(int dim) throws Exception {
        seat(dim, 200);
    }

    /** Put the player at a known altitude in {@code dim} through the production transfer path. */
    private void seat(int dim, int y) throws Exception {
        String enter = exec("artest space enter " + botName + " " + dim + " 0.5 " + y + " 0.5");
        assertTrue("space enter must succeed: " + enter, enter.contains("\"ok\":true"));
    }

    /** The client's OWN copy of the render feed, read on the client thread. */
    private String clientBodies() throws Exception {
        JsonObject sf = bot().readStaticField(CLIENT_BODIES_CLASS, "CLIENT_BODIES");
        return sf.get("isNull").getAsBoolean() ? "" : sf.get("value").getAsString();
    }

    /**
     * Aim the real client, let it render, capture, and copy the PNG out of the harness game dir (which
     * is deleted at teardown). Gates on the MEASURED look, never on elapsed ticks.
     */
    private BufferedImage capture(int dim, int y, float yaw, float pitch, String name) throws Exception {
        seat(dim, y);
        bot().waitTicks(5);
        bot().setLook(yaw, pitch);
        boolean aimed = false;
        for (int i = 0; i < 20 && !aimed; i++) {
            bot().waitTicks(2);
            JsonObject state = bot().reportState();
            aimed = Math.abs(state.get("playerPitch").getAsFloat() - pitch) < 0.5f
                    && Math.abs(wrapDegrees(state.get("playerYaw").getAsFloat() - yaw)) < 0.5f;
        }
        assertTrue("the client must actually be looking at " + yaw + "/" + pitch
                + " before the frame is captured, got " + bot().reportState(), aimed);

        // Re-seat AFTER the aim converged, and gate on the CLIENT's own reported altitude. The player is
        // in free fall the whole time, and how far it has fallen is not fixed: aiming takes a variable
        // number of polls. Altitude is not cosmetic here - the overworld sky is drawn against the
        // atmosphere density AT the viewer's height, so an unpinned altitude silently changes the
        // control frame from "thin air, dark sky, stars" to "thick air, bright noon sky". That drift is
        // what made an earlier version of this test pass and fail on identical code.
        seat(dim, y);
        double clientY = Double.NaN;
        boolean seated = false;
        for (int i = 0; i < 20 && !seated; i++) {
            bot().waitTicks(2);
            clientY = bot().reportState().get("playerY").getAsDouble();
            seated = clientY > y - 20 && clientY <= y + 1;
        }
        assertTrue("the client must be back at the capture altitude " + y + " before the frame is taken,"
                + " got " + clientY, seated);
        // Re-hide immediately before the capture: a toast can arrive at any tick, and vanilla draws
        // toasts outside the hideGUI gate, so only a fresh drain guarantees a clean frame.
        bot().setHudHidden(true);
        // Re-assert the sky gate AT capture time and read the field back. Setting it once at the start
        // would leave every later frame trusting that nothing overwrote it, and a closed gate produces
        // a frame that is empty for a reason that has nothing to do with the renderer under test.
        JsonObject gate = bot().setRenderDistance(SKY_RENDER_DISTANCE);
        assertEquals("the sky pass gate must be open when the frame is captured: " + gate,
                SKY_RENDER_DISTANCE, gate.get("renderDistance").getAsInt());
        bot().waitTicks(6);

        JsonObject shot = bot().screenshot(name);
        assertTrue("screenshot must land on disk: " + shot, shot.get("exists").getAsBoolean());
        assertTrue("screenshot must come from the framebuffer, not an undefined back buffer: " + shot,
                shot.get("framebuffer").getAsBoolean());
        Path dst = outDir.resolve(name + ".png");
        Files.copy(Paths.get(shot.get("path").getAsString()), dst, StandardCopyOption.REPLACE_EXISTING);
        BufferedImage image = ImageIO.read(new File(dst.toString()));
        assertTrue("screenshot must decode: " + dst, image != null);
        return image;
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** The most common colour in a region — the frame's own background, whatever colour it happens to be. */
    private static int modalColour(BufferedImage img, int x0, int x1, int y0, int y1) {
        Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
        int best = 0;
        int bestCount = -1;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                Integer prior = counts.get(rgb);
                int next = prior == null ? 1 : prior + 1;
                counts.put(rgb, next);
                if (next > bestCount) {
                    bestCount = next;
                    best = rgb;
                }
            }
        }
        return best;
    }

    private static long differsCount(BufferedImage img, int x0, int x1, int y0, int y1, int reference) {
        long hits = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (channelDelta(img.getRGB(x, y), reference) > DIFF) hits++;
            }
        }
        return hits;
    }

    private static double differsFrom(BufferedImage img, int x0, int x1, int y0, int y1, int reference) {
        long area = (long) (x1 - x0) * (y1 - y0);
        return area == 0 ? 0 : (double) differsCount(img, x0, x1, y0, y1, reference) / area;
    }

    /** Fraction of a whole frame that is not its own most common colour. */
    private static double nonBackgroundFraction(BufferedImage img, int x0, int x1, int y0, int y1) {
        return differsFrom(img, x0, x1, y0, y1, modalColour(img, x0, x1, y0, y1));
    }

    private static int channelDelta(int p, int q) {
        int dr = Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF));
        int dg = Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF));
        int db = Math.abs((p & 0xFF) - (q & 0xFF));
        return Math.max(dr, Math.max(dg, db));
    }

    /** Pixels whose colour differs by more than {@link #DIFF} on any channel. */
    private static long diffCount(BufferedImage a, BufferedImage b, int x0, int x1, int y0, int y1) {
        long hits = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (channelDelta(a.getRGB(x, y), b.getRGB(x, y)) > DIFF) hits++;
            }
        }
        return hits;
    }

    private static double diffFraction(BufferedImage a, BufferedImage b, int x0, int x1, int y0, int y1) {
        long area = (long) (x1 - x0) * (y1 - y0);
        return area == 0 ? 0 : (double) diffCount(a, b, x0, x1, y0, y1) / area;
    }

    /** A one-line summary of a frame, so a failure says what was actually captured. */
    private static String describe(BufferedImage img) {
        int bg = modalColour(img, 0, img.getWidth(), 0, img.getHeight());
        return "[" + img.getWidth() + "x" + img.getHeight() + " modal=" + rgb(bg)
                + " nonBackground=" + pct(differsFrom(img, 0, img.getWidth(), 0, img.getHeight(), bg)) + "]";
    }

    private static String rgb(int colour) {
        return "(" + ((colour >> 16) & 0xFF) + "," + ((colour >> 8) & 0xFF) + "," + (colour & 0xFF) + ")";
    }

    private static String pct(double fraction) {
        return Math.round(fraction * 1000) / 10.0 + "%";
    }
}
