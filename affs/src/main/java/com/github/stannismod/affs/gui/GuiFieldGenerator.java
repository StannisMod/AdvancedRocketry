package com.github.stannismod.affs.gui;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.network.PacketSetFieldRadius;
import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

import java.io.IOException;
import java.util.Locale;

public class GuiFieldGenerator extends GuiAffsBase {

    private final TileEntityFieldGenerator tile;
    private GuiButton minusButton;
    private GuiButton plusButton;

    public GuiFieldGenerator(ContainerFieldGenerator container, TileEntityFieldGenerator tile) {
        super(container);
        this.tile = tile;
        this.xSize = 176;
        this.ySize = 176;
    }

    @Override
    public void initGui() {
        super.initGui();
        int left = this.guiLeft;
        int top = this.guiTop;

        minusButton = new GuiButton(0, left + 20, top + 56, 20, 20, "-");
        plusButton = new GuiButton(1, left + 136, top + 56, 20, 20, "+");
        buttonList.add(minusButton);
        buttonList.add(plusButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        super.actionPerformed(button);
        if (tile == null || tile.getWorld() == null) {
            return;
        }

        if (button.id == 0 || button.id == 1) {
            // The SETTING, not what a damaged emitter currently manages to project: stepping from the
            // shrunken radius would quietly re-declare the field smaller every time it was nudged.
            int radius = tile.getDeclaredRadius();
            radius += button.id == 0 ? -1 : 1;
            AdvancedForceFieldSystem.NETWORK.sendToServer(new PacketSetFieldRadius(tile.getPos(), radius));
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawFlatGrayBackground();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        drawTitle(I18n.format("tile.field_generator.name"));
        drawStat(I18n.format("gui.affs.access_code"), tile.getAccessCode(), rowY(0));
        // Projected / declared while damage is holding the field in, so the panel says which of the two
        // numbers the bill below is charging for; one number while they agree.
        drawStat(I18n.format("gui.affs.radius"), tile.getRadius() == tile.getDeclaredRadius()
                ? String.valueOf(tile.getDeclaredRadius())
                : tile.getRadius() + " / " + tile.getDeclaredRadius(), rowY(2));
        drawStat(I18n.format("gui.affs.shield_accumulator"), tile.getEnergyStored() + " / " + tile.getMaxEnergyStored(), rowY(5));
        drawStat(I18n.format("gui.affs.tier"), (tile.getTier() + 1) + " / 4", rowY(6));
        drawStat(I18n.format("gui.affs.impact_efficiency"), String.format(Locale.ROOT, "%.2fx", tile.getImpactEfficiencyMultiplier()), rowY(7));
        drawStat(I18n.format("gui.affs.field_powered"), tile.isFieldPowered() ? I18n.format("options.on") : I18n.format("options.off"), rowY(8));
        drawStat(I18n.format("gui.affs.cycle_cost"), tile.getShieldCycleCost(), rowY(9));
        drawStat(I18n.format("gui.affs.drain_per_tick"), tile.getShieldDrainThisTick(), rowY(10));
        drawStat("Shield received/t", tile.getShieldReceivedThisTick(), rowY(11));
        drawStat("Shield consumed/t", tile.getShieldConsumedThisTick(), rowY(12));
    }
}
