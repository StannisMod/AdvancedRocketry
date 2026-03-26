package zmaster587.advancedRocketry.integration.jei.gasgiants;

import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GasGiantWrapper implements IRecipeWrapper {

    private final int dimId;
    private final String planetName;
    private final List<FluidStack> fluids;
    private final ItemStack machineStack;

    public GasGiantWrapper(int dimId, String planetName, List<FluidStack> fluids) {
        this.dimId = dimId;
        this.planetName = planetName;
        this.machineStack = new ItemStack(AdvancedRocketryBlocks.blockDeployableRocketBuilder);

        this.fluids = new ArrayList<>();
        if (fluids != null) {
            for (FluidStack fluid : fluids) {
                if (fluid != null) {
                    this.fluids.add(fluid.copy());
                }
            }
        }
    }

    public int getDimId() {
        return dimId;
    }

    public String getPlanetName() {
        return planetName;
    }

    public List<FluidStack> getFluids() {
        List<FluidStack> copy = new ArrayList<>(fluids.size());
        for (FluidStack fluid : fluids) {
            copy.add(fluid == null ? null : fluid.copy());
        }
        return copy;
    }

    public ItemStack getMachineStack() {
        return machineStack;
    }

    @Override
    public void getIngredients(IIngredients ingredients) {
        ingredients.setInputLists(
                mezz.jei.api.ingredients.VanillaTypes.ITEM,
                Collections.singletonList(Collections.singletonList(machineStack))
        );

        ingredients.setOutputs(
                mezz.jei.api.ingredients.VanillaTypes.FLUID,
                getFluids()
        );
    }

    @Override
    public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
        FontRenderer fr = minecraft.fontRenderer;
        int color = 0x404040;

        fr.drawString(fr.trimStringToWidth(planetName, 58), 30, 10, color);
        fr.drawString("Dim: " + dimId, 30, 24, color);

        IDrawable slotFrame = GasGiantCategory.getSharedSlotFrame();
        if (slotFrame != null) {
            int slotCount = Math.min(fluids.size(), GasGiantCategory.MAX_SLOTS);

            GlStateManager.pushMatrix();
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableBlend();
            GlStateManager.disableLighting();

            for (int i = 0; i < slotCount; i++) {
                int col = 2 - (i % 3); // right-to-left
                int row = i / 3;

                int x = GasGiantCategory.GRID_X + col * GasGiantCategory.CELL;
                int y = GasGiantCategory.GRID_Y + row * GasGiantCategory.CELL;

                slotFrame.draw(minecraft, x, y);
            }

            GlStateManager.popMatrix();
        }
    }

    @Override
    public List<String> getTooltipStrings(int mouseX, int mouseY) {
        return Collections.emptyList();
    }
}