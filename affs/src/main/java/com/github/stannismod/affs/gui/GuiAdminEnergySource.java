package com.github.stannismod.affs.gui;

import com.github.stannismod.affs.te.TileEntityAdminEnergySource;
import net.minecraft.client.resources.I18n;

public class GuiAdminEnergySource extends GuiAffsBase {

    private final TileEntityAdminEnergySource tile;

    public GuiAdminEnergySource(ContainerAdminEnergySource container, TileEntityAdminEnergySource tile) {
        super(container);
        this.tile = tile;
        this.xSize = 176;
        this.ySize = 82;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawFlatGrayBackground();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        drawTitle(I18n.format("tile.admin_energy_source.name"));
        drawStat("FE transferred/t", tile.getFeTransferredThisTick(), rowY(1));
        drawMuted("Infinite FE source", rowY(2));
    }
}
