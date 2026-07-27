package zmaster587.advancedRocketry.block;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import zmaster587.libVulpes.block.BlockTile;

/**
 * A ship machine that carries a tile entity but has nothing to say to a player who right-clicks it.
 *
 * <p>The hyperdrive family is deliberately built this way: a generator, a capacitor, an emitter and
 * a dampener have no settings to change and no inventory to fill. What each of them is worth is
 * decided by what the player welded around it, and what the ship as a whole can do is read at the
 * navigation computer — one console, one place to look, instead of a panel on every block.</p>
 */
public class BlockShipMachine extends BlockTile {

    public BlockShipMachine(Class<? extends TileEntity> tileClass) {
        super(tileClass, -1);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state,
                                    EntityPlayer player, EnumHand hand, EnumFacing side,
                                    float hitX, float hitY, float hitZ) {
        return false; // nothing to open: the ship's readouts live at the navigation computer
    }
}
