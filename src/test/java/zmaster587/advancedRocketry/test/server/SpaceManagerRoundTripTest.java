package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Server e2e for the {@code SpaceManager} controller driving the real world lifecycle through
 * {@code PoolSlotBinder}: proves the two-tier design end to end against live dimensions.
 *
 * <p>In one synchronous probe (a pool of one slot, so every fresh materialize forces an eviction):
 * a dirty cell's marker block survives eviction and reload via the manager (store round-trip); a
 * clean cell loaded into the freed slot is isolated (does not see the other cell's marker); and a
 * garbage-collected idle stored cell has its on-disk folder actually deleted. Gen-agnostic:
 * persistence is asserted as "marker present", isolation as "marker absent".</p>
 */
public class SpaceManagerRoundTripTest extends AbstractSharedServerTest {

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @Test
    public void controllerFlushesReloadsIsolatesAndGarbageCollects() throws Exception {
        String r = exec("artest space manager");
        assertTrue("space manager probe must complete: " + r, r.contains("\"ok\":true"));
        assertTrue("dirty cell must round-trip through the store, a clean cell must stay isolated, "
                + "and GC must delete the stored folder: " + r, r.contains("\"pass\":true"));
    }
}
