package zmaster587.advancedRocketry.satellite;

import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.libVulpes.LibVulpes;

public class SatelliteMassScanner extends SatelliteData {

    public SatelliteMassScanner() {
        super();
        data = new DataStorage(DataStorage.DataType.MASS);
        data.lockDataType(DataStorage.DataType.MASS);
    }

    @Override
    public String getName() {
        return LibVulpes.proxy.getLocalizedString("item.satellite.massscanner");
    }

    @Override
    public double failureChance() {
        return 0;
    }

}
