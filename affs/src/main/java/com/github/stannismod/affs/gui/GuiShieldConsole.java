package com.github.stannismod.affs.gui;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.network.PacketOpenGui;
import com.github.stannismod.affs.network.PacketSetShieldResistanceBias;
import com.github.stannismod.affs.te.TileEntityShieldConsole;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

import java.io.IOException;

public class GuiShieldConsole extends GuiAffsBase {

    private final TileEntityShieldConsole tile;
    private GuiButton mapButton;
    private GuiRatioSlider resistanceSlider;

    public GuiShieldConsole(ContainerShieldConsole container, TileEntityShieldConsole tile) {
        super(container);
        this.tile = tile;
        this.xSize = 176;
        this.ySize = 220;
    }

    @Override
    public void initGui() {
        super.initGui();
        int left = this.guiLeft;
        int top = this.guiTop;

        mapButton = new GuiButton(1, left + 8, top + 30, 160, 20, I18n.format("gui.affs.network_map"));
        resistanceSlider = new GuiRatioSlider(2, left + 8, top + 76, 160, 20, tile.getShieldEnergyResistanceBias(), I18n.format("gui.affs.energy_resistance"), I18n.format("gui.affs.physical_resistance"), value ->
                AdvancedForceFieldSystem.NETWORK.sendToServer(PacketSetShieldResistanceBias.forConsole(tile.getPos(), value))
        );
        buttonList.add(mapButton);
        buttonList.add(resistanceSlider);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        super.actionPerformed(button);
        if (tile == null || tile.getWorld() == null) {
            return;
        }

        if (button.id == 1) {
            AdvancedForceFieldSystem.NETWORK.sendToServer(PacketOpenGui.forBlock(AdvancedForceFieldSystem.GUI_NETWORK_MAP, tile.getPos()));
        }
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
        if (resistanceSlider != null && !resistanceSlider.isDragging()) {
            double bias = tile.getShieldEnergyResistanceBias();
            if (Math.abs(resistanceSlider.getValue() - bias) > 1.0E-4D) {
                resistanceSlider.setValueSilently(bias);
            }
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawFlatGrayBackground();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        drawTitle(I18n.format("tile.shield_console.name"));
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
