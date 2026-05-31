package zmaster587.advancedRocketry.integration.dataloaders;

import zmaster587.advancedRocketry.tile.TileWirelessTransceiver;

public class WirelessTransceiverDataLoaderServer extends WirelessTransceiverDataLoader {

    DataBlockDataLoaderServer data;
    TileWirelessTransceiver transceiver;

    public WirelessTransceiverDataLoaderServer(TileWirelessTransceiver transceiver) {
        this.transceiver = transceiver;
    }

	@Override
	public boolean isLinked() {
		return transceiver.isLinkedWireless();
	}

	@Override
	public boolean isExtracting() {
		return transceiver.isExtractModeWireless();
	}

	@Override
	public int getNetworkId() {
		return transceiver.getWirelessNetworkId();
	}
    
}
