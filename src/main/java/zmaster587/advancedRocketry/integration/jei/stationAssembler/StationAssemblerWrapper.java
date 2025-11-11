package zmaster587.advancedRocketry.integration.jei.stationAssembler;

import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.AdvancedRocketryItems;

/**
 * JEI wrapper for the Station Assembler flow shown in TileStationAssembler.
 * Inputs (by code):
 *  - slot 0: AdvancedRocketryBlocks.blockLoader (meta 1)     -> Satellite Loading Hatch
 *  - slot 1: AdvancedRocketryItems.itemSpaceStationChip       -> Station Chip (can be blank or programmed)
 * Outputs (by code):
 *  - slot 2: AdvancedRocketryItems.itemSpaceStation           -> Packed station (ItemPackedStructure)
 *  - slot 3: AdvancedRocketryItems.itemSpaceStationChip       -> New chip ONLY when making a brand-new station
 *
 * We present both outputs (JEI is illustrative, not conditional).
 */
public class StationAssemblerWrapper implements IRecipeWrapper {

    private final ItemStack inputHatch;
    private final ItemStack inputChip;
    private final ItemStack outStation;
    private final ItemStack outChipMaybe;

    public StationAssemblerWrapper() {
        // Input 0: blockLoader with meta 1
        this.inputHatch = new ItemStack(AdvancedRocketryBlocks.blockLoader, 1, 1);

        // Input 1: station chip item (no NBT required for JEI showcase)
        this.inputChip = new ItemStack(AdvancedRocketryItems.itemSpaceStationChip);

        // Output 2: station item
        this.outStation = new ItemStack(AdvancedRocketryItems.itemSpaceStation);

        // Output 3: station chip (appears when creating a new station)
        this.outChipMaybe = new ItemStack(AdvancedRocketryItems.itemSpaceStationChip);
    }

    @Override
    public void getIngredients(IIngredients ing) {
        // No fluids; only items.
        java.util.List<java.util.List<ItemStack>> inputs = new java.util.ArrayList<>(2);
        inputs.add(java.util.Collections.singletonList(inputHatch));
        inputs.add(java.util.Collections.singletonList(inputChip));
        ing.setInputLists(mezz.jei.api.ingredients.VanillaTypes.ITEM, inputs);

        // Show both outputs to reflect possible results of "Build".
        java.util.List<ItemStack> outs = new java.util.ArrayList<>(2);
        outs.add(outStation);
        outs.add(outChipMaybe);
        ing.setOutputs(mezz.jei.api.ingredients.VanillaTypes.ITEM, outs);
    }

    // Convenience accessors (used by Category layout)
    public ItemStack getInputHatch()   { return inputHatch; }
    public ItemStack getInputChip()    { return inputChip; }
    public ItemStack getOutStation()   { return outStation; }
    public ItemStack getOutChipMaybe() { return outChipMaybe; }

    // Optional: a display/catalyst icon you can use if you decide later.
    static ItemStack iconStack() {
        // Safe: chip exists and isn’t blacklisted in your ARPlugin snippet.
        return new ItemStack(AdvancedRocketryItems.itemSpaceStationChip);
    }
}
