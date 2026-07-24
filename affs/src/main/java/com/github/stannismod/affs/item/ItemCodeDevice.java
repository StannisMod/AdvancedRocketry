package com.github.stannismod.affs.item;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.util.CodeUtils;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

public class ItemCodeDevice extends Item {

    public ItemCodeDevice() {
        this.setRegistryName(AdvancedForceFieldSystem.MODID, "code_device");
        this.setUnlocalizedName("code_device");
        this.setCreativeTab(AdvancedForceFieldSystem.tabAffs);
        this.setMaxStackSize(1);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        if (!worldIn.isRemote) {
            AdvancedForceFieldSystem.openAffsGui(playerIn, AdvancedForceFieldSystem.GUI_CODE_DEVICE, worldIn, handIn.ordinal(), 0, 0);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, playerIn.getHeldItem(handIn));
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        String code = CodeUtils.getCode(stack);
        tooltip.add(code.isEmpty() ? "Code: <empty>" : "Code: " + code);
    }

    @Override
    public void getSubItems(net.minecraft.creativetab.CreativeTabs tab, NonNullList<ItemStack> items) {
        if (isInCreativeTab(tab)) {
            items.add(new ItemStack(this));
        }
    }
}
