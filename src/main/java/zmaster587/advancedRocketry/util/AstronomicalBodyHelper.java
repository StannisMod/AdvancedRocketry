package zmaster587.advancedRocketry.util;

import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;

public class AstronomicalBodyHelper {
    /**
     * Returns the size multiplier for a body at the input distance, relative to either 1AU or the moon's orbital distance, depending on parent body
     *
     * @param orbitalDistance the distance from the parent body
     * @return the float multiplier for size
     */
    public static float getBodySizeMultiplier(float orbitalDistance) {
        //Returns size multiplier relative to Earth standard (1AU = 100 Distance)
        return 100f / orbitalDistance;
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
        return 48d * Math.pow(Math.pow((orbitalDistance / (100d * solarSize)), 3), 0.5d);
    }

    /**
     * Returns the orbital period for a body at a given distance around its parent planet
     *
     * @param orbitalDistance the distance from the parent body
     * @param planetaryMass   the mass of the planet in question
     * @return the orbital period in MC Days (24000 ticks)
     */
    public static double getMoonOrbitalPeriod(float orbitalDistance, float planetaryMass) {
        //One (lunar) MC month is 8 MC days, so the moon orbits in 8
        //The same a the function for planets, but since gravity is directly correlated with mass uses the gravity of the plant for mass
        return 8d * Math.pow(Math.pow((orbitalDistance / 100d), 3) / planetaryMass, 0.5d);
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
        double periodTicks = 24000d * getOrbitalPeriod(orbitalDistance, solarSize);
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
     * @param orbitalDistance               the distance from the parent body
     * @param parentGravitationalMultiplier the size of the parent planet in question
     * @return the current angle around the planet in radians
     */
    public static double getMoonOrbitalTheta(int orbitalDistance, float parentGravitationalMultiplier) {
        return getMoonOrbitalThetaAt(orbitalDistance, parentGravitationalMultiplier,
                AdvancedRocketry.proxy.getWorldTimeUniversal(0));
    }

    /**
     * A moon's orbital theta around its parent at world tick {@code worldTick} — the moon half of
     * {@link #getOrbitalThetaAt}.
     *
     * @return the angle around the parent planet in RADIANS
     */
    public static double getMoonOrbitalThetaAt(int orbitalDistance, float parentGravitationalMultiplier,
                                               long worldTick) {
        //Because the function is still in AU and solar mass, some correctional factors to convert to those units
        double periodTicks = 24000d * getMoonOrbitalPeriod(orbitalDistance, parentGravitationalMultiplier);
        if (!(periodTicks > 0d) || Double.isInfinite(periodTicks)) {
            return 0d;
        }
        return ((worldTick % periodTicks) / periodTicks) * (2d * Math.PI);
    }

    /**
     * Returns the visual orbital theta for a body at a given distance around its parent planet, at this current moment, as a value from 0 - 360
     *
     * @param rotationalPeriod              the rotational period of the moon we are rendering from
     * @param orbitalDistance               the distance from the parent body
     * @param parentGravitationalMultiplier the distance from the parent body
     * @param currentOrbitalTheta           the orbital theta of the moon we are rendering from
     * @param baseOrbitalTheta              the base orbital theta of the planet in question
     * @return the current angle around the planet normalized 0 - 360, for GL calls
     */
    public static float getParentPlanetThetaFromMoon(int rotationalPeriod, int orbitalDistance, float parentGravitationalMultiplier, double currentOrbitalTheta, double baseOrbitalTheta) {
        //Convert from radians to degrees for easier math
        float degreeOrbitalTheta = (float) (currentOrbitalTheta * 180 / Math.PI);
        //Computer the number of rotations per revolution and use that for how fast the planet would seem to orbit from the moon
        //Planet will not move at all if it is tidally locked
        float planetPositionTheta = (((float) (AstronomicalBodyHelper.getMoonOrbitalPeriod(orbitalDistance, parentGravitationalMultiplier) * 24000) / rotationalPeriod) - 1) * degreeOrbitalTheta;
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
        int starSurfaceTemperature = 58 * star.getTemperature();
        float starRadius = star.getSize() / 215f;
        //Gives output in AU
        float planetaryOrbitalRadius = orbitalDistance / 100f;
        //Albedo is 0.3f hardcoded because of inability to easily calculate
        double averageWithoutAtmosphere = starSurfaceTemperature * Math.pow(starRadius / (2 * planetaryOrbitalRadius), 0.5) * Math.pow((1f - 0.3f), 0.25);
        //Slightly kludgey solution that works out mostly for Venus and well for Earth, without being overly complex
        //Output is in Kelvin
        return (int) (averageWithoutAtmosphere * Math.max(1, (1.125d * Math.pow((atmPressure / 100d), 0.25))));
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
        //Normal stars are 1.0 times this value, black holes with accretion discs emit less and so modify it
        float lightMultiplier = 1.0f;
        //Make all values ratios of Earth normal to get ratio compared to Earth
        float normalizedStarTemperature = star.getTemperature() / 100f;
        float planetaryOrbitalRadius = orbitalDistance / 100f;
        //Check to see if the star is a black hole
        boolean blackHole = star.isBlackHole();
        Iterable<StellarBody> subs = star.getSubStars();
        if (subs != null) {
            for (StellarBody star2 : subs) {
                if (star2 != null && !star2.isBlackHole()) {
                    blackHole = false;
                    break;
                }
            }
        }
        //There's no real easy way to get the light emitted by an accretion disc, so this substitutes
        if (blackHole)
            lightMultiplier *= 0.25;
        //Returns ratio compared to a planet at 1 AU for Sol, because the other values in AR are normalized,
        //and this works fairly well for hooking into with other mod's solar panels & such
        double brightness =
                lightMultiplier *
                ((Math.pow(star.getSize(), 2) * Math.pow(normalizedStarTemperature, 4)) /
                Math.pow(planetaryOrbitalRadius, 2));

        // Guarantee: never return 0, NaN, or Infinity
        if (!Double.isFinite(brightness) || brightness < MIN_BRIGHTNESS) {
            return MIN_BRIGHTNESS;
        }
        return brightness;
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
