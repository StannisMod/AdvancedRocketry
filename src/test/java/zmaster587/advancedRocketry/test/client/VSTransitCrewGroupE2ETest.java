package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hyperspace transit with a crew member aboard: four scenarios that used to be four classes and four
 * client boots, now one boot.
 *
 * <h2>Why this cluster shares safely</h2>
 *
 * <p>Every scenario here arranges itself through {@code artest space transit-setup-piloted} /
 * {@code -empty}, and that probe allocates a <b>fresh origin pool cell per call</b>. So each
 * scenario works in a dimension of its own: the whole-dimension ship counts these bodies use as
 * assembly gates ({@code vs ship-count-all &lt;originDim&gt;}) are scoped by construction, not by
 * luck, and none of them needed narrowing. That is the opposite of the ground-fixture cluster, where
 * every scenario shares dim 0 and the gates had to be rewritten.</p>
 *
 * <p>The three things the scenarios DO leave behind are closed by
 * {@link AbstractSharedVsClientE2ETest}: a still-riding player, {@code vs permaload} (each scenario
 * switches it on for itself), and the flight computer's static command channels. Their original
 * {@code @After cleanup()} methods did the first two by hand and did not check them; the shared
 * reset asserts both, so those methods are dropped rather than carried over.</p>
 *
 * <p>{@code bot().setRenderDistance} is the one channel that belongs to this family alone — the
 * sky-observing scenario widens it — so it is restored here, in the reset, and not in an
 * {@code @After} (which JUnit runs BEFORE the failure watcher, destroying the journal a red needs).
 * It is static because JUnit builds a fresh test instance per method while the client JVM keeps the
 * setting.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSTransitCrewGroupE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-transit-crew";
    }

    /**
     * The render distance the sky scenario widened, or -1 when nothing has touched it. Static: the
     * value lives in the client JVM, which outlives every test instance in this class.
     */
    private static int previousRenderDistance = -1;

    @Override
    protected void resetFamilyStateBeforeTeleport() throws Exception {
        super.resetFamilyStateBeforeTeleport();
        if (previousRenderDistance >= 0) {
            bot().setRenderDistance(previousRenderDistance);
            previousRenderDistance = -1;
        }
    }

    // ---- shared arrangement helpers (byte-identical in all four sources) ----

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");

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

    /** Blocks per tick for the jump. Slow enough that the ship stays parked for tens of ticks. */
private static final long PARK_SPEED = 100_000L;


    // ---- migrated: VSShipTransitCrewE2ETest ----

    @Test
    public void aSeatedCrewMemberSurvivesAHyperspaceTransitStillRiding() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)", serverHasVs());

        // Headless: pin ships loaded so a freshly assembled ship does not auto-unload between probe calls.
        exec("artest vs permaload true");

        // Build a PILOTED tier-2 ship in a fresh transit ORIGIN pool cell. The VS assembly is ASYNC (queued on
        // the physics thread), so the ship + its seat are not queryable synchronously - poll for them below.
        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");

        // Wait for the async assembly to load the ship in the origin cell (count-all -> load-ships -> count).
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        // Now the ship is up: locate the pilot seat's subspace pos + the ship's world pos.
        String seat = exec("artest vs find-seat " + originDim + " 1 64 1");
        // CONTROL (witness sensitivity): the seat must actually have been built and located, or the whole
        // "still riding after the jump" observation is vacuous.
        assertTrue("the pilot seat must be found in the assembled ship (else the test is vacuous): " + seat,
                readBool(seat, "seatFound"));
        int seatX = readInt(seat, "seatX"), seatY = readInt(seat, "seatY"), seatZ = readInt(seat, "seatZ");
        double shipWorldX = readDouble(seat, "shipWorldX");
        double shipWorldY = readDouble(seat, "shipWorldY");
        double shipWorldZ = readDouble(seat, "shipWorldZ");

        // The bot's username (server read, arrange-only).
        String health = exec("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(health);
        assertTrue("player health must echo the player name: " + health, nameM.find());
        String botName = nameM.group(1);

        // Move the REAL client into the origin cell, at the ship's world position (round the doubles to the
        // ints the command takes). The client must FOLLOW into the origin dim.
        int sx = (int) Math.round(shipWorldX), sy = (int) Math.round(shipWorldY), sz = (int) Math.round(shipWorldZ);
        String enter = exec("artest space enter " + botName + " " + originDim + " " + sx + " " + sy + " " + sz);
        assertTrue("space enter into the origin cell must succeed: " + enter, readBool(enter, "ok"));
        bot().waitTicks(20);
        assertEquals("the client must have followed into the transit origin cell",
                originDim, bot().reportWeather().get("dim").getAsInt());

        // Seat the bot on the ship's pilot-seat dummy (bound to the seat's subspace pos located at
        // setup). Retried with a FRESH spawn on failure: the dummy is spawned at the shipyard's
        // subspace coordinates and glued to the ship's world position only on its first tick - on
        // a loaded machine the spawn chunk can unload before that tick and the returned entity id
        // resolves to nothing ("entity not found"). A fresh spawn each retry is what recovers.
        String mountAt = "", mount = "";
        boolean mounted = false;
        for (int attempt = 0; attempt < 5 && !mounted; attempt++) {
            mountAt = exec("artest vs seat-mount-at " + originDim + " " + seatX + " " + seatY + " " + seatZ);
            assertTrue("seat-mount-at must spawn the seat dummy: " + mountAt, readBool(mountAt, "ok"));
            int dummyId = readInt(mountAt, "dummyId");
            mount = exec("artest player mount-entity " + dummyId);
            mounted = mount.contains("\"mounted\":true");
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        assertTrue("the bot must mount the pilot-seat dummy (5 spawn+mount attempts): " + mount,
                mounted);
        bot().waitTicks(10);

        // CONTROL: the client must confirm it IS riding BEFORE the transit — so "riding after" is meaningful.
        assertTrue("the bot must be seated on the ship BEFORE the jump (control): "
                + bot().reportRidingEntity(), bot().reportRidingEntity().get("riding").getAsBoolean());

        // Depart into hyperspace at the ship anchor (1,64,1 from transit-setup-piloted).
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1");
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // Advance the jump: tick until it arrives (inTransit == 0), capturing the target cell's slot dim.
        int targetDim = -1;
        String lastTick = "";
        for (int i = 0; i < 80 && targetDim < 0; i++) {
            lastTick = exec("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                targetDim = readInt(lastTick, "targetDim");
                break;
            }
            bot().waitTicks(2);
        }
        assertTrue("the jump never completed (still in transit); last tick=" + lastTick, targetDim >= 0);

        // The crew reseat is retry-based and completes a few ticks AFTER inTransit hits 0. Keep ticking (to
        // drive the retries) and observe the CLIENT until it is riding again in the target dim, bounded.
        boolean crewSurvived = false;
        for (int i = 0; i < 60 && !crewSurvived; i++) {
            exec("artest space transit-tick");
            bot().waitTicks(2);
            crewSurvived = bot().reportRidingEntity().get("riding").getAsBoolean()
                    && bot().reportWeather().get("dim").getAsInt() == targetDim;
        }

        // ACCEPTANCE (client oracle): the client itself must render the crew member STILL RIDING the ship's
        // seat, in the TARGET cell — the reseat carried it across dims and re-mounted it.
        JsonObject riding = bot().reportRidingEntity();
        assertTrue("the crew member must survive the jump still riding, on the CLIENT: " + riding
                + " (targetDim=" + targetDim + ", clientDim=" + bot().reportWeather().get("dim").getAsInt() + ")",
                riding.get("riding").getAsBoolean());
        assertTrue("the re-mounted entity must be the ship's seat dummy: " + riding,
                riding.get("entityClass").getAsString().endsWith("EntityDummy"));
        assertEquals("the client must have followed the crew into the target cell",
                targetDim, bot().reportWeather().get("dim").getAsInt());
    }


    // ---- migrated: VSCrewRidesItsShipThroughHyperspaceE2ETest ----

    @Test
    public void aSeatedCrewMemberIsAboardHisShipInHyperspaceWhileItIsStillFlying() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)", serverHasVs());

        exec("artest vs permaload true");

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");

        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        String seat = exec("artest vs find-seat " + originDim + " 1 64 1");
        // CONTROL (witness sensitivity): without a located seat there is nothing to sit on and every
        // later "he is aboard" reading is vacuous.
        assertTrue("the pilot seat must be found in the assembled ship (else the test is vacuous): " + seat,
                readBool(seat, "seatFound"));
        int seatX = readInt(seat, "seatX"), seatY = readInt(seat, "seatY"), seatZ = readInt(seat, "seatZ");
        double shipWorldX = readDouble(seat, "shipWorldX");
        double shipWorldY = readDouble(seat, "shipWorldY");
        double shipWorldZ = readDouble(seat, "shipWorldZ");

        String health = exec("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(health);
        assertTrue("player health must echo the player name: " + health, nameM.find());
        String botName = nameM.group(1);

        int sx = (int) Math.round(shipWorldX), sy = (int) Math.round(shipWorldY), sz = (int) Math.round(shipWorldZ);
        String enter = exec("artest space enter " + botName + " " + originDim + " " + sx + " " + sy + " " + sz);
        assertTrue("space enter into the origin cell must succeed: " + enter, readBool(enter, "ok"));
        bot().waitTicks(20);
        assertEquals("the client must have followed into the transit origin cell",
                originDim, bot().reportWeather().get("dim").getAsInt());

        // Seat the bot. Retried with a FRESH dummy spawn: the dummy is glued to the ship's world
        // position only on its first tick, and on a loaded machine its spawn chunk can unload before
        // that tick, leaving the returned entity id resolving to nothing.
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

        // CONTROL: the client says it IS riding, in the ORIGIN cell, before the jump — so both of the
        // mid-flight readings below can move.
        assertTrue("the bot must be seated on the ship BEFORE the jump (control): "
                + bot().reportRidingEntity(), bot().reportRidingEntity().get("riding").getAsBoolean());
        assertEquals("the bot must be in the origin cell before the jump (control)",
                originDim, bot().reportWeather().get("dim").getAsInt());

        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // Fly it, sampling the CLIENT while the ship is still en route.
        int samples = 0, hyperDim = -1, crewDim = -1;
        int clientDimInFlight = Integer.MIN_VALUE;
        boolean ridingInFlight = false;
        String lastTick = "";
        for (int i = 0; i < 120; i++) {
            lastTick = exec("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                break; // arrived - everything after this point is the far end, which is another test's
            }
            bot().waitTicks(2);
            samples++;
            if (samples == 1) {
                // The FIRST in-flight sample is the one that matters: it is the earliest moment the
                // crew could have been left behind, and later samples would let a late-arriving fix
                // hide an initial ejection.
                hyperDim = readInt(lastTick, "hyperDim");
                crewDim = readInt(lastTick, "crewDim");
                clientDimInFlight = bot().reportWeather().get("dim").getAsInt();
                ridingInFlight = bot().reportRidingEntity().get("riding").getAsBoolean();
            }
        }

        // The instrument must have fired: a jump that arrived instantly proves nothing about the
        // interval, and a green with zero samples would be exactly that.
        assertTrue("the jump was never observed mid-flight (0 in-flight samples); last tick=" + lastTick,
                samples > 0);

        // Arrangement oracle: the subsystem's own answer for where this crew belongs is the shared
        // hyperspace world. If these two disagree the fixture, not production, is what failed.
        assertEquals("mid-flight the subsystem must place this crew in the hyperspace world"
                + " (crewDim vs hyperDim); tick=" + lastTick, hyperDim, crewDim);

        // THE CONTRACT: the crew travels with its ship. The client's own dimension, in flight, is the
        // world the ship is parked in - not the cell it departed from.
        assertEquals("the crew member must be in the hyperspace world while his ship is flying, as HIS"
                + " OWN CLIENT sees it - he was in dim " + clientDimInFlight + " (origin cell was "
                + originDim + ", hyperspace is " + hyperDim + "), after " + samples + " in-flight samples",
                hyperDim, clientDimInFlight);

        // ...and a jump does not take the pilot out of his seat for the duration of the flight.
        assertTrue("the crew member must still be riding his seat in flight, on the CLIENT: "
                + bot().reportRidingEntity(), ridingInFlight);
    }


    // ---- migrated: VSCrewedArrivalReseatsWithNobodyToLoadTheShipE2ETest ----

    /** Ticks of transit driving after arrival. The re-seat is retry-based; a healthy one takes a few. */
private static final int RESEAT_POLLS = 90;

    /**
     * Run a probe and return ONLY its JSON envelope. The server writes its own log lines to the same
     * stream, so joining every returned line hands the assertions whatever unrelated line happened to
     * land in the window — including one that satisfies them.
     */
private String execEnvelope(String cmd) throws Exception {
        String envelope = "";
        for (String line : serverClient().execute(cmd)) {
            int brace = line.indexOf('{');
            if (brace >= 0 && line.endsWith("}")) {
                envelope = line.substring(brace);
            }
        }
        return envelope;
    }

    /** ORIGIN-side arrangement: poll until the fixture ship EXISTS. Asked through the queryable
     *  registry, which answers for an unloaded ship — so this waits without forcing anything. */
private boolean waitForRegisteredShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (readIntOr(execEnvelope("artest vs ship-count-all " + dim), "count", -1) >= 1) {
                return true;
            }
            bot().waitTicks(5);
        }
        return false;
    }

    private static boolean hasKey(String json, String key) {
        return json != null && json.contains("\"" + key + "\":");
    }

    @Test
    public void aCrewMemberIsReseatedOnArrivalWithNothingForcingTheShipLoaded() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)", serverHasVs());

        String setup = execEnvelope("artest space transit-setup-piloted");
        assertTrue("piloted transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");

        // ARRANGEMENT. The fixture's assembly is async, so wait for the ship to EXIST — asked through the
        // queryable registry, which answers for an unloaded ship and therefore forces nothing.
        assertTrue("the piloted origin ship never assembled in the pool cell (dim " + originDim + ")",
                waitForRegisteredShip(originDim));

        String health = execEnvelope("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(health);
        assertTrue("player health must echo the player name: " + health, nameM.find());
        String botName = nameM.group(1);

        // Put the bot in the origin cell FIRST, at the assembly anchor. That is what makes the origin ship
        // loaded — by a real player's proximity, VS's own mechanism — so even the arrangement needs no
        // force-load. (A first cut called `vs load-ships` here instead, and the ship had unloaded again by
        // the very next probe: find-seat came back with the seat located but NO ship world position. That
        // is this same bug biting the arrangement rather than the assertion.)
        String enter = execEnvelope("artest space enter " + botName + " " + originDim + " 1 64 1");
        assertTrue("space enter into the origin cell must succeed: " + enter, readBool(enter, "ok"));
        bot().waitTicks(20);
        assertEquals("the client must have followed into the transit origin cell",
                originDim, bot().reportWeather().get("dim").getAsInt());

        // Now locate the seat. Retried, because the ship's world position resolves only once VS has
        // actually loaded it for the nearby bot, a tick or two after the dimension transfer.
        String seat = "";
        for (int i = 0; i < 40 && !hasKey(seat, "shipWorldX"); i++) {
            seat = execEnvelope("artest vs find-seat " + originDim + " 1 64 1");
            if (!hasKey(seat, "shipWorldX")) {
                bot().waitTicks(5);
            }
        }
        // Witness sensitivity: without a located seat the whole "still riding on the far side" observation
        // is vacuous, so this is asserted before anything is done to the ship.
        assertTrue("the pilot seat must be found in the assembled ship (else the test is vacuous): " + seat,
                readBool(seat, "seatFound"));
        assertTrue("the origin ship must resolve a world position with the bot beside it: " + seat,
                hasKey(seat, "shipWorldX"));
        int seatX = readInt(seat, "seatX"), seatY = readInt(seat, "seatY"), seatZ = readInt(seat, "seatZ");

        // Seat the bot. Retried with a FRESH dummy each attempt: the dummy is spawned at the shipyard's
        // subspace coordinates and glued to the ship's world position only on its first tick, so a spawn
        // chunk that unloads before that tick leaves the returned id resolving to nothing.
        String mount = "";
        boolean mounted = false;
        for (int attempt = 0; attempt < 5 && !mounted; attempt++) {
            String mountAt = execEnvelope("artest vs seat-mount-at " + originDim + " " + seatX + " " + seatY + " " + seatZ);
            assertTrue("seat-mount-at must spawn the seat dummy: " + mountAt, readBool(mountAt, "ok"));
            mount = execEnvelope("artest player mount-entity " + readInt(mountAt, "dummyId"));
            mounted = mount.contains("\"mounted\":true");
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        assertTrue("the bot must mount the pilot-seat dummy (5 spawn+mount attempts): " + mount, mounted);
        bot().waitTicks(10);

        // CONTROL: the client confirms it IS riding before the jump, so "riding after" carries information.
        assertTrue("the bot must be seated on the ship BEFORE the jump (control): "
                + bot().reportRidingEntity(), bot().reportRidingEntity().get("riding").getAsBoolean());

        String begin = execEnvelope("artest space transit-begin " + originDim + " 1 64 1");
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        int targetDim = -1;
        String lastTick = "";
        for (int i = 0; i < 80 && targetDim < 0; i++) {
            lastTick = execEnvelope("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                targetDim = readInt(lastTick, "targetDim");
                break;
            }
            bot().waitTicks(2);
        }
        assertTrue("the jump never completed (still in transit); last tick=" + lastTick, targetDim >= 0);

        // The leg under test. Drive the transit's retries and watch the CLIENT. Note what is NOT here: no
        // load-ships against targetDim, no permaload. If the re-seat needs the arriving ship loaded, there
        // is nothing in this world to load it.
        boolean reseated = false;
        for (int i = 0; i < RESEAT_POLLS && !reseated; i++) {
            execEnvelope("artest space transit-tick");
            bot().waitTicks(2);
            reseated = bot().reportRidingEntity().get("riding").getAsBoolean()
                    && bot().reportWeather().get("dim").getAsInt() == targetDim;
        }

        JsonObject riding = bot().reportRidingEntity();
        assertTrue("a crew member must be re-seated on arrival with NOTHING forcing the ship loaded; client "
                + "reports " + riding + " (targetDim=" + targetDim + ", clientDim="
                + bot().reportWeather().get("dim").getAsInt() + ")",
                riding.get("riding").getAsBoolean());
        assertTrue("the re-mounted entity must be the ship's seat dummy: " + riding,
                riding.get("entityClass").getAsString().endsWith("EntityDummy"));
        assertEquals("the client must have followed the crew into the target cell",
                targetDim, bot().reportWeather().get("dim").getAsInt());
    }


    // ---- migrated: VSJumpTellsThePilotWhatIsHappeningE2ETest ----

    private static final String SKY = "zmaster587.advancedRocketry.client.render.planet.BoundarySky";

    private static final String TUNNEL = "zmaster587.advancedRocketry.client.render.planet.HyperspaceTunnel";

    /** Above vanilla's sky-pass floor of 4 chunks; the harness otherwise pins the client at 2. */
private static final int SKY_RENDER_DISTANCE = 8;

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
                // Baseline the frame counters once the CLIENT'S OWN dimension is the corridor —
                // not a fixed number of ticks after the HUD flips. They are CUMULATIVE client-side
                // counters and this is an equality pin, so every frame the client draws while it
                // still believes it is in the cell reads as "the ring was drawn in hyperspace".
                // The HUD is server-driven state; the ring is drawn by the client's own renderer
                // off the client's own dimension, so that dimension is the condition to wait on.
                // A tick count is only a guess at how long the handover takes, and the guess
                // scales with load: measured 2026-08-07 the leak was exactly one frame and a
                // 4-tick pause covered it, then on 2026-08-08 the same pause let TWO through under
                // full-gate load (expected:<122> but was:<124>). Waiting on the state cannot be
                // out-run by a slower handover.
                int hyperDimNow = readInt(lastTick, "hyperDim");
                for (int settle = 0; settle < 40
                        && bot().reportWeather().get("dim").getAsInt() != hyperDimNow; settle++) {
                    bot().waitTicks(1);
                }
                assertEquals("ARRANGEMENT: the client never reached the corridor's own dimension, so "
                                + "the ring baseline below would be taken in the cell it left",
                        hyperDimNow, bot().reportWeather().get("dim").getAsInt());
                // The client's dimension is necessary but not sufficient: measured 2026-08-08, one
                // ring frame still lands AFTER it flips (the leak went 2 -> 1 when the wait moved
                // from 4 ticks to this state). So wait for the renderer ITSELF to say it is in the
                // corridor — its own frame counter advancing — and baseline only then. The window
                // given up is exactly the handover frame, which the claim was never about; a ring
                // frame drawn while the corridor is provably being drawn is a real one.
                long tunnelAtFlip = tunnelFrames();
                for (int settle = 0; settle < 40 && tunnelFrames() <= tunnelAtFlip; settle++) {
                    bot().waitTicks(1);
                }
                assertTrue("ARRANGEMENT: the corridor renderer never drew a frame after the client "
                                + "entered hyperspace (corridor frames stuck at " + tunnelAtFlip
                                + "), so there is no corridor to baseline the ring against",
                        tunnelFrames() > tunnelAtFlip);
                ringAtStart = ringFrames();
                tunnelAtStart = tunnelFrames();
                scenario().record("ringAtStart", ringAtStart)
                        .record("tunnelAtStart", tunnelAtStart);
            }
            samples++;
            hudInFlight = hudNow;
            ringInFlight = ringFrames();
            tunnelInFlight = tunnelFrames();
            // Per-sample, so a future red says WHERE the ring moved instead of only that it did.
            if (ringInFlight != ringAtStart) {
                scenario().record("ringMovedAtSample" + samples, ringInFlight);
            }
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

}
