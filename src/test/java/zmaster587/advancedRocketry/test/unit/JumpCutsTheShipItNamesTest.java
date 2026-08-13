package zmaster587.advancedRocketry.test.unit;

import java.util.UUID;

import org.junit.Test;

import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.space.VSShipCrosser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * A jump leg cuts the ship the jump NAMES — on the way out and on the way back alike.
 *
 * <p>Both legs park in, and cut out of, a world that holds every ship in flight at once, so "the
 * craft at this anchor" is a question with more than one answer by construction. The rule under test
 * is the one both legs now share: the durable id the jump is keyed by decides which craft is cut; a
 * flight computer at the anchor that POSITIVELY names another ship refuses the cut outright; and
 * everything the rule cannot establish falls back to the anchor exactly as before, because a leg
 * that would have worked may never be turned into a failure by a check that cannot judge it.</p>
 *
 * <p>The last clause is the one with a history: the refusal was written as a gate three times over
 * and each version refused real jumps, so the "cannot establish" cases below are as load-bearing as
 * the refusal itself.</p>
 */
public class JumpCutsTheShipItNamesTest {

    private static final BlockPos ANCHOR = new BlockPos(0, 128, 0);
    private static final int DIM = 7;

    private static final UUID OURS = UUID.fromString("00000000-0000-0000-0000-0000000000A1");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-0000000000B2");
    private static final UUID AT_ANCHOR = UUID.fromString("00000000-0000-0000-0000-0000000000C3");

    /** {@code REFUSED} is a value of the same type as an answer; recognise it the way production does. */
    private static final UUID REFUSED = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static UUID cut(UUID byDurableId, UUID byPosition, UUID afcNames) {
        return VSShipCrosser.identifyShipToCut("test", ANCHOR, OURS.toString(), DIM,
                byDurableId, byPosition, afcNames);
    }

    @Test
    public void theShipTheJumpNamesWinsOverWhateverTheAnchorReaches() {
        // The whole defect in one line: the anchor reaches a stranger, the jump's own id names our
        // hull, and the cut must take ours.
        assertEquals("the craft the jump's durable id names is the one cut",
                OURS, cut(OURS, STRANGER, null));
    }

    @Test
    public void anAnchorThatPositivelyNamesAnotherShipRefusesTheCut() {
        assertEquals("a computer at the anchor that calls itself another ship refuses the cut",
                REFUSED, cut(null, STRANGER, STRANGER));
    }

    @Test
    public void anAnchorThatNamesOurOwnShipIsCutByPosition() {
        // Agreement is not a mismatch: with no durable binding, an anchor whose computer names US is
        // the ordinary healthy case and must cross exactly as it always did.
        assertEquals(AT_ANCHOR, cut(null, AT_ANCHOR, OURS));
    }

    @Test
    public void nothingEstablishableFallsBackToTheAnchor() {
        // No durable binding, no computer to ask: the leg proceeds by position, as before the rule
        // existed. This is the case a gate-shaped check would have refused.
        assertEquals(AT_ANCHOR, cut(null, AT_ANCHOR, null));
    }

    @Test
    public void aSyntheticJumpIdIsNotAnIdentityClaimAndCannotRefuse() {
        // A leg driven under a non-uuid key makes no identity claim, so there is nothing to compare
        // and nothing to refuse — even against a computer that names somebody.
        assertEquals(AT_ANCHOR, VSShipCrosser.identifyShipToCut("test", ANCHOR, "fixture-ship-1",
                DIM, null, AT_ANCHOR, STRANGER));
    }

    @Test
    public void anUnresolvableAnchorAnswersNothingRatherThanGuessing() {
        assertNull("no identity and no craft at the anchor is 'nothing to cut', not a guess",
                cut(null, null, null));
    }
}
