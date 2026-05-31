package zmaster587.advancedRocketry.item;

import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.libVulpes.items.ItemIngredient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class ItemData extends ItemIngredient implements IDataItem {

    public ItemData() {
        super(1);
        setMaxStackSize(1);
    }

    // ---- OLD API (keep) ----
    public int getMaxData(int damage) {
        return damage == 0 ? 1000 : 0;
    }

    // ---- NEW API (IDataItem) ----
    @Override
    public int getMaxData(@Nonnull ItemStack stack) {
        return getMaxData(stack.getItemDamage());
    }

    @Override
    public int getItemStackLimit(@Nonnull ItemStack stack) {
        return getData(stack) == 0 ? super.getItemStackLimit(stack) : 1;
    }

    public int getData(@Nonnull ItemStack stack) {
        return getDataStorage(stack).getData();
    }

    public DataStorage.DataType getDataType(@Nonnull ItemStack stack) {
        return getDataStorage(stack).getDataType();
    }

    @Override
    @Nonnull
    public DataStorage getDataStorage(@Nonnull ItemStack item) {

        DataStorage data = new DataStorage();

        if (!item.hasTagCompound()) {
            data.setMaxData(getMaxData(item));
            NBTTagCompound nbt = new NBTTagCompound();
            data.writeToNBT(nbt);
            // NOTE: original ItemData does NOT auto-attach tag here.
            // Keep behavior to avoid subtle side effects.
        } else {
            data.readFromNBT(item.getTagCompound());
            // make sure capacity is correct for this item
            data.setMaxData(getMaxData(item));
        }

        return data;
    }

    @Override
    public int addData(@Nonnull ItemStack item, int amount, @Nonnull DataStorage.DataType dataType) {
        DataStorage data = getDataStorage(item);

        int amt = data.addData(amount, dataType, true);

        NBTTagCompound nbt = new NBTTagCompound();
        data.writeToNBT(nbt);
        item.setTagCompound(nbt);

        return amt;
    }

    @Override
    public int removeData(@Nonnull ItemStack item, int amount, @Nonnull DataStorage.DataType dataType) {
        DataStorage data = getDataStorage(item);

        int amt = data.removeData(amount, true);

        NBTTagCompound nbt = new NBTTagCompound();
        data.writeToNBT(nbt);
        item.setTagCompound(nbt);

        return amt;
    }

    @Override
    public void setData(@Nonnull ItemStack item, int amount, @Nonnull DataStorage.DataType dataType) {
        DataStorage data = getDataStorage(item);

        data.setData(amount, dataType);

        NBTTagCompound nbt = new NBTTagCompound();
        data.writeToNBT(nbt);
        item.setTagCompound(nbt);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(@Nonnull ItemStack stack, @Nullable World world,
                               List<String> list, ITooltipFlag flag) {
        super.addInformation(stack, world, list, flag);

        DataStorage data = getDataStorage(stack);

        // Type:
        list.add(I18n.format("tooltip.advancedrocketry.itemdata.header"));
        String typeText = I18n.format(data.getDataType().toString());
        list.add(I18n.format("tooltip.advancedrocketry.itemdata.type") + typeText);

        // Data:
        list.add(I18n.format("tooltip.advancedrocketry.itemdata.data")
                + TextFormatting.GOLD + data.getData()
                + TextFormatting.WHITE + " / "
                + TextFormatting.GOLD + data.getMaxData());

        // Hold Shift:
        if (net.minecraft.client.gui.GuiScreen.isShiftKeyDown()) {
            list.add(TextFormatting.GRAY +
                    I18n.format("tooltip.advancedrocketry.itemdataunit.shift.1"));
        } else if (I18n.hasKey("tooltip.advancedrocketry.hold_shift")) {
            list.add(TextFormatting.DARK_GRAY.toString() +
                    TextFormatting.ITALIC +
                    I18n.format("tooltip.advancedrocketry.hold_shift"));
        }
    }
}
