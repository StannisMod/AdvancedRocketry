package zmaster587.advancedRocketry.api.damage;

/**
 * Why the spend stopped. This is the field that separates outcomes a bare
 * {@link DamageOutcome#NOTHING_STRUCK} would blur together — in particular "there was nothing there"
 * from "there may well be something there, but it is not loaded, so ask again later".
 */
public enum StopReason {

    /** The budget ran out inside structure. Pairs with {@link DamageOutcome#ABSORBED}. */
    BUDGET_EXHAUSTED,

    /** Nothing damageable stood in the way. Pairs with {@link DamageOutcome#NOTHING_STRUCK}. */
    NO_CANDIDATES,

    /** The path left the structure with budget to spare. Pairs with {@link DamageOutcome#EXITED}. */
    EXITED_FAR_SIDE,

    /**
     * The granted PATH ran out while the body was still inside structure, with budget in hand.
     * Pairs with {@link DamageOutcome#EXITED}, because the budget is handed back either way — and
     * that shared outcome is exactly why this reason has to exist separately.
     *
     * <p>"Budget left over" and "came out the other side" are different facts, and a caller that
     * has to decide whether the body is still IN there can only tell them apart here. Reported as
     * {@code EXITED_FAR_SIDE} until 2026-08-20, which told a round that had bored a fifth of a
     * block that it had left the plate.</p>
     */
    REACH_EXHAUSTED,

    /**
     * The target region is not loaded, so nothing could be resolved. <b>Not</b> a statement that there
     * is nothing there — a caller able to retry should, and one that treats this as "clean miss"
     * silently loses shots into unloaded space.
     */
    TARGET_UNLOADED,

    /**
     * This impact identity was applied already and was refused a second time. Zero spend, nothing
     * touched. A retrying caller sees its own earlier success, not a new one.
     */
    DUPLICATE_IMPACT
}
