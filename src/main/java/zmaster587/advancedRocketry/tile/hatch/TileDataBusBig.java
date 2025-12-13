package zmaster587.advancedRocketry.tile.hatch;

import net.minecraft.nbt.NBTTagCompound;

public class TileDataBusBig extends TileDataBus {

    private static final int MULT = 4;

    public TileDataBusBig() {
        super();
        enforceBigCapacity();
    }

    public TileDataBusBig(int number) {
        super(number);
        enforceBigCapacity();
    }

    private void enforceBigCapacity() {
        int max = BASE_MAX_DATA * MULT;

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
