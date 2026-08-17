package zmaster587.advancedRocketry.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import javax.annotation.Nullable;

import zmaster587.advancedRocketry.tile.heat.TileHeatIntakeDuct;

/**
 * The air-intake duct: bolted to a chiller's cold face, it makes the machine draw from the room
 * instead of from a coolant loop.
 * <p>
 * Deliberately NOT a {@link BlockHeatLoop}. Plumbing joins the heat graph and carries thermal mass;
 * this joins nothing and stores nothing — it is a mouth, and all it does is say which zone is on the
 * other side of it. Making it a loop block would additionally weld it to whatever pipe it touched,
 * which is exactly the merge a chiller exists to prevent.
 */
public class BlockHeatIntakeDuct extends Block {

    public BlockHeatIntakeDuct() {
        super(Material.IRON);
    }

    @Override
    public boolean hasTileEntity(@Nullable IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(@Nullable World world, @Nullable IBlockState state) {
        return new TileHeatIntakeDuct();
    }
}
