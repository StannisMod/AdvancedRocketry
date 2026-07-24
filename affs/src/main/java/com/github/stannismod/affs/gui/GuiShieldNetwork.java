package com.github.stannismod.affs.gui;

import com.github.stannismod.affs.te.TileEntityShieldCable;
import net.minecraft.client.resources.I18n;

public class GuiShieldNetwork extends GuiAffsBase {

    private final TileEntityShieldCable tile;

    public GuiShieldNetwork(ContainerShieldNetwork container, TileEntityShieldCable tile) {
        super(container);
        this.tile = tile;
        this.xSize = 196;
        this.ySize = 146;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawFlatGrayBackground();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        drawTitle(I18n.format("tile.shield_cable.name"));
        drawStat(I18n.format("gui.affs.network_status"), tile.getNetworkStatusText(), rowY(1));
        drawStat(I18n.format("gui.affs.network_root"), tile.getComponentAnchorString(), rowY(2));
        drawStat(I18n.format("gui.affs.network_counts"), tile.getComponentCableCount() + " / " + tile.getComponentSourceCount() + " / " + tile.getComponentSinkCount(), rowY(3));
        drawStat(I18n.format("gui.affs.network_flow"), tile.getComponentDeliveredFlow() + " / " + tile.getComponentSourceAvailable() + " / " + tile.getComponentSinkRequested(), rowY(4));
        drawStat(I18n.format("gui.affs.network_capacity"), tile.getComponentCableCapacity(), rowY(5));
        drawStat(I18n.format("gui.affs.network_saturated"), tile.getComponentSaturatedCables(), rowY(6));
        drawStat(I18n.format("gui.affs.network_bottleneck"), tile.getBottleneckCableString(), rowY(7));
        drawStat(I18n.format("gui.affs.network_util"), tile.getBottleneckUtilizationText(), rowY(8));
        drawMuted(I18n.format("gui.affs.network_hint"), rowY(9));
    }
}
