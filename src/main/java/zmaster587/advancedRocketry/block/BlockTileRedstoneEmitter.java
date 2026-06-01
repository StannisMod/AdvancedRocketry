package zmaster587.advancedRocketry.block;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.client.TooltipInjector;
import zmaster587.libVulpes.block.BlockTile;

import javax.annotation.Nullable;
import java.util.List;

// Fueling Station block
public class BlockTileRedstoneEmitter extends BlockTile {

    public BlockTileRedstoneEmitter(Class<? extends TileEntity> tileClass, int guiId) {
        super(tileClass, guiId);
    }

    @Override
    public int getWeakPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return state.getValue(STATE) ? 15 : 0;
    }

    @Override
    public int getStrongPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return getWeakPower(state, world, pos, side);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.advancedrocketry.fuelingstation", insertAt);
    }

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    public void setRedstoneState(World world, IBlockState _ignored, BlockPos pos, boolean newState) {
        // Server-only to avoid client mutations
        if (world.isRemote) return;

        // skip if chunk isn't loaded
        if (!world.isBlockLoaded(pos)) return;

        // Read the current state from the world to avoid acting on a stale IBlockState
        IBlockState curState = world.getBlockState(pos);
        if (curState.getBlock() != this) return;

        boolean current = curState.getValue(STATE);
        if (current == newState) return; // no-op if unchanged

        IBlockState updated = curState.withProperty(STATE, newState);

        // 3 = neighbors notified (1) + clients updated (2)
        world.setBlockState(pos, updated, 3);
    }
}
