package zmaster587.advancedRocketry.integration.jei.asteroids;

import mezz.jei.api.recipe.IRecipeHandler;
import mezz.jei.api.recipe.IRecipeWrapper;
import zmaster587.advancedRocketry.integration.jei.ARPlugin;

public class AsteroidRecipeHandler implements IRecipeHandler<AsteroidWrapper> {
    @Override public Class<AsteroidWrapper> getRecipeClass() { return AsteroidWrapper.class; }
    @Override public String getRecipeCategoryUid(AsteroidWrapper r) { return ARPlugin.asteroidsUUID; }
    @Override public IRecipeWrapper getRecipeWrapper(AsteroidWrapper r) { return r; }
    @Override public boolean isRecipeValid(AsteroidWrapper r) { return r != null && r.isValid(); }
}
