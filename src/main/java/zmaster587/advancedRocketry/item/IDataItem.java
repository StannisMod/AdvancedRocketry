package zmaster587.advancedRocketry.item;

import net.minecraft.item.ItemStack;
import zmaster587.advancedRocketry.api.DataStorage;

import javax.annotation.Nonnull;

/**
 * Common interface for any item that behaves like an ItemData-style data container.
 *
 * Goal:
 * - Allow commands (/advancedrocketry filldata), GUI slots (SlotData),
 *   and machine logic (TileDataBus/Observatory/Terminal/etc)
 *   to accept multiple item implementations without hard-typing to ItemData.
 *
 * Contract:
 * - Implementations should store their DataStorage in the root tag of the ItemStack
 *   in the same shape as DataStorage#writeToNBT / readFromNBT expects.
 * - getDataStorage MUST return a DataStorage instance representing the stack state.
 * - setData/addData/removeData MUST persist changes back into the stack NBT.
 */
public interface IDataItem {

    /**
     * @return max capacity for this specific stack.
     * Implementations may compute this from damage, NBT, config, etc.
     */
    int getMaxData(@Nonnull ItemStack stack);

    /**
     * Reads a DataStorage snapshot from the stack.
     * Implementations should ensure returned storage has correct maxData applied.
     */
    @Nonnull
    DataStorage getDataStorage(@Nonnull ItemStack stack);

    /**
     * Convenience read of current amount.
     */
    default int getData(@Nonnull ItemStack stack) {
        return getDataStorage(stack).getData();
    }

    /**
     * Convenience read of current type.
     */
    @Nonnull
    default DataStorage.DataType getDataType(@Nonnull ItemStack stack) {
        return getDataStorage(stack).getDataType();
    }

    /**
     * Adds data of the given type to this stack.
     *
     * @return amount actually added
     */
    int addData(@Nonnull ItemStack stack, int amount, @Nonnull DataStorage.DataType dataType);

    /**
     * Removes data of the given type from this stack.
     *
     * @return amount actually removed
     */
    int removeData(@Nonnull ItemStack stack, int amount, @Nonnull DataStorage.DataType dataType);

    /**
     * Sets (overwrites) data amount + type on this stack.
     */
    void setData(@Nonnull ItemStack stack, int amount, @Nonnull DataStorage.DataType dataType);
}
