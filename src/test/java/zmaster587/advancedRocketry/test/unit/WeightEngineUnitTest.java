package zmaster587.advancedRocketry.test.unit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import org.junit.Test;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.util.WeightEngine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * MC-free unit coverage for {@link WeightEngine}: the parts that don't need a
 * block/item registry — fluid weight arithmetic, the JSON table round-trip, and
 * default seeding. Block/material resolution (which needs real ItemStacks) is
 * covered by the server-tier {@code WeightSystemTest}.
 */
public class WeightEngineUnitTest {

    private static Fluid testFluid() {
        ResourceLocation tex = new ResourceLocation("advancedrocketry", "blocks/unit_fluid");
        return new Fluid("ar_unit_fluid", tex, tex);
    }

    @Test
    public void fluidWeightIsPositiveAndLinearInAmount() {
        WeightEngine we = WeightEngine.INSTANCE;
        we.resetTables();
        double prevScale = ARConfiguration.getCurrentConfig().fuelMassScale;
        try {
            ARConfiguration.getCurrentConfig().fuelMassScale = 1.0;
            // An unknown fluid still weighs something (the fallback per-mB rate)
            // and the weight is linear in the amount. The exact kN/mB constant is
            // an implementation default.
            float base = we.getWeight(testFluid(), 1000f);
            assertTrue("fallback fluid weight must be positive: " + base, base > 0);
            assertEquals("fluid weight must be linear in the amount",
                    2 * base, we.getWeight(testFluid(), 2000f), 1e-4);
        } finally {
            ARConfiguration.getCurrentConfig().fuelMassScale = prevScale;
        }
    }

    @Test
    public void fuelMassScaleMultipliesFluidWeight() {
        WeightEngine we = WeightEngine.INSTANCE;
        we.resetTables();
        double prevScale = ARConfiguration.getCurrentConfig().fuelMassScale;
        try {
            ARConfiguration.getCurrentConfig().fuelMassScale = 1.0;
            float base = we.getWeight(testFluid(), 1000f);

            ARConfiguration.getCurrentConfig().fuelMassScale = 2.5;
            assertEquals("fluid weight must scale by fuelMassScale",
                    2.5f * base, we.getWeight(testFluid(), 1000f), 1e-4);
        } finally {
            ARConfiguration.getCurrentConfig().fuelMassScale = prevScale;
        }
    }

    @Test
    public void seedDefaultsPopulatesMaterialTable() {
        WeightEngine we = WeightEngine.INSTANCE;
        we.resetTables();
        assertTrue("default material table must be populated", we.materialCount() > 10);
    }

    @Test
    public void individualOverrideSurvivesSaveLoadRoundTrip() {
        WeightEngine we = WeightEngine.INSTANCE;
        try {
            we.resetTables();
            assertNull("clean slate must not know the test key", we.rawIndividual("ar:roundtrip_probe"));

            we.setIndividual("ar:roundtrip_probe", 42.0);
            we.save();

            // Wipe in-memory state, then reload from the file just written.
            we.resetTables();
            assertNull("resetTables must drop the in-memory override", we.rawIndividual("ar:roundtrip_probe"));

            we.load();
            assertEquals("override must persist across save/load",
                    Double.valueOf(42.0), we.rawIndividual("ar:roundtrip_probe"));
        } finally {
            // Leave no residue in the on-disk config for other tests.
            we.resetTables();
            we.save();
        }
    }

    // ---- The whole file, not one column of it -------------------------------

    /** Where the engine keeps the pack-editable table. Relative, like every other path here. */
    private static final File CONFIG = new File("config/advRocketry/weights.json");

    /**
     * A column the engine READS is a column a pack may write, and {@code save()} rewrites the whole
     * file — so a column that is loaded and not saved is a column the pack silently loses the first
     * time anything saves. This walks every column through the file rather than picking one, because
     * the columns that go missing are by definition the ones nobody remembered to add to a list.
     *
     * <p>Two ablation columns were lost exactly this way: read by {@code load()}, absent from
     * {@code save()}, invisible until a pack's hand-written rows evaporated.</p>
     */
    @Test
    public void everyColumnAPackCanWriteSurvivesASave() throws Exception {
        WeightEngine we = WeightEngine.INSTANCE;
        try {
            writeConfig("{\n"
                    + "  \"individual\": {\"ar:probe\": 1.5},\n"
                    + "  \"byRegex\": {\"ar:probe.*\": 2.5},\n"
                    + "  \"fluids\": {\"ar_probe_fluid\": 0.5},\n"
                    + "  \"materials\": {\"IRON\": 3.5},\n"
                    + "  \"fallback\": 4.5,\n"
                    + "  \"fluidFallback\": 5.5,\n"
                    + "  \"toughnessIndividual\": {\"ar:probe\": 6.5},\n"
                    + "  \"toughnessByRegex\": {\"ar:probe.*\": 7.5},\n"
                    + "  \"toughnessMaterials\": {\"IRON\": 8.5},\n"
                    + "  \"toughnessFallback\": 9.5,\n"
                    + "  \"ablationIndividual\": {\"ar:probe\": 10.5},\n"
                    + "  \"ablationByRegex\": {\"ar:probe.*\": 11.5}\n"
                    + "}\n");

            we.load();
            we.save();

            JsonObject saved = readConfig();
            List<String> lost = new ArrayList<String>();
            assertRow(saved, "individual", "ar:probe", 1.5, lost);
            assertRow(saved, "byRegex", "ar:probe.*", 2.5, lost);
            assertRow(saved, "fluids", "ar_probe_fluid", 0.5, lost);
            assertRow(saved, "materials", "IRON", 3.5, lost);
            assertScalar(saved, "fallback", 4.5, lost);
            assertScalar(saved, "fluidFallback", 5.5, lost);
            assertRow(saved, "toughnessIndividual", "ar:probe", 6.5, lost);
            assertRow(saved, "toughnessByRegex", "ar:probe.*", 7.5, lost);
            assertRow(saved, "toughnessMaterials", "IRON", 8.5, lost);
            assertScalar(saved, "toughnessFallback", 9.5, lost);
            assertRow(saved, "ablationIndividual", "ar:probe", 10.5, lost);
            assertRow(saved, "ablationByRegex", "ar:probe.*", 11.5, lost);

            assertTrue("these hand-written config values did not survive one save/load cycle, so a "
                    + "pack that edits them loses them the first time the game writes the file: "
                    + lost, lost.isEmpty());
        } finally {
            we.resetTables();
            we.save();
        }
    }

    /**
     * Regex columns are first-match-wins, so the order a pack writes its patterns in IS the
     * precedence between two patterns that both match. A column deserialised into an unordered map
     * answers a different question after a reload than the one the pack asked.
     */
    @Test
    public void aRegexColumnKeepsThePackSOrderAcrossASave() throws Exception {
        WeightEngine we = WeightEngine.INSTANCE;
        try {
            // Deliberately not alphabetical and not hash order: three overlapping patterns whose
            // meaning is entirely decided by which one is tried first.
            writeConfig("{\n"
                    + "  \"ablationByRegex\": {\"ar:zulu.*\": 1.0, \"ar:.*\": 2.0, \"ar:alpha.*\": 3.0},\n"
                    + "  \"toughnessByRegex\": {\"ar:zulu.*\": 1.0, \"ar:.*\": 2.0, \"ar:alpha.*\": 3.0}\n"
                    + "}\n");

            we.load();
            we.save();

            JsonObject saved = readConfig();
            List<String> expected = Arrays.asList("ar:zulu.*", "ar:.*", "ar:alpha.*");
            for (String column : new String[]{"ablationByRegex", "toughnessByRegex"}) {
                assertEquals("first-match-wins makes pattern order the precedence rule, and "
                                + column + " came back reordered",
                        expected, keysInOrder(saved.getAsJsonObject(column)));
            }
        } finally {
            we.resetTables();
            we.save();
        }
    }

    /**
     * {@code resetTables} is the clean slate — both the test hook and the branch the engine takes
     * when a config file cannot be read. A column it forgets keeps the previous load's rows, so a
     * broken config silently inherits half of the file it failed to parse.
     */
    @Test
    public void resettingTheTablesLeavesNoColumnBehind() throws Exception {
        WeightEngine we = WeightEngine.INSTANCE;
        try {
            writeConfig("{\n"
                    + "  \"individual\": {\"ar:probe\": 1.5},\n"
                    + "  \"toughnessIndividual\": {\"ar:probe\": 6.5},\n"
                    + "  \"ablationIndividual\": {\"ar:probe\": 10.5},\n"
                    + "  \"ablationByRegex\": {\"ar:probe.*\": 11.5}\n"
                    + "}\n");
            we.load();

            we.resetTables();
            we.save();

            JsonObject saved = readConfig();
            List<String> survivors = new ArrayList<String>();
            for (String column : new String[]{"individual", "toughnessIndividual",
                    "ablationIndividual", "ablationByRegex"}) {
                // Present-and-empty, not merely absent: a column that save() drops altogether would
                // otherwise read as "reset worked", which is how this test could pass while the
                // clean slate left every ablation row standing in memory.
                assertTrue("save() must write column " + column + ", or this test cannot see whether "
                        + "the reset cleared it", saved.has(column));
                if (saved.getAsJsonObject(column).entrySet().size() != 0) {
                    survivors.add(column + " -> " + saved.getAsJsonObject(column));
                }
            }
            assertTrue("a reset must leave no column carrying the previous load's rows, and these "
                    + "still do: " + survivors, survivors.isEmpty());
        } finally {
            we.resetTables();
            we.save();
        }
    }

    private static void writeConfig(String json) throws IOException {
        File parent = CONFIG.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(CONFIG), StandardCharsets.UTF_8)) {
            w.write(json);
        }
    }

    private static JsonObject readConfig() throws IOException {
        try (Reader r = new InputStreamReader(new FileInputStream(CONFIG), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(r, JsonObject.class);
        }
    }

    /** This Gson has no {@code keySet()}; {@code entrySet()} keeps insertion order all the same. */
    private static List<String> keysInOrder(JsonObject column) {
        List<String> keys = new ArrayList<String>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : column.entrySet()) {
            keys.add(e.getKey());
        }
        return keys;
    }

    private static void assertRow(JsonObject saved, String column, String key, double value,
                                  List<String> lost) {
        if (!saved.has(column) || !saved.getAsJsonObject(column).has(key)
                || Math.abs(saved.getAsJsonObject(column).get(key).getAsDouble() - value) > 1e-9) {
            lost.add(column + "[" + key + "]");
        }
    }

    private static void assertScalar(JsonObject saved, String key, double value, List<String> lost) {
        if (!saved.has(key) || Math.abs(saved.get(key).getAsDouble() - value) > 1e-9) {
            lost.add(key);
        }
    }
}
