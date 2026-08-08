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
import org.junit.Ignore;
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
 * <h2>What it measures, and what it deliberately does not</h2>
 *
 * The subject is the WORLD's durability: hyperspace's chunks now live in a folder named after the
 * world rather than after the dimension id this boot happened to mint, and nothing wipes it any
 * more. So the question is whether the ships in it — their blocks AND the physics mod's own registry
 * entry for them — come back.
 *
 * <h2>RED, and deliberately so — it is the criterion, not a regression</h2>
 *
 * <p>Measured 2026-08-08 on this box: <b>1 ship in hyperspace before the restart, 0 after</b>, with
 * the dimension id even landing on 86 both times, so the id was never the variable. The evidence
 * says the loss is on the WRITE side and belongs to the physics mod's per-world data, not to the
 * folder: after boot 1 the origin cell's {@code capabilities.dat} was <b>1350 bytes</b> (it still
 * held a ship) while hyperspace's was <b>140</b> — an empty registry — and the run's own debug log
 * shows exactly one 3.9 KB serialisation, which by size is the cell's. Hyperspace's in-memory
 * registry had answered "one ship" moments earlier, so the object that gets serialised is not the
 * one the crossing populated, or it is emptied before the save reaches it.</p>
 *
 * <p>Until that is fixed the folder is wiped at every (re)init on purpose — keeping it would only
 * accumulate hull blocks nothing can ever adopt — so this test is {@code @Ignore}d. Un-ignoring it
 * means deleting the annotation, nothing else: the scenario, the control and the assertion are all
 * exactly what a working durable hyperspace has to satisfy.</p>
 *
 * <p>It does NOT exercise production's transit restore. The {@code artest space transit-*} probes
 * build a PRIVATE transit manager, invisible to the production save/restore wiring, so a record
 * written by one is not read by the other. The record half is pinned in {@code testUnit}
 * ({@code ShipTransitManagerTest}: reclaiming the lane, adopting the parked hull, falling back to
 * the snapshot when the lane came back empty, and disposing of what no record claims); this pins the
 * physical half those decisions are made about.
 */
public class HyperspaceSurvivesARestartE2ETest {

    /** Slow enough that the ship is still parked in its lane when the server goes down. */
    private static final long PARK_SPEED = 100_000L;

    private static final Pattern INT = Pattern.compile("\"%s\":(-?\\d+)");

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

    @Ignore("Measured red: the physics mod's per-world ship registry serialises EMPTY for hyperspace "
            + "(140 bytes vs 1350 for a cell holding one ship). Un-ignore by deleting this line once "
            + "that round-trips; the scenario itself needs no change.")
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

        String tick = exec("artest space transit-tick");
        int hyperDimBefore = readInt(tick, "hyperDim");
        int inTransit = readInt(tick, "inTransit");
        assertTrue("ARRANGEMENT: the jump must still be in flight when the server goes down, or"
                + " nothing is parked to survive anything: " + tick, inTransit >= 1);

        // THE CONTROL, and it is not optional: if no ship reached hyperspace on this boot, "no ship
        // after the restart" would be the arrangement's own answer rather than the product's.
        int parkedBefore = readIntOr(exec("artest vs ship-count-all " + hyperDimBefore), "count", -1);
        assertTrue("ARRANGEMENT: a ship must actually be registered in hyperspace (dim "
                + hyperDimBefore + ") before the restart - found " + parkedBefore, parkedBefore >= 1);

        // No explicit save: what survives has to survive the shutdown save alone, which is the only
        // save a real operator's stop ever runs.
        harness.close();
        harness = null;

        // ── boot 2: a brand new server JVM, same world root ──────────────────────────────────────
        harness = RealDedicatedServerHarness.startWith(root, false);
        String status = exec("artest space subsystem-status");
        assertTrue("the production subsystem must come up again on boot 2, or nothing below is"
                + " exercising it: " + status, status.contains("\"registered\":true"));

        // Re-derive hyperspace's id on THIS boot rather than reusing boot 1's: the id is minted per
        // boot by a free-id scan, and the whole point of naming the folder after the world is that
        // the content no longer depends on which id the scan lands on.
        String setupAfter = exec("artest space transit-setup-piloted");
        assertTrue("the transit probe stack must come up on boot 2: " + setupAfter,
                readBool(setupAfter, "ok"));
        int hyperDimAfter = readInt(exec("artest space transit-tick"), "hyperDim");

        int parkedAfter = readIntOr(exec("artest vs ship-count-all " + hyperDimAfter), "count", -1);
        assertEquals("a ship parked in hyperspace must still be parked in hyperspace after a real"
                + " restart - that is what makes a jump something a restart is survivable BY."
                + " Hyperspace was dim " + hyperDimBefore + " on boot 1 and dim " + hyperDimAfter
                + " on boot 2, holding " + parkedBefore + " ship(s) before and " + parkedAfter
                + " after", parkedBefore, parkedAfter);
    }
}
