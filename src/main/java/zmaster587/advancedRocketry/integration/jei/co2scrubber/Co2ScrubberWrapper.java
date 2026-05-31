package zmaster587.advancedRocketry.integration.jei.co2scrubber;

import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.BlankRecipeWrapper;
import net.minecraft.item.ItemStack;
import java.util.Collections;

public class Co2ScrubberWrapper extends BlankRecipeWrapper {
    private final ItemStack cartridge;

    public Co2ScrubberWrapper(ItemStack cartridge) {
        this.cartridge = cartridge;
    }

    @Override
    public void getIngredients(IIngredients ing) {
        // INPUTS: the cartridge (as a list-of-lists)
        ing.setInputLists(mezz.jei.api.ingredients.VanillaTypes.ITEM,
                java.util.Collections.singletonList(
                        java.util.Collections.singletonList(cartridge)));

        // OUTPUTS: expose BOTH blocks + the cartridge
        java.util.List<net.minecraft.item.ItemStack> outs = new java.util.ArrayList<>(3);
        outs.add(new net.minecraft.item.ItemStack(zmaster587.advancedRocketry.api.AdvancedRocketryBlocks.blockCO2Scrubber));
        outs.add(new net.minecraft.item.ItemStack(zmaster587.advancedRocketry.api.AdvancedRocketryBlocks.blockOxygenVent));
        outs.add(cartridge);
        ing.setOutputs(mezz.jei.api.ingredients.VanillaTypes.ITEM, outs);
    }

    // Used by the recipe handler's isRecipeValid
    public ItemStack getCartridgeStack() {
        return cartridge;
    }
}
