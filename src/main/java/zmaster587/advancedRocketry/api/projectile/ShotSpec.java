package zmaster587.advancedRocketry.api.projectile;

import net.minecraft.util.math.Vec3d;
import zmaster587.advancedRocketry.api.damage.ImpactKind;

import java.util.UUID;

/**
 * Everything a weapon states when it fires, and nothing about the weapon. The muzzle declares where
 * the round starts, how fast it is going, how much it is worth on arrival and how long it may live;
 * the substrate decides nothing about any of that and the weapon decides nothing about what happens
 * when it lands.
 *
 * <h3>Frames and units</h3>
 * <p>{@link #getOrigin()} and {@link #getVelocity()} are <b>world</b> coordinates, and velocity is in
 * blocks per <b>tick</b> — the unit the integration steps in, so that no call site is left converting
 * from blocks per second and getting it wrong by a factor of twenty. {@link #getImpactEnergy()} is in
 * the same unit as a shield's impact energy and a damage budget, which is what lets a shell hand its
 * residual straight through with no conversion in between.</p>
 *
 * <h3>Owner and faction are tokens, not permissions</h3>
 * <p>The substrate never asks either one a question. They travel with the shot so that the layers
 * which do care — friend-or-foe at the turret, attribution at the impact — have something to read;
 * a shot does not decline to hit its owner, because deciding that is not the substrate's job.</p>
 */
public final class ShotSpec {

    /** Default lifetime when the shooter does not state one: one minute of flight. */
    public static final int DEFAULT_LIFETIME_TICKS = 1200;

    private final Vec3d origin;
    private final Vec3d velocity;
    private final double radius;
    private final double mass;
    private final int lifetimeTicks;
    private final int impactEnergy;
    private final ImpactKind kind;
    private final UUID owner;
    private final String faction;
    private final ShotEnvironment environment;
    private final String guidance;

    public ShotSpec(Vec3d origin, Vec3d velocity, double radius, double mass, int lifetimeTicks,
                    int impactEnergy, ImpactKind kind, UUID owner, String faction,
                    ShotEnvironment environment, String guidance) {
        this.origin = origin;
        this.velocity = velocity == null ? new Vec3d(0.0D, 0.0D, 0.0D) : velocity;
        this.radius = Math.max(0.0D, radius);
        this.mass = Math.max(0.0D, mass);
        this.lifetimeTicks = Math.max(1, lifetimeTicks);
        this.impactEnergy = Math.max(0, impactEnergy);
        this.kind = kind == null ? ImpactKind.KINETIC : kind;
        this.owner = owner;
        this.faction = faction;
        this.environment = environment == null ? ShotEnvironment.VACUUM : environment;
        this.guidance = guidance;
    }

    /** A plain unguided round in vacuum, owned by nobody: the shape every other one is built from. */
    public static ShotSpec kinetic(Vec3d origin, Vec3d velocity, int impactEnergy) {
        return new ShotSpec(origin, velocity, 0.25D, 1.0D, DEFAULT_LIFETIME_TICKS, impactEnergy,
                ImpactKind.KINETIC, null, null, ShotEnvironment.VACUUM, null);
    }

    public ShotSpec withKind(ImpactKind newKind) {
        return new ShotSpec(origin, velocity, radius, mass, lifetimeTicks, impactEnergy, newKind, owner,
                faction, environment, guidance);
    }

    public ShotSpec withLifetime(int ticks) {
        return new ShotSpec(origin, velocity, radius, mass, ticks, impactEnergy, kind, owner, faction,
                environment, guidance);
    }

    public ShotSpec withBody(double newRadius, double newMass) {
        return new ShotSpec(origin, velocity, newRadius, newMass, lifetimeTicks, impactEnergy, kind,
                owner, faction, environment, guidance);
    }

    public ShotSpec withOwner(UUID newOwner, String newFaction) {
        return new ShotSpec(origin, velocity, radius, mass, lifetimeTicks, impactEnergy, kind, newOwner,
                newFaction, environment, guidance);
    }

    public ShotSpec withEnvironment(ShotEnvironment newEnvironment) {
        return new ShotSpec(origin, velocity, radius, mass, lifetimeTicks, impactEnergy, kind, owner,
                faction, newEnvironment, guidance);
    }

    /**
     * Attach a guidance token. Nothing steers today: the substrate carries this and persists it so
     * that the layer which eventually does the steering has somewhere to say what it is steering
     * towards, without every shot in flight at that moment becoming unreadable.
     */
    public ShotSpec withGuidance(String newGuidance) {
        return new ShotSpec(origin, velocity, radius, mass, lifetimeTicks, impactEnergy, kind, owner,
                faction, environment, newGuidance);
    }

    /** Where the round starts, in WORLD coordinates. */
    public Vec3d getOrigin() {
        return origin;
    }

    /** Velocity in WORLD coordinates, blocks per tick. */
    public Vec3d getVelocity() {
        return velocity;
    }

    /** Body radius in blocks. Carried for the layers that draw and size it; the crossing test is a ray. */
    public double getRadius() {
        return radius;
    }

    /** Body mass. Carried for the layers that compute recoil and momentum transfer. */
    public double getMass() {
        return mass;
    }

    public int getLifetimeTicks() {
        return lifetimeTicks;
    }

    /** What it is worth on arrival, in shield-energy-equivalent units. */
    public int getImpactEnergy() {
        return impactEnergy;
    }

    public ImpactKind getKind() {
        return kind;
    }

    /** Who fired it, or null. A token the substrate never reads. */
    public UUID getOwner() {
        return owner;
    }

    /** Whose side it is on, or null. A token the substrate never reads. */
    public String getFaction() {
        return faction;
    }

    public ShotEnvironment getEnvironment() {
        return environment;
    }

    /** The reserved guidance token, or null. Nothing steers in stage 1; see {@link #withGuidance}. */
    public String getGuidance() {
        return guidance;
    }
}
