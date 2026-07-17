package zmaster587.advancedRocketry.space;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Production {@link DescentController.PasteResolver}: works out where a descending ship's blocks may
 * be pasted in a real planet dimension. The ship pastes into clear sky ABOVE the terrain at the
 * planet's spawn (spread by lane so simultaneous descents do not overlap) and under build height —
 * a paste that clips at Y=256 breaks VS's flood-fill. Reads the ship's block geometry off its source
 * shipyard and the destination terrain top through {@link TerrainHeightFinder}. Safe {@code null}
 * (refuse the descent) when VS is absent, a world is missing, or the ship is too tall to fit.
 */
public final class VSDescentPasteResolver implements DescentController.PasteResolver {

    /** Blocks between adjacent paste lanes at a planet's spawn (simultaneous-descent spread). */
    private static final int DESCENT_LANE_STRIDE = 64;

    @Override
    public DescentController.Landing resolve(int slotDim, double[] shipWorldPos, int destPlanetDim,
                                             int laneIndex) {
        WorldServer src = DimensionManager.getWorld(slotDim);
        WorldServer dst = DimensionManager.getWorld(destPlanetDim);
        if (src == null || dst == null || shipWorldPos == null) {
            return null;
        }
        int shipHeight = VSIntegration.shipBlockHeight(
                src, shipWorldPos[0], shipWorldPos[1], shipWorldPos[2]);
        AxisAlignedBB yard = VSIntegration.shipyardBoundsAt(
                src, shipWorldPos[0], shipWorldPos[1], shipWorldPos[2]);
        if (shipHeight <= 0 || yard == null) {
            return null; // VS absent / no ship there
        }
        int width = (int) (yard.maxX - yard.minX);
        int depth = (int) (yard.maxZ - yard.minZ);

        BlockPos spawn = dst.getSpawnPoint();
        int pasteX = spawn.getX() + laneIndex * DESCENT_LANE_STRIDE;
        int pasteZ = spawn.getZ();

        int terrainTop = TerrainHeightFinder.terrainTopOfFootprint(dst, pasteX, pasteZ, width, depth);
        int pasteY = TerrainHeightFinder.pasteY(
                terrainTop, TerrainHeightFinder.CLEARANCE_BLOCKS, shipHeight);
        if (pasteY < 0) {
            return null; // the ship is too tall to fit above the terrain here
        }
        // The ship arrives above the terrain it was pasted over; the settle rigid-teleport keeps it
        // near the paste site (centred over the footprint) and carries the riders.
        double[] landingPose = {pasteX + width / 2.0, pasteY + 1.0, pasteZ + depth / 2.0};
        return new DescentController.Landing(pasteX, pasteY, pasteZ, landingPose);
    }
}
