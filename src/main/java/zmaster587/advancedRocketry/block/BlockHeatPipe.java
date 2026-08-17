package zmaster587.advancedRocketry.block;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.tile.heat.TileHeatPipe;

import javax.annotation.Nullable;

/**
 * Coolant pipe: plumbing, like the ventilation duct, and with the same shape of decision — where
 * you run it and how much of it you run, never anything you do to one block.
 */
public class BlockHeatPipe extends BlockHeatLoop {

    @Override
    public TileEntity createTileEntity(@Nullable World world, @Nullable IBlockState state) {
        return new TileHeatPipe();
    }
}
