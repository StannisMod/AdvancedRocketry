package zmaster587.advancedRocketry.test.unit;

import net.minecraftforge.client.settings.KeyConflictContext;
import org.junit.Test;
import zmaster587.advancedRocketry.client.ARKeyConflictContext;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the conflict-resolution contract of {@link ARKeyConflictContext} that the
 * Controls screen relies on (and that the client e2e tests, which only exercise
 * runtime firing, can't observe): PILOTING and NOT_PILOTING must be mutually
 * exclusive so two bindings sharing a key — one in each context — are never
 * flagged as conflicting.
 *
 * <p>{@code conflicts()} is pure and does not touch the client, so this stays a
 * deterministic unit test; the "which context is active when" half is covered by
 * the real-client {@code FreeFlightModeE2ETest} key-conflict cases.
 */
public class ARKeyConflictContextTest {

    @Test
    public void eachContextConflictsWithItself() {
        // A binding only conflicts with another binding in the SAME context —
        // e.g. two PILOTING keys on the same key would (correctly) clash.
        assertTrue(ARKeyConflictContext.PILOTING.conflicts(ARKeyConflictContext.PILOTING));
        assertTrue(ARKeyConflictContext.NOT_PILOTING.conflicts(ARKeyConflictContext.NOT_PILOTING));
    }

    @Test
    public void pilotingAndNotPilotingNeverConflict() {
        // The whole point: a steering key (PILOTING) and the vanilla key it
        // overrides (NOT_PILOTING) coexist on one key with no conflict warning.
        assertFalse(ARKeyConflictContext.PILOTING.conflicts(ARKeyConflictContext.NOT_PILOTING));
        assertFalse(ARKeyConflictContext.NOT_PILOTING.conflicts(ARKeyConflictContext.PILOTING));
    }

    @Test
    public void doesNotClaimConflictWithForgeBuiltInContexts() {
        // Our contexts must not over-claim conflicts against IN_GAME/GUI, or the
        // steering keys would be flagged against unrelated in-game bindings.
        assertFalse(ARKeyConflictContext.PILOTING.conflicts(KeyConflictContext.IN_GAME));
        assertFalse(ARKeyConflictContext.PILOTING.conflicts(KeyConflictContext.GUI));
        assertFalse(ARKeyConflictContext.NOT_PILOTING.conflicts(KeyConflictContext.IN_GAME));
        assertFalse(ARKeyConflictContext.NOT_PILOTING.conflicts(KeyConflictContext.GUI));
    }
}
