package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The failure ladder's last rung: past its material's own limit a block stops being damaged and is
 * gone.
 *
 * <p>Every scenario runs the real thing - a real coolant loop, charged through the same verb the
 * physics tests use, sweeping on the domain's own tick. What is asserted is the RULE (the substance
 * decides, and only past its ceiling) rather than any temperature, and the ceiling is read off the
 * probe rather than named here.</p>
 *
 * <p>The control leg is what makes the subject a measurement: the identical rig with a cooler loop
 * must leave the same block standing. Without it, "the stone is gone" is also what a test that never
 * placed the stone looks like.</p>
 */
public class HullMeltsPastItsMaterialTest extends AbstractSharedServerTest {

    private static final int Y = 70;
    private static final int Z = 3400;
    private static final int X_MELT = 2100;
    private static final int X_COLD = 2140;
    private static final int X_UNKNOWN = 2180;
    private static final int X_SELF = 2220;

    /** Three pipes is enough loop to charge; the block under test stands against the middle one. */
    private static final int PIPES = 3;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static long field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(-?\\d+)").matcher(json);
        assertTrue("expected a numeric field " + name + " in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static String text(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        assertTrue("expected a text field " + name + " in: " + json, m.find());
        return m.group(1);
    }

    /** A run of pipe, with one block of {@code victim} standing against its middle, in clear air. */
    private void buildRig(int cx, String victim) throws Exception {
        exec("artest fill 0 " + (cx - 2) + " " + (Y - 1) + " " + (Z - 2)
                + " " + (cx + PIPES + 2) + " " + (Y + 2) + " " + (Z + 2) + " minecraft:air");
        for (int i = 0; i < PIPES; i++) {
            String placed = exec("artest place 0 " + (cx + i) + " " + Y + " " + Z
                    + " advancedrocketry:heatPipe");
            assertTrue("pipe place failed: " + placed, placed.contains("\"placed\":true"));
        }
        String victimPlaced = exec("artest place 0 " + (cx + 1) + " " + (Y + 1) + " " + Z
                + " " + victim);
        assertTrue("victim place failed: " + victimPlaced, victimPlaced.contains("\"placed\":true"));
        String solved = exec("artest subnet solve all 0 1");
        assertTrue("the loop never solved: " + solved, solved.contains("\"ticksSolved\":1"));
    }

    private String blockAbove(int cx) throws Exception {
        return exec("artest heat material 0 " + (cx + 1) + " " + (Y + 1) + " " + Z);
    }

    /** Charge the loop to a temperature and let the domain tick, with the sweep on every tick. */
    private void cookAt(int cx, long kelvin) throws Exception {
        String set = exec("artest config set shipHeatMeltCheckTicks 1");
        assertTrue("could not pace the sweep: " + set, set.contains("\"ok\":true"));
        String empty = exec("artest heat cycle 0 " + cx + " " + Y + " " + Z + " 0 1");
        long capacity = field(empty, "heatCapacity");
        assertTrue("premise: the loop must have thermal mass: " + empty, capacity > 0);
        int ambient = (int) field(exec("artest config get shipHeatAmbientKelvin"), "value");
        long charge = (kelvin - ambient) * capacity;
        String cooked = exec("artest heat cycle 0 " + cx + " " + Y + " " + Z + " " + charge + " 2");
        // The readback can legitimately find no loop: at a temperature past the PIPES' own material
        // the first swept tick takes them, which is the self-consumption scenario. Only a loop that
        // still exists is required to read the temperature it was charged to.
        if (cooked.contains("\"inLoop\":true")) {
            assertTrue("premise: the loop must actually have reached " + kelvin + " K: " + cooked,
                    field(cooked, "temperatureMilliK") >= (kelvin - 1) * 1000L);
        }
    }

    @Test
    public void aLoopPastTheMaterialsCeilingTakesTheBlockAndLeavesLava() throws Exception {
        buildRig(X_MELT, "minecraft:stone");
        String before = blockAbove(X_MELT);
        long ceiling = field(before, "ceilingKelvin");
        assertTrue("premise: the victim must be a substance with a limit: " + before, ceiling > 0);
        assertEquals("premise: and it must still be stone before anything is cooked: " + before,
                "minecraft:stone", text(before, "block"));

        cookAt(X_MELT, ceiling + 200);

        String after = blockAbove(X_MELT);
        assertEquals("past its own limit the block is not damaged, it is gone - and rock leaves lava"
                + " behind: " + after, "minecraft:lava", text(after, "block"));
    }

    @Test
    public void theSameRigBelowTheCeilingLeavesTheBlockStanding() throws Exception {
        buildRig(X_COLD, "minecraft:stone");
        long ceiling = field(blockAbove(X_COLD), "ceilingKelvin");

        cookAt(X_COLD, ceiling - 200);

        String after = blockAbove(X_COLD);
        assertEquals("below the limit the rung must not fire at all - a block is lost at a"
                + " temperature, not at a mood: " + after, "minecraft:stone", text(after, "block"));
    }

    /**
     * Found by the bedrock scenario rather than planned: past the pipes' OWN material the loop is
     * what melts, and there is nothing left to cook anything else with. The design says an overheated
     * loop eats its own pipes first, and this is that, arrived at from the other direction.
     */
    @Test
    public void aLoopPastItsOwnMaterialConsumesItself() throws Exception {
        buildRig(X_SELF, "minecraft:stone");
        String pipe = exec("artest heat material 0 " + X_SELF + " " + Y + " " + Z);
        long pipeCeiling = field(pipe, "ceilingKelvin");
        assertTrue("premise: the pipes must be made of something with a limit: " + pipe,
                pipeCeiling > 0);

        cookAt(X_SELF, pipeCeiling + 500);

        String after = exec("artest subnet info heat 0 " + X_SELF + " " + Y + " " + Z);
        assertTrue("a loop hotter than its own pipes has no pipes: " + after,
                after.contains("\"members\":0") || after.contains("\"error\""));
    }

    /**
     * A substance the table cannot name has no ceiling, and a rung with no threshold must not act.
     * This is what stops the mechanic eating a modded machine nobody described.
     */
    @Test
    public void aSubstanceNobodyDescribedIsNeverMelted() throws Exception {
        buildRig(X_UNKNOWN, "minecraft:bedrock");
        String before = blockAbove(X_UNKNOWN);
        assertEquals("premise: the fixture must actually stand on the unknown block: " + before,
                "minecraft:bedrock", text(before, "block"));

        // Hot enough that stone would be gone twice over, and still under what the loop's OWN pipes
        // survive - at 5000 K the pipes melt first and there is no loop left to run the sweep, which
        // is a different scenario and is the one below.
        cookAt(X_UNKNOWN, 1600);

        String after = blockAbove(X_UNKNOWN);
        assertEquals("with no ceiling there is no threshold to cross: " + after,
                "minecraft:bedrock", text(after, "block"));
    }
}
