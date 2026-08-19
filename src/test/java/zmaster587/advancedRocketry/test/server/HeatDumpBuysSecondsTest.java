package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The emergency dump: heat leaves the ship inside a lump of matter that is thrown overboard.
 *
 * <p>What is pinned is the SHAPE of the bargain rather than any rate. The dump takes heat off the
 * loop only once the ship is already losing, it charges the material it was given, and the charged
 * lump goes out of the port carrying the energy with it - so the loop is genuinely colder and the
 * matter is genuinely gone. A dump that ran while the ship was coping would be a cooling system, and
 * the contract forbids exactly that.</p>
 */
public class HeatDumpBuysSecondsTest extends AbstractSharedServerTest {

    private static final int Y = 70;
    private static final int Z = 3500;
    private static final int X_HOT = 2300;
    private static final int X_COLD = 2340;

    private static final int PIPES = 3;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static long field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(-?\\d+)").matcher(json);
        assertTrue("expected a numeric field " + name + " in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    /** A loop with a dump bolted to its end, loaded with a block of iron and powered. */
    private void buildRig(int cx) throws Exception {
        exec("artest fill 0 " + (cx - 2) + " " + (Y - 1) + " " + (Z - 2)
                + " " + (cx + PIPES + 12) + " " + (Y + 2) + " " + (Z + 2) + " minecraft:air");
        for (int i = 0; i < PIPES; i++) {
            String placed = exec("artest place 0 " + (cx + i) + " " + Y + " " + Z
                    + " advancedrocketry:heatPipe");
            assertTrue("pipe place failed: " + placed, placed.contains("\"placed\":true"));
        }
        String dump = exec("artest place 0 " + (cx + PIPES) + " " + Y + " " + Z
                + " advancedrocketry:heatDump");
        assertTrue("dump place failed: " + dump, dump.contains("\"placed\":true"));
        String energy = exec("artest energy inject 0 " + (cx + PIPES) + " " + Y + " " + Z + " 1000000");
        assertTrue("the dump must have power: " + energy, energy.contains("\"ok\":true"));
        String loaded = exec("artest heat dump 0 " + (cx + PIPES) + " " + Y + " " + Z
                + " load minecraft:iron_block");
        assertTrue("the dump must be loaded with something to charge: " + loaded,
                loaded.contains("\"hasStack\":true"));
        exec("artest subnet solve all 0 1");
    }

    private String dumpInfo(int cx) throws Exception {
        return exec("artest heat dump 0 " + (cx + PIPES) + " " + Y + " " + Z);
    }

    /** Charge the loop to a stated temperature and advance the domain in one call. */
    private String cycle(int cx, long kelvin, int ticks) throws Exception {
        String empty = exec("artest heat cycle 0 " + cx + " " + Y + " " + Z + " 0 1");
        long capacity = field(empty, "heatCapacity");
        assertTrue("premise: the loop must have thermal mass: " + empty, capacity > 0);
        int ambient = (int) field(exec("artest config get shipHeatAmbientKelvin"), "value");
        long charge = (kelvin - ambient) * capacity;
        return exec("artest heat cycle 0 " + cx + " " + Y + " " + Z + " " + charge + " " + ticks);
    }

    @Test
    public void aLoopPastTheTriggerLosesHeatIntoTheSlugAndThrowsItOut() throws Exception {
        buildRig(X_HOT);
        long trigger = field(exec("artest config get shipHeatDumpTriggerKelvin"), "value");
        String before = dumpInfo(X_HOT);
        assertEquals("premise: the dump must start holding the material it was given: " + before,
                0L, field(before, "charge"));
        assertTrue("premise: and that material must be able to take heat at all: " + before,
                field(before, "headroom") > 0);

        String cooked = cycle(X_HOT, trigger + 200, 2);

        String after = dumpInfo(X_HOT);
        // Either the slug is holding charge, or it filled and was thrown out - both are the rung
        // working, and telling them apart is what `hasStack` is for.
        assertTrue("the dump must have taken heat off the loop and put it in the slug: " + after,
                field(after, "charge") > 0 || !after.contains("\"hasStack\":true"));
        assertTrue("and the loop must be poorer by what left it: " + cooked,
                field(cooked, "sunk") > 0);
    }

    @Test
    public void aShipThatIsCopingThrowsNothingAway() throws Exception {
        buildRig(X_COLD);
        long trigger = field(exec("artest config get shipHeatDumpTriggerKelvin"), "value");

        String cooked = cycle(X_COLD, trigger - 200, 2);

        String after = dumpInfo(X_COLD);
        assertEquals("below the trigger the dump must do nothing at all - it is an emergency, not a"
                + " cooling system: " + after, 0L, field(after, "charge"));
        assertEquals("and the loop must lose nothing to it: " + cooked, 0L, field(cooked, "sunk"));
    }
}
