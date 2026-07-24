package com.github.stannismod.affs.te;

import com.github.stannismod.affs.util.NBTDeserializer;
import com.github.stannismod.affs.util.NBTSerializer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nonnull;

/**
 * Stores coords of root TE and delegates all interaction to it
 */
public class TileEntityMultiBlockDelegate extends TileEntity {

    private BlockPos root;

    @Override
    public void readFromNBT(@Nonnull final NBTTagCompound compound) {
        super.readFromNBT(compound);
        root = NBTDeserializer.blockPos(compound.getTag("root"));
    }

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(@Nonnull final NBTTagCompound compound) {
        compound.setTag("root", NBTSerializer.blockPos(root));
        return super.writeToNBT(compound);
    }

    public BlockPos getRoot() {
        return root;
    }

    public void setRoot(final BlockPos root) {
        this.root = root;
    }
}
