package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Repro (bug-report-workflow) for finding L3 — UNFIXED unguarded
 * {@code getNewSatellite} deref in {@code TileSatelliteBuilder.canAssembleSatellite}
 * (~line 91: {@code getNewSatellite(satType).isAcceptableControllerItemStack(...)}).
 *
 * <p>Reasonable use (add-on / registration-order gap): a mod registers a primary
 * satellite part via the public {@code SatelliteRegistry.registerSatelliteProperty(
 * stack, props.setSatelliteType("x"))} but its paired {@code registerSatellite(
 * "x", class)} is missing or runs later. A player puts that part in the Satellite
 * Builder core slot, adds a power source + chassis + id chip, and clicks Build.
 * {@code canAssembleSatellite()} calls {@code getNewSatellite("x")} → null and
 * dereferences it → NPE. Every sibling caller of {@code getNewSatellite}
 * (createFromNBT, ItemSatellite) null-guards; the builder is the one that forgot.</p>
 *
 * <p>The {@code press-build-unregistered} probe registers exactly such an
 * orphaned property through the real public API (no reflection, no production
 * edit), loads the real builder slots, presses the real Build button, and catches
 * the NPE. This pins the CURRENT (unfixed) crash; a fix makes
 * {@code canAssembleSatellite} return false (build silently rejected) instead.
 * Reachability: add-on/API-misuse only (every shipped core type is class-registered
 * before its property) — honest but not vanilla-reachable.</p>
 */
public class SatelliteBuilderUnregisteredTypeNpeTest extends AbstractSharedServerTest {

    @Test
    public void buildWithUnregisteredCoreTypeThrowsNpe() throws Exception {
        int x = 10900, y = 64, z = 9700; // isolated column, distinct from other builder tests

        exec("artest chunk warmup 0 " + (x >> 4) + " " + (z >> 4) + " " + (x >> 4) + " " + (z >> 4));
        String place = exec("artest place 0 " + x + " " + y + " " + z + " advancedrocketry:satelliteBuilder");
        assertTrue("satellite builder must place: " + place, place.contains("\"placed\":true"));

        String resp = exec("artest satellite-builder press-build-unregistered 0 " + x + " " + y + " " + z);
        assertTrue("probe setup must succeed: " + resp, resp.contains("\"ok\":true"));
        assertTrue("the bogus type must be absent from the class registry (else not a valid L3 repro): " + resp,
                resp.contains("\"getNewSatelliteNull\":true"));
        assertTrue("the bogus part must actually load into core slot 0: " + resp,
                resp.contains("\"slot0Loaded\":true"));
        assertTrue("PIN L3 (UNFIXED): pressing Build with an unregistered core type must throw — "
                        + "canAssembleSatellite derefs the null getNewSatellite result. A fix makes it return "
                        + "false (build rejected). Got: " + resp,
                resp.contains("\"outcome\":\"NullPointerException\""));
        assertTrue("the NPE must originate in TileSatelliteBuilder.canAssembleSatellite (the unguarded deref): "
                        + resp,
                resp.contains("TileSatelliteBuilder") && resp.contains("canAssembleSatellite"));
    }
}
