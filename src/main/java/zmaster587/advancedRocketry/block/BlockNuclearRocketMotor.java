package zmaster587.advancedRocketry.block;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.IRocketNuclearCore;
import zmaster587.advancedRocketry.tile.TileBrokenPart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BlockNuclearRocketMotor extends BlockRocketMotor {

    public BlockNuclearRocketMotor(Material mat) {
        super(mat);
    }

    @Override
    public int getThrust(World world, BlockPos pos) {
        return 35;
    }
    @Override
    public IBlockState getActualState(@Nonnull IBlockState state, IBlockAccess world, BlockPos pos) {
        // Prefer nuclear core adjacency over tanks
        if (world.getBlockState(pos.up()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.DOWN);
        if (world.getBlockState(pos.down()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.UP);
        if (world.getBlockState(pos.east()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.EAST);
        if (world.getBlockState(pos.west()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.WEST);
        if (world.getBlockState(pos.south()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.SOUTH);
        if (world.getBlockState(pos.north()).getBlock() instanceof IRocketNuclearCore)
            return state.withProperty(FACING, EnumFacing.NORTH);
        return state;
    }
    @Override
    public int getFuelConsumptionRate(World world, int x, int y, int z) {
        return 1;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(final World worldIn, final IBlockState state) {
        return new TileBrokenPart(10, 4 * (float) ARConfiguration.getCurrentConfig().increaseWearIntensityProb);
    }
}
