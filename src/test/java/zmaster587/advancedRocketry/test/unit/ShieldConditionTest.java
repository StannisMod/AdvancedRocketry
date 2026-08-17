package zmaster587.advancedRocketry.test.unit;

import com.github.stannismod.affs.world.shield.ShieldCondition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a shield block's condition is allowed to do to it.
 *
 * <p>Two laws, and neither of them is a magnitude. <b>A damaged node delivers less, monotonically</b>
 * — a shell that ate more fire never buys back capability. And <b>an emitter under fire covers less
 * ground</b>, visibly: the shrink is not allowed to vanish into rounding, and it is not allowed to
 * take the field away entirely while the block is still standing.</p>
 *
 * <p>Where the coefficients sit is balance and is deliberately not pinned here; the tests pass their
 * own so a tuning pass never reddens them.</p>
 */
public class ShieldConditionTest {

    private static final double PENALTY = 0.5D;
    private static final int MIN_RADIUS = 1;

    /** A node's delivery only ever walks downward, and it stops at nothing rather than at less. */
    @Test
    public void deliveryFallsWithConditionAndNeverBelowNothing() {
        assertEquals("a pristine node must deliver everything it is rated for", 1.0D,
                ShieldCondition.scale(0.0D, PENALTY), 1.0E-9D);

        double previous = Double.MAX_VALUE;
        for (double fraction = 0.0D; fraction <= 1.0D; fraction += 0.05D) {
            double factor = ShieldCondition.scale(fraction, PENALTY);
            assertTrue("a shield node must never deliver MORE as it is damaged further: " + factor
                    + " after " + previous + " at " + fraction, factor <= previous + 1.0E-9D);
            assertTrue("a wrecked node delivers nothing; it must never deliver a negative amount and"
                    + " start consuming: " + factor + " at " + fraction, factor >= 0.0D);
            previous = factor;
        }

        assertTrue("with the whole rating on the line, a node one step from destruction must deliver"
                + " strictly less than a pristine one",
                ShieldCondition.scale(1.0D, 1.0D) < ShieldCondition.scale(0.0D, 1.0D));
    }

    /** Turn the consequence off and a shield stops caring about damage — the disable path is real. */
    @Test
    public void aZeroPenaltyLeavesEverythingUntouched() {
        assertEquals("with the penalty at zero a wrecked node must still deliver its full rating",
                1.0D, ShieldCondition.scale(1.0D, 0.0D), 1.0E-9D);
        assertEquals("with the penalty at zero a wrecked emitter must still project its whole field",
                12, ShieldCondition.shrinkRadius(12, 1.0D, 0.0D, MIN_RADIUS));
    }

    /**
     * The claim the whole consequence rests on: a damaged emitter covers less than the same emitter
     * pristine — as an ordering, not as a radius.
     */
    @Test
    public void aDamagedEmitterProjectsASmallerFieldThanAPristineOne() {
        int declared = 8;
        int pristine = ShieldCondition.shrinkRadius(declared, 0.0D, PENALTY, MIN_RADIUS);
        int battered = ShieldCondition.shrinkRadius(declared, 0.5D, PENALTY, MIN_RADIUS);

        assertEquals("an undamaged emitter must project exactly the field it was told to hold",
                declared, pristine);
        assertTrue("a damaged emitter must project a SMALLER field than the same emitter pristine ("
                + battered + " vs " + pristine + "): the one consequence a player can see coming is"
                + " the field drawing in", battered < pristine);
    }

    /** Damage that is real must be visible, not absorbed by rounding to the same integer. */
    @Test
    public void theSmallestRealDamageAlreadyShows() {
        int declared = 8;
        int oneStageOfFour = ShieldCondition.shrinkRadius(declared, 0.25D, PENALTY, MIN_RADIUS);
        assertTrue("an emitter with a stage on it still projected its full field (" + oneStageOfFour
                + " of " + declared + "): a consequence that rounds away is one a player cannot read",
                oneStageOfFour < declared);
    }

    /** It shrinks; it does not switch off. Losing the field entirely is what destruction is for. */
    @Test
    public void aStandingEmitterAlwaysProjectsSomething() {
        for (int declared = 1; declared <= 16; declared++) {
            int shrunk = ShieldCondition.shrinkRadius(declared, 1.0D, 1.0D, MIN_RADIUS);
            assertTrue("an emitter that is still standing must still project a field, however small"
                    + " (declared " + declared + " gave " + shrunk + ")", shrunk >= MIN_RADIUS);
            assertTrue("damage must never GROW the field (declared " + declared + " gave " + shrunk
                    + ")", shrunk <= declared);
        }
    }

    /** Repair is a re-read, so the same declared radius and no damage gives the field back whole. */
    @Test
    public void repairRestoresTheWholeFieldBecauseNothingIsAccumulated() {
        int declared = 10;
        ShieldCondition.shrinkRadius(declared, 0.75D, PENALTY, MIN_RADIUS);
        assertEquals("the field must come back whole once the block is mended — the radius is derived"
                + " from the declared one every time, never chipped away at",
                declared, ShieldCondition.shrinkRadius(declared, 0.0D, PENALTY, MIN_RADIUS));
    }
}
