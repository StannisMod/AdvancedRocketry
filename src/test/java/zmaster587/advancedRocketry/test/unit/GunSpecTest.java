package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.api.weapon.GunSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a gun's numbers promise the player who builds it.
 *
 * <p>The promise is that parts <b>add up</b>: two barrels are worth more than one, a part never
 * silently overrides what another contributed, and a build that is not a gun says so instead of
 * firing something worthless. These are the properties an addon's part depends on when it joins a
 * build it knows nothing about, so they are pinned here rather than left to whatever the first
 * caller happened to observe.</p>
 */
public class GunSpecTest {

    private static final double EPSILON = 1.0E-9D;

    /** A controller with nothing built around it is not a gun, and does not pretend to be one. */
    @Test
    public void anEmptyBuildIsNotOperable() {
        assertFalse("an empty assembly must not be operable", GunSpec.EMPTY.isOperable());
        assertEquals(0, GunSpec.EMPTY.getPartCount());
    }

    /** Two of a part are worth twice one of it. Nothing else in the contract is as load-bearing. */
    @Test
    public void partsAddUp() {
        GunSpec one = new GunSpec.Builder().addMuzzleSpeed(0.9D).addImpactEnergy(8).countPart().build();
        GunSpec two = new GunSpec.Builder().addMuzzleSpeed(0.9D).addImpactEnergy(8).countPart()
                .addMuzzleSpeed(0.9D).addImpactEnergy(8).countPart().build();

        assertEquals(one.getMuzzleSpeed() * 2.0D, two.getMuzzleSpeed(), EPSILON);
        assertEquals(one.getImpactEnergy() * 2, two.getImpactEnergy());
        assertEquals(2, two.getPartCount());
    }

    /** A build with a barrel and a round worth firing is a gun. */
    @Test
    public void aBarrelAndAChargeMakeAnOperableGun() {
        GunSpec gun = new GunSpec.Builder().addMuzzleSpeed(0.9D).addImpactEnergy(8).countPart().build();
        assertTrue("a barrel section should make an operable gun", gun.isOperable());
    }

    /** Speed with nothing behind it is not a gun: a round worth zero is not a round. */
    @Test
    public void speedWithoutAChargeIsNotAGun() {
        GunSpec noCharge = new GunSpec.Builder().addMuzzleSpeed(2.0D).countPart().build();
        assertFalse("a gun with no impact energy must not be operable", noCharge.isOperable());
    }

    /** More barrel makes a gun truer, but never better than true. */
    @Test
    public void spreadTightensTowardsZeroAndStopsThere() {
        GunSpec.Builder builder = new GunSpec.Builder();
        for (int part = 0; part < 100; part++) {
            builder.addSpreadDegrees(-0.8D).countPart();
        }
        assertEquals("spread must floor at a true barrel", 0.0D, builder.build().getSpreadDegrees(), EPSILON);
    }

    /** However much feed is stacked, a gun cannot fire twice in one tick. */
    @Test
    public void theFireIntervalFloorsAtOneTick() {
        GunSpec.Builder builder = new GunSpec.Builder();
        for (int part = 0; part < 50; part++) {
            builder.speedUpFireIntervalBy(3).countPart();
        }
        assertEquals(1, builder.build().getFireIntervalTicks());
    }

    /** A part cannot contribute a negative, whatever it passes in. */
    @Test
    public void negativeContributionsAreRefusedRatherThanSubtracted() {
        GunSpec spec = new GunSpec.Builder()
                .addMuzzleSpeed(1.0D).addMuzzleSpeed(-5.0D)
                .addImpactEnergy(10).addImpactEnergy(-100)
                .countPart().build();

        assertEquals(1.0D, spec.getMuzzleSpeed(), EPSILON);
        assertEquals(10, spec.getImpactEnergy());
    }
}
