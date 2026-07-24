package com.github.stannismod.affs.item;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;

public class ItemBlockTiered extends ItemBlock {

    private final int tierCount;

    public ItemBlockTiered(Block block, int tierCount) {
        super(block);
        this.tierCount = Math.max(1, tierCount);
        this.setRegistryName(block.getRegistryName());
        this.setCreativeTab(AdvancedForceFieldSystem.tabAffs);
        this.setHasSubtypes(true);
    }

    @Override
    public int getMetadata(int damage) {
        return Math.max(0, Math.min(tierCount - 1, damage));
    }

    @Override
    public String getUnlocalizedName(ItemStack stack) {
        String path = AdvancedForceFieldSystem.resourcePath(getRegistryName());
        return "tile." + path + ".tier" + getMetadata(stack.getMetadata());
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (!isInCreativeTab(tab)) {
            return;
        }
        for (int tier = 0; tier < tierCount; tier++) {
            items.add(new ItemStack(this, 1, tier));
        }
    }
}
