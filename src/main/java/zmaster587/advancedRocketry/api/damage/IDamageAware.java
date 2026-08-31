package zmaster587.advancedRocketry.api.damage;

/**
 * A unit that wants to know what broke it.
 *
 * <h3>The layer above computes nothing on your behalf</h3>
 * <p>What being damaged DOES to a unit is the unit's own to work out — the derate, the probability of
 * a failure, what the failure looks like. A damaged engine gives less thrust because it throttles
 * itself back to stay safe, not because anything above it lowered a number, and a crew that overrides
 * that takes the risk knowingly. So this hands you the facts and wants nothing back.</p>
 *
 * <h3>Told, not asked</h3>
 * <p>{@link #onDamage} returns nothing. There is no answer the damage layer would act on: the stage is
 * already written, the budget is already spent, and the unit's reaction is the unit's business. If you
 * want to change what happens to the thing that HIT you, that is a different seam entirely — see
 * {@link IContactResponder}, which is asked BEFORE anything is spent and whose answer decides the
 * body's fate. A block may implement both; they are two different sentences about it.</p>
 *
 * <h3>Carried as a capability, unlike the contact seam</h3>
 * <p>A contact is asked of the BLOCK as much as the tile, because two thousand vanilla blocks have no
 * tile and still have to be met. An occurrence is only meaningful to something with state to change,
 * and anything with state has a tile — so this rides
 * {@link zmaster587.advancedRocketry.api.capability.CapabilityDamageAware}, which also lets a foreign
 * tile be given one without subclassing anything.</p>
 */
public interface IDamageAware {

    /**
     * Something happened to this unit. Called on the SERVER, after the stage has been written and, for
     * a fatal blow, after the block itself is gone — the unit is being told about its own destruction,
     * which is exactly the case it most needs and the one it would never hear if it were told earlier
     * or later. {@link DamageOccurrence#isDestroyed()} is how you tell.
     *
     * <p>Throwing from here is not a way to refuse an occurrence: the stage is already written and the
     * budget already spent, so an exception loses the news and changes nothing else.</p>
     */
    void onDamage(DamageOccurrence occurrence);
}
