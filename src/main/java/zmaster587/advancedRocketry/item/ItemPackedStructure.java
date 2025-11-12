package zmaster587.advancedRocketry.item;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.client.TooltipInjector;
import zmaster587.advancedRocketry.util.StorageChunk;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ItemPackedStructure extends Item {

    public ItemPackedStructure() {
        setHasSubtypes(true);
    }

    public void setStructure(@Nonnull ItemStack stack, StorageChunk chunk) {
        NBTTagCompound nbt;
        if (stack.hasTagCompound())
            nbt = stack.getTagCompound();
        else
            nbt = new NBTTagCompound();

        NBTTagCompound chunkNbt = new NBTTagCompound();

        chunk.writeToNBT(chunkNbt);

        nbt.setTag("chunk", chunkNbt);
        stack.setTagCompound(nbt);
    }

    public StorageChunk getStructure(@Nonnull ItemStack stack) {
        if (stack.hasTagCompound()) {
            NBTTagCompound nbt = stack.getTagCompound();
            StorageChunk chunk = new StorageChunk();

            chunk.readFromNBT(nbt.getCompoundTag("chunk"));
            return chunk;
        }
        return null;
    }
    
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.advancedrocketry.packedstructure", insertAt);
    }
}
