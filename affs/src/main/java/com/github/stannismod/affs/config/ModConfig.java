package com.github.stannismod.affs.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public final class ModConfig {

    private static final String CATEGORY_IMPACT = "impact";
    private static final String CATEGORY_SHIELD = "shield";
    private static final String CATEGORY_CONTOUR = "contour";

    private static Configuration configuration;

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

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
