package zmaster587.advancedRocketry.test.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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

    /**
     * The slot dimension the subsystem attributes to a restored ship must name the world its cell is
     * ACTUALLY bound to on THIS boot.
     *
     * <p>Slot dim ids are minted per boot and handed out in whatever order cells happen to be
     * materialized, so the id a ship's cell held last session says nothing about this one. A record
     * that carries one across a restart points the departure crossing at a world that either does not
     * exist or holds somebody else's cell — and the pilot pays a capacitor charge for a jump that
     * never leaves. Persisting the galactic coordinate is not enough on its own: the coordinate is
     * what survives a restart, the dimension is what has to be re-derived from it.</p>
     *
     * <p>The reboot alone does not produce the divergence. The pool hands out the same ids in the
     * same order, so a ship whose cell is materialized first on boot 2 lands back on the id it had
     * and the assertion below would pass without ever exercising the staleness. Boot 2 therefore
     * materializes a DIFFERENT cell first, which takes the slot the ship used to hold and forces its
     * cell onto another one — the same cross-session shift a real server produces when its players
     * do not happen to reach their ships in the order they left them. The shift is asserted rather
     * than assumed, so a pool that stopped shifting fails here instead of quietly making this test
     * vacuous.</p>
     */
    @Test
    public void aRestoredShipsSlotDimNamesTheWorldItsCellIsActuallyIn() throws Exception {
        // --- boot 1: settle the ship; its cell is materialized into whatever slot is free first ----
        harness = RealDedicatedServerHarness.startWith(root, false);
        assumeProductionSubsystemAvailable();

        String status = exec("artest space subsystem-status");
        assertTrue("the production space subsystem must be live on boot 1 — without it nothing below "
                + "is exercising the shipped wiring: " + status, status.contains("\"registered\":true"));

        String settled = exec("artest space ledger-settle " + SHIP_ID + " "
                + SECTOR_X + " " + SECTOR_Y + " " + SECTOR_Z + " 0 0 0");
        assertTrue("the ship must be recorded in the production ledger: " + settled,
                settled.contains("\"ok\":true"));
        int slotBeforeReboot = jsonInt(settled, "slotDim");

        // --- the reboot: this process really exits -----------------------------------------------
        harness.close();
        harness = null;

        // --- boot 2: a brand new JVM, same world directory ---------------------------------------
        harness = RealDedicatedServerHarness.startWith(root, false);

        String statusAfter = exec("artest space subsystem-status");
        assertTrue("the production subsystem must come up again on boot 2: " + statusAfter,
                statusAfter.contains("\"registered\":true"));

        // Take the ship's old slot with an unrelated cell BEFORE its own cell is made live, so the
        // ship's cell is forced onto a different slot than it held last session.
        String decoy = exec("artest space occupy 1 1 1");
        int decoySlot = jsonInt(decoy, "slotDim");
        assertEquals("the decoy must land on the slot the ship's cell held before the reboot — that is "
                + "what makes the ship's own cell move: " + decoy, slotBeforeReboot, decoySlot);

        String live = exec("artest space occupy " + SECTOR_X + " " + SECTOR_Y + " " + SECTOR_Z);
        int liveSlot = jsonInt(live, "slotDim");
        assertNotEquals("the arrangement must actually move the ship's cell onto a different slot; "
                + "if it did not, this test proves nothing about a stale id: " + live,
                slotBeforeReboot, liveSlot);

        String restored = exec("artest space ledger-get " + SHIP_ID);
        assertTrue("a ship settled before the reboot must still be known after it: " + restored,
                restored.contains("\"found\":true"));
        assertEquals("the slot dim attributed to the restored ship must be the one its cell is live "
                + "in now, not the one it happened to occupy last session — a departure resolves its "
                + "origin world from this id: " + restored,
                liveSlot, jsonInt(restored, "slotDim"));
        assertEquals("and that dimension must be bound to the ship's OWN cell. This is the assertion "
                + "that fails loudest in play: a stale id can still resolve to a live world, and the "
                + "crossing would then cut a ship out of a cell belonging to somebody else: "
                + restored,
                SECTOR_X + "_" + SECTOR_Y + "_" + SECTOR_Z, jsonString(restored, "slotCell"));
    }

    /** The value of a numeric JSON field in a probe response. Fails the test if it is absent. */
    private static int jsonInt(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        assertTrue("probe response carries no numeric \"" + field + "\": " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    /** The value of a string JSON field in a probe response. Fails the test if it is absent. */
    private static String jsonString(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertTrue("probe response carries no string \"" + field + "\": " + json, m.find());
        return m.group(1);
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
