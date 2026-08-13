package zmaster587.advancedRocketry.test.unit;

import org.junit.After;
import org.junit.Test;

import zmaster587.advancedRocketry.space.SpaceManager;
import zmaster587.advancedRocketry.space.SpaceSubsystem;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * The one promise a replaceable subsystem has to keep: <b>putting one in hands back the way OUT, and
 * taking it out restores what was there.</b>
 *
 * <p>The shape this pins replaced a setter that assigned five service fields and kept no copy of
 * them, whose "uninstall" assigned five nulls. Since the hook that builds the production subsystem
 * runs once per server start and can never run again, the first test scenario in a boot to use that
 * uninstall left the whole subsystem answering {@code null} for the rest of the server's life — and
 * every production reader (the flight computer's tick, the login restore, the jump trigger) then did
 * nothing at all, through its own null check, without a line in any log. Ledger #235.</p>
 *
 * <p><b>Why these assertions can fail.</b> Each names a state the old shape actually produced: the
 * restore leg was {@code null} rather than the previous subsystem, and the nested leg unwound to
 * whatever the last writer happened to leave. They are the contract, not a description of the
 * current code, and the previous implementation is the red witness for every one of them.</p>
 */
public class SpaceSubsystemInstallTest {

    /**
     * A subsystem wired with an explicit config and its own clock, so the construction needs no live
     * game — which is also the property that makes the production factory usable from a probe.
     */
    private static SpaceSubsystem subsystem() {
        return new SpaceSubsystem(null, () -> 0L,
                new SpaceManager.Config(SpaceManager.GcPolicy.NEVER, 0L, 0));
    }

    @After
    public void leaveNothingInstalled() {
        SpaceSubsystem.install(null).close();
        while (SpaceSubsystem.get() != null) {
            SpaceSubsystem.install(null);
        }
    }

    @Test
    public void closingAnInstallRestoresWhatWasThereRatherThanClearingIt() {
        SpaceSubsystem production = subsystem();
        SpaceSubsystem probe = subsystem();
        SpaceSubsystem.Handle base = SpaceSubsystem.install(production);

        SpaceSubsystem.Handle handle = SpaceSubsystem.install(probe);
        assertSame("the installed subsystem must be the live one while its handle is open",
                probe, SpaceSubsystem.get());

        handle.close();

        // THE CLAUSE. Not "the live one is no longer the probe's" — that is satisfied by null, which
        // is exactly what the old uninstall did and exactly the state that killed the subsystem.
        assertSame("closing an install must put the PREVIOUS subsystem back, not clear the field",
                production, SpaceSubsystem.get());
        base.close();
    }

    @Test
    public void anInstallOverNothingUnwindsToNothing() {
        SpaceSubsystem probe = subsystem();
        SpaceSubsystem.Handle handle = SpaceSubsystem.install(probe);
        assertSame(probe, SpaceSubsystem.get());

        handle.close();

        // The control for the test above: where there genuinely was nothing to restore, restoring
        // nothing is correct. Without this leg, "close puts something back" could be satisfied by an
        // implementation that never clears — which would leak a probe subsystem into the next
        // scenario, the mirror of the bug and just as silent.
        assertNull("an install over an empty subsystem must unwind to empty", SpaceSubsystem.get());
    }

    @Test
    public void nestedInstallsUnwindInOrder() {
        SpaceSubsystem production = subsystem();
        SpaceSubsystem first = subsystem();
        SpaceSubsystem second = subsystem();
        SpaceSubsystem.Handle base = SpaceSubsystem.install(production);

        SpaceSubsystem.Handle outer = SpaceSubsystem.install(first);
        SpaceSubsystem.Handle inner = SpaceSubsystem.install(second);
        assertSame(second, SpaceSubsystem.get());

        inner.close();
        assertSame("unwinding the inner install must expose the outer one, not production",
                first, SpaceSubsystem.get());

        outer.close();
        assertSame("unwinding both must reach production", production, SpaceSubsystem.get());
        base.close();
    }

    @Test
    public void closingIsIdempotentAndNeverResurrectsOverAThirdParty() {
        SpaceSubsystem probe = subsystem();
        SpaceSubsystem later = subsystem();
        SpaceSubsystem.Handle handle = SpaceSubsystem.install(probe);

        // Somebody else takes over without unwinding the first — the ordinary state of a shared
        // server boot, where two scenarios install in turn.
        SpaceSubsystem.Handle takeover = SpaceSubsystem.install(later);

        handle.close();
        assertSame("a stale handle must not stamp on whoever holds the subsystem now",
                later, SpaceSubsystem.get());

        handle.close(); // twice, because a cleanup path that runs per scenario will do this
        assertSame("closing twice must change nothing", later, SpaceSubsystem.get());
        takeover.close();
    }
}
