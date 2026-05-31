package zmaster587.advancedRocketry.integration.jei.asteroids;

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
import zmaster587.libVulpes.LibVulpes;

public class AsteroidCategory implements IRecipeCategory<AsteroidWrapper> {

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawable slotFrame;

    private static final int SLOT = 18;
    private static final int PAD = 6;
    private static final int GAP = 10;
    private static final int TITLE_H = 12;

    // Background: [inputs col] gap [6x2 grid]
    private static final int BG_W = PAD + SLOT + GAP + (AsteroidWrapper.COLS * SLOT) + PAD;
    private static final int BG_H = TITLE_H + PAD + (AsteroidWrapper.ROWS * SLOT) + PAD;

    // Grid top-left
    private static final int GRID_X0 = PAD + SLOT + GAP;
    private static final int GRID_Y0 = TITLE_H + PAD;

    // Inputs centered vs grid
    private static final int IN_X = PAD;
    private static final int IN_GAP = 0;
    private static final int IN0_Y = GRID_Y0 + (AsteroidWrapper.ROWS * SLOT - (2 * SLOT + IN_GAP)) / 2;
    private static final int IN1_Y = IN0_Y + SLOT + IN_GAP;

    public AsteroidCategory(IGuiHelper gui) {
        this.background = gui.createBlankDrawable(BG_W, BG_H);
        this.icon = gui.createDrawableIngredient(new ItemStack(AdvancedRocketryBlocks.blockObservatory));
        this.slotFrame = gui.getSlotDrawable();
    }

    @Override public String getUid() { return ARPlugin.asteroidsUUID; }
    @Override public String getTitle() { return LibVulpes.proxy.getLocalizedString("jei.ar.asteroids"); }
    @Override public String getModName() { return "Advanced Rocketry"; }
    @Override public IDrawable getBackground() { return background; }
    @Override public IDrawable getIcon() { return icon; }

    @Override
    public void setRecipe(IRecipeLayout layout, AsteroidWrapper wrapper, IIngredients ing) {
        IGuiItemStackGroup items = layout.getItemStacks();

        // Inputs
        items.init(0, true, IN_X, IN0_Y);
        items.init(1, true, IN_X, IN1_Y);

        // Outputs: 6x2 = 12
        int slot = 2;
        for (int row = 0; row < AsteroidWrapper.ROWS; row++) {
            for (int col = 0; col < AsteroidWrapper.COLS; col++) {
                items.init(slot, false, GRID_X0 + col * SLOT, GRID_Y0 + row * SLOT);
                slot++;
            }
        }

        // Bind inputs
        java.util.List<java.util.List<ItemStack>> inLists =
                ing.getInputs(mezz.jei.api.ingredients.VanillaTypes.ITEM);
        if (inLists.size() > 0) items.set(0, inLists.get(0));
        if (inLists.size() > 1) items.set(1, inLists.get(1));

        // Bind outputs
        java.util.List<java.util.List<ItemStack>> outLists =
                ing.getOutputs(mezz.jei.api.ingredients.VanillaTypes.ITEM);
        if (!outLists.isEmpty() && !outLists.get(0).isEmpty()) {
            java.util.List<ItemStack> outs = outLists.get(0);
            for (int i = 0; i < AsteroidWrapper.PAGE_SIZE; i++) {
                int jeiSlot = 2 + i;
                if (i < outs.size()) items.set(jeiSlot, outs.get(i));
            }
        }
    }

    @Override
    public void drawExtras(Minecraft mc) {
        // Reset GL so slot drawable is vanilla-grey (no tint)
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableLighting();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();

        // Input frames
        slotFrame.draw(mc, IN_X, IN0_Y);
        slotFrame.draw(mc, IN_X, IN1_Y);

        // Grid frames
        for (int row = 0; row < AsteroidWrapper.ROWS; row++) {
            for (int col = 0; col < AsteroidWrapper.COLS; col++) {
                slotFrame.draw(mc, GRID_X0 + col * SLOT, GRID_Y0 + row * SLOT);
            }
        }

        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
