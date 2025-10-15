package zmaster587.advancedRocketry.integration.jei.satelliteBuilder;

import mezz.jei.api.gui.ITooltipCallback;
import mezz.jei.api.IJeiHelpers;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import zmaster587.advancedRocketry.api.AdvancedRocketryItems;
import zmaster587.advancedRocketry.integration.jei.satelliteBuilder.SatelliteBuilderWrapper;
import zmaster587.libVulpes.api.LibVulpesItems;
import zmaster587.libVulpes.interfaces.IRecipe;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SatelliteBuilderRecipeMaker {

    public static List<SatelliteBuilderWrapper> getMachineRecipes(IJeiHelpers helpers, Class clazz) {
        List<SatelliteBuilderWrapper> recipes = new ArrayList<>();

        // --- Satellite Assembly Example ---
        // Slot mapping:
        //  0: Function component (core module)
        //  1-6: Module components (power, IO, etc)
        //  7: Output slot
        //  8: ID chip slot (input)
        //  9: Chip copy slot (input)
        // 10: Holding slot (ghostslot, not used by player)
        // 11: Chassis slot


        // slot 0
        List<ItemStack> coreModules = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            coreModules.add(new ItemStack(AdvancedRocketryItems.itemSatellitePrimaryFunction, 1, i));
        } // slot 0
        
        // slots 1-6: power gen, battery, data units
        List<ItemStack> moduleVariants = Arrays.asList(
            new ItemStack(AdvancedRocketryItems.itemSatellitePowerSource, 1, 0),
            new ItemStack(AdvancedRocketryItems.itemSatellitePowerSource, 1, 1),
            new ItemStack(LibVulpesItems.itemBattery, 1, 0),
            new ItemStack(LibVulpesItems.itemBattery, 1, 1),
            new ItemStack(AdvancedRocketryItems.itemDataUnit, 1, 0)
        ); 


        
        // Slot 8: controllers mapped 1:1 to primariry core modules (metas 0-6)
        List<ItemStack> satelliteControllers = Arrays.asList(
            new ItemStack(AdvancedRocketryItems.itemSatelliteIdChip), // 0: Optical
            new ItemStack(AdvancedRocketryItems.itemSatelliteIdChip), // 1: Composition
            new ItemStack(AdvancedRocketryItems.itemSatelliteIdChip), // 2: Mass Scanner
            new ItemStack(AdvancedRocketryItems.itemSatelliteIdChip), // 3: Microwave Energy
            new ItemStack(AdvancedRocketryItems.itemOreScanner),      // 4: Ore Mapping
            new ItemStack(AdvancedRocketryItems.itemBiomeChanger),    // 5: Biome Changer (remote)
            new ItemStack(AdvancedRocketryItems.itemWeatherController)// 6: Weather Controller (remote)
        );
        
        
        List<ItemStack> output = Collections.singletonList(new ItemStack(AdvancedRocketryItems.itemSatellite)); // slot 7 (output)
        List<ItemStack> chassis = Collections.singletonList(new ItemStack(AdvancedRocketryItems.itemSatellite)); // slot 11 (empty chassis)

        List<List<ItemStack>> inputs = new ArrayList<>();
        inputs.add(coreModules);                    // slot 0: Function component
        inputs.add(moduleVariants);                   // slot 1: Module component
        inputs.add(moduleVariants);                   // slot 2: Module component
        inputs.add(moduleVariants);                   // slot 3: Module component
        inputs.add(moduleVariants);                    // slot 4: Module component
        inputs.add(moduleVariants);                    // slot 5: Module component
        inputs.add(moduleVariants);                    // slot 6: Module component
        //inputs.add(Collections.emptyList());        // slot 7: Output slot (not used as input)
        inputs.add(satelliteControllers);                           // slot 8: ID chip slot
        inputs.add(Collections.emptyList());        // slot 9: Chip copy slot (not used in this recipe)
        //inputs.add(Collections.emptyList());        // slot 10: Holding slot (ghostslot, not used)
        inputs.add(chassis);                         // slot 11: Chassis slot

        // Anonymous IRecipe implementation
        IRecipe assemblyRecipe = new IRecipe() {
            @Override
            public List<ItemStack> getOutput() { return output; } // slot 7
            @Override
            public List<FluidStack> getFluidOutputs() { return Collections.emptyList(); }
            @Override
            public List<List<ItemStack>> getIngredients() { return inputs; }
            @Override
            public List<FluidStack> getFluidIngredients() { return Collections.emptyList(); }
            @Override
            public int getTime() { return 200; }
            @Override
            public int getPower() { return 0; }
            @Override
            public String getOreDictString(int var1) { return ""; }
        };

        recipes.add(new SatelliteBuilderWrapper(assemblyRecipe, false));

        // --- Chip Copy Example ---
        // Slot mapping for chip copy:
        //  8: Source chip (input)
        //  9: Blank chip (input)
        //  7: Output slot (copied chip)

        List<ItemStack> sourceChips = Arrays.asList(
            new ItemStack(AdvancedRocketryItems.itemSatelliteIdChip),
            new ItemStack(AdvancedRocketryItems.itemPlanetIdChip),
            new ItemStack(AdvancedRocketryItems.itemSpaceStationChip),
            new ItemStack(AdvancedRocketryItems.itemOreScanner),
            new ItemStack(AdvancedRocketryItems.itemBiomeChanger),
            new ItemStack(AdvancedRocketryItems.itemWeatherController),
            new ItemStack(AdvancedRocketryItems.itemSpaceElevatorChip)
        );

        // Mirror the source chips for blank chips
        List<ItemStack> blankChips = sourceChips;

        // The output cycling in mapped order:
        List<ItemStack> copiedOutputVariants = Arrays.asList(
            new ItemStack(AdvancedRocketryItems.itemSatelliteIdChip),
            new ItemStack(AdvancedRocketryItems.itemPlanetIdChip),
            new ItemStack(AdvancedRocketryItems.itemSpaceStationChip),
            new ItemStack(AdvancedRocketryItems.itemOreScanner),
            new ItemStack(AdvancedRocketryItems.itemBiomeChanger),
            new ItemStack(AdvancedRocketryItems.itemWeatherController),
            new ItemStack(AdvancedRocketryItems.itemSpaceElevatorChip)
        );

        List<List<ItemStack>> chipCopyInputs = new ArrayList<>();
        chipCopyInputs.add(Collections.emptyList()); // slot 0: Function component (not used)
        chipCopyInputs.add(Collections.emptyList()); // slot 1: Power component (not used)
        chipCopyInputs.add(Collections.emptyList()); // slot 2: Power component (not used)
        chipCopyInputs.add(Collections.emptyList()); // slot 3: Power component (not used)
        chipCopyInputs.add(Collections.emptyList()); // slot 4: IO component (not used)
        chipCopyInputs.add(Collections.emptyList()); // slot 5: IO component (not used)
        chipCopyInputs.add(Collections.emptyList()); // slot 6: IO component (not used)
        //chipCopyInputs.add(Collections.emptyList()); // slot 7: Output slot
        chipCopyInputs.add(sourceChips);             // slot 8: Source chip
        chipCopyInputs.add(blankChips);              // slot 9: Blank chip
        //chipCopyInputs.add(Collections.emptyList()); // slot 10: Holding slot (not used)
        chipCopyInputs.add(Collections.emptyList()); // slot 11: Chassis slot (not used)

        IRecipe chipCopyRecipe = new IRecipe() {
            @Override public List<ItemStack> getOutput() { 
                // Could return first as a fallback; the wrapper will override with setOutputLists
                return java.util.Collections.singletonList(copiedOutputVariants.get(0)); 
            }
            @Override
            public List<FluidStack> getFluidOutputs() { return Collections.emptyList(); }
            @Override
            public List<List<ItemStack>> getIngredients() { return chipCopyInputs; }
            @Override
            public List<FluidStack> getFluidIngredients() { return Collections.emptyList(); }
            @Override
            public int getTime() { return 200; }
            @Override
            public int getPower() { return 0; }
            @Override
            public String getOreDictString(int var1) { return ""; }
        };

        recipes.add(new SatelliteBuilderWrapper(chipCopyRecipe, true, copiedOutputVariants));

        return recipes;
    }
}
