package com.github.stannismod.affs.item;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.entity.EntityLaserBolt;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;

public class ItemLaserGun extends Item {

    public ItemLaserGun() {
        this.setRegistryName(AdvancedForceFieldSystem.MODID, "laser_gun");
        this.setUnlocalizedName("laser_gun");
        this.setCreativeTab(AdvancedForceFieldSystem.tabAffs);
        this.setMaxStackSize(1);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        ItemStack stack = playerIn.getHeldItem(handIn);
        if (!worldIn.isRemote) {
            EntityLaserBolt bolt = new EntityLaserBolt(worldIn, playerIn);
            bolt.shoot(playerIn, playerIn.rotationPitch, playerIn.rotationYaw, 0.0F, 3.0F, 0.0F);
            worldIn.spawnEntity(bolt);
        }
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }
}
