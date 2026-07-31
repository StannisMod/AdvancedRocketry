package zmaster587.advancedRocketry.space;

import java.util.List;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Production {@link ShipCrossingService.Ops}: carries a crossing state machine's decisions out
 * against live worlds — the proven per-ship crossing, the rider-carrying rigid pose teleport, and
 * the crew re-seat. Shared by the entry on-ramp ({@link ShipEntryController}) and the planet
 * descent ({@link DescentController}); mirrors {@link VSShipCrosser}'s role for the transit state
 * machine. Safe no-ops (nulls/false) when VS is absent or a world is missing, so a crossing aborts
 * cleanly.
 */
public final class VSShipCrossingOps implements ShipCrossingService.Ops {

    /** Rider-carry box half-width around a ship pose — the proven probe recipe's range. */
    private static final double RIDER_RANGE = 8.0;

    @Override
    public double[] shipWorldPosition(int dimId, BlockPos afcPos) {
        WorldServer world = DimensionManager.getWorld(dimId);
        return world == null ? null : VSIntegration.getShipWorldPosition(world, afcPos);
    }

    @Override
    public List<CrewTransfer.Crew> captureCrew(int dimId, BlockPos afcPos, double[] shipWorldPos) {
        WorldServer world = DimensionManager.getWorld(dimId);
        return world == null
                ? new java.util.ArrayList<CrewTransfer.Crew>()
                : CrewTransfer.capture(world, afcPos, shipWorldPos);
    }

    @Override
    public List<CrewTransfer.Crew> peekCrew(int dimId, BlockPos afcPos, double[] shipWorldPos) {
        WorldServer world = DimensionManager.getWorld(dimId);
        return world == null
                ? new java.util.ArrayList<CrewTransfer.Crew>()
                : CrewTransfer.peek(world, afcPos, shipWorldPos);
    }

    @Override
    public void latchEntryUntilBelowTheLine(int dimId, BlockPos afcPos) {
        WorldServer world = DimensionManager.getWorld(dimId);
        if (world == null || afcPos == null) {
            return;
        }
        net.minecraft.tileentity.TileEntity te = world.getTileEntity(afcPos);
        if (te instanceof zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer) {
            ((zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer) te)
                    .latchEntryUntilBelowTheLine();
        }
    }

    @Override
    public BlockPos cross(int srcDimId, double[] srcShipPos, int destDim,
                          int pasteX, int pasteY, int pasteZ) {
        WorldServer src = DimensionManager.getWorld(srcDimId);
        WorldServer dst = DimensionManager.getWorld(destDim);
        if (src == null || dst == null || srcShipPos == null) {
            return null;
        }
        VSIntegration.CrossResult res = VSIntegration.crossShip(
                src, srcShipPos[0], srcShipPos[1], srcShipPos[2], dst, pasteX, pasteY, pasteZ);
        return res.ok() ? res.anchor : null;
    }

    @Override
    public void pinDim(int dimId) {
        DimensionManager.keepDimensionLoaded(dimId, true);
    }

    @Override
    public boolean reseat(int destDim, BlockPos anchor, List<CrewTransfer.Crew> crew,
            java.util.UUID shipId) {
        WorldServer world = DimensionManager.getWorld(destDim);
        return world != null && CrewTransfer.reseat(world, anchor, crew, shipId);
    }

    @Override
    public boolean teleportPoseWithRiders(int destDim, BlockPos anchor, double px, double py, double pz) {
        WorldServer world = DimensionManager.getWorld(destDim);
        if (world == null) {
            return false;
        }
        // Readiness gate. The thing that must be true before the pose is written is that the physics
        // mod has finished relocating the blocks we pasted into the ship's subspace shipyard —
        // writing the transform mid-relocation re-maps the remaining blocks against the moved pose.
        // That is a statement about THIS crossing's progress, and it is readable at the one point we
        // own: the anchor we pasted on and seeded the assembly with. The assembly deletes every block
        // it claims from this world (the anchor is always in its found set, it is the seed), so the
        // anchor going to air is exactly "my ship has been claimed" — an exact test on one position,
        // not a nearest-ship lookup, and independent of whether the ship happens to be loaded.
        //
        // It deliberately does NOT ask whether a ship is loaded. Loadedness is re-decided every tick
        // from player proximity: with nobody aboard and nobody nearby, an unmanned arrival is exactly
        // the case such a gate can never satisfy on its own, and it only ever passed because the
        // settle force-loaded the ship itself — a coin flip against the unload the physics mod queues
        // on the same tick, not a readiness check.
        if (!world.isAirBlock(anchor)) {
            return false;
        }
        double sx = anchor.getX() + 0.5, sy = anchor.getY() + 0.5, sz = anchor.getZ() + 0.5;
        // Capture riders at the CURRENT pose before the write, then carry them by the same delta
        // (the proven teleport-ship recipe; a carried dummy's seated player follows as passenger).
        List<EntityDummy> riders = world.getEntitiesWithinAABB(EntityDummy.class,
                new AxisAlignedBB(sx, sy, sz, sx, sy, sz).grow(RIDER_RANGE));
        if (!VSIntegration.teleportShipTo(world, sx, sy, sz, px, py, pz)) {
            return false;
        }
        ArrivalTrace.server("poseTp.ship t=" + world.getTotalWorldTime()
                + " y=" + ArrivalTrace.fmt(sy) + "->" + ArrivalTrace.fmt(py)
                + " riders=" + riders.size());
        for (EntityDummy d : riders) {
            double oldY = d.posY;
            d.setPositionAndUpdate(d.posX + (px - sx), d.posY + (py - sy), d.posZ + (pz - sz));
            ArrivalTrace.server("poseTp.dummy t=" + world.getTotalWorldTime()
                    + " dummy=" + d.getEntityId()
                    + " y=" + ArrivalTrace.fmt(oldY) + "->" + ArrivalTrace.fmt(d.posY)
                    + " pass=" + ArrivalTrace.ids(d.getPassengers()));
            // Safety net: a mount must never leave a seated player behind (a rider split from his
            // mount by more than tracking range is unrecoverable client-side). The settle re-seats
            // AFTER this teleport so no crew normally rides through it, but probe mounts and any
            // future caller with a live rider are carried by the same delta.
            for (net.minecraft.entity.Entity p : d.getPassengers()) {
                if (p instanceof net.minecraft.entity.player.EntityPlayerMP) {
                    double riderY = p.posY;
                    p.setPositionAndUpdate(p.posX + (px - sx), p.posY + (py - sy), p.posZ + (pz - sz));
                    ArrivalTrace.server("poseTp.rider t=" + world.getTotalWorldTime()
                            + " p=" + p.getEntityId()
                            + " y=" + ArrivalTrace.fmt(riderY) + "->" + ArrivalTrace.fmt(p.posY));
                }
            }
        }
        return true;
    }

    @Override
    public void unparkAt(int destDim, double px, double py, double pz) {
        WorldServer world = DimensionManager.getWorld(destDim);
        if (world != null) {
            VSIntegration.unparkShipAt(world, px, py, pz);
        }
    }

    @Override
    public void messageCrew(List<CrewTransfer.Crew> crew, String langKey, Object... args) {
        for (CrewTransfer.Crew rider : crew) {
            if (!rider.player.hasDisconnected()) {
                rider.player.sendMessage(new TextComponentTranslation(langKey, args));
            }
        }
    }
}
