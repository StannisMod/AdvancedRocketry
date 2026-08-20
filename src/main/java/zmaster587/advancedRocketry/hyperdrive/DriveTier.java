package zmaster587.advancedRocketry.hyperdrive;

import zmaster587.advancedRocketry.universe.UniverseScale;

/**
 * A hyperdrive's generation, and the only thing it changes: how efficiently power becomes speed.
 *
 * <h3>A tier is a coefficient, never a licence</h3>
 * <p>There is no permission gate anywhere on this enum. A first-generation drive aimed across
 * interstellar space is not refused — it simply goes much slower, which makes the trip unreasonable
 * rather than impossible, and the barrier a player then meets is life support and generation without
 * sunlight over that duration. Real systems and real risks, not a red message.</p>
 *
 * <h3>Why the tiers are the bands, and why there are exactly two</h3>
 * <p>Distance in this universe is not smooth: it comes in bands separated by orders of magnitude —
 * across a system, out to the nearest stars, across a galaxy, out to the next one. Growing a drive
 * (more coils) is spent ONCE and closes the first of those gaps; after that the coils are gone, so a
 * tier has to pay a WHOLE band gap rather than a residue. A tier therefore exists for each gap that
 * building bigger cannot cover, and each one is NAMED for the band it owns.</p>
 *
 * <p>The gap out to the next galaxy is only {@link UniverseScale#GALAXY_SEPARATION_IN_DIAMETERS}, far
 * below what one generation of drive is worth, so there is no third tier: reaching another galaxy is
 * patience at full {@link #GALACTIC}, which is an honest answer rather than a refusal.</p>
 */
public enum DriveTier {

    /**
     * The drive a player builds himself. Its band is his own neighbourhood of stars, and it closes
     * that band by SIZE — the coil count — rather than by efficiency, which is why its efficiency is
     * the unit: every other tier is quoted against it.
     */
    INTERSTELLAR(1d),

    /**
     * The drive that makes a galaxy crossable. Its efficiency is not a chosen number: it IS the gap
     * between the two bands, a galaxy's diameter measured in interstellar steps, so it is derived from
     * the two lengths the universe layer already declares and moves with them if they ever move.
     *
     * <p>That derivation is the point. Written as a literal it would be a number nobody could check
     * and one that silently stopped meaning "one band" the first time the star separation or the
     * galaxy size was retuned.</p>
     */
    GALACTIC(2d * UniverseScale.REFERENCE_GALAXY_RADIUS_LY / UniverseScale.MEAN_STAR_SEPARATION_LY);

    private final double efficiency;

    DriveTier(double efficiency) {
        this.efficiency = Math.max(1d, efficiency);
    }

    /**
     * How much more speed this generation gets out of the same power as {@link #INTERSTELLAR}, which
     * is 1 by definition.
     *
     * <p>It sits in the DENOMINATOR of a route's total energy — ticks are {@code d·m/(η·P)} and the
     * in-flight draw is proportional to {@code P}, so power cancels and the bill for a leg depends on
     * distance, mass and the tier alone. "A tier buys efficiency" is therefore literal arithmetic and
     * not a figure of speech.</p>
     */
    public double efficiency() {
        return efficiency;
    }

    /** The band this generation is built to cross in the time one band is meant to take. */
    public double bandLightYears() {
        return this == GALACTIC
                ? 2d * UniverseScale.REFERENCE_GALAXY_RADIUS_LY
                : UniverseScale.MEAN_STAR_SEPARATION_LY;
    }

    /** The generation every hull has until a later one is built. */
    public static DriveTier baseline() {
        return INTERSTELLAR;
    }

    /** The tier stored under {@code ordinal}, or the baseline when the value is not one of ours. */
    public static DriveTier byOrdinal(int ordinal) {
        DriveTier[] all = values();
        return (ordinal < 0 || ordinal >= all.length) ? baseline() : all[ordinal];
    }
}
