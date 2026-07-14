package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.SpaceSlotPool;
import zmaster587.advancedRocketry.space.SpaceSubsystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract pins for the space-subsystem registration hygiene: the enable-gate decision surface
 * ({@link SpaceSubsystem#shouldRegister}) and the ephemeral-hyperspace folder target
 * ({@link SpaceSlotPool#unboundSlotSubfolder}). Pure — no server, no world.
 *
 * <p>The gate contract (a mechanic behind a config flag must, when off, register NOTHING): each guard
 * — the config flag, Valkyrien Skies presence, the test-harness stand-down, and once-per-session
 * idempotence — must independently veto registration. The OFF-flag pin is the regression guard: it
 * fails the moment the flag stops fully disabling the subsystem.</p>
 *
 * <p>The folder pin fixes the on-disk path the hyperspace wipe deletes, so it can never target the
 * wrong directory, and so the path stays stable (it is also what {@code WorldProviderSpaceSlot}
 * reads/writes for an unbound slot).</p>
 */
public class SpaceRegistrationHygieneTest {

    // ---- enable-gate: shouldRegister(testMode, enabled, vsAvailable, alreadyBuilt) ----------

    @Test
    public void registersOnlyWhenEveryConditionIsMet() {
        assertTrue("live server, flag on, VS present, not yet built -> register",
                SpaceSubsystem.shouldRegister(false, true, true, false));
    }

    @Test
    public void theDisabledFlagFullyStandsDown() {
        // Regression guard: with the enable flag off, NOTHING registers, whatever else is true.
        assertFalse("enableSpaceSubsystem=false must veto registration",
                SpaceSubsystem.shouldRegister(false, false, true, false));
    }

    @Test
    public void noValkyrienSkiesMeansNothingToHost() {
        assertFalse("without VS the subsystem has no tier-2 ships to host -> do not register",
                SpaceSubsystem.shouldRegister(false, true, false, false));
    }

    @Test
    public void theTestHarnessStandsDownSoItsOwnPoolIsNotStacked() {
        assertFalse("in test mode the probe registers its own pool; production must stand down",
                SpaceSubsystem.shouldRegister(true, true, true, false));
    }

    @Test
    public void anAlreadyBuiltSessionDoesNotReRegister() {
        assertFalse("a single-player re-open reuses the JVM-global registration",
                SpaceSubsystem.shouldRegister(false, true, true, true));
    }

    // ---- ephemeral hyperspace: the wipe targets exactly the unbound-slot folder ------------

    @Test
    public void unboundSlotFolderHasTheStableOnDiskPath() {
        assertEquals("advRocketry/spacepool/slot7", SpaceSlotPool.unboundSlotSubfolder(7));
        assertEquals("advRocketry/spacepool/slot-2", SpaceSlotPool.unboundSlotSubfolder(-2));
    }
}
