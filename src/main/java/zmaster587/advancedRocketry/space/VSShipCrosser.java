package zmaster587.advancedRocketry.space;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Production {@link ShipTransitManager.Crosser}: carries the transit state machine's depart/arrive
 * decisions out against live worlds, using the proven per-ship crossing ({@link VSIntegration#crossShip})
 * plus {@link VSIntegration#parkShipAt}/{@link VSIntegration#unparkShipAt}. Both crossings paste into a
 * clear void column so the flood-fill re-assembly grabs only the ship (space-model §4). A safe no-op
 * (returns {@code null} - the transit aborts cleanly) when VS is absent or a world is missing.
 */
public final class VSShipCrosser implements ShipTransitManager.Crosser {

    /** Clear-sky Y the target-cell arrival pastes at (cells are void; a high column avoids any floor). */
    private static final int ARRIVAL_Y = 200;
    /** Per-lane X offset for arrivals, so ships arriving into one cell from different lanes never overlap. */
    private static final int ARRIVAL_LANE_STRIDE = 64;

    @Override
    public BlockPos departToHyperspace(int srcSlotDim, BlockPos srcAnchor, HyperspaceTiles.Tile tile) {
        WorldServer src = DimensionManager.getWorld(srcSlotDim);
        WorldServer hyper = HyperspaceWorld.getOrCreate();
        if (src == null || hyper == null || srcAnchor == null) {
            return null;
        }
        VSIntegration.CrossResult res = VSIntegration.crossShip(
                src, srcAnchor.getX() + 0.5, srcAnchor.getY() + 0.5, srcAnchor.getZ() + 0.5,
                hyper, tile.pos.getX(), tile.pos.getY(), tile.pos.getZ());
        if (!res.ok()) {
            return null;
        }
        // Park the just-assembled ship so it holds its lane while ShipTransit advances its coord logically.
        VSIntegration.parkShipAt(hyper, res.anchor.getX() + 0.5, res.anchor.getY() + 0.5, res.anchor.getZ() + 0.5);
        return res.anchor;
    }

    @Override
    public BlockPos arriveFromHyperspace(HyperspaceTiles.Tile tile, BlockPos hyperAnchor, int targetSlotDim) {
        WorldServer hyper = HyperspaceWorld.getOrCreate();
        WorldServer dst = DimensionManager.getWorld(targetSlotDim);
        if (hyper == null || dst == null || hyperAnchor == null) {
            return null;
        }
        int dstX = tile.index * ARRIVAL_LANE_STRIDE;
        VSIntegration.CrossResult res = VSIntegration.crossShip(
                hyper, hyperAnchor.getX() + 0.5, hyperAnchor.getY() + 0.5, hyperAnchor.getZ() + 0.5,
                dst, dstX, ARRIVAL_Y, 0);
        if (!res.ok()) {
            return null;
        }
        // Unpark: hand the ship back to free VS physics now that it is in the destination bubble.
        VSIntegration.unparkShipAt(dst, res.anchor.getX() + 0.5, res.anchor.getY() + 0.5, res.anchor.getZ() + 0.5);
        return res.anchor;
    }
}
