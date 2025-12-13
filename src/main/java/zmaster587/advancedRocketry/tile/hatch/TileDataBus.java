package zmaster587.advancedRocketry.tile.hatch;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.advancedRocketry.inventory.modules.ModuleAutoData;
import zmaster587.advancedRocketry.item.IDataItem;
import zmaster587.advancedRocketry.item.ItemData;
import zmaster587.advancedRocketry.util.IDataInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.tile.multiblock.TileMultiBlock;
import zmaster587.libVulpes.tile.multiblock.hatch.TileInventoryHatch;
import zmaster587.libVulpes.util.INetworkMachine;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;

public class TileDataBus extends TileInventoryHatch implements IDataInventory, INetworkMachine {

    protected DataStorage data;
    protected static final int BASE_MAX_DATA = 2000;

    public TileDataBus() {
        data = new DataStorage(DataStorage.DataType.UNDEFINED);
        data.setMaxData(BASE_MAX_DATA);
    }

    public TileDataBus(int number) {
        super(number);
        data = new DataStorage(DataStorage.DataType.UNDEFINED);
        data.setMaxData(BASE_MAX_DATA);

        inventory.setCanInsertSlot(0, true);
        inventory.setCanInsertSlot(1, false);
        inventory.setCanExtractSlot(0, false);
        inventory.setCanExtractSlot(1, true);
    }

    @Override
    public void loadData(int id) {

        ItemStack itemStack = inventory.getStackInSlot(0);

        if (!itemStack.isEmpty() && itemStack.getItem() instanceof IDataItem) {
            IDataItem item = (IDataItem) itemStack.getItem();

            DataStorage chip = item.getDataStorage(itemStack);

            int moved = this.data.addData(
                    chip.getData(),
                    chip.getDataType(),
                    true
            );

            // Remove exactly what was accepted
            item.removeData(itemStack, moved, DataStorage.DataType.UNDEFINED);

            inventory.setInventorySlotContents(1, decrStackSize(0, 1));
        }
    }


    @Override
    public String getModularInventoryName() {
        return "tile.loader.0.name";
    }

    @Override
    public void storeData(int id) {
        ItemStack itemStack = inventory.getStackInSlot(0);

        if (!itemStack.isEmpty()
                && itemStack.getItem() instanceof IDataItem
                && inventory.getStackInSlot(1).isEmpty()) {

            IDataItem item = (IDataItem) itemStack.getItem();

            int added = item.addData(
                    itemStack,
                    this.data.getData(),
                    this.data.getDataType()
            );

            this.data.removeData(added, true);

            inventory.setInventorySlotContents(1, decrStackSize(0, 1));
        }
    }


    public void setData(int data, DataStorage.DataType dataType) {
        this.data.setData(data, dataType);
    }

    @Override
    public int addData(int data, DataStorage.DataType dataType, EnumFacing dir, boolean commit) {
        return this.data.addData(data, dataType, commit);
    }

    public int getData() {
        return data.getData();
    }

    public final DataStorage getDataObject() {
        return data;
    }

    public int setMaxData() {
        return data.getMaxData();
    }

    public void setMaxData(int maxData) {
        data.setMaxData(maxData);
    }

    /**
     * @param type the datatype to lock the tile to or null to unlock
     * @see DataStorage
     */
    public void lockData(DataStorage.DataType type) {
        data.lockDataType(type);
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        LinkedList<ModuleBase> modules = new LinkedList<>();
        modules.add(new ModuleAutoData(40, 20, 0, 1, this, this, data));
        return modules;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
    }

    @Override
    public void setInventorySlotContents(int slot, @Nonnull ItemStack stack) {
        inventory.setInventorySlotContents(slot, stack);

        ItemStack itemStack = inventory.getStackInSlot(0);

        if (!itemStack.isEmpty()
                && itemStack.getItem() instanceof IDataItem
                && inventory.getStackInSlot(1).isEmpty()) {

            IDataItem item = (IDataItem) itemStack.getItem();
            DataStorage chip = item.getDataStorage(itemStack);

            int chipData = chip.getData();
            int chipMax  = chip.getMaxData();

            // Auto-load from chip -> bus
            if (chipData > 0 && data.getData() < data.getMaxData()) {
                loadData(0);

            // Auto-store from bus -> chip
            } else if (data.getData() > 0 && chipData < chipMax) {
                storeData(0);
            }
        }

        inventory.markDirty();
        markDirty();
        this.handleUpdateTag(getUpdateTag());

        if (this.hasMaster() && this.getMasterBlock() instanceof TileMultiBlock)
            ((TileMultiBlock) this.getMasterBlock()).onInventoryUpdated();
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public boolean canExtractItem(int index, @Nonnull ItemStack stack, EnumFacing direction) {
        return index == 1;
    }

    @Override
    public boolean canInsertItem(int index, @Nonnull ItemStack itemStackIn, EnumFacing direction) {
        return index == 0 && isItemValidForSlot(index, itemStackIn);
    }

    @Override
    protected NBTTagCompound writeToNBTHelper(NBTTagCompound nbtTagCompound) {
        super.writeToNBTHelper(nbtTagCompound);
        data.writeToNBT(nbtTagCompound);
        return nbtTagCompound;
    }

    @Override
    protected void readFromNBTHelper(NBTTagCompound nbtTagCompound) {
        super.readFromNBTHelper(nbtTagCompound);
        data.readFromNBT(nbtTagCompound);
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId, NBTTagCompound nbt) {
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id, NBTTagCompound nbt) {
    }

    @Override
    public int extractData(int maxAmount, DataType type, EnumFacing dir, boolean commit) {
        if (type == DataStorage.DataType.UNDEFINED || this.data.getDataType() == type)
            return this.data.removeData(maxAmount, commit);
        return 0;
    }
}
