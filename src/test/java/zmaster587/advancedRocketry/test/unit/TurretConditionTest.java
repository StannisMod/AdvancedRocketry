package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.api.weapon.TurretDriveState;
import zmaster587.advancedRocketry.weapon.TurretMechanism;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a mount's own condition does to it, and what it is not allowed to do.
 *
 * <p>Two claims, and neither is a number. <b>A drive degrades in one direction through named
 * states</b> — it turns, then turns slowly, then does not turn and still shoots. And <b>condition
 * never overrules a decision</b>: a player's lock and an explicit fault outrank it, or a repaired
 * gun could never be locked again and a locked gun would come unlocked the first time it was
 * scratched.</p>
 *
 * <p>Nothing here pins where the rungs sit. Those are balance, and a test that pinned them would go
 * red the first time somebody tuned the mechanic without changing how it works.</p>
 */
public class TurretConditionTest {

    private static final double DERATE_AT = 0.25D;
    private static final double JAM_AT = 0.75D;

    /** The ladder goes one way, and each rung delivers strictly less traverse than the one above. */
    @Test
    public void conditionWalksTheDriveDownAndNeverBackUp() {
        assertEquals("a pristine mount is not degraded", TurretDriveState.WORKING,
                TurretDriveState.fromDamage(0.0D, DERATE_AT, JAM_AT));
        assertEquals("a damaged mount turns slowly", TurretDriveState.DERATED,
                TurretDriveState.fromDamage(DERATE_AT, DERATE_AT, JAM_AT));
        assertEquals("a wrecked mount seizes", TurretDriveState.JAMMED,
                TurretDriveState.fromDamage(JAM_AT, DERATE_AT, JAM_AT));

        double previous = Double.MAX_VALUE;
        for (double fraction = 0.0D; fraction <= 1.0D; fraction += 0.05D) {
            double rate = TurretDriveState.fromDamage(fraction, DERATE_AT, JAM_AT).getRateFactor();
            assertTrue("traverse must never IMPROVE as a mount is damaged further: " + rate
                    + " after " + previous + " at " + fraction, rate <= previous + 1.0E-9D);
            previous = rate;
        }
    }

    /**
     * A seized mount still fires. This is the whole reason the ladder ends at a named state rather
     * than at a scalar going to zero — a gun that cannot turn is not a gun that cannot shoot.
     */
    @Test
    public void aMountSeizedByDamageStillFires() {
        TurretDriveState seized = TurretDriveState.fromDamage(1.0D, DERATE_AT, JAM_AT);
        assertFalse("a seized mount must not turn", seized.isDrivable());
        assertTrue("a seized mount must still be able to fire down the bearing it stopped at",
                seized.permitsFiring());
    }

    /** Condition speaks for a mount nobody has said anything about — the normal case. */
    @Test
    public void conditionDrivesAMountUnderNoExplicitOrder() {
        TurretMechanism mount = TurretMechanism.standard();
        assertEquals(TurretDriveState.WORKING, mount.getDriveState());

        mount.setDamageDriveState(TurretDriveState.DERATED);
        assertEquals("a damaged mount nobody has touched must report itself damaged",
                TurretDriveState.DERATED, mount.getDriveState());

        // ...and it actually turns less, which is the part a player feels.
        assertTrue("a derated mount must turn less than a working one in the same tick",
                degreesTurnedInOneTick(TurretDriveState.DERATED)
                        < degreesTurnedInOneTick(TurretDriveState.WORKING));
    }

    /**
     * An explicit state outranks condition, in both directions: a locked mount stays locked when it
     * is damaged, and a repaired one does not silently forget it was locked.
     */
    @Test
    public void aDecisionOutranksCondition() {
        TurretMechanism mount = TurretMechanism.standard();
        mount.setDriveState(TurretDriveState.LOCKED);
        mount.setDamageDriveState(TurretDriveState.DERATED);
        assertEquals("damage unlocked a mount a player had locked", TurretDriveState.LOCKED,
                mount.getDriveState());

        mount.setDamageDriveState(TurretDriveState.WORKING);
        assertEquals("repairing the block quietly released the lock", TurretDriveState.LOCKED,
                mount.getDriveState());
    }

    /** Repair walks it back: the state is a re-read fact, not a scar the mount remembers. */
    @Test
    public void repairingTheBlockRestoresTheDrive() {
        TurretMechanism mount = TurretMechanism.standard();
        mount.setDamageDriveState(TurretDriveState.JAMMED);
        assertEquals(TurretDriveState.JAMMED, mount.getDriveState());

        mount.setDamageDriveState(TurretDriveState.WORKING);
        assertEquals("a repaired mount must work again — condition is read, never accumulated",
                TurretDriveState.WORKING, mount.getDriveState());
        assertTrue("and it must turn again", degreesTurnedInOneTick(TurretDriveState.WORKING) > 0.0D);
    }

    private static double degreesTurnedInOneTick(TurretDriveState condition) {
        TurretMechanism mount = TurretMechanism.standard();
        mount.setDamageDriveState(condition);
        mount.commandBearing(90.0D, 0.0D);
        double before = mount.getYaw();
        mount.tick(3.0D);
        return Math.abs(mount.getYaw() - before);
    }
}
