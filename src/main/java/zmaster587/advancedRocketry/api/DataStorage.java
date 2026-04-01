package zmaster587.advancedRocketry.api;

import java.util.Locale;

import net.minecraft.nbt.NBTTagCompound;

public class DataStorage {

    private int data, maxData;
    private DataType dataType;
    private boolean locked;
    public DataStorage() {
        dataType = DataType.UNDEFINED;
        locked = false;
    }

    public DataStorage(DataType data) {
        dataType = data;
        locked = false;
    }

    public boolean setData(int data, DataType dataType) {
        // If empty/typeless, allow adopting the provided type (unless locked)
        if (!this.locked && this.dataType == DataType.UNDEFINED && dataType != DataType.UNDEFINED) {
            this.dataType = dataType;
        }

        if (dataType == DataType.UNDEFINED || dataType == this.dataType) {
            this.data = Math.max(0, Math.min(data, maxData));

            // If we just became empty and are not locked, clear type
            if (!this.locked && this.data == 0) {
                this.dataType = DataType.UNDEFINED;
            }
            return true;
        }
        return false;
    }



    public int getData() {
        return data;
    }

    public DataType getDataType() {
        return dataType;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public int getMaxData() {
        return maxData;
    }

    public void setMaxData(int maxData) {
        this.maxData = maxData;
        if (data > maxData)
            data = maxData;
    }

    /**
     * Locks the DataStorage to only accept the specified type and will delete any data that does not match the type
     *
     * @param type the type of data to lock to, null to unlock
     */
    public void lockDataType(DataType type) {
        this.locked = type != null;

        if (this.locked) {
            if (this.dataType != type)
                this.data = 0;

            this.dataType = type;
        } else if (this.data == 0)
            this.dataType = DataType.UNDEFINED;
    }

    public boolean isLocked() {
        return this.locked;
    }

    /**
     * @param data     amount to add
     * @param dataType type to add
     * @return data amount added
     */
    public int addData(int data, DataType incomingType, boolean commit) {
        // Snapshot
        final boolean empty = (this.data == 0);
        DataType current = this.dataType;

        // Compute effective type without mutating state on simulation
        DataType effective = current;
        if (!this.locked && empty) {
            effective = (incomingType != DataType.UNDEFINED) ? incomingType : DataType.UNDEFINED;
        }

        // Accept if: unlocked+UNDEFINED (wildcard), same type, or typeless
        boolean accepts =
            (!this.locked && incomingType == DataType.UNDEFINED) ||
            (incomingType == effective) ||
            (!this.locked && effective == DataType.UNDEFINED);


        if (!accepts) return 0;

        int amountToAdd = Math.min(data, this.maxData - this.data);
        if (amountToAdd <= 0) return 0;

        if (commit) {
            // Finalize adoption only on real add
            if (!this.locked && this.data == 0) {
                this.dataType = (incomingType != DataType.UNDEFINED) ? incomingType : DataType.UNDEFINED;
            }
            this.data += amountToAdd;

            // If still UNDEFINED but we added concrete data (edge case), solidify
            if (this.dataType == DataType.UNDEFINED && incomingType != DataType.UNDEFINED) {
                this.dataType = incomingType;
            }
        }
        return amountToAdd;
    }



    /**
     * @param data max amount of data to remove
     * @return amount of data removed
     */
    public int removeData(int data, boolean commit) {
        int dataRemoved = Math.min(data, this.data);
        if (commit)
            this.data -= dataRemoved;
        if (!locked && this.data == 0)
            this.dataType = DataType.UNDEFINED;

        return dataRemoved;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("Data", data);
        nbt.setInteger("maxData", maxData);
        nbt.setInteger("DataType", dataType.ordinal());
        nbt.setBoolean("locked", locked);
    }

    public void readFromNBT(NBTTagCompound nbt) {
        data = nbt.getInteger("Data");
        maxData = nbt.getInteger("maxData");
        try {
            dataType = DataType.values()[nbt.getInteger("DataType")];
        } catch (ArrayIndexOutOfBoundsException e) {
            dataType = DataType.UNDEFINED;
        }

        if (nbt.hasKey("locked"))
            locked = nbt.getBoolean("locked");
        else
            locked = false;

        // >>> ADD: heal stale type on empty <<<
        if (!locked && data == 0) {
            dataType = DataType.UNDEFINED;
        }
    }

    public enum DataType {
        UNDEFINED(0),
        DISTANCE(1),
        HUMIDITY(2),
        TEMPERATURE(3),
        COMPOSITION(4),
        ATMOSPHEREDENSITY(5),
        MASS(6);

        public final int id;

        DataType(int id) {
            this.id = id;
        }

        public static DataType getById(int id) {
            return DataType.values()[id];
        }

        public String toString() {
            return "data." + name().toLowerCase(Locale.ENGLISH) + ".name";
        }
    }
}
