package zmaster587.advancedRocketry.world.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ITeleporter;

public class BasicTeleporter implements ITeleporter {
    private final BlockPos basePos;

    public BasicTeleporter(BlockPos basePos) {
        this.basePos = basePos;
    }

    protected BlockPos getTargetPos(World world) {
        return basePos;
    }

    @Override
    public void placeEntity(World world, Entity entity, float yaw) {
        entity.moveToBlockPosAndAngles(getTargetPos(world), yaw, entity.rotationPitch);
    }
}
