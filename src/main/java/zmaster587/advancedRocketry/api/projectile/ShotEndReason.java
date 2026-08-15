package zmaster587.advancedRocketry.api.projectile;

/**
 * Why a shot stopped existing. A shot always ends for exactly one stated reason — "it is no longer in
 * the registry" is not an outcome anybody can act on, and a weapon that cannot tell a hit from a
 * timeout cannot report a miss.
 */
public enum ShotEndReason {

    /** It ran out its declared lifetime without meeting anything. */
    EXPIRED,

    /** A shell paid for it in full and it had no body left to send anywhere. */
    FIELD_ABSORBED,

    /**
     * A shell mirrored it, and what came back was slower than the speed floor. Unlike an entity, which
     * has to end up somewhere and so gets nudged, a shot record has the better option of ceasing to
     * exist rather than loitering at the shell at nearly zero velocity.
     */
    REFLECTED_TOO_SLOW,

    /** It met structure and its impact was handed to the damage service. */
    STRUCTURE_IMPACT,

    /** Its world went away underneath it. */
    WORLD_UNLOADED
}
