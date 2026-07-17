package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * Moves a tier-2 ship's crew across a per-ship crossing. {@link VSIntegration#crossShip} moves
 * blocks + tile NBT only — riders differ per caller — and a crossing re-assembles the ship into a
 * <b>fresh subspace</b>, so every absolute-subspace binding (a pilot dummy's {@code seatPos})
 * goes stale. This class owns the two halves around the crossing:
 *
 * <ol>
 *   <li>{@link #capture}: BEFORE the cut — enumerate the seated crew at the ship's live world
 *       position (riders live in the WORLD frame, never in the shipyard box), record each rider
 *       against its seat's AFC-link offset (the one binding that IS invariant under re-assembly),
 *       and retire the old dummies (their seat blocks are about to stop existing).</li>
 *   <li>{@link #reseat}: AFTER re-assembly — find each seat's NEW subspace position by matching
 *       the recorded link offset over the fresh ship's seat tiles, transfer the rider into the
 *       destination world, and re-mount it on a freshly-bound dummy (the
 *       {@code BlockPilotSeat} mount recipe).</li>
 * </ol>
 *
 * <p>Scope: SEATED crew (a pilot on a linked pilot seat). Walking crew waits on the extreme-pose
 * collision fix. Server main thread only.</p>
 */
public final class CrewTransfer {

    /** How far (blocks) around the ship's world position riders are enumerated — the proven
     *  rider-carry box of the ship-move probes. */
    private static final double RIDER_RANGE = 8.0;

    /** One captured rider: the player and the seat's AFC-link offset that re-identifies its seat
     *  on the re-assembled ship (relative offsets survive the rigid relocation; absolute subspace
     *  coordinates do not). */
    public static final class Crew {
        public final EntityPlayerMP player;
        public final int afcDx, afcDy, afcDz;

        Crew(EntityPlayerMP player, int afcDx, int afcDy, int afcDz) {
            this.player = player;
            this.afcDx = afcDx;
            this.afcDy = afcDy;
            this.afcDz = afcDz;
        }
    }

    private CrewTransfer() { }

    /**
     * Enumerate the seated crew of the ship whose flight computer sits at subspace {@code afcPos},
     * with the ship's live world position {@code shipWorldPos}. Records each seated player against
     * its seat's link offset, dismounts it, and retires the now-orphaned dummy. Call BEFORE the
     * crossing cuts the ship's blocks.
     */
    public static List<Crew> capture(WorldServer world, BlockPos afcPos, double[] shipWorldPos) {
        List<Crew> crew = new ArrayList<>();
        if (shipWorldPos == null) {
            return crew;
        }
        AxisAlignedBB box = new AxisAlignedBB(
                shipWorldPos[0], shipWorldPos[1], shipWorldPos[2],
                shipWorldPos[0], shipWorldPos[1], shipWorldPos[2]).grow(RIDER_RANGE);
        for (EntityDummy dummy : world.getEntitiesWithinAABB(EntityDummy.class, box)) {
            BlockPos seatPos = dummy.getSeatPos();
            if (seatPos == null) {
                continue;
            }
            TileEntity te = world.getTileEntity(seatPos);
            if (!(te instanceof TilePilotSeat)) {
                continue;
            }
            TilePilotSeat seat = (TilePilotSeat) te;
            BlockPos linkedAfc = seat.getFlightComputerPos();
            if (linkedAfc == null || !linkedAfc.equals(afcPos)) {
                continue; // a different ship's seat sharing the neighbourhood
            }
            int dx = linkedAfc.getX() - seatPos.getX();
            int dy = linkedAfc.getY() - seatPos.getY();
            int dz = linkedAfc.getZ() - seatPos.getZ();
            for (Entity passenger : dummy.getPassengers()) {
                if (passenger instanceof EntityPlayerMP) {
                    crew.add(new Crew((EntityPlayerMP) passenger, dx, dy, dz));
                    passenger.dismountRidingEntity();
                }
            }
            // The seat block this dummy is bound to is about to be cut; a stale dummy would
            // otherwise linger and clear the (re-assembled) ship's pilot input every tick.
            dummy.setDead();
        }
        return crew;
    }

    /**
     * Re-seat the captured crew on the re-assembled ship anchored (any ship block) at
     * {@code anchor} in {@code dstWorld}: for each rider, find the seat whose AFC-link offset
     * matches its record, transfer the rider into {@code dstWorld} (production player-list path),
     * and mount it on a freshly-bound dummy. Returns {@code false} if any rider's seat could not
     * be resolved yet (the caller retries next tick — re-assembly is asynchronous; already-seated
     * riders are not double-mounted thanks to the bound-dummy reuse in the mount recipe).
     */
    public static boolean reseat(WorldServer dstWorld, BlockPos anchor, List<Crew> crew) {
        if (crew.isEmpty()) {
            return true;
        }
        List<TilePilotSeat> seats = seatsOfShipAt(dstWorld, anchor);
        boolean allSeated = true;
        for (Crew rider : crew) {
            TilePilotSeat seat = matchSeat(seats, rider);
            double[] seatWorld = seat == null
                    ? null : VSIntegration.getSeatWorldPosition(dstWorld, seat.getPos());
            if (seat == null || seatWorld == null) {
                allSeated = false; // seat tile or ship transform not up yet — retry
                continue;
            }
            EntityPlayerMP player = rider.player;
            if (player.hasDisconnected()) {
                continue;
            }
            if (player.dimension != dstWorld.provider.getDimension()) {
                final double tx = seatWorld[0], ty = seatWorld[1], tz = seatWorld[2];
                player.getServer().getPlayerList().transferPlayerToDimension(player,
                        dstWorld.provider.getDimension(),
                        (world, entity, yaw) -> entity.setLocationAndAngles(tx, ty, tz, yaw, 0f));
            } else {
                player.setPositionAndUpdate(seatWorld[0], seatWorld[1], seatWorld[2]);
            }
            if (player.getRidingEntity() instanceof EntityDummy) {
                continue; // already re-seated by an earlier retry
            }
            // The BlockPilotSeat mount recipe: a dummy at the seat's live world position, bound
            // to the seat's (new) subspace block, and the player riding it.
            EntityDummy dummy = new EntityDummy(dstWorld, seatWorld[0], seatWorld[1], seatWorld[2]);
            dummy.setSeatPos(seat.getPos());
            dstWorld.spawnEntity(dummy);
            player.startRiding(dummy, true);
        }
        return allSeated;
    }

    /** Every pilot-seat tile of the ship at {@code anchor}, found over its subspace shipyard. */
    private static List<TilePilotSeat> seatsOfShipAt(WorldServer world, BlockPos anchor) {
        List<TilePilotSeat> seats = new ArrayList<>();
        AxisAlignedBB yard = VSIntegration.shipyardBoundsAt(world,
                anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
        for (TileEntity te : world.loadedTileEntityList) {
            if (!(te instanceof TilePilotSeat)) {
                continue;
            }
            BlockPos p = te.getPos();
            // Before relocation finishes the seat may still sit at the paste site (near the
            // anchor); after it, inside the shipyard box. Accept both while the ship settles.
            boolean inYard = yard != null && yard.contains(new net.minecraft.util.math.Vec3d(
                    p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
            boolean nearAnchor = p.distanceSq(anchor) <= RIDER_RANGE * RIDER_RANGE;
            if (inYard || nearAnchor) {
                seats.add((TilePilotSeat) te);
            }
        }
        return seats;
    }

    /** The seat whose AFC-link offset matches {@code rider}'s record, or {@code null}. */
    private static TilePilotSeat matchSeat(List<TilePilotSeat> seats, Crew rider) {
        for (TilePilotSeat seat : seats) {
            BlockPos afc = seat.getFlightComputerPos();
            if (afc == null) {
                continue;
            }
            BlockPos p = seat.getPos();
            if (afc.getX() - p.getX() == rider.afcDx
                    && afc.getY() - p.getY() == rider.afcDy
                    && afc.getZ() - p.getZ() == rider.afcDz) {
                return seat;
            }
        }
        return null;
    }
}
