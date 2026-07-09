package zmaster587.advancedRocketry.block;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.client.TooltipInjector;
import zmaster587.advancedRocketry.entity.EntityDummy;
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

    /**
     * Sit like {@link BlockSeat}, but BIND the mount dummy to this seat block ({@link
     * EntityDummy#setSeatPos}). On a Valkyrien Skies ship the dummy renders at world coordinates
     * while this seat block lives at a distant ship-subspace position, so the client must resolve
     * the seat from the bound block pos, not the dummy's own position. (Reimplemented rather than
     * delegating to {@code super} so the dummy carries the binding whether it is reused or spawned.)
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            for (Entity e : world.getEntitiesWithinAABBExcludingEntity(player, new AxisAlignedBB(pos, pos.add(1, 1, 1)))) {
                if (e instanceof EntityDummy) {
                    if (!e.getPassengers().isEmpty()) {
                        return true;
                    }
                    e.setPosition(pos.getX() + 0.5f, pos.getY() + 0.2f, pos.getZ() + 0.5f);
                    ((EntityDummy) e).setSeatPos(pos);
                    player.startRiding(e);
                    return true;
                }
            }
            EntityDummy dummy = new EntityDummy(world, pos.getX() + 0.5f, pos.getY() + 0.2f, pos.getZ() + 0.5f);
            dummy.setSeatPos(pos);
            world.spawnEntity(dummy);
            player.startRiding(dummy);
        }
        return true;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.advancedrocketry.pilotseat", insertAt);
    }
}
