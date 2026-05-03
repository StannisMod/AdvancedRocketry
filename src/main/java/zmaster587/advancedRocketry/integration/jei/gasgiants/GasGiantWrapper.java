package zmaster587.advancedRocketry.integration.jei.gasgiants;

import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GasGiantWrapper implements IRecipeWrapper {

    private final int dimId;
    private final String planetName;
    private final String starName;
    private final ResourceLocation planetIcon;
    private final List<FluidStack> fluids;
    private final ItemStack machineStack;

    public GasGiantWrapper(int dimId, String planetName, String starName, ResourceLocation planetIcon, List<FluidStack> fluids) {
        this.dimId = dimId;
        this.planetName = planetName;
        this.starName = starName == null ? "" : starName;
        this.planetIcon = planetIcon;
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

    public String getStarName() {
        return starName;
    }

    public ResourceLocation getPlanetIcon() {
        return planetIcon;
    }

    public List<FluidStack> getFluids() {
        List<FluidStack> copy = new ArrayList<>(fluids.size());
        for (FluidStack fluid : fluids) {
            copy.add(fluid == null ? null : fluid.copy());
        }
        return copy;
    }

    public List<ItemStack> getFluidBucketStacks() {
        List<ItemStack> buckets = new ArrayList<>();

        for (FluidStack fluid : fluids) {
            if (fluid == null || fluid.getFluid() == null) continue;

            FluidStack bucketFluid = fluid.copy();
            bucketFluid.amount = Fluid.BUCKET_VOLUME;

            ItemStack bucket = FluidUtil.getFilledBucket(bucketFluid);

            if (!bucket.isEmpty()) {
                buckets.add(bucket);
            }
        }

        return buckets;
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

        List<ItemStack> bucketOutputs = getFluidBucketStacks();

        if (!bucketOutputs.isEmpty()) {
            ingredients.setOutputs(
                    mezz.jei.api.ingredients.VanillaTypes.ITEM,
                    bucketOutputs
            );
        }
    }

    @Override
    public void drawInfo(Minecraft minecraft, int recipeWidth, int recipeHeight, int mouseX, int mouseY) {
        FontRenderer fr = minecraft.fontRenderer;
        int mainColor = 0x404040;
        int hintColor = 0x7A7A7A; // subtler than main line

        if (planetIcon != null) {
            GlStateManager.pushMatrix();
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableBlend();
            minecraft.getTextureManager().bindTexture(planetIcon);
            Gui.drawModalRectWithCustomSizedTexture(4, 4, 0, 0, 16, 16, 16, 16);
            GlStateManager.popMatrix();
        }

        fr.drawString(fr.trimStringToWidth(planetName, 64), 24, 7, mainColor);

        if (!starName.isEmpty()) {
            GlStateManager.pushMatrix();
            GlStateManager.scale(0.75f, 0.75f, 1.0f);
            fr.drawString(
                    I18n.format("jei.advancedrocketry.gasgiants.orbiting", starName),
                    Math.round(25 / 0.75f),
                    Math.round(16 / 0.75f),
                    hintColor
            );
            GlStateManager.popMatrix();
        }

        IDrawable slotFrame = GasGiantCategory.getSharedSlotFrame();
        if (slotFrame != null) {
            int slotCount = Math.min(fluids.size(), GasGiantCategory.MAX_SLOTS);

            GlStateManager.pushMatrix();
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableBlend();
            GlStateManager.disableLighting();

            for (int i = 0; i < slotCount; i++) {
                int col = 2 - (i % 3);
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