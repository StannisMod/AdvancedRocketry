package zmaster587.advancedRocketry.integration.jei.stationAssembler;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IDrawableAnimated;
import mezz.jei.api.gui.IDrawableAnimated.StartDirection;
import mezz.jei.api.gui.IDrawableStatic;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import zmaster587.advancedRocketry.integration.jei.ARPlugin;
import zmaster587.libVulpes.LibVulpes;

/**
 * Compact layout, mirroring the simple two-input / two-output flow:
 *  Inputs: [Satellite Loader (meta 1)] [Station Chip]
 *  Outputs: [Packed Station] [Station Chip (when new station)]
 */
public class StationAssemblerCategory implements IRecipeCategory<StationAssemblerWrapper> {

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawable slotFrame;
    private static final int BG_W = 180;
    private static final int BG_H = 90;

    private static final ResourceLocation ROCKET_BUILDER_PNG =
        new ResourceLocation("advancedrocketry", "textures/gui/rocketBuilder.png");

    private static final int PB_BACK_U = 76,  PB_BACK_V = 93,  PB_BACK_W = 8,  PB_BACK_H = 52;
    private static final int PB_FILL_U = 176, PB_FILL_V = 15,  PB_FILL_W = 2,  PB_FILL_H = 38;
    private static final int PB_INSET_X = 3,  PB_INSET_Y = 2;
    private static final int ANIM_MS = 100;
    private final IDrawable backBar;           // background frame (8x52)
    private final IDrawableStatic fillStatic;  // fill slice (2x38)
    private final IDrawableAnimated fillAnim;  // animated fill (bottom->top)
    private int _x0, _x1, _y0, _y1;
    private final int barX;
    private final int barY;

    public StationAssemblerCategory(IGuiHelper gui) {
        this.background = gui.createBlankDrawable(BG_W, BG_H);
        this.icon       = gui.createDrawableIngredient(
            new net.minecraft.item.ItemStack(zmaster587.advancedRocketry.api.AdvancedRocketryBlocks.blockStationBuilder));
        this.slotFrame  = gui.getSlotDrawable();

        // build drawables from the exact atlas slices
        this.backBar    = gui.createDrawable(ROCKET_BUILDER_PNG, PB_BACK_U, PB_BACK_V, PB_BACK_W, PB_BACK_H);
        this.fillStatic = gui.createDrawable(ROCKET_BUILDER_PNG, PB_FILL_U, PB_FILL_V, PB_FILL_W, PB_FILL_H);
        this.fillAnim   = gui.createAnimatedDrawable(fillStatic, ANIM_MS, StartDirection.BOTTOM, /*inverted*/ false);

        // position: right edge, centered Y
        this.barX = BG_W - PB_BACK_W;
        this.barY = (BG_H - PB_BACK_H) / 2;
    }

    @Override public String getUid()         { return ARPlugin.stationAssemblerUUID; }
    @Override
    public String getTitle() {
        return new net.minecraft.item.ItemStack(
            zmaster587.advancedRocketry.api.AdvancedRocketryBlocks.blockStationBuilder
        ).getDisplayName();
    }
    @Override public String getModName()     { return "Advanced Rocketry"; }
    @Override public IDrawable getBackground(){ return background; }
    @Override public IDrawable getIcon()     { return icon; }

    // keep your BG_W, BG_H, progress bar fields as-is...

    @Override
    public void setRecipe(IRecipeLayout layout, StationAssemblerWrapper wrapper, IIngredients ing) {
        IGuiItemStackGroup items = layout.getItemStacks();

        // compact columns; roomy rows
        final int SLOT = 18;
        final int COL_GAP = 2;   // close together horizontally
        final int ROW_GAP = 24;  // more empty space vertically

        final int widthNeeded  = SLOT * 2 + COL_GAP;   // 38
        final int heightNeeded = SLOT * 2 + ROW_GAP;   // 60
        final int left = (BG_W - widthNeeded) / 2;
        final int top  = (BG_H - heightNeeded) / 2;

        final int x0 = left;
        final int x1 = left + SLOT + COL_GAP;
        final int y0 = top;
        final int y1 = top + SLOT + ROW_GAP;

        // row 1: [ bay | empty chip ]
        items.init(0, true,  x0, y0);  // bay (Satellite Loader meta 1)
        items.init(1, true,  x1, y0);  // empty chip

        // row 2: [ packed item | programmed chip ]
        items.init(2, false, x0, y1);  // packed station
        items.init(3, false, x1, y1);  // programmed chip

        // bind ingredients
        java.util.List<java.util.List<ItemStack>> inLists  =
            ing.getInputs(mezz.jei.api.ingredients.VanillaTypes.ITEM);
        java.util.List<java.util.List<ItemStack>> outLists =
            ing.getOutputs(mezz.jei.api.ingredients.VanillaTypes.ITEM);

        if (inLists.size()  >= 1) items.set(0, inLists.get(0));
        if (inLists.size()  >= 2) items.set(1, inLists.get(1));
        if (outLists.size() >= 1) items.set(2, outLists.get(0));
        if (outLists.size() >= 2) items.set(3, outLists.get(1));

        // programmed chip: strip original tooltip, show only our hint
        items.addTooltipCallback((slot, input, stack, tooltip) -> {
            if (slot != 3 || stack == null || stack.isEmpty()
                || !(stack.getItem() instanceof zmaster587.advancedRocketry.item.ItemStationChip)) return;

            // Only when chip is unprogrammed
            if (zmaster587.advancedRocketry.item.ItemStationChip.getUUID(stack) == 0) {
                // Vanilla "unprogrammed" text (with and without gray formatting)
                final String vanilla = zmaster587.libVulpes.LibVulpes.proxy.getLocalizedString("msg.unprogrammed");
                final String vanillaGray = net.minecraft.util.text.TextFormatting.GRAY + vanilla;

                // Strip just that line (handle formatting/no-format)
                tooltip.removeIf(line ->
                    line.equals(vanillaGray) ||
                    line.equals(vanilla) ||
                    net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(line).equals(vanilla)
                );

                // Insert our JEI-specific hint
                tooltip.add(net.minecraft.util.text.TextFormatting.GRAY +
                    zmaster587.libVulpes.LibVulpes.proxy.getLocalizedString(
                        "jei.ar.stationAssembler.newStationChipHint"
                    )
                );
            }
        });


        // keep for drawing slot frames
        this._x0 = x0; this._x1 = x1; this._y0 = y0; this._y1 = y1;
    }

    @Override
    public void drawExtras(Minecraft mc) {
        // progress bar (exact sprite, right-aligned, centered Y)
        backBar.draw(mc, barX, barY);
        fillAnim.draw(mc, barX + PB_INSET_X, barY + PB_INSET_Y);

        // draw slot frames at the centered positions
        slotFrame.draw(mc, _x0, _y0);
        slotFrame.draw(mc, _x1, _y0);
        slotFrame.draw(mc, _x0, _y1);
        slotFrame.draw(mc, _x1, _y1);
    }
}
