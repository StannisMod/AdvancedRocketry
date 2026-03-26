package zmaster587.advancedRocketry.integration.jei.gasgiants;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IGuiFluidStackGroup;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class GasGiantCategory implements IRecipeCategory<GasGiantWrapper> {

    public static final String UID = "zmaster587.AR.gasGiants";

    public static final int GRID_X = 94;
    public static final int GRID_Y = 2;
    public static final int CELL = 18;
    public static final int MAX_SLOTS = 9;

    private static IDrawable sharedSlotFrame;

    private final IDrawable background;
    private final IDrawable icon;
    private final IDrawable slotFrame;

    public GasGiantCategory(IGuiHelper gui) {
        this.background = gui.createBlankDrawable(150, 56);
        this.icon = gui.createDrawableIngredient(new ItemStack(AdvancedRocketryBlocks.blockDeployableRocketBuilder));
        this.slotFrame = gui.getSlotDrawable();
        sharedSlotFrame = this.slotFrame;
    }

    public static IDrawable getSharedSlotFrame() {
        return sharedSlotFrame;
    }

    private static String getHarvestCapTooltip() {
        ARConfiguration cfg = ARConfiguration.getCurrentConfig();

        if (cfg.gasHarvestInfinite) {
            return TextFormatting.AQUA + "Harvest cap: Infinite";
        }

        long capMb = Math.round(64000D * cfg.gasHarvestAmountMultiplier);
        return TextFormatting.AQUA + "Harvest cap: "
                + NumberFormat.getIntegerInstance(Locale.US).format(capMb)
                + " mB/mission";
    }

    @Override
    public String getUid() {
        return UID;
    }

    @Override
    public String getTitle() {
        return "Gas Missions";
    }

    @Override
    public String getModName() {
        return "Advanced Rocketry";
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(mezz.jei.api.gui.IRecipeLayout layout, GasGiantWrapper wrapper, IIngredients ingredients) {
        IGuiItemStackGroup items = layout.getItemStacks();
        IGuiFluidStackGroup fluids = layout.getFluidStacks();

        items.init(0, true, 8, 19);
        items.set(0, wrapper.getMachineStack());

        List<FluidStack> gasList = wrapper.getFluids();
        int slotCount = Math.min(gasList.size(), MAX_SLOTS);

        for (int i = 0; i < slotCount; i++) {
            int col = 2 - (i % 3);
            int row = i / 3;

            int x = GRID_X + col * CELL + 1;
            int y = GRID_Y + row * CELL + 1;

            fluids.init(i, false, x, y, 16, 16, 1000, false, null);
            fluids.set(i, gasList.get(i));
        }

        fluids.addTooltipCallback((slotIndex, input, fluid, tooltip) -> {
            if (slotIndex < 0 || slotIndex >= slotCount || fluid == null) return;

            tooltip.add("");
            tooltip.add(TextFormatting.YELLOW + wrapper.getPlanetName());
            tooltip.add(TextFormatting.GRAY + "Dim " + wrapper.getDimId());
            tooltip.add(getHarvestCapTooltip());
        });
    }

    @Override
    public void drawExtras(Minecraft minecraft) {
        slotFrame.draw(minecraft, 8, 19);
    }
}