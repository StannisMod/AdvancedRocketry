package zmaster587.advancedRocketry.util;

import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;

public class AstronomicalBodyHelper {

    // ─── The reference frame ───────────────────────────────────────────────────
    // Named per MEANING, not per value. Three of these are 100 and they are NOT the same quantity:
    // a distance scale, an atmosphere scale and a star-temperature scale all shared the literal in
    // this one file, which made a search-and-replace on "100" a silent way to corrupt the
    // temperature formula. Never collapse them because the numbers happen to match.
    //
    // They are ints so that every use site states the arithmetic it wants: the original mixed 100f
    // and 100d for the SAME scale, and float-vs-double division is not always the same number once
    // narrowed. The casts below are deliberate and preserve each site's original type exactly.

    /** Distance units in one astronomical unit — the scale the whole system is written in. */
    public static final int DISTANCE_UNITS_PER_AU = 100;
    /** Atmosphere-density units in one Earth atmosphere. NOT the distance scale. */
    public static final int ATM_PRESSURE_UNITS_PER_ATMOSPHERE = 100;
    /** Star-temperature units in one Sol. NOT the distance scale either. */
    public static final int TEMPERATURE_UNITS_PER_SOL = 100;
    /** Kelvin per unit of {@link StellarBody#getTemperature()}. */
    public static final int KELVIN_PER_STAR_TEMPERATURE_UNIT = 58;
    /** Solar radii in one astronomical unit — carries a star's size into the distance frame. */
    public static final int SOLAR_RADII_PER_AU = 215;

    // ─── The calendar ──────────────────────────────────────────────────────────
    // Inherited from upstream: "One MC Year is 48 MC days (16 IRL Hours), one month is 8 MC Days".
    // The two are leading coefficients of the same power law at two reference distances — one for
    // planets around a star, one for moons around a planet. Their ratio (six months to a year) is a
    // FICTION CHOICE, not a derivation, which is why the month is written independently rather than
    // as a fraction of the year: changing one does NOT change the other. If that relation is ever
    // meant to hold, encode it deliberately and record the decision here.

    /** Days in one year: the orbital period one AU from a size-1 star. */
    public static final int DAYS_PER_YEAR = 48;
    /** Days in one lunar month: a moon's period at the reference distance from a mass-1 parent. */
    public static final int DAYS_PER_LUNAR_MONTH = 8;
    /** Ticks in one day — the platform's rate, NOT a planet's rotational period (that is per-dim). */
    public static final int TICKS_PER_DAY = 24000;

    /**
     * Returns the size multiplier for a body at the input distance, relative to either 1AU or the moon's orbital distance, depending on parent body
     *
     * @param orbitalDistance the distance from the parent body
     * @return the float multiplier for size
     */
    public static float getBodySizeMultiplier(float orbitalDistance) {
        //Returns size multiplier relative to Earth standard (1AU = 100 Distance)
        return (float) DISTANCE_UNITS_PER_AU / orbitalDistance;
    }

    /**
     * Returns the orbital period for a body at a given distance around its star
     *
     * @param orbitalDistance the distance from the parent body
     * @param solarSize       the size of the sun in question
     * @return the orbital period in MC Days (24000 ticks)
     */
    public static double getOrbitalPeriod(int orbitalDistance, float solarSize) {
        //One MC Year is 48 MC days (16 IRL Hours), one month is 8 MC Days
        return DAYS_PER_YEAR
                * Math.pow(Math.pow((orbitalDistance / ((double) DISTANCE_UNITS_PER_AU * solarSize)), 3), 0.5d);
    }

    /**
     * Returns the orbital period for a body at a given distance around its parent planet
     *
     * <p><b>The second argument is a MASS, in Earth masses, and callers used to pass surface gravity.</b>
     * The two agree only at one Earth radius — {@code g = M/R²} — so the substitution was exact for
     * Earth and wrong by {@code sqrt(M/g)} everywhere else, which for Jupiter (M=318, g=2.53) made its
     * moons orbit 11.2 times too slowly. Pass the body's mass; where nothing has stated one, its
     * gravity IS the right stand-in, because a body with no stated bulk is a body assumed to be one
     * Earth radius across.</p>
     *
     * @param orbitalDistance the distance from the parent body
     * @param planetaryMass   the mass of the planet in question, in Earth masses
     * @return the orbital period in MC Days (24000 ticks)
     */
    public static double getMoonOrbitalPeriod(float orbitalDistance, float planetaryMass) {
        //One (lunar) MC month is 8 MC days, so the moon orbits in 8
        //The same as the function for planets, with the parent's mass in place of the star's size
        return DAYS_PER_LUNAR_MONTH
                * Math.pow(Math.pow((orbitalDistance / (double) DISTANCE_UNITS_PER_AU), 3) / planetaryMass, 0.5d);
    }

    /**
     * Returns the orbital theta for a body at a given distance around its star, at this current moment
     *
     * @param orbitalDistance the distance from the parent body
     * @param solarSize       the size of the sun in question
     * @return the current angle around the star in radians
     */
    public static double getOrbitalTheta(int orbitalDistance, float solarSize) {
        return getOrbitalThetaAt(orbitalDistance, solarSize, AdvancedRocketry.proxy.getWorldTimeUniversal(0));
    }

    /**
     * The orbital theta a body at {@code orbitalDistance} around a star of {@code solarSize} has at
     * world tick {@code worldTick} — the same law as {@link #getOrbitalTheta}, evaluated at an
     * arbitrary time. Navigation extrapolates with this: a jump takes long enough for the
     * destination to move, so the computer has to aim where the body WILL be.
     *
     * @return the angle around the star in RADIANS
     */
    public static double getOrbitalThetaAt(int orbitalDistance, float solarSize, long worldTick) {
        double periodTicks = (double) TICKS_PER_DAY * getOrbitalPeriod(orbitalDistance, solarSize);
        if (!(periodTicks > 0d) || Double.isInfinite(periodTicks)) {
            // A degenerate orbit (zero distance, or a star with no size recorded) does not move.
            // Answering 0 keeps it addressable instead of handing every caller a NaN coordinate.
            return 0d;
        }
        return ((worldTick % periodTicks) / periodTicks) * (2d * Math.PI);
    }

    /**
     * Returns the orbital theta for a body at a given distance around its parent planet, at this current moment
     *
     * @param orbitalDistance the distance from the parent body
     * @param parentMassEarths the mass of the parent planet, in Earth masses
     * @return the current angle around the planet in radians
     */
    public static double getMoonOrbitalTheta(int orbitalDistance, float parentMassEarths) {
        return getMoonOrbitalThetaAt(orbitalDistance, parentMassEarths,
                AdvancedRocketry.proxy.getWorldTimeUniversal(0));
    }

    /**
     * A moon's orbital theta around its parent at world tick {@code worldTick} — the moon half of
     * {@link #getOrbitalThetaAt}.
     *
     * @return the angle around the parent planet in RADIANS
     */
    public static double getMoonOrbitalThetaAt(int orbitalDistance, float parentMassEarths,
                                               long worldTick) {
        //Because the function is still in AU and solar mass, some correctional factors to convert to those units
        double periodTicks = (double) TICKS_PER_DAY
                * getMoonOrbitalPeriod(orbitalDistance, parentMassEarths);
        if (!(periodTicks > 0d) || Double.isInfinite(periodTicks)) {
            return 0d;
        }
        return ((worldTick % periodTicks) / periodTicks) * (2d * Math.PI);
    }

    /**
     * Returns the visual orbital theta for a body at a given distance around its parent planet, at this current moment, as a value from 0 - 360
     *
     * @param rotationalPeriod    the rotational period of the moon we are rendering from
     * @param orbitalDistance     the distance from the parent body
     * @param parentMassEarths    the mass of the parent planet, in Earth masses
     * @param currentOrbitalTheta the orbital theta of the moon we are rendering from
     * @param baseOrbitalTheta    the base orbital theta of the planet in question
     * @return the current angle around the planet normalized 0 - 360, for GL calls
     */
    public static float getParentPlanetThetaFromMoon(int rotationalPeriod, int orbitalDistance, float parentMassEarths, double currentOrbitalTheta, double baseOrbitalTheta) {
        //Convert from radians to degrees for easier math
        float degreeOrbitalTheta = (float) (currentOrbitalTheta * 180 / Math.PI);
        //Computer the number of rotations per revolution and use that for how fast the planet would seem to orbit from the moon
        //Planet will not move at all if it is tidally locked
        float planetPositionTheta = (((float) (AstronomicalBodyHelper.getMoonOrbitalPeriod(orbitalDistance, parentMassEarths) * TICKS_PER_DAY) / rotationalPeriod) - 1) * degreeOrbitalTheta;
        //Add the base orbital theta so the planet is in the correct place
        return (planetPositionTheta + (float) (baseOrbitalTheta * 180 / Math.PI)) % 360;
    }

    /**
     * Returns the average temperature of a planet with the passed parameters
     *
     * @param star            the stellar body that the planet orbits
     * @param orbitalDistance the distance from the star
     * @param atmPressure     the pressure of the planet's atmosphere
     * @return the temperature of the planet in Kelvin
     */
    public static int getAverageTemperature(StellarBody star, int orbitalDistance, int atmPressure) {
        int starSurfaceTemperature = KELVIN_PER_STAR_TEMPERATURE_UNIT * star.getTemperature();
        float starRadius = star.getSize() / (float) SOLAR_RADII_PER_AU;
        //Gives output in AU
        float planetaryOrbitalRadius = orbitalDistance / (float) DISTANCE_UNITS_PER_AU;
        //Albedo is 0.3f hardcoded because of inability to easily calculate
        double averageWithoutAtmosphere = starSurfaceTemperature * Math.pow(starRadius / (2 * planetaryOrbitalRadius), 0.5) * Math.pow((1f - 0.3f), 0.25);
        //Slightly kludgey solution that works out mostly for Venus and well for Earth, without being overly complex
        //Output is in Kelvin
        return (int) (averageWithoutAtmosphere
                * Math.max(1, (1.125d * Math.pow((atmPressure / (double) ATM_PRESSURE_UNITS_PER_ATMOSPHERE), 0.25))));
    }

    /**
     * Returns the average insolation of a planet with the passed parameters
     *
     * @param star            the stellar body that the planet orbits
     * @param orbitalDistance the distance from the star
     * @return the insolation of the planet relative to Earth insolation
     */
    private static final double MIN_BRIGHTNESS = 1.0e-9d;

    public static double getStellarBrightness(StellarBody star, int orbitalDistance) {
        if (star == null || orbitalDistance <= 0) {
            return MIN_BRIGHTNESS;
        }
        float planetaryOrbitalRadius = orbitalDistance / (float) DISTANCE_UNITS_PER_AU;
        // EVERY star that shines on this world contributes, and what ADDS is the FLUX each one
        // delivers here — not their luminosities. Radiant power from mutually incoherent sources
        // superposes linearly, so E = sum of L_i / d_i², with each star's own distance under its own
        // luminosity. Summing luminosities first and dividing once is the same number only while all
        // the stars are equidistant from the planet.
        //
        // Today they are, by construction rather than by physics: a companion's separation is stored
        // as an ANGLE in the sky (StellarBody.getStarSeparation), so there is no distance to give it,
        // and every companion is fed the primary's. That is exact for the close binaries the model can
        // actually describe, and it is why this sums flux terms rather than luminosities — when a
        // companion gains a real orbital radius, only the argument below changes.
        //
        // This replaces a walk over the companions whose only effect was to clear a boolean: any
        // ordinary companion turned the accretion-disc dimming OFF, after which the brightness came
        // from the BLACK HOLE's own size and temperature at full strength, and the companion itself
        // never contributed a photon.
        //Returns ratio compared to a planet at 1 AU for Sol, because the other values in AR are normalized,
        //and this works fairly well for hooking into with other mod's solar panels & such
        double brightness = fluxOf(star, planetaryOrbitalRadius);
        Iterable<StellarBody> companions = star.getSubStars();
        if (companions != null) {
            for (StellarBody companion : companions) {
                if (companion != null) {
                    brightness += fluxOf(companion, planetaryOrbitalRadius);
                }
            }
        }

        // Guarantee: never return 0, NaN, or Infinity
        if (!Double.isFinite(brightness) || brightness < MIN_BRIGHTNESS) {
            return MIN_BRIGHTNESS;
        }
        return brightness;
    }

    /**
     * The flux one star delivers at {@code orbitalRadiusAu}, relative to Sol at 1 AU:
     * {@code size² · (T/Sol)⁴ / r²} — Stefan-Boltzmann over the inverse square, both in solar units.
     * Quartered for a black hole, because there is no easy way to model what an accretion disc emits.
     *
     * <p>0.25 is a power of two, so applying it to the numerator rather than to the finished quotient
     * is exact: a system of one star returns bit-identical numbers to the version that multiplied at
     * the end.</p>
     */
    private static double fluxOf(StellarBody star, float orbitalRadiusAu) {
        //Make all values ratios of Earth normal to get ratio compared to Earth
        float normalizedStarTemperature = star.getTemperature() / (float) TEMPERATURE_UNITS_PER_SOL;
        double luminosity = Math.pow(star.getSize(), 2) * Math.pow(normalizedStarTemperature, 4);
        //There's no real easy way to get the light emitted by an accretion disc, so this substitutes
        if (star.isBlackHole()) {
            luminosity *= 0.25d;
        }
        return luminosity / Math.pow(orbitalRadiusAu, 2);
    }

    /**
     * Returns the human-eye-perceivable brightness of this insolation multiplier
     *
     * @param stellarBrightnessMultiplier the insolation multiplier to use
     * @return the brightness multiplier perceivable to a human
     */
    public static double getPlanetaryLightLevelMultiplier(double stellarBrightnessMultiplier) {
        double log2Multiplier = (Math.log10(stellarBrightnessMultiplier) / Math.log10(2.0));
        //Returns the brightness visible to the eye, compared to the actual flux - this is a factor of ~1.5x for every 2x increase in luminosity
        //This is used for planetary light levels, as those would be eyesight based unlike the stellar brightness or similar
        return Math.pow(1.5, log2Multiplier);
    }
}
