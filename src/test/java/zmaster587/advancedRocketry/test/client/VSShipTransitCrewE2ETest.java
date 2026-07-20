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
 * A seated crew member survives a tier-2 Valkyrien Skies ship's hyperspace TRANSIT still riding — observed
 * on the REAL CLIENT, not through a server probe. This is the honest end-to-end gate for the crew
 * capture/reseat wiring: a bot sits on a piloted ship's seat in the transit ORIGIN cell, the ship jumps to
 * the TARGET cell, and the bot's OWN client must report it still riding its seat, in the target dimension,
 * on the far side.
 *
 * <p><b>Probe sequence</b> (all via {@code serverClient().execute}, the CLIENT harness's server channel):</p>
 * <ol>
 *   <li>{@code vs available} — gate; the whole test skips when VS is absent (run with {@code -PwithVS}).</li>
 *   <li>{@code vs permaload true} — keep the assembled ship loaded through the headless run (reset in
 *       {@link #cleanup()}).</li>
 *   <li>{@code space transit-setup-piloted} — build a PILOTED tier-2 ship (AFC + AFC-linked pilot seat on a
 *       deck) in a fresh origin pool cell; returns the origin dim, the seat's subspace pos, and the ship's
 *       world pos. {@code seatFound:true} is the witness-sensitivity control — a false makes the test
 *       vacuous, so it is asserted before anything else.</li>
 *   <li>{@code space enter <bot> <originDim> <shipWorld…>} — transfer the real client into the origin cell
 *       (production {@code transferPlayerToDimension}); the client must FOLLOW ({@code report_weather.dim}
 *       == originDim).</li>
 *   <li>{@code vs seat-mount-at <originDim> <seat…>} + {@code player mount-entity <dummyId>} — seat the bot
 *       on the ship's pilot-seat dummy.</li>
 *   <li>CONTROL: the client reports it IS riding BEFORE the jump — so a later "riding" is meaningful.</li>
 *   <li>{@code space transit-begin} then repeated {@code space transit-tick} — advance the jump; capture the
 *       target dim when {@code inTransit} hits 0, then keep ticking (the reseat is retry-based, completing a
 *       few ticks after arrival) until the client is riding again in the target dim.</li>
 * </ol>
 *
 * <p><b>Acceptance (client oracle):</b> {@code report_riding_entity.riding == true}, the re-mounted entity
 * class ends with {@code EntityDummy}, and {@code report_weather.dim == targetDim}. The production reseat's
 * {@code transferPlayerToDimension} carried the CLIENT across dims and re-mounted it — that is the honest
 * crew-survival observation, distinct from any server-side entity query.</p>
 *
 * <p>Expected RED until the crew capture/reseat wiring is verified end-to-end; the test is the repro,
 * written where a client e2e belongs instead of a manual playtest. Every dim id is read from the probe
 * responses — never hardcoded — and every wait/poll loop is bounded.</p>
 */
public class VSShipTransitCrewE2ETest extends AbstractClientE2ETest {

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");

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

    @After
    public void cleanup() {
        try {
            if (serverHasVs()) {
                exec("artest player dismount");
                exec("artest vs permaload false");
            }
        } catch (Exception ignored) {
        }
    }

    // --- helpers (mirror the tier-2 client e2e classes) ---------------------------------------------

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
}
