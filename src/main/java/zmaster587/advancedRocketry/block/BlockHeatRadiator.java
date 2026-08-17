package zmaster587.advancedRocketry.block;

import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.tile.heat.TileHeatRadiator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A radiating cell. Directional in all six ways, because a ship has a top and a bottom as much as it
 * has sides, and the surface a plate is built on decides where it can shed to.
 * <p>
 * Placing it against a hull face points its radiating side AWAY from that face, which is the way a
 * player expects to build a plate: click the outside of the hull, get a cell facing space.
 */
public class BlockHeatRadiator extends BlockHeatLoop {

    public static final PropertyDirection FACING = PropertyDirection.create("facing");

    public BlockHeatRadiator() {
        setDefaultState(blockState.getBaseState().withProperty(FACING, EnumFacing.UP));
    }

    /** The way the cell at this state radiates. Never null, so a caller needs no fallback. */
    public static EnumFacing facingOf(IBlockState state) {
        if (state != null && state.getBlock() instanceof BlockHeatRadiator) {
            return state.getValue(FACING);
        }
        return EnumFacing.UP;
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getIndex();
    }

    @Override
    @Nonnull
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.getFront(meta & 7));
    }

    @Override
    @Nonnull
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX,
                                            float hitY, float hitZ, int meta, EntityLivingBase placer,
                                            EnumHand hand) {
        // `facing` is the side of the block that was clicked, so it already points out of the hull.
        return getDefaultState().withProperty(FACING, facing);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer,
                                @Nonnull ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
    }

    @Override
    public TileEntity createTileEntity(@Nullable World world, @Nullable IBlockState state) {
        return new TileHeatRadiator();
    }
}
