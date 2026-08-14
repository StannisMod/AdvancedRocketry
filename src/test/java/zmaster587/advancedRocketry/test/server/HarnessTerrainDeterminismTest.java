package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Two INDEPENDENT harness worlds must generate the SAME terrain.
 *
 * <p>The harness creates a fresh world directory per boot, so with no world seed configured
 * vanilla rolled a brand-new random landscape for every test run. Any fixture that stands a
 * player, places a platform or flies a craft at fixed coordinates then sat in open air on one
 * run and inside a hillside on the next — a test that "flakes" while the code under test is
 * perfectly deterministic. One such case cost a full investigation: a player suffocating inside
 * terrain read as a space suit failing to grant vacuum immunity.</p>
 *
 * <p><b>Never give this class {@code requiresFlatTerrain()}.</b> On a flat world terrain is
 * identical whatever the seed is, so this test would pass on an unpinned seed too — it would stop
 * guarding the one thing it exists for.</p>
 *
 * <p>This pins the property that makes those fixtures reproducible: same coordinates, same
 * ground, every run. It compares the sampled surface of several chunks — including the columns
 * the shared player-fixture and the flight fixtures actually stand on — across two separately
 * created worlds. If someone clears the configured seed, this test fails instead of quietly
 * handing the suite a new landscape.</p>
 */
public class HarnessTerrainDeterminismTest {

    /** Chunks sampled: spawn, the shared (8, ~78, 8) player fixture, and two flight-fixture columns. */
    private static final int[][] SAMPLED_CHUNKS = {
            {0, 0},
            {212, 31},
            {25, 18},
    };

    private static final Pattern TOP_Y = Pattern.compile("\"topY\":(-?\\d+)");
    private static final Pattern TOP_BLOCK = Pattern.compile("\"topBlock\":\"([^\"]+)\"");
    private static final Pattern BIOME = Pattern.compile("\"biome\":\"([^\"]+)\"");

    private Path firstDir;
    private Path secondDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDirs() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        firstDir = Files.createTempDirectory("forge-server-terrain-determinism-a-");
        secondDir = Files.createTempDirectory("forge-server-terrain-determinism-b-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    @Test
    public void twoFreshWorldsGenerateIdenticalTerrain() throws Exception {
        firstBoot = RealDedicatedServerHarness.startWith(firstDir, /*cleanupOnClose=*/true);
        List<String> first = sampleAll(firstBoot);
        firstBoot.close();
        firstBoot = null;

        secondBoot = RealDedicatedServerHarness.startWith(secondDir, /*cleanupOnClose=*/true);
        List<String> second = sampleAll(secondBoot);

        for (int i = 0; i < SAMPLED_CHUNKS.length; i++) {
            assertEquals("chunk [" + SAMPLED_CHUNKS[i][0] + "," + SAMPLED_CHUNKS[i][1] + "] "
                            + "differs between two freshly created harness worlds — the world seed is "
                            + "not pinned, so every run gets its own landscape and every fixed-coordinate "
                            + "fixture is a coin flip",
                    first.get(i), second.get(i));
        }
    }

    /** "topY|topBlock|biome" per sampled chunk — the surface a fixture would land on. */
    private List<String> sampleAll(RealDedicatedServerHarness harness) throws Exception {
        List<String> surfaces = new ArrayList<String>();
        for (int[] chunk : SAMPLED_CHUNKS) {
            String resp = String.join("\n",
                    harness.client().execute(
                            "artest worldgen sample 0 " + chunk[0] + " " + chunk[1]));
            surfaces.add(surfaceOf(resp, chunk));
        }
        return surfaces;
    }

    private static String surfaceOf(String resp, int[] chunk) {
        Matcher y = TOP_Y.matcher(resp);
        Matcher block = TOP_BLOCK.matcher(resp);
        Matcher biome = BIOME.matcher(resp);
        assertTrue("sample of chunk [" + chunk[0] + "," + chunk[1] + "] malformed: " + resp,
                y.find() && block.find() && biome.find());
        return y.group(1) + "|" + block.group(1) + "|" + biome.group(1);
    }
}
