package com.github.stannismod.affs.block;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.te.TileEntityAdminEnergySource;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public class BlockAdminEnergySource extends Block implements ITileEntityProvider, IHasItemBlock {

    public BlockAdminEnergySource(String name, Material material) {
        super(material);
        this.setUnlocalizedName(name);
        this.setRegistryName(AdvancedForceFieldSystem.MODID, name);
        this.setCreativeTab(AdvancedForceFieldSystem.tabAffs);
        this.setHardness(3.0F);
        this.setResistance(6.0F);
        this.setSoundType(SoundType.METAL);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileEntityAdminEnergySource();
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!worldIn.isRemote) {
            AdvancedForceFieldSystem.openAffsGui(playerIn, AdvancedForceFieldSystem.GUI_ADMIN_ENERGY_SOURCE, worldIn, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    @Override
    public Item createItemBlock() {
        return new ItemBlock(this).setRegistryName(getRegistryName()).setCreativeTab(AdvancedForceFieldSystem.tabAffs);
    }
}
