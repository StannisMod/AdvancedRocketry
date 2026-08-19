package zmaster587.advancedRocketry.api.damage;

import net.minecraft.util.math.Vec3d;

/**
 * The facts a travelling body has when it meets something — and nothing about what is carrying them.
 *
 * <h3>Why the facts and not the record</h3>
 * <p>Armour answers bodies. A shell fired from a gun is one; a bolt is one; a beam somebody is HOLDING
 * on a hull is one too, and it is not a shot in any registry — it has no lifetime, no position that
 * survives a tick and nothing to step. A seam that took the shot record would therefore serve exactly
 * one weapon family, and every later family would arrive with a choice between inventing a fake shot
 * and duplicating the armour behind it. Taking the facts costs one small object per contact and makes
 * armour something the whole game can be built against.</p>
 *
 * <h3>The identity is given, not minted here</h3>
 * <p>{@link #getImpactId()} is what the damage service's duplicate memory keys on, so it must come
 * from whatever owns the body's continuity across ticks: a shot mints one per impact from its own
 * sequence, a held beam mints one per tick it is held. This class does not know how long the thing it
 * describes has existed and must not guess.</p>
 */
public final class TravellingBody {

    private final long impactId;
    private final Vec3d velocity;
    private final ImpactKind kind;
    private final int energy;
    private final double radius;

    /**
     * @param impactId identity for THIS meeting, distinct from every other in this world
     * @param velocity the body's velocity in WORLD terms, blocks per tick
     * @param kind     what sort of arrival this is, in the hull's own vocabulary
     * @param energy   what it is still worth on arrival
     * @param radius   the body's radius in blocks — its cross-section is what the material resists
     */
    public TravellingBody(long impactId, Vec3d velocity, ImpactKind kind, int energy, double radius) {
        this.impactId = impactId;
        this.velocity = velocity;
        this.kind = kind;
        this.energy = Math.max(0, energy);
        this.radius = Math.max(0.0D, radius);
    }

    public long getImpactId() {
        return impactId;
    }

    /** WORLD velocity, blocks per tick. The block's own frame is derived at the seam, never here. */
    public Vec3d getVelocity() {
        return velocity;
    }

    public ImpactKind getKind() {
        return kind;
    }

    public int getEnergy() {
        return energy;
    }

    public double getRadius() {
        return radius;
    }

    /** The direction it is travelling, or straight down when it is not travelling at all. */
    public Vec3d getDirection() {
        if (velocity == null) {
            return new Vec3d(0.0D, -1.0D, 0.0D);
        }
        double speed = velocity.lengthVector();
        return speed <= 1.0E-9D ? new Vec3d(0.0D, -1.0D, 0.0D) : velocity.scale(1.0D / speed);
    }
}
