package com.github.stannismod.affs.item;

import com.github.stannismod.affs.block.BlockFieldGenerator;
import net.minecraft.block.Block;

public class ItemBlockFieldGenerator extends ItemBlockTiered {

    public ItemBlockFieldGenerator(Block block) {
        super(block, BlockFieldGenerator.TIER_COUNT);
    }
}
