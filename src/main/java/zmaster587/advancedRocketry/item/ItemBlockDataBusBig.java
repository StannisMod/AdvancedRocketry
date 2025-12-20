package zmaster587.advancedRocketry.item;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.tile.hatch.TileDataBusBig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class ItemBlockDataBusBig extends ItemBlock implements IDataItem {

    // Keep this local and explicit
    private static final int BASE_MAX_DATA = 2000; // match TileDataBus base
    private static final int DEFAULT_MULT = 4;

    public ItemBlockDataBusBig(Block block) {
        super(block);
        setHasSubtypes(false);
        setMaxDamage(0);
    }

    // ---- IDataItem ----

    private static int getConfiguredMultSafe() {
        int mult = DEFAULT_MULT;

        try {
            ARConfiguration cfg = ARConfiguration.getCurrentConfig();
            if (cfg != null) mult = cfg.dataBusBigMultiplier;
        } catch (Throwable ignored) {}

        if (mult < 1) mult = 1;
        else if (mult > 20) mult = 20;

        return mult;
    }

    private static int computeMaxData() {
        int mult = getConfiguredMultSafe();
        long maxLong = (long) BASE_MAX_DATA * (long) mult;
        return maxLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) maxLong;
    }

    @Override
    public int getMaxData(@Nonnull ItemStack stack) {
        return computeMaxData();
    }



    @Override
    @Nonnull
    public DataStorage getDataStorage(@Nonnull ItemStack stack) {
        DataStorage data = new DataStorage();

        if (!stack.hasTagCompound()) {
            data.setMaxData(getMaxData(stack));
            data.setData(0, DataStorage.DataType.UNDEFINED);
        } else {
            data.readFromNBT(stack.getTagCompound());
            data.setMaxData(getMaxData(stack));
            if (data.getData() > data.getMaxData()) {
                data.setData(data.getMaxData(), data.getDataType());
            }
        }
        return data;
    }

    @Override
    public int addData(@Nonnull ItemStack stack, int amount, @Nonnull DataStorage.DataType dataType) {
        DataStorage data = getDataStorage(stack);

        int added = data.addData(amount, dataType, true);

        NBTTagCompound nbt = new NBTTagCompound();
        data.writeToNBT(nbt);
        stack.setTagCompound(nbt);

        return added;
    }

    @Override
    public int removeData(@Nonnull ItemStack stack, int amount, @Nonnull DataStorage.DataType dataType) {
        DataStorage data = getDataStorage(stack);

        int removed = data.removeData(amount, true);

        NBTTagCompound nbt = new NBTTagCompound();
        data.writeToNBT(nbt);
        stack.setTagCompound(nbt);

        return removed;
    }

    @Override
    public void setData(@Nonnull ItemStack stack, int amount, @Nonnull DataStorage.DataType dataType) {
        DataStorage data = getDataStorage(stack);

        data.setData(amount, dataType);

        NBTTagCompound nbt = new NBTTagCompound();
        data.writeToNBT(nbt);
        stack.setTagCompound(nbt);
    }

    // ---- stacking rule (same behavior as ItemData) ----
    @Override
    public int getItemStackLimit(@Nonnull ItemStack stack) {
        return getData(stack) == 0 ? super.getItemStackLimit(stack) : 1;
    }

    // ---- place NBT into TE ----
    @Override
    public boolean placeBlockAt(ItemStack stack, EntityPlayer player, World world, BlockPos pos,
                                EnumFacing side, float hitX, float hitY, float hitZ, IBlockState newState) {

        boolean placed = super.placeBlockAt(stack, player, world, pos, side, hitX, hitY, hitZ, newState);
        if (!placed || world.isRemote) return placed;

        if (!stack.hasTagCompound()) return placed;

        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileDataBusBig)) return placed;

        TileDataBusBig bus = (TileDataBusBig) te;

        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null) {
            NBTTagCompound teTag = bus.writeToNBT(new NBTTagCompound());
            teTag.merge(tag);
            bus.readFromNBT(teTag);
            bus.markDirty();
            world.notifyBlockUpdate(pos, newState, newState, 3);
        }

        return placed;
    }

    // ---- tooltip parity with ItemData ----
    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(@Nonnull ItemStack stack, @Nullable World world,
                               List<String> list, ITooltipFlag flag) {

        DataStorage data = getDataStorage(stack);

        // Header
        list.add(I18n.format("tooltip.advancedrocketry.databusbig.header"));

        // Type
        String typeText = I18n.format(data.getDataType().toString());
        list.add(I18n.format("tooltip.advancedrocketry.itemdata.type") + typeText);

        // Data
        list.add(I18n.format("tooltip.advancedrocketry.itemdata.data")
                + TextFormatting.GOLD + data.getData()
                + TextFormatting.WHITE + " / "
                + TextFormatting.GOLD + data.getMaxData());
    }
}
