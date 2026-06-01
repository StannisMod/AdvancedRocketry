package zmaster587.advancedRocketry.world.util;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.World;

public class TeleporterSeekBlock extends BasicTeleporter {
    public TeleporterSeekBlock(BlockPos targetPos) {
        super(targetPos);
    }

    @Override
    protected BlockPos getTargetPos(World world) {
        BlockPos pos = super.getTargetPos(world);
        MutableBlockPos clearPos = new MutableBlockPos(pos);

        for (int yy = pos.getY(); yy < world.getHeight(); yy++) {
            clearPos.setPos(pos.getX(), yy, pos.getZ());
            if (world.isAirBlock(clearPos) && world.isAirBlock(clearPos.add(0, 1, 0))) {
                return clearPos;
            }
        }
        return pos;
    }
}
