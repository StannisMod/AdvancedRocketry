package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Regression guard (finding L6) for the defense-in-depth null
 * guard in {@code TileSatelliteBuilder.assembleSatellite}.
 *
 * <p>{@code assembleSatellite} independently dereferences {@code getNewSatellite(
 * satType)} (via {@code sat.getControllerItemStack(...)}), the sibling of the L3
 * deref in {@code canAssembleSatellite}. In production it is only reached through the
 * {@code canAssembleSatellite} gate (now L3-guarded), so the null is unreachable via
 * gameplay — but a direct caller would NPE. The fix mirrors the L3 guard
 * ({@code if (sat == null) return}).</p>
 *
 * <p>The {@code assemble-unregistered-direct} probe calls the public
 * {@code assembleSatellite()} DIRECTLY (bypassing the gate) with an unregistered core
 * type loaded through the real registration API, and catches any throw. Pins the
 * corrected contract: the direct call is a no-op (no throw). Add-on/API-misuse
 * reachability only — the guard is belt-and-suspenders for a future direct caller.</p>
 */
public class SatelliteBuilderAssembleUnregisteredDirectTest extends AbstractSharedServerTest {

    @Test
    public void directAssembleWithUnregisteredCoreTypeIsNullSafe() throws Exception {
        int x = 10960, y = 64, z = 9760; // isolated column, distinct chunk from other builder tests

        exec("artest chunk warmup 0 " + (x >> 4) + " " + (z >> 4) + " " + (x >> 4) + " " + (z >> 4));
        String place = exec("artest place 0 " + x + " " + y + " " + z + " advancedrocketry:satelliteBuilder");
        assertTrue("satellite builder must place: " + place, place.contains("\"placed\":true"));

        String resp = exec("artest satellite-builder assemble-unregistered-direct 0 " + x + " " + y + " " + z);
        assertTrue("probe setup must succeed: " + resp, resp.contains("\"ok\":true"));
        assertTrue("the bogus type must be absent from the class registry (else not a valid L6 repro): " + resp,
                resp.contains("\"getNewSatelliteNull\":true"));
        assertTrue("the bogus part must actually load into core slot 0: " + resp,
                resp.contains("\"slot0Loaded\":true"));
        assertTrue("PIN L6: a DIRECT assembleSatellite() with an unregistered core type must NOT throw — "
                        + "the defense-in-depth guard returns before sat.getControllerItemStack. Got: " + resp,
                resp.contains("\"outcome\":\"no-throw\""));
    }
}
