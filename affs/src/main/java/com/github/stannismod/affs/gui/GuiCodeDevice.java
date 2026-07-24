package com.github.stannismod.affs.gui;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.network.PacketSyncCodeValue;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

public class GuiCodeDevice extends GuiAffsBase {

    private static final int SAVE_FLASH_DURATION = 6;

    private final ContainerCodeDevice container;
    private GuiTextField codeField;
    private GuiButton saveButton;
    private int saveFlashTicks = 0;

    public GuiCodeDevice(ContainerCodeDevice container) {
        super(container);
        this.container = container;
        this.xSize = 176;
        this.ySize = 90;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        codeField = new GuiTextField(0, fontRenderer, guiLeft + 8, guiTop + 28, 160, 20);
        codeField.setMaxStringLength(32);
        codeField.setText(container.getInitialCode());
        saveButton = new GuiButton(1, guiLeft + 58, guiTop + 56, 60, 20, I18n.format("gui.affs.save"));
        buttonList.add(saveButton);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        super.actionPerformed(button);
        if (button.id == 1) {
            AdvancedForceFieldSystem.NETWORK.sendToServer(PacketSyncCodeValue.forItem(container.getHand(), codeField.getText()));
            saveFlashTicks = SAVE_FLASH_DURATION;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (codeField.isFocused() && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
            if (saveButton != null) {
                saveButton.playPressSound(mc.getSoundHandler());
                actionPerformed(saveButton);
            }
            return;
        }
        if (codeField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        codeField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        codeField.updateCursorCounter();
        if (saveFlashTicks > 0) {
            saveFlashTicks--;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (saveFlashTicks > 0 && saveButton != null) {
            int alpha = 40 + (saveFlashTicks * 25);
            int color = (Math.min(255, alpha) << 24) | 0xF0FF80;
            drawRect(saveButton.x, saveButton.y, saveButton.x + saveButton.width, saveButton.y + saveButton.height, color);
            drawRect(saveButton.x, saveButton.y, saveButton.x + saveButton.width, saveButton.y + 1, 0xB0FFFFFF);
            drawRect(saveButton.x, saveButton.y + saveButton.height - 1, saveButton.x + saveButton.width, saveButton.y + saveButton.height, 0xB0FFFFFF);
            drawRect(saveButton.x, saveButton.y, saveButton.x + 1, saveButton.y + saveButton.height, 0xB0FFFFFF);
            drawRect(saveButton.x + saveButton.width - 1, saveButton.y, saveButton.x + saveButton.width, saveButton.y + saveButton.height, 0xB0FFFFFF);
        }
        codeField.drawTextBox();
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawFlatGrayBackground();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        drawTitle(I18n.format("item.code_device.name"));
        drawLabel(I18n.format("gui.affs.code"), rowY(0));
    }
}
