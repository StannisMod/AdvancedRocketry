package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Soft-dependency contract for the Valkyrien Skies integration.
 *
 * <p>The player/packager-facing promise: <b>AR loads and runs without Valkyrien
 * Skies installed.</b> VS is a {@code compileOnly} dependency, so it is NOT on
 * this test JVM's runtime classpath — neither is {@code VSBridge}'s
 * {@code org.valkyrienskies.*} import. So the gate must report absent and
 * {@code init()} must be a no-op that never loads a VS-importing class.</p>
 *
 * <p>The {@code initIsASafeNoOpWithoutVs} test is a real guard, not decoration:
 * if someone breaks the boundary rule and touches {@code VSBridge} outside the
 * {@code isAvailable()} gate, this fails with {@code NoClassDefFoundError}
 * because VS is not on the runtime classpath here.</p>
 */
public class VSIntegrationTest {

    @Test
    public void gateReportsAbsentWhenVsNotInstalled() {
        assertFalse("VS must report absent in the test JVM (it is compileOnly, not a runtime dep)",
                VSIntegration.isAvailable());
    }

    @Test
    public void initIsASafeNoOpWithoutVs() {
        // Must not throw and must not load VSBridge (which imports
        // org.valkyrienskies.*). A NoClassDefFoundError here means the gate leaked.
        VSIntegration.init();
    }

    @Test
    public void modidIsTheVsCoreId() {
        assertEquals("valkyrienskies", VSIntegration.MODID);
    }
}
