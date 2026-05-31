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
        // --- Inputs (your two visible inputs) ---
        java.util.List<java.util.List<ItemStack>> inputs = new java.util.ArrayList<>(3);
        inputs.add(java.util.Collections.singletonList(inputHatch)); // bay (loader meta 1)
        inputs.add(java.util.Collections.singletonList(inputChip));   // empty chip

        // --- Hidden machine block for discoverability (so R/U on block opens this page) ---
        ItemStack stationBlock = new ItemStack(
            zmaster587.advancedRocketry.api.AdvancedRocketryBlocks.blockStationBuilder
        );
        inputs.add(java.util.Collections.singletonList(stationBlock));

        ing.setInputLists(mezz.jei.api.ingredients.VanillaTypes.ITEM, inputs);

        // --- Outputs (show both possible results of "Build") ---
        java.util.List<ItemStack> outs = new java.util.ArrayList<>(3);
        outs.add(outStation);
        if (outChipMaybe != null && !outChipMaybe.isEmpty()) {
            outs.add(outChipMaybe);
        }

        // Also include the block as an output so R on the block finds this page too
        outs.add(stationBlock);

        ing.setOutputs(mezz.jei.api.ingredients.VanillaTypes.ITEM, outs);
    }
}
