package zmaster587.advancedRocketry.space;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Production {@link DescentController.PasteResolver}: works out where a descending ship arrives in a
 * real planet dimension.
 *
 * <p>A descent does <b>not</b> look for a landing site on the ground. The ship materializes HIGH IN
 * THE AIR over the destination and the pilot flies it down himself — he may never need to touch the
 * surface at all. That removes the whole "does the ship fit above the terrain here" question: there
 * is no terrain query, no footprint fit, and therefore no way for a descent to be refused because a
 * particular column was occupied.</p>
 *
 * <p>The arrival is a two-part move, exactly as the entry on-ramp does it in reverse: a VS ship's
 * BLOCKS live in its subspace shipyard and must be pasted inside the vanilla block band, while its
 * POSE is an ordinary world-frame value that is not capped by the build height. So the blocks go
 * into a legal band just under {@link TerrainHeightFinder#MAX_BUILD_Y} (lane-strided across the
 * destination's spawn column, so simultaneous descents cannot overlap), and the settle
 * rigid-teleports the re-assembled ship's pose — carrying its riders — up to
 * {@link #arrivalAltitude}.</p>
 *
 * <p>Safe {@code null} (refuse the descent) only when a world is missing, VS cannot find the ship, or
 * the ship is literally taller than the world's block band.</p>
 */
public final class VSDescentPasteResolver implements DescentController.PasteResolver {

    private static final Logger LOGGER = LogManager.getLogger("advancedrocketry/space");

    /** Blocks between adjacent paste lanes at a planet's spawn (simultaneous-descent spread). */
    private static final int DESCENT_LANE_STRIDE = 64;

    /**
     * Vertical hysteresis, in blocks, between a descent arrival and the two altitudes it must stay
     * clear of. {@code tunable}.
     *
     * <p>BELOW: the physics mod clamps every ship's pose to a hard ceiling
     * ({@link VSIntegration#shipYPositionMaximum()}) each physics step, and the entry on-ramp fires
     * the moment a piloted planet-side ship climbs past
     * {@link ShipEntryController#effectiveEntryCeiling}. An arrival at or above either would be
     * pinned by the clamp, or would bounce the ship straight back into space on the tick it landed.
     * ABOVE: the destination's blocks stop at the build height, so an arrival must clear that band by
     * the same margin or the ship can materialize inside a mountain.</p>
     */
    public static final int ARRIVAL_HYSTERESIS_BLOCKS = 100;

    /**
     * The world-frame Y a descending ship arrives at over a destination whose orbit line is
     * {@code orbitHeight} and whose physics pose clamp is {@code physicsCeiling}: as high as the
     * destination allows, minus {@link #ARRIVAL_HYSTERESIS_BLOCKS}, and never inside the block band.
     *
     * <p>The upper bound is deliberately the ENTRY line rather than the raw physics clamp. The two
     * are not interchangeable here: the space subsystem raises the physics clamp to cover the whole
     * realized cell pose band (megablocks up) at server start, so "the clamp minus a hundred" is
     * millions of blocks above a planet — unreachable by any pilot, and far above the altitude at
     * which the entry on-ramp would immediately take the ship back off the planet. Deriving from the
     * entry line keeps the arrival below BOTH (the entry line is itself capped below the clamp), so
     * this is the stricter of the two readings, not a relaxation.</p>
     *
     * <p>Pure, so the arrival geometry is unit-testable without a world.</p>
     */
    public static double arrivalAltitude(int orbitHeight, double physicsCeiling) {
        double belowCeiling = ShipEntryController.effectiveEntryCeiling(orbitHeight, physicsCeiling)
                - ARRIVAL_HYSTERESIS_BLOCKS;
        double aboveBlocks = TerrainHeightFinder.MAX_BUILD_Y + ARRIVAL_HYSTERESIS_BLOCKS;
        return Math.max(belowCeiling, aboveBlocks);
    }

    @Override
    public DescentController.Landing resolve(int slotDim, double[] shipWorldPos, int destPlanetDim,
                                             int laneIndex) {
        WorldServer src = DimensionManager.getWorld(slotDim);
        WorldServer dst = DimensionManager.getWorld(destPlanetDim);
        if (src == null || dst == null || shipWorldPos == null) {
            LOGGER.warn("[SPACE] descent unresolved: slotDim={} (world {}), destDim={} (world {}), "
                            + "shipPos={}", slotDim, src != null, destPlanetDim, dst != null,
                    shipWorldPos == null ? "null" : "present");
            return null;
        }
        int shipHeight = VSIntegration.shipBlockHeight(
                src, shipWorldPos[0], shipWorldPos[1], shipWorldPos[2]);
        AxisAlignedBB yard = VSIntegration.shipyardBoundsAt(
                src, shipWorldPos[0], shipWorldPos[1], shipWorldPos[2]);
        if (shipHeight <= 0 || yard == null) {
            LOGGER.warn("[SPACE] descent unresolved: no physics ship at {},{},{} in dim {} "
                            + "(shipHeight={}, shipyard={})", shipWorldPos[0], shipWorldPos[1],
                    shipWorldPos[2], slotDim, shipHeight, yard);
            return null; // VS absent / no ship there
        }
        int width = (int) (yard.maxX - yard.minX);
        int depth = (int) (yard.maxZ - yard.minZ);

        BlockPos spawn = dst.getSpawnPoint();
        int pasteX = spawn.getX() + laneIndex * DESCENT_LANE_STRIDE;
        int pasteZ = spawn.getZ();

        // Paste the BLOCKS as high as the vanilla block band allows. This is not a landing site — the
        // ship is lifted off it a moment later by the pose teleport — so the only requirements are
        // that the band is legal (a paste clipping at Y=256 splits VS's FIND_ALL_BLOCKS flood-fill)
        // and that it is clear sky (the re-assembly anchors on the first non-air block in the
        // footprint, which must be a ship block and never the destination's terrain). Hugging the
        // ceiling satisfies both without asking the world anything.
        int pasteY = TerrainHeightFinder.MAX_BUILD_Y - shipHeight;
        if (pasteY < 0) {
            LOGGER.warn("[SPACE] descent unresolved: ship is {} blocks tall, taller than dim {}'s "
                            + "whole block band ({})", shipHeight, destPlanetDim,
                    TerrainHeightFinder.MAX_BUILD_Y);
            return null;
        }

        DimensionProperties props = zmaster587.advancedRocketry.dimension.DimensionManager.getInstance()
                .getDimensionProperties(destPlanetDim);
        int orbitHeight = props != null ? props.getOrbitHeight()
                : ARConfiguration.getCurrentConfig().orbit;
        double arrivalY = arrivalAltitude(orbitHeight, VSIntegration.shipYPositionMaximum());

        // The ship arrives in the air over the paste column, centred on its own footprint, and flies
        // down under its pilot's hand from there.
        double[] landingPose = {pasteX + width / 2.0, arrivalY, pasteZ + depth / 2.0};
        LOGGER.info("[SPACE] descent resolved for dim {}: lane={} paste={},{},{} shipHeight={} "
                        + "shipyard=[{},{}]x[{},{}] orbitHeight={} physicsCeiling={} arrivalY={}",
                destPlanetDim, laneIndex, pasteX, pasteY, pasteZ, shipHeight,
                (int) yard.minX, (int) yard.maxX, (int) yard.minZ, (int) yard.maxZ,
                orbitHeight, VSIntegration.shipYPositionMaximum(), arrivalY);
        return new DescentController.Landing(pasteX, pasteY, pasteZ, landingPose);
    }
}
