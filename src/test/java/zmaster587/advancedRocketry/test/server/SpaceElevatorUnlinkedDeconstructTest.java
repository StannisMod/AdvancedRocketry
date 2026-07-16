package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * C051 (FIND-025) — teardown of a space elevator that was never tether-linked.
 *
 * <p><b>Contract</b>: breaking (deconstructing) a Space Elevator multiblock
 * that has no active tether link must be a safe no-op on the link teardown —
 * never a server crash. {@code dimBlockPos} is null for an elevator that was
 * never linked (constructor default) or was unlinked in-game; a previously
 * linked elevator keeps its link across reload (the inherited
 * {@code readFromNBT} restores {@code dimBlockPos} via the virtual
 * {@code readNetworkData}), so the unlinked states above are exactly the ones
 * {@code deconstructMultiBlock} must tolerate.</p>
 *
 * <p>Drives {@code deconstructMultiBlock} via the {@code artest machine
 * deconstruct} probe on a fixture-built (never try-completed, never linked)
 * elevator controller. That is the same method the production block-break
 * teardown invokes on a formed multiblock
 * ({@code BlockMultiblockMachine.breakBlock} is completion-gated, so the
 * in-world crash needs a completed unlinked elevator; the probe drives the
 * method directly on the fixture state, where the NPE site is identical).
 * Driving the method directly (rather than breaking the block in-world) also
 * isolates THIS teardown path from the unrelated hidden-multiblock
 * deconstruct NPE documented in {@link SpaceElevatorMultiblockTest}.</p>
 *
 * <p>Repro history: pre-fix this test pinned the wrong behaviour (teardown
 * threw {@code NullPointerException} from
 * {@code TileSpaceElevator.deconstructMultiBlock}); flipped to the corrected
 * contract with the C051 null-guard (bug-report-workflow Path B).</p>
 *
 * <p>Position-isolated at x=6580: SpaceElevatorMultiblockTest's fixtures span
 * roughly x 6496..6564 (controllers at 6500/6530/6560 ± the footprint), this
 * footprint starts at ~6576 — no overlap, and each test class gets a fresh
 * temp world anyway.</p>
 */
public class SpaceElevatorUnlinkedDeconstructTest extends AbstractSharedServerTest {

    private static final int CX = 6580;
    private static final int CY = 64;
    private static final int CZ = 6500;

    @Test
    public void deconstructingUnlinkedElevatorIsASafeNoOpOnLinkTeardown() throws Exception {
        warmup(CX, CZ);
        String fixture = join(client().execute(
                "artest fixture multiblock space-elevator 0 " + CX + " " + CY + " " + CZ));
        assertTrue("fixture multiblock space-elevator failed: " + fixture,
                fixture.contains("\"ok\":true"));

        String info = join(client().execute(
                "artest machine info 0 " + CX + " " + CY + " " + CZ));
        assertTrue("expected TileSpaceElevator tile at controller pos: " + info,
                info.contains("TileSpaceElevator"));

        String deconstruct = join(client().execute(
                "artest machine deconstruct 0 " + CX + " " + CY + " " + CZ));

        // Contract: tearing down an elevator with no tether link must not
        // crash — the link teardown is a safe no-op when dimBlockPos is null.
        assertTrue("deconstruct probe errored: " + deconstruct,
                deconstruct.contains("\"ok\":true"));
        assertTrue("deconstructing an unlinked elevator must not throw, got: "
                + deconstruct, deconstruct.contains("\"threw\":false"));
    }

    private static String join(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** Force-generate and populate the chunk grid covering the elevator
     *  footprint BEFORE the fixture lays its blocks — see
     *  {@link SpaceElevatorMultiblockTest} for why. */
    private static void warmup(int blockX, int blockZ) throws Exception {
        int cx1 = (blockX - 16) >> 4;
        int cz1 = (blockZ - 16) >> 4;
        int cx2 = (blockX + 16) >> 4;
        int cz2 = (blockZ + 16) >> 4;
        String resp = join(client().execute(
                "artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2));
        assertTrue("chunk warmup failed: " + resp, resp.contains("\"ok\":true"));
    }
}
