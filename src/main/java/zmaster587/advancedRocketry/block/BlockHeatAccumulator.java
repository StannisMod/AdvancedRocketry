package zmaster587.advancedRocketry.block;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.tile.heat.TileHeatAccumulator;

import javax.annotation.Nullable;

/**
 * A block of thermal mass for a coolant loop. Dumb on purpose: no power, no GUI, nothing to set —
 * what it is worth is decided entirely by how many of them a ship carries.
 */
public class BlockHeatAccumulator extends BlockHeatLoop {

    @Override
    public TileEntity createTileEntity(@Nullable World world, @Nullable IBlockState state) {
        return new TileHeatAccumulator();
    }
}
