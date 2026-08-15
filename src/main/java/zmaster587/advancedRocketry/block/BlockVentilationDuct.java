package zmaster587.advancedRocketry.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.tile.atmosphere.TileVentilationDuct;

import javax.annotation.Nullable;

/**
 * The ventilation duct: plumbing, not a machine. It has no GUI and no state a player can set — its
 * only property is how much it will carry, which is why the interesting decision is where you run
 * it and how many you run, rather than anything you do to one.
 */
public class BlockVentilationDuct extends Block {

    public BlockVentilationDuct() {
        super(Material.IRON);
    }

    @Override
    public boolean hasTileEntity(@Nullable IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(@Nullable World world, @Nullable IBlockState state) {
        return new TileVentilationDuct();
    }
}
