package com.github.stannismod.affs.world.shield;

/**
 * The two impact families the shield bills differently (D134-2). A cooperative strike declares its kind;
 * the shield picks the resistance-bias multiplier from it (energy vs physical), exactly as the entity
 * scan already does for an energy projectile vs a kinetic body.
 */
public enum ShieldStrikeKind {

    /**
     * A moving mass / kinetic projectile. What the field does with it is decided by whether a travelling
     * <em>body</em> exists — not by whether that body happens to be a Forge {@code Entity}:
     *
     * <ul>
     *   <li>a travelling entity — reflected by the per-tick AABB scan, as it always has been (a
     *       permanent compatibility path for other mods' projectiles and thrown bodies);</li>
     *   <li>a declared strike that carries a body ({@link ShieldStrike#hasBody()}) — a shot living as a
     *       record rather than an entity — reflected by {@link ShieldStrikeService#resolve};</li>
     *   <li>a declared strike with no body at all — an abstract kinetic source — absorbed at the
     *       physical-resistance multiplier.</li>
     * </ul>
     */
    KINETIC,

    /**
     * An energy bolt or an instantaneous hitscan beam. Always <em>absorbed</em>, never reflected, and
     * billed at the energy-resistance multiplier — a beam hands the shield its energy directly (D134-2),
     * there is no velocity to reflect.
     */
    RADIANT
}
