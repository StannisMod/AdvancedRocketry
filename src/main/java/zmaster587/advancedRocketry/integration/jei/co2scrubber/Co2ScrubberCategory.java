package zmaster587.advancedRocketry.integration.jei.co2scrubber;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.gui.IRecipeLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.integration.jei.ARPlugin;

public class Co2ScrubberCategory implements IRecipeCategory<Co2ScrubberWrapper> {

    private final IDrawable bg;
    private final IDrawable icon;
    private final IDrawable slot;

    public Co2ScrubberCategory(IGuiHelper gui) {
        this.bg   = gui.createBlankDrawable(150, 40);
        this.icon = gui.createDrawableIngredient(new ItemStack(AdvancedRocketryBlocks.blockCO2Scrubber));
        this.slot = gui.getSlotDrawable();
    }

    @Override public String getUid()         { return ARPlugin.co2ScrubberUUID; }
    @Override public String getTitle()       { return new ItemStack(AdvancedRocketryBlocks.blockCO2Scrubber).getDisplayName(); }
    @Override public String getModName()     { return "Advanced Rocketry"; }
    @Override public IDrawable getBackground(){ return bg; }
    @Override public IDrawable getIcon()     { return icon; }

    @Override
    public void setRecipe(IRecipeLayout layout, Co2ScrubberWrapper wrapper, IIngredients ing) {
        IGuiItemStackGroup items = layout.getItemStacks();

        // One input slot (cartridge), left side
        items.init(0, true, 20, 11);
        items.set(0, ing.getInputs(mezz.jei.api.ingredients.VanillaTypes.ITEM).get(0));

        // Oxygen Vent ghost on the right
        items.init(1, false, 120, 11);
        items.set(1, new ItemStack(AdvancedRocketryBlocks.blockOxygenVent));
    }

    @Override
    public void drawExtras(Minecraft mc) {
        // Draw the slot frame behind the cartridge
        slot.draw(mc, 20, 11);
    }
}
