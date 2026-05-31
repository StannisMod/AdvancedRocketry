package zmaster587.advancedRocketry.integration.jei.satelliteBuilder;

import mezz.jei.api.IGuiHelper;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import zmaster587.advancedRocketry.integration.jei.ARPlugin;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.libVulpes.LibVulpes;

import java.util.List;

public class SatelliteBuilderCategory implements IRecipeCategory<SatelliteBuilderWrapper> {

    private final IDrawable background;
    private final IDrawable slotFunctionComponent;
    private final IDrawable slotPowerComponent;
    private final IDrawable slotIO;
    private final IDrawable slotSatellite;
    private final IDrawable slotIdChip;
    private final IDrawable progressBar;
    private final IDrawable vanillaSlot;
    private final String uid;

    public SatelliteBuilderCategory(IGuiHelper guiHelper) {
        // Use a blank or minimal background (176x90 is enough for the elements)
        this.background = guiHelper.createBlankDrawable(176, 90);

        // Slot frames/icons from TextureResources
        this.slotFunctionComponent = guiHelper.createDrawable(
                new ResourceLocation("libvulpes:textures/gui/maingui.png"),
                212, 18, 18, 18); // functionComponent

        this.slotPowerComponent = guiHelper.createDrawable(
                new ResourceLocation("libvulpes:textures/gui/maingui.png"),
                230, 18, 18, 18); // powercomponent

        this.slotIO = guiHelper.createDrawable(
                new ResourceLocation("libvulpes:textures/gui/maingui.png"),
                212, 0, 18, 18); // ioSlot

        this.slotSatellite = guiHelper.createDrawable(
                new ResourceLocation("advancedrocketry:textures/gui/progressBars/progressBars.png"),
                220, 238, 18, 18);

        this.slotIdChip = guiHelper.createDrawable(
                new ResourceLocation("libvulpes:textures/gui/maingui.png"),
                230, 0, 18, 18); // idChip

        this.vanillaSlot = guiHelper.getSlotDrawable();

        this.progressBar = guiHelper.createDrawable(
                new ResourceLocation("advancedrocketry:textures/gui/progressBars/progressBars.png"),
                217, 0, 17, 17); // progressBar

        this.uid = ARPlugin.satelliteBuilderUUID;
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public String getTitle() {
        return LibVulpes.proxy.getLocalizedString("tile.satelliteBuilder.name");
    }

    @Override
    public String getModName() {
        return "Advanced Rocketry";
    }

    @Override
    public String getUid() {
        return uid;
    }

    public Class<? extends SatelliteBuilderWrapper> getRecipeClass() {
        return SatelliteBuilderWrapper.class;
    }


    @Override
    public void setRecipe(IRecipeLayout recipeLayout, SatelliteBuilderWrapper wrapper, IIngredients ingredients) {
        // Place JEI slots at the same coordinates as the modules
        // Slot indices: see TileSatelliteBuilder for mapping
        // 0: function, 1-6: IO, 7: Output, 8: Chip, 9: Chip, 10: Chip copy 11: chassis
        // Function slot 0
        recipeLayout.getItemStacks().init(0, true, 152, 10);

        // Power slots 1-2-3
        recipeLayout.getItemStacks().init(1, true, 116, 30);
        recipeLayout.getItemStacks().init(2, true, 134, 30);
        recipeLayout.getItemStacks().init(3, true, 152, 30);
        recipeLayout.getItemStacks().init(4, true, 116, 50);
        recipeLayout.getItemStacks().init(5, true, 134, 50);
        recipeLayout.getItemStacks().init(6, true, 152, 50);

        // Output slot 7
        recipeLayout.getItemStacks().init(7, false, 58, 36);

        // ID chip slot 8
        recipeLayout.getItemStacks().init(8, true, 58, 16);

        // Chip copy slot 9
        recipeLayout.getItemStacks().init(9, true, 82, 16);

        // holdingslot slot 10 not used by players
        //recipeLayout.getItemStacks().init(10, false, 58, 36);

        // Chassis slot 11 
        recipeLayout.getItemStacks().init(11, true, 38, 16);
        
        recipeLayout.getItemStacks().set(ingredients);
        
        // Add tooltip cosmetics
        wrapper.registerTooltipCallbacks(recipeLayout.getItemStacks());
    }

    @Override
    public void drawExtras(Minecraft minecraft) {
        FontRenderer fr = minecraft.fontRenderer;
        // Draw slot frames and icons at the correct positions
        slotFunctionComponent.draw(minecraft, 152, 10); // slot 0
        slotPowerComponent.draw(minecraft, 116, 30); // slot 1
        slotPowerComponent.draw(minecraft, 134, 30); // slot 2
        slotPowerComponent.draw(minecraft, 152, 30); // slot 3
        slotIO.draw(minecraft, 116, 50); // slot 4
        slotIO.draw(minecraft, 134, 50); // slot 5
        slotIO.draw(minecraft, 152, 50); // slot 6
        vanillaSlot.draw(minecraft, 58, 36); // Output slot (slot 7)
        slotIdChip.draw(minecraft, 58, 16); // slot 8
        slotIdChip.draw(minecraft, 82, 16); // slot 9
        slotSatellite.draw(minecraft, 38, 16); // Chassis slot (slot 11)

        // Progress bar
        progressBar.draw(minecraft, 75, 36);

    }
    @Override
    public java.util.List<String> getTooltipStrings(int mouseX, int mouseY) {
        return java.util.Collections.emptyList();
    }

}
