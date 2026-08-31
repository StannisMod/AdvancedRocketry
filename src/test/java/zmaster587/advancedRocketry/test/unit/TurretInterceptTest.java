package zmaster587.advancedRocketry.test.unit;

import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import zmaster587.advancedRocketry.weapon.TurretFireControl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Leading a moving target: the arithmetic that turns "where it is" into "where to shoot".
 *
 * <p>The contract is not a formula, it is an arrival: <b>a round leaving at the muzzle speed and the
 * target travelling at its own velocity must reach the aim point at the same moment.</b> Each test
 * below checks that arrival, so any solver that gets there — iterative, closed-form, or something
 * else entirely — passes.</p>
 */
public class TurretInterceptTest {

    private static final Vec3d MUZZLE = new Vec3d(0.0D, 0.0D, 0.0D);

    /**
     * A target that is not moving is its own aim point. Worth its own test because a lead applied to
     * a stationary target is a miss that looks exactly like a correct implementation right up until
     * somebody checks.
     */
    @Test
    public void aStillTargetIsAimedAtExactly() {
        Vec3d target = new Vec3d(40.0D, 0.0D, 0.0D);
        Vec3d aim = TurretFireControl.interceptPoint(MUZZLE, target, Vec3d.ZERO, 2.0D);
        assertEquals("a still target must be shot at, not led", 0.0D, aim.distanceTo(target), 1.0E-9D);
    }

    /**
     * A target crossing the line of fire is led — and led by the right amount: the round and the
     * target arrive together, which is the only statement that is true of a correct lead and false of
     * a plausible one.
     */
    @Test
    public void theRoundAndACrossingTargetArriveTogether() {
        Vec3d target = new Vec3d(60.0D, 0.0D, 0.0D);
        Vec3d velocity = new Vec3d(0.0D, 0.0D, 0.35D);
        double muzzleSpeed = 2.0D;

        Vec3d aim = TurretFireControl.interceptPoint(MUZZLE, target, velocity, muzzleSpeed);
        assertTrue("nothing was led at all — the aim point is still the target's own position",
                aim.distanceTo(target) > 1.0D);

        double roundFlightTicks = aim.distanceTo(MUZZLE) / muzzleSpeed;
        Vec3d whereTheTargetWillBe = target.add(velocity.scale(roundFlightTicks));
        assertEquals("the round arrives at a place the target is not: aim " + aim + " vs target "
                + whereTheTargetWillBe, 0.0D, aim.distanceTo(whereTheTargetWillBe), 0.05D);
    }

    /** A target running away is led further than one crossing; a closer one, less. Both arrive. */
    @Test
    public void aRecedingTargetIsLedFurtherThanAnApproachingOne() {
        Vec3d target = new Vec3d(50.0D, 0.0D, 0.0D);
        double muzzleSpeed = 3.0D;

        Vec3d receding = TurretFireControl.interceptPoint(MUZZLE, target,
                new Vec3d(0.4D, 0.0D, 0.0D), muzzleSpeed);
        Vec3d approaching = TurretFireControl.interceptPoint(MUZZLE, target,
                new Vec3d(-0.4D, 0.0D, 0.0D), muzzleSpeed);

        assertTrue("a target running away must be shot at further out than where it is: " + receding,
                receding.distanceTo(MUZZLE) > target.distanceTo(MUZZLE));
        assertTrue("and one closing must be shot at nearer: " + approaching,
                approaching.distanceTo(MUZZLE) < target.distanceTo(MUZZLE));

        for (Vec3d aim : new Vec3d[] {receding, approaching}) {
            double flight = aim.distanceTo(MUZZLE) / muzzleSpeed;
            Vec3d along = aim.subtract(target);
            assertEquals("the arrival does not line up for " + aim, flight * 0.4D,
                    along.lengthVector(), 0.05D);
        }
    }

    /**
     * A gun with no muzzle speed cannot lead anything, and must say so by aiming at the target
     * rather than by dividing by zero.
     */
    @Test
    public void aGunWithNoMuzzleSpeedAimsAtTheTargetItself() {
        Vec3d target = new Vec3d(20.0D, 0.0D, 0.0D);
        Vec3d aim = TurretFireControl.interceptPoint(MUZZLE, target, new Vec3d(0.5D, 0.0D, 0.0D), 0.0D);
        assertEquals(0.0D, aim.distanceTo(target), 1.0E-9D);
    }
}
