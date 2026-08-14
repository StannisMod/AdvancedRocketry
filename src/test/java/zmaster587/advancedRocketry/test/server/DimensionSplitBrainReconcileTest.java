package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 4 — C129 reproduction + regression guard.
 *
 * <p>Contract under test: a dimension persisted in {@code advRocketry/temp.dat}
 * must stay registered and reachable after reload even if it is absent from
 * {@code planetDefs.xml}. On load, registration was driven solely from the XML
 * ({@code dimCouplingList.dims}) while per-dim state lives in temp.dat, so a body
 * present in temp.dat but missing from a hand-edited / restored / reset XML was
 * silently never registered — the player's planet vanished from the galaxy and
 * its {@code DIM<n>} save data was orphaned.</p>
 *
 * <p>Boot 1 generates the galaxy and saves. Between boots one {@code <planet>}
 * node is deleted from the saved world XML (temp.dat untouched, still contains
 * it) — simulating a hand-edited XML / restored older backup. Boot 2 on the same
 * world must still register that dimension. Pre-fix it is missing from the AR
 * registry; post-fix a reconciliation loop registers every temp.dat dim the XML
 * pass skipped.</p>
 */
public class DimensionSplitBrainReconcileTest {

    private static final Pattern AR_DIMS = Pattern.compile("\"arDimensions\":\\[([^\\]]*)]");
    private static final Pattern PLANET_DIMID =
            Pattern.compile("<planet\\b[^>]*\\bDIMID=\"(-?\\d+)\"");

    private Path workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -Dforge.test.harness.enabled=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = Files.createTempDirectory("forge-server-c129-splitbrain-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private static Set<Integer> arDims(RealDedicatedServerHarness h) throws Exception {
        String list = ok(h.client().execute("artest dim list"));
        Matcher m = AR_DIMS.matcher(list);
        assertTrue("dim list missing arDimensions: " + list, m.find());
        Set<Integer> out = new HashSet<>();
        String body = m.group(1).trim();
        if (!body.isEmpty()) {
            for (String s : body.split(",")) {
                if (!s.trim().isEmpty()) out.add(Integer.parseInt(s.trim()));
            }
        }
        return out;
    }

    private static Path planetDefsPath(RealDedicatedServerHarness h) throws Exception {
        String save = ok(h.client().execute("artest server save-dimensions"));
        assertTrue("save-dimensions failed: " + save, save.contains("\"xmlExists\":true"));
        Matcher m = Pattern.compile("\"xmlPath\":\"([^\"]*)\"").matcher(save);
        assertTrue("save-dimensions missing xmlPath: " + save, m.find());
        return Paths.get(m.group(1).replace("\\\\", "\\"));
    }

    @Test
    public void planetInTempDatButRemovedFromXmlStaysRegistered() throws Exception {
        firstBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/false);
        Set<Integer> before = arDims(firstBoot);
        Path xmlPath = planetDefsPath(firstBoot);
        firstBoot.close();
        firstBoot = null;

        // Pick a positive planet dim that is BOTH written in the XML (a non-moon
        // <planet> node) and registered — removing it from the XML creates the
        // temp.dat/XML divergence.
        String content = new String(Files.readAllBytes(xmlPath), StandardCharsets.UTF_8);
        int target = Integer.MIN_VALUE;
        Matcher pm = PLANET_DIMID.matcher(content);
        while (pm.find()) {
            int d = Integer.parseInt(pm.group(1));
            if (d > 0 && before.contains(d)) {
                target = d;
                break;
            }
        }
        assertTrue("no positive <planet> DIMID both in the XML and registered "
                        + "(before=" + before + "):\n" + content,
                target != Integer.MIN_VALUE);

        // Delete the whole <planet ... DIMID="target" ...>...</planet> node.
        Pattern node = Pattern.compile(
                "<planet\\b[^>]*\\bDIMID=\"" + target + "\"[^>]*>.*?</planet>\\s*",
                Pattern.DOTALL);
        String edited = node.matcher(content).replaceFirst("");
        assertNotEquals("target planet node must have been removed from the XML",
                content, edited);
        Files.write(xmlPath, edited.getBytes(StandardCharsets.UTF_8));

        secondBoot = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
        Set<Integer> after = arDims(secondBoot);

        assertTrue("a planet persisted in temp.dat but removed from planetDefs.xml "
                        + "must stay registered after reload (C129); dim " + target
                        + " missing from " + after,
                after.contains(target));

        String info = ok(secondBoot.client().execute("artest dim info " + target));
        assertTrue("dim " + target + " must be an AR planet after reload, not the "
                        + "overworld fallback: " + info,
                info.contains("\"isARPlanet\":true"));
    }
}
