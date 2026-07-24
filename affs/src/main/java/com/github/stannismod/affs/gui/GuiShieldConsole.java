package com.github.stannismod.affs.gui;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.network.PacketOpenGui;
import com.github.stannismod.affs.network.PacketSetShieldResistanceBias;
import com.github.stannismod.affs.network.PacketSyncCodeValue;
import com.github.stannismod.affs.te.TileEntityShieldConsole;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

public class GuiShieldConsole extends GuiAffsBase {

    private static final int SAVE_FLASH_DURATION = 6;

    private final TileEntityShieldConsole tile;
    private GuiButton saveCodeButton;
    private GuiButton mapButton;
    private GuiRatioSlider resistanceSlider;
    private GuiTextField codeField;
    private String syncedCode = "";
    private int saveFlashTicks = 0;

    public GuiShieldConsole(ContainerShieldConsole container, TileEntityShieldConsole tile) {
        super(container);
        this.tile = tile;
        this.xSize = 176;
        this.ySize = 220;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        int left = this.guiLeft;
        int top = this.guiTop;

        saveCodeButton = new GuiButton(0, left + 58, top + 52, 60, 20, I18n.format("gui.affs.save"));
        mapButton = new GuiButton(1, left + 8, top + 76, 160, 20, I18n.format("gui.affs.network_map"));
        resistanceSlider = new GuiRatioSlider(2, left + 8, top + 110, 160, 20, tile.getShieldEnergyResistanceBias(), I18n.format("gui.affs.energy_resistance"), I18n.format("gui.affs.physical_resistance"), value ->
                AdvancedForceFieldSystem.NETWORK.sendToServer(PacketSetShieldResistanceBias.forConsole(tile.getPos(), value))
        );
        codeField = new GuiTextField(1, fontRenderer, left + 8, top + 30, 160, 20);
        codeField.setMaxStringLength(32);
        syncedCode = tile.getNetworkCode();
        codeField.setText(syncedCode);
        buttonList.add(saveCodeButton);
        buttonList.add(mapButton);
        buttonList.add(resistanceSlider);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        super.actionPerformed(button);
        if (tile == null || tile.getWorld() == null) {
            return;
        }

        if (button.id == 0) {
            AdvancedForceFieldSystem.NETWORK.sendToServer(PacketSyncCodeValue.forNetworkNode(tile.getPos(), codeField.getText()));
            saveFlashTicks = SAVE_FLASH_DURATION;
        } else if (button.id == 1) {
            AdvancedForceFieldSystem.NETWORK.sendToServer(PacketOpenGui.forBlock(AdvancedForceFieldSystem.GUI_NETWORK_MAP, tile.getPos()));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (codeField.isFocused() && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
            if (saveCodeButton != null) {
                saveCodeButton.playPressSound(mc.getSoundHandler());
                actionPerformed(saveCodeButton);
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
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (clickedMouseButton == 0 && resistanceSlider != null) {
            resistanceSlider.mouseDragged(mc, mouseX);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (resistanceSlider != null) {
            resistanceSlider.mouseReleased(mouseX, mouseY);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        codeField.updateCursorCounter();
        if (saveFlashTicks > 0) {
            saveFlashTicks--;
        }
        String networkCode = tile.getNetworkCode();
        if (!networkCode.equals(syncedCode)) {
            syncedCode = networkCode;
            if (!codeField.isFocused()) {
                codeField.setText(networkCode);
            }
        }
        if (resistanceSlider != null && !resistanceSlider.isDragging()) {
            double bias = tile.getShieldEnergyResistanceBias();
            if (Math.abs(resistanceSlider.getValue() - bias) > 1.0E-4D) {
                resistanceSlider.setValueSilently(bias);
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (saveFlashTicks > 0 && saveCodeButton != null) {
            int alpha = 40 + (saveFlashTicks * 25);
            int color = (Math.min(255, alpha) << 24) | 0xF0FF80;
            drawRect(saveCodeButton.x, saveCodeButton.y, saveCodeButton.x + saveCodeButton.width, saveCodeButton.y + saveCodeButton.height, color);
            drawRect(saveCodeButton.x, saveCodeButton.y, saveCodeButton.x + saveCodeButton.width, saveCodeButton.y + 1, 0xB0FFFFFF);
            drawRect(saveCodeButton.x, saveCodeButton.y + saveCodeButton.height - 1, saveCodeButton.x + saveCodeButton.width, saveCodeButton.y + saveCodeButton.height, 0xB0FFFFFF);
            drawRect(saveCodeButton.x, saveCodeButton.y, saveCodeButton.x + 1, saveCodeButton.y + saveCodeButton.height, 0xB0FFFFFF);
            drawRect(saveCodeButton.x + saveCodeButton.width - 1, saveCodeButton.y, saveCodeButton.x + saveCodeButton.width, saveCodeButton.y + saveCodeButton.height, 0xB0FFFFFF);
        }
        codeField.drawTextBox();
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawFlatGrayBackground();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        drawTitle(I18n.format("tile.shield_console.name"));
        drawLabel(I18n.format("gui.affs.code"), rowY(0));
        drawStat(I18n.format("gui.affs.network_status"), tile.getNetworkStatusText(), rowY(4));
        drawStat(I18n.format("gui.affs.network_root"), tile.getRootString(), rowY(5));
        drawStat(I18n.format("gui.affs.shield_counts"), tile.getCableCount() + " / " + tile.getGeneratorCount() + " / " + tile.getInjectorCount(), rowY(6));
        drawStat(I18n.format("gui.affs.network_generation"), tile.getGenerationPerTick(), rowY(7));
        drawStat(I18n.format("gui.affs.network_consumption"), tile.getConsumptionPerTick(), rowY(8));
        drawStat(I18n.format("gui.affs.network_flow"), tile.getDeliveredFlow() + " / " + tile.getSourceAvailable() + " / " + tile.getSinkRequested(), rowY(9));
        drawStat(I18n.format("gui.affs.network_capacity"), String.valueOf(tile.getCableCapacity()), rowY(10));
        drawStat(I18n.format("gui.affs.network_saturated"), String.valueOf(tile.getSaturatedCables()), rowY(11));
        drawStat(I18n.format("gui.affs.network_bottleneck"), tile.getBottleneckUtilizationText(), rowY(12));
        drawStat(I18n.format("gui.affs.shield_balance"), tile.getShieldEnergyResistanceText(), rowY(13));
        drawMuted(I18n.format("gui.affs.network_hint"), rowY(14));
    }
}
