package zmaster587.advancedRocketry.test.unit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import zmaster587.advancedRocketry.navigation.JumpGate;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for whether a ship may jump. The gate promises four things to the rest of the game:
 * a ship with no navigation computer, no idea where it is, or nowhere to go cannot jump; a ship that
 * has all three can; an objection that is merely ill-advised warns instead of refusing; and asking
 * costs nothing, so a pilot may ask as often as he likes.
 */
public class JumpGateTest {

    private static final GalacticCoord TARGET = GalacticCoord.ofSectorLocal(9L, 2L, 2L, 0L, 0L, 0L);

    /** A ship that answers exactly what it is told to answer. */
    private static final class FakeShip implements JumpGate.ShipContext {
        boolean navComputer = true;
        boolean positionKnown = true;
        GalacticCoord target = TARGET;

        @Override
        public boolean hasNavComputer() {
            return navComputer;
        }

        @Override
        public boolean positionKnown() {
            return positionKnown;
        }

        @Override
        public GalacticCoord target() {
            return target;
        }
    }

    @Before
    public void clearRegistrations() {
        JumpGate.reset();
    }

    @After
    public void restoreRegistrations() {
        JumpGate.reset();
    }

    @Test
    public void aShipWithComputerPositionAndTargetMayJump() {
        JumpGate.Verdict verdict = JumpGate.check(new FakeShip());

        assertTrue("nothing objects, so the jump is clear: " + verdict, verdict.allowed());
        assertFalse("a clear jump must not ask the pilot to confirm anything",
                verdict.needsConfirmation());
    }

    @Test
    public void noNavigationComputerRefusesTheJump() {
        FakeShip ship = new FakeShip();
        ship.navComputer = false;

        JumpGate.Verdict verdict = JumpGate.check(ship);

        assertFalse(verdict.allowed());
        assertEquals(JumpGate.MSG_NO_NAV_COMPUTER, verdict.firstMessage());
    }

    @Test
    public void aShipThatDoesNotKnowWhereItIsRefusesToJump() {
        FakeShip ship = new FakeShip();
        ship.positionKnown = false;

        JumpGate.Verdict verdict = JumpGate.check(ship);

        assertFalse("a jump needs BOTH endpoints; an unknown departure is not a jump", verdict.allowed());
        assertEquals(JumpGate.MSG_POSITION_UNKNOWN, verdict.firstMessage());
    }

    @Test
    public void aRelocalizedShipMayJumpAgain() {
        FakeShip ship = new FakeShip();
        ship.positionKnown = false;
        assertFalse("precondition: the lost ship cannot jump", JumpGate.check(ship).allowed());

        // Re-localization ALWAYS succeeds after its time - that is what makes a misjump a setback
        // rather than a dead save.
        ship.positionKnown = true;

        assertTrue("once the ship knows where it is again, jumping must be possible",
                JumpGate.check(ship).allowed());
    }

    @Test
    public void noTargetRefusesTheJump() {
        FakeShip ship = new FakeShip();
        ship.target = null;

        JumpGate.Verdict verdict = JumpGate.check(ship);

        assertFalse(verdict.allowed());
        assertEquals(JumpGate.MSG_NO_TARGET, verdict.firstMessage());
    }

    @Test
    public void aHandTypedTargetIsAcceptedWithoutBeingAKnownAddress() {
        FakeShip ship = new FakeShip();
        ship.target = GalacticCoord.ofSectorLocal(999L, 999L, 999L, 0L, 0L, 0L);

        assertTrue("jumping to an unscanned coordinate is reckless, not illegal",
                JumpGate.check(ship).allowed());
    }

    @Test
    public void anAdvisoryWarnsWithoutBlocking() {
        JumpGate.register(JumpGate.Stage.SUPPLY, new JumpGate.Predicate() {
            @Override
            public JumpGate.Objection check(JumpGate.ShipContext ship) {
                return new JumpGate.Objection(JumpGate.Severity.ADVISORY, "msg.test.notenoughfuel");
            }
        });

        JumpGate.Verdict verdict = JumpGate.check(new FakeShip());

        assertTrue("an ill-advised jump is still the pilot's to make", verdict.allowed());
        assertTrue("but he must be told and must confirm", verdict.needsConfirmation());
        assertEquals("msg.test.notenoughfuel", verdict.firstMessage());
    }

    @Test
    public void aRegisteredHardObjectionRefusesTheJump() {
        JumpGate.register(JumpGate.Stage.POWER, new JumpGate.Predicate() {
            @Override
            public JumpGate.Objection check(JumpGate.ShipContext ship) {
                return new JumpGate.Objection(JumpGate.Severity.HARD, "msg.test.nocharge");
            }
        });

        JumpGate.Verdict verdict = JumpGate.check(new FakeShip());

        assertFalse("a subsystem that registers a hard objection can stop the jump", verdict.allowed());
        assertEquals("msg.test.nocharge", verdict.firstMessage());
    }

    @Test
    public void navigationObjectionsAreReportedBeforeLaterStages() {
        FakeShip ship = new FakeShip();
        ship.navComputer = false;
        JumpGate.register(JumpGate.Stage.POWER, new JumpGate.Predicate() {
            @Override
            public JumpGate.Objection check(JumpGate.ShipContext s) {
                return new JumpGate.Objection(JumpGate.Severity.HARD, "msg.test.nocharge");
            }
        });

        JumpGate.Verdict verdict = JumpGate.check(ship);

        assertEquals("a ship that cannot even aim is told that first, not that its capacitor is flat",
                JumpGate.MSG_NO_NAV_COMPUTER, verdict.firstMessage());
        assertEquals("every objection is collected - the gate is free, so nothing short-circuits",
                2, verdict.objections().size());
    }
}
