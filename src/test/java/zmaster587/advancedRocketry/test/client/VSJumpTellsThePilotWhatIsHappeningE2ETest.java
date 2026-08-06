package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A jump has to tell the pilot it is happening.
 *
 * <p>The flight itself takes his controls away, shows him no bodies and counts nothing down, so
 * everything he can learn about it arrives through three channels: the chat says he departed and
 * arrived, the HUD names the phase he is in, and the sky draws a corridor instead of the boundary
 * ring belonging to a cell he is no longer in. This test drives one real transit and reads all
 * three from the client.
 *
 * <p><b>Both halves of the sky are counted, and they must move in OPPOSITE directions.</b> A
 * corridor counter alone cannot tell a suppressed ring from a sky renderer that stopped running,
 * and a ring counter alone cannot tell a corridor from a black screen. The control leg before the
 * jump establishes that the ring counter DOES advance in an ordinary cell, which is what turns its
 * standing still in flight into a measurement rather than an absence.
 *
 * <p><b>Not covered here</b>: the drive readout (armed state, capacitor charge, spool countdown).
 * It needs a ship with a hyperdrive aboard, which this fixture does not build; it belongs with the
 * fixture that does.
 *
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class VSJumpTellsThePilotWhatIsHappeningE2ETest extends AbstractClientE2ETest {

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");

    private static final String SKY = "zmaster587.advancedRocketry.client.render.planet.BoundarySky";
    private static final String TUNNEL = "zmaster587.advancedRocketry.client.render.planet.HyperspaceTunnel";

    /** Blocks per tick for the jump. Slow enough that the flight lasts tens of client ticks. */
    private static final long PARK_SPEED = 100_000L;

    /** Above vanilla's sky-pass floor of 4 chunks; the harness otherwise pins the client at 2. */
    private static final int SKY_RENDER_DISTANCE = 8;

    /** What the client's render distance was before this test raised it, so cleanup can put it back. */
    private int previousRenderDistance = -1;

    @Test
    public void aJumpAnnouncesItselfInChatOnTheHudAndInTheSky() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath", serverHasVs());

        // Vanilla runs the sky pass only at renderDistanceChunks >= 4 and the harness pins the client
        // at 2, so without this the sky renderer never executes and every sky reading below would be
        // honestly zero for the wrong reason. The gate is read back off the client's own field rather
        // than assumed. The HUD is deliberately NOT hidden here: it is one of the three subjects.
        com.google.gson.JsonObject rd = bot().setRenderDistance(SKY_RENDER_DISTANCE);
        previousRenderDistance = rd.get("previous").getAsInt();
        assertTrue("the sky pass gate must be open, read back off the client's own field: " + rd,
                rd.get("skyPassEnabled").getAsBoolean());

        exec("artest vs permaload true");

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        seatTheBot(originDim);

        // ── CONTROL, in an ordinary cell ────────────────────────────────────────────────────────
        // The ring is what a cell draws. Establishing that its counter MOVES here is what makes it
        // standing still in flight evidence of suppression rather than of a dead renderer.
        long skyBefore = skyFrames();
        long ringBefore = ringFrames();
        long tunnelBefore = tunnelFrames();
        bot().waitTicks(20);
        // First that the sky renderer runs here at all. Without this the ring assertion below answers
        // two questions with one zero, and "suppressed" is indistinguishable from "never reached".
        assertTrue("this sky renderer must run in an ordinary cell (sky frames " + skyBefore + " -> "
                        + skyFrames() + "); nothing else in this test means anything if it does not",
                skyFrames() > skyBefore);
        assertTrue("the descent-boundary ring must be drawn in an ordinary cell, or its standing still "
                        + "in flight is not evidence of anything (ring frames "
                        + ringBefore + " -> " + ringFrames() + ")",
                ringFrames() > ringBefore);
        assertEquals("the hyperspace corridor must NOT be drawn in an ordinary cell",
                tunnelBefore, tunnelFrames());
        assertTrue("the HUD must not name a jump phase before the jump: " + hud(),
                !hud().contains("HYPERSPACE"));

        // ── THE JUMP ────────────────────────────────────────────────────────────────────────────
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));
        bot().waitTicks(10);

        assertTrue("departing must be said in the pilot's own chat - a jump that starts in silence is "
                        + "indistinguishable from a key that did nothing: " + chat(),
                chat().contains("Jump engaged"));

        // Fly it, reading the client WHILE the ship is still en route.
        //
        // The sky window opens on the first sample where the CLIENT is observably in hyperspace, not
        // on the tick the server was told to depart. Between those two the client is still standing
        // in the cell it left and its sky is still that cell's; counting those frames against "the
        // ring is suppressed in hyperspace" would measure the crossing rather than the corridor.
        long ringAtStart = -1L;
        long tunnelAtStart = -1L;
        int samples = 0;
        String hudInFlight = "";
        long ringInFlight = -1L;
        long tunnelInFlight = -1L;
        String lastTick = "";
        for (int i = 0; i < 120; i++) {
            lastTick = exec("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                break;
            }
            bot().waitTicks(2);
            String hudNow = hud();
            if (ringAtStart < 0) {
                if (!hudNow.contains("HYPERSPACE")) {
                    continue; // not across yet: nothing sampled here is about hyperspace
                }
                ringAtStart = ringFrames();
                tunnelAtStart = tunnelFrames();
            }
            samples++;
            hudInFlight = hudNow;
            ringInFlight = ringFrames();
            tunnelInFlight = tunnelFrames();
        }

        // The instrument must have fired: a jump that arrived instantly proves nothing about the
        // interval, and a green with zero samples would be exactly that.
        // Zero samples now means one of two things and both are fatal to everything below: the jump
        // arrived without ever being observed in flight, or the client never crossed into hyperspace
        // at all. Either way nothing after this line would be measuring the corridor.
        assertTrue("the client was never observed in hyperspace during the flight (0 samples); "
                        + "last tick=" + lastTick + ", last HUD=" + hud(),
                samples > 0);

        assertTrue("the HUD must name the jump phase while the ship is in flight, so a pilot with no "
                        + "controls can tell a flight from a hang - HUD read: " + hudInFlight,
                hudInFlight.contains("HYPERSPACE"));

        assertTrue("the corridor must be drawn in hyperspace (corridor frames " + tunnelAtStart
                        + " -> " + tunnelInFlight + " over " + samples + " samples)",
                tunnelInFlight > tunnelAtStart);

        assertEquals("the descent-boundary ring must NOT be drawn in hyperspace - there is nothing to "
                        + "descend to there, and the same renderer draws it unconditionally for a cell "
                        + "(ring frames over the flight, with the corridor advancing " + tunnelAtStart
                        + " -> " + tunnelInFlight + ")",
                ringAtStart, ringInFlight);

        // ── ARRIVAL ─────────────────────────────────────────────────────────────────────────────
        for (int i = 0; i < 60 && readInt(lastTick, "inTransit") != 0; i++) {
            lastTick = exec("artest space transit-tick");
            bot().waitTicks(2);
        }
        assertEquals("the transit must have finished for the arrival message to be owed: " + lastTick,
                0, readInt(lastTick, "inTransit"));
        bot().waitTicks(20);
        assertTrue("arriving must be said in the pilot's own chat: " + chat(),
                chat().contains("Arrived"));
    }

    // --- arrangement --------------------------------------------------------------------------------

    /** Put the bot in the origin cell and on the ship's pilot seat. */
    private void seatTheBot(int originDim) throws Exception {
        String seat = exec("artest vs find-seat " + originDim + " 1 64 1");
        assertTrue("the pilot seat must be found in the assembled ship (else the test is vacuous): " + seat,
                readBool(seat, "seatFound"));
        int seatX = readInt(seat, "seatX"), seatY = readInt(seat, "seatY"), seatZ = readInt(seat, "seatZ");

        String health = exec("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(health);
        assertTrue("player health must echo the player name: " + health, nameM.find());
        String botName = nameM.group(1);

        int sx = (int) Math.round(readDouble(seat, "shipWorldX"));
        int sy = (int) Math.round(readDouble(seat, "shipWorldY"));
        int sz = (int) Math.round(readDouble(seat, "shipWorldZ"));
        String enter = exec("artest space enter " + botName + " " + originDim + " " + sx + " " + sy + " " + sz);
        assertTrue("space enter into the origin cell must succeed: " + enter, readBool(enter, "ok"));
        bot().waitTicks(20);
        assertEquals("the client must have followed into the transit origin cell",
                originDim, bot().reportWeather().get("dim").getAsInt());

        // Retried with a FRESH dummy spawn: the dummy is glued to the ship's world position only on
        // its first tick, and on a loaded machine its spawn chunk can unload before that tick.
        String mount = "";
        boolean mounted = false;
        for (int attempt = 0; attempt < 5 && !mounted; attempt++) {
            String mountAt = exec("artest vs seat-mount-at " + originDim + " " + seatX + " " + seatY + " " + seatZ);
            assertTrue("seat-mount-at must spawn the seat dummy: " + mountAt, readBool(mountAt, "ok"));
            mount = exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
            mounted = mount.contains("\"mounted\":true");
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        assertTrue("the bot must mount the pilot-seat dummy (5 spawn+mount attempts): " + mount, mounted);
        bot().waitTicks(10);
        assertTrue("the bot must be seated on the ship BEFORE the jump (control): "
                + bot().reportRidingEntity(), bot().reportRidingEntity().get("riding").getAsBoolean());
    }

    // --- helpers (mirror the sibling transit client e2e classes) ------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    /** Poll for a loaded VS ship in {@code dim} (assembly is async; a headless server forces the load). */
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

    private static boolean readBool(String json, String key) {
        return Pattern.compile("\"" + key + "\":true").matcher(json).find();
    }

    // --- observation --------------------------------------------------------------------------------

    /** The Free Flight HUD text as the client last rendered it. */
    private String hud() throws Exception {
        return bot().readStaticField("zmaster587.advancedRocketry.event.RocketEventHandler",
                "lastFreeFlightHud").get("value").getAsString();
    }

    private long ringFrames() throws Exception {
        return readCounter(SKY, "ringFramesDrawn");
    }

    /** Frames on which this sky renderer ran at all, whatever it decided to draw. */
    private long skyFrames() throws Exception {
        return readCounter(SKY, "skyFramesDrawn");
    }

    /** A client-side counter, read as text: the bridge hands values back as strings. */
    private long readCounter(String className, String field) throws Exception {
        com.google.gson.JsonObject sf = bot().readStaticField(className, field);
        assertTrue("the client must expose " + className + "#" + field + ": " + sf,
                !sf.get("isNull").getAsBoolean());
        return Long.parseLong(sf.get("value").getAsString().trim());
    }

    private long tunnelFrames() throws Exception {
        return readCounter(TUNNEL, "framesDrawn");
    }

    /** The client's recent chat history, as one string. Deep enough to survive the harness's own
     *  per-command marker lines, which are themselves chat. */
    private String chat() throws Exception {
        return bot().reportChat(200).toString();
    }

    @After
    public void cleanup() {
        try {
            if (previousRenderDistance >= 0) {
                bot().setRenderDistance(previousRenderDistance);
            }
            if (serverHasVs()) {
                exec("artest player dismount");
                exec("artest vs permaload false");
            }
        } catch (Exception ignored) {
            // A cleanup failure must not mask the assertion that already failed.
        }
    }
}
