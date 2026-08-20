package zmaster587.advancedRocketry.universe;

import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

/**
 * The one law above every galaxy: how much bigger the universe is at tick {@code t} than it was when
 * the world was made.
 *
 * <p>{@code a(0) = 1} by definition — <b>{@code t = 0} is world creation, so the universe's age is the
 * save's age.</b> That is not an approximation to something else; there is no other clock the universe
 * layer could be measured against.</p>
 *
 * <h3>Expansion is MONOTONE, where rotation is not</h3>
 * <p>A shear-separated target comes back: two systems at different galactic radii drift apart and then
 * together again, because {@code theta} wraps. An expansion-separated one does not. {@code a(t)} only
 * ever grows, so a galaxy that recedes past a drive's reach has receded permanently — and that is a
 * stronger claim about a player's world than "the sky moves slowly", which is why it is written down
 * here rather than left implicit in a formula.</p>
 *
 * <h3>Which clock</h3>
 * <p>Everything here is per TICK through the ORBITAL CALENDAR: a year is
 * {@link AstronomicalBodyHelper#DAYS_PER_YEAR} days because that is the period of a one-AU orbit about
 * a one-solar-mass star, and every other rate in this layer is quoted against the same year. Reading a
 * tick as a twentieth of a REAL second instead would put a planet's year and a galaxy's recession on
 * two different clocks, and the two would disagree by a factor of 548.</p>
 *
 * <h3>Scale</h3>
 * <p>The galaxy lattice is compressed against reality (see {@link UniverseScale}), and the Hubble
 * constant is NOT compressed with it — it is the real one. The consequence is deliberate and physical:
 * at 75 000 light years apart, neighbouring galaxies recede at about 1.6 km/s while their own peculiar
 * velocities run in the hundreds. So this universe behaves like a bound GROUP, where peculiar motion
 * dominates and expansion is the slow background — which is exactly what a real galaxy group does.</p>
 */
public final class Cosmology {

    /** The Hubble constant in km/s per megaparsec — the measured one, uncompressed. */
    public static final double HUBBLE_KM_S_PER_MEGAPARSEC = 70d;

    /** Light years in one megaparsec — what carries the Hubble constant into this layer's unit. */
    public static final double LIGHT_YEARS_PER_MEGAPARSEC = 3_261_563.777d;

    /**
     * The fractional rate at which every intergalactic separation grows, per tick. Derived, never
     * written as a literal: it is the Hubble constant expressed in this layer's length and this
     * layer's clock.
     */
    public static final double HUBBLE_PER_TICK =
            UniverseScale.lightYearsPerTick(HUBBLE_KM_S_PER_MEGAPARSEC) / LIGHT_YEARS_PER_MEGAPARSEC;

    /**
     * The horizon a galaxy's drift is BOUNDED against, in ticks — about 870 years of world time, or a
     * couple of real years of continuous play.
     *
     * <p>A galaxy that wandered out of its own lattice cell would break three things at once:
     * at-most-one-galaxy-per-cell, non-overlap, and the O(1) answer to "which galaxy is this point in",
     * which reads the containing cell and nothing else. So a drawn velocity is clamped to keep the
     * galaxy inside its cell for at least this long. At realistic speeds the clamp is five orders away
     * from binding, which is the point of measuring it rather than asserting it.</p>
     */
    public static final long DRIFT_HORIZON_TICKS = 1_000_000_000L;

    private Cosmology() {
    }

    /**
     * How much bigger the universe is at {@code tick} than at world creation. {@code a(0) = 1}, and it
     * only ever grows.
     */
    public static double scaleFactorAt(long tick) {
        return Math.exp(HUBBLE_PER_TICK * (double) tick);
    }
}
