package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The first time losing a fight costs anything but holes.
 *
 * <p>A gun is shot at through the damage engine's own entry point — no probe writes a drive state —
 * and the mount walks down its ladder: it turns, it turns slowly, it seizes. The last rung is the
 * one worth having a test for: <b>a seized gun still fires</b> down the bearing it stopped at, which
 * is the whole reason the ladder ends in a named state and not in a rate of zero.</p>
 *
 * <p>Each claim is checked against the state BEFORE it: a gun that never turned, never fired, or was
 * already broken would satisfy half of this by accident.</p>
 */
public class TurretDamageDegradesE2ETest extends AbstractSharedServerTest {

    private static final int X = 9000, Y = 80, Z = 9000;
    private static final long TIMEOUT_MS = 25_000L;

    /**
     * How many stages one impact is allowed to buy. Sized from the block's OWN stage cost, read off
     * the probe rather than guessed: the cost comes from the toughness table, which is balance and
     * will move, and a hard-coded budget silently stops damaging anything the day it does.
     */
    private static final double STAGES_PER_IMPACT = 1.5D;

    /**
     * Impact identities, never reused. The service refuses a repeated id and answers
     * {@code DUPLICATE_IMPACT} — correct behaviour, and it silently ends a scenario that walks the
     * ladder in two passes if both passes number their shots from the same place.
     */
    private int nextImpactId = 1000;

    @Test
    public void aGunShotUpTurnsSlowlyThenSeizesAndStillFires() throws Exception {
        int base = X;
        buildSite(base);
        buildGun(base);
        String built = awaitOperable(base);
        assertTrue("the gun never assembled: " + built, built.contains("\"operable\":true"));
        assertEquals("a pristine gun must report a working drive: " + built, "WORKING", drive(base));

        // It works: pointed at something, it turns onto it and fires. Without this the degradation
        // below would be indistinguishable from a gun that never did anything.
        exec("artest turret charge 0 " + base + " " + Y + " " + Z);
        exec("artest turret target 0 " + base + " " + Y + " " + Z + " " + (base + 40.5D) + " "
                + (Y + 0.5D) + " " + (Z + 0.5D));
        assertTrue("the pristine gun never fired, so nothing below is about damage",
                awaitShots(base, 1) >= 1);

        // Now shoot the mount itself, through production's own path.
        String derated = awaitDrive(base, "DERATED");
        assertEquals("a damaged mount must turn slowly rather than either working perfectly or"
                + " dying outright: stage " + stage(base) + " of " + maxStage(base) + ", drive "
                + drive(base), "DERATED", derated);

        String jammed = awaitDrive(base, "JAMMED");
        assertEquals("a wrecked mount must SEIZE: stage " + stage(base) + " of " + maxStage(base),
                "JAMMED", jammed);

        // The rung that earns its own name: it stopped turning, it did not stop shooting.
        exec("artest turret charge 0 " + base + " " + Y + " " + Z);
        int before = shotsOf(base);
        assertTrue("a seized gun stopped firing — then JAMMED is just a slower way of saying DEAD,"
                + " and a whole class of desperate defence is gone: " + read(base),
                awaitShots(base, before + 1) > before);
    }

    // ---- driving the world

    /**
     * Hit the mount until its drive reaches {@code wanted}, or the budget of attempts runs out.
     * Every impact carries its own identity, because the service refuses a repeat.
     */
    private String awaitDrive(int bx, String wanted) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        for (int shot = 0; shot < 40 && System.currentTimeMillis() < deadline; shot++) {
            if (wanted.equals(drive(bx))) {
                return wanted;
            }
            int budget = (int) Math.ceil(stageCost(bx) * STAGES_PER_IMPACT);
            // From the SIDE, at the mount's own height, through cleared air: the mount is the first
            // solid thing the ray meets. From above it would go through the barrels first and take
            // the gun apart before the drive ever degraded — which is a different experiment, and
            // the one the first version of this test accidentally ran.
            String resp = exec("artest damage impact 0 " + (bx - 2.5D) + " " + (Y + 0.5D) + " "
                    + (Z + 0.5D) + " 1 0 0 " + budget + " KINETIC " + (nextImpactId++));
            assertTrue("the impact was refused, so the mount is not being damaged at all: " + resp,
                    resp.contains("\"ok\":true"));
            assertTrue("the impact spent nothing — it is not reaching the mount, and every"
                    + " assertion after this would be about an undamaged gun: " + resp,
                    extractInt(resp, "spent") > 0);
            assertTrue("the gun's own block was destroyed before it could seize: " + resp,
                    read(bx).contains("\"operable\":true"));
            Thread.sleep(300L);
        }
        return drive(bx);
    }

    private void buildGun(int bx) throws Exception {
        place("advancedrocketry:turret", bx, Y, Z);
        for (int i = 1; i <= 4; i++) {
            place("advancedrocketry:gunBarrel", bx, Y + i, Z);
        }
        place("advancedrocketry:gunCooling", bx, Y, Z + 1);
        place("advancedrocketry:gunCooling", bx, Y, Z - 1);
    }

    private void buildSite(int bx) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + ((bx - 16) >> 4) + " "
                + ((Z - 16) >> 4) + " " + ((bx + 64) >> 4) + " " + ((Z + 16) >> 4))
                .contains("\"ok\":true"));
        assertTrue("could not clear the site", exec("artest fill 0 " + (bx - 4) + " " + (Y - 2) + " "
                + (Z - 4) + " " + (bx + 60) + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air")
                .contains("\"ok\":true"));
        assertTrue("could not hold the chunk", exec("artest chunk forceload 0 " + (bx >> 4) + " "
                + (Z >> 4)).contains("\"ok\":true"));
    }

    // ---- reading the world

    private String drive(int bx) throws Exception {
        Matcher m = Pattern.compile("\"drive\":\"([A-Z]+)\"").matcher(read(bx));
        return m.find() ? m.group(1) : "?";
    }

    private int stage(int bx) throws Exception {
        return extractInt(exec("artest damage stage 0 " + bx + " " + Y + " " + Z), "stage");
    }

    private int maxStage(int bx) throws Exception {
        return extractInt(exec("artest damage stage 0 " + bx + " " + Y + " " + Z), "maxStage");
    }

    /** What one stage of this block costs, as the toughness table prices it today. */
    private int stageCost(int bx) throws Exception {
        int cost = extractInt(exec("artest damage stage 0 " + bx + " " + Y + " " + Z), "stageCost");
        return cost > 0 ? cost : 100;
    }

    private String awaitOperable(int bx) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String state = read(bx);
        while (System.currentTimeMillis() < deadline && !state.contains("\"operable\":true")) {
            Thread.sleep(250L);
            state = read(bx);
        }
        return state;
    }

    private int awaitShots(int bx, int wanted) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int shots = shotsOf(bx);
        while (System.currentTimeMillis() < deadline && shots < wanted) {
            Thread.sleep(250L);
            shots = shotsOf(bx);
        }
        return shots;
    }

    private int shotsOf(int bx) throws Exception {
        return extractInt(read(bx), "shots");
    }

    private String read(int bx) throws Exception {
        return exec("artest turret read 0 " + bx + " " + Y + " " + Z);
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = exec("artest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + resp, resp.contains("\"placed\":true"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
