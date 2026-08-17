package zmaster587.advancedRocketry.weapon;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.Vec3d;
import zmaster587.advancedRocketry.api.weapon.TurretDriveState;

/**
 * The traverse: a bearing, the limits it may take, the rate it may change at, and what happens when
 * the drive stops working.
 *
 * <h3>A mechanism, not a pair of angles</h3>
 * <p>The mount is the first of several things in this mod that are commanded rather than set — an
 * engine gimbal and a landing gear are the same shape. So the state here is deliberately the whole
 * shape: where it IS, where it was TOLD to go, whether that command was inside what the build can
 * do, and which failure it is in. A caller reads the current bearing and gets the truth in every
 * one of those cases, including the ones where the truth is "wherever it seized".</p>
 *
 * <h3>Saturation is visible, never a silent clamp</h3>
 * <p>Commanding a bearing outside the declared arc does not quietly snap to the nearest legal one
 * and report success. The mount goes as far as it may and {@link #isSaturated()} stays true for as
 * long as the command is out of reach, so a console can show a player that the target is behind the
 * hull rather than leaving them to wonder why nothing is being hit.</p>
 *
 * <h3>Angles</h3>
 * <p>Yaw is degrees clockwise from south in Minecraft's own convention, so a bearing computed from
 * a direction vector here matches one computed anywhere else in the game. Pitch is degrees, positive
 * DOWN — again Minecraft's convention — and the elevation limits are expressed in it, so a limit
 * read off this class means the same thing as one read off an entity.</p>
 */
public class TurretMechanism {

    /** How close counts as on target. Half a degree is finer than any barrel's spread. */
    public static final double AIM_TOLERANCE_DEGREES = 0.5D;

    /** Degrees per tick a freewheeling mount drifts. Slow, constant, and never a command. */
    private static final double FREEWHEEL_DRIFT_DEGREES = 0.75D;

    private double yaw;
    private double pitch;
    private double commandedYaw;
    private double commandedPitch;
    private boolean commanded;
    private boolean saturated;
    private TurretDriveState driveState = TurretDriveState.WORKING;

    private final double minPitch;
    private final double maxPitch;

    /**
     * @param minPitch most upward the barrel may point, in Minecraft pitch (negative is up)
     * @param maxPitch most downward the barrel may point
     */
    public TurretMechanism(double minPitch, double maxPitch) {
        this.minPitch = Math.min(minPitch, maxPitch);
        this.maxPitch = Math.max(minPitch, maxPitch);
    }

    /** A mount that may point anywhere above the horizontal and a little below it. */
    public static TurretMechanism standard() {
        return new TurretMechanism(-90.0D, 20.0D);
    }

    /**
     * Point at this world direction. The command is remembered as given: a target that later moves
     * back inside the arc is reached without anybody having to re-issue anything.
     */
    public void commandDirection(Vec3d direction) {
        if (direction == null || direction.lengthVector() < 1.0E-9D) {
            return;
        }
        Vec3d unit = direction.normalize();
        double horizontal = Math.sqrt(unit.x * unit.x + unit.z * unit.z);
        commandBearing(Math.toDegrees(Math.atan2(-unit.x, unit.z)),
                Math.toDegrees(-Math.atan2(unit.y, horizontal)));
    }

    public void commandBearing(double yawDegrees, double pitchDegrees) {
        this.commandedYaw = wrapDegrees(yawDegrees);
        this.commandedPitch = pitchDegrees;
        this.commanded = true;
    }

    /** Stop asking for anything. The mount holds where it is; it does not return to a home bearing. */
    public void clearCommand() {
        this.commanded = false;
        this.saturated = false;
    }

    public boolean hasCommand() {
        return commanded;
    }

    /**
     * Advance the mount one tick towards its command at no more than {@code ratePerTick} degrees,
     * derated by the drive state. Answers whether the mount is now pointing where it was told.
     */
    public boolean tick(double ratePerTick) {
        if (driveState == TurretDriveState.FREEWHEELING) {
            // No brake: it turns because nothing is holding it, not because anybody asked.
            yaw = wrapDegrees(yaw + FREEWHEEL_DRIFT_DEGREES);
            saturated = false;
            return false;
        }
        if (!commanded || !driveState.isDrivable()) {
            return commanded && isOnTarget();
        }

        double reachablePitch = clampPitch(commandedPitch);
        // Saturation is decided against what was ASKED FOR, before the arc clamps it — a mount that
        // reports "on target" at the edge of its arc while the target is beyond it is a mount that
        // lies once per engagement.
        saturated = Math.abs(reachablePitch - commandedPitch) > 1.0E-6D;

        double step = Math.max(0.0D, ratePerTick) * driveState.getRateFactor();
        if (step <= 0.0D) {
            return false;
        }
        yaw = approach(yaw, commandedYaw, step, true);
        pitch = approach(pitch, reachablePitch, step, false);
        return isOnTarget();
    }

    /**
     * Whether the barrel is within tolerance of what it was ASKED for — not of the arc-clamped
     * version of it. A mount at the edge of its arc with the target beyond it is not on target, and
     * saying otherwise would let a gun fire happily into its own hull once per engagement.
     */
    public boolean isOnTarget() {
        if (!commanded) {
            return false;
        }
        double dYaw = Math.abs(wrapDegrees(commandedYaw - yaw));
        double dPitch = Math.abs(commandedPitch - pitch);
        return dYaw <= AIM_TOLERANCE_DEGREES && dPitch <= AIM_TOLERANCE_DEGREES;
    }

    /** The direction the barrel actually points, whatever the reason it points there. */
    public Vec3d getAimDirection() {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRad);
        return new Vec3d(-Math.sin(yawRad) * horizontal, -Math.sin(pitchRad), Math.cos(yawRad) * horizontal);
    }

    /** What the mount was TOLD, as opposed to where it has got to. What a client is sent. */
    public double getCommandedYaw() {
        return commandedYaw;
    }

    public double getCommandedPitch() {
        return commandedPitch;
    }

    public double getYaw() {
        return yaw;
    }

    public double getPitch() {
        return pitch;
    }

    /** True while the command asks for a bearing the build cannot reach. */
    public boolean isSaturated() {
        return saturated;
    }

    public TurretDriveState getDriveState() {
        return driveState;
    }

    /**
     * Change the drive state. A mount entering a state that holds no command keeps its bearing —
     * the whole point of the failure vocabulary is that a killed drive leaves the barrel somewhere
     * definite rather than nowhere.
     */
    public void setDriveState(TurretDriveState state) {
        if (state != null) {
            this.driveState = state;
            if (!state.isDrivable()) {
                this.saturated = false;
            }
        }
    }

    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setDouble("yaw", yaw);
        nbt.setDouble("pitch", pitch);
        nbt.setDouble("cmdYaw", commandedYaw);
        nbt.setDouble("cmdPitch", commandedPitch);
        nbt.setBoolean("commanded", commanded);
        nbt.setInteger("drive", driveState.ordinal());
    }

    public void readFromNBT(NBTTagCompound nbt) {
        yaw = nbt.getDouble("yaw");
        pitch = nbt.getDouble("pitch");
        commandedYaw = nbt.getDouble("cmdYaw");
        commandedPitch = nbt.getDouble("cmdPitch");
        commanded = nbt.getBoolean("commanded");
        int drive = nbt.getInteger("drive");
        TurretDriveState[] states = TurretDriveState.values();
        driveState = drive >= 0 && drive < states.length ? states[drive] : TurretDriveState.WORKING;
    }

    private double clampPitch(double value) {
        return Math.max(minPitch, Math.min(maxPitch, value));
    }

    private static double approach(double current, double target, double step, boolean wrapping) {
        double delta = wrapping ? wrapDegrees(target - current) : target - current;
        if (Math.abs(delta) <= step) {
            return wrapping ? wrapDegrees(target) : target;
        }
        double moved = current + Math.copySign(step, delta);
        return wrapping ? wrapDegrees(moved) : moved;
    }

    /** To (-180, 180]. Written out rather than borrowed so this class stays testable off-thread. */
    private static double wrapDegrees(double degrees) {
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
