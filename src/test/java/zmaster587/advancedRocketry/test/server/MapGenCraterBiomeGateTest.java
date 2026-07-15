package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Repro (bug-report-workflow, finding C037) for the crater-spawn biome-weight gate.
 *
 * <p>{@code MapGenCrater.recursiveGenerate} (and its sibling {@code MapGenCraterSmall})
 * had the spawn condition {@code A || B && shouldCraterSpawn(...)}. Because {@code &&}
 * binds tighter than {@code ||}, it parsed as {@code A || (B && gate)}, so the per-biome
 * crater-weight gate was bypassed whenever the chunkX RNG disjunct (A) matched — craters
 * spawned in biomes with no crater weight. The fix parenthesizes to {@code (A || B) &&
 * gate} in both generators.</p>
 *
 * <p>The {@code worldgen crater-gate} probe drives the REAL {@code MapGenCrater.generate}
 * on a stone-filled ChunkPrimer with the dim's crater gate forced FALSE (a single
 * weight-0 {@code BiomeEntry} — non-empty so {@code shouldCraterSpawn}'s empty→true
 * shortcut is skipped, and weight 0 never passes {@code itemWeight > rand.nextInt(99)}),
 * with {@code chancePerChunk=1} so the chunkX disjunct is always true. It then counts
 * excavated air. Corrected contract: with the gate false, NO crater is carved (0
 * excavated air). The buggy precedence bypasses the gate and drills air holes (&gt;0).
 * Server-tier (worldgen); no client surface. Runs on a registered planet dim (a valid
 * biome/topBlock, which the crater's ridge placement dereferences).</p>
 */
public class MapGenCraterBiomeGateTest extends AbstractHeadlessServerTest {

    private static final int SPACE_DIM = -2;
    private static final Pattern AR_DIMS = Pattern.compile("\"arDimensions\":\\[([^\\]]*)\\]");
    private static final Pattern AIR = Pattern.compile("\"airBlocks\":(\\d+)");

    @Test
    public void craterGateRejectsNonCraterBiome() throws Exception {
        assertGateRejects("");   // MapGenCrater
    }

    @Test
    public void smallCraterGateRejectsNonCraterBiome() throws Exception {
        assertGateRejects("small");   // MapGenCraterSmall (same precedence bug)
    }

    private void assertGateRejects(String generatorArg) throws Exception {
        int dim = firstPlanetDim();
        Assume.assumeTrue("needs a registered AR planet dim (valid biome/topBlock)",
                dim != Integer.MIN_VALUE);
        exec("artest dim load " + dim);

        String r = exec(("artest worldgen crater-gate " + dim + " " + generatorArg).trim());
        assertTrue("probe must run: " + r, r.contains("\"ok\":true"));

        int air = extract(AIR, r);
        assertTrue("C037: with the per-biome crater gate FALSE, the fixed (A||B)&&gate carves NO "
                        + "crater into the stone-filled chunk (0 excavated air). The buggy A||(B&&gate) "
                        + "bypasses the gate when the chunkX disjunct matches and drills air holes (>0). "
                        + "generator=" + (generatorArg.isEmpty() ? "crater" : generatorArg)
                        + " got airBlocks=" + air + " in " + r,
                air == 0);
    }

    private int firstPlanetDim() throws Exception {
        String list = exec("artest dim list");
        Matcher m = AR_DIMS.matcher(list);
        if (m.find()) {
            for (String s : m.group(1).split(",")) {
                s = s.trim();
                if (s.isEmpty()) continue;
                int d = Integer.parseInt(s);
                if (d != 0 && d != -1 && d != SPACE_DIM) return d;
            }
        }
        return Integer.MIN_VALUE;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static int extract(Pattern p, String s) {
        Matcher m = p.matcher(s);
        assertTrue("pattern " + p + " not found in: " + s, m.find());
        return Integer.parseInt(m.group(1));
    }
}
