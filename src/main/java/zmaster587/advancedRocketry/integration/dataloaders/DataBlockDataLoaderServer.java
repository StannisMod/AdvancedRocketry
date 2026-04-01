package zmaster587.advancedRocketry.integration.dataloaders;

import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.api.DataStorage.DataType;

public class DataBlockDataLoaderServer extends DataBlockDataLoader {

    DataStorage storage;

    public DataBlockDataLoaderServer(DataStorage storage) {
        this.storage = storage;
    }

	@Override
	public DataType getDataType() {
        return storage.getDataType();
	}

	@Override
	public boolean isLocked() {
        return storage.isLocked();
	}

	@Override
	public int getDataAmount() {
        return storage.getData();
	}

	@Override
	public int getMaxData() {
        return storage.getMaxData();
	}
    
}
