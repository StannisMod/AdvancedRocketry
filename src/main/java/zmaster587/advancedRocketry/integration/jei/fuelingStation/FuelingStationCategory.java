package zmaster587.advancedRocketry.integration.jei.fuelingStation;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.*;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.integration.jei.ARPlugin;
import zmaster587.libVulpes.gui.CommonResources;

public class FuelingStationCategory implements IRecipeCategory<FuelingStationWrapper> {

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawable tankFrame;   // 14x54 bezel from generic background
    private final IDrawable slotFrame;   // JEI’s standard slot look

    // --- compact but accurate: 150 x 56 so 4 recipes fit on one JEI page ---
    public FuelingStationCategory(IGuiHelper gui) {
        this.background = gui.createBlankDrawable(150, 56);
        this.icon       = gui.createDrawableIngredient(new ItemStack(AdvancedRocketryBlocks.blockFuelingStation));

        // exact bezel the in-game ModuleLiquidIndicator draws: u=176,v=58,w=14,h=54
        this.tankFrame  = gui.createDrawable(CommonResources.genericBackground, 176, 58, 14, 54);

        // vanilla-looking slot border
        this.slotFrame  = gui.getSlotDrawable();
    }

    @Override public String getUid()         { return ARPlugin.fuelingStationUUID; }
    @Override public String getTitle()       { return new ItemStack(AdvancedRocketryBlocks.blockFuelingStation).getDisplayName(); }
    @Override public String getModName()     { return "Advanced Rocketry"; }
    @Override public IDrawable getBackground(){ return background; }
    @Override public IDrawable getIcon()     { return icon; }

    @Override
    public void setRecipe(IRecipeLayout layout, FuelingStationWrapper wrapper, IIngredients ing) {
        // Fluid gauge (inside the real bezel)
        IGuiFluidStackGroup fluids = layout.getFluidStacks();
        fluids.init(0, true, 28, 3, 12, 52, 1000, false, null);
        fluids.set(0, wrapper.getFluid());

        IGuiItemStackGroup items = layout.getItemStacks();

        // ITEM inputs come as two lists: [ [bucket?], [role tank] ]
        java.util.List<java.util.List<ItemStack>> itemInputs =
                ing.getInputs(mezz.jei.api.ingredients.VanillaTypes.ITEM);

        // Slot 0: bucket INPUT (if present)
        items.init(0, true, 45, 6);
        if (!itemInputs.isEmpty() && !itemInputs.get(0).isEmpty()
                && itemInputs.get(0).get(0).getItem() == wrapper.getFilledContainer().getItem()) {
            items.set(0, itemInputs.get(0));
        } else {
            items.set(0, java.util.Collections.emptyList());
        }
        items.addTooltipCallback((slotIndex, input, stack, tooltip) -> {
            if (slotIndex != 0 || stack == null || stack.isEmpty()) return;

            // Only decorate the bucket input slot
            tooltip.add("");
            tooltip.add(net.minecraft.util.text.TextFormatting.YELLOW +
                zmaster587.libVulpes.LibVulpes.proxy.getLocalizedString(
                    "jei.ar.fuel.role." + wrapper.getRole().langKey()
                ));
        });

        // Slot 1: ROLE TANK
        items.init(1, true, 120, 6);
        // The role tank will be the other input list
        if (itemInputs.size() >= 2) {
            items.set(1, itemInputs.get(1));
        } else if (!wrapper.getRoleTankStack().isEmpty()) {
            // fallback if bucket missing -> the only input is the role tank
            items.set(1, java.util.Collections.singletonList(wrapper.getRoleTankStack()));
        }
        fluids.addTooltipCallback((slotIndex, input, fluid, tooltip) -> {
            if (slotIndex != 0 || fluid == null) return;

            // Blank spacer then role + usage
            tooltip.add("");
            tooltip.add(net.minecraft.util.text.TextFormatting.YELLOW +
                zmaster587.libVulpes.LibVulpes.proxy.getLocalizedString(
                    "jei.ar.fuel.role." + wrapper.getRole().langKey()
                ));
        });        
    }

    @Override
    public void drawExtras(Minecraft mc) {
        tankFrame.draw(mc, 27, 2);
        slotFrame.draw(mc, 45, 6);
    }

}
