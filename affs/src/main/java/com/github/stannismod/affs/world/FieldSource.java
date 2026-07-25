package com.github.stannismod.affs.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public interface FieldSource {

    BlockPos getPos();

    int getRadius();

    /**
     * The field centre in WORLD coordinates. For a standalone field this is just the block centre; on a
     * VS ship it is the subspace centre mapped through the ship transform (§4.3), so the shell rides the
     * hull. The field geometry is a rotation-invariant sphere, so once the centre is in world space all
     * the SDF / collision math stays world-frame. Default = identity (world == field); the emitter
     * overrides it with its resolved {@link FieldFrame}.
     */
    default Vec3d getWorldCenter() {
        return new Vec3d(getPos().getX() + 0.5D, getPos().getY() + 0.5D, getPos().getZ() + 0.5D);
    }
}
