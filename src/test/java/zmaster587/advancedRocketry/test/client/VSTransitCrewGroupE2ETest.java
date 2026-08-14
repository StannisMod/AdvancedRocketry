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

    private static final Pattern SETUP_SHIP_ID = Pattern.compile("\"shipId\":\"([^\"]+)\"");

    /**
     * The identity of the ship this scenario's setup just assembled.
     *
     * <p>Every scenario in this class runs its jump out of the SAME pool slot dimension and builds at
     * the SAME anchor — the setup allocates a fresh cell controller each time, and a fresh controller's
     * binding map is empty, so it always takes the first slot in the pool. Asking "the ship at
     * (1,64,1)" in that dimension is therefore a question with several right answers, and the one the
     * nearest-ship lookup returns is the FIRST ship ever assembled there: departed, and holding an
     * empty shipyard. Measured twice in independent boots as {@code seatFound:false} on a ship that
     * had just been built, with the same yard box printed under two different slot dims.</p>
     */
    private static String setupShipId(String setup) {
        Matcher m = SETUP_SHIP_ID.matcher(setup);
        assertTrue("the piloted transit setup must name the ship it assembled — without it every"
                + " later question about that ship is a nearest-ship guess in a dimension this class"
                + " deliberately reuses: " + setup, m.find());
        return m.group(1);
    }

    /** {@code find-seat} keyed by identity — see {@link #setupShipId} for why never by the anchor. */
    private String findSeat(int originDim, String shipId) throws Exception {
        return exec("artest vs find-seat " + originDim + " id " + shipId);
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

        // Now the ship is up: locate the pilot seat's subspace pos + the ship's world pos, keyed by the
        // identity the setup handed back rather than by the anchor every scenario here shares.
        String seat = findSeat(originDim, setupShipId(setup));
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

        // ACCEPTANCE, on the crossing's own census: this jump moved the hull it NAMES, and it parked
        // in a lane that was its alone. Both are invisible from everything asserted above - a jump
        // that delivered a stranger's hull with this crew re-seated on it looks exactly like a
        // successful one from the client's side, which is how the positional cut survived so long.
        String census = exec("artest vs arrival-trace");
        Matcher cutM = ARRIVAL_CUT.matcher(census);
        assertTrue("the arrival must leave a cut census behind: " + census, cutM.find());
        String cut = cutM.group(1);
        String cutting = censusField(cut, "cutting");
        String byDurableId = censusField(cut, "byDurableId");
        String byPosition = censusField(cut, "byPosition");
        // Printed, not merely asserted: which ARM the assertion below took is the whole value of it.
        // With no durable id resolvable it degenerates into "the cut took the anchor's craft", which
        // is what production did before there was a rule at all - a green that says nothing.
        System.out.println("[JUMP CENSUS] " + cut);
        // The jump must be able to NAME its own hull, not merely fail to mistake somebody else's for
        // it. A crossing carries the craft's durable name onto the record it creates; without that
        // the name is lost at every crossing and can only come back from the ship's own tick, which
        // a parked hull never gets - so this field going back to "null" means the carry is gone and
        // the arrival is resolving by position again, which is exactly what it looks like when it
        // delivers a stranger.
        assertTrue("the jump must resolve its own hull BY NAME in hyperspace - a null here means the "
                + "arrival is back to picking whatever craft its anchor reaches. census: " + cut,
                !"null".equals(byDurableId) && !"(absent)".equals(byDurableId));
        // Unconditional in BOTH arms: where the jump's durable id resolves a hull, that hull is the
        // one cut; where nothing could be established, the anchor's craft is - and saying which arm
        // applied is the difference between a check with three answers and one with none.
        assertEquals("the arrival cut the hull the jump names (byDurableId), or - where no identity "
                        + "could be established - the one at its anchor. census: " + cut,
                "null".equals(byDurableId) ? byPosition : byDurableId, cutting);
        assertTrue("a healthy jump is never REFUSED its own hull - a refusal here means the identity "
                + "check fires on a case it cannot judge. census: " + cut, !"REFUSED".equals(cutting));

        Matcher laneM = DEPART_LANE.matcher(census);
        assertTrue("the departure must leave a lane census behind: " + census, laneM.find());
        String lane = laneM.group(1);
        assertTrue("the lane this jump departed into must have been EMPTY when it was handed out - a "
                        + "lane holding a second hull makes every later position lookup at that "
                        + "anchor ambiguous. census: " + lane,
                lane.contains("alreadyThere=[]"));
    }

    /** One {@code key=value} field out of a census line, or {@code "(absent)"}. Values are plain
     *  tokens (uuids, "null", "REFUSED"); the bracketed and BlockPos fields are read whole. */
    private static String censusField(String census, String key) {
        Matcher m = Pattern.compile("(?:^| )" + key + "=(\\S+)").matcher(census);
        return m.find() ? m.group(1) : "(absent)";
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

        String seat = findSeat(originDim, setupShipId(setup));
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
            seat = execEnvelope("artest vs find-seat " + originDim + " id " + setupShipId(setup));
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
private void seatTheBot(int originDim, String shipId) throws Exception {
        String seat = findSeat(originDim, shipId);
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

        seatTheBot(originDim, setupShipId(setup));

        // ── CONTROL, in an ordinary cell ────────────────────────────────────────────────────────
        long skyBefore = skyFrames();
        long tunnelBefore = tunnelFrames();
        bot().waitTicks(20);
        // The sky renderer must run here at all. Without it "the corridor is drawn in hyperspace"
        // answers two questions with one number, and "the corridor came up" is indistinguishable
        // from "the sky pass never ran".
        assertTrue("this sky renderer must run in an ordinary cell (sky frames " + skyBefore + " -> "
                        + skyFrames() + "); nothing else in this test means anything if it does not",
                skyFrames() > skyBefore);
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
        // in the cell it left and drawing that cell's sky, so a baseline taken there would count the
        // crossing rather than the corridor.
        long tunnelAtStart = -1L;
        int samples = 0;
        String hudInFlight = "";
        long tunnelInFlight = -1L;
        String lastTick = "";
        for (int i = 0; i < 120; i++) {
            lastTick = exec("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                break;
            }
            bot().waitTicks(2);
            String hudNow = hud();
            if (tunnelAtStart < 0) {
                if (!hudNow.contains("HYPERSPACE")) {
                    continue; // not across yet: nothing sampled here is about hyperspace
                }
                // Baseline once the CLIENT'S OWN dimension is the corridor, not a fixed number of
                // ticks after the HUD flips. The HUD is server-driven state; the sky is drawn by the
                // client's own renderer off the client's own dimension, so that dimension is the
                // condition to wait on. A tick count is only a guess at how long the handover takes,
                // and the guess scales with load.
                int hyperDimNow = readInt(lastTick, "hyperDim");
                for (int settle = 0; settle < 40
                        && bot().reportWeather().get("dim").getAsInt() != hyperDimNow; settle++) {
                    bot().waitTicks(1);
                }
                assertEquals("ARRANGEMENT: the client never reached the corridor's own dimension, so "
                                + "the baseline below would be taken in the cell it left",
                        hyperDimNow, bot().reportWeather().get("dim").getAsInt());
                tunnelAtStart = tunnelFrames();
                scenario().record("tunnelAtStart", tunnelAtStart);
            }
            samples++;
            hudInFlight = hudNow;
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


    // ---- a crew member on his FEET crosses too ----

    /**
     * The sneak key, which is how a player leaves a seat. Held on the real client so the dismount
     * runs vanilla's own client path ({@code EntityPlayerSP} sending the stop-riding action) rather
     * than a server-side {@code dismountRidingEntity} standing in for it.
     */
    private static final int SNEAK_KEY = org.lwjgl.input.Keyboard.KEY_LSHIFT;

    /**
     * Stand the seated bot up and leave him ON the deck, resolved there. Returns the deck-capture
     * report, so the caller can assert the state the crossing's own enumeration reads.
     *
     * <p>The sneak key is the human's action; the server dismount is a fallback for the run where
     * the key path does not fire (the same shape the deck-capture scenarios use), and it replaces
     * only the TRIGGER — the object dismounted, the frame it lands in and the capture that follows
     * are identical either way.</p>
     *
     * <p><b>Where he LANDS is not part of the subject.</b> Vanilla puts a dismounting rider beside
     * his mount, this fixture's whole deck is 3×3, and the cell around it is void — so on some runs
     * he steps off the edge and there is no crew member on a deck to carry (measured: the control
     * passed twice and failed on the third run, with the capture reporting not even aboard by
     * containment). Standing aboard is this scenario's PRECONDITION, not its mechanism, so the
     * arrangement re-drops him over the deck until it takes, geometry-robustly rather than
     * assuming one landing spot.</p>
     */
    private String standTheBotOnTheDeck(double shipX, double shipY, double shipZ) throws Exception {
        boolean dismounted = false;
        bot().holdKey(SNEAK_KEY);
        for (int i = 0; i < 40 && !dismounted; i++) {
            bot().waitTicks(2);
            dismounted = !bot().reportRidingEntity().get("riding").getAsBoolean();
        }
        bot().releaseKey(SNEAK_KEY);
        if (!dismounted) {
            exec("artest player dismount");
            bot().waitTicks(5);
            dismounted = !bot().reportRidingEntity().get("riding").getAsBoolean();
        }
        assertTrue("ARRANGEMENT: the crew member must actually leave his seat, or there is no crew"
                + " member on his feet to carry: " + bot().reportRidingEntity(), dismounted);
        bot().waitTicks(30); // let him settle and the capture take
        String capture = exec("artest vs deck-capture");
        for (int drop = 0; drop < 6 && !readBool(capture, "alreadyTracked"); drop++) {
            exec("tp @a " + shipX + " " + (shipY + 4.0) + " " + shipZ + " 0 0");
            bot().waitTicks(40); // fall onto the deck and settle
            capture = exec("artest vs deck-capture");
        }
        return capture;
    }

    /**
     * The dimension the CLIENT is in, or a readable failure when it is in none.
     *
     * <p>{@code reportWeather().get("dim")} is absent whenever the client has no world — it was
     * disconnected, it died into a respawn screen, or it never joined — and reading it blind turns
     * every one of those into an {@code NullPointerException} on a line about dimensions. That is a
     * verdict nobody can act on: it names neither which of the three happened nor that the subject
     * left the game at all. This says which, and it says it where the reading is taken.
     */
    private int clientDim(String where) throws Exception {
        JsonObject weather = bot().reportWeather();
        if (weather.get("dim") == null) {
            JsonObject state = bot().reportState();
            org.junit.Assert.fail("the CLIENT has no world at " + where + ", so it is out of the game"
                    + " rather than in the wrong dimension — a death into a respawn screen and a"
                    + " disconnect both look like this, and neither is a statement about the subject."
                    + " weather=" + weather + " state=" + state);
        }
        return weather.get("dim").getAsInt();
    }

    /** Forward, on the real client — the key a player walks with. */
    private static final int FORWARD_KEY = org.lwjgl.input.Keyboard.KEY_W;

    private static final Pattern RESEAT_BLOCK = Pattern.compile("\"reseatBlock\":\"([^\"]*)\"");

    /**
     * The crossing's own account of why it has not finished putting this crew back aboard, in the
     * placement's words: which step of the seat lookup or of the deck placement it is waiting on.
     *
     * <p>Read out of {@code arrival-trace} but reduced to that one field. The full envelope carries
     * the whole position-writer ring, which is hundreds of events long by the time an arrival has
     * stalled, and a verdict nobody scrolls to the end of is a verdict nobody reads. Empty means the
     * last re-seat put everyone aboard — a real answer, not a missing one.</p>
     */
    private String reseatBlock() throws Exception {
        String trace = exec("artest vs arrival-trace");
        Matcher m = RESEAT_BLOCK.matcher(trace);
        Matcher cut = ARRIVAL_CUT.matcher(trace);
        Matcher lane = DEPART_LANE.matcher(trace);
        return (m.find() ? m.group(1) : "(no arrival-trace envelope)")
                + (cut.find() ? " ;; cut: " + cut.group(1) : "")
                + (lane.find() ? " ;; depart: " + lane.group(1) : "");
    }

    private static final Pattern ARRIVAL_CUT = Pattern.compile("\"arrivalCut\":\"([^\"]*)\"");

    private static final Pattern DEPART_LANE = Pattern.compile("\"departLane\":\"([^\"]*)\"");

    /**
     * How long the void gives a crew member who is aboard nothing before it takes him, in server
     * ticks — read from production so the waits below cannot drift away from the budget they are
     * about. Mirrors `HyperspaceVoid.GRACE_TICKS`.
     */
    private static final int VOID_GRACE_TICKS = 200;

    /**
     * JUMP-2 and JUMP-8, in one flight, because the first is the honest control for the second:
     * <b>hyperspace is a place you live in, and stepping off your ship there kills you.</b>
     *
     * <p>A crew member stands up mid-flight, stays on his deck for longer than the void's whole
     * budget, and is fine; then he walks off the hull and dies. Without the first leg the second
     * proves only that something in hyperspace kills people; without the second the first proves only
     * that nothing does.</p>
     */
    @Test
    public void aCrewMemberLivesInHyperspaceUntilHeStepsOffHisShip() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)", serverHasVs());

        exec("artest vs permaload true");
        // The void exempts creative and spectator on purpose, so the mode is SET rather than assumed:
        // in either of them this scenario could only ever come back "he survived".
        exec("gamemode survival @a");

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);
        seatTheBot(originDim, setupShipId(setup));

        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // Fly only as far as hyperspace and then STOP driving the transit: an un-ticked jump parks
        // its ship in its lane indefinitely, which is the interval this scenario is about.
        int hyperDim = -1;
        String lastTick = "";
        for (int i = 0; i < 120; i++) {
            lastTick = exec("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                break;
            }
            hyperDim = readInt(lastTick, "hyperDim");
            if (bot().reportWeather().get("dim").getAsInt() == hyperDim) {
                break;
            }
            bot().waitTicks(2);
        }
        assertEquals("ARRANGEMENT: the client must actually be in hyperspace before anything here is"
                + " about hyperspace; last tick=" + lastTick,
                hyperDim, bot().reportWeather().get("dim").getAsInt());

        // The seat's own world position, as the CLIENT renders it — the deck reference for the
        // stand-up, read off the mount rather than from a probe that would need the lane's anchor.
        JsonObject mount = bot().reportRidingEntity();
        assertTrue("ARRANGEMENT: he must still be riding his seat on arrival in hyperspace: " + mount,
                mount.get("riding").getAsBoolean());
        double deckX = mount.get("posX").getAsDouble();
        double deckY = mount.get("posY").getAsDouble();
        double deckZ = mount.get("posZ").getAsDouble();

        // ── JUMP-2: the interval is livable ─────────────────────────────────────────────────────
        String capture = standTheBotOnTheDeck(deckX, deckY, deckZ);
        assertTrue("a crew member must be able to leave his seat IN HYPERSPACE and be resolved on his"
                + " deck there — that is what makes the flight an interval rather than a cutscene: "
                + capture, readBool(capture, "alreadyTracked"));

        // ...and stay there. The span is the void's OWN budget plus a margin, so "he is alive" is a
        // statement about the countdown having had every chance to fire rather than about a window
        // too short to reach it.
        bot().waitTicks(VOID_GRACE_TICKS + 60);
        JsonObject aboardState = bot().reportState();
        String aboardCapture = exec("artest vs deck-capture");
        assertTrue("a crew member standing on his own deck in hyperspace must not be taken by the"
                + " void — he is aboard, and the danger is for bodies that are not: client="
                + aboardState + " capture=" + aboardCapture,
                aboardState.get("health").getAsFloat() > 0f);
        assertTrue("...and he must still be resolved on that deck after the whole budget: "
                + aboardCapture, readBool(aboardCapture, "alreadyTracked"));

        // ── JUMP-8: the void is lethal ──────────────────────────────────────────────────────────
        // He walks off. Nothing prevents him — the danger is the mechanic, not a wall. The teleport
        // is a fallback for the run where the walk does not clear this fixture's 3x3 deck; it
        // replaces the WAY he leaves, never the leaving, which is what the mechanic reads.
        bot().holdKey(FORWARD_KEY);
        for (int i = 0; i < 20 && readBool(exec("artest vs deck-capture"), "alreadyTracked"); i++) {
            bot().waitTicks(5);
        }
        bot().releaseKey(FORWARD_KEY);
        String offHull = exec("artest vs deck-capture");
        if (readBool(offHull, "alreadyTracked")) {
            exec("tp @a " + (deckX + 30.0) + " " + deckY + " " + (deckZ + 30.0) + " 0 0");
            bot().waitTicks(20);
            offHull = exec("artest vs deck-capture");
        }
        assertTrue("ARRANGEMENT: he must actually be off the hull, or the void has nothing to take: "
                + offHull, !readBool(offHull, "alreadyTracked"));

        // Arm the channel the verdict is read out of, immediately before the wait and with no server
        // command after it: the harness echoes a marker line into this same chat for every command
        // it runs.
        armChatObservation();

        // The countdown, plus the same margin the livable leg was given.
        boolean dead = false;
        for (int i = 0; i < (VOID_GRACE_TICKS + 60) / 10 && !dead; i++) {
            bot().waitTicks(10);
            dead = bot().reportState().get("health").getAsFloat() <= 0f;
        }
        JsonObject afterState = bot().reportState();
        assertTrue("leaving your ship in hyperspace must kill you, and the client is what has to show"
                + " it — health " + afterState.get("health") + ", screen "
                + afterState.get("screen") + "; the same body survived the same span aboard, so this"
                + " is the step off the hull and not the flight", dead);

        // WHICH death, and this is not a detail. A body that steps off a lane at Y=128 in an all-air
        // world FALLS, and vanilla's own out-of-world damage below Y=-64 kills it inside this same
        // window — so "he is dead" is satisfied by a build in which this mechanic does nothing at
        // all. The message the player is shown is what tells the two apart.
        String obituary = bot().reportChat(200).toString();
        assertTrue("the void of hyperspace must be what took him, not the drop out of the world —"
                + " otherwise this scenario is green on a build where the mechanic is absent."
                + " Chat: " + obituary,
                obituary.contains("void of hyperspace"));
        assertTrue("...and it must be a SENTENCE, not a raw translation key: a death nobody can read"
                + " is a death the player cannot attribute. Chat: " + obituary,
                !obituary.contains("death.attack.arHyperspaceVoid"));

        // Fly the jump out. Every other scenario here ends with its ship delivered, and this one
        // deliberately stopped ticking mid-flight — leaving a hull parked in the world every later
        // scenario shares, with a crew record for a player who is no longer alive to be re-seated.
        // Ending the transit puts the shared world back the way this scenario found it.
        for (int i = 0; i < 200; i++) {
            if (readInt(exec("artest space transit-tick"), "inTransit") == 0) {
                break;
            }
            bot().waitTicks(2);
        }
    }

    /**
     * JUMP-3: both crossings carry every member of the transit crew, in whatever posture he is in.
     *
     * <p>The seated sibling above pins the same contract for a pilot in a chair. This one puts the
     * crew member on his FEET — the posture the crossing's enumeration used to miss entirely, since
     * it walked seat dummies and a standing player rides nothing — and asks the same question of the
     * same instrument: which world is the CLIENT in while the ship is en route, and then the same
     * question again at the far end, because the clause is about BOTH crossings.</p>
     *
     */
    @Test
    public void aWalkingCrewMemberTravelsWithHisShipThroughHyperspace() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)", serverHasVs());

        exec("artest vs permaload true");

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        // Board the way every other scenario here boards (seat + its own control), then stand up.
        // The ship's world position is read for the stand-up arrangement's re-drop, not asserted on.
        String seat = findSeat(originDim, setupShipId(setup));
        seatTheBot(originDim, setupShipId(setup));
        String capture = standTheBotOnTheDeck(readDouble(seat, "shipWorldX"),
                readDouble(seat, "shipWorldY"), readDouble(seat, "shipWorldZ"));

        // ── CONTROLS, all three before the stimulus ─────────────────────────────────────────────
        // Each one can fail, and each failure would make the in-flight reading vacuous in its own
        // way: a crew member still in his chair is the seated case again; one who is not resolved on
        // the deck is not aboard by the definition the crossing enumerates on; one already outside
        // the origin cell has nowhere to be carried from.
        assertTrue("CONTROL: the crew member must be off his seat before the jump: "
                + bot().reportRidingEntity(),
                !bot().reportRidingEntity().get("riding").getAsBoolean());
        assertTrue("CONTROL: the server must hold a deck capture for him — that is what 'aboard on"
                + " his feet' MEANS to the crossing, and without it this test would be about a"
                + " player standing in a void cell: " + capture,
                readBool(capture, "alreadyTracked") && !readBool(capture, "hullStand"));
        assertEquals("CONTROL: he must be in the origin cell before the jump", originDim,
                bot().reportWeather().get("dim").getAsInt());

        // ── THE JUMP ────────────────────────────────────────────────────────────────────────────
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        int samples = 0, hyperDim = -1, crewDim = -1;
        int clientDimInFlight = Integer.MIN_VALUE;
        boolean ridingInFlight = true;
        String captureInFlight = "";
        String lastTick = "";
        for (int i = 0; i < 120; i++) {
            lastTick = exec("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                break; // arrived — the far end is another scenario's subject
            }
            bot().waitTicks(2);
            samples++;
            if (samples == 1) {
                // The FIRST in-flight sample: the earliest moment the crew could have been left
                // behind, and the one a late-arriving fix cannot hide behind.
                hyperDim = readInt(lastTick, "hyperDim");
                crewDim = readInt(lastTick, "crewDim");
                clientDimInFlight = bot().reportWeather().get("dim").getAsInt();
                ridingInFlight = bot().reportRidingEntity().get("riding").getAsBoolean();
                captureInFlight = exec("artest vs deck-capture");
            }
        }

        // The instrument must have fired: a jump that arrived instantly says nothing about the
        // interval, and a green with zero samples would be exactly that.
        assertTrue("the jump was never observed mid-flight (0 in-flight samples); last tick=" + lastTick,
                samples > 0);

        // Arrangement oracle: the subsystem's own answer for where this crew belongs. If these two
        // disagree the fixture, not production, is what failed.
        assertEquals("mid-flight the subsystem must place this crew in the hyperspace world"
                + " (crewDim vs hyperDim); tick=" + lastTick, hyperDim, crewDim);

        // THE CONTRACT: a crossing carries whoever is aboard, standing included. The client's own
        // dimension in flight is the world its ship is parked in, not the cell it departed from.
        assertEquals("a crew member on his FEET must travel with his ship, as HIS OWN CLIENT sees it"
                + " — he was in dim " + clientDimInFlight + " (origin cell " + originDim
                + ", hyperspace " + hyperDim + ") after " + samples + " in-flight samples;"
                + " deck capture in flight=" + captureInFlight,
                hyperDim, clientDimInFlight);

        // ...and he arrives in the posture he left in: carried, not quietly seated on the way.
        assertTrue("a crew member who was standing must still be standing in flight, not folded into"
                + " a seat by the carry: " + captureInFlight, !ridingInFlight);

        // ── THE SECOND CROSSING ─────────────────────────────────────────────────────────────────
        // The clause is about BOTH crossings, and the two are not the same code path reached twice:
        // the departure boards him onto a ship parked in hyperspace, the arrival re-establishes him
        // on a ship being re-assembled in a cell that may hold other craft. A green on the first
        // says nothing about the second.
        int targetDim = -1;
        for (int i = 0; i < 120 && targetDim < 0; i++) {
            lastTick = exec("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                targetDim = readInt(lastTick, "targetDim");
                break;
            }
            bot().waitTicks(2);
        }
        // An arrival that never completes is now a statement about the crew: the settle waits for
        // everyone to be back aboard, so "still in transit" IS the placement not converging, and the
        // placement's own account of the step it is stuck on belongs in the verdict rather than in a
        // server log somebody has to go and find.
        assertTrue("the jump never completed (still in transit); last tick=" + lastTick
                + "; the crossing says it is blocked at: " + reseatBlock(), targetDim >= 0);

        // Drive the placement's retries and watch the CLIENT, exactly as the seated siblings do.
        boolean carriedOn = false;
        for (int i = 0; i < RESEAT_POLLS && !carriedOn; i++) {
            exec("artest space transit-tick");
            bot().waitTicks(2);
            carriedOn = bot().reportWeather().get("dim").getAsInt() == targetDim
                    && readBool(exec("artest vs deck-capture"), "alreadyTracked");
        }
        String captureOnArrival = exec("artest vs deck-capture");
        assertEquals("the arrival crossing must carry the crew member on his feet too — his own"
                + " client must be in the TARGET cell: " + captureOnArrival
                + "; placement blocked at: " + reseatBlock(),
                targetDim, bot().reportWeather().get("dim").getAsInt());
        assertTrue("...and he must be back ON THE DECK there, not merely in the right world: "
                + captureOnArrival + "; placement blocked at: " + reseatBlock(),
                readBool(captureOnArrival, "alreadyTracked"));
        assertTrue("...and still on his feet, never seated late by the arrival: "
                + bot().reportRidingEntity(),
                !bot().reportRidingEntity().get("riding").getAsBoolean());
    }

    /**
     * The corridor is the backdrop of a WORLD, so it is drawn for everyone in that world — not only
     * for whoever happens to be sitting down.
     *
     * <p>The defect: the sky's gate was the jump phase published on the SEAT entity, and that answers
     * 0 for anybody riding nothing. A crew member who stood up mid-flight lost the corridor, and
     * hyperspace has nothing else in its sky (no cell is loaded, so no body is ever synced), so it
     * went empty and motionless — which the reporter read as the jump itself having stopped.
     *
     * <p><b>Three readings, and the first two are what make the third mean anything.</b> No corridor
     * in an ordinary cell; a corridor while SEATED in hyperspace; a corridor still coming while he is
     * on his FEET. Without the middle reading "drawn while standing" cannot be told from "the sky pass
     * never ran", and the sky counter is read in the same window as the tunnel counter for the same
     * reason — it advances on the renderer's first line, before any branch, so a still sky and a still
     * corridor are distinguishable.
     */
    @Test
    public void aStandingCrewMemberStillSeesTheHyperspaceCorridor() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)", serverHasVs());

        // Vanilla runs the sky pass only at renderDistanceChunks >= 4 and the harness pins the client
        // at 2, so without this every sky reading below would be honestly zero for the wrong reason.
        // Read back off the client's own field rather than assumed.
        JsonObject rd = bot().setRenderDistance(SKY_RENDER_DISTANCE);
        previousRenderDistance = rd.get("previous").getAsInt();
        assertTrue("the sky pass gate must be open, read back off the client's own field: " + rd,
                rd.get("skyPassEnabled").getAsBoolean());

        exec("artest vs permaload true");
        exec("gamemode survival @a");

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);
        seatTheBot(originDim, setupShipId(setup));

        // ── READING 1, in an ordinary cell: no corridor ──────────────────────────────────────────
        long skyInCell = skyFrames();
        long tunnelInCell = tunnelFrames();
        bot().waitTicks(20);
        assertTrue("this sky renderer must run in an ordinary cell (sky frames " + skyInCell + " -> "
                        + skyFrames() + "); nothing below means anything if it does not",
                skyFrames() > skyInCell);
        assertEquals("the corridor must NOT be drawn in an ordinary cell — it says 'you are in a jump'",
                tunnelInCell, tunnelFrames());

        // ── INTO HYPERSPACE, then stop driving the jump ──────────────────────────────────────────
        // An un-ticked transit parks its ship in its lane indefinitely, which is the interval this
        // scenario is about: it needs the flight to still be happening while it reads the sky.
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));
        int hyperDim = -1;
        String lastTick = "";
        for (int i = 0; i < 120; i++) {
            lastTick = exec("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                break;
            }
            hyperDim = readInt(lastTick, "hyperDim");
            if (bot().reportWeather().get("dim").getAsInt() == hyperDim) {
                break;
            }
            bot().waitTicks(2);
        }
        assertEquals("ARRANGEMENT: the client must actually be in hyperspace before any reading here"
                + " is about hyperspace; last tick=" + lastTick,
                hyperDim, bot().reportWeather().get("dim").getAsInt());

        // ── READING 2, SEATED in hyperspace: the corridor comes up ───────────────────────────────
        JsonObject mount = bot().reportRidingEntity();
        assertTrue("ARRANGEMENT: he must still be in his seat for the seated reading: " + mount,
                mount.get("riding").getAsBoolean());
        long tunnelSeated = tunnelFrames();
        bot().waitTicks(20);
        long drawnSeated = tunnelFrames() - tunnelSeated;
        assertTrue("the corridor must be drawn for a SEATED pilot in hyperspace — this is the leg that"
                        + " proves the instrument can see a corridor at all (frames drawn in 20 ticks="
                        + drawnSeated + ")",
                drawnSeated > 0);

        // ── THE STIMULUS: he stands up, IN FLIGHT ────────────────────────────────────────────────
        double deckX = mount.get("posX").getAsDouble();
        double deckY = mount.get("posY").getAsDouble();
        double deckZ = mount.get("posZ").getAsDouble();
        String capture = standTheBotOnTheDeck(deckX, deckY, deckZ);
        assertTrue("ARRANGEMENT: he must be resolved on his deck in hyperspace, i.e. aboard on his"
                + " feet rather than adrift in a void world: " + capture,
                readBool(capture, "alreadyTracked"));
        assertTrue("ARRANGEMENT: and off his seat — riding anything at all would make the reading below"
                        + " the seated case again: " + bot().reportRidingEntity(),
                !bot().reportRidingEntity().get("riding").getAsBoolean());

        // ── READING 3, THE CONTRACT: on his feet, the corridor is still coming ───────────────────
        long skyStanding = skyFrames();
        long tunnelStanding = tunnelFrames();
        bot().waitTicks(20);
        long skyDrawnStanding = skyFrames() - skyStanding;
        long drawnStanding = tunnelFrames() - tunnelStanding;
        assertTrue("INSTRUMENT: the sky renderer must still be running in this window, or a still"
                        + " corridor below would be a still SKY and say nothing about the gate"
                        + " (sky frames in 20 ticks=" + skyDrawnStanding + ")",
                skyDrawnStanding > 0);
        assertTrue("a crew member who stood up mid-flight must still see the corridor: hyperspace has"
                        + " nothing else in its sky, so losing it leaves him looking at a dead"
                        + " starfield and reading his own jump as having stopped. Frames drawn in 20"
                        + " ticks while standing=" + drawnStanding + ", against " + drawnSeated
                        + " while seated in the same flight; sky frames standing=" + skyDrawnStanding,
                drawnStanding > 0);
    }

    /**
     * JUMP-4, the posture half: what the arrival returns is the posture the crew member is IN, not the
     * one he had when the jump fired.
     *
     * <p>The defect (ledger #212): the crew is captured ONCE, at the departure cut, and both re-seats
     * replay that frozen record. Hyperspace is livable by JUMP-2 — stand up, walk, use the ship — so a
     * crew member who stood up in the corridor was force-mounted back into the seat on arrival, undoing
     * an entire flight's worth of what the interval invited him to do.
     *
     * <p><b>Why its sibling could not catch this.</b>
     * {@link #aWalkingCrewMemberTravelsWithHisShipThroughHyperspace} stands the crew member up BEFORE
     * the jump, so the departure record already says STANDING and replaying it lands him on the deck —
     * correct behaviour reached by accident. The defect lives in the posture CHANGE, so this scenario
     * boards him seated, commits the jump from the chair, and only then puts him on his feet. That
     * sibling stays the control: it is green on either side of the fix, and this one is not.
     */
    @Test
    public void aCrewMemberWhoStoodUpMidFlightArrivesOnHisFeet() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)", serverHasVs());

        exec("artest vs permaload true");
        exec("gamemode survival @a");

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");
        assertTrue("the piloted origin ship never assembled/loaded in the pool cell (dim " + originDim + ")",
                waitForLoadedShip(originDim) >= 1);

        // Boards SEATED and jumps from the chair: that is what writes a SEATED departure record, and
        // the record is the subject here.
        seatTheBot(originDim, setupShipId(setup));
        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

        // Fly as far as hyperspace and stop driving: the stand-up has to happen mid-flight, between the
        // two cuts, which is the whole point.
        int hyperDim = -1;
        String lastTick = "";
        for (int i = 0; i < 120; i++) {
            lastTick = exec("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                break;
            }
            hyperDim = readInt(lastTick, "hyperDim");
            if (bot().reportWeather().get("dim").getAsInt() == hyperDim) {
                break;
            }
            bot().waitTicks(2);
        }
        assertEquals("ARRANGEMENT: the client must be in hyperspace before he can stand up in it;"
                + " last tick=" + lastTick, hyperDim, bot().reportWeather().get("dim").getAsInt());

        JsonObject mount = bot().reportRidingEntity();
        assertTrue("ARRANGEMENT: he must have crossed SEATED — a departure record that already said"
                + " STANDING is the sibling scenario, and it passes on the broken build: " + mount,
                mount.get("riding").getAsBoolean());

        // ── THE STIMULUS: off the seat, mid-flight ───────────────────────────────────────────────
        String capture = standTheBotOnTheDeck(mount.get("posX").getAsDouble(),
                mount.get("posY").getAsDouble(), mount.get("posZ").getAsDouble());
        assertTrue("ARRANGEMENT: he must be resolved on his deck in hyperspace — aboard on his feet is"
                + " what the arrival is supposed to give back: " + capture,
                readBool(capture, "alreadyTracked"));
        assertTrue("ARRANGEMENT: and genuinely out of the chair before the arrival: "
                        + bot().reportRidingEntity(),
                !bot().reportRidingEntity().get("riding").getAsBoolean());

        // ── FINISH THE JUMP ─────────────────────────────────────────────────────────────────────
        int targetDim = -1;
        for (int i = 0; i < 120 && targetDim < 0; i++) {
            lastTick = exec("artest space transit-tick");
            if (readInt(lastTick, "inTransit") == 0) {
                targetDim = readInt(lastTick, "targetDim");
                break;
            }
            bot().waitTicks(2);
        }
        assertTrue("the jump never completed (still in transit); last tick=" + lastTick, targetDim >= 0);

        // Drive the placement's retries and watch the CLIENT, as the siblings do — and watch whether he
        // is still THERE, which is a separate question from his posture and has to be asked first.
        //
        // The second cut removes the hull he is standing on. A body left adrift on it has only the
        // void's budget before hyperspace takes him, and that budget is SHORTER than this arrival
        // window, so "adrift" and "put back aboard" are separated here by whether he is alive at all.
        // Without this the loss surfaces as an NPE on a later line about dimensions, which names
        // neither the loss nor the window it happened in.
        boolean carriedOn = false;
        for (int i = 0; i < RESEAT_POLLS && !carriedOn; i++) {
            exec("artest space transit-tick");
            bot().waitTicks(2);
            JsonObject state = bot().reportState();
            com.google.gson.JsonElement health = state.get("health");
            assertTrue("the crew member was LOST during the arrival window rather than re-established"
                            + " on the ship: a body adrift on a hull that has just been cut has only"
                            + " the void's " + VOID_GRACE_TICKS + " ticks, and this window is longer"
                            + " than that. Iteration " + i + " of " + RESEAT_POLLS + ", state=" + state,
                    health != null && health.getAsFloat() > 0f);
            carriedOn = clientDim("the arrival poll") == targetDim
                    && readBool(exec("artest vs deck-capture"), "alreadyTracked");
        }
        String captureOnArrival = exec("artest vs deck-capture");
        assertEquals("ARRANGEMENT: the arrival must have carried him at all — his own client must be in"
                + " the TARGET cell before his posture there means anything: " + captureOnArrival,
                targetDim, clientDim("the arrival verdict"));
        // ── THE CONTRACT, before the arrangement-shaped reading below ───────────────────────────
        // Posture first, deliberately: being off the deck is a CONSEQUENCE of having been seated, so a
        // red that leads with the missing deck capture describes the symptom's shadow. Riding at all is
        // the defect, and the probe says so in one field.
        assertTrue("a crew member who was on his FEET when the ship arrived must arrive on his feet:"
                        + " the arrival may not replay where he was sitting when the jump fired, an"
                        + " entire flight earlier. Riding state on arrival="
                        + bot().reportRidingEntity() + " capture=" + captureOnArrival,
                !bot().reportRidingEntity().get("riding").getAsBoolean());
        assertTrue("...and he must be back ON THE DECK, not merely in the right world: "
                + captureOnArrival, readBool(captureOnArrival, "alreadyTracked"));
    }

}
