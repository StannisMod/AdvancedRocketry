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
 * A crew member RIDES his ship through hyperspace — observed on the REAL CLIENT, in the MIDDLE of the
 * flight, not at its two ends.
 *
 * <p><b>Why this is a separate test from the crew-survives-a-jump one.</b> That test seats a bot,
 * jumps, and asserts the bot is riding in the target cell afterwards. Both of its observations are
 * TRUE of a jump that dismounts its crew into the cell it departs from and teleports them to the
 * destination on arrival — the two ends are exactly the part that is restored. This test asserts the
 * interval BETWEEN them: while the ship is parked in the shared hyperspace world, the crew is there
 * with it.</p>
 *
 * <p><b>What the human does vs what this test does.</b> A player arms a destination at the navigation
 * console and fires the jump with the helm key; this test drives the transit fixture directly
 * ({@code space transit-setup-piloted} + {@code space transit-begin} + repeated
 * {@code space transit-tick}), because the subject here is where the CREW is during the flight, not
 * how the flight is triggered. The trigger's own client path is covered elsewhere. The fixture ship is
 * a bare deck with a flight computer and a linked pilot seat and has no propulsion — it does not need
 * any: the jump is what moves it.</p>
 *
 * <p><b>The jump is deliberately SLOW.</b> {@code transit-begin} takes a speed, and the default
 * crosses the fixture's one-sector gap in a single tick — which would leave no interval to observe at
 * all. {@link #PARK_SPEED} sizes the flight so the ship is parked for tens of probe-driven ticks, and
 * the test asserts it actually got samples in that window before it asserts anything about them.</p>
 *
 * <p><b>Oracles.</b> The dimension the crew belongs in is read from the SERVER
 * ({@code crewDim}/{@code hyperDim} on the tick response) rather than hardcoded — subsystem dimension
 * ids are minted per boot. The client's own answer ({@code report_weather.dim},
 * {@code report_riding_entity}) is the ACTUAL.</p>
 */
public class VSCrewRidesItsShipThroughHyperspaceE2ETest extends AbstractClientE2ETest {

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");

    /** Blocks per tick for the jump. Slow enough that the ship stays parked for tens of ticks. */
    private static final long PARK_SPEED = 100_000L;

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
}
