package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * A beam is HELD, and what that means is that dwell is the weapon.
 *
 * <p>A gun that throws rounds spends its energy in lumps at intervals, and holding the trigger longer
 * buys more lumps. A beam has no lump: it is a line with a power, re-resolved every tick, and its depth
 * grows for as long as it is lit. These pin that difference where it is visible — in the hole — and the
 * one behaviour that makes a starved beam readable instead of a stutter.</p>
 *
 * <p>Numbers are deliberately not asserted. Every one of them is balance and will move; what is claimed
 * is an ORDERING (longer dwell digs deeper) and a STATE MACHINE (dark, saving, lit again).</p>
 */
public class ABeamIsHeldNotThrownE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 84, Z = 9500;
    private static final int DWELL_X = 9600, STARVED_X = 9660;
    /** Deep enough that a short dwell cannot reach the far side; see the dwell scenario. */
    private static final int WALL_DEPTH = 30;


    /**
     * The claim the whole family exists for: keeping it on the same spot digs DEEPER, with no second
     * trigger pull and no round in flight anywhere.
     *
     * <p>The gun is kept fed while it burns, and that is not a convenience — it is the subject. A
     * beam with a finite buffer and no supply stops at what its capacitor held, and then "held twice
     * as long" would measure the capacitor rather than the dwell. Fed, what is left to vary is the
     * time on target, which is the thing being claimed.</p>
     */
    @Test
    public void holdingItLongerDigsDeeper() throws Exception {
        buildSite(DWELL_X);
        buildBeamGun(DWELL_X);

        // Deep enough that a SHORT dwell cannot cross it, and made of the toughest ordinary thing
        // there is. The first version was six blocks of stone: the short burn went through all six,
        // both measurements read "6", and the test was reporting the depth of its own wall.
        int wallX = DWELL_X + 12;
        fill(wallX, wallX + WALL_DEPTH - 1, "minecraft:iron_block");
        aimAt(DWELL_X, wallX + WALL_DEPTH + 8);

        int afterShort = burnFor(DWELL_X, 1);
        assertTrue("a beam held on an iron wall cut nothing at all into it: then it is not "
                + "depositing its power into what it is pointed at, and dwell buys nothing. gun="
                + read(DWELL_X), afterShort > 0);

        assertTrue("the short burn already crossed the whole wall (" + afterShort + " of "
            + WALL_DEPTH + "): the measurement is saturated and cannot show a longer one going"
            + " further, whatever production does", afterShort < WALL_DEPTH);

        int afterLong = burnFor(DWELL_X, 4);
        assertTrue("keeping the beam on the same spot four times as long got no further into the "
                + "wall (short=" + afterShort + " long=" + afterLong + "): then depth does not grow "
                + "with dwell and a beam is just a gun with an odd fire rate", afterLong > afterShort);
    }

    /**
     * A starved beam goes DARK and saves up, rather than flickering at whatever rate its feed happens
     * to deliver. The distinction a fire control cannot be built without: "not shooting" and "cannot
     * shoot yet" are different answers, and only one of them means the gun is broken.
     */
    @Test
    public void aStarvedBeamGoesDarkAndSavesUpInsteadOfStuttering() throws Exception {
        buildSite(STARVED_X);
        buildBeamGun(STARVED_X);
        charge(STARVED_X);

        int wallX = STARVED_X + 12;
        fill(wallX, wallX + 5, "minecraft:iron_block");
        aimAt(STARVED_X, wallX + 20);

        // Nothing feeds this gun, so its buffer is all it will ever have: burn it down and the duty
        // cycle is what happens next.
        String state = awaitRecharging(STARVED_X);
        assertTrue("a beam with no feed never went dark: then it either fired on an empty buffer or "
                + "it stuttered on whatever arrived, and neither is a state anything can act on: "
                + state, extract(state, "beamRecharging") == 1);
        // CONTROL, and it is not decoration: an earlier version of this scenario went green against a
        // gun whose buffer was too small to ever hold its own quantum, so it was dark from the first
        // tick and never fired at all. "Went dark" only means anything if it burned first.
        assertTrue("the gun went dark without ever having landed a tick of beam: then this measured a "
                + "weapon that cannot fire, not one that ran its capacitor down: " + state,
                extract(state, "shots") > 0);
        assertTrue("the gun reports itself lit while it is recharging: then the two states are one "
                + "and fire control cannot tell not-shooting from cannot-shoot-yet: " + state,
                extract(state, "beamLit") == 0);
        assertTrue("this gun does not think it is a beam at all, so the run tested a thrower: " + state,
                extract(state, "beamPower") > 0);
    }

    // ---- driving

    /** The reference beam gun: a controller with emitters on it and cooling around it. */
    private void buildBeamGun(int bx) throws Exception {
        place("advancedrocketry:turret", bx, Y, Z);
        for (int i = 1; i <= 3; i++) {
            place("advancedrocketry:gunBeamEmitter", bx, Y + i, Z);
        }
        place("advancedrocketry:gunCooling", bx, Y, Z + 1);
        place("advancedrocketry:gunCooling", bx, Y, Z - 1);
    }

    private void buildSite(int bx) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup " + DIM + " " + ((bx - 16) >> 4)
                + " " + ((Z - 16) >> 4) + " " + ((bx + 64) >> 4) + " " + ((Z + 16) >> 4))
                .contains("\"ok\":true"));
        assertTrue("could not clear the site", exec("artest fill " + DIM + " " + (bx - 4) + " "
                + (Y - 2) + " " + (Z - 4) + " " + (bx + 60) + " " + (Y + 12) + " " + (Z + 4)
                + " minecraft:air").contains("\"ok\":true"));
        assertTrue("could not hold the chunk", exec("artest chunk forceload " + DIM + " " + (bx >> 4)
                + " " + (Z >> 4)).contains("\"ok\":true"));
        for (int cx = (bx >> 4); cx <= ((bx + 40) >> 4); cx++) {
            exec("artest chunk forceload " + DIM + " " + cx + " " + (Z >> 4));
        }
    }

    private void fill(int fromX, int toX, String block) throws Exception {
        assertTrue("could not build the wall", exec("artest fill " + DIM + " " + fromX + " " + Y + " "
                + Z + " " + toX + " " + Y + " " + Z + " " + block).contains("\"ok\":true"));
    }

    private void charge(int bx) throws Exception {
        exec("artest turret charge " + DIM + " " + bx + " " + Y + " " + Z);
    }

    private void aimAt(int bx, int targetX) throws Exception {
        exec("artest turret target " + DIM + " " + bx + " " + Y + " " + Z + " " + (targetX + 0.5D)
                + " " + (Y + 0.5D) + " " + (Z + 0.5D));
    }

    // ---- reading

    /**
     * Keep the gun fed and on target for {@code cycles} charge-and-burn passes, then report how deep
     * the hole is. The feed stands in for the ship supply this scenario deliberately does not build:
     * without it the measurement would be of the capacitor, not of the dwell.
     */
    private int burnFor(int bx, int cycles) throws Exception {
        for (int i = 0; i < cycles; i++) {
            charge(bx);
            Thread.sleep(1_400L);
        }
        return depthOf(bx + 12);
    }

    /** How many blocks of the wall are gone or marked. */
    private int depthOf(int wallX) throws Exception {
        int depth = 0;
        for (int i = 0; i < WALL_DEPTH; i++) {
            String state = exec("artest damage stage " + DIM + " " + (wallX + i) + " " + Y + " " + Z);
            if (state.contains("\"wasDestroyed\":true") || state.contains("\"block\":\"minecraft:air\"")
                    || extract(state, "stage") > 0) {
                depth = i + 1;
            }
        }
        return depth;
    }

    private String read(int bx) throws Exception {
        return exec("artest turret read " + DIM + " " + bx + " " + Y + " " + Z);
    }

    /** Wait until the gun reports itself dark and saving, or give up and report what it does say. */
    private String awaitRecharging(int bx) throws Exception {
        long deadline = System.currentTimeMillis() + 25_000L;
        String state = "";
        while (System.currentTimeMillis() < deadline) {
            state = exec("artest turret read " + DIM + " " + bx + " " + Y + " " + Z);
            if (extract(state, "beamRecharging") == 1) {
                return state;
            }
            Thread.sleep(150L);
        }
        return state;
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
