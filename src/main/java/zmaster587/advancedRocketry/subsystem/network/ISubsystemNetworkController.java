package zmaster587.advancedRocketry.subsystem.network;

/**
 * A console: it reads and edits the network's state without carrying any of the commodity.
 * <p>
 * Controllers are stateless editors — the network state is the single source of truth, so two
 * consoles on one network cannot disagree, they can only both be looking at it.
 */
public interface ISubsystemNetworkController extends ISubsystemNetworkNode {

    void applyNetworkState(SubsystemNetworkState state);
}
