package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Presence contract for the Valkyrien Skies integration.
 *
 * <p>VS is <b>vendored into Advanced Rocketry</b> — its source is compiled into AR's own jar and its
 * libraries are ordinary runtime dependencies, so VS is a mandatory part of AR and is always on the
 * runtime classpath (the 3.0.0 "own the physics stack" decision). The gate must therefore report
 * <b>present</b>, and {@link VSIntegration#init()} must resolve the VS API without throwing.</p>
 *
 * <p>Presence is detected by a classpath probe, not a modid — VS is no longer registered as its own
 * mod (it is hosted by AR's single mod container), so {@code Loader.isModLoaded("valkyrienskies")}
 * would be false.</p>
 */
public class VSIntegrationTest {

    @Test
    public void gateReportsPresentBecauseVsIsVendored() {
        assertTrue("VS is vendored into AR (compiled in) — the gate must report it present",
                VSIntegration.isAvailable());
    }

    @Test
    public void initResolvesTheVsApiWhenPresent() {
        // With VS present this runs the real integration path (logs + touches a VS API type).
        // A NoClassDefFoundError here would mean the vendored VS classpath did not resolve.
        VSIntegration.init();
    }

    @Test
    public void modidIsTheVsRegistryDomain() {
        // Still "valkyrienskies": the registry DOMAIN for VS blocks/items survives the merge even
        // though VS is no longer its own mod (a registry domain need not equal the owning modid).
        assertEquals("valkyrienskies", VSIntegration.MODID);
    }
}
