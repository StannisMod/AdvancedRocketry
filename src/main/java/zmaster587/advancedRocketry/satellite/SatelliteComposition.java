package zmaster587.advancedRocketry.satellite;

import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.libVulpes.LibVulpes;

public class SatelliteComposition extends SatelliteData {

    public SatelliteComposition() {
        super();
        data = new DataStorage(DataStorage.DataType.COMPOSITION);
        data.lockDataType(DataStorage.DataType.COMPOSITION);
    }

    @Override
    public String getName() {
        return LibVulpes.proxy.getLocalizedString("item.satellite.composition");
    }

    @Override
    public double failureChance() {
        return 0;
    }
}
