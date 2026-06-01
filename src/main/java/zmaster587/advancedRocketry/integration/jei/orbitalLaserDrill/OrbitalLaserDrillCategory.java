package zmaster587.advancedRocketry.integration.jei.orbitalLaserDrill;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.integration.jei.ARPlugin;

public class OrbitalLaserDrillCategory implements IRecipeCategory<OrbitalLaserDrillWrapper> {

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawable slotFrame;

    private static final int SLOT = 18;
    private static final int GRID = OrbitalLaserDrillWrapper.GRID;
    private static final int PAD = 6;
    private static final int GAP = 10;

    private static final int BG_W = PAD + SLOT + GAP + (GRID * SLOT) + PAD;
    private static final int BG_H = PAD + (GRID * SLOT) + PAD;

    private static final int MACHINE_X = PAD;
    private static final int MACHINE_Y = (BG_H - SLOT) / 2;

    private static final int GRID_X0 = PAD + SLOT + GAP;
    private static final int GRID_Y0 = PAD;

    private String header = "";

    public OrbitalLaserDrillCategory(IGuiHelper gui) {
        this.background = gui.createBlankDrawable(BG_W, BG_H);
        this.icon = gui.createDrawableIngredient(new ItemStack(AdvancedRocketryBlocks.blockSpaceLaser));
        this.slotFrame = gui.getSlotDrawable();
    }

    @Override public String getUid() { return ARPlugin.orbitalLaserDrillUUID; }

    @Override
    public String getTitle() {
        return new ItemStack(AdvancedRocketryBlocks.blockSpaceLaser).getDisplayName();
    }

    @Override public String getModName() { return "Advanced Rocketry"; }
    @Override public IDrawable getBackground() { return background; }
    @Override public IDrawable getIcon() { return icon; }

    @Override
    public void setRecipe(IRecipeLayout layout, OrbitalLaserDrillWrapper wrapper, IIngredients ing) {
        this.header = (wrapper != null) ? wrapper.getHeaderText() : "";

        IGuiItemStackGroup items = layout.getItemStacks();

        items.init(0, true, MACHINE_X, MACHINE_Y);

        int slot = 1;
        for (int row = 0; row < GRID; row++) {
            for (int col = 0; col < GRID; col++) {
                items.init(slot, false, GRID_X0 + col * SLOT, GRID_Y0 + row * SLOT);
                slot++;
            }
        }

        java.util.List<java.util.List<ItemStack>> inLists =
                ing.getInputs(mezz.jei.api.ingredients.VanillaTypes.ITEM);
        if (!inLists.isEmpty()) {
            items.set(0, inLists.get(0));
        }

        java.util.List<java.util.List<ItemStack>> outLists =
                ing.getOutputs(mezz.jei.api.ingredients.VanillaTypes.ITEM);

        if (!outLists.isEmpty() && !outLists.get(0).isEmpty()) {
            java.util.List<ItemStack> outs = outLists.get(0);
            for (int i = 0; i < (GRID * GRID); i++) {
                int jeiSlot = 1 + i;
                if (i < outs.size()) {
                    items.set(jeiSlot, outs.get(i));
                }
            }
        }
    }

    @Override
    public void drawExtras(Minecraft mc) {
        // Slot frame for machine
        slotFrame.draw(mc, MACHINE_X, MACHINE_Y);

        // Slot frames for grid
        for (int row = 0; row < GRID; row++) {
            for (int col = 0; col < GRID; col++) {
                slotFrame.draw(mc, GRID_X0 + col * SLOT, GRID_Y0 + row * SLOT);
            }
        }
    }
}
