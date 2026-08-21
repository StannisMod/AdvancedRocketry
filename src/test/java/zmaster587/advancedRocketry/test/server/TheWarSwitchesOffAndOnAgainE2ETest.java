package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One switch, both weapon families, and a world that survives being switched.
 *
 * <h3>Why both families are pinned and not one</h3>
 * <p>The key this replaced gated the shot registry alone. A held beam has no record and never passes
 * through that registry, so a beam turret kept burning hulls on a server that had switched combat
 * off — and every instrument said the war was off. A switch that covers one family is worse than no
 * switch, because it reads as a promise, so the thrower half of this test is the control that would
 * have passed against the broken build and the beam half is the one that would not.</p>
 *
 * <h3>Why ON again is the point</h3>
 * <p>The switch exists to be thrown on a world that has already been fought over and thrown back
 * later, so what OFF must NOT do is as load-bearing as what it does: damage already recorded stays,
 * and guns fire again afterwards without being rebuilt.</p>
 */
public class TheWarSwitchesOffAndOnAgainE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 84, Z = 9900;
    private static final int THROWER_X = 9700, BEAM_X = 9760;

    @Test
    public void withTheWarOffNeitherFamilyDamagesAnythingAndBothWorkAgainAfterwards() throws Exception {
        buildSite(THROWER_X);
        buildGun(THROWER_X, false);
        buildSite(BEAM_X);
        buildGun(BEAM_X, true);

        int throwerWall = THROWER_X + 12, beamWall = BEAM_X + 12;
        wall(throwerWall);
        wall(beamWall);

        try {
            exec("artest config set enableWeapons false");

            aimAndFeed(THROWER_X, throwerWall);
            aimAndFeed(BEAM_X, beamWall);
            Thread.sleep(4_000L);

            String thrower = read(THROWER_X);
            assertTrue("a gun reports itself merely idle with combat switched off: a disabled gun and"
                    + " a broken one then look identical, which is what the old switch did: " + thrower,
                    thrower.contains("\"weaponsDisabled\":true"));
            assertEquals("a thrower fired with the war switched off: " + thrower, 0,
                    extract(thrower, "shots"));

            String beam = read(BEAM_X);
            assertTrue("the BEAM gun is lit with the war switched off — the half of the mechanic the"
                    + " old key never covered: " + beam, extract(beam, "beamLit") == 0);

            assertTrue("the thrower's wall was damaged with the war off: " + stage(throwerWall),
                    intact(stage(throwerWall)));
            assertTrue("the beam's wall was damaged with the war off, so the beam is still declaring"
                    + " impacts: " + stage(beamWall), intact(stage(beamWall)));
        } finally {
            exec("artest config set enableWeapons true");
        }

        // And on again, on the same world, with no rebuilding: the switch is meant to be thrown twice.
        aimAndFeed(THROWER_X, throwerWall);
        String firing = awaitShots(THROWER_X);
        assertTrue("with the war switched back on the gun never fired again: the switch is one-way,"
                + " which is not what it was built for: " + firing, extract(firing, "shots") >= 1);
        assertTrue("a gun still reports itself disabled after the war was switched back on: "
                + firing, firing.contains("\"weaponsDisabled\":false"));
    }

    // ---- driving

    private void buildGun(int bx, boolean beam) throws Exception {
        place("advancedrocketry:turret", bx, Y, Z);
        for (int i = 1; i <= 3; i++) {
            place(beam ? "advancedrocketry:gunBeamEmitter" : "advancedrocketry:gunBarrel", bx, Y + i, Z);
        }
        place("advancedrocketry:gunCooling", bx, Y, Z + 1);
        place("advancedrocketry:gunCooling", bx, Y, Z - 1);
    }

    private void buildSite(int bx) throws Exception {
        exec("artest chunk warmup " + DIM + " " + ((bx - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((bx + 48) >> 4) + " " + ((Z + 16) >> 4));
        exec("artest fill " + DIM + " " + (bx - 4) + " " + (Y - 2) + " " + (Z - 4) + " " + (bx + 40)
                + " " + (Y + 12) + " " + (Z + 4) + " minecraft:air");
        for (int cx = ((bx - 16) >> 4); cx <= ((bx + 40) >> 4); cx++) {
            exec("artest chunk forceload " + DIM + " " + cx + " " + (Z >> 4));
        }
    }

    private void wall(int x) throws Exception {
        exec("artest fill " + DIM + " " + x + " " + Y + " " + Z + " " + (x + 3) + " " + Y + " " + Z
                + " minecraft:iron_block");
    }

    private void aimAndFeed(int bx, int wallX) throws Exception {
        exec("artest turret charge " + DIM + " " + bx + " " + Y + " " + Z);
        exec("artest turret target " + DIM + " " + bx + " " + Y + " " + Z + " " + (wallX + 0.5D)
                + " " + (Y + 0.5D) + " " + (Z + 0.5D));
    }

    // ---- reading

    private String read(int bx) throws Exception {
        return exec("artest turret read " + DIM + " " + bx + " " + Y + " " + Z);
    }

    private String stage(int x) throws Exception {
        return exec("artest damage stage " + DIM + " " + x + " " + Y + " " + Z);
    }

    private static boolean intact(String stageJson) {
        return !stageJson.contains("\"wasDestroyed\":true")
                && !stageJson.contains("\"block\":\"minecraft:air\"")
                && extractStatic(stageJson, "stage") <= 0;
    }

    private String awaitShots(int bx) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
        String state = "";
        while (System.currentTimeMillis() < deadline) {
            exec("artest turret charge " + DIM + " " + bx + " " + Y + " " + Z);
            state = read(bx);
            if (extract(state, "shots") >= 1) {
                return state;
            }
            Thread.sleep(500L);
        }
        return state;
    }

    private void place(String block, int x, int y, int z) throws Exception {
        String resp = exec("artest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        assertTrue("failed to place " + block + ": " + resp, resp.contains("\"placed\":true"));
    }

    private int extract(String json, String key) {
        return extractStatic(json, key);
    }

    private static int extractStatic(String json, String key) {
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

    private String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }
}
