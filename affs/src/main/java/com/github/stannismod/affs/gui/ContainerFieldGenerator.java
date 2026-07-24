package com.github.stannismod.affs.gui;

import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

public class ContainerFieldGenerator extends Container {

    private final TileEntityFieldGenerator tile;

    public ContainerFieldGenerator(EntityPlayer player, TileEntityFieldGenerator tile) {
        this.tile = tile;
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return tile != null
                && !tile.isInvalid()
                && tile.getWorld() != null
                && tile.getWorld().getTileEntity(tile.getPos()) == tile
                && playerIn.getDistanceSq(
                    tile.getPos().getX() + 0.5D,
                    tile.getPos().getY() + 0.5D,
                    tile.getPos().getZ() + 0.5D
                ) <= 64.0D;
    }
}
