package zmaster587.advancedRocketry.test.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>A restart is something a jump survives.</b> A ship parked in hyperspace is still parked in
 * hyperspace after the server has actually stopped and started again — JUMP-9's physical half.
 *
 * <h2>Why this needs TWO server JVMs</h2>
 *
 * The claim is about what survives a shutdown save and a fresh boot, and neither of those happens
 * in-process. An in-process "restart" — re-importing a record into a live manager — is the shape
 * {@code VSShipTransitPersistE2ETest} already uses, and its own javadoc says so: the JVM does not
 * restart, so hyperspace is never truly re-created and the physics mod's per-world data is never
 * re-read from disk. That is exactly the half this test exists to measure, so this class manages its
 * own harnesses rather than extending the one-harness base.
 *
 * <h2>What it measures</h2>
 *
 * Two things, in the order that makes a failure readable:
 *
 * <ol>
 *   <li><b>The jump came back as the SHIP</b> — production's restore found the hull still standing in
 *       its lane and resumed the jump on it ({@code transitsParked}). A jump whose lane came back
 *       empty is still "in transit" and still arrives, by pasting the block snapshot it carries, so
 *       the count of jumps cannot tell a survived hull from a rebuilt copy. The snapshot path is what
 *       a jump silently degrades to when hyperspace does not come back, which is why this is asserted
 *       first.</li>
 *   <li><b>The hull itself survived</b> — the ship is still registered in hyperspace, from the
 *       physics mod's own per-world registry, read on a fresh JVM off the same world root.</li>
 * </ol>
 *
 * <p>Hyperspace's chunks live in a folder named after the world rather than after the dimension id
 * this boot happened to mint, and nothing wipes it: the ids differ freely between the two boots and
 * the content does not move.</p>
 *
 * <h2>Why the arrangement hands the jump to production</h2>
 *
 * <p>The {@code artest space transit-*} probes build a PRIVATE transit manager, so a jump they start
 * is invisible to production's save and restore. Left that way, the hull this test parks looks to the
 * boot reconciliation exactly like a hull no record claims — and it is collected, correctly. So the
 * arrangement calls {@code transit-claim} before the shutdown: being claimed is the difference
 * between a ship in flight and abandoned debris, and only a claimed jump is the subject here.</p>
 *
 * <p>The record's own decisions — reclaiming the lane, adopting the parked hull, falling back to the
 * snapshot when the lane came back empty, disposing of what no record claims — are pinned in
 * {@code testUnit} ({@code ShipTransitManagerTest}); this pins the physical half those decisions are
 * made about, across a real restart.</p>
 */
public class HyperspaceSurvivesARestartE2ETest {

    /**
     * Slow enough that the ship is still parked in its lane when the server goes down AND for the
     * whole of the next boot. The cells sit a sector (4M blocks) apart, so one block per tick is
     * roughly forever: production picks the restored jump up and advances it from this boot's clock,
     * and a speed that merely outlasts the shutdown would let it ARRIVE while boot 2 is coming up —
     * an empty hyperspace that looks exactly like a lost one.
     */
    private static final long PARK_SPEED = 1L;

    private static final Pattern INT = Pattern.compile("\"%s\":(-?\\d+)");

    /**
     * A per-world ship registry holding nothing weighs this on disk. Diagnostic only — it separates
     * the registries worth printing from the ~100 empty ones every space dim writes, and it is a
     * lower bound rather than an exact figure, so a payload that only just carries something still
     * prints.
     */
    private static final long EMPTY_REGISTRY_BYTES = 200L;

    private Path root;
    private RealDedicatedServerHarness harness;

    @Before
    public void seedWorldDirectory() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled - set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        root = Files.createTempDirectory("forge-server-hyperspace-durability-");
    }

    @After
    public void closeHarness() throws Exception {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    private String exec(String command) throws Exception {
        String envelope = "";
        List<String> lines = harness.client().execute(command);
        for (String line : lines) {
            int brace = line.indexOf('{');
            if (brace >= 0 && line.endsWith("}")) {
                envelope = line.substring(brace);
            }
        }
        return envelope;
    }

    private static int readInt(String json, String key) {
        Matcher m = Pattern.compile(String.format(INT.pattern(), key)).matcher(json);
        assertTrue("expected int \"" + key + "\" in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static int readIntOr(String json, String key, int def) {
        Matcher m = Pattern.compile(String.format(INT.pattern(), key)).matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static boolean readBool(String json, String key) {
        return json.contains("\"" + key + "\":true");
    }

    /** Poll for the ship the fixture assembles in its origin cell (VS assembly is asynchronous). */
    private boolean waitForShipIn(int dim) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (readIntOr(exec("artest vs ship-count-all " + dim), "count", -1) >= 1) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    @Test
    public void aShipParkedInHyperspaceIsStillThereAfterTheServerRestarts() throws Exception {
        // ── boot 1: put a real ship into hyperspace and shut the server down under it ────────────
        harness = RealDedicatedServerHarness.startWith(root, false);
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)",
                exec("artest vs available").contains("\"available\":true"));

        String setup = exec("artest space transit-setup-piloted");
        assertTrue("the piloted transit fixture must build: " + setup, readBool(setup, "ok"));
        int originDim = readInt(setup, "originDim");
        assertTrue("ARRANGEMENT: the fixture ship never assembled in the origin cell (dim "
                + originDim + ")", waitForShipIn(originDim));

        String begin = exec("artest space transit-begin " + originDim + " 1 64 1 " + PARK_SPEED);
        assertTrue("the departure crossing must put the ship into hyperspace: " + begin,
                readBool(begin, "began"));

        String tick = exec("artest space transit-tick 10");
        int hyperDimBefore = readInt(tick, "hyperDim");
        int inTransit = readInt(tick, "inTransit");
        assertTrue("ARRANGEMENT: the jump must still be in flight when the server goes down, or"
                + " nothing is parked to survive anything: " + tick, inTransit >= 1);

        // THE CONTROL, and it is not optional: if no ship reached hyperspace on this boot, "no ship
        // after the restart" would be the arrangement's own answer rather than the product's.
        int parkedBefore = readIntOr(exec("artest vs ship-count-all " + hyperDimBefore), "count", -1);
        assertTrue("ARRANGEMENT: a ship must actually be registered in hyperspace (dim "
                + hyperDimBefore + ") before the restart - found " + parkedBefore, parkedBefore >= 1);

        // Hand the jump to PRODUCTION, so what crosses the restart is a claimed jump rather than a
        // hull sitting in a lane. It is the arrangement's job because the probe stack builds its own
        // transit manager: without the claim the boot reconciliation is right to collect the hull, and
        // this test would be measuring an abandoned ship rather than a jump in flight.
        String claim = exec("artest space transit-claim");
        assertTrue("ARRANGEMENT: production must take the claim, or nothing on the far side of the"
                + " restart is restoring a jump: " + claim,
                readBool(claim, "ok") && readIntOr(claim, "claimed", 0) >= 1);

        // No explicit save: what survives has to survive the shutdown save alone, which is the only
        // save a real operator's stop ever runs.
        harness.close();
        harness = null;

        // Boot 1's own account of that save, read BETWEEN the boots. Both readings have to be taken
        // here: boot 2 re-inits hyperspace (rewriting its data file) and the preserved server log is
        // one file per test JVM, so boot 2 overwrites both. A reading taken after the whole run
        // describes boot 2 while appearing to describe boot 1.
        String savedRegistries = capabilityFilesOnDisk();
        String boot1Serialisations = serialisationLines();

        // ── boot 2: a brand new server JVM, same world root ──────────────────────────────────────
        harness = RealDedicatedServerHarness.startWith(root, false);
        String status = exec("artest space subsystem-status");
        assertTrue("the production subsystem must come up again on boot 2, or nothing below is"
                + " exercising it: " + status, status.contains("\"registered\":true"));

        // The jump resumed as the SHIP, not as a copy of it. A restored transit that found its lane
        // empty is still "in transit" and still arrives — by pasting the block snapshot it carries —
        // so the count of jumps alone cannot tell a survived hull from a rebuilt one, and the snapshot
        // path is exactly what a jump degrades to when hyperspace does not come back. Asserted BEFORE
        // the ship count below, because this is the reading that says which product came back.
        assertTrue("a jump restored across the restart must still be carrying its parked hull, not"
                        + " falling back to the block snapshot: " + status
                        + "\n  registries on disk after boot 1: " + savedRegistries,
                readIntOr(status, "transitsParked", 0) >= 1);

        // Re-derive hyperspace's id on THIS boot rather than reusing boot 1's: the id is minted per
        // boot by a free-id scan, and the whole point of naming the folder after the world is that
        // the content no longer depends on which id the scan lands on.
        String setupAfter = exec("artest space transit-setup-piloted");
        assertTrue("the transit probe stack must come up on boot 2: " + setupAfter,
                readBool(setupAfter, "ok"));
        int hyperDimAfter = readInt(exec("artest space transit-tick 10"), "hyperDim");

        int parkedAfter = readIntOr(exec("artest vs ship-count-all " + hyperDimAfter), "count", -1);
        assertEquals("a ship parked in hyperspace must still be parked in hyperspace after a real"
                + " restart - that is what makes a jump something a restart is survivable BY."
                + " Hyperspace was dim " + hyperDimBefore + " on boot 1 and dim " + hyperDimAfter
                + " on boot 2, holding " + parkedBefore + " ship(s) before and " + parkedAfter
                + " after.\n  registries on disk after boot 1: " + savedRegistries
                + "\n  boot 1 serialisations: " + boot1Serialisations,
                parkedBefore, parkedAfter);
    }

    /**
     * Every per-world ship registry the save holds, with its size — the on-disk half of the reading.
     * Which world each file belongs to is in its PATH, so a payload can be attributed to a dimension
     * instead of guessed at from its size.
     */
    private String capabilityFilesOnDisk() {
        Path space = root.resolve("world").resolve("advRocketry");
        if (!Files.isDirectory(space)) {
            return "no " + space + " on disk at all";
        }
        StringBuilder out = new StringBuilder();
        int empty = 0;
        try (java.util.stream.Stream<Path> tree = Files.walk(space)) {
            java.util.List<Path> dats = tree
                    .filter(p -> p.getFileName().toString().equals("capabilities.dat"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            for (Path p : dats) {
                // Every space dim writes one of these and almost all of them are the empty payload;
                // listing a hundred of those buries the two that carry a ship. The count is kept so
                // "nothing was written at all" stays distinguishable from "everything was empty".
                if (Files.size(p) <= EMPTY_REGISTRY_BYTES) {
                    empty++;
                    continue;
                }
                out.append("\n    ").append(space.relativize(p)).append(" = ")
                        .append(Files.size(p)).append(" bytes");
            }
        } catch (Exception e) {
            return "could not be read: " + e;
        }
        return (out.length() == 0 ? "every registry on disk is empty" : out.toString())
                + "\n    (" + empty + " further registries hold no ship)";
    }

    /**
     * What the physics mod said it wrote, from the server log the harness preserves at close. Each
     * line names the world and the ship count, so "one big payload and several empty ones" can be
     * attributed rather than inferred from the sizes.
     */
    private String serialisationLines() {
        try {
            Path log = RealDedicatedServerHarness.preservedLogPath();
            if (!Files.isRegularFile(log)) {
                return "no preserved server log at " + log;
            }
            java.util.List<String> hits = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(log, java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.contains("VS serialization") || line.contains("VS deserialization")) {
                    hits.add(line.substring(Math.max(0, line.indexOf("VS "))));
                }
            }
            if (hits.isEmpty()) {
                return "the server log holds no VS (de)serialization line at all";
            }
            // The tail is the shutdown save; earlier lines are autosaves of the same worlds.
            return "\n    " + String.join("\n    ",
                    hits.subList(Math.max(0, hits.size() - 20), hits.size()));
        } catch (Exception e) {
            return "could not be read: " + e;
        }
    }
}
