package zmaster587.advancedRocketry.block;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import zmaster587.libVulpes.block.BlockTileComparatorOverride;
import zmaster587.libVulpes.util.IAdjBlockUpdate;

public class BlockTileNeighborUpdate extends BlockTileComparatorOverride {

    /**
     * @param tileClass must extend IAdjBlockUpdate
     */
    public BlockTileNeighborUpdate(Class<? extends TileEntity> tileClass, int guiId) {
        super(tileClass, guiId);
    }

    // redstone power uses neighbor change to update redstone power
    @Override
    public void neighborChanged(net.minecraft.block.state.IBlockState state,
                                net.minecraft.world.World world,
                                net.minecraft.util.math.BlockPos pos,
                                net.minecraft.block.Block blockIn,
                                net.minecraft.util.math.BlockPos fromPos) {
        super.neighborChanged(state, world, pos, blockIn, fromPos);
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof zmaster587.libVulpes.util.IAdjBlockUpdate) {
            ((zmaster587.libVulpes.util.IAdjBlockUpdate) te).onAdjacentBlockUpdated();
        }
    }


    @Override
    public void onNeighborChange(IBlockAccess world, BlockPos pos, BlockPos neighbor) {
        super.onNeighborChange(world, pos, neighbor);
        TileEntity tile = world.getTileEntity(pos);

        if (tile instanceof IAdjBlockUpdate)
            ((IAdjBlockUpdate) tile).onAdjacentBlockUpdated();
    }

}
