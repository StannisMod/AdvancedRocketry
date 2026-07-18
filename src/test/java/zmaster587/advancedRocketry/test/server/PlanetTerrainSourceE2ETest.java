package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A planet dimension's chunk generator is chosen by its {@code terrainSource}, while in every mode the
 * dimension stays a {@link zmaster587.advancedRocketry.world.provider.WorldProviderPlanet} and keeps its
 * Advanced Rocketry atmosphere and gravity — those are dim-keyed and orthogonal to the chunk generator.
 *
 * <p>Each case clones an existing AR planet into a fresh dimension with a flipped terrainSource via the
 * {@code /artest worldgen create-terrain-dim} probe, loads it, and reads {@code dim info}:</p>
 * <ul>
 *   <li>MOD_WORLDTYPE {@code flat} dispatches to the foreign {@code ChunkGeneratorFlat}, yet the provider
 *       stays WorldProviderPlanet and gravity is preserved.</li>
 *   <li>TEMPLATE with no source region files dispatches to {@code ChunkProviderTemplate} and yields a void
 *       world (no stone across the scanned region).</li>
 *   <li>MOD_WORLDTYPE with an unregistered world-type name degrades to the native AR generator instead of
 *       crashing.</li>
 * </ul>
 */
public class PlanetTerrainSourceE2ETest extends AbstractSharedServerTest {

    // Below Constants.STAR_ID_OFFSET (10000): at/above it, DimensionManager.getDimensionProperties treats
    // the id as a star proxy and returns the overworld props, so a real planet dim must live under it.
    private static final int MOD_WT_DIM = 9990;
    private static final int TEMPLATE_DIM = 9991;
    private static final int FALLBACK_DIM = 9992;

    private static final String AR_PLANET_PROVIDER =
            "\"providerClass\":\"zmaster587.advancedRocketry.world.provider.WorldProviderPlanet\"";

    private static final Pattern AR_DIMS_ARRAY = Pattern.compile("\"arDimensions\":\\[([^]]*)]");
    private static final Pattern GRAVITY = Pattern.compile("\"gravity\":([0-9.eE+-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(\\d+)");

    @Test
    public void modWorldtypeUsesForeignGeneratorButStaysAnArPlanet() throws Exception {
        int template = firstTemplateArDimOrSkip();
        String create = exec("artest worldgen create-terrain-dim "
                + MOD_WT_DIM + " " + template + " MOD_WORLDTYPE flat");
        assertTrue("create-terrain-dim must succeed: " + create, create.contains("\"ok\":true"));

        exec("artest dim load " + MOD_WT_DIM);
        String info = exec("artest dim info " + MOD_WT_DIM);

        assertTrue("the loaded world must see the authored terrainSource: " + info,
                info.contains("\"terrainSource\":\"MOD_WORLDTYPE\""));
        assertTrue("MOD_WORLDTYPE dim must stay a WorldProviderPlanet: " + info,
                info.contains(AR_PLANET_PROVIDER));
        assertTrue("MOD_WORLDTYPE 'flat' must dispatch to the foreign ChunkGeneratorFlat: " + info,
                info.contains("ChunkGeneratorFlat"));

        // Orthogonality: AR gravity is dim-keyed, so it must survive under the foreign generator.
        Matcher g = GRAVITY.matcher(info);
        assertTrue("dim info must report gravity: " + info, g.find());
        assertTrue("AR gravity must be preserved under MOD_WORLDTYPE, got " + g.group(1),
                Float.parseFloat(g.group(1)) > 0f);
    }

    @Test
    public void templateWithNoSourceGeneratesVoidViaTemplateGenerator() throws Exception {
        int template = firstTemplateArDimOrSkip();
        String create = exec("artest worldgen create-terrain-dim "
                + TEMPLATE_DIM + " " + template + " TEMPLATE noSuchTemplate");
        assertTrue("create-terrain-dim must succeed: " + create, create.contains("\"ok\":true"));

        exec("artest dim load " + TEMPLATE_DIM);
        String info = exec("artest dim info " + TEMPLATE_DIM);
        assertTrue("the loaded world must see the authored terrainSource: " + info,
                info.contains("\"terrainSource\":\"TEMPLATE\""));
        assertTrue("TEMPLATE dim must stay a WorldProviderPlanet: " + info,
                info.contains(AR_PLANET_PROVIDER));
        assertTrue("TEMPLATE must dispatch to ChunkProviderTemplate: " + info,
                info.contains("zmaster587.advancedRocketry.world.ChunkProviderTemplate"));

        // No source region files -> void: the scanned region has no fill (stone) blocks.
        String stats = exec("artest worldgen ore-stats " + TEMPLATE_DIM + " 0 0 2 minecraft:stone");
        Matcher m = COUNT.matcher(stats);
        assertTrue("ore-stats must report a count: " + stats, m.find());
        int count = Integer.parseInt(m.group(1));
        assertTrue("a TEMPLATE dim with no source region files must generate a void world "
                + "(0 stone across the scanned region); count=" + count + " stats=" + stats, count == 0);
    }

    @Test
    public void unregisteredModWorldtypeFallsBackToNativeGenerator() throws Exception {
        int template = firstTemplateArDimOrSkip();
        String create = exec("artest worldgen create-terrain-dim "
                + FALLBACK_DIM + " " + template + " MOD_WORLDTYPE definitelyNotAWorldType");
        assertTrue("create-terrain-dim must succeed: " + create, create.contains("\"ok\":true"));

        exec("artest dim load " + FALLBACK_DIM);
        String info = exec("artest dim info " + FALLBACK_DIM);

        assertTrue("fallback dim must stay a WorldProviderPlanet: " + info,
                info.contains(AR_PLANET_PROVIDER));
        assertTrue("an unregistered MOD_WORLDTYPE name must fall back to a native AR generator: " + info,
                info.contains("advancedRocketry.world.ChunkProviderPlanet")
                        || info.contains("advancedRocketry.world.ChunkProviderCavePlanet"));
        assertFalse("the fallback must NOT use the foreign flat generator: " + info,
                info.contains("ChunkGeneratorFlat"));
    }

    /** A registered non-overworld AR planet to clone, excluding the dims this test creates. */
    private int firstTemplateArDimOrSkip() throws Exception {
        String joined = exec("artest dim list");
        Assume.assumeFalse("No AR dimensions registered — skipping",
                joined.contains("\"arDimensions\":[]"));
        Matcher m = AR_DIMS_ARRAY.matcher(joined);
        assertTrue("could not parse arDimensions: " + joined, m.find());
        for (String part : m.group(1).split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            int dim = Integer.parseInt(t);
            if (dim != 0 && dim != MOD_WT_DIM && dim != TEMPLATE_DIM && dim != FALLBACK_DIM) return dim;
        }
        Assume.assumeTrue("Only overworld registered — skipping", false);
        return -1;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
