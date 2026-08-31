package zmaster587.advancedRocketry.projectile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.Vec3d;
import zmaster587.advancedRocketry.api.damage.ImpactKind;
import zmaster587.advancedRocketry.api.projectile.ShotEnvironment;
import zmaster587.advancedRocketry.api.projectile.ShotSpec;

import java.util.UUID;

/**
 * A shot in flight — a record in a registry, deliberately <b>not</b> an entity.
 *
 * <h3>Why not an entity</h3>
 * <p>An entity is only simulated where the world is loaded and only tracked near a player. A round
 * that has to cross kilometres therefore has two futures as an entity, and both are wrong: it dies
 * quietly the moment it leaves the shooter's bubble, which makes long-range fire a lie, or the server
 * keeps a corridor of world loaded along every trajectory, which makes firing an attack on the host.
 * A record is simulated by its world's own tick regardless of who is watching, and costs three
 * vectors.</p>
 *
 * <h3>Which frame its position is in — and why that is not always the world's</h3>
 * <p>A free-flying shot is expressed in world coordinates. A shot that is <em>inside somebody's
 * material</em> is expressed in that hull's own subspace frame instead ({@link #getHullId()}), because
 * a hull is a thing that manoeuvres: a round stored in world coordinates would be left behind by a
 * ship that moved between two ticks and would resume the next tick outside the plate it was drilling,
 * or inside a plate that had sailed on. Riding the hull's frame makes boring through a hard-turning
 * ship and boring through a parked one the same arithmetic.</p>
 *
 * <h3>Mutable, and owned by exactly one thing</h3>
 * <p>Position, velocity, age and the remaining impact energy change every tick; everything else is
 * fixed at the muzzle. The record is owned by the {@link ShotRegistry} of one world and is mutated
 * only by {@link ShotSubstrate} while stepping it — server side. A client never simulates a shot: it
 * would be describing a different flight from the one that is going to hit somebody.</p>
 */
public final class Shot {

    private final long id;
    private final double radius;
    private final double mass;
    private final ImpactKind kind;
    private final UUID owner;
    private final String faction;
    private final String guidance;
    private final ShotEnvironment environment;
    private final int lifetimeTicks;

    private Vec3d position;
    private Vec3d velocity;
    /**
     * The hull this shot is currently inside, or null when it is in free flight. While it is set,
     * {@link #position} and {@link #velocity} are expressed in THAT ship's subspace frame, and the
     * velocity is relative to the hull rather than to the world.
     */
    private String hullId;
    private int age;
    private int impactEnergy;

    Shot(long id, ShotSpec spec) {
        this.id = id;
        this.radius = spec.getRadius();
        this.mass = spec.getMass();
        this.kind = spec.getKind();
        this.owner = spec.getOwner();
        this.faction = spec.getFaction();
        this.guidance = spec.getGuidance();
        this.environment = spec.getEnvironment();
        this.lifetimeTicks = spec.getLifetimeTicks();
        this.position = spec.getOrigin();
        this.velocity = spec.getVelocity();
        this.impactEnergy = spec.getImpactEnergy();
        this.hullId = null;
        this.age = 0;
    }

    private Shot(long id, double radius, double mass, ImpactKind kind, UUID owner, String faction,
                 String guidance, ShotEnvironment environment, int lifetimeTicks, Vec3d position,
                 Vec3d velocity, String hullId, int age, int impactEnergy) {
        this.id = id;
        this.radius = radius;
        this.mass = mass;
        this.kind = kind;
        this.owner = owner;
        this.faction = faction;
        this.guidance = guidance;
        this.environment = environment;
        this.lifetimeTicks = lifetimeTicks;
        this.position = position;
        this.velocity = velocity;
        this.hullId = hullId;
        this.age = age;
        this.impactEnergy = impactEnergy;
    }

    public long getId() {
        return id;
    }

    /**
     * Position in the frame this shot is currently kept in: the WORLD's while it flies, its hull's
     * SUBSPACE while it bores. Anything that needs world coordinates regardless asks
     * {@link ShotFrame#worldPosition}.
     */
    public Vec3d getPosition() {
        return position;
    }

    /**
     * Velocity in the same frame as {@link #getPosition()}, blocks per tick — so while this shot is
     * inside a hull, it is the velocity RELATIVE to that hull, which is the one boring is done with.
     * {@link ShotFrame#worldVelocity} adds the hull's own motion back.
     */
    public Vec3d getVelocity() {
        return velocity;
    }

    /**
     * The hull this shot is inside, or null in free flight. Non-null means every coordinate on this
     * record is that ship's subspace.
     */
    public String getHullId() {
        return hullId;
    }

    public double getSpeed() {
        return velocity.lengthVector();
    }

    public int getAge() {
        return age;
    }

    public int getLifetimeTicks() {
        return lifetimeTicks;
    }

    /** What is left to spend on arrival; a shell that pays only part of the cost lowers this. */
    public int getImpactEnergy() {
        return impactEnergy;
    }

    public ImpactKind getKind() {
        return kind;
    }

    public double getRadius() {
        return radius;
    }

    public double getMass() {
        return mass;
    }

    public UUID getOwner() {
        return owner;
    }

    public String getFaction() {
        return faction;
    }

    /** The reserved guidance token, or null. Nothing reads it in stage 1; it round-trips a save. */
    public String getGuidance() {
        return guidance;
    }

    public ShotEnvironment getEnvironment() {
        return environment;
    }

    void setPosition(Vec3d newPosition) {
        this.position = newPosition;
    }

    void setVelocity(Vec3d newVelocity) {
        this.velocity = newVelocity;
    }

    /** Declare which frame the coordinates above are in. Only {@link ShotFrame} may say. */
    void setHullId(String newHullId) {
        this.hullId = newHullId;
    }

    void setImpactEnergy(int newImpactEnergy) {
        this.impactEnergy = Math.max(0, newImpactEnergy);
    }

    void incrementAge() {
        this.age++;
    }

    NBTTagCompound writeToNBT() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setLong("id", id);
        nbt.setDouble("radius", radius);
        nbt.setDouble("mass", mass);
        nbt.setString("kind", kind.name());
        if (owner != null) {
            nbt.setString("owner", owner.toString());
        }
        if (faction != null) {
            nbt.setString("faction", faction);
        }
        if (guidance != null) {
            nbt.setString("guidance", guidance);
        }
        nbt.setDouble("gravity", environment.getGravityPerTickSquared());
        nbt.setInteger("lifetime", lifetimeTicks);
        nbt.setDouble("posX", position.x);
        nbt.setDouble("posY", position.y);
        nbt.setDouble("posZ", position.z);
        nbt.setDouble("velX", velocity.x);
        nbt.setDouble("velY", velocity.y);
        nbt.setDouble("velZ", velocity.z);
        if (hullId != null) {
            // Which frame the position above is in. Without it a reloaded save reads a subspace
            // triple as a world one and the round reappears in the shipyard.
            nbt.setString("hull", hullId);
        }
        nbt.setInteger("age", age);
        nbt.setInteger("energy", impactEnergy);
        return nbt;
    }

    static Shot readFromNBT(NBTTagCompound nbt) {
        ImpactKind kind;
        try {
            kind = ImpactKind.valueOf(nbt.getString("kind"));
        } catch (IllegalArgumentException wrongName) {
            // A save written by a build that knew a kind this one does not. Losing the shot is worse
            // than billing it as the commonest kind there is.
            kind = ImpactKind.KINETIC;
        }
        UUID owner = nbt.hasKey("owner") ? parseUuid(nbt.getString("owner")) : null;
        return new Shot(nbt.getLong("id"), nbt.getDouble("radius"), nbt.getDouble("mass"), kind, owner,
                nbt.hasKey("faction") ? nbt.getString("faction") : null,
                nbt.hasKey("guidance") ? nbt.getString("guidance") : null,
                ShotEnvironment.gravity(nbt.getDouble("gravity")),
                nbt.getInteger("lifetime"),
                new Vec3d(nbt.getDouble("posX"), nbt.getDouble("posY"), nbt.getDouble("posZ")),
                new Vec3d(nbt.getDouble("velX"), nbt.getDouble("velY"), nbt.getDouble("velZ")),
                nbt.hasKey("hull") ? nbt.getString("hull") : null,
                nbt.getInteger("age"), nbt.getInteger("energy"));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "Shot#" + id + "[pos=" + position + " vel=" + velocity
                + (hullId == null ? "" : " in hull " + hullId) + " energy=" + impactEnergy
                + " age=" + age + "/" + lifetimeTicks + "]";
    }
}
