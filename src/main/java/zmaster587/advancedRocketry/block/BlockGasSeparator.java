package zmaster587.advancedRocketry.block;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.tile.atmosphere.TileGasSeparator;
import zmaster587.libVulpes.block.BlockTile;

/**
 * The separator's block half. Its only reason to exist is the sneak-click: a plain right-click
 * opens the machine like any other, while shift-right-click flips it between pulling gas out of the
 * room and pushing gas back in. That makes swapping direction a one-second field action rather than
 * a trip through a GUI — the point of the manual utility mode.
 */
public class BlockGasSeparator extends BlockTile {

    public BlockGasSeparator(Class<? extends TileEntity> tileClass, int guiId) {
        super(tileClass, guiId);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!player.isSneaking())
            return super.onBlockActivated(world, pos, state, player, hand, facing, hitX, hitY, hitZ);

        TileEntity tile = world.getTileEntity(pos);
        if (!(tile instanceof TileGasSeparator))
            return false;

        TileGasSeparator separator = (TileGasSeparator) tile;
        if (!world.isRemote) {
            separator.toggleMode();
            player.sendStatusMessage(new TextComponentTranslation(separator.isCombining()
                    ? "msg.gasSeparator.combining"
                    : "msg.gasSeparator.splitting"), true);
        }
        return true;
    }
}
