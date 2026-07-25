package com.github.stannismod.affs.block;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.te.TileEntityShieldAccumulator;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Bulk shield-energy reserve block. Plain machine block backed by {@link TileEntityShieldAccumulator};
 * no tiers and no GUI — it is a passive store that the shield network fills and drains automatically.
 */
public class BlockShieldAccumulator extends Block implements ITileEntityProvider {

    public BlockShieldAccumulator(String name, Material material) {
        super(material);
        setUnlocalizedName(name);
        setRegistryName(AdvancedForceFieldSystem.MODID, name);
        setCreativeTab(AdvancedForceFieldSystem.tabAffs);
        setHardness(3.0F);
        setResistance(8.0F);
        setSoundType(SoundType.METAL);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileEntityShieldAccumulator();
    }
}
