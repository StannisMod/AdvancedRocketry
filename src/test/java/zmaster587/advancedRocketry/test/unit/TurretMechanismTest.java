package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.Vec3d;
import org.junit.Test;
import zmaster587.advancedRocketry.api.weapon.TurretDriveState;
import zmaster587.advancedRocketry.weapon.TurretMechanism;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a commanded mount promises, independent of anything that shoots.
 *
 * <p>Three promises, and each test here fails only if one of them is broken: a declared rate is
 * never exceeded; a command the mount cannot reach is <b>visibly</b> saturated rather than quietly
 * clamped; and a drive that stops working leaves the barrel at a bearing the aim path can still read
 * as the truth. Nothing below asserts a step count, an internal field or how the angles are
 * interpolated — a rewrite that keeps those three promises keeps these tests green.</p>
 */
public class TurretMechanismTest {

    private static final double EPSILON = 1.0E-6D;

    /** The declared rate is a hard ceiling: one tick may not move the mount further than it. */
    @Test
    public void aTickNeverTurnsFurtherThanTheDeclaredRate() {
        TurretMechanism mount = TurretMechanism.standard();
        mount.commandBearing(170.0D, 0.0D);

        double previous = mount.getYaw();
        for (int tick = 0; tick < 20; tick++) {
            mount.tick(3.0D);
            double moved = Math.abs(wrap(mount.getYaw() - previous));
            assertTrue("turned " + moved + " degrees in one tick against a declared 3", moved <= 3.0D + EPSILON);
            previous = mount.getYaw();
        }
    }

    /** Given enough ticks it gets there, and says so. */
    @Test
    public void aReachableCommandIsEventuallyMet() {
        TurretMechanism mount = TurretMechanism.standard();
        mount.commandBearing(90.0D, -10.0D);

        boolean onTarget = false;
        for (int tick = 0; tick < 200 && !onTarget; tick++) {
            onTarget = mount.tick(2.0D);
        }
        assertTrue("a reachable bearing was never reached", onTarget);
        assertFalse("a reachable bearing must not report saturation", mount.isSaturated());
    }

    /**
     * A target below the arc is not silently turned into the lowest legal bearing and called a hit:
     * the mount reports saturation for as long as the command is out of reach.
     */
    @Test
    public void anUnreachableCommandSaturatesInsteadOfClampingSilently() {
        TurretMechanism mount = new TurretMechanism(-90.0D, 20.0D);
        mount.commandBearing(0.0D, 80.0D);

        for (int tick = 0; tick < 200; tick++) {
            mount.tick(5.0D);
        }
        assertTrue("an out-of-arc command must be visibly saturated", mount.isSaturated());
        assertEquals("the mount should sit at the edge of its arc", 20.0D, mount.getPitch(), EPSILON);
        assertFalse("a saturated mount is not on target", mount.isOnTarget());
    }

    /** A seized drive keeps its bearing and keeps its gun: it aims where it stopped, and may fire. */
    @Test
    public void aJammedDriveHoldsItsBearingAndStillFires() {
        TurretMechanism mount = TurretMechanism.standard();
        mount.commandBearing(45.0D, 0.0D);
        for (int tick = 0; tick < 100; tick++) {
            mount.tick(2.0D);
        }
        double seizedYaw = mount.getYaw();
        Vec3d seizedAim = mount.getAimDirection();

        mount.setDriveState(TurretDriveState.JAMMED);
        mount.commandBearing(-135.0D, 0.0D);
        for (int tick = 0; tick < 100; tick++) {
            mount.tick(2.0D);
        }

        assertEquals("a jammed mount moved", seizedYaw, mount.getYaw(), EPSILON);
        assertEquals("a jammed mount's aim is still readable", seizedAim.x, mount.getAimDirection().x, EPSILON);
        assertTrue("a jammed gun may still fire down its stuck bearing",
                mount.getDriveState().permitsFiring());
    }

    /** A dead drive is the one state that stops the shooting as well as the turning. */
    @Test
    public void aDeadDriveNeitherTurnsNorFires() {
        TurretMechanism mount = TurretMechanism.standard();
        mount.setDriveState(TurretDriveState.DEAD);
        mount.commandBearing(120.0D, 0.0D);
        for (int tick = 0; tick < 50; tick++) {
            mount.tick(5.0D);
        }
        assertEquals("a dead mount turned", 0.0D, mount.getYaw(), EPSILON);
        assertFalse("a dead gun must not fire", mount.getDriveState().permitsFiring());
    }

    /** A derated drive is slower than a working one, and still arrives. */
    @Test
    public void aDeratedDriveIsSlowerThanAWorkingOne() {
        TurretMechanism working = TurretMechanism.standard();
        TurretMechanism derated = TurretMechanism.standard();
        derated.setDriveState(TurretDriveState.DERATED);
        working.commandBearing(90.0D, 0.0D);
        derated.commandBearing(90.0D, 0.0D);

        working.tick(4.0D);
        derated.tick(4.0D);

        assertTrue("a derated drive turned at least as fast as a working one",
                Math.abs(derated.getYaw()) < Math.abs(working.getYaw()));
    }

    /** A bearing survives a save: a gun reloaded is a gun still pointing where it was left. */
    @Test
    public void theBearingSurvivesARoundTrip() {
        TurretMechanism mount = TurretMechanism.standard();
        mount.commandBearing(33.0D, -12.0D);
        for (int tick = 0; tick < 100; tick++) {
            mount.tick(2.0D);
        }
        mount.setDriveState(TurretDriveState.DERATED);

        NBTTagCompound nbt = new NBTTagCompound();
        mount.writeToNBT(nbt);
        TurretMechanism restored = TurretMechanism.standard();
        restored.readFromNBT(nbt);

        assertEquals(mount.getYaw(), restored.getYaw(), EPSILON);
        assertEquals(mount.getPitch(), restored.getPitch(), EPSILON);
        assertEquals(mount.getDriveState(), restored.getDriveState());
        assertTrue("a restored mount forgot what it was told to do", restored.hasCommand());
    }

    /** Aiming at a direction and reading the aim back gives the same direction. */
    @Test
    public void aCommandedDirectionIsTheDirectionItEndsUpPointing() {
        TurretMechanism mount = TurretMechanism.standard();
        Vec3d wanted = new Vec3d(1.0D, 0.0D, 1.0D).normalize();
        mount.commandDirection(wanted);
        for (int tick = 0; tick < 400; tick++) {
            mount.tick(2.0D);
        }
        Vec3d aim = mount.getAimDirection();
        assertEquals(wanted.x, aim.x, 1.0E-3D);
        assertEquals(wanted.y, aim.y, 1.0E-3D);
        assertEquals(wanted.z, aim.z, 1.0E-3D);
    }

    private static double wrap(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped <= -180.0D) {
            wrapped += 360.0D;
        }
        if (wrapped > 180.0D) {
            wrapped -= 360.0D;
        }
        return wrapped;
    }
}
