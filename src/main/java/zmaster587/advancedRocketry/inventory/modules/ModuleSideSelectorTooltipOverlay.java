package zmaster587.advancedRocketry.inventory.modules;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModuleBlockSideSelector;

import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public class ModuleSideSelectorTooltipOverlay extends ModuleBase {

    private final ModuleBlockSideSelector selector;
    private final String[] stateNames;

    // Match lib layout + side order
    private final int[][] rects;

    // Keep allocations low: reuse list
    private final List<String> tooltip = new ArrayList<>(2);

    private static String dirName(int side) {
        switch (side) {
            case 0: return I18n.format("advancedrocketry.sideselector.direction.bottom");
            case 1: return I18n.format("advancedrocketry.sideselector.direction.top");
            case 2: return I18n.format("advancedrocketry.sideselector.direction.north");
            case 3: return I18n.format("advancedrocketry.sideselector.direction.south");
            case 4: return I18n.format("advancedrocketry.sideselector.direction.west");
            case 5: return I18n.format("advancedrocketry.sideselector.direction.east");
            default: return "?";
        }
    }

    public ModuleSideSelectorTooltipOverlay(int offsetX, int offsetY,
                                            ModuleBlockSideSelector selector,
                                            String[] stateNames) {
        super(offsetX, offsetY);
        this.selector = selector;
        this.stateNames = stateNames;

        // These positions match ModuleBlockSideSelector constructor
        rects = new int[][]{
                {offsetX + 42, offsetY + 42, 16, 16}, // 0 bottom
                {offsetX + 21, offsetY + 21, 16, 16}, // 1 top
                {offsetX + 21, offsetY +  0, 16, 16}, // 2 north
                {offsetX + 21, offsetY + 42, 16, 16}, // 3 south
                {offsetX +  0, offsetY + 21, 16, 16}, // 4 west
                {offsetX + 42, offsetY + 21, 16, 16}  // 5 east
        };
    }

    @Override
    public void renderToolTip(int guiOffsetX, int guiOffsetY,
                              int mouseX, int mouseY, float zLevel,
                              GuiContainer gui, FontRenderer font) {

        for (int side = 0; side < 6; side++) {
            int[] r = rects[side];

            int rx = r[0];
            int ry = r[1];
            int rw = r[2];
            int rh = r[3];

            if (mouseX >= rx && mouseX < rx + rw && mouseY >= ry && mouseY < ry + rh) {
                int state = selector.getStateForSide(side);
                String mode = (state >= 0 && state < stateNames.length)
                        ? stateNames[state]
                        : "Unknown";

                tooltip.clear();
                tooltip.add(dirName(side) + ": " + mode);

                this.drawTooltip(gui, tooltip, mouseX, mouseY, zLevel, font);
                return;
            }
        }
    }
}
