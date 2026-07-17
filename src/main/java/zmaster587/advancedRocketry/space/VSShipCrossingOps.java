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
    public void loadShips(int destDim) {
        WorldServer world = DimensionManager.getWorld(destDim);
        if (world != null) {
            VSIntegration.loadAllShips(world);
        }
    }

    @Override
    public boolean reseat(int destDim, BlockPos anchor, List<CrewTransfer.Crew> crew) {
        WorldServer world = DimensionManager.getWorld(destDim);
        return world != null && CrewTransfer.reseat(world, anchor, crew);
    }

    @Override
    public boolean teleportPoseWithRiders(int destDim, BlockPos anchor, double px, double py, double pz) {
        WorldServer world = DimensionManager.getWorld(destDim);
        if (world == null) {
            return false;
        }
        // Readiness gate: only teleport a LOADED ship (the crossing-spike criterion). Moving the
        // ShipData transform while VS is still relocating the pasted blocks into the shipyard
        // would re-map them against the moved pose; the controller simply retries next tick.
        if (VSIntegration.loadedShipCount(world) <= 0) {
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
        for (EntityDummy d : riders) {
            d.setPositionAndUpdate(d.posX + (px - sx), d.posY + (py - sy), d.posZ + (pz - sz));
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
