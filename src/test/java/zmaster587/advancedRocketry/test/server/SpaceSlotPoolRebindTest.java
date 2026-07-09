package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Layer-0 spike: proves a pre-registered pool "slot" dimension can be rebound to
 * different on-disk cells at runtime by retargeting its chunk directory (via
 * {@code WorldProviderSpaceSlot.getSaveFolder()}).
 *
 * <p>A marker block placed in cell A must survive a rebind away to cell B (where the same position
 * reads as NOT that marker — a different, isolated world) and back to A (where it returns), while a
 * marker placed in B must NOT bleed into A. Gen-agnostic: persistence is asserted as "stone
 * present", isolation as "stone absent", so it does not depend on what empty space generates. No
 * Valkyrien Skies needed — Layer 1 ({@code VSShip…}) covers the VS-ship round-trip.</p>
 */
public class SpaceSlotPoolRebindTest extends AbstractSharedServerTest {

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @Test
    public void slotRebindsBetweenCellsAndBlockRoundTrips() throws Exception {
        // The whole rebind round-trip runs synchronously inside the probe (see `space roundtrip`),
        // so there are no cross-tick timing hazards here -- we assert the single result envelope.
        String r = exec("artest space roundtrip");
        assertTrue("space roundtrip must complete: " + r, r.contains("\"ok\":true"));
        assertTrue("a slot must rebind between on-disk cells with per-cell block isolation "
                + "(A marker persists, B marker does not bleed into A): " + r, r.contains("\"pass\":true"));
    }
}
