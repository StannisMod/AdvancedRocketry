package com.github.stannismod.affs.world.shield;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public interface IShieldNetworkNode {

    World getNodeWorld();

    BlockPos getNodePos();
}
