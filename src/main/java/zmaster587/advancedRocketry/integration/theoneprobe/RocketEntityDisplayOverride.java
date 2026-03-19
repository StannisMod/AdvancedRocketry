package zmaster587.advancedRocketry.integration.theoneprobe;

import mcjty.theoneprobe.api.IEntityDisplayOverride;
import mcjty.theoneprobe.api.IProbeHitEntityData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.ProbeMode;
import mcjty.theoneprobe.api.TextStyleClass;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry.FuelType;
import zmaster587.advancedRocketry.entity.EntityRocket;

public class RocketEntityDisplayOverride implements IEntityDisplayOverride {

    @Override
    public boolean overrideStandardInfo(ProbeMode mode, IProbeInfo probeInfo,
                                        EntityPlayer player, World world,
                                        Entity entity, IProbeHitEntityData data) {
        if (!(entity instanceof EntityRocket)) {
            return false;
        }

        EntityRocket rocket = (EntityRocket) entity;
        probeInfo.text(TextStyleClass.NAME + getRocketDisplayName(rocket));
        probeInfo.text(TextStyleClass.MODNAME + "Advanced Rocketry");
        return true;
    }

    private static String getRocketDisplayName(EntityRocket rocket) {
        FuelType mainFuel = rocket.getRocketFuelType();

        if (mainFuel == FuelType.LIQUID_MONOPROPELLANT) {
            return "Monopropellant Rocket";
        }
        if (mainFuel == FuelType.LIQUID_BIPROPELLANT) {
            return "Bipropellant Rocket";
        }
        if (mainFuel == FuelType.NUCLEAR_WORKING_FLUID) {
            return "Nuclear Rocket";
        }

        return "Rocket";
    }
}