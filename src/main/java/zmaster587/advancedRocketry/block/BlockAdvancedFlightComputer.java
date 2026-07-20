package zmaster587.advancedRocketry.block;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;
import zmaster587.advancedRocketry.tile.TilePilotSeat;
import zmaster587.advancedRocketry.util.StorageChunk;
import zmaster587.libVulpes.block.BlockTile;

/**
 * The Advanced Flight Computer's block. A plain {@link BlockTile} except for destruction: the
 * computer is a tier-2 ship's sole command authority, so removing it (mined, explosion, a command)
 * must cleanly end the piloting session instead of leaving a ghost of it behind.
 *
 * <p>On a real destruction (never a relocation cut — assembly and crossings MOVE the craft and own
 * their crew handling) every seated pilot whose seat is linked to this computer is dismounted, his
 * seat's mount dummy removed, and he is told the flight computer is gone. The tile's own
 * {@code invalidate()} kills the live command channels, so the physics-thread force controller
 * stops thrusting the ship the instant the block dies — never a runaway under a dead computer's
 * last command.</p>
 */
public class BlockAdvancedFlightComputer extends BlockTile {

    public BlockAdvancedFlightComputer(int guiId) {
        super(TileAdvancedFlightComputer.class, guiId);
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote && !StorageChunk.isRelocationInProgress()
                && world instanceof WorldServer) {
            // The computer keeps no back-link to its seat, so find the seated crew the same way the
            // pilot messaging does: every mount dummy whose bound seat's stored offset resolves to
            // THIS block. (During a VS relocation's own block sweep no live dummy resolves here —
            // a stale pre-assembly binding points at vacated coordinates and a crossing captures
            // its crew before the cut — so this enumeration is naturally a no-op there.)
            for (Object obj : ((WorldServer) world).loadedEntityList.toArray()) {
                if (!(obj instanceof EntityDummy)) {
                    continue;
                }
                EntityDummy dummy = (EntityDummy) obj;
                TilePilotSeat seat = TilePilotSeat.forRider(dummy, world);
                if (seat == null || !pos.equals(seat.getFlightComputerPos())) {
                    continue;
                }
                for (Entity rider : new java.util.ArrayList<>(dummy.getPassengers())) {
                    if (rider instanceof net.minecraft.entity.player.EntityPlayerMP) {
                        ((net.minecraft.entity.player.EntityPlayerMP) rider).sendStatusMessage(
                                new TextComponentTranslation("msg.pilotseat.afcdestroyed"), true);
                    }
                    rider.dismountRidingEntity();
                }
                dummy.setDead();
            }
        }
        super.breakBlock(world, pos, state);
    }
}
