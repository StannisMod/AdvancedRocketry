package zmaster587.advancedRocketry.integration.theoneprobe;

import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoProvider;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.integration.dataloaders.AbstractDataContext;
import zmaster587.advancedRocketry.integration.dataloaders.DataBlockDataLoader;
import zmaster587.advancedRocketry.integration.dataloaders.DataBlockDataLoaderServer;
import zmaster587.advancedRocketry.integration.dataloaders.WirelessTransceiverDataLoader;
import zmaster587.advancedRocketry.integration.dataloaders.WirelessTransceiverDataLoaderServer;
import zmaster587.advancedRocketry.tile.TileWirelessTransceiver;

public class DataBlockProbeProvider implements IProbeInfoProvider {

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

        AbstractDataContext topContext = new TOPDataContext(probeInfo);
        boolean showLockedLine = true;
        if (tile instanceof TileWirelessTransceiver) {
            WirelessTransceiverDataLoader loader = new WirelessTransceiverDataLoaderServer((TileWirelessTransceiver) tile);
            loader.addWirelessDataInfo(topContext);
            showLockedLine = false;
        }

        DataStorage storage = DataBlockDataLoader.getDataStorage(tile);
        if (storage != null) {
            DataBlockDataLoader loader = new DataBlockDataLoaderServer(storage);
            loader.addCommonDataInfo(topContext, showLockedLine);
        }
    }
}