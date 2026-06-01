package zmaster587.advancedRocketry.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockPistonBase;
import net.minecraft.block.BlockPistonExtension;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;

import javax.annotation.Nonnull;

public class BlockSmallPlatePressHead extends BlockPistonExtension {

    public BlockSmallPlatePressHead() {
        super();
    }

    private boolean hasValidPressBase(World world, BlockPos headPos, IBlockState headState) {
        BlockPos basePos = headPos.offset(headState.getValue(FACING).getOpposite());
        IBlockState baseState = world.getBlockState(basePos);

        return baseState.getBlock() == AdvancedRocketryBlocks.blockPlatePress
                && baseState.getValue(BlockPistonBase.FACING) == headState.getValue(FACING)
                && baseState.getValue(BlockPistonBase.EXTENDED);
    }

    @Override
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block blockIn, BlockPos fromPos) {
        if (!hasValidPressBase(world, pos, state)) {
            world.setBlockToAir(pos);
            return;
        }

        BlockPos basePos = pos.offset(state.getValue(FACING).getOpposite());
        IBlockState baseState = world.getBlockState(basePos);
        baseState.neighborChanged(world, basePos, blockIn, fromPos);
    }

    @Override
    public void onBlockHarvested(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
        BlockPos basePos = pos.offset(state.getValue(FACING).getOpposite());
        IBlockState baseState = world.getBlockState(basePos);

        if (baseState.getBlock() == AdvancedRocketryBlocks.blockPlatePress
                && baseState.getValue(BlockPistonBase.FACING) == state.getValue(FACING)
                && baseState.getValue(BlockPistonBase.EXTENDED)) {

            if (player.capabilities.isCreativeMode) {
                world.setBlockToAir(basePos);
            } else {
                baseState.getBlock().dropBlockAsItem(world, basePos, baseState, 0);
                world.setBlockToAir(basePos);
            }
        }

        super.onBlockHarvested(world, pos, state, player);
    }

    @Override
    @Nonnull
    public ItemStack getItem(World world, BlockPos pos, IBlockState state) {
        return new ItemStack(AdvancedRocketryBlocks.blockPlatePress);
    }
}