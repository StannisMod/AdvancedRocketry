package zmaster587.advancedRocketry.integration.jei.fuelingStation;

import mezz.jei.api.IJeiHelpers;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry.FuelType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FuelingStationRecipeMaker {

    public static List<FuelingStationWrapper> getRecipes(IJeiHelpers helpers) {
        List<FuelingStationWrapper> out = new ArrayList<>();

        add(out, FuelType.LIQUID_MONOPROPELLANT,  FuelingStationWrapper.Role.MONO);
        add(out, FuelType.LIQUID_BIPROPELLANT,   FuelingStationWrapper.Role.BIPROP_FUEL);
        add(out, FuelType.LIQUID_OXIDIZER,       FuelingStationWrapper.Role.OXIDIZER);
        add(out, FuelType.NUCLEAR_WORKING_FLUID, FuelingStationWrapper.Role.WORKING_FLUID);

        return out;
    }

    private static void add(List<FuelingStationWrapper> list,
                            FuelType type,
                            FuelingStationWrapper.Role role) {
        // Avoid name clash with AR’s FuelRegistry by fully-qualifying Forge’s registry here.
        for (Map.Entry<String, Fluid> e : net.minecraftforge.fluids.FluidRegistry.getRegisteredFluids().entrySet()) {
            Fluid f = e.getValue();
            if (f != null && FuelRegistry.instance.isFuel(type, f)) {
                list.add(new FuelingStationWrapper(new FluidStack(f, 1000), role));
            }
        }
    }

    public static List<FuelingStationWrapper> getMachineRecipes(IJeiHelpers helpers, Class<?> ignored) {
        return getRecipes(helpers);
    }
}
