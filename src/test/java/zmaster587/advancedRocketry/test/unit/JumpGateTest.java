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

    /**
     * A ship that answers exactly what it is told to answer. It starts as a ship that CAN jump —
     * computer, position, target, a drive, a charged capacitor and the energy for the flight — so
     * every test below changes exactly one thing and sees exactly one consequence.
     */
    private static final class FakeShip implements JumpGate.ShipContext {
        boolean navComputer = true;
        boolean positionKnown = true;
        GalacticCoord target = TARGET;
        long drivePower = 8_000L;
        long burstCost = 160_000L;
        long capacitorCharge = 160_000L;
        long hullOutsideWindow = 0L;
        long storedEnergy = 1_000_000L;
        long flightEnergyCost = 400_000L;

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

        @Override
        public long drivePower() {
            return drivePower;
        }

        @Override
        public long burstCost() {
            return burstCost;
        }

        @Override
        public long capacitorCharge() {
            return capacitorCharge;
        }

        @Override
        public long hullOutsideWindow() {
            return hullOutsideWindow;
        }

        @Override
        public long storedEnergy() {
            return storedEnergy;
        }

        @Override
        public long flightEnergyCost() {
            return flightEnergyCost;
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

    // ─── The drive clauses ─────────────────────────────────────────────────────

    @Test
    public void aShipWithNoFieldGeneratorCannotJump() {
        FakeShip ship = new FakeShip();
        ship.drivePower = 0L;

        JumpGate.Verdict verdict = JumpGate.check(ship);

        assertFalse("a jump needs a machine behind it, not just a destination", verdict.allowed());
        assertEquals(JumpGate.MSG_NO_DRIVE, verdict.firstMessage());
    }

    @Test
    public void aShipWithNoDriveIsToldOnlyThatOnce() {
        FakeShip ship = new FakeShip();
        ship.drivePower = 0L;
        ship.capacitorCharge = 0L;
        ship.storedEnergy = 0L;

        JumpGate.Verdict verdict = JumpGate.check(ship);

        // The capacitor and supply clauses have nothing to say about a ship with no drive: telling a
        // pilot his capacitor is flat and his tanks are dry, when the answer is "you have no
        // hyperdrive", buries the one thing he needs to hear under two things he does not.
        assertEquals("a driveless ship raises exactly one objection: " + verdict,
                1, verdict.objections().size());
    }

    @Test
    public void aFlatCapacitorRefusesTheJump() {
        FakeShip ship = new FakeShip();
        ship.capacitorCharge = ship.burstCost - 1L;

        JumpGate.Verdict verdict = JumpGate.check(ship);

        assertFalse("without the burst the window does not open at all - that is physics",
                verdict.allowed());
        assertEquals(JumpGate.MSG_CAPACITOR_LOW, verdict.firstMessage());
    }

    @Test
    public void exactlyEnoughChargeIsEnough() {
        FakeShip ship = new FakeShip();
        ship.capacitorCharge = ship.burstCost;

        assertTrue("the burst costs what it costs; meeting it exactly is meeting it",
                JumpGate.check(ship).allowed());
    }

    @Test
    public void aHullOutsideTheWindowWarnsWithoutRefusing() {
        FakeShip ship = new FakeShip();
        ship.hullOutsideWindow = 40L;

        JumpGate.Verdict verdict = JumpGate.check(ship);

        assertTrue("leaving part of the ship behind is a decision, not an impossibility",
                verdict.allowed());
        assertTrue("but the pilot must be told he is about to make it", verdict.needsConfirmation());
        assertEquals(JumpGate.MSG_WINDOW_UNDERSIZED, verdict.firstMessage());
    }

    @Test
    public void tooLittleEnergyForTheFlightWarnsWithoutRefusing() {
        FakeShip ship = new FakeShip();
        ship.storedEnergy = ship.flightEnergyCost - 1L;

        JumpGate.Verdict verdict = JumpGate.check(ship);

        assertTrue("running out on the way ends a flight early; it does not forbid one",
                verdict.allowed());
        assertTrue(verdict.needsConfirmation());
        assertEquals(JumpGate.MSG_ENERGY_SHORTFALL, verdict.firstMessage());
    }

    @Test
    public void aHardDriveObjectionIsReportedBeforeAnAdvisorySupplyOne() {
        FakeShip ship = new FakeShip();
        ship.capacitorCharge = 0L;
        ship.storedEnergy = 0L;

        JumpGate.Verdict verdict = JumpGate.check(ship);

        assertEquals("the pilot hears what stops him before what merely worries him",
                JumpGate.MSG_CAPACITOR_LOW, verdict.firstMessage());
        assertEquals(2, verdict.objections().size());
    }
}
