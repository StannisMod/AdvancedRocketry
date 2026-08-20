package zmaster587.advancedRocketry.universe;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;

/**
 * How bright a star LOOKS from somewhere else — the photometry a telescope is bounded by.
 *
 * <p>An instrument does not reach a distance; it reaches a BRIGHTNESS. Everything a survey can find
 * is what stands above its limiting magnitude, and distance enters only through the inverse-square
 * law that dims things. Stating an instrument's reach as a length is therefore stating a consequence
 * as if it were a cause: the same telescope sees a blue giant eighty times farther than a red dwarf,
 * and no single number of light years describes both.</p>
 *
 * <p><b>The unit is already spoken here.</b> {@link Nebula#MAGNITUDES_PER_DENSITY_LIGHT_YEAR} turns a
 * dust column into magnitudes of extinction and {@link UniverseRegistry#extinctionBetween} returns
 * them, so dust and distance are two terms of ONE sum rather than two mechanics that have to be
 * reconciled. That is the whole reason a magnitude limit is the right bound and a light-year horizon
 * was the wrong one.</p>
 *
 * <p>Three quantities, in the order they are derived:</p>
 * <ol>
 *   <li><b>Luminosity</b>, from the star's own size and temperature — {@code L/L(sun) = R^2*(T/T(sun))^4},
 *       the Stefan-Boltzmann law for a sphere.</li>
 *   <li><b>Absolute magnitude</b> {@code M = M(sun) - 2.5*log10(L)} — how bright it would be at the
 *       standard ten parsecs.</li>
 *   <li><b>Apparent magnitude</b> {@code m = M + 5*log10(d/10pc) + A} — how bright it is from here,
 *       through whatever dust {@code A} lies between.</li>
 * </ol>
 *
 * <p>Magnitudes run BACKWARDS: smaller is brighter, and a difference of 5 is a factor of 100 in
 * received flux. So "brighter than the limit" reads {@code m <= limit}, which is the one place this
 * scale trips a reader who has not met it before.</p>
 */
public final class StellarMagnitude {

    private StellarMagnitude() {
    }

    /**
     * The Sun's absolute visual magnitude — the zero point the whole scale is hung from.
     *
     * <p>Measured, not chosen: 4.83 is the accepted value in the V band, and every absolute magnitude
     * below is stated relative to it. Changing it does not rescale the sky, it moves the Sun.</p>
     */
    public static final double SOLAR_ABSOLUTE_MAGNITUDE = 4.83d;

    /** Light years in one parsec — 3.26156, the conversion the magnitude law's {@code 10 pc} needs. */
    public static final double LIGHT_YEARS_PER_PARSEC = 3.26156d;

    /**
     * The temperature this layer calls the Sun's.
     *
     * <p>{@link StellarBody#getTemperature()} is in <b>units of a hundredth of Sol</b> and not in
     * kelvin, whatever its javadoc says — the stock table seats a sun-like star at 100 and a red dwarf
     * at 40, and every consumer in the mod reads it that way. It is spelled out here because this
     * class raises it to the FOURTH power, where a wrong unit is not a small error.</p>
     */
    public static final double SOLAR_TEMPERATURE_UNITS = 100d;

    /**
     * How luminous a star of {@code radiusSuns} and {@code temperatureUnits} is, in Suns.
     *
     * <p>{@code L = 4*pi*R^2*sigma*T^4} for both, divided: {@code L/L(sun) = (R/R(sun))^2*(T/T(sun))^4}.
     * The fourth power is what makes the sky's brightness so unlike its population — a blue star is
     * 0.13 % of the stars and outshines a red dwarf by nearly four orders.</p>
     */
    public static double luminositySuns(double radiusSuns, double temperatureUnits) {
        double r = Math.max(0d, radiusSuns);
        double t = Math.max(0d, temperatureUnits) / SOLAR_TEMPERATURE_UNITS;
        return r * r * t * t * t * t;
    }

    /**
     * The same for a star object.
     *
     * <p><b>An unstated temperature is read as Sol's, and that is a decision worth seeing.</b>
     * {@link StellarBody} leaves temperature at zero until something sets it, and zero raised to the
     * fourth power is a star that emits nothing — so a pack that describes a star by its size alone
     * would have written an invisible one, and it would have found out by pointing a telescope at
     * empty sky. Zero here means UNSTATED, not cold, exactly as an unstated bulk means one Earth
     * everywhere else in this layer. A star that really is dark says so by being a black hole.</p>
     */
    public static double luminositySuns(StellarBody star) {
        if (star == null) {
            return 0d;
        }
        // A black hole emits nothing a survey in the visible could catch. It is not "very faint" —
        // it is off this scale entirely, and the caller's own "never detected" branch is the right one.
        if (star.isBlackHole()) {
            return 0d;
        }
        int temperature = star.getTemperature();
        return luminositySuns(star.getSize(),
                temperature > 0 ? temperature : SOLAR_TEMPERATURE_UNITS);
    }

    /**
     * The absolute magnitude of a star of {@code luminositySuns} — how bright it would look at ten
     * parsecs. Infinite for a star that emits nothing, which is the honest answer and never a number
     * a comparison would accidentally accept.
     */
    public static double absoluteMagnitude(double luminositySuns) {
        if (!(luminositySuns > 0d)) {
            return Double.POSITIVE_INFINITY;
        }
        return SOLAR_ABSOLUTE_MAGNITUDE - 2.5d * Math.log10(luminositySuns);
    }

    /**
     * How bright a star of absolute magnitude {@code absolute} looks from {@code distanceLightYears}
     * away through {@code extinctionMagnitudes} of dust.
     *
     * <p>The distance modulus {@code 5*log10(d/10pc)} is undefined at zero distance and enormous just
     * above it, so a look from inside the star's own cell is answered with the absolute magnitude
     * alone rather than with minus infinity: standing on top of something is not an observation, and
     * a survey's own system is found by being there rather than by being seen.</p>
     */
    public static double apparentMagnitude(double absolute, double distanceLightYears,
                                           double extinctionMagnitudes) {
        if (Double.isInfinite(absolute)) {
            return Double.POSITIVE_INFINITY;
        }
        double parsecs = Math.max(0d, distanceLightYears) / LIGHT_YEARS_PER_PARSEC;
        double modulus = (parsecs <= 1e-9d) ? 0d : 5d * Math.log10(parsecs / 10d);
        return absolute + modulus + Math.max(0d, extinctionMagnitudes);
    }

    /** The same, straight from a star's own bulk — the form a detection stage calls. */
    public static double apparentMagnitudeOf(StellarBody star, double distanceLightYears,
                                             double extinctionMagnitudes) {
        return apparentMagnitude(absoluteMagnitude(luminositySuns(star)), distanceLightYears,
                extinctionMagnitudes);
    }

    /**
     * How far a star of {@code luminositySuns} stays above {@code limitMagnitude} in CLEAR sky, in
     * light years — the inverse of the distance modulus, and the number that TRUNCATES a survey.
     *
     * <p>This is what replaces a configured horizon. An instrument's reach is the range of the
     * brightest thing it could possibly see: past that nothing is detectable at any density, so the
     * walk stops rather than being stopped. Dust only ever shortens it, so a reach computed with no
     * extinction is an upper bound and a survey that walks it misses nothing.</p>
     *
     * <p>Zero for a star that emits nothing.</p>
     */
    public static double detectionRangeLightYears(double luminositySuns, double limitMagnitude) {
        double absolute = absoluteMagnitude(luminositySuns);
        if (Double.isInfinite(absolute)) {
            return 0d;
        }
        double parsecs = Math.pow(10d, (limitMagnitude - absolute) / 5d + 1d);
        if (Double.isInfinite(parsecs) || Double.isNaN(parsecs)) {
            return Double.MAX_VALUE;
        }
        return Math.max(0d, parsecs * LIGHT_YEARS_PER_PARSEC);
    }

    /**
     * The reach of an instrument of {@code limitMagnitude} against the brightest of
     * {@code archetypes} — the physical horizon of a survey aimed with it.
     *
     * <p>Every archetype is asked and the widest wins, because a survey does not know what it is
     * about to find. The brightest is not the hottest nor the largest but the one whose {@code R^2T^4}
     * is greatest, which is why this is computed rather than read off the end of the table.</p>
     *
     * <p>A generator with no archetypes at all reaches nothing, and that is the honest answer: an
     * empty universe has nothing to see, and a survey of it should be instantly complete rather than
     * long and fruitless.</p>
     */
    public static double instrumentReachLightYears(Iterable<GalaxyGenConfig.StarType> archetypes,
                                                   double limitMagnitude) {
        if (archetypes == null) {
            return 0d;
        }
        double best = 0d;
        for (GalaxyGenConfig.StarType type : archetypes) {
            // The archetype's BRIGHTEST realisation: a star's size is drawn from a band, and the reach
            // has to cover the brightest star the band can produce or the walk would stop short of
            // something it can see.
            double luminosity = luminositySuns(type.maxSize, type.temperature);
            best = Math.max(best, detectionRangeLightYears(luminosity, limitMagnitude));
        }
        return best;
    }
}
