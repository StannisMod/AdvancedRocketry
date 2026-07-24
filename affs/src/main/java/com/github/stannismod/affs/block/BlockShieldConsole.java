package com.github.stannismod.affs.block;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.te.TileEntityShieldConsole;
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

public class BlockShieldConsole extends Block implements ITileEntityProvider, IHasItemBlock {

    public BlockShieldConsole(String name, Material material) {
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
        return new TileEntityShieldConsole();
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!worldIn.isRemote) {
            AdvancedForceFieldSystem.openAffsGui(playerIn, AdvancedForceFieldSystem.GUI_SHIELD_CONSOLE, worldIn, pos.getX(), pos.getY(), pos.getZ());
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
