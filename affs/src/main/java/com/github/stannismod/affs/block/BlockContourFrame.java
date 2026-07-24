package com.github.stannismod.affs.block;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;

public class BlockContourFrame extends Block implements IHasItemBlock {

    public BlockContourFrame(String name, Material material) {
        super(material);
        setUnlocalizedName(name);
        setRegistryName(AdvancedForceFieldSystem.MODID, name);
        setCreativeTab(AdvancedForceFieldSystem.tabAffs);
        setHardness(2.5F);
        setResistance(8.0F);
        setSoundType(SoundType.METAL);
    }

    @Override
    public Item createItemBlock() {
        return new ItemBlock(this).setRegistryName(getRegistryName()).setCreativeTab(AdvancedForceFieldSystem.tabAffs);
    }
}
