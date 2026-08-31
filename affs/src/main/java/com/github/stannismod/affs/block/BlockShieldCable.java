package com.github.stannismod.affs.block;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.item.ItemBlockTiered;
import com.github.stannismod.affs.te.TileEntityShieldCable;
import com.github.stannismod.affs.world.shield.ShieldNetworkManager;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkNode;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class BlockShieldCable extends Block implements ITileEntityProvider, IHasItemBlock {

    private static final AxisAlignedBB CORE_AABB = new AxisAlignedBB(5.0D / 16.0D, 5.0D / 16.0D, 5.0D / 16.0D,
            11.0D / 16.0D, 11.0D / 16.0D, 11.0D / 16.0D);
    private static final AxisAlignedBB NORTH_ARM_AABB = new AxisAlignedBB(5.0D / 16.0D, 5.0D / 16.0D, 0.0D,
            11.0D / 16.0D, 11.0D / 16.0D, 5.0D / 16.0D);
    private static final AxisAlignedBB SOUTH_ARM_AABB = new AxisAlignedBB(5.0D / 16.0D, 5.0D / 16.0D, 11.0D / 16.0D,
            11.0D / 16.0D, 11.0D / 16.0D, 1.0D);
    private static final AxisAlignedBB WEST_ARM_AABB = new AxisAlignedBB(0.0D, 5.0D / 16.0D, 5.0D / 16.0D,
            5.0D / 16.0D, 11.0D / 16.0D, 11.0D / 16.0D);
    private static final AxisAlignedBB EAST_ARM_AABB = new AxisAlignedBB(11.0D / 16.0D, 5.0D / 16.0D, 5.0D / 16.0D,
            1.0D, 11.0D / 16.0D, 11.0D / 16.0D);
    private static final AxisAlignedBB DOWN_ARM_AABB = new AxisAlignedBB(5.0D / 16.0D, 0.0D, 5.0D / 16.0D,
            11.0D / 16.0D, 5.0D / 16.0D, 11.0D / 16.0D);
    private static final AxisAlignedBB UP_ARM_AABB = new AxisAlignedBB(5.0D / 16.0D, 11.0D / 16.0D, 5.0D / 16.0D,
            11.0D / 16.0D, 1.0D, 11.0D / 16.0D);

    public static final PropertyInteger TIER = PropertyInteger.create("tier", 0, BlockFieldGenerator.TIER_COUNT - 1);
    public static final PropertyBool NORTH = PropertyBool.create("north");
    public static final PropertyBool EAST = PropertyBool.create("east");
    public static final PropertyBool SOUTH = PropertyBool.create("south");
    public static final PropertyBool WEST = PropertyBool.create("west");
    public static final PropertyBool UP = PropertyBool.create("up");
    public static final PropertyBool DOWN = PropertyBool.create("down");

    public BlockShieldCable(String name, Material material) {
        super(material);
        setUnlocalizedName(name);
        setRegistryName(AdvancedForceFieldSystem.MODID, name);
        setCreativeTab(AdvancedForceFieldSystem.tabAffs);
        setHardness(1.0F);
        setResistance(4.0F);
        setSoundType(SoundType.METAL);
        setLightOpacity(0);
        this.setDefaultState(this.blockState.getBaseState()
                .withProperty(TIER, 0)
                .withProperty(NORTH, false)
                .withProperty(EAST, false)
                .withProperty(SOUTH, false)
                .withProperty(WEST, false)
                .withProperty(UP, false)
                .withProperty(DOWN, false));
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileEntityShieldCable();
    }

    @Nonnull
    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, TIER, NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(TIER, clampTier(meta));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(TIER);
    }

    @Nonnull
    @Override
    public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
        return state
                .withProperty(NORTH, canConnect(world, pos, EnumFacing.NORTH))
                .withProperty(EAST, canConnect(world, pos, EnumFacing.EAST))
                .withProperty(SOUTH, canConnect(world, pos, EnumFacing.SOUTH))
                .withProperty(WEST, canConnect(world, pos, EnumFacing.WEST))
                .withProperty(UP, canConnect(world, pos, EnumFacing.UP))
                .withProperty(DOWN, canConnect(world, pos, EnumFacing.DOWN));
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!worldIn.isRemote) {
            // The cable is the natural inspection point for the shield network.
            AdvancedForceFieldSystem.openAffsGui(playerIn, AdvancedForceFieldSystem.GUI_SHIELD_NETWORK, worldIn, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    public BlockRenderLayer getRenderLayer() {
        return BlockRenderLayer.CUTOUT_MIPPED;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean doesSideBlockRendering(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing face) {
        return false;
    }

    @Override
    public boolean getUseNeighborBrightness(IBlockState state) {
        return true;
    }

    @Override
    public BlockFaceShape getBlockFaceShape(IBlockAccess worldIn, IBlockState state, BlockPos pos, EnumFacing face) {
        return BlockFaceShape.UNDEFINED;
    }

    @Nonnull
    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return buildBoundingBox(state);
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState blockState, IBlockAccess worldIn, BlockPos pos) {
        return buildBoundingBox(blockState);
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, @Nullable Entity entityIn, boolean isActualState) {
        addCollisionBoxToList(pos, entityBox, collidingBoxes, CORE_AABB);
        if (state.getValue(NORTH)) {
            addCollisionBoxToList(pos, entityBox, collidingBoxes, NORTH_ARM_AABB);
        }
        if (state.getValue(SOUTH)) {
            addCollisionBoxToList(pos, entityBox, collidingBoxes, SOUTH_ARM_AABB);
        }
        if (state.getValue(WEST)) {
            addCollisionBoxToList(pos, entityBox, collidingBoxes, WEST_ARM_AABB);
        }
        if (state.getValue(EAST)) {
            addCollisionBoxToList(pos, entityBox, collidingBoxes, EAST_ARM_AABB);
        }
        if (state.getValue(DOWN)) {
            addCollisionBoxToList(pos, entityBox, collidingBoxes, DOWN_ARM_AABB);
        }
        if (state.getValue(UP)) {
            addCollisionBoxToList(pos, entityBox, collidingBoxes, UP_ARM_AABB);
        }
    }

    private AxisAlignedBB buildBoundingBox(IBlockState state) {
        AxisAlignedBB box = CORE_AABB;
        if (state.getValue(NORTH)) {
            box = box.union(NORTH_ARM_AABB);
        }
        if (state.getValue(SOUTH)) {
            box = box.union(SOUTH_ARM_AABB);
        }
        if (state.getValue(WEST)) {
            box = box.union(WEST_ARM_AABB);
        }
        if (state.getValue(EAST)) {
            box = box.union(EAST_ARM_AABB);
        }
        if (state.getValue(DOWN)) {
            box = box.union(DOWN_ARM_AABB);
        }
        if (state.getValue(UP)) {
            box = box.union(UP_ARM_AABB);
        }
        return box;
    }

    private boolean canConnect(IBlockAccess world, BlockPos pos, EnumFacing facing) {
        TileEntity tileEntity = world.getTileEntity(pos.offset(facing));
        // A cable connects to shield nodes only: a ventilation duct laid through the same wall is a
        // network node too, and joining it would draw an arm to a block this line never feeds.
        return tileEntity instanceof ISubsystemNetworkNode
                && ((ISubsystemNetworkNode) tileEntity).getNetworkDomain() == ShieldNetworkManager.DOMAIN;
    }

    @Override
    public Item createItemBlock() {
        return new ItemBlockTiered(this, BlockFieldGenerator.TIER_COUNT);
    }

    @Override
    public void getSubBlocks(net.minecraft.creativetab.CreativeTabs itemIn, NonNullList<ItemStack> items) {
        Item item = Item.getItemFromBlock(this);
        for (int tier = 0; tier < BlockFieldGenerator.TIER_COUNT; tier++) {
            items.add(new ItemStack(item, 1, tier));
        }
    }

    @Override
    public int damageDropped(IBlockState state) {
        return getMetaFromState(state);
    }

    private static int clampTier(int tier) {
        return Math.max(0, Math.min(BlockFieldGenerator.TIER_COUNT - 1, tier));
    }
}
