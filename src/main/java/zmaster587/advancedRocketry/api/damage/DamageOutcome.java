package zmaster587.advancedRocketry.api.damage;

/** What became of a declared impact. The branch point a shot reads to decide its own fate. */
public enum DamageOutcome {

    /** The impact met no structure at all — nothing was spent and nothing was touched. */
    NOTHING_STRUCK,

    /** Structure took the whole budget: the impact ends inside what it struck. */
    ABSORBED,

    /** Structure did not consume the whole budget and the impact left the far side, still carrying it. */
    EXITED
}
