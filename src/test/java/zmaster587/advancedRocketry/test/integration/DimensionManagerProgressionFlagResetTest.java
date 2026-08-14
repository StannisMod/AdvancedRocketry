package zmaster587.advancedRocketry.test.integration;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertFalse;

/**
 * §7.4 progression-flag lifecycle — {@code DimensionManager.hasReachedMoon} /
 * {@code hasReachedWarp} are process-global statics that gate the one-time
 * ONE_SMALL_STEP / FLIGHT_OF_PHOENIX advancements.
 *
 * <p>Finding C126: they are set true by gameplay and read from a world's
 * {@code stat} NBT on load, but were never reset on teardown. In single-player
 * (client and integrated server share one JVM) that leaked world A's
 * progression into a freshly-created world B whose {@code temp.dat} is missing
 * or empty — B's {@code loadDimensions} early-returns before the stat read, so
 * the stale {@code true} survived and silently suppressed B's first-ever
 * moon-landing / warp advancement (and was then re-persisted into B's save).</p>
 *
 * <p>The cross-world symptom itself needs two integrated worlds in one JVM (the
 * dedicated-server test harness forks a fresh JVM per boot, so the static never
 * leaks there). This pins the corrected teardown contract that closes the leak:
 * {@code onServerStopped()} — fired on every world exit — resets the flags so
 * the next world cannot inherit them.</p>
 */
public class DimensionManagerProgressionFlagResetTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @After
    public void restoreBootstrapState() {
        // onServerStopped() clears the star registry; MinecraftBootstrap.ensure()
        // short-circuits after its first call, so re-register Sol for the benefit
        // of any later test class sharing this JVM.
        if (DimensionManager.getInstance().getStar(0) == null) {
            StellarBody sol = new StellarBody();
            sol.setId(0);
            sol.setName("Sol");
            sol.setTemperature(100);
            DimensionManager.getInstance().addStar(sol);
        }
        DimensionManager.hasReachedMoon = false;
        DimensionManager.hasReachedWarp = false;
    }

    @Test
    public void onServerStoppedClearsProgressionFlags() {
        DimensionManager.hasReachedMoon = true;
        DimensionManager.hasReachedWarp = true;

        DimensionManager.getInstance().onServerStopped();

        assertFalse("hasReachedMoon must reset on world teardown (no cross-world leak)",
                DimensionManager.hasReachedMoon);
        assertFalse("hasReachedWarp must reset on world teardown (no cross-world leak)",
                DimensionManager.hasReachedWarp);
    }
}
