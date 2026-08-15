package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a restart must give back — split by who owns it.
 *
 * <p>A subsystem network is deliberately not saved: it has no durable name, being "the blocks that
 * happen to be connected right now", so it is rebuilt from the world every time. That decision is
 * only sound if the rebuild actually returns the same network, and until now nothing checked it.
 * The settings are the opposite case: they live on BLOCKS precisely because a block has a name, and
 * so they are the things that must survive byte-for-byte.</p>
 *
 * <p>So this pins both halves against one restart: the ventilation graph comes back with the same
 * shape (nothing persisted it — chunk load re-registers the nodes), while the vent's zone priority
 * and the shield console's resistance bias come back with the same VALUES (their tiles persisted
 * them). A regression in either direction is invisible in a single-boot test.</p>
 */
public class SubsystemNetworkRestartTest {

    private static final Pattern CABLES = Pattern.compile("\"cables\":(-?\\d+)");
    private static final Pattern SOURCES = Pattern.compile("\"sources\":(-?\\d+)");
    private static final Pattern SINKS = Pattern.compile("\"sinks\":(-?\\d+)");
    private static final Pattern MEMBERS = Pattern.compile("\"members\":(-?\\d+)");
    private static final Pattern PRIORITY = Pattern.compile("\"priority\":(-?\\d+)");
    private static final Pattern BIAS = Pattern.compile("\"resistanceBias\":([0-9.]+)");

    /** One chunk, so a single probe pulls every node of both networks back into memory. */
    private static final int Y = 64;
    private static final int Z = 2608;
    private static final int VENT = 2608;
    private static final int DUCT_A = VENT + 1;
    private static final int DUCT_B = VENT + 2;
    private static final int PLANT = VENT + 3;
    private static final int SHIELD_SOURCE = VENT + 5;
    private static final int SHIELD_SINK = VENT + 6;
    private static final int SHIELD_CONSOLE = VENT + 7;

    private static final int ZONE_PRIORITY = 1;
    private static final String RESISTANCE_BIAS = "0.75";

    private Path workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D"
                        + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-subnet-restart-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    @Test
    public void theNetworkIsRebuiltFromTheWorldWhileItsSettingsAreRestoredFromTheirBlocks()
            throws Exception {
        // ─────── Boot 1: build both networks, set both settings ───────
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);

        place(firstBoot, "advancedrocketry:oxygenVent", VENT);
        place(firstBoot, "advancedrocketry:ventilationDuct", DUCT_A);
        place(firstBoot, "advancedrocketry:ventilationDuct", DUCT_B);
        place(firstBoot, "advancedrocketry:lifeSupportPlant", PLANT);
        place(firstBoot, "affs:shield_generator", SHIELD_SOURCE);
        place(firstBoot, "affs:field_generator", SHIELD_SINK);
        place(firstBoot, "affs:shield_console", SHIELD_CONSOLE);

        String priorityWrite = exec(firstBoot,
                "artest vent priority 0 " + VENT + " " + Y + " " + Z + " " + ZONE_PRIORITY);
        assertEquals("priority write failed: " + priorityWrite,
                ZONE_PRIORITY, intOf(PRIORITY, priorityWrite, "priority (boot 1)"));

        String biasWrite = exec(firstBoot, "artest shield console-bias 0 " + SHIELD_CONSOLE + " "
                + Y + " " + Z + " " + RESISTANCE_BIAS);
        assertTrue("bias write failed: " + biasWrite, biasWrite.contains("\"ok\":true"));

        exec(firstBoot, "artest subnet solve lifesupport 0 2");
        String ventilationBefore = subnet(firstBoot, DUCT_A);
        int cablesBefore = intOf(CABLES, ventilationBefore, "cables (boot 1)");
        int sourcesBefore = intOf(SOURCES, ventilationBefore, "sources (boot 1)");
        int sinksBefore = intOf(SINKS, ventilationBefore, "sinks (boot 1)");
        int membersBefore = intOf(MEMBERS, ventilationBefore, "members (boot 1)");
        assertEquals("premise: two ducts must be in the network before the restart: "
                + ventilationBefore, 2, cablesBefore);
        assertEquals("premise: the plant must be its source: " + ventilationBefore, 1, sourcesBefore);
        assertEquals("premise: the vent must be its sink: " + ventilationBefore, 1, sinksBefore);

        firstBoot.close();
        firstBoot = null;

        // ─────── Boot 2: same world, nothing saved the network itself ───────
        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);

        // Bring the chunk in FIRST. Nothing about the network is restored by loading a save: the
        // tiles re-register from onLoad and the graph is rebuilt from their adjacency, so with the
        // chunk still on disk there is legitimately no network to report. In play a walking player
        // does this; here it is explicit, because a probe that quietly loaded the chunk would be
        // changing the thing it measures.
        String forced = exec(secondBoot, "artest chunk forceload 0 " + (VENT >> 4) + " " + (Z >> 4));
        assertTrue("chunk forceload failed: " + forced, forced.contains("\"ok\":true"));
        exec(secondBoot, "artest subnet solve lifesupport 0 2");
        String ventilationAfter = subnet(secondBoot, DUCT_A);
        assertEquals("the ventilation graph must come back with the same cable count — it is "
                + "rebuilt from the world, and the world did not change: " + ventilationAfter,
                cablesBefore, intOf(CABLES, ventilationAfter, "cables (boot 2)"));
        assertEquals("and the same source count: " + ventilationAfter,
                sourcesBefore, intOf(SOURCES, ventilationAfter, "sources (boot 2)"));
        assertEquals("and the same sink count: " + ventilationAfter,
                sinksBefore, intOf(SINKS, ventilationAfter, "sinks (boot 2)"));
        assertEquals("and the same membership: " + ventilationAfter,
                membersBefore, intOf(MEMBERS, ventilationAfter, "members (boot 2)"));

        // The settings, by contrast, are only here because their own tiles wrote them to NBT.
        String priorityAfter = exec(secondBoot, "artest vent priority 0 " + VENT + " " + Y + " " + Z);
        assertEquals("the vent's zone priority must survive the restart — it is the vent's own "
                        + "setting, not the network's: " + priorityAfter,
                ZONE_PRIORITY, intOf(PRIORITY, priorityAfter, "priority (boot 2)"));

        String consoleAfter = exec(secondBoot, "artest shield console-info 0 " + SHIELD_CONSOLE
                + " " + Y + " " + Z);
        assertEquals("the console's resistance bias must survive the restart, and it is the only "
                        + "thing that re-seeds the rebuilt shield network: " + consoleAfter,
                Double.parseDouble(RESISTANCE_BIAS),
                Double.parseDouble(stringOf(BIAS, consoleAfter, "bias (boot 2)")), 1.0e-6);
    }

    private void place(RealDedicatedServerHarness harness, String block, int x) throws Exception {
        String resp = exec(harness, "artest place 0 " + x + " " + Y + " " + Z + " " + block);
        assertTrue(block + " place failed at " + x + ": " + resp, resp.contains("\"placed\":true"));
    }

    private String subnet(RealDedicatedServerHarness harness, int x) throws Exception {
        return exec(harness, "artest subnet info lifesupport 0 " + x + " " + Y + " " + Z);
    }

    private static String exec(RealDedicatedServerHarness harness, String command) throws Exception {
        return String.join("\n", harness.client().execute(command));
    }

    private static int intOf(Pattern pattern, String response, String label) {
        return Integer.parseInt(stringOf(pattern, response, label));
    }

    private static String stringOf(Pattern pattern, String response, String label) {
        Matcher m = pattern.matcher(response);
        assertTrue("could not parse " + label + " from response: " + response, m.find());
        return m.group(1);
    }
}
