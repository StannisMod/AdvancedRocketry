package zmaster587.advancedRocketry.item;

import com.mojang.realmsclient.gui.ChatFormatting;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import java.util.List;

public class ItemIdWithName extends Item {

    public void setName(@Nonnull ItemStack stack, String name) {
        NBTTagCompound nbt = stack.hasTagCompound() ? stack.getTagCompound() : new NBTTagCompound();
        nbt.setString("name", name);
        stack.setTagCompound(nbt);
    }

    public String getName(@Nonnull ItemStack stack) {
        if (stack.hasTagCompound()) {
            NBTTagCompound nbt = stack.getTagCompound();
            return nbt.getString("name");
        }

        return "";
    }


    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(@Nonnull ItemStack stack, World player, List<String> list, ITooltipFlag bool) {
        if (stack.getItemDamage() == -1) {
            list.add(ChatFormatting.GRAY + "Unprogrammed");
            return;
        }

        String keyOrName = getName(stack);
        if (keyOrName == null || keyOrName.isEmpty()) {
            return;
        }

        // If it's a lang key, this becomes localized; if not, it returns the input unchanged.
        String translated = net.minecraft.client.resources.I18n.format(keyOrName);
        list.add(translated);
    }
}
