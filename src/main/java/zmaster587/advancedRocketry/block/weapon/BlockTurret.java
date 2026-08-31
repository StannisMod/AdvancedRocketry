package zmaster587.advancedRocketry.block.weapon;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.tile.weapon.TileTurret;

import javax.annotation.Nullable;

/**
 * The gun's controller block: the thing a player builds a gun AROUND.
 *
 * <p>Deliberately a plain block with a tile entity and no GUI of its own. A turret is commanded by a
 * linker, by a console over the weapons network, or by nothing at all — a screen on the mount itself
 * would be a fourth way to say the same thing, and the one that is hardest to reach in a firefight.</p>
 */
public class BlockTurret extends Block {

    public BlockTurret() {
        super(Material.IRON);
        setHardness(4.0F);
        setResistance(15.0F);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    @Nullable
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileTurret();
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean shouldSideBeRendered(IBlockState state, IBlockAccess world, net.minecraft.util.math.BlockPos pos,
                                        net.minecraft.util.EnumFacing side) {
        return true;
    }
}
