package zmaster587.advancedRocketry.inventory.modules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.libVulpes.gui.CommonResources;
import zmaster587.libVulpes.inventory.TextureResources;
import zmaster587.libVulpes.inventory.modules.IButtonInventory;
import zmaster587.libVulpes.inventory.modules.ModuleButton;

import javax.annotation.Nonnull;

/**
 * Slot-like clickable button that renders ANY ItemStack (including non-block items) using RenderItem.
 * Drop-in replacement for ModuleSlotButton when the stack is not a Block item.
 * zmaster587.libVulpes.inventory.modules.ModuleSlotButton only works for Block items.
 * this class was created to allow displaying items such as batteries, ingots, etc.
 * if libvulpes ModuleSlotButton is updated to support non-block items, this class may be deprecated.
 * remove this class if libvulpes ModuleSlotButton is updated to support non-block items.
 */
public class ModuleItemSlotButton extends ModuleButton {

    private final ItemStack stack;

    public ModuleItemSlotButton(int offsetX, int offsetY, int buttonId, IButtonInventory tile,
                                @Nonnull ItemStack slotDisplay, String extraDisplay) {
        // IMPORTANT: pass "" as button label so nothing is drawn
        super(offsetX, offsetY, buttonId, "", tile,
                TextureResources.buttonNull,
                "",   // <- was: slotDisplay.getDisplayName() + "\n" + extraDisplay
                16, 16);

        this.stack = slotDisplay;

        // Set tooltip instead (hover-only)
        String tt = slotDisplay.isEmpty() ? "" : slotDisplay.getDisplayName();
        if (extraDisplay != null && !extraDisplay.isEmpty()) {
            tt = tt.isEmpty() ? extraDisplay : (tt + " \n" + extraDisplay);
        }
        this.setToolTipText(tt);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(CommonResources.genericBackground);
        gui.drawTexturedModalRect(x + this.offsetX - 1, y + this.offsetY - 1, 176, 0, 18, 18);

        if (stack.isEmpty()) return;

        int ix = x + this.offsetX;
        int iy = y + this.offsetY;

        Minecraft mc = Minecraft.getMinecraft();

        RenderHelper.enableGUIStandardItemLighting();
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, ix, iy);
        //mc.getRenderItem().renderItemOverlayIntoGUI(font, stack, ix, iy, null);
        RenderHelper.disableStandardItemLighting();
    }
}
