package zmaster587.advancedRocketry.integration.theoneprobe;

import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.NumberFormat;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.advancedRocketry.tile.cables.TileWirelessTransciever;
import zmaster587.advancedRocketry.tile.hatch.TileDataBus;
import zmaster587.advancedRocketry.tile.satellite.TileSatelliteTerminal;
import zmaster587.libVulpes.LibVulpes;

public class DataBlockProbeProvider implements IProbeInfoProvider {

    private static final int DATA_BORDER_COLOR = 0xFF555555;
    private static final int DATA_BACKGROUND_COLOR = 0xFF000000;
    private static final int DATA_FILLED_COLOR = 0xFF1FA51F;
    private static final int DATA_ALT_FILLED_COLOR = 0xFF137013;

    @Override
    public String getID() {
        return "advancedrocketry:data_blocks";
    }

    @Override
    public void addProbeInfo(ProbeMode mode, IProbeInfo probeInfo, EntityPlayer player, World world,
                             IBlockState blockState, IProbeHitData hitData) {
        if (mode != ProbeMode.EXTENDED) {
            return;
        }

        TileEntity tile = world.getTileEntity(hitData.getPos());
        if (tile == null) {
            return;
        }

        if (tile instanceof TileWirelessTransciever) {
            addWirelessDataInfo(probeInfo, (TileWirelessTransciever) tile);
            return;
        }

        DataStorage storage = getDataStorage(tile);
        if (storage != null) {
            addCommonDataInfo(probeInfo, storage, true);
        }
    }

    private static DataStorage getDataStorage(TileEntity tile) {
        if (tile instanceof TileDataBus) {
            return ((TileDataBus) tile).getDataObject();
        }

        if (tile instanceof TileSatelliteTerminal) {
            return ((TileSatelliteTerminal) tile).getDataObject();
        }

        return null;
    }
    private static String getModeTextPadded(boolean extractMode) {
        String mode = tr(extractMode
                ? "msg.top.advancedrocketry.data.mode.extract"
                : "msg.top.advancedrocketry.data.mode.insert");

        if (!extractMode) {
            mode += " ";
        }

        return mode;
    }
    private static String getLinkStatusBadge(boolean linked) {
        return (linked
                ? net.minecraft.util.text.TextFormatting.GREEN
                : net.minecraft.util.text.TextFormatting.RED)
                + tr(linked
                ? "msg.top.advancedrocketry.data.link.linked"
                : "msg.top.advancedrocketry.data.link.unlinked");
    }

    private static void addWirelessDataInfo(IProbeInfo probeInfo, TileWirelessTransciever tile) {
        DataStorage storage = tile.getUiBufferObject();
        if (storage == null) {
            return;
        }

        probeInfo.text(
                tr("msg.top.advancedrocketry.data.mode")
                        + ": "
                        + getModeTextPadded(tile.isExtractModeWireless())
                        + "    "
                        + getLinkStatusBadge(tile.isLinkedWireless())
        );

        addCommonDataInfo(probeInfo, storage, false);
    }

    private static void addCommonDataInfo(IProbeInfo probeInfo, DataStorage storage, boolean showLockedLine) {
        if (storage == null) {
            return;
        }

        probeInfo.text(
                tr("msg.top.advancedrocketry.data.type")
                        + ": "
                        + getDataTypeText(storage.getDataType())
        );

        addDataBar(probeInfo, storage);

        if (showLockedLine && storage.isLocked()) {
            probeInfo.text(tr("msg.top.advancedrocketry.data.locked"));
        }
    }

    private static void addDataBar(IProbeInfo probeInfo, DataStorage storage) {
        int current = storage.getData();
        int max = Math.max(1, storage.getMaxData());

        probeInfo.progress(
                current,
                max,
                probeInfo.defaultProgressStyle()
                        .borderColor(DATA_BORDER_COLOR)
                        .backgroundColor(DATA_BACKGROUND_COLOR)
                        .filledColor(DATA_FILLED_COLOR)
                        .alternateFilledColor(DATA_ALT_FILLED_COLOR)
                        .height(12)
                        .width(100)
                        .showText(true)
                        .numberFormat(NumberFormat.COMMAS)
                        .suffix(" " + tr("msg.top.advancedrocketry.data.label"))
        );
    }

    private static String getDataTypeText(DataType type) {
        if (type == null) {
            return tr("data.undefined.name");
        }

        return tr(type.toString());
    }

    private static String tr(String key) {
        return LibVulpes.proxy.getLocalizedString(key);
    }
}