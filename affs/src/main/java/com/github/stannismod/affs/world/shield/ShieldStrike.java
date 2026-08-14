package com.github.stannismod.affs.world.shield;

import com.github.stannismod.affs.config.ModConfig;
import net.minecraft.util.math.Vec3d;

/**
 * A single declared strike against the shield — the cooperative strike interface (D134-2 tier-1). A
 * cooperating weapon (first implementer = an AR turret; the same seam serves future third-party
 * integrations) builds one of these and hands it to {@link ShieldStrikeService#resolve}, which absorbs
 * it precisely: the energy is <em>known</em>, not guessed from velocity.
 *
 * <p>The strike is modelled as a ray so the service can find where it meets the field. {@code impactEnergy}
 * is the strike's own declared energy in shield-energy-equivalent units, before the shield applies its
 * kind and tier multipliers. A source that reports weapon <em>damage</em> rather than energy can convert
 * via {@link #fromDamage} using the tunable {@code shieldStrikeDamageToEnergyFactor} (D134-2, axis G).</p>
 *
 * <p>A strike may additionally declare the <em>travelling body</em> it carries, as a velocity vector: its
 * presence IS the statement "there is a body here", and its absence the statement that there is not.
 * That distinction is what a shot which exists as a registry record rather than as a Forge {@code Entity}
 * needs — the body is real, it simply is not something the field's per-tick entity scan can see. A
 * declared kinetic strike <em>with</em> a body is mirrored off the shell by
 * {@link ShieldStrikeService#resolve}; one <em>without</em> is absorbed, as it always was. The energy stays
 * declared either way: a velocity being available is not a reason to start inferring what the caller can
 * state.</p>
 */
public final class ShieldStrike {

    private final Vec3d origin;
    private final Vec3d direction;
    private final double maxDistance;
    private final int impactEnergy;
    private final ShieldStrikeKind kind;
    private final boolean unblockable;
    private final Vec3d bodyVelocity;

    public ShieldStrike(Vec3d origin, Vec3d direction, double maxDistance, int impactEnergy,
                        ShieldStrikeKind kind, boolean unblockable) {
        this(origin, direction, maxDistance, impactEnergy, kind, unblockable, null);
    }

    public ShieldStrike(Vec3d origin, Vec3d direction, double maxDistance, int impactEnergy,
                        ShieldStrikeKind kind, boolean unblockable, Vec3d bodyVelocity) {
        this.origin = origin;
        this.direction = normalize(direction);
        this.maxDistance = Math.max(0.0D, maxDistance);
        this.impactEnergy = Math.max(0, impactEnergy);
        this.kind = kind == null ? ShieldStrikeKind.RADIANT : kind;
        this.unblockable = unblockable;
        this.bodyVelocity = bodyVelocity;
    }

    /** A blockable beam of the given declared energy. */
    public static ShieldStrike beam(Vec3d origin, Vec3d direction, double maxDistance, int impactEnergy,
                                    ShieldStrikeKind kind) {
        return new ShieldStrike(origin, direction, maxDistance, impactEnergy, kind, false);
    }

    /**
     * A kinetic strike that declares the travelling body behind it — a shot that exists as a record
     * rather than as an entity. Full absorption reflects it; see {@link ShieldStrikeResult#reflected}.
     */
    public static ShieldStrike kineticBody(Vec3d origin, Vec3d direction, double maxDistance,
                                           int impactEnergy, Vec3d bodyVelocity) {
        return new ShieldStrike(origin, direction, maxDistance, impactEnergy, ShieldStrikeKind.KINETIC,
                false, bodyVelocity);
    }

    /** A beam whose declared energy is derived from a weapon's damage value (axis G tunable factor). */
    public static ShieldStrike fromDamage(Vec3d origin, Vec3d direction, double maxDistance, double damage,
                                          ShieldStrikeKind kind) {
        int energy = (int) Math.ceil(Math.max(0.0D, damage) * ModConfig.shieldStrikeDamageToEnergyFactor);
        return new ShieldStrike(origin, direction, maxDistance, energy, kind, false);
    }

    public Vec3d getOrigin() {
        return origin;
    }

    /** Unit direction (zero vector if the supplied direction was degenerate). */
    public Vec3d getDirection() {
        return direction;
    }

    public double getMaxDistance() {
        return maxDistance;
    }

    public int getImpactEnergy() {
        return impactEnergy;
    }

    public ShieldStrikeKind getKind() {
        return kind;
    }

    /** Bypasses the shield entirely (matches vanilla unblockable damage, D134-2 axis G). */
    public boolean isUnblockable() {
        return unblockable;
    }

    /** The declared travelling body's world velocity, or null when the strike carries no body. */
    public Vec3d getBodyVelocity() {
        return bodyVelocity;
    }

    /** True when this strike declares a travelling body (the thing the entity scan cannot see). */
    public boolean hasBody() {
        return bodyVelocity != null;
    }

    private static Vec3d normalize(Vec3d v) {
        if (v == null) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        double len = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
        if (len <= 1.0E-8D) {
            return new Vec3d(0.0D, 0.0D, 0.0D);
        }
        return new Vec3d(v.x / len, v.y / len, v.z / len);
    }
}
