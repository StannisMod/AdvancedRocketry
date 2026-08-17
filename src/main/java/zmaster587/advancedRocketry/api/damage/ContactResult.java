package zmaster587.advancedRocketry.api.damage;

import net.minecraft.util.math.Vec3d;

/**
 * What a block answered when a travelling body met it.
 *
 * <h3>Three states, four behaviours</h3>
 * <ul>
 *   <li>{@link #passedThrough} — the body carries on, worth less. The default: it is what an ordinary
 *       block does, and what "weakened penetration" means.</li>
 *   <li>{@link #stopped} — nothing continues past this block. <b>Reactive armour is this</b>, plus the
 *       block spending itself; a block that detonates its own charge answers {@code stopped} and takes
 *       care of its own destruction, because what a unit does about its own damage is the unit's
 *       (see the damage-occurrence interface).</li>
 *   <li>{@link #deflected} — the body continues somewhere else. <b>Mirror armour and an angle
 *       ricochet are both this</b>: they differ only in who computed the new velocity — the block's
 *       own law, or the default law from the surface normal and the incidence angle.</li>
 * </ul>
 *
 * <p>Deflection is not a special case a reader has to know about: one that has never heard of mirror
 * armour still reads "the body did not stop here", which stays true. The same discipline the shield's
 * own strike result keeps for its reflection.</p>
 */
public final class ContactResult {

    private final boolean stopped;
    private final int residualEnergy;
    private final Vec3d deflectedVelocity;

    private ContactResult(boolean stopped, int residualEnergy, Vec3d deflectedVelocity) {
        this.stopped = stopped;
        this.residualEnergy = Math.max(0, residualEnergy);
        this.deflectedVelocity = deflectedVelocity;
    }

    /** The body carries on along its own course with {@code residualEnergy} left. */
    public static ContactResult passedThrough(int residualEnergy) {
        return new ContactResult(false, residualEnergy, null);
    }

    /** Nothing continues past this block. */
    public static ContactResult stopped() {
        return new ContactResult(true, 0, null);
    }

    /**
     * The body leaves along {@code newVelocity} with {@code residualEnergy} left.
     *
     * <p>A null or motionless {@code newVelocity} degrades to {@link #stopped()} rather than claiming
     * a deflection with nowhere to go — the same refusal the shield's reflection makes, and for the
     * same reason: a body deflected to a standstill is a body that stopped.</p>
     */
    public static ContactResult deflected(Vec3d newVelocity, int residualEnergy) {
        if (newVelocity == null || newVelocity.lengthVector() <= 1.0E-9D) {
            return stopped();
        }
        return new ContactResult(false, residualEnergy, newVelocity);
    }

    /** True when nothing continues past the block that answered. */
    public boolean isStopped() {
        return stopped;
    }

    /** True when the body continues, but along a course this block chose. */
    public boolean isDeflected() {
        return deflectedVelocity != null;
    }

    /** What the body still carries; {@code 0} when it stopped. */
    public int getResidualEnergy() {
        return residualEnergy;
    }

    /** The course the body leaves on, or {@code null} when it kept its own. */
    public Vec3d getDeflectedVelocity() {
        return deflectedVelocity;
    }
}
