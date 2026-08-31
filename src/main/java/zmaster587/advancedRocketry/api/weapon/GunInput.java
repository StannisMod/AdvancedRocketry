package zmaster587.advancedRocketry.api.weapon;

/**
 * What a gun needs delivered to it in order to fire.
 *
 * <h3>Declared by the build, so nothing has to guess</h3>
 * <p>A gun states its inputs rather than being asked to justify a failure to fire: a player looking
 * at a silent weapon can be told "it wants gas and has none" instead of being left to work out which
 * of six conditions is unmet. An addon's part declares its own input the same way, which is what
 * lets a supply system serve weapons it was not written knowing about.</p>
 *
 * <h3>One of these is implemented</h3>
 * <p>{@link #FORGE_ENERGY} is real: guns hold a buffer, draw from any FE source, and the weapons
 * network distributes it. {@link #GAS} is declared and reserved — the fabric that would carry it
 * exists for other purposes, and the plasma weapons that would want it are a later wave. A build
 * that declares it today is not refused and is not charged; the declaration is what makes adding the
 * supply later a change to one place rather than to every gun.</p>
 */
public enum GunInput {

    /** Forge Energy, drawn from the gun's own buffer. Implemented. */
    FORGE_ENERGY,

    /** A gas feed from the ship's fabric. Declared only — nothing consumes it yet. */
    GAS
}
