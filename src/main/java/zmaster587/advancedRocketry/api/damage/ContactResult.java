package zmaster587.advancedRocketry.api.damage;

import net.minecraft.util.math.Vec3d;

/**
 * What a block answered when a travelling body met it.
 *
 * <h3>Three behaviours, and a way to have none</h3>
 * <ul>
 *   <li>{@link #noOpinion} — <b>this block has nothing to say about THIS body</b>, and the default law
 *       applies exactly as if it answered nothing at all. It is not a behaviour, it is a declining to
 *       have one, and it exists because the alternative was to decline by saying
 *       {@code passedThrough(everything)} — which is a real answer meaning "through, for free". A
 *       mirror shipped saying that about slugs and became an armour plate that kinetic fire could
 *       neither pay for nor break.</li>
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

    /**
     * The one instance of "nothing to say". A singleton because it carries no facts: two declinings
     * are the same declining, and giving it a residual energy would invite somebody to read one.
     */
    private static final ContactResult NO_OPINION = new ContactResult(false, 0, null, true);

    private final boolean stopped;
    private final int residualEnergy;
    private final Vec3d deflectedVelocity;
    private final boolean noOpinion;

    private ContactResult(boolean stopped, int residualEnergy, Vec3d deflectedVelocity,
                          boolean noOpinion) {
        this.stopped = stopped;
        this.residualEnergy = Math.max(0, residualEnergy);
        this.deflectedVelocity = deflectedVelocity;
        this.noOpinion = noOpinion;
    }

    /**
     * This block declines to answer for this body: the default law applies, exactly as it does for the
     * two thousand blocks that implement nothing at all.
     *
     * <p>A responder answers for the arrivals it has a mechanism for and declines for the rest —
     * a mirror has a law about light and none about a solid round, and the round should then be priced
     * and resisted like any other piece of glass. Whoever declines here is asking for the ordinary
     * treatment, not asking to be skipped.</p>
     */
    public static ContactResult noOpinion() {
        return NO_OPINION;
    }

    /** True when this block declined to answer and the default law should decide instead. */
    public boolean isNoOpinion() {
        return noOpinion;
    }

    /** The body carries on along its own course with {@code residualEnergy} left. */
    public static ContactResult passedThrough(int residualEnergy) {
        return new ContactResult(false, residualEnergy, null, false);
    }

    /** Nothing continues past this block. */
    public static ContactResult stopped() {
        return new ContactResult(true, 0, null, false);
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
        return new ContactResult(false, residualEnergy, newVelocity, false);
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
