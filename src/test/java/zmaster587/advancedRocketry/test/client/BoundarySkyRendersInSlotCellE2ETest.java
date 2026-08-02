package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import zmaster587.advancedRocketry.client.render.planet.ApparentSize;
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
 * bodies) and act as the cross-side oracle for what SHOULD be drawn, and where.</p>
 *
 * <h2>The subject is a real system, at real distances, on real bearings</h2>
 * The defect this test exists for was reported from a cell holding SIX bodies between 3 000 and 60 000
 * blocks on six different bearings, and an earlier version of this test proved only that ONE synthetic
 * body 1 000 blocks straight up could be drawn — a subject that could not exhibit the report. The bodies
 * below are the measured live set: five descend-target moons and a gas giant that is not one, at the live
 * distances, spread so that no two are within 45 degrees of each other. Two of them are then aimed at
 * INDIVIDUALLY, using the bearing the SERVER reports for them rather than one this test computed for
 * itself, because "the client drew something in the sky" and "the client drew the body where the server
 * put it" are different claims and only the second one lets a pilot fly at a moon.
 *
 * <h2>Which world the pilot is in is the SERVER's answer, not this test's</h2>
 * A cell is bound to a slot world by materializing it, and that binding is what the render feed is keyed
 * with. The slot the binding lands in is therefore read back off the settle, never chosen here: a number
 * picked by the test is a guess, and a guess that happens to agree would still pass on a build that keyed
 * the feed to the wrong world. (An earlier version guessed, put the pilot in a world its ship's cell was
 * not bound to, and reported the resulting blank sky as a production defect.)
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
 *   <li><b>Each aimed body, by exact cancellation</b> — two captures from the IDENTICAL camera
 *       direction, one before any body exists and one after all six do. The sky here is camera-centred,
 *       so every other pixel (stars, ring) is bit-identical between them and the differing pixels are
 *       the billboards and nothing else.</li>
 *   <li><b>The same aim vs an empty bearing</b> — a direction more than 100 degrees from every body in
 *       the cell (chosen by geometry, not by hope) must NOT gain a billboard when the bodies are
 *       registered, and must not look like the aimed frames do.</li>
 *   <li><b>Starfield</b> — pixels differing from the background in the upper part of the empty-bearing
 *       frame, which holds no ring and no body, i.e. the cell is not an empty void.</li>
 * </ol>
 *
 * <h2>Setup shortcuts, and what human action each replaces</h2>
 * A player reaches this view by flying a ship into space; the arrival settles it in the ledger and the
 * cell's contents come from the generated universe. Here {@code ledger-settle} injects the settled entry
 * and {@code add-poi} registers the bodies in the real universe registry. Both change only WHICH data the
 * producer has, not which object, frame or lifecycle stage the renderer reads — the renderer is fed
 * through the identical production broadcast — so the rendering path under test is the real one.
 */
public class BoundarySkyRendersInSlotCellE2ETest extends AbstractClientE2ETest {

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");
    /** The slot the settle actually bound the cell to — the one place that decides it. */
    private static final Pattern BOUND_DIM = Pattern.compile("\"slotDim\":(-?\\d+)");
    private static final String CLIENT_BODIES_CLASS =
            "zmaster587.advancedRocketry.network.PacketSystemBodiesSync";
    private static final String SKY_CLASS =
            "zmaster587.advancedRocketry.client.render.planet.BoundarySky";

    /** Cell the ship settles in. sy=5000 dodges the fallback stars (all at sy=sz=0). */
    private static final String CELL = "0 5000 0";

    /**
     * The cell's contents: {@code localX localY localZ kind dimId}. The ship settles at the cell CENTRE,
     * so each body's local offset IS the ship&rarr;body direction the producer sends.
     *
     * <p>Distances and count are the live set (2 961 / 28 275 / 34 985 / 39 050 / 54 713 / 59 255 blocks);
     * the bearings are spread so that the closest pair is 45 degrees apart and every body sits at least
     * 20 degrees off the horizon, i.e. clear of the boundary ring band. A gas giant with no dimension of
     * its own is included because it is NOT a descend target and takes the other billboard size — the
     * feed carries both kinds and so must the subject.</p>
     */
    private static final String[][] SYSTEM = {
            {"768", "-1072", "-2652", "MOON", "0"},           // ~2 961 - the nearest descend target
            {"-23443", "11940", "10363", "MOON", "0"},        // ~28 275
            {"-30108", "-13988", "11037", "MOON", "0"},       // ~34 985
            {"7644", "34614", "-16382", "GAS_GIANT", "-1"},   // ~39 050 - not a descend target
            {"-42912", "-23517", "-24475", "MOON", "0"},      // ~54 713
            {"-39818", "28442", "-33418", "MOON", "0"},       // ~59 255
    };

    /** The nearest descend target: the body a pilot has to find and fly at to descend at all. */
    private static final int NEAREST = 0;
    /** The gas giant: a non-descend body, which takes the other tint and no texture of its own. */
    private static final int GIANT = 3;
    /**
     * The FARTHEST body, and deliberately the same KIND as {@link #NEAREST}: a moon of dim 0, so the
     * two are drawn with the same texture and the same tint and differ in nothing but distance.
     *
     * <p>The obvious pairing — nearest against the gas giant — cannot measure size at all. The giant
     * has no dimension of its own, so it is drawn as a flat untextured quad in which EVERY pixel
     * differs from the sky, while a textured Earth billboard has interior texels that match a dark
     * sky and do not count. Measured: the giant at 39 050 blocks changed 9 702 px and the moon at
     * 2 962 blocks changed 7 684 px, i.e. an area comparison there measures FILL and reports the
     * size relation backwards.</p>
     */
    private static final int FARTHEST = 5;

    /**
     * A bearing with nothing in it: more than 100 degrees from every body above, and 22 degrees off the
     * horizon so the boundary ring cannot reach the middle of the frame. This is the "aimed away"
     * control, and it has to be derived from the same geometry as the bodies — with six of them in the
     * sky, the antipode of one body can easily be another.
     */
    private static final float EMPTY_YAW = -48f;
    private static final float EMPTY_PITCH = 22f;

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

    /** Capture altitude inside the slot cell (well clear of the void-death floor). */
    private static final int CELL_CAPTURE_Y = 200;

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
    public void aPilotInASlotCellSeesTheRingTheBodiesAndStars() throws Exception {
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
        BufferedImage[] before = new BufferedImage[SYSTEM.length];
        BufferedImage[] after = new BufferedImage[SYSTEM.length];
        BufferedImage emptyBefore;
        BufferedImage emptyAfter;
        int slotDim;
        int labelsWithNoBodies = -1;
        int labelsWithBodies = -1;
        try {
            // --- Control FIRST: is the sky pass running at all? Above the clouds so nothing but sky is
            // in frame.
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

            // Arrange the space stack, then settle a ship in the cell. The settle MATERIALIZES the cell,
            // and the slot it lands in is the answer this test uses everywhere below: the feed is keyed
            // with it and the pilot is put into it.
            String setup = exec("artest space entry-setup 1");
            assertTrue("entry-setup must install the stack: " + setup, setup.contains("\"ok\":true"));
            String settle = exec("artest space ledger-settle " + CELL + " 0");
            assertTrue("ledger-settle must succeed: " + settle, settle.contains("\"ok\":true"));
            Matcher boundM = BOUND_DIM.matcher(settle);
            assertTrue("the settle must report which slot the cell was bound to: " + settle, boundM.find());
            slotDim = Integer.parseInt(boundM.group(1));

            // Night, so the cell's fog clear is dark and a white starfield can be seen against it.
            exec("time set 18000");

            seat(slotDim, CELL_CAPTURE_Y);
            bot().waitTicks(20);

            JsonObject clientWorld = bot().reportWeather();
            assertTrue("client must have a world after the transfer",
                    clientWorld.get("worldReady").getAsBoolean());
            assertEquals("the client must be rendering the very world the ship's cell is bound to",
                    slotDim, clientWorld.get("dim").getAsInt());

            // No body has been registered yet: the client store must hold none for this slot, or the
            // "before" captures are not befores.
            assertTrue("no body may be synced for the slot yet, got: " + clientBodies(),
                    !clientBodies().contains(slotDim + "=[RenderBody{"));

            horizon = capture(slotDim, CELL_CAPTURE_Y, 90f, 0f, "horizon_ring");
            // How many body labels the client's LAST FRAME wrote, with no body in the cell yet. The
            // control for the label leg: a counter that is non-zero here is counting something other
            // than this cell's bodies.
            labelsWithNoBodies = labelsDrawn();
            // A before-frame on each body's bearing, plus one on the empty bearing. Only the two aimed
            // bodies are measured, but capturing all of them costs one frame each and makes a later
            // "which body failed" question answerable from the artefacts.
            for (int i : new int[] {NEAREST, GIANT, FARTHEST}) {
                float[] aim = aimAt(local(i, 0), local(i, 1), local(i, 2));
                before[i] = capture(slotDim, CELL_CAPTURE_Y, aim[0], aim[1], "before_body" + i);
            }
            emptyBefore = capture(slotDim, CELL_CAPTURE_Y, EMPTY_YAW, EMPTY_PITCH, "before_empty");

            for (String[] body : SYSTEM) {
                String poi = exec("artest space add-poi " + CELL + " " + body[0] + " " + body[1] + " "
                        + body[2] + " " + body[3] + " " + body[4] + " 7");
                assertTrue("add-poi must register the body: " + poi, poi.contains("\"ok\":true"));
            }

            // The whole set has to reach the client's own store before any frame can be blamed on the
            // renderer. Gated on the COUNT, so a partially-arrived feed is not read as a drawing bug.
            String bodies = null;
            boolean got = false;
            for (int i = 0; i < 24 && !got; i++) {
                bot().waitTicks(5);
                bodies = clientBodies();
                got = countBodies(bodies, slotDim) == SYSTEM.length;
            }
            assertTrue("the client must have all " + SYSTEM.length + " bodies of the cell before it can"
                    + " be asked to draw them, got: " + bodies, got);

            // Cross-side oracle: the SERVER's own feed, for this slot dim, carries exactly these bodies
            // on exactly these bearings. Everything below aims with the server's numbers.
            String feed = exec("artest space bodies");
            assertEquals("the server feed must carry the whole system for this slot dim: " + feed,
                    SYSTEM.length, feedBodyCount(feed, slotDim));
            for (int i = 0; i < SYSTEM.length; i++) {
                String dir = "\"dir\":[" + SYSTEM[i][0] + "," + SYSTEM[i][1] + "," + SYSTEM[i][2] + "]";
                assertTrue("the server must report body " + i + " on its own bearing " + dir
                        + " (a body drawn on a bearing nobody sent is a body a pilot cannot fly at): "
                        + feed, feed.contains(dir));
                assertTrue("and the client must hold the identical direction: " + bodies,
                        bodies.contains("dir=" + SYSTEM[i][0] + "," + SYSTEM[i][1] + "," + SYSTEM[i][2]));
            }

            for (int i : new int[] {NEAREST, GIANT, FARTHEST}) {
                float[] aim = aimAt(local(i, 0), local(i, 1), local(i, 2));
                after[i] = capture(slotDim, CELL_CAPTURE_Y, aim[0], aim[1], "after_body" + i);
            }
            emptyAfter = capture(slotDim, CELL_CAPTURE_Y, EMPTY_YAW, EMPTY_PITCH, "after_empty");
            labelsWithBodies = labelsDrawn();
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

        // ------------------------------------------- Leg 2: each aimed body, by exact cancellation.
        // Same camera, same starfield, same ring - only the body data changed, so any pixel that differs
        // is a billboard. The bottom rows stay excluded as belt-and-braces against any HUD element that
        // outlives the hide (they are all anchored to the bottom of the screen).
        // The billboard's angular radius is half/BODY_DISTANCE, i.e. 6.3 degrees for a descend target
        // (half 10) and 3.8 for a plain body (half 6); on a 70-degree vertical FOV that is 9.1% and 5.4%
        // of the frame height. Each aim therefore has its own expected disc and its own sample box.
        // The billboard's angular radius is halfSizeFor(distance)/BODY_DISTANCE, and since
        // CON-C14-16 made the half-size a function of distance, the expected disc is derived from
        // that law rather than from a constant. It is used only to SIZE the sample box - the
        // assertion is still "a disc was drawn at the bearing the server reported", which a build
        // that drew nothing fails whatever the box is.
        assertBodyDrawn(NEAREST, before[NEAREST], after[NEAREST], emptyAfter);
        assertBodyDrawn(GIANT, before[GIANT], after[GIANT], emptyAfter);

        // ------------------- Leg 2b: apparent size FALLS with distance (C14 CON-C14-16), in pixels.
        // The player-visible half of the clause: two bodies a pilot can see in the same cell, one
        // twenty times further away than the other, must not look the same size. Before this every
        // body was drawn at one of two fixed sizes, so a moon at 3 km and one at 59 km were
        // indistinguishable and "the planet is crawling away" was not something the sky could show.
        //
        // The two are the same KIND on purpose (see FARTHEST): same texture, same tint, so the only
        // thing that can differ is the disc. Counted inside a box sized on the NEARER body's own
        // expected disc and centred on the aim, which the far body's smaller disc cannot fill and
        // which no other body reaches - the fixture spreads them 45 degrees apart at least.
        int sizeBox = (int) Math.ceil(discRadiusOf(NEAREST) * h);
        long nearArea = diffCount(before[NEAREST], after[NEAREST],
                w / 2 - sizeBox, w / 2 + sizeBox, h / 2 - sizeBox, h / 2 + sizeBox);
        long farArea = diffCount(before[FARTHEST], after[FARTHEST],
                w / 2 - sizeBox, w / 2 + sizeBox, h / 2 - sizeBox, h / 2 + sizeBox);
        assertTrue("the nearer body must be drawn LARGER than the far one; near("
                        + Math.round(distanceOf(NEAREST)) + " blocks)=" + nearArea + "px far("
                        + Math.round(distanceOf(FARTHEST)) + " blocks)=" + farArea + "px in a "
                        + (2 * sizeBox) + "px box ("
                        + outDir.resolve("after_body" + NEAREST + ".png") + " vs "
                        + outDir.resolve("after_body" + FARTHEST + ".png") + ")",
                nearArea > farArea);

        // ------------------------------------------------- Leg 3: the empty bearing gains nothing.
        // Every body is more than 100 degrees away from this aim, so registering all six must leave this
        // frame alone. Without this the "something changed" legs above could be satisfied by a build that
        // smeared a billboard across the whole sky.
        int emptyHalf = (int) (0.045 * h);
        double emptyCentre = diffFraction(emptyBefore, emptyAfter,
                w / 2 - emptyHalf, w / 2 + emptyHalf, h / 2 - emptyHalf, h / 2 + emptyHalf);
        double emptyFrame = diffFraction(emptyBefore, emptyAfter, 0, w, 0, (int) (0.62 * h));
        assertTrue("a bearing with no body within 100 degrees must not gain one; centre="
                + pct(emptyCentre) + " frame=" + pct(emptyFrame)
                + " (" + outDir.resolve("before_empty.png") + " vs "
                + outDir.resolve("after_empty.png") + ")", emptyCentre <= 0.05);

        // ------------------------------------------------------------------------ Leg 4: the starfield.
        // The empty-bearing frame holds no body, and at 22 degrees of pitch the ring band is well below
        // the upper part of it - so anything that is not the background up here is the sky itself.
        int starTop = (int) (0.40 * h);
        int starBackground = modalColour(emptyAfter, 0, w, 0, starTop);
        long stars = differsCount(emptyAfter, 0, w, 0, starTop, starBackground);
        assertTrue("an orbit cell must not be an empty void - the sky must carry stars; differing="
                + stars + "px against background " + rgb(starBackground) + " " + describe(emptyAfter)
                + " (" + outDir.resolve("after_empty.png") + ")", stars >= 25);

        // ------------------------------------------------- Leg 5: every body says what it is (C14
        // CON-C14-17). Read off the CLIENT's own per-frame counter rather than off pixels, because
        // "is that text or is it a star" is not a question a pixel count can answer - and because
        // the clause is "one label per body", which a count states exactly. The before-sample is the
        // control: with no body in the cell the counter must be zero, so a non-zero after-sample is
        // attributable to the bodies and to nothing else.
        assertEquals("no body is registered yet, so the sky can have labelled nothing",
                0, labelsWithNoBodies);
        assertEquals("the sky must label every body it draws, by default and with no configuration",
                SYSTEM.length, labelsWithBodies);
    }

    /** How many body labels the client's last rendered frame wrote. */
    private int labelsDrawn() throws Exception {
        JsonObject sf = bot().readStaticField(SKY_CLASS, "labelsDrawnLastFrame");
        assertTrue("the sky renderer must expose its per-frame label count: " + sf,
                !sf.get("isNull").getAsBoolean());
        return Integer.parseInt(sf.get("value").getAsString().trim());
    }

    // ------------------------------------------------------------------------------------ helpers

    /**
     * One body's three legs: it appeared where the server said it is, it covers the aimed centre, and
     * aiming at it does not look like aiming at empty sky.
     *
     * @param discRadius   the billboard's expected radius as a fraction of the frame height
     * @param sampleRadius half-size of the centre sample box, inside that disc
     */
    private void assertBodyDrawn(int index, BufferedImage beforeFrame, BufferedImage afterFrame,
                                 BufferedImage emptyFrame) {
        int w = afterFrame.getWidth();
        int h = afterFrame.getHeight();
        double discRadius = discRadiusOf(index);
        double sampleRadius = discRadius / 2.0;
        float[] aim = aimAt(local(index, 0), local(index, 1), local(index, 2));
        String where = "body " + index + " (" + SYSTEM[index][3] + " at "
                + Math.round(Math.sqrt(
                        (double) local(index, 0) * local(index, 0)
                                + (double) local(index, 1) * local(index, 1)
                                + (double) local(index, 2) * local(index, 2)))
                + " blocks, aim yaw=" + aim[0] + " pitch=" + aim[1] + ")";

        long appeared = diffCount(beforeFrame, afterFrame, 0, w, 0, (int) (0.62 * h));
        long minBillboard = Math.round(0.25 * Math.PI * Math.pow(discRadius * h, 2));
        assertTrue("registering the cell's bodies must change what the client draws towards " + where
                + "; changed=" + appeared + "px, required>=" + minBillboard + " ("
                + outDir.resolve("before_body" + index + ".png") + " vs "
                + outDir.resolve("after_body" + index + ".png") + ")", appeared >= minBillboard);

        int box = (int) (sampleRadius * h);
        double centreChanged = diffFraction(beforeFrame, afterFrame,
                w / 2 - box, w / 2 + box, h / 2 - box, h / 2 + box);
        assertTrue("the billboard must cover the frame centre when the camera is on the bearing the"
                + " SERVER reports for " + where + "; centre=" + pct(centreChanged),
                centreChanged >= 0.40);

        double vsEmpty = diffFraction(afterFrame, emptyFrame,
                w / 2 - box, w / 2 + box, h / 2 - box, h / 2 + box);
        assertTrue("aiming at " + where + " must not look like aiming at empty sky; centre difference="
                + pct(vsEmpty), vsEmpty >= 0.40);
    }

    /**
     * The billboard's expected radius as a fraction of the frame HEIGHT: its half-size (which C14
     * CON-C14-16 makes a function of distance) subtends {@code atan(half / BODY_DISTANCE)} on a
     * 70-degree vertical FOV. Used only to SIZE sample boxes; every assertion is still about what
     * the client actually drew, so a build that drew nothing fails whatever the box is.
     */
    private static double discRadiusOf(int index) {
        return Math.toDegrees(Math.atan(ApparentSize.halfSizeFor(distanceOf(index)) / 90.0)) / 70.0;
    }

    /** How far the configured body {@code index} is from the settled ship, in blocks. */
    private static double distanceOf(int index) {
        double dx = local(index, 0);
        double dy = local(index, 1);
        double dz = local(index, 2);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** One component of a configured body's local offset (its direction from the settled ship). */
    private static long local(int index, int axis) {
        return Long.parseLong(SYSTEM[index][axis]);
    }

    /**
     * The client look ({@code yaw}, {@code pitch}) that points a camera along {@code (dx,dy,dz)}.
     * Minecraft's view vector is {@code (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))}, which
     * inverts to this; {@code BoundarySky} places a billboard along the raw direction in world axes, so
     * the two meet only if both conversions are right.
     */
    private static float[] aimAt(long dx, long dy, long dz) {
        double len = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-(double) dx, (double) dz));
        float pitch = (float) -Math.toDegrees(Math.asin(dy / len));
        return new float[] {yaw, pitch};
    }

    /** How many bodies the CLIENT store holds for {@code slotDim}. */
    private static int countBodies(String clientBodies, int slotDim) {
        int start = clientBodies.indexOf(slotDim + "=[");
        if (start < 0) {
            return 0;
        }
        int end = clientBodies.indexOf(']', start);
        String list = end < 0 ? clientBodies.substring(start) : clientBodies.substring(start, end);
        int count = 0;
        int at = list.indexOf("RenderBody{");
        while (at >= 0) {
            count++;
            at = list.indexOf("RenderBody{", at + 1);
        }
        return count;
    }

    /** How many bodies the SERVER's own feed carries for {@code slotDim}; -1 when the dim is absent. */
    private static int feedBodyCount(String json, int slotDim) {
        Matcher m = Pattern.compile("\\{\"slotDim\":" + slotDim + ",\"bodyCount\":(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
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
