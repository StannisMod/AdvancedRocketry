package com.github.stannismod.affs.block;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.item.ItemBlockFieldGenerator;
import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public class BlockFieldGenerator extends Block implements ITileEntityProvider, IHasItemBlock {

    public static final int TIER_COUNT = 4;
    public static final PropertyInteger TIER = PropertyInteger.create("tier", 0, TIER_COUNT - 1);

    public BlockFieldGenerator(final String name, final Material material) {
        super(material);
        this.setUnlocalizedName(name);
        this.setRegistryName(AdvancedForceFieldSystem.MODID, name);
        this.setCreativeTab(AdvancedForceFieldSystem.tabAffs);
        this.setHardness(3.0F);
        this.setResistance(6.0F);
        this.setSoundType(SoundType.METAL);
        this.setDefaultState(this.blockState.getBaseState().withProperty(TIER, 0));
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileEntityFieldGenerator();
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!worldIn.isRemote) {
            // Open the GUI only on the server to keep the authoritative state in one place.
            AdvancedForceFieldSystem.openAffsGui(playerIn, AdvancedForceFieldSystem.GUI_FIELD_GENERATOR, worldIn, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    @Override
    public void getSubBlocks(net.minecraft.creativetab.CreativeTabs itemIn, NonNullList<ItemStack> items) {
        // Add every tier explicitly so creative/search exposes all shield variants.
        Item item = Item.getItemFromBlock(this);
        for (int tier = 0; tier < TIER_COUNT; tier++) {
            items.add(new ItemStack(item, 1, tier));
        }
    }

    @Override
    public Item createItemBlock() {
        return new ItemBlockFieldGenerator(this);
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, TIER);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(TIER, clampTier(meta));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(TIER);
    }

    @Override
    public int damageDropped(IBlockState state) {
        return getMetaFromState(state);
    }

    private static int clampTier(int tier) {
        return Math.max(0, Math.min(TIER_COUNT - 1, tier));
    }
}
