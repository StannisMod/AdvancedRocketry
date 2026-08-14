package zmaster587.advancedRocketry.api.damage;

/**
 * Which blocks a damage budget is allowed to spend itself on. One engine, a pluggable rule: the
 * budget-and-spend loop is identical for every mode and only the candidate order differs.
 */
public enum SelectionMode {

    /**
     * Weighted by subsystem class rather than by geometry — power-carrying blocks first. The mode an
     * emergency exit uses: it has a ship and a severity, and no impact point at all.
     */
    POWER_BIASED,

    /**
     * Candidates on the incidence SIDE, nearest-first along the incidence normal. Designed for a
     * hazard that bathes one flank (a star's plasma), where "the side facing it" is the whole of the
     * geometry — <em>not</em> for a solid round, which makes a hole where it struck.
     */
    DIRECTIONAL,

    /** Every candidate equally likely; no geometry and no bias. */
    UNIFORM,

    /**
     * Walks the voxel ray from the entry point along the impact direction, spending on each block it
     * meets until the budget runs out or it leaves the far side. This is the mode that gives a shot a
     * penetration depth and an exit point, and so the one that lets two weapons of equal energy behave
     * differently.
     */
    PENETRATING
}
