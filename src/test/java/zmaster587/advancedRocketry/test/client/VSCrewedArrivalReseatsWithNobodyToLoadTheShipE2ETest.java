package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A seated crew member is re-seated on the far side of a jump <b>with nothing forcing the arriving ship
 * loaded</b> — observed on the REAL CLIENT.
 *
 * <p>This is the crewed twin of the unmanned arrival pin, and it exists because the existing crew e2e
 * ({@link VSShipTransitCrewE2ETest}) cannot see the thing under test: it opens with
 * {@code vs permaload true}, which hands the whole run the very state the arrival is supposed to
 * establish for itself. Under that affordance the re-seat is green whatever the production code does
 * about loadedness.</p>
 *
 * <p><b>Why an arrival is the hard case, and why it is a chicken-and-egg.</b> Valkyrien Skies loads a
 * ship only while a player is within its load distance and queues an unload every tick for one that is
 * not. The crew are dismounted at the origin and are carried across only by the re-seat itself — the
 * production path moves each player into the destination dimension only AFTER his seat's world position
 * resolves. So during the settle nobody is near the arriving ship, the ship is not loaded, and any step
 * that needs it loaded cannot complete except by AR force-loading it itself — which is a race against
 * VS's own unload, not a fact about the arrival's progress.</p>
 *
 * <p><b>What is arrangement and what is under test.</b> The ORIGIN side may be force-loaded freely: the
 * fixture ship has to be assembled and its seat located before a bot can sit on it, and once the bot has
 * entered the origin cell VS holds that ship loaded honestly, by proximity. The DESTINATION side is the
 * leg under test and is given NOTHING — no {@code permaload}, no {@code load-ships}, no observer.</p>
 *
 * <p><b>Acceptance (client oracle):</b> the bot's own client reports it riding an {@code EntityDummy} and
 * reports the TARGET dimension. Gated on the server's real VS presence (run with {@code -PwithVS}).</p>
 */
public class VSCrewedArrivalReseatsWithNobodyToLoadTheShipE2ETest extends AbstractClientE2ETest {

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");

    /** Ticks of transit driving after arrival. The re-seat is retry-based; a healthy one takes a few. */
    private static final int RESEAT_POLLS = 90;

    @Test
    public void aCrewMemberIsReseatedOnArrivalWithNothingForcingTheShipLoaded() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)", serverHasVs());

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("piloted transit setup must succeed: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");

        // ARRANGEMENT. The fixture's assembly is async, so wait for the ship to EXIST — asked through the
        // queryable registry, which answers for an unloaded ship and therefore forces nothing.
        assertTrue("the piloted origin ship never assembled in the pool cell (dim " + originDim + ")",
                waitForRegisteredShip(originDim));

        String health = exec("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(health);
        assertTrue("player health must echo the player name: " + health, nameM.find());
        String botName = nameM.group(1);

        // Put the bot in the origin cell FIRST, at the assembly anchor. That is what makes the origin ship
        // loaded — by a real player's proximity, VS's own mechanism — so even the arrangement needs no
        // force-load. (A first cut called `vs load-ships` here instead, and the ship had unloaded again by
        // the very next probe: find-seat came back with the seat located but NO ship world position. That
        // is this same bug biting the arrangement rather than the assertion.)
        String enter = exec("artest space enter " + botName + " " + originDim + " 1 64 1");
        assertTrue("space enter into the origin cell must succeed: " + enter, readBool(enter, "ok"));
        bot().waitTicks(20);
        assertEquals("the client must have followed into the transit origin cell",
                originDim, bot().reportWeather().get("dim").getAsInt());

        // Now locate the seat. Retried, because the ship's world position resolves only once VS has
        // actually loaded it for the nearby bot, a tick or two after the dimension transfer.
        String seat = "";
        for (int i = 0; i < 40 && !hasKey(seat, "shipWorldX"); i++) {
            seat = exec("artest vs find-seat " + originDim + " 1 64 1");
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

        // CONTROL: the client confirms it IS riding before the jump, so "riding after" carries information.
        assertTrue("the bot must be seated on the ship BEFORE the jump (control): "
                + bot().reportRidingEntity(), bot().reportRidingEntity().get("riding").getAsBoolean());

        String begin = exec("artest space transit-begin " + originDim + " 1 64 1");
        assertTrue("the transit must begin (departure crossing): " + begin, readBool(begin, "began"));

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

        // The leg under test. Drive the transit's retries and watch the CLIENT. Note what is NOT here: no
        // load-ships against targetDim, no permaload. If the re-seat needs the arriving ship loaded, there
        // is nothing in this world to load it.
        boolean reseated = false;
        for (int i = 0; i < RESEAT_POLLS && !reseated; i++) {
            exec("artest space transit-tick");
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

    @After
    public void cleanup() {
        try {
            if (serverHasVs()) {
                exec("artest player dismount");
            }
        } catch (Exception ignored) {
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    /**
     * Run a probe and return ONLY its JSON envelope. The server writes its own log lines to the same
     * stream, so joining every returned line hands the assertions whatever unrelated line happened to
     * land in the window — including one that satisfies them.
     */
    private String exec(String cmd) throws Exception {
        String envelope = "";
        for (String line : serverClient().execute(cmd)) {
            int brace = line.indexOf('{');
            if (brace >= 0 && line.endsWith("}")) {
                envelope = line.substring(brace);
            }
        }
        return envelope;
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    /** ORIGIN-side arrangement: poll until the fixture ship EXISTS. Asked through the queryable
     *  registry, which answers for an unloaded ship — so this waits without forcing anything. */
    private boolean waitForRegisteredShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (readIntOr(exec("artest vs ship-count-all " + dim), "count", -1) >= 1) {
                return true;
            }
            bot().waitTicks(5);
        }
        return false;
    }

    private static boolean hasKey(String json, String key) {
        return json != null && json.contains("\"" + key + "\":");
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

    private static boolean readBool(String json, String key) {
        return Pattern.compile("\"" + key + "\":true").matcher(json).find();
    }
}
