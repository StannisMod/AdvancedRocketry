package com.github.stannismod.affs.block;

import net.minecraft.item.Item;

import javax.annotation.Nullable;

public interface IHasItemBlock {

    @Nullable
    Item createItemBlock();
}
