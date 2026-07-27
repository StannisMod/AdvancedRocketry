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

import net.minecraft.util.text.TextComponentTranslation;

import zmaster587.advancedRocketry.client.TooltipInjector;
import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;
import zmaster587.advancedRocketry.tile.TilePilotSeat;
import zmaster587.advancedRocketry.util.StorageChunk;

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
     *
     * <p>Refusals are surfaced, not silent: a seat whose dummy already carries a DIFFERENT
     * passenger answers with an action-bar message naming the occupant (a click on one's own
     * occupied seat is a silent no-op), and a successful sit on a craft that is not yet assembled
     * (the seat is unlinked, so no input will reach any flight computer) tells the pilot why his
     * controls are dead. The occupied refusal wins — exactly one message per click.</p>
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            EntityDummy existing = boundDummyAt(world, pos);
            if (existing != null && !existing.getPassengers().isEmpty()) {
                Entity occupant = existing.getPassengers().get(0);
                if (occupant != player) {
                    player.sendStatusMessage(new TextComponentTranslation(
                            "msg.pilotseat.occupied", occupant.getName()), true);
                }
                return true; // someone is already piloting from this seat
            }
            if (existing != null) {
                existing.setPosition(pos.getX() + 0.5f, pos.getY() + 0.2f, pos.getZ() + 0.5f);
                existing.setSeatPos(pos);
                player.startRiding(existing);
            } else {
                EntityDummy dummy = new EntityDummy(world,
                        pos.getX() + 0.5f, pos.getY() + 0.2f, pos.getZ() + 0.5f);
                dummy.setSeatPos(pos);
                world.spawnEntity(dummy);
                player.startRiding(dummy);
            }
            TileEntity te = world.getTileEntity(pos);
            // "Assembled" means a physics ship actually manages this seat, NOT that the seat carries
            // a link. The link is recorded by the assembler before the physics mod confirms the
            // spawn and persists through a rejected one, so gating on it alone suppressed this very
            // notice in the one case it exists for: a craft that failed to assemble. See
            // TilePilotSeat#isManagedByShip.
            boolean assembled = te instanceof TilePilotSeat
                    && ((TilePilotSeat) te).isLinked()
                    && ((TilePilotSeat) te).isManagedByShip(world);
            if (!assembled && player instanceof net.minecraft.entity.player.EntityPlayerMP) {
                // Delayed past the mount packet's tracker flush: sent immediately, the notice is
                // overwritten by vanilla's "press X to dismount" hint before the player reads it.
                zmaster587.advancedRocketry.util.DelayedActionBar.send(
                        (net.minecraft.entity.player.EntityPlayerMP) player,
                        new TextComponentTranslation("msg.pilotseat.notassembled"), 10);
            }
        }
        return true;
    }

    /**
     * Destroying an occupied pilot seat by ANY cause (mined, explosion, a command) releases the
     * ship's controls instead of latching them: the seated rider is dismounted, the seat's bound
     * dummy is removed, and the linked flight computer drops its live input and cruise setpoint
     * ({@link TileAdvancedFlightComputer#onControlStationLost}), reverting the ship to an unmanned
     * station-hold. Without this, the client silently stops sending when the seat tile dies (no
     * release packet), the riderless-dummy input clearer never runs (it needs the seat tile to
     * resolve), and the flight computer executes the pilot's last command every tick — with Flight
     * Assist on, a held throttle keeps ramping the cruise: an uncontrollable runaway ship.
     *
     * <p>NOT run when a relocation cut ({@link StorageChunk#isRelocationInProgress}) removes the
     * block — assembly and crossings move the craft, and their crew handling (rebind / capture +
     * re-seat) owns the binding across the move.</p>
     */
    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote && !StorageChunk.isRelocationInProgress()) {
            TileEntity te = world.getTileEntity(pos); // still present: removed after breakBlock
            if (te instanceof TilePilotSeat) {
                TileAdvancedFlightComputer afc = ((TilePilotSeat) te).getFlightComputer();
                if (afc != null) {
                    afc.onControlStationLost();
                }
            }
            EntityDummy dummy = boundDummyAt(world, pos);
            if (dummy != null) {
                for (Entity rider : new java.util.ArrayList<>(dummy.getPassengers())) {
                    rider.dismountRidingEntity();
                }
                dummy.setDead();
            }
        }
        super.breakBlock(world, pos, state);
    }

    /**
     * The dummy already bound to the seat at {@code seatPos}, or {@code null} if none. A tier-2
     * seat block lives in the ship's distant subspace while its bound dummy is glued to the seat's
     * live WORLD position ({@link EntityDummy#onUpdate}), so a search at the block position alone
     * never finds it on a moving ship. Without the reuse this enables, every re-mount would spawn a
     * FRESH dummy and leave the old (empty) one behind — and an empty dummy clears the flight
     * computer's pilot input every server tick ({@link EntityDummy} telemetry), so the accumulated
     * dummies would fight a returning pilot's input. Every path that mounts (or spawns a mount for)
     * a pilot seat goes through this lookup first — one seat, one dummy.
     *
     * <p><b>The bound seat position is the identity; proximity is only a shortcut.</b> The two boxes
     * below are O(1) fast paths for the ordinary cases (a parked seat; a flying ship whose dummy is
     * glued within a block of it), but a box is a bet on how far the ship travels between the
     * dummy's glue and this lookup — and that distance is the cruise speed divided by twenty. The
     * bet was lost the moment the cap was raised: at 40 blocks/s a ship crosses 2 blocks per tick,
     * the one-block box missed, a second dummy was spawned, and the empty twin went straight back to
     * clearing the pilot's input. The fallback therefore matches the dummy's own recorded seat
     * position instead — exact, and it cannot go stale again the next time the cap moves.</p>
     */
    public static EntityDummy boundDummyAt(World world, BlockPos seatPos) {
        EntityDummy atBlock = firstBoundDummy(world,
                new AxisAlignedBB(seatPos, seatPos.add(1, 1, 1)), seatPos);
        if (atBlock != null) {
            return atBlock;
        }
        double[] worldSeat = VSIntegration.getSeatWorldPosition(world, seatPos);
        if (worldSeat != null) {
            AxisAlignedBB atWorld = new AxisAlignedBB(worldSeat[0], worldSeat[1], worldSeat[2],
                    worldSeat[0], worldSeat[1], worldSeat[2]).grow(1.0);
            EntityDummy near = firstBoundDummy(world, atWorld, seatPos);
            if (near != null) {
                return near;
            }
        }
        return boundDummyAnywhere(world, seatPos);
    }

    /**
     * The dummy bound to {@code seatPos} wherever it currently is — the authoritative answer the
     * boxes above only approximate. Walks the world's loaded entities, so it is kept for the miss
     * case; every caller is a player action or a once-per-crossing step, never a per-tick path.
     */
    private static EntityDummy boundDummyAnywhere(World world, BlockPos seatPos) {
        for (net.minecraft.entity.Entity e : world.loadedEntityList) {
            if (e instanceof EntityDummy && !e.isDead
                    && seatPos.equals(((EntityDummy) e).getSeatPos())) {
                return (EntityDummy) e;
            }
        }
        return null;
    }

    /** The first dummy in {@code box} bound to {@code seatPos}, or {@code null}. */
    private static EntityDummy firstBoundDummy(World world, AxisAlignedBB box, BlockPos seatPos) {
        for (EntityDummy e : world.getEntitiesWithinAABB(EntityDummy.class, box)) {
            if (seatPos.equals(e.getSeatPos())) {
                return e;
            }
        }
        return null;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        int insertAt = TooltipInjector.computeInsertIndex(tooltip, flag.isAdvanced());
        TooltipInjector.renderShiftAlt(stack, tooltip, "tooltip.advancedrocketry.pilotseat", insertAt);
    }
}
