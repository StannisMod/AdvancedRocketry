package zmaster587.advancedRocketry.integration.dataloaders;

import net.minecraft.tileentity.TileEntity;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.advancedRocketry.tile.TileWirelessTransceiver;
import zmaster587.advancedRocketry.tile.hatch.TileDataBus;
import zmaster587.advancedRocketry.tile.satellite.TileSatelliteTerminal;

public abstract class DataBlockDataLoader {
    public abstract DataType getDataType();
    public abstract boolean isLocked();
    public abstract int getDataAmount();
    public abstract int getMaxData();

    private static final int DATA_BORDER_COLOR = 0xFF555555;
    private static final int DATA_BACKGROUND_COLOR = 0xFF000000;
    private static final int DATA_FILLED_COLOR = 0xFF1FA51F;
    private static final int DATA_ALT_FILLED_COLOR = 0xFF137013;

    public static DataStorage getDataStorage(TileEntity tile) {
        if (tile instanceof TileWirelessTransceiver) {
            return ((TileWirelessTransceiver) tile).getUiBufferObject();
        }

        if (tile instanceof TileDataBus) {
            return ((TileDataBus) tile).getDataObject();
        }

        if (tile instanceof TileSatelliteTerminal) {
            return ((TileSatelliteTerminal) tile).getDataObject();
        }

        return null;
    }

    public void addCommonDataInfo(AbstractDataContext context, boolean showLockedLine) {
        context.addMessage(
                context.translate("msg.top.advancedrocketry.data.type")
                        + ": "
                        + getDataTypeText(context, getDataType())
        );

        addDataBar(context);

        if (showLockedLine && isLocked()) {
            context.addMessage(context.translate("msg.top.advancedrocketry.data.locked"));
        }
    }

    private void addDataBar(AbstractDataContext context) {
        int current = getDataAmount();
        int max = Math.max(1, getMaxData());
        context.addProgressBar(null, current, max,
            DATA_BORDER_COLOR, DATA_BACKGROUND_COLOR, DATA_FILLED_COLOR, DATA_ALT_FILLED_COLOR,
            "Data");
    }

    private String getDataTypeText(AbstractDataContext context, DataType type) {
        if (type == null) {
            return context.translate("data.undefined.name");
        }

        return context.translate(type.toString());
    }
}
