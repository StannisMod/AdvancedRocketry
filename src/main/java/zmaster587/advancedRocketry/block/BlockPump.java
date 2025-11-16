package zmaster587.advancedRocketry.block;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidUtil;
import zmaster587.libVulpes.block.BlockTile;

public class BlockPump extends BlockTile {

    public BlockPump(Class tileClass, int guiId) {
        super(tileClass, guiId);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state,
                                    EntityPlayer player, EnumHand hand,
                                    EnumFacing side, float hitX, float hitY, float hitZ) {
        // Try container <-> TE interaction first (handles buckets both directions).
        if (FluidUtil.interactWithFluidHandler(player, hand, world, pos, side)) {
            return true;
        }
        return super.onBlockActivated(world, pos, state, player, hand, side, hitX, hitY, hitZ);
    }
}
