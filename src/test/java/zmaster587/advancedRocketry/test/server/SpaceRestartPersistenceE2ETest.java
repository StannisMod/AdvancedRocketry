package zmaster587.advancedRocketry.test.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.stannismod.forge.testing.TestTimeouts;
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

    /**
     * How long to wait for the world autosave to reach an armed save fault. Vanilla saves every 900
     * ticks, i.e. 45 s at a full tick rate, and a harness server under fork contention runs slower than
     * that — so the wait is scaled the way every other hard ceiling in this suite is, and is generous:
     * its only cost on a healthy build is that it ends early, the moment the fault reports it fired.
     */
    private static final long AUTOSAVE_WAIT_MS = (long) (150_000L * TestTimeouts.factor());

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

    /**
     * A save point that cannot record a ship it has been told is flying must keep the fleet it already
     * persisted, rather than storing an empty one over it.
     *
     * <p><b>The state this arranges is the one a real crash produced.</b> A ship in flight is deliberately
     * absent from the stored settled list — the in-flight jump record is what carries it — so the two
     * halves of the durable record are only ever right together. In the playtest that started this, the
     * half that fetches the jumps died half-way through a save; the half that empties the settled list had
     * already run; and what reached the disk said the world contained no ships at all. The next flush made
     * that permanent and the pilot came back to a world that had forgotten he ever owned a ship.</p>
     *
     * <p>Reproducing the class-loading accident itself is neither possible nor the point. What matters is
     * the STATE it left the save point in — the ledger saying "flying", nothing carrying it — and that is
     * arrangeable directly. A save may then legitimately do only one of two things: record the ship, or
     * record nothing at all. It may not record its absence.</p>
     *
     * <p><b>What makes this able to fail.</b> The instrument is not the forced save: it is the SHUTDOWN
     * save, which the neighbouring reboot test already proves runs and writes. So a forced save that
     * silently did nothing cannot turn this green — it would leave the shutdown save to write the same
     * emptied fleet, and the assertion below would still be the thing that catches it. The one thing the
     * forced save is load-bearing for is laying down a good snapshot BEFORE the bad state exists; if that
     * failed, this test goes red, never quietly green.</p>
     */
    @Test
    public void aSavePointThatCannotRecordAFlyingShipKeepsTheFleetItAlreadyPersisted() throws Exception {
        // --- boot 1 -------------------------------------------------------------------------------
        harness = RealDedicatedServerHarness.startWith(root, false);
        assumeProductionSubsystemAvailable();

        String status = exec("artest space subsystem-status");
        assertTrue("the production space subsystem must be live on boot 1: " + status,
                status.contains("\"registered\":true"));

        String settled = exec("artest space ledger-settle " + SHIP_ID + " "
                + SECTOR_X + " " + SECTOR_Y + " " + SECTOR_Z + " 0 0 0");
        assertTrue("the ship must be recorded in the production ledger: " + settled,
                settled.contains("\"ok\":true"));

        // Lay a good snapshot on disk. Everything below is about what the NEXT save does to it.
        String saved = exec("artest space save-now");
        assertTrue("the forced save must have run: " + saved, saved.contains("\"ok\":true"));

        // Now the state the crash left behind: the ledger says this ship is flying, and no jump carries
        // it. Both halves are asserted, because "the ship is somewhere else" and "the ship is nowhere"
        // are the same reading from the settled list alone.
        String flying = exec("artest space ledger-transit " + SECTOR_X + " " + SECTOR_Y + " " + SECTOR_Z
                + " " + SHIP_ID);
        assertTrue("arrangement: the ledger must now call the ship in-flight: " + flying,
                flying.contains("\"state\":\"IN_TRANSIT\""));
        String midStatus = exec("artest space subsystem-status");
        assertEquals("arrangement: and NO jump may be carrying it — that is the whole condition under "
                        + "test, and with a jump in flight this test would prove nothing: " + midStatus,
                0, jsonInt(midStatus, "transits"));
        assertEquals("arrangement: the subsystem must still hold the ship, or the save has nothing to "
                + "lose: " + midStatus, 1, jsonInt(midStatus, "ledger"));

        String savedAgain = exec("artest space save-now");
        assertTrue("the second save must also have run: " + savedAgain, savedAgain.contains("\"ok\":true"));

        // --- the reboot ---------------------------------------------------------------------------
        harness.close();
        harness = null;
        harness = RealDedicatedServerHarness.startWith(root, false);

        String statusAfter = exec("artest space subsystem-status");
        assertTrue("the production subsystem must come up again on boot 2: " + statusAfter,
                statusAfter.contains("\"registered\":true"));

        String restored = exec("artest space ledger-get " + SHIP_ID);
        assertTrue("the ship must survive a save point that could not record it — a save is allowed to "
                + "be one cycle stale, never to erase a fleet: " + restored,
                restored.contains("\"found\":true"));
        assertTrue("and it must come back at the address the last GOOD save recorded: " + restored,
                restored.contains("\"cell\":\"" + SECTOR_X + "_" + SECTOR_Y + "_" + SECTOR_Z + "\""));
    }

    /**
     * A save point that fails part-way leaves both the fleet and the server standing.
     *
     * <p><b>What "fails" means here, precisely.</b> The gathering the handler does before it writes is
     * meant to be total, so nothing can be made to break it from outside — which is exactly why the
     * subsystem exposes a one-shot armed fault instead. What it stands in for is a mistake in that
     * gather: a null nobody expected, a collection changed under an iterator. The handler undertakes to
     * survive THAT and lose one stale cycle. It deliberately does not undertake to survive an
     * {@link Error} — a broken class loader or an exhausted heap is not a condition a save handler can
     * mend, and a crash report is worth more than a line swallowed every forty-five seconds. The fleet
     * does not rest on that distinction: the gather touches the store only once it holds every value,
     * so a throw of any kind leaves the previous snapshot whole (which is the leg above).</p>
     *
     * <p><b>Which save the fault has to land in is the whole design of this test.</b> A save asked for
     * by a command cannot demonstrate anything here: vanilla's command dispatch catches {@code
     * Throwable}, so a handler that throws under a {@code /save-all} is caught two frames up and the
     * server survives on the broken build as readily as on the fixed one. The save that can take the
     * server down is the WORLD AUTOSAVE, raised straight out of the server tick with nothing between it
     * and the tick loop's crash handler — the crash report this task came from names that exact stack.
     * So this arms the fault and then WAITS for the periodic autosave to walk into it, which is why the
     * test is slow. The fault going un-armed is the witness that it really fired; without that, a wait
     * that was merely too short would read as "the server survived".</p>
     *
     * <p><b>Red witness</b>: delete the {@code catch} around the save handler's body. The poll below
     * then dies reporting that the server process exited.</p>
     */
    @Test
    public void aSavePointThatFailsPartWayLeavesBothTheFleetAndTheServerStanding() throws Exception {
        harness = RealDedicatedServerHarness.startWith(root, false);
        assumeProductionSubsystemAvailable();

        String status = exec("artest space subsystem-status");
        assertTrue("the production space subsystem must be live on boot 1: " + status,
                status.contains("\"registered\":true"));

        String settled = exec("artest space ledger-settle " + SHIP_ID + " "
                + SECTOR_X + " " + SECTOR_Y + " " + SECTOR_Z + " 0 0 0");
        assertTrue("the ship must be recorded in the production ledger: " + settled,
                settled.contains("\"ok\":true"));

        String armed = exec("artest space save-fault-once");
        assertTrue("the fault must actually be armed, or nothing below is exercising a failed save: "
                + armed, armed.contains("\"armed\":true"));
        assertTrue("and the subsystem must agree it is armed: " + exec("artest space subsystem-status"),
                exec("artest space subsystem-status").contains("\"saveFaultArmed\":true"));

        // Wait for the world autosave to walk into the fault. Every poll is itself a liveness check:
        // on an unguarded handler the server is gone by now and exec() reports the dead process.
        String live = "";
        boolean fired = false;
        long startedAt = System.currentTimeMillis();
        while (System.currentTimeMillis() - startedAt < AUTOSAVE_WAIT_MS) {
            live = exec("artest space subsystem-status");
            if (live.contains("\"saveFaultArmed\":false")) {
                fired = true;
                break;
            }
            Thread.sleep(2000);
        }
        assertTrue("no autosave reached the armed fault within "
                + (AUTOSAVE_WAIT_MS / 1000) + "s, so this run never exercised a failing save at all "
                + "and its green would be worth nothing: " + live, fired);

        // It fired, from the server tick, and the server is still answering.
        assertTrue("the server must still be running after a save point failed — a failed save costs a "
                + "stale cycle, not the process: " + live, live.contains("\"registered\":true"));

        // And it is not wedged: an ordinary save still works afterwards.
        String recovered = exec("artest space save-now");
        assertTrue("the next save must work normally: " + recovered, recovered.contains("\"ok\":true"));

        harness.close();
        harness = null;
        harness = RealDedicatedServerHarness.startWith(root, false);

        String restored = exec("artest space ledger-get " + SHIP_ID);
        assertTrue("and the ship the failing save was holding must still be there: " + restored,
                restored.contains("\"found\":true"));
        assertTrue("at its own address: " + restored,
                restored.contains("\"cell\":\"" + SECTOR_X + "_" + SECTOR_Y + "_" + SECTOR_Z + "\""));
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
