package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract coverage for {@link zmaster587.advancedRocketry.util.WeightEngine}
 * exercised against real (registered) blocks and fluids in a booted server.
 *
 * <p>These tests pin the <em>contracts</em> of the weight resolution chain, not
 * the exact kN constants in the default material table:</p>
 *
 * <ul>
 *   <li>heavier materials resolve to a larger weight than lighter ones;</li>
 *   <li>stack count multiplies the per-item weight;</li>
 *   <li>resolution precedence: individual override &gt; regex &gt; material;</li>
 *   <li>{@code weightMaterialScale} scales material-derived weights;</li>
 *   <li>fluid weight uses the fallback per-mB rate and {@code fuelMassScale}.</li>
 * </ul>
 *
 * <p>Every method calls {@code /artest weight reset} first so the shared
 * WeightEngine singleton + the two scale config keys start from defaults
 * (see {@link AbstractSharedServerTest} state-leak contract).</p>
 */
public class WeightSystemTest extends AbstractSharedServerTest {

    private static final Pattern WEIGHT = Pattern.compile("\"weight\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)");

    private void reset() throws Exception {
        String r = String.join("\n", client().execute("artest weight reset"));
        assertTrue("weight reset failed: " + r, r.contains("\"ok\":true"));
    }

    private double itemWeight(String id, int count) throws Exception {
        String r = String.join("\n", client().execute("artest weight item " + id + " " + count));
        assertTrue("item " + id + " not registered: " + r, r.contains("\"registered\":true"));
        Matcher m = WEIGHT.matcher(r);
        assertTrue("no weight field for " + id + ": " + r, m.find());
        return Double.parseDouble(m.group(1));
    }

    private double fluidWeight(String name, int amount) throws Exception {
        String r = String.join("\n", client().execute("artest weight fluid " + name + " " + amount));
        assertTrue("fluid " + name + " not registered: " + r, r.contains("\"registered\":true"));
        Matcher m = WEIGHT.matcher(r);
        assertTrue("no weight field for fluid " + name + ": " + r, m.find());
        return Double.parseDouble(m.group(1));
    }

    @Test
    public void heavierMaterialsWeighMore() throws Exception {
        reset();
        double iron = itemWeight("minecraft:iron_block", 1);   // Material.IRON
        double stone = itemWeight("minecraft:stone", 1);       // Material.ROCK
        double glass = itemWeight("minecraft:glass", 1);       // Material.GLASS
        double wool = itemWeight("minecraft:wool", 1);         // Material.CLOTH

        assertTrue("all material weights must be positive", iron > 0 && stone > 0 && glass > 0 && wool > 0);
        assertTrue("iron must be heavier than stone (" + iron + " vs " + stone + ")", iron > stone);
        assertTrue("stone must be heavier than glass (" + stone + " vs " + glass + ")", stone > glass);
        assertTrue("glass must be at least as heavy as wool (" + glass + " vs " + wool + ")", glass >= wool);
    }

    @Test
    public void stackCountMultipliesWeight() throws Exception {
        reset();
        double one = itemWeight("minecraft:iron_block", 1);
        double four = itemWeight("minecraft:iron_block", 4);
        assertEquals("weight must scale linearly with stack count", 4 * one, four, 1e-4);
    }

    @Test
    public void individualOverrideBeatsMaterial() throws Exception {
        reset();
        double material = itemWeight("minecraft:stone", 1);
        assertTrue("baseline material weight must differ from the override sentinel", material != 99.0);

        String set = String.join("\n", client().execute("artest weight set minecraft:stone 99.0"));
        assertTrue("weight set failed: " + set, set.contains("\"ok\":true"));

        assertEquals("explicit individual override must win over the material table",
                99.0, itemWeight("minecraft:stone", 1), 1e-4);
    }

    @Test
    public void regexBeatsMaterialButIndividualBeatsRegex() throws Exception {
        reset();
        String reg = String.join("\n", client().execute("artest weight set-regex minecraft:gla.* 3.0"));
        assertTrue("set-regex failed: " + reg, reg.contains("\"ok\":true"));
        assertEquals("regex rule must win over the material table",
                3.0, itemWeight("minecraft:glass", 1), 1e-4);

        String set = String.join("\n", client().execute("artest weight set minecraft:glass 50.0"));
        assertTrue("weight set failed: " + set, set.contains("\"ok\":true"));
        assertEquals("individual override must win over a matching regex rule",
                50.0, itemWeight("minecraft:glass", 1), 1e-4);
    }

    @Test
    public void materialScaleScalesMaterialWeights() throws Exception {
        reset();
        double base = itemWeight("minecraft:stone", 1);

        String sc = String.join("\n", client().execute("artest weight material-scale 2.0"));
        assertTrue("material-scale failed: " + sc, sc.contains("\"ok\":true"));

        assertEquals("material weight must scale by weightMaterialScale",
                2 * base, itemWeight("minecraft:stone", 1), 1e-4);
    }

    @Test
    public void fluidWeightUsesFallbackAndFuelScale() throws Exception {
        reset();
        double base = fluidWeight("water", 1000);
        assertTrue("fluid weight must be positive: " + base, base > 0);

        String sc = String.join("\n", client().execute("artest weight fuel-scale 2.0"));
        assertTrue("fuel-scale failed: " + sc, sc.contains("\"ok\":true"));

        assertEquals("fluid weight must scale by fuelMassScale",
                2 * base, fluidWeight("water", 1000), 1e-4);
    }
}
