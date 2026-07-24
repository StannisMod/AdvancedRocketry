package com.github.stannismod.affs.gui;

import com.github.stannismod.affs.te.TileEntityShieldGenerator;
import net.minecraft.client.resources.I18n;

public class GuiShieldGenerator extends GuiAffsBase {

    private final TileEntityShieldGenerator tile;

    public GuiShieldGenerator(ContainerShieldGenerator container, TileEntityShieldGenerator tile) {
        super(container);
        this.tile = tile;
        this.xSize = 176;
        this.ySize = 116;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawFlatGrayBackground();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        drawTitle(I18n.format("tile.shield_generator.name"));
        drawStat(I18n.format("gui.affs.fe_buffer"), tile.getFeStored() + " / " + tile.getMaxFeStored(), rowY(1));
        drawStat(I18n.format("gui.affs.shield_buffer"), tile.getShieldStored() + " / " + tile.getMaxShieldStored(), rowY(2));
        drawStat(I18n.format("gui.affs.fe_input") + "/t", tile.getFeReceivedThisTick(), rowY(4));
        drawStat(I18n.format("gui.affs.fe_to_shield") + "/t", tile.getFeConsumedThisTick(), rowY(5));
        drawStat(I18n.format("gui.affs.shield_produced") + "/t", tile.getShieldProducedThisTick(), rowY(6));
        drawStat(I18n.format("gui.affs.shield_extracted") + "/t", tile.getShieldExtractedThisTick(), rowY(7));
    }
}
