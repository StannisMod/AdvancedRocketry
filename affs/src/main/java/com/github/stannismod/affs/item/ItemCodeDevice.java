package com.github.stannismod.affs.item;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.te.TileEntityContourInjector;
import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import com.github.stannismod.affs.util.CodeUtils;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
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
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        TileEntity te = world.getTileEntity(pos);
        boolean stampable = te instanceof TileEntityFieldGenerator || te instanceof TileEntityContourInjector;
        if (!stampable) {
            return EnumActionResult.PASS;
        }
        // Stamp the carried access code onto the shield node it is used on; the field then admits
        // any entity carrying a device with a matching code. This is the rotatable Layer-3 credential.
        if (!world.isRemote) {
            String code = CodeUtils.getCode(player.getHeldItem(hand));
            if (te instanceof TileEntityFieldGenerator) {
                ((TileEntityFieldGenerator) te).applyAccessCode(code);
            } else {
                ((TileEntityContourInjector) te).applyContourCode(code);
            }
        }
        return EnumActionResult.SUCCESS;
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
