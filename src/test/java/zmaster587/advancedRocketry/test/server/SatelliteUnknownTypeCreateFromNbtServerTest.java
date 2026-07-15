package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Regression guard (bug-report-workflow Step 3, Path B) for findings C002 /
 * FIND-002 and C155 / FIND-199 (HIGH), server tier.
 *
 * <p>{@code SatelliteRegistry.createFromNBT} used to NPE on an unregistered
 * satellite {@code dataType} ({@code getNewSatellite} returns null and the old
 * code dereferenced it). The fix returns {@code null} for an unresolvable type
 * so callers ({@code DimensionProperties.readFromNBT},
 * {@code PacketSatellite.readClient}, {@code PacketSatellitesUpdate.readClient})
 * drop the satellite instead of crashing. This drives the exact production
 * {@code createFromNBT} path server-side via {@code artest satellite
 * create-from-nbt-unknown} and confirms it returns null (no NPE).</p>
 *
 * <p>The client-visible side is pinned by
 * {@code test/client/SatelliteUnknownTypeClientE2ETest}; the unit tier by
 * {@code SatelliteRegistryFallbackTest}. Ledgered — see
 * {@code .agent/history/known-bugs-ledger.md} Batch #2.</p>
 */
public class SatelliteUnknownTypeCreateFromNbtServerTest extends AbstractSharedServerTest {

    @Test
    public void createFromNbtWithUnknownTypeReturnsNullOnServer() throws Exception {
        String resp = String.join("\n",
                client().execute("artest satellite create-from-nbt-unknown 0"));

        assertTrue("SatelliteRegistry.createFromNBT with an unregistered dataType "
                + "must return null server-side (no NullPointerException, no "
                + "placeholder) so the caller drops the satellite. Got: " + resp,
                resp.contains("\"satClass\":\"null\""));
    }
}
