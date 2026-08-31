package zmaster587.advancedRocketry.subsystem.network;

/**
 * A transport node: it neither produces nor consumes, it only limits.
 * <p>
 * Cables are a scaling and reach tool, not a requirement — two adjacent nodes form a network with
 * no cable between them, and that link is unthrottled. A cable is the only place a finite capacity
 * enters the graph, which is what makes "add another line" a meaningful build decision.
 */
public interface ISubsystemCable extends ISubsystemNetworkNode {

    /** The most this cable will carry in one tick. */
    int getThroughputPerTick();

    /** Report what actually went through, after the solve. */
    void addTransferred(int amount);

    /**
     * The network's own report, delivered to a cable that wants to display it (a readout block, a
     * console face). No-op by default: most cables are pipe.
     */
    default void onNetworkStats(SubsystemNetworkState state) {
    }
}
