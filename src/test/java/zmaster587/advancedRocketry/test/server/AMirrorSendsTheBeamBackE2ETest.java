package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A mirror does not swallow a beam — it sends it somewhere, and somewhere is a place with blocks in it.
 *
 * <p>Mirror plating computes an outgoing direction for the beam it reflects and hands it back as a
 * deflection. For a long time nothing on the beam path asked whether the answer WAS a deflection: the
 * beam ended at the plating and the reflected energy was reported to a caller that did not read it. The
 * hull behind the mirror was protected, so from the defender's chair the armour looked right, and
 * three green tests over the thrown-round path — where deflection has always worked — said nothing
 * about it. What was missing had no observer at all.</p>
 *
 * <p>This gives it one. A beam meeting a plate square-on is reflected back down its own line, and the
 * only thing standing on that line is the gun that fired it. That is a real consequence and not a test
 * fixture: shooting a mirror head-on is a way to shoot yourself, and a player is entitled to find that
 * out. The assertion is deliberately about WHERE the energy went rather than how much of it went
 * there — the reflectances are balance and will move.</p>
 */
public class AMirrorSendsTheBeamBackE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 84, Z = 9560;
    private static final int GUN_X = 9700;
    /** Far enough that the reflected line has a clear run home, short enough to stay in loaded chunks. */
    private static final int MIRROR_X = GUN_X + 12;

    /**
     * Hold a beam on a mirror and the gun is what the beam comes back to.
     *
     * <p>Two halves, and both are the point. The iron behind the mirror must be untouched, or the
     * plating is not reflecting but merely being slow to break; and the gun's own controller must have
     * taken damage, or the reflected energy went nowhere and the mirror is an absorber with extra
     * steps. Either half alone passes for the wrong reason.</p>
     */
    @Test
    public void aBeamHeldOnAMirrorComesBackToTheGunThatFiredIt() throws Exception {
        buildSite();
        buildBeamGun();

        // One plate square across the line of fire, with plain iron directly behind it. The iron is
        // the control: whatever happens to the gun, this must not be dug.
        place("advancedrocketry:mirrorPlatingGold", MIRROR_X, Y, Z);
        place("minecraft:iron_block", MIRROR_X + 1, Y, Z);

        aimAt(MIRROR_X);
        for (int i = 0; i < 4; i++) {
            exec("artest turret charge " + DIM + " " + GUN_X + " " + Y + " " + Z);
            Thread.sleep(1_400L);
        }

        String behind = stageAt(MIRROR_X + 1);
        assertTrue("the iron BEHIND the mirror was damaged, so the plating passed the beam through "
                + "instead of turning it — this test is then measuring a broken mirror and not a "
                + "reflection: " + behind, stage(behind) == 0 && !behind.contains("\"wasDestroyed\":true"));

        String gun = stageAt(GUN_X);
        assertTrue("the gun that fired into a mirror took nothing at all. The reflected energy went "
                + "nowhere: the plating answered with a deflection and the beam path threw the answer "
                + "away, which is what made a mirror look like armour and behave like a hole in the "
                + "world's bookkeeping: " + gun,
                stage(gun) > 0 || gun.contains("\"wasDestroyed\":true"));
    }

    // ---- driving

    /** The same reference beam gun the dwell scenarios use: a controller, emitters, cooling. */
    private void buildBeamGun() throws Exception {
        place("advancedrocketry:turret", GUN_X, Y, Z);
        for (int i = 1; i <= 3; i++) {
            place("advancedrocketry:gunBeamEmitter", GUN_X, Y + i, Z);
        }
        place("advancedrocketry:gunCooling", GUN_X, Y, Z + 1);
        place("advancedrocketry:gunCooling", GUN_X, Y, Z - 1);
    }

    private void buildSite() throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup " + DIM + " "
                + ((GUN_X - 16) >> 4) + " " + ((Z - 16) >> 4) + " " + ((GUN_X + 32) >> 4) + " "
                + ((Z + 16) >> 4)).contains("\"ok\":true"));
        assertTrue("could not clear the site", exec("artest fill " + DIM + " " + (GUN_X - 8) + " "
                + (Y - 2) + " " + (Z - 4) + " " + (GUN_X + 28) + " " + (Y + 12) + " " + (Z + 4)
                + " minecraft:air").contains("\"ok\":true"));
        for (int cx = ((GUN_X - 16) >> 4); cx <= ((GUN_X + 28) >> 4); cx++) {
            exec("artest chunk forceload " + DIM + " " + cx + " " + (Z >> 4));
        }
    }

    private void aimAt(int targetX) throws Exception {
        exec("artest turret target " + DIM + " " + GUN_X + " " + Y + " " + Z + " "
                + (targetX + 0.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D));
    }

    // ---- reading

    private String stageAt(int x) throws Exception {
        return exec("artest damage stage " + DIM + " " + x + " " + Y + " " + Z);
    }

    private int stage(String json) {
        return extract(json, "stage");
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + resp, resp.contains("\"placed\":true"));
    }

    private static int extract(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+|true|false)").matcher(json);
        if (!m.find()) {
            return -1;
        }
        String v = m.group(1);
        if ("true".equals(v)) {
            return 1;
        }
        if ("false".equals(v)) {
            return 0;
        }
        return Integer.parseInt(v);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
