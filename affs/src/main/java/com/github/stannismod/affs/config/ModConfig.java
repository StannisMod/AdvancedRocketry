package com.github.stannismod.affs.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public final class ModConfig {

    private static final String CATEGORY_IMPACT = "impact";
    private static final String CATEGORY_SHIELD = "shield";
    private static final String CATEGORY_CONTOUR = "contour";
    private static final String CATEGORY_BUFFERS = "buffers";
    private static final String CATEGORY_WEAPONS = "weapons";

    // Distinct roles for the three shield-energy buffers (each tunable):
    //  - generatorShieldBuffer: small conversion-smoothing store in the shield generator (a few ticks
    //    of FE->shield conversion), so a generator hoards no meaningful reserve on its own;
    //  - generatorFeBuffer: the generator's raw-FE intake buffer, kept equally small;
    //  - emitterCoilBuffer: the emitter's small, fast field-hold coil (activation is a fraction of it);
    //  - accumulatorBuffer: the bulk reserve held by the dedicated accumulator block.
    public static final int DEFAULT_GENERATOR_SHIELD_BUFFER = 16_000;
    public static final int DEFAULT_GENERATOR_FE_BUFFER = 16_000;
    public static final int DEFAULT_EMITTER_COIL_BUFFER = 40_000;
    public static final int DEFAULT_ACCUMULATOR_BUFFER = 500_000;

    private static Configuration configuration;

    public static int generatorShieldBuffer = DEFAULT_GENERATOR_SHIELD_BUFFER;
    public static int generatorFeBuffer = DEFAULT_GENERATOR_FE_BUFFER;
    public static int emitterCoilBuffer = DEFAULT_EMITTER_COIL_BUFFER;
    public static int accumulatorBuffer = DEFAULT_ACCUMULATOR_BUFFER;

    public static double entityImpactEnergyPerVelocitySq = 160.0D;
    public static double projectileImpactEnergyPerVelocitySq = 320.0D;
    public static double energyProjectileImpactEnergy = 10_000.0D;
    public static double explosionImpactEnergyPerResistance = 12.0D;
    public static double shieldCollisionBaseEnergyCost = 100.0D;
    public static double shieldEnergyResistanceBias = 0.5D;
    public static double shieldActivationThreshold = 0.5D;
    public static double minimumImpactEnergyCost = 1.0D;
    public static double shieldCollisionMinDamageEnergy = 25.0D;
    public static double shieldCollisionDamagePerEnergy = 0.02D;
    public static double shieldTierEfficiencyStep = 0.2D;
    public static double contourMaintenanceEnergyPerFieldBlock = 4.0D;

    // D134-3: per-emitter recharge throughput. An emitter can only pour energy into its zone of the
    // field at a bounded rate; this is the shield's per-zone regeneration bottleneck (the "interesting"
    // limiter). Base rate per tick, scaled up per emitter tier so larger/denser emitters regenerate
    // faster. The network never routes an emitter more than this per tick.
    public static int emitterRechargeThroughputBase = 4_000;
    public static double emitterThroughputTierStep = 0.5D;
    // D134-4: passive-maintenance coefficient. Holding a powered field at its current strength costs
    // little (this coefficient x pi x r^2 per tick, spread over a 20-tick cycle) — the SMALL of the two
    // draws. Regeneration (the LARGE draw) is the throughput-capped refill above. Kept small relative to
    // the throughput so maintenance never dominates the regeneration budget.
    public static double emitterMaintenanceEnergyPerSurfaceArea = 12.0D;

    // D134-2 tier-1 cooperative weapon interaction (axis-G tunable, never balance-pinned):
    //  - shieldStrikeAbsorptionRate: shield energy spent per unit of a cooperative strike's declared
    //    impact energy. spent = min(stored, impactEnergy x rate x kindMult / tierEff).
    //  - shieldStrikeDamageToEnergyFactor: converts a cooperating source's *damage* value to declared
    //    impact energy when it reports damage rather than energy.
    // The tier-2 residual hitscan-ray hook (a blanket World.rayTraceBlocks mixin) is deferred to its own
    // task; its config lands with it, not here.
    public static double shieldStrikeAbsorptionRate = 1.0D;
    public static double shieldStrikeDamageToEnergyFactor = 500.0D;

    private ModConfig() {
    }

    public static void load(File file) {
        configuration = new Configuration(file);
        sync();
    }

    public static void sync() {
        if (configuration == null) {
            return;
        }

        configuration.load();

        entityImpactEnergyPerVelocitySq = configuration.getFloat(
                "entityImpactEnergyPerVelocitySq",
                CATEGORY_IMPACT,
                160.0F,
                0.0F,
                Float.MAX_VALUE,
                "Energy drained per entity velocity-squared unit when the entity collides with the shield."
        );

        projectileImpactEnergyPerVelocitySq = configuration.getFloat(
                "projectileImpactEnergyPerVelocitySq",
                CATEGORY_IMPACT,
                320.0F,
                0.0F,
                Float.MAX_VALUE,
                "Energy drained per projectile velocity-squared unit when the projectile collides with the shield."
        );

        energyProjectileImpactEnergy = configuration.getFloat(
                "energyProjectileImpactEnergy",
                CATEGORY_IMPACT,
                10_000.0F,
                0.0F,
                Float.MAX_VALUE,
                "Energy drained when an energy projectile is absorbed by the shield."
        );

        explosionImpactEnergyPerResistance = configuration.getFloat(
                "explosionImpactEnergyPerResistance",
                CATEGORY_IMPACT,
                12.0F,
                0.0F,
                Float.MAX_VALUE,
                "Energy drained per explosion resistance point for every block protected by the shield."
        );

        shieldCollisionBaseEnergyCost = configuration.getFloat(
                "shieldCollisionBaseEnergyCost",
                CATEGORY_IMPACT,
                12.0F,
                0.0F,
                Float.MAX_VALUE,
                "Base shield energy cost for any entity collision that the shield actually blocks."
        );

        shieldEnergyResistanceBias = configuration.getFloat(
                "shieldEnergyResistanceBias",
                CATEGORY_SHIELD,
                0.5F,
                0.0F,
                1.0F,
                "Shield resistance balance. 0.0 favors energy resistance, 1.0 favors physical resistance, and 0.5 is the default 1:1 balance."
        );

        shieldActivationThreshold = configuration.getFloat(
                "shieldActivationThreshold",
                CATEGORY_SHIELD,
                0.5F,
                0.0F,
                1.0F,
                "Fraction of shield capacity required before the shield activates. Once active, it stays on until energy reaches 0."
        );

        minimumImpactEnergyCost = configuration.getFloat(
                "minimumImpactEnergyCost",
                CATEGORY_IMPACT,
                1.0F,
                0.0F,
                Float.MAX_VALUE,
                "Minimum shield energy cost for any impact event that the shield actually blocks."
        );

        shieldCollisionMinDamageEnergy = configuration.getFloat(
                "shieldCollisionMinDamageEnergy",
                CATEGORY_IMPACT,
                25.0F,
                0.0F,
                Float.MAX_VALUE,
                "Collision energy below this threshold still blocks the entity, but does not deal damage."
        );

        shieldCollisionDamagePerEnergy = configuration.getFloat(
                "shieldCollisionDamagePerEnergy",
                CATEGORY_IMPACT,
                0.02F,
                0.0F,
                Float.MAX_VALUE,
                "Damage dealt per shield collision energy unit above the minimum damage threshold."
        );

        shieldTierEfficiencyStep = configuration.getFloat(
                "shieldTierEfficiencyStep",
                CATEGORY_SHIELD,
                0.2F,
                0.0F,
                1.0F,
                "Each tier above Tier 0 makes the field 20% more energy efficient by default. Tier 0 uses 1.0x efficiency, Tier 1 uses 1.2x, Tier 2 uses 1.4x, Tier 3 uses 1.6x."
        );

        contourMaintenanceEnergyPerFieldBlock = configuration.getFloat(
                "contourMaintenanceEnergyPerFieldBlock",
                CATEGORY_CONTOUR,
                4.0F,
                0.0F,
                Float.MAX_VALUE,
                "Energy consumed per contour field block per tick while the contour field is active."
        );

        emitterRechargeThroughputBase = configuration.getInt(
                "emitterRechargeThroughputBase",
                CATEGORY_SHIELD,
                4_000,
                1,
                Integer.MAX_VALUE,
                "Base per-tick shield-energy recharge throughput of a single Tier 0 emitter. This is the "
                        + "per-zone regeneration bottleneck: the field regenerates its zone no faster than "
                        + "this, no matter how large the generator or accumulator behind it."
        );

        emitterThroughputTierStep = configuration.getFloat(
                "emitterThroughputTierStep",
                CATEGORY_SHIELD,
                0.5F,
                0.0F,
                Float.MAX_VALUE,
                "Each emitter tier above Tier 0 adds this fraction of the base throughput. Tier 0 uses the "
                        + "base rate, Tier 1 uses base x (1 + step), etc. — higher-tier emitters regenerate faster."
        );

        emitterMaintenanceEnergyPerSurfaceArea = configuration.getFloat(
                "emitterMaintenanceEnergyPerSurfaceArea",
                CATEGORY_SHIELD,
                12.0F,
                0.0F,
                Float.MAX_VALUE,
                "Passive-maintenance coefficient. Holding a powered field costs this x pi x radius^2 per "
                        + "tick (the small draw); regeneration after damage is the larger, throughput-capped draw."
        );

        generatorShieldBuffer = configuration.getInt(
                "generatorShieldBuffer",
                CATEGORY_BUFFERS,
                DEFAULT_GENERATOR_SHIELD_BUFFER,
                0,
                Integer.MAX_VALUE,
                "Shield-energy buffer inside a shield generator. Small on purpose: it smooths FE->shield "
                        + "conversion over a few ticks, it is not a reserve. Bulk storage is the accumulator's job."
        );

        generatorFeBuffer = configuration.getInt(
                "generatorFeBuffer",
                CATEGORY_BUFFERS,
                DEFAULT_GENERATOR_FE_BUFFER,
                0,
                Integer.MAX_VALUE,
                "Raw-FE intake buffer inside a shield generator. Kept small so a generator cannot hoard FE."
        );

        emitterCoilBuffer = configuration.getInt(
                "emitterCoilBuffer",
                CATEGORY_BUFFERS,
                DEFAULT_EMITTER_COIL_BUFFER,
                1,
                Integer.MAX_VALUE,
                "Field-hold coil buffer inside an emitter (field generator). Small and fast: the field "
                        + "activates once the coil reaches shieldActivationThreshold of this value."
        );

        accumulatorBuffer = configuration.getInt(
                "accumulatorBuffer",
                CATEGORY_BUFFERS,
                DEFAULT_ACCUMULATOR_BUFFER,
                1,
                Integer.MAX_VALUE,
                "Bulk shield-energy reserve held by a shield accumulator block."
        );

        shieldStrikeAbsorptionRate = configuration.getFloat(
                "shieldStrikeAbsorptionRate",
                CATEGORY_WEAPONS,
                1.0F,
                0.0F,
                Float.MAX_VALUE,
                "Shield energy spent per unit of a cooperative strike's declared impact energy (tier-1). "
                        + "The shield spends min(stored, impactEnergy x rate x kindMultiplier / tierEfficiency)."
        );

        shieldStrikeDamageToEnergyFactor = configuration.getFloat(
                "shieldStrikeDamageToEnergyFactor",
                CATEGORY_WEAPONS,
                500.0F,
                0.0F,
                Float.MAX_VALUE,
                "Converts a cooperating weapon's damage value into declared shield impact energy when the "
                        + "source reports damage rather than energy."
        );

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
