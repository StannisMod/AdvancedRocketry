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

    /**
     * Which commodity this node deals in. A node states its own domain rather than being told one
     * at registration, so a block cannot end up in a graph it does not belong to, and so anything
     * holding a node — a cable deciding whether to connect to its neighbour, a readout walking the
     * world — can ask without a per-domain marker interface to test against.
     */
    SubsystemNetworkDomain getNetworkDomain();

    World getNodeWorld();

    BlockPos getNodePos();
}
