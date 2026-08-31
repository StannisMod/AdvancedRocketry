package zmaster587.advancedRocketry.api.sensor;

/**
 * What a thing looks like to something listening for it: how hot it is, and how much of it there is.
 *
 * <h3>Two numbers, never one</h3>
 * <p>They are kept apart because the whole build trade lives in their difference. Total radiated
 * power decides how FAR away a thing can be noticed; radiance — a function of temperature alone —
 * decides how well it can be RESOLVED once noticed. A compact, chiller-boosted radiator array and a
 * large cool one can shed exactly the same watts while being completely different targets: the first
 * is a point beacon that can be locked from a long way off, the second is a smear that is easy to
 * find and hard to hit. Collapsing them into a single "signature" number would delete that choice.</p>
 *
 * <h3>The seam</h3>
 * <p>Nothing in the game implements this yet: the heat subsystem that will give a ship a real
 * radiator temperature is unbuilt, and until it lands a target's numbers are estimated from what the
 * world already knows about it (see {@code SignatureModel}). This interface is where that estimate
 * stops being used — a ship, a machine or an addon's entity that can state its own temperature
 * implements it, and the sensor believes it in preference to any guess.</p>
 */
public interface ITargetSignature {

    /**
     * The temperature of the radiating surface, in kelvin. Never zero: everything is warmer than the
     * background, so silence reduces the range at which a thing is noticed and never makes it
     * invisible.
     */
    double getRadiatorTemperatureKelvin();

    /** How much radiating surface there is, in square metres. Affects range, never lock quality. */
    double getRadiatingAreaSquareMetres();
}
