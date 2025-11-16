package zmaster587.advancedRocketry.item;

import net.minecraft.client.gui.GuiScreen;
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

public class ItemData extends ItemIngredient {

    int maxData;

    public ItemData() {
        super(1);
        setMaxStackSize(1);
    }

    public int getMaxData(int damage) {
        return damage == 0 ? 1000 : 0;
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

    public DataStorage getDataStorage(@Nonnull ItemStack item) {

        DataStorage data = new DataStorage();

        if (!item.hasTagCompound()) {
            data.setMaxData(getMaxData(item.getItemDamage()));
            NBTTagCompound nbt = new NBTTagCompound();
            data.writeToNBT(nbt);
        } else
            data.readFromNBT(item.getTagCompound());

        return data;
    }

    public int addData(@Nonnull ItemStack item, int amount, DataStorage.DataType dataType) {
        DataStorage data = getDataStorage(item);

        int amt = data.addData(amount, dataType, true);

        NBTTagCompound nbt = new NBTTagCompound();
        data.writeToNBT(nbt);
        item.setTagCompound(nbt);

        return amt;
    }

    public int removeData(@Nonnull ItemStack item, int amount, DataStorage.DataType dataType) {
        DataStorage data = getDataStorage(item);

        int amt = data.removeData(amount, true);

        NBTTagCompound nbt = new NBTTagCompound();
        data.writeToNBT(nbt);
        item.setTagCompound(nbt);

        return amt;
    }

    public void setData(@Nonnull ItemStack item, int amount, DataStorage.DataType dataType) {
        DataStorage data = getDataStorage(item);

        data.setData(amount, dataType);

        NBTTagCompound nbt = new NBTTagCompound();
        data.writeToNBT(nbt);
        item.setTagCompound(nbt);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(@Nonnull ItemStack stack, @Nullable World world, List<String> list, ITooltipFlag flag) {
        super.addInformation(stack, world, list, flag);

        DataStorage data = getDataStorage(stack);

        // 1) Type: <type>
        list.add(TextFormatting.DARK_PURPLE + "Space Suit Component");
        String typeText = net.minecraft.client.resources.I18n.format(data.getDataType().toString());
        list.add(net.minecraft.util.text.TextFormatting.WHITE + "Type: " + typeText);

        // 2) §fData stored: §6<data> §f/§6 <max>
        list.add(net.minecraft.util.text.TextFormatting.WHITE + "Data stored: "
            + net.minecraft.util.text.TextFormatting.GOLD + data.getData()
            + net.minecraft.util.text.TextFormatting.WHITE + " / "
            + net.minecraft.util.text.TextFormatting.GOLD + data.getMaxData());

        // 3) Hold Shift for more info
        if (net.minecraft.client.gui.GuiScreen.isShiftKeyDown()) {
            list.add(net.minecraft.util.text.TextFormatting.GRAY +
                    net.minecraft.client.resources.I18n.format("tooltip.advancedrocketry.itemdataunit.shift.1"));
        } else if (net.minecraft.client.resources.I18n.hasKey("tooltip.advancedrocketry.hold_shift")) {
            list.add(net.minecraft.util.text.TextFormatting.DARK_GRAY.toString() +
                    net.minecraft.util.text.TextFormatting.ITALIC +
                    net.minecraft.client.resources.I18n.format("tooltip.advancedrocketry.hold_shift"));
        }
    }

}
