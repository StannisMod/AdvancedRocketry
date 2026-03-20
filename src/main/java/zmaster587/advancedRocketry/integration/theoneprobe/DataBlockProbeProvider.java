package zmaster587.advancedRocketry.integration.theoneprobe;

import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.NumberFormat;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
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

        BlockPos pos = hitData.getPos();
        TileEntity tile = world.getTileEntity(pos);

        if (tile instanceof TileDataBus) {
            addDataStorageInfo(probeInfo, ((TileDataBus) tile).getDataObject());
            return;
        }

        if (tile instanceof TileSatelliteTerminal) {
            addDataStorageInfo(probeInfo, ((TileSatelliteTerminal) tile).getDataObject());
            return;
        }

        if (tile instanceof TileWirelessTransciever) {
            addWirelessDataInfo(probeInfo, (TileWirelessTransciever) tile);
        }
    }

    private static void addWirelessDataInfo(IProbeInfo probeInfo, TileWirelessTransciever tile) {
        DataStorage storage = tile.getUiBufferObject();
        if (storage == null) {
            return;
        }



        probeInfo.text(
                tr("msg.top.advancedrocketry.data.mode")
                        + ": "
                        + tr(tile.isExtractModeWireless()
                        ? "msg.top.advancedrocketry.data.mode.extract"
                        : "msg.top.advancedrocketry.data.mode.insert")
        );

        probeInfo.text(
                tr("msg.top.advancedrocketry.data.link")
                        + ": "
                        + tr(tile.isLinkedWireless()
                        ? "msg.top.advancedrocketry.data.link.linked"
                        : "msg.top.advancedrocketry.data.link.unlinked")
        );

        DataType type = storage.getDataType();
        probeInfo.text(
                tr("msg.top.advancedrocketry.data.type")
                        + ": "
                        + tr(type.toString())
        );
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
                        .suffix(" Data")
        );
    }

    private static void addDataStorageInfo(IProbeInfo probeInfo, TileDataBus bus) {
        addDataStorageInfo(probeInfo, bus.getDataObject());
    }

    private static void addDataStorageInfo(IProbeInfo probeInfo, TileSatelliteTerminal terminal) {
        addDataStorageInfo(probeInfo, terminal.getDataObject());
    }

    private static void addDataStorageInfo(IProbeInfo probeInfo, DataStorage storage) {
        if (storage == null) {
            return;
        }

        DataType type = storage.getDataType();
        probeInfo.text(
                tr("msg.top.advancedrocketry.data.type")
                        + ": "
                        + tr(type.toString())
        );

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
                        .suffix(" Data")
        );

        if (storage.isLocked()) {
            probeInfo.text(tr("msg.top.advancedrocketry.data.locked"));
        }
    }

    private static String tr(String key) {
        return LibVulpes.proxy.getLocalizedString(key);
    }
}