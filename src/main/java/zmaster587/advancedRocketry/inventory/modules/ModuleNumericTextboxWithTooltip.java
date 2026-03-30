package zmaster587.advancedRocketry.inventory.modules;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.libVulpes.inventory.modules.IGuiCallback;
import zmaster587.libVulpes.inventory.modules.ModuleNumericTextbox;

import java.util.Arrays;
import java.util.List;

public class ModuleNumericTextboxWithTooltip extends ModuleNumericTextbox {

    private final List<String> tooltip;
    private final int hoverWidth;
    private final int hoverHeight;

    public ModuleNumericTextboxWithTooltip(
            IGuiCallback tile,
            int offsetX,
            int offsetY,
            int sizeX,
            int sizeY,
            int maxStrLen,
            String... tooltipLines
    ) {
        super(tile, offsetX, offsetY, sizeX, sizeY, maxStrLen);
        this.tooltip = Arrays.asList(tooltipLines);
        this.hoverWidth = sizeX;
        this.hoverHeight = sizeY;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderToolTip(int guiOffsetX, int guiOffsetY, int mouseX, int mouseY, float zLevel, GuiContainer gui, FontRenderer font) {
        if (tooltip == null || tooltip.isEmpty()) {
            return;
        }

        if (mouseX >= offsetX && mouseX < offsetX + hoverWidth
                && mouseY >= offsetY && mouseY < offsetY + hoverHeight) {
            drawTooltip(gui, tooltip, mouseX, mouseY, zLevel, font);
        }
    }
}