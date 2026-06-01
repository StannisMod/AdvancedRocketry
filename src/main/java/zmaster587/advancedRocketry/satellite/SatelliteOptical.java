package zmaster587.advancedRocketry.satellite;

import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.libVulpes.LibVulpes;

public class SatelliteOptical extends SatelliteData {

    public SatelliteOptical() {
        super();
        data = new DataStorage(DataStorage.DataType.DISTANCE);
        data.lockDataType(DataType.DISTANCE);
    }

    @Override
    public String getName() {
        return LibVulpes.proxy.getLocalizedString("item.satellite.opticaltelescope");
    }

    @Override
    public double failureChance() {
        return 0;
    }
}
