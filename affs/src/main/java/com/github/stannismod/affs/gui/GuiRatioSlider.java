package com.github.stannismod.affs.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;

import java.util.Locale;

public class GuiRatioSlider extends GuiButton {

    public interface ValueChanged {
        void onValueChanged(double value);
    }

    private static final int KNOB_WIDTH = 8;

    private final String leftLabel;
    private final String rightLabel;
    private final ValueChanged callback;
    private double value;
    private boolean dragging;

    public GuiRatioSlider(int buttonId, int x, int y, int width, int height, double initialValue, String leftLabel, String rightLabel, ValueChanged callback) {
        super(buttonId, x, y, width, height, "");
        this.leftLabel = leftLabel;
        this.rightLabel = rightLabel;
        this.callback = callback;
        setValue(initialValue, false);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!visible) {
            return;
        }

        hovered = mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        GlStateManager.disableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        drawRect(x, y + height / 2 - 2, x + width, y + height / 2 + 2, 0xFF4A4A4A);
        int knobX = x + 2 + (int) Math.round((width - KNOB_WIDTH - 4) * value);
        drawRect(knobX, y, knobX + KNOB_WIDTH, y + height, dragging ? 0xFFF5D95A : 0xFFD0D0D0);
        drawRect(knobX + 1, y + 1, knobX + KNOB_WIDTH - 1, y + height - 1, 0xFFAAAAAA);
        GlStateManager.enableTexture2D();
        drawCenteredString(mc.fontRenderer, displayString, x + width / 2, y + height / 2 - 4, 0x202020);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        boolean pressed = super.mousePressed(mc, mouseX, mouseY);
        if (pressed) {
            dragging = true;
            updateValue(mouseX);
        }
        return pressed;
    }

    public void mouseDragged(Minecraft mc, int mouseX) {
        if (dragging) {
            updateValue(mouseX);
        }
    }

    public void mouseReleased(int mouseX, int mouseY) {
        dragging = false;
    }

    public double getValue() {
        return value;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setValue(double value) {
        setValue(value, true);
    }

    public void setValueSilently(double value) {
        setValue(value, false);
    }

    private void updateValue(int mouseX) {
        double relative = (mouseX - (double) x - 2.0D) / Math.max(1.0D, width - KNOB_WIDTH - 4.0D);
        setValue(relative, true);
    }

    private void setValue(double value, boolean notify) {
        double clamped = value < 0.0D ? 0.0D : Math.min(1.0D, value);
        if (Math.abs(this.value - clamped) < 1.0E-6D) {
            return;
        }
        this.value = clamped;
        this.displayString = String.format(Locale.ROOT, "%s %d%% / %s %d%%", leftLabel, Math.round((1.0D - clamped) * 100.0D), rightLabel, Math.round(clamped * 100.0D));
        if (notify && callback != null) {
            callback.onValueChanged(clamped);
        }
    }
}
