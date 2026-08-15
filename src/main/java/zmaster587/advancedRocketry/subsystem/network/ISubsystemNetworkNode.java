package zmaster587.advancedRocketry.subsystem.network;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * A block that takes part in a subsystem network: shields, ventilation, heat, turret control.
 * <p>
 * A network is a connected component over block adjacency of its own domain's nodes. Membership is
 * all this interface establishes — what a node then DOES is decided by which of
 * {@link ISubsystemSource}, {@link ISubsystemSink}, {@link ISubsystemCable} and
 * {@link ISubsystemNetworkController} it also implements. The roles are not exclusive: a store is
 * both a source and a sink, and reads as both.
 */
public interface ISubsystemNetworkNode {

    World getNodeWorld();

    BlockPos getNodePos();
}
