package zmaster587.advancedRocketry.integration.jei.gasgiants;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraftforge.fluids.FluidStack;

public class GasGiantRecipeHandler implements IRecipeHandler<GasGiantWrapper> {

    @Override
    public Class<GasGiantWrapper> getRecipeClass() {
        return GasGiantWrapper.class;
    }

    @Override
    public String getRecipeCategoryUid(GasGiantWrapper recipe) {
        return GasGiantCategory.UID;
    }

    @Override
    public IRecipeWrapper getRecipeWrapper(GasGiantWrapper recipe) {
        return recipe;
    }

    @Override
    public boolean isRecipeValid(GasGiantWrapper recipe) {
        if (recipe == null) return false;
        if (recipe.getPlanetName() == null || recipe.getPlanetName().isEmpty()) return false;
        if (recipe.getFluids() == null || recipe.getFluids().isEmpty()) return false;

        for (FluidStack stack : recipe.getFluids()) {
            if (stack == null || stack.getFluid() == null) return false;
        }

        return true;
    }
}