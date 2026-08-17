package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The step that was missing: <b>nobody names the target.</b>
 *
 * <p>Everything the gun system could do before this class needed a human in the loop — a linker, a
 * console, a probe. A battery could track what it was told about and could not notice anything. The
 * sensor is the block that closes that, and these two tests are the two things it has to be true of:
 * it finds a hostile on its own, and it cannot hold everything equally well.</p>
 *
 * <h3>Each test carries its own control</h3>
 * <p>"The gun fired" is worthless on its own — a gun fires for a dozen reasons. So the first test
 * watches the SAME battery and the SAME zombie with acquisition switched off and then on, and the
 * second watches the same pair through a listening sensor and then an illuminating one. Only the one
 * variable moves, and the state before it moves is asserted rather than assumed: an unbuilt gun, a
 * flat battery or a mount stuck at the edge of its arc is silent too, and none of those is what is
 * being measured here.</p>
 *
 * <h3>Why the site is roofed and floored</h3>
 * <p>A zombie in daylight burns, and a burning body is a beacon — it would sail over any lock
 * threshold and turn the second test green for exactly the wrong reason. Building a box removes the
 * question rather than relying on the world's clock.</p>
 */
public class FireControlSensorE2ETest extends AbstractSharedServerTest {

    /** This class's own site, clear of every other server test's. */
    private static final int X = 9400, Y = 80, Z = 9400;

    /** Where a contact is comfortably lockable by listening alone. */
    private static final int NEAR_TARGET = 18;

    /** Far enough that a cool body's own radiance no longer resolves it. */
    private static final int FAR_TARGET = 60;

    private static final long TIMEOUT_MS = 25_000L;

    /** Long enough for a gun that is going to fire to have fired several times. */
    private static final long QUIET_WATCH_MS = 5_000L;

    /**
     * A battery nobody has told anything acquires a hostile that walks into range, and stops doing
     * so the moment acquisition is switched off — which is what says the sensor is the reason.
     */
    @Test
    public void aSensorAcquiresAHostileThatNobodyNamed() throws Exception {
        int base = X;
        buildSite(base);
        buildBattery(base);
        String gun = awaitOperable(base);
        assertTrue("the gun never assembled, so its silence would say nothing: " + gun,
                gun.contains("\"operable\":true"));
        assertTrue("something has already given this gun a target — then nothing below is about"
                + " acquisition: " + gun, gun.contains("\"hasTarget\":false"));

        // The control first: the same battery, the same target, acquisition switched off.
        config("enableFireControlSensor", "false");
        spawnZombie(base + NEAR_TARGET);
        charge(base);
        Thread.sleep(QUIET_WATCH_MS);
        String silent = read(base);
        assertEquals("a battery with acquisition disabled fired at something nobody named it — the"
                + " config flag does not disable the mechanic: " + silent, 0, extractInt(silent, "shots"));
        assertTrue("and it should not be holding a contact either: " + silent,
                silent.contains("\"acquired\":false"));

        // One variable moves.
        config("enableFireControlSensor", "true");
        String sensor = awaitSensorContact(base + 1);
        assertTrue("the sensor never found the zombie standing " + NEAR_TARGET + " blocks in front"
                + " of it: " + sensor, sensor.contains("\"hasContact\":true"));

        charge(base);
        assertTrue("the battery never fired on a target its own sensor was holding: "
                + read(base), awaitShots(base, 1) >= 1);
        assertTrue("the gun fired, but not on an acquisition — something else gave it a target: "
                + read(base), read(base).contains("\"acquired\":true"));
    }

    /**
     * The trade the whole passive/active split exists for: a cool body far enough away is SEEN by a
     * listening sensor and cannot be held well enough to shoot at. Illuminating it holds it — and
     * that is the only thing that changes between the two halves of this test.
     */
    @Test
    public void aCoolTargetTooFarToHoldByListeningIsHeldByIlluminating() throws Exception {
        int base = X + 200;
        buildSite(base);
        buildBattery(base);
        config("enableFireControlSensor", "true");
        config("fireControlSensorRadius", "96.0");
        config("fireControlSensorLockQualityToFire", "0.25");
        config("fireControlSensorActiveLockQuality", "0.95");

        String gun = awaitOperable(base);
        assertTrue("the gun never assembled: " + gun, gun.contains("\"operable\":true"));
        spawnZombie(base + FAR_TARGET);
        charge(base);

        // Listening. It hears the zombie and cannot resolve it.
        String listening = awaitSensorContact(base + 1);
        assertTrue("the listening sensor did not even detect the zombie, so nothing below is about"
                + " the LOCK: " + listening, listening.contains("\"hasContact\":true"));
        assertTrue("a cool body at " + FAR_TARGET + " blocks was locked by listening alone — then"
                + " illuminating buys nothing and the mode is decoration: " + listening,
                listening.contains("\"locked\":false"));

        String tracking = awaitGunAcquired(base);
        assertTrue("the gun is not holding the sensor's contact: " + tracking,
                tracking.contains("\"acquired\":true"));
        assertTrue("the gun is not even pointing at it — then its silence is about geometry rather"
                + " than about the lock: " + tracking, tracking.contains("\"onTarget\":true"));

        int before = extractInt(read(base), "shots");
        Thread.sleep(QUIET_WATCH_MS);
        assertEquals("the battery fired on a contact it cannot hold: a poor track must mean tracking"
                + " without shooting, or the lock threshold is not doing anything: " + read(base),
                before, extractInt(read(base), "shots"));

        // Same sensor, same zombie, same distance — it switches the light on.
        exec("artest sensor charge 0 " + (base + 1) + " " + Y + " " + Z);
        assertTrue("the probe could not switch the sensor to active",
                exec("artest sensor mode 0 " + (base + 1) + " " + Y + " " + Z + " active")
                        .contains("\"ok\":true"));

        String illuminating = awaitSensorLocked(base + 1);
        assertTrue("illuminating did not produce a lock on the same target at the same range: "
                + illuminating, illuminating.contains("\"locked\":true"));
        assertTrue("an actively illuminating sensor must be emitting — that is its whole price: "
                + illuminating, illuminating.contains("\"emitting\":true"));

        charge(base);
        assertTrue("the battery still would not fire once the contact was properly held: "
                + read(base), awaitShots(base, before + 1) > before);
    }

    // ---- scenario construction

    /**
     * A gun and a sensor, touching, which is all it takes to be one network: no cable, no console.
     * The sensor is the only thing here that was not already possible.
     */
    private void buildBattery(int bx) throws Exception {
        place("advancedrocketry:turret", bx, Y, Z);
        for (int i = 1; i <= 4; i++) {
            place("advancedrocketry:gunBarrel", bx, Y + i, Z);
        }
        place("advancedrocketry:gunCooling", bx, Y, Z + 1);
        place("advancedrocketry:gunCooling", bx, Y, Z - 1);
        place("advancedrocketry:fireControlSensor", bx + 1, Y, Z);
        exec("artest sensor charge 0 " + (bx + 1) + " " + Y + " " + Z);
    }

    /** A floored, roofed, cleared corridor: no daylight, no terrain in the line of fire, no falling. */
    private void buildSite(int bx) throws Exception {
        int far = bx + FAR_TARGET + 12;
        // A roofed corridor is a dark room, and a dark room breeds contacts nobody put there. The
        // only thing this battery is allowed to notice is the zombie this test spawns.
        exec("gamerule doMobSpawning false");
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + ((bx - 16) >> 4) + " "
                + ((Z - 16) >> 4) + " " + ((far + 16) >> 4) + " " + ((Z + 16) >> 4))
                .contains("\"ok\":true"));
        fill(bx - 4, Y, Z - 4, far, Y + 6, Z + 4, "minecraft:air");
        fill(bx - 4, Y - 1, Z - 4, far, Y - 1, Z + 4, "minecraft:stone");
        fill(bx - 4, Y + 7, Z - 4, far, Y + 7, Z + 4, "minecraft:stone");
        // Everything has to keep ticking, including the zombie at the far end.
        for (int cx = (bx - 16) >> 4; cx <= (far + 16) >> 4; cx++) {
            assertTrue("could not hold a chunk", exec("artest chunk forceload 0 " + cx + " "
                    + (Z >> 4)).contains("\"ok\":true"));
        }
    }

    private void spawnZombie(int bx) throws Exception {
        String resp = exec("artest entity spawn 0 " + (bx + 0.5D) + " " + Y + " " + (Z + 0.5D)
                + " minecraft:zombie");
        assertTrue("could not spawn the target: " + resp, resp.contains("\"spawned\":true"));
    }

    private void config(String key, String value) throws Exception {
        String resp = exec("artest config set " + key + " " + value);
        assertTrue("could not set " + key + ": " + resp, resp.contains("\"ok\":true"));
    }

    private void charge(int bx) throws Exception {
        exec("artest turret charge 0 " + bx + " " + Y + " " + Z);
    }

    // ---- waiting on the world

    private String awaitOperable(int bx) throws Exception {
        return await(() -> read(bx), state -> state.contains("\"operable\":true"));
    }

    private String awaitGunAcquired(int bx) throws Exception {
        return await(() -> read(bx), state -> state.contains("\"acquired\":true")
                && state.contains("\"onTarget\":true"));
    }

    private String awaitSensorContact(int bx) throws Exception {
        return await(() -> sensorRead(bx), state -> state.contains("\"hasContact\":true"));
    }

    private String awaitSensorLocked(int bx) throws Exception {
        return await(() -> sensorRead(bx), state -> state.contains("\"locked\":true"));
    }

    private int awaitShots(int bx, int wanted) throws Exception {
        String state = await(() -> read(bx), s -> extractInt(s, "shots") >= wanted);
        return extractInt(state, "shots");
    }

    /** Poll one probe until it says what we are waiting for, or the budget runs out. */
    private String await(ProbeRead probe, java.util.function.Predicate<String> done) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String state = probe.read();
        while (System.currentTimeMillis() < deadline && !done.test(state)) {
            Thread.sleep(250L);
            state = probe.read();
        }
        return state;
    }

    private interface ProbeRead {
        String read() throws Exception;
    }

    // ---- probes

    private String read(int bx) throws Exception {
        return exec("artest turret read 0 " + bx + " " + Y + " " + Z);
    }

    private String sensorRead(int bx) throws Exception {
        return exec("artest sensor read 0 " + bx + " " + Y + " " + Z);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) throws Exception {
        String resp = exec("artest fill 0 " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " "
                + z2 + " " + block);
        assertTrue("could not fill with " + block + ": " + resp, resp.contains("\"ok\":true"));
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = exec("artest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + y + "," + z + ": " + resp,
                resp.contains("\"placed\":true"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
