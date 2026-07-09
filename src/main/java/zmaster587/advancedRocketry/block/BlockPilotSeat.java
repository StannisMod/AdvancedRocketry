package zmaster587.advancedRocketry.block;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.client.TooltipInjector;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * Pilot seat for a tier-2 (Valkyrien Skies) ship. Sits exactly like the {@linkplain BlockSeat
 * generic seat} — right-click to mount an invisible dummy — but carries a {@link TilePilotSeat}
 * that routes the seated player's Free Flight input to the ship's Advanced Flight Computer.
 *
 * <p>Extending {@link BlockSeat} reuses the mount/dismount and render behaviour unchanged; only
 * the tile entity (control routing) and the tooltip differ.</p>
 */
public class BlockPilotSeat extends BlockSeat {

    public BlockPilotSeat(Material mat) {
        super(mat);
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World worldIn, IBlockState state) {
        return new TilePilotSeat();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.advancedrocketry.pilotseat", insertAt);
    }
}
