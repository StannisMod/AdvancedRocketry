package zmaster587.advancedRocketry.integration.jei.fuelingStation;

import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;

import java.util.Collections;

public class FuelingStationWrapper implements IRecipeWrapper {

    public enum Role {
        MONO("monopropellant"), BIPROP_FUEL("biprop_fuel"),
        OXIDIZER("oxidizer"),    WORKING_FLUID("working_fluid");
        private final String key; Role(String k){ this.key = k; }
        public String langKey(){ return key; }
    }

    private final FluidStack fluid;
    private final Role role;

    public FuelingStationWrapper(FluidStack fluid, Role role) {
        this.fluid = fluid;
        this.role  = role;
    }

    public Role getRole()   { return role; }
    public FluidStack getFluid() { return fluid; }

    @Override
    public void getIngredients(IIngredients ing) {
        // Fluid input (internal tank)
        ing.setInputs(mezz.jei.api.ingredients.VanillaTypes.FLUID,
                java.util.Collections.singletonList(fluid));

        // ITEM inputs in order:
        // 0: [filled bucket?]
        // 1: [role tank]
        // 2: [fueling station]  <-- hidden, just for discoverability via U/R on the block
        java.util.List<java.util.List<ItemStack>> itemInputs = new java.util.ArrayList<>(3);

        // 0) filled bucket (if present)
        ItemStack filled = getFilledContainer();
        if (!filled.isEmpty()) {
            itemInputs.add(java.util.Collections.singletonList(filled));
        }

        // 1) role tank (always try to include)
        ItemStack roleTank = getRoleTankStack();
        if (!roleTank.isEmpty()) {
            itemInputs.add(java.util.Collections.singletonList(roleTank));
        }

        // 2) fueling station (hidden ingredient so U/R on the block opens this tab)
        ItemStack station = new ItemStack(zmaster587.advancedRocketry.api.AdvancedRocketryBlocks.blockFuelingStation);
        itemInputs.add(java.util.Collections.singletonList(station));

        // commit item inputs
        ing.setInputLists(mezz.jei.api.ingredients.VanillaTypes.ITEM, itemInputs);

        // Outputs: keep role tank (if present) and ALSO the station (so R on the block opens this tab)
        java.util.List<ItemStack> outs = new java.util.ArrayList<>(2);
        if (!roleTank.isEmpty()) outs.add(roleTank);
        outs.add(station);
        ing.setOutputs(mezz.jei.api.ingredients.VanillaTypes.ITEM, outs);
    }


    public ItemStack getRoleTankStack() {
        switch (role) {
            case MONO:
                return AdvancedRocketryBlocks.blockFuelTank != null ? new ItemStack(AdvancedRocketryBlocks.blockFuelTank) : ItemStack.EMPTY;
            case BIPROP_FUEL:
                return AdvancedRocketryBlocks.blockBipropellantFuelTank != null ? new ItemStack(AdvancedRocketryBlocks.blockBipropellantFuelTank) : ItemStack.EMPTY;
            case OXIDIZER:
                return AdvancedRocketryBlocks.blockOxidizerFuelTank != null ? new ItemStack(AdvancedRocketryBlocks.blockOxidizerFuelTank) : ItemStack.EMPTY;
            case WORKING_FLUID:
                return AdvancedRocketryBlocks.blockNuclearFuelTank != null ? new ItemStack(AdvancedRocketryBlocks.blockNuclearFuelTank) : ItemStack.EMPTY;
            default:
                return ItemStack.EMPTY;
        }
    }

    // keep this helper; category uses it conditionally
    public ItemStack getFilledContainer() {
        ItemStack is = net.minecraftforge.fluids.FluidUtil.getFilledBucket(fluid);
        return is == null ? ItemStack.EMPTY : is;
    }

    static ItemStack fuelStationDisplayStack() {
        return new ItemStack(AdvancedRocketryBlocks.blockFuelingStation);
    }
}
