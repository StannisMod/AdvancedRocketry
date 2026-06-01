package zmaster587.advancedRocketry.client.gui;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.IAtmosphere;
import zmaster587.advancedRocketry.tile.atmosphere.TileAtmosphereDetector;
import zmaster587.libVulpes.inventory.modules.ModuleButton;

@SideOnly(Side.CLIENT)
public class ModuleSelectableAtmosphereButton extends ModuleButton {

    private static final int BUTTON_COLOR_NORMAL = 0xFF22FF22;
    private static final int BUTTON_COLOR_SELECTED = 0xFFFFFF55;
    private static final int BUTTON_BG_NORMAL = 0xFFFFFFFF;
    private static final int BUTTON_BG_SELECTED = 0xFF444444;

    private final IAtmosphere atmosphere;
    private final TileAtmosphereDetector detector;

    public ModuleSelectableAtmosphereButton(int offsetX, int offsetY, int buttonId, IAtmosphere atmosphere, String text, TileAtmosphereDetector detector, ResourceLocation[] buttonImages) {
        super(offsetX, offsetY, buttonId, text, detector, buttonImages);
        this.atmosphere = atmosphere;
        this.detector = detector;
    }

    @Override
    public void renderForeground(int guiOffsetX, int guiOffsetY, int mouseX, int mouseY, float zLevel, GuiContainer gui, FontRenderer font) {
        boolean selected = detector.isAtmosphereSelected(atmosphere);

        setColor(selected ? BUTTON_COLOR_SELECTED : BUTTON_COLOR_NORMAL);
        setBGColor(selected ? BUTTON_BG_SELECTED : BUTTON_BG_NORMAL);

        super.renderForeground(guiOffsetX, guiOffsetY, mouseX, mouseY, zLevel, gui, font);
    }
}