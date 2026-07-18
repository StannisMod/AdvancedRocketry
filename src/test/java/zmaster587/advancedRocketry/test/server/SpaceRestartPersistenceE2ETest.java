package zmaster587.advancedRocketry.test.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Server e2e for tier-2 space persistence across a REAL server restart: two separate server JVMs
 * over one and the same world directory.
 *
 * <p>Every other test of this subsystem drives a probe-local stack and simulates a restart in
 * process, which cannot see the failures that only a genuine reboot produces — a snapshot that is
 * marked dirty too late to be written, an id that was minted per session and silently changed, a
 * restore hook that never runs because the production wiring stood down. This test closes that gap
 * by using the production path end to end: the shipped server-start hook builds the subsystem, the
 * shipped world-save hook writes it, the process really exits, and the shipped server-started hook
 * on the SECOND boot is what has to bring the state back from disk.</p>
 *
 * <p>The subsystem normally stands down when it detects a test harness (the probes register their
 * own dimension pool, and two pools would fight over slot ids), so the world is pre-seeded with the
 * config flag that opts back in. That flag is the whole reason this test can exist.</p>
 *
 * <p>Nothing here touches physics — what is under test is the persistence of the server's record of
 * where ships are, not a loaded ship. It is nonetheless a {@code -PwithVS} test, because the
 * subsystem declines to register at all without Valkyrien Skies (no tier-2 ships to host means
 * nothing worth registering ten dimensions for), so the wiring under test would not exist.</p>
 */
public class SpaceRestartPersistenceE2ETest {

    /** Stable across both boots — the whole point is that the SECOND server recognises it. */
    private static final String SHIP_ID = "2f8c1f6a-4d3b-4c11-9a7e-0b5d6e7f8a90";
    /** An arbitrary but exact galactic address; asserted back verbatim after the reboot. */
    private static final String SECTOR_X = "7";
    private static final String SECTOR_Y = "-3";
    private static final String SECTOR_Z = "11";

    private Path root;
    private RealDedicatedServerHarness harness;

    @Before
    public void seedWorldDirectory() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));

        root = Files.createTempDirectory("forge-server-space-restart-");
        Path arConfigDir = root.resolve("config").resolve("advRocketry");
        Files.createDirectories(arConfigDir);
        // Opt the production space subsystem back in under the harness. Written as a whole config
        // file rather than patched in, because on the first boot none exists yet; the mod fills in
        // every other key with its default and preserves this one.
        String cfg = "# seeded by SpaceRestartPersistenceE2ETest\n"
                + "performance {\n"
                + "    B:spaceRegisterUnderTestHarness=true\n"
                + "}\n";
        Files.write(arConfigDir.resolve("advancedRocketry.cfg"), cfg.getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void stopHarness() throws Exception {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", harness.client().execute(cmd));
    }

    /**
     * The production subsystem only registers when Valkyrien Skies is present — without tier-2 ships
     * there is nothing for it to host, so it deliberately declines. That makes this an {@code -PwithVS}
     * test even though nothing here touches physics: the wiring under test refuses to exist otherwise.
     */
    private void assumeProductionSubsystemAvailable() throws Exception {
        String vs = exec("artest vs available");
        Assume.assumeTrue("Valkyrien Skies absent — the production space subsystem declines to "
                + "register without it; run with -PwithVS: " + vs, vs.contains("\"available\":true"));
    }

    @Test
    public void aSettledShipsGalacticPositionSurvivesAServerReboot() throws Exception {
        // --- boot 1: the production subsystem comes up and records a ship ------------------------
        harness = RealDedicatedServerHarness.startWith(root, false);
        assumeProductionSubsystemAvailable();

        String status = exec("artest space subsystem-status");
        // If this fails the rest of the test is meaningless rather than wrong: the production wiring
        // never registered, so nothing below would be exercising it. Say so explicitly.
        assertTrue("the production space subsystem must be live on boot 1 (config opt-in) — "
                + "without it this test would silently assert nothing: " + status,
                status.contains("\"registered\":true"));

        String settled = exec("artest space ledger-settle " + SHIP_ID + " "
                + SECTOR_X + " " + SECTOR_Y + " " + SECTOR_Z + " 0 0 0");
        assertTrue("the ship must be recorded in the production ledger: " + settled,
                settled.contains("\"ok\":true"));

        String beforeSave = exec("artest space ledger-get " + SHIP_ID);
        assertTrue("sanity: the ledger must hold the ship BEFORE the reboot, or a green result "
                + "after it would prove nothing: " + beforeSave, beforeSave.contains("\"found\":true"));

        // Deliberately NO explicit save here. The ship is recorded and the server is then simply
        // stopped, which is what an operator does and the harshest honest case: the shutdown save is
        // the only one that ever runs, and it is the last one there will be. An implementation that
        // merely marks its snapshot dirty during that save has already missed it, and nothing writes
        // map storage afterwards — so the ship would be silently lost. Saving twice here would hide
        // exactly that, by letting a second pass write what the first one dirtied.

        // --- the reboot: this process really exits ----------------------------------------------
        harness.close();
        harness = null;

        // --- boot 2: a brand new JVM, same world directory ---------------------------------------
        harness = RealDedicatedServerHarness.startWith(root, false);

        String statusAfter = exec("artest space subsystem-status");
        assertTrue("the production subsystem must come up again on boot 2: " + statusAfter,
                statusAfter.contains("\"registered\":true"));

        String restored = exec("artest space ledger-get " + SHIP_ID);
        assertTrue("a ship settled before the reboot must still be known after it — this is the "
                + "contract that a player's ship is not lost by restarting the server: " + restored,
                restored.contains("\"found\":true"));
        assertTrue("it must come back at the SAME galactic address, not merely exist: " + restored,
                restored.contains("\"cell\":\"" + SECTOR_X + "_" + SECTOR_Y + "_" + SECTOR_Z + "\""));
        assertTrue("and it must come back settled, not in some default state: " + restored,
                restored.contains("\"state\":\"SETTLED\""));
    }

    @Test
    public void registeringThePoolASecondTimeReusesItInsteadOfMintingAnother() throws Exception {
        // Dimension registration is JVM-global. A second pool would not merely waste ids: every slot
        // already bound to a cell would keep its id while the subsystem started handing out different
        // ones, so a ship's world and the pool's idea of that world would silently diverge.
        harness = RealDedicatedServerHarness.startWith(root, false);
        assumeProductionSubsystemAvailable();

        String status = exec("artest space subsystem-status");
        assertTrue("production subsystem must be live: " + status, status.contains("\"registered\":true"));

        String again = exec("artest space pool-idempotence");
        assertTrue("re-registering must not grow the pool: " + again, again.contains("\"grew\":false"));
        assertTrue("and it must hand back the dimensions that already exist: " + again,
                again.contains("\"returnedExisting\":true"));
    }

    @Test
    public void anUnknownShipIsReportedMissingRatherThanInvented() throws Exception {
        // The witness for the test above: prove the probe can say "no". Without this, a ledger-get
        // that answered "found" unconditionally would make the restart assertion pass on a subsystem
        // that restored nothing at all.
        harness = RealDedicatedServerHarness.startWith(root, false);
        assumeProductionSubsystemAvailable();

        String status = exec("artest space subsystem-status");
        assertTrue("production subsystem must be live: " + status, status.contains("\"registered\":true"));

        String missing = exec("artest space ledger-get " + UUID.randomUUID());
        assertTrue("a ship that was never settled must read back as absent: " + missing,
                missing.contains("\"found\":false"));
    }
}
