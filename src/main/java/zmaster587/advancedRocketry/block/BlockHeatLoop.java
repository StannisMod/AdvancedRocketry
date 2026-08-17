package zmaster587.advancedRocketry.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.subsystem.heat.HeatNetwork;

import javax.annotation.Nullable;

/**
 * What a coolant pipe and a heat accumulator have in common as BLOCKS: they are plumbing, and they
 * are the only thing in the world that notices when the machine they are cooling appears or goes.
 * <p>
 * That second job needs saying. A machine is not part of the heat network — it is read from beside
 * it — so placing a reactor against a finished pipe marks nothing dirty and the loop would go on
 * cooling a machine that is not there, or ignoring one that is. Vanilla already tells a block when
 * its neighbours change, whichever way they were placed, so the signal costs nothing and covers
 * every placer rather than only a player's hand.
 */
public abstract class BlockHeatLoop extends Block {

    protected BlockHeatLoop() {
        super(Material.IRON);
    }

    @Override
    public boolean hasTileEntity(@Nullable IBlockState state) {
        return true;
    }

    @Override
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block blockIn, BlockPos fromPos) {
        super.neighborChanged(state, world, pos, blockIn, fromPos);
        HeatNetwork.onLoopNeighbourChanged(world, pos);
    }
}
