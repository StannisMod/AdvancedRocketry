package zmaster587.advancedRocketry.inventory.modules;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.libVulpes.gui.CommonResources;
import zmaster587.libVulpes.inventory.modules.ModuleBase;

import java.util.LinkedList;
import java.util.List;

/**
 * Minimal, read-only data bar for the wireless transceiver's internal buffer.
 * - No buttons
 * - No slots
 * - Server-authoritative: syncs amount, max, and type via tiny window properties.
 */
public class ModuleWirelessBufferBar extends ModuleBase {

    // Reuse same visuals as ModuleData so it looks consistent
    static final int BAR_Y_SIZE = 38;
    static final int BAR_X_SIZE = 6;
    static final int TEX_OFFSET_X = 0;
    static final int TEX_OFFSET_Y = 215;

    private final DataStorage data;     // points to your uiBuffer
    private int prevAmount = -1;
    private int prevMax = -1;
    private int prevTypeOrdinal = -1;

    public ModuleWirelessBufferBar(int offsetX, int offsetY, DataStorage data) {
        super(offsetX, offsetY);
        this.data = data;
        this.sizeX = 10; // hitbox-ish; not used for layout
        this.sizeY = BAR_Y_SIZE + 12;
    }

    @Override
    public int numberOfChangesToSend() {
        // amount, max, type
        return 3;
    }

    @Override
    public boolean needsUpdate(int localId) {
        switch (localId) {
            case 0: return data.getData() != prevAmount;
            case 1: return data.getMaxData() != prevMax;
            case 2: return data.getDataType().ordinal() != prevTypeOrdinal;
            default: return false;
        }
    }

    @Override
    protected void updatePreviousState(int localId) {
        if (localId == 0) prevAmount = data.getData();
        else if (localId == 1) prevMax = data.getMaxData();
        else if (localId == 2) prevTypeOrdinal = data.getDataType().ordinal();
    }

    @Override
    public void sendChanges(net.minecraft.inventory.Container container,
                            net.minecraft.inventory.IContainerListener crafter,
                            int variableId, int localId) {
        int v;
        if (localId == 0) v = data.getData();
        else if (localId == 1) v = data.getMaxData();
        else /* localId == 2 */ v = data.getDataType().ordinal();
        crafter.sendWindowProperty(container, variableId, v);
    }

    @Override
    public void onChangeRecieved(int slot, int value) {
        if (slot == 0) {
            // amount (type set below or left unchanged)
            data.setData(value, DataType.UNDEFINED);
        } else if (slot == 1) {
            data.setMaxData(value);
        } else if (slot == 2) {
            DataType t = DataType.values()[Math.max(0, Math.min(DataType.values().length - 1, value))];
            data.setDataType(t);
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderForeground(int guiOffsetX, int guiOffsetY, int mouseX, int mouseY, float zLevel,
                                GuiContainer gui, FontRenderer font) {
        int relX = mouseX - offsetX;
        int relY = mouseY - offsetY;
        if (relX >= 0 && relX < BAR_X_SIZE && relY >= 0 && relY < BAR_Y_SIZE) {
            List<String> tt = new LinkedList<>();
            // "Data"
            tt.add(net.minecraft.client.resources.I18n.format(
                "msg.tooltip.data") + " " + data.getData() + " / " + data.getMaxData());

            // "Type: %s" with translated type
            String typeName = net.minecraft.client.resources.I18n.format(data.getDataType().toString());
            tt.add(net.minecraft.client.resources.I18n.format("msg.wirelessTransciever.type", typeName));


            this.drawTooltip(gui, tt, mouseX, mouseY, zLevel, font);
        }
    }


    @SideOnly(Side.CLIENT)
    @Override
    public void renderBackground(GuiContainer gui, int x, int y, int mouseX, int mouseY, FontRenderer font) {
        // Bind the correct texture (same sheet as ModuleData)
        gui.mc.getTextureManager().bindTexture(CommonResources.genericBackground);

        // Draw only the bar frame (8x40 at UV 176,18)
        gui.drawTexturedModalRect(offsetX + x, offsetY + y, 176, 18, 8, 40);

        // Compute fill amount
        int max = Math.max(1, data.getMaxData());
        float percent = Math.min(1f, Math.max(0f, data.getData() / (float) max));
        int filled = (int) (percent * BAR_Y_SIZE);

        // Draw the green fill (6 x filled) from UV (0, 215 + (BAR_Y_SIZE - filled))
        //    Fill grows upward inside the frame
        if (filled > 0) {
            gui.drawTexturedModalRect(
                offsetX + x + 1,
                offsetY + y + 1 + (BAR_Y_SIZE - filled),
                0,
                215 + (BAR_Y_SIZE - filled),
                BAR_X_SIZE,
                filled
            );
        }
    }
}
