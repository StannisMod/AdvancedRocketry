package zmaster587.advancedRocketry.tile.hatch;

import net.minecraft.nbt.NBTTagCompound;
import zmaster587.advancedRocketry.api.ARConfiguration;

public class TileDataBusBig extends TileDataBus {

    private static final int DEFAULT_MULT = 4;

    public TileDataBusBig() {
        super();
        enforceBigCapacity();
    }

    public TileDataBusBig(int number) {
        super(number);
        enforceBigCapacity();
    }

    private static int getConfiguredMultSafe() {
        int mult = DEFAULT_MULT;

        try {
            ARConfiguration cfg = ARConfiguration.getCurrentConfig();
            if (cfg != null) mult = cfg.dataBusBigMultiplier;
        } catch (Throwable ignored) {
            // If config isn't ready for any reason, fall back to default.
        }

        if (mult < 1) mult = 1;
        else if (mult > 20) mult = 20;

        return mult;
    }

    private void enforceBigCapacity() {
        int mult = getConfiguredMultSafe();

        long maxLong = (long) BASE_MAX_DATA * (long) mult;
        int max = maxLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) maxLong;

        this.data.setMaxData(max);

        if (this.data.getData() > max) {
            this.data.setData(max, this.data.getDataType());
        }
    }

    @Override
    public String getModularInventoryName() {
        return "tile.databusbig.name";
    }

    @Override
    protected void readFromNBTHelper(NBTTagCompound nbtTagCompound) {
        super.readFromNBTHelper(nbtTagCompound);
        enforceBigCapacity();
    }
}
