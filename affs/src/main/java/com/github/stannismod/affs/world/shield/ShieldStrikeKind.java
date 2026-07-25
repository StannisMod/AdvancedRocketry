package com.github.stannismod.affs.world.shield;

/**
 * The two impact families the shield bills differently (D134-2). A cooperative strike declares its kind;
 * the shield picks the resistance-bias multiplier from it (energy vs physical), exactly as the entity
 * scan already does for an energy projectile vs a kinetic body.
 */
public enum ShieldStrikeKind {

    /**
     * A moving mass / kinetic projectile. The per-tick entity scan physically <em>reflects</em> a
     * travelling one; a declared kinetic strike with no travelling entity is absorbed at the physical-
     * resistance multiplier.
     */
    KINETIC,

    /**
     * An energy bolt or an instantaneous hitscan beam. Always <em>absorbed</em>, never reflected, and
     * billed at the energy-resistance multiplier — a beam hands the shield its energy directly (D134-2),
     * there is no velocity to reflect.
     */
    RADIANT
}
