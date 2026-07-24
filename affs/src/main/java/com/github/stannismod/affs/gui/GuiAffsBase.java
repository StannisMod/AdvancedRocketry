package com.github.stannismod.affs.gui;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;

public abstract class GuiAffsBase extends GuiContainer {

    protected static final int CONTENT_LEFT = 8;
    protected static final int TITLE_Y = 8;
    protected static final int FIRST_ROW_Y = 24;
    protected static final int ROW_HEIGHT = 12;
    protected static final int TITLE_COLOR = 0x202020;
    protected static final int LABEL_COLOR = 0x404040;
    protected static final int VALUE_COLOR = 0x202020;
    protected static final int MUTED_COLOR = 0x606060;

    protected GuiAffsBase(Container inventorySlotsIn) {
        super(inventorySlotsIn);
    }

    @Override
    public void drawDefaultBackground() {
        drawRect(0, 0, width, height, 0xFF7A7A7A);
    }

    protected void drawFlatGrayBackground() {
        drawRect(guiLeft, guiTop, guiLeft + xSize, guiTop + ySize, 0xFF8B8B8B);
        drawRect(guiLeft + 1, guiTop + 1, guiLeft + xSize - 1, guiTop + ySize - 1, 0xFFB0B0B0);
        drawRect(guiLeft + 2, guiTop + 2, guiLeft + xSize - 2, guiTop + ySize - 2, 0xFF5A5A5A);
    }

    protected int rowY(int row) {
        return FIRST_ROW_Y + row * ROW_HEIGHT;
    }

    protected void drawTitle(String text) {
        fontRenderer.drawString(text, CONTENT_LEFT, TITLE_Y, TITLE_COLOR);
    }

    protected void drawLabel(String text, int y) {
        fontRenderer.drawString(text, CONTENT_LEFT, y, LABEL_COLOR);
    }

    protected void drawValue(String text, int y) {
        fontRenderer.drawString(text, CONTENT_LEFT, y, VALUE_COLOR);
    }

    protected void drawMuted(String text, int y) {
        fontRenderer.drawString(text, CONTENT_LEFT, y, MUTED_COLOR);
    }

    protected void drawStat(String label, String value, int y) {
        fontRenderer.drawString(label + ": " + value, CONTENT_LEFT, y, VALUE_COLOR);
    }

    protected void drawStat(String label, int value, int y) {
        drawStat(label, Integer.toString(value), y);
    }
}
