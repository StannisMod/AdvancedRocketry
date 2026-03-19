package zmaster587.advancedRocketry.integration.theoneprobe;

import mcjty.theoneprobe.api.IProbeHitEntityData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoEntityProvider;
import mcjty.theoneprobe.api.NumberFormat;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import zmaster587.advancedRocketry.api.StatsRocket;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry.FuelType;
import zmaster587.advancedRocketry.entity.EntityRocket;

public class RocketEntityProbeProvider implements IProbeInfoEntityProvider {

    @Override
    public String getID() {
        return "advancedrocketry:rocket_entity";
    }

    @Override
    public void addProbeEntityInfo(ProbeMode mode, IProbeInfo probeInfo, EntityPlayer player, World world, Entity entity, IProbeHitEntityData data) {
        if (!(entity instanceof EntityRocket)) {
            return;
        }

        EntityRocket rocket = (EntityRocket) entity;
        StatsRocket stats = rocket.getRocketStats();

        FuelType mainFuel = rocket.getRocketFuelType();
        if (mainFuel == null) {
            return;
        }

        switch (mainFuel) {
            case LIQUID_MONOPROPELLANT:
                addFuelSection(probeInfo, "Fuel", stats.getFuelFluid(), "Monopropellant",
                        rocket.getFuelAmount(FuelType.LIQUID_MONOPROPELLANT),
                        rocket.getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT));
                break;

            case LIQUID_BIPROPELLANT:
                addFuelSection(probeInfo, "Fuel", stats.getFuelFluid(), "Bipropellant Fuel",
                        rocket.getFuelAmount(FuelType.LIQUID_BIPROPELLANT),
                        rocket.getFuelCapacity(FuelType.LIQUID_BIPROPELLANT));

                addFuelSection(probeInfo, "Oxidizer", stats.getOxidizerFluid(), "Oxidizer",
                        rocket.getFuelAmount(FuelType.LIQUID_OXIDIZER),
                        rocket.getFuelCapacity(FuelType.LIQUID_OXIDIZER));
                break;

            case NUCLEAR_WORKING_FLUID:
                addFuelSection(probeInfo, "Working Fluid", stats.getWorkingFluid(), "Working Fluid",
                        rocket.getFuelAmount(FuelType.NUCLEAR_WORKING_FLUID),
                        rocket.getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID));
                break;
        }



        if (mode == ProbeMode.EXTENDED) {
        probeInfo.text("Burn rate: " + rocket.getFuelConsumptionRate(mainFuel) + " mB/t");
        }
    }

    private static void addFuelSection(IProbeInfo probeInfo, String label, String registryName, String fallbackName, int amount, int capacity) {
        if (capacity <= 0) {
            return;
        }

        probeInfo.text(label + ": " + getPrettyFluidName(registryName, fallbackName));
        probeInfo.progress(amount, capacity,
                probeInfo.defaultProgressStyle()
                        .suffix(" mB")
                        .numberFormat(NumberFormat.COMMAS));
    }

    private static String getPrettyFluidName(String registryName, String fallbackName) {
        if (registryName == null || registryName.isEmpty() || "null".equals(registryName)) {
            return fallbackName;
        }

        Fluid fluid = FluidRegistry.getFluid(registryName);
        if (fluid == null) {
            return registryName;
        }

        try {
            return fluid.getLocalizedName(new FluidStack(fluid, 1));
        } catch (Exception e) {
            return fluid.getName();
        }
    }
}