package zmaster587.advancedRocketry.sensor;

import net.minecraft.entity.Entity;
import zmaster587.advancedRocketry.api.sensor.ITargetSignature;

/**
 * How well a thing can be heard, and how well it can be held — the two halves of a signature, kept
 * apart on purpose.
 *
 * <h3>The law</h3>
 * <p>A body radiates {@code σT⁴} watts per square metre of surface. Two consequences, and the whole
 * of this class is them:</p>
 * <ul>
 *   <li><b>Detection range</b> comes from TOTAL radiated power, {@code σT⁴·A}, and grows with its
 *       square root — the ordinary inverse-square falloff read backwards. A big cool object is easy
 *       to notice from far away.</li>
 *   <li><b>Lock quality</b> comes from RADIANCE, {@code σT⁴}, which depends on temperature alone.
 *       A small hot object is a point beacon: it is precisely locatable, however little of it there
 *       is.</li>
 * </ul>
 * <p>So a compact chiller-boosted array and a large cool one that shed identical watts are entirely
 * different targets, and a player who has understood that has understood the trade this mechanic is
 * made of. Merging the two into one "signature" number would delete it.</p>
 *
 * <h3>Where a temperature comes from today</h3>
 * <p>Nowhere, yet: the heat subsystem that will give a hull a radiator temperature is unbuilt. Until
 * it lands, anything that does not state its own signature is ESTIMATED here — a body at roughly
 * living-thing temperature, a burning one at flame temperature, area from its own bounding box. The
 * estimate is deliberately crude and deliberately isolated to one method: the shape of the law is
 * the part that is meant to survive, and when a ship can say how hot it is it says so through
 * {@link ITargetSignature} and none of this is consulted.</p>
 */
public final class SignatureModel {

    /** Stefan–Boltzmann, W·m⁻²·K⁻⁴. */
    public static final double SIGMA = 5.670374419E-8D;

    /**
     * The temperature at which a target locks perfectly at the reference range. Set at the
     * temperature of a working machine rather than of a living body, so that the ordinary warm
     * things walking around a planet are trackable close in and poor targets at range — which is
     * what makes the active mode worth its emission.
     */
    public static final double REFERENCE_TEMPERATURE_KELVIN = 500.0D;

    /** The range, in blocks, at which a body at the reference temperature is perfectly resolved. */
    public static final double REFERENCE_LOCK_RANGE_BLOCKS = 32.0D;

    /**
     * Blocks of detection range per square root of a watt. The only constant here that is pure
     * bookkeeping: it converts the physics into the scale a Minecraft world is built at.
     */
    public static final double DETECTION_BLOCKS_PER_SQRT_WATT = 3.0D;

    /** What an ordinary warm body is estimated at, in kelvin, until something says otherwise. */
    public static final double AMBIENT_BODY_KELVIN = 300.0D;

    /** What a burning body is estimated at. A thing on fire is a beacon, and should be one. */
    public static final double BURNING_BODY_KELVIN = 1200.0D;

    private SignatureModel() {
    }

    /** Radiance in W/m²: {@code σT⁴}. Temperature alone — area does not appear, and must not. */
    public static double radiance(double temperatureKelvin) {
        double temperature = Math.max(0.0D, temperatureKelvin);
        return SIGMA * temperature * temperature * temperature * temperature;
    }

    /** Total radiated power in watts: radiance times how much surface is doing the radiating. */
    public static double radiatedPower(double temperatureKelvin, double areaSquareMetres) {
        return radiance(temperatureKelvin) * Math.max(0.0D, areaSquareMetres);
    }

    /**
     * How far away this target can be NOTICED at all, in blocks. Square root of total power: a
     * target with four times the output is noticed twice as far away, which is the inverse-square
     * law read from the other end.
     */
    public static double detectionRangeBlocks(double temperatureKelvin, double areaSquareMetres) {
        return DETECTION_BLOCKS_PER_SQRT_WATT
                * Math.sqrt(radiatedPower(temperatureKelvin, areaSquareMetres));
    }

    /**
     * How well a listening sensor resolves a target at this distance, 0..1.
     *
     * <p>{@code (T/T_ref)⁴ · (d_ref/d)²} — the target's radiance against the reference, falling off
     * with the square of the range. Area is absent by design: this is the term a compact hot object
     * wins and a large cool one loses.</p>
     */
    public static double passiveQuality(double temperatureKelvin, double distanceBlocks) {
        if (distanceBlocks <= 0.0D) {
            return 1.0D;
        }
        double temperatureRatio = radiance(temperatureKelvin) / radiance(REFERENCE_TEMPERATURE_KELVIN);
        double rangeRatio = REFERENCE_LOCK_RANGE_BLOCKS / distanceBlocks;
        return clamp01(temperatureRatio * rangeRatio * rangeRatio);
    }

    /**
     * How well an illuminating sensor resolves a target at this distance, 0..1.
     *
     * <p>The target's own temperature does not appear: the sensor is providing the light, which is
     * exactly why this is the only way to hold a cold, silent thing. Quality is the plateau the
     * installation is tuned for, tapering over the last quarter of the sensor's radius so that the
     * edge of the envelope is a place where things are held badly rather than a wall.</p>
     */
    public static double activeQuality(double distanceBlocks, double radiusBlocks, double plateau) {
        if (radiusBlocks <= 0.0D || distanceBlocks > radiusBlocks) {
            return 0.0D;
        }
        double taperStart = radiusBlocks * 0.75D;
        if (distanceBlocks <= taperStart) {
            return clamp01(plateau);
        }
        double fade = 1.0D - (distanceBlocks - taperStart) / (radiusBlocks - taperStart);
        return clamp01(plateau * fade);
    }

    /**
     * What this entity looks like, when it has not said. A living thing is warm, a burning thing is
     * a beacon, and the radiating area is the surface of the box it occupies — all three are things
     * the world already knows, so nothing here invents a number the player cannot see the reason for.
     */
    public static double estimatedTemperatureKelvin(Entity entity) {
        if (entity == null) {
            return AMBIENT_BODY_KELVIN;
        }
        if (entity instanceof ITargetSignature) {
            return Math.max(0.0D, ((ITargetSignature) entity).getRadiatorTemperatureKelvin());
        }
        return entity.isBurning() ? BURNING_BODY_KELVIN : AMBIENT_BODY_KELVIN;
    }

    /** The radiating surface of an entity's own box, in square metres — a block being a metre. */
    public static double estimatedAreaSquareMetres(Entity entity) {
        if (entity == null) {
            return 1.0D;
        }
        if (entity instanceof ITargetSignature) {
            return Math.max(0.0D, ((ITargetSignature) entity).getRadiatingAreaSquareMetres());
        }
        double width = Math.max(0.1D, entity.width);
        double height = Math.max(0.1D, entity.height);
        return 2.0D * width * width + 4.0D * width * height;
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : (value > 1.0D ? 1.0D : value);
    }
}
