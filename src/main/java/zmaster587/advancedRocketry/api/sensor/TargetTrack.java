package zmaster587.advancedRocketry.api.sensor;

import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * One contact, as a sensor currently holds it: where it is, where it is going, and how well it is
 * being held.
 *
 * <h3>Position AND velocity, because a point cannot be led</h3>
 * <p>A gun handed a point misses a moving target by however far it moves while the round is in the
 * air. That is not a gun problem — a gun has no way to know a target is moving — so the velocity
 * travels with the contact, and the mount that consumes it works out the intercept using its own
 * muzzle speed.</p>
 *
 * <h3>Quality is not confidence</h3>
 * <p>{@link #getQuality()} is how well the target is RESOLVED, on 0..1. It is what separates
 * "something is out there" from "I can put a round on it": below the installation's lock threshold a
 * battery may track a contact all day and still not fire at it. Where the number comes from depends
 * on the mode that produced it — the target's own radiance when listening, the sensor's own
 * illumination when lit.</p>
 *
 * <p>Immutable. A track is a snapshot of a moment, not a handle onto a target that keeps changing
 * underneath its reader.</p>
 */
public final class TargetTrack {

    private final UUID entity;
    private final Vec3d position;
    private final Vec3d velocity;
    private final double quality;
    private final SensorMode mode;
    private final double radianceWattsPerSquareMetre;
    private final double distance;

    public TargetTrack(UUID entity, Vec3d position, Vec3d velocity, double quality, SensorMode mode,
                       double radianceWattsPerSquareMetre, double distance) {
        this.entity = entity;
        this.position = position;
        this.velocity = velocity == null ? Vec3d.ZERO : velocity;
        this.quality = Math.max(0.0D, Math.min(1.0D, quality));
        this.mode = mode;
        this.radianceWattsPerSquareMetre = radianceWattsPerSquareMetre;
        this.distance = distance;
    }

    /** The entity this contact is, or null for a contact that is not one (nothing produces those yet). */
    public UUID getEntity() {
        return entity;
    }

    /** Where it was when the scan saw it, in WORLD coordinates — never a ship's subspace. */
    public Vec3d getPosition() {
        return position;
    }

    /** How it was moving, in blocks per tick, world frame. Zero for something that was not. */
    public Vec3d getVelocity() {
        return velocity;
    }

    /** How well it is resolved, 0..1. */
    public double getQuality() {
        return quality;
    }

    /** Which channel produced this: what was heard, or what was lit. */
    public SensorMode getMode() {
        return mode;
    }

    /** The target's own radiance, W/m² — the passive channel's actual input. */
    public double getRadianceWattsPerSquareMetre() {
        return radianceWattsPerSquareMetre;
    }

    /** How far the contact was from the sensor when it was taken, in blocks. */
    public double getDistance() {
        return distance;
    }

    /**
     * Whether this contact is resolved well enough to shoot at. A contact that is detected but not
     * locked is a real and useful state: the battery knows something is there, and cannot hit it.
     */
    public boolean isLocked(double qualityFloor) {
        return quality >= qualityFloor;
    }
}
