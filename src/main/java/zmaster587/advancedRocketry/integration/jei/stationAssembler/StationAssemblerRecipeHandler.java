package zmaster587.advancedRocketry.integration.jei.stationAssembler;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import zmaster587.advancedRocketry.integration.jei.ARPlugin;

public class StationAssemblerRecipeHandler implements IRecipeHandler<StationAssemblerWrapper> {
    @Override public Class<StationAssemblerWrapper> getRecipeClass() { return StationAssemblerWrapper.class; }
    @Override public String getRecipeCategoryUid(StationAssemblerWrapper r) { return ARPlugin.stationAssemblerUUID; }
    @Override public IRecipeWrapper getRecipeWrapper(StationAssemblerWrapper r) { return r; }
    @Override public boolean isRecipeValid(StationAssemblerWrapper r) { return r != null; }
}
