package zmaster587.advancedRocketry.subsystem.hull;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * "Are the next few blocks along my facing empty?" — asked by everything that has to point out of a
 * hull: an airlock throwing matter overboard, a radiator that must see open space to shed into.
 * <p>
 * <b>It needs no frame conversion, and that is the interesting part.</b> A ship's blocks live in a
 * stationary subspace and that is exactly where the world stores them, so this is ordinary block
 * arithmetic and it stays correct on a ship that is flying and turning. Only something that leaves
 * the hull as an ENTITY has to cross into the world frame.
 */
public final class HullClearance {

    private HullClearance() {
    }

    /**
     * How far along {@code facing} the first obstruction sits, or 0 when the way is clear.
     * <p>
     * A DISTANCE rather than a boolean, because a blocked exit is something a player has to go and
     * find: "blocked at 2" sends them to the right block, "blocked" sends them around the ship. Air
     * and replaceable blocks (grass, snow) do not count — a machine that stopped working because of
     * a snow layer would read as broken rather than as obstructed.
     */
    public static int obstructionDistance(World world, BlockPos pos, EnumFacing facing, int clearance) {
        if (world == null || pos == null || facing == null) {
            return 1;
        }
        for (int step = 1; step <= Math.max(1, clearance); step++) {
            BlockPos ahead = pos.offset(facing, step);
            if (!world.isAirBlock(ahead)
                    && !world.getBlockState(ahead).getBlock().isReplaceable(world, ahead)) {
                return step;
            }
        }
        return 0;
    }
}
