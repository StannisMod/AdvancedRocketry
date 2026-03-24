package zmaster587.advancedRocketry.block;

import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.libVulpes.block.BlockTile;
import zmaster587.advancedRocketry.client.TooltipInjector;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class BlockTransceiver extends BlockTile {

    public static final PropertyDirection FACING = PropertyDirection.create("facing");

    private static final AxisAlignedBB AABB_N = new AxisAlignedBB(.25, .25, 0.00, .75, .75, .25);
    private static final AxisAlignedBB AABB_S = new AxisAlignedBB(.25, .25, .75, .75, .75, 1.00);
    private static final AxisAlignedBB AABB_W = new AxisAlignedBB(0.00, .25, .25, .25, .75, .75);
    private static final AxisAlignedBB AABB_E = new AxisAlignedBB(.75, .25, .25, 1.00, .75, .75);
    private static final AxisAlignedBB AABB_U = new AxisAlignedBB(.25, .75, .25, .75, 1.00, .75);
    private static final AxisAlignedBB AABB_D = new AxisAlignedBB(.25, 0.00, .25, .75, .25, .75);

    public BlockTransceiver(Class<? extends TileEntity> tileClass, int guiId) {
        super(tileClass, guiId);
        this.setDefaultState(
            this.blockState.getBaseState()
                .withProperty(STATE, Boolean.FALSE)
                .withProperty(FACING, EnumFacing.NORTH)
        );
        this.setHardness(3f);
        this.setResistance(10f);
        this.setLightOpacity(0);
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        EnumFacing f = state.getValue(FACING).getOpposite();
        switch (f) {
            case NORTH: return AABB_N;
            case SOUTH: return AABB_S;
            case WEST:  return AABB_W;
            case EAST:  return AABB_E;
            case UP:    return AABB_U;
            case DOWN:  return AABB_D;
            default:    return FULL_BLOCK_AABB;
        }
    }

    @Override public boolean isFullCube(IBlockState state) { return false; }
    @Override public boolean isNormalCube(IBlockState state, IBlockAccess world, BlockPos pos) { return false; }
    @Override public boolean isOpaqueCube(IBlockState state) { return false; }

    @SideOnly(Side.CLIENT)
    @Override
    @Nonnull
    public BlockRenderLayer getBlockLayer() {
        return BlockRenderLayer.CUTOUT_MIPPED;
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, STATE);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int face = state.getValue(FACING).getIndex(); // 0..5
        boolean on = state.getValue(STATE);
        return face | (on ? 8 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        EnumFacing f = EnumFacing.getFront(meta & 7);
        boolean on = (meta & 8) != 0;
        return this.getDefaultState().withProperty(FACING, f).withProperty(STATE, on);
    }

    @Override
    public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos) {
        return state;
    }

    @Override
    @Nonnull
    public IBlockState getStateForPlacement(
            World world, BlockPos pos, EnumFacing clickedFace,
            float hitX, float hitY, float hitZ, int meta,
            EntityLivingBase placer, EnumHand hand) {
        return this.getDefaultState()
            .withProperty(FACING, clickedFace)
            .withProperty(STATE, Boolean.FALSE);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, @Nonnull ItemStack stack) {
        // keep orientation from getStateForPlacement
    }

    @Override
    public boolean rotateBlock(World world, BlockPos pos, EnumFacing axis) {
        IBlockState s = world.getBlockState(pos);
        EnumFacing cur = s.getValue(FACING);
        EnumFacing next;

        switch (axis) {
            case UP:
            case DOWN:
                next = (cur == EnumFacing.UP || cur == EnumFacing.DOWN) ? cur : cur.rotateY();
                break;
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                next = (cur == EnumFacing.UP || cur == EnumFacing.DOWN) ? cur : cur.rotateY();
                break;
            default:
                next = cur;
        }

        if (next != cur) {
            world.setBlockState(pos, s.withProperty(FACING, next), 2);
            return true;
        }
        return false;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.advancedrocketry.transceiver", insertAt);
    }

    public static EnumFacing getFront(IBlockState state) {
        if (state == null || !state.getPropertyKeys().contains(FACING)) return EnumFacing.NORTH;
        return state.getValue(FACING);
    }
}
