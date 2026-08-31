package zmaster587.advancedRocketry.subsystem.network;

/**
 * A node the network can take the commodity FROM.
 * <p>
 * The commodity is an integer per tick and the network never learns what it measures — shield
 * energy, air exchange, heat. Its unit belongs to the {@link SubsystemNetworkDomain}, and every
 * node in one domain must speak the same one.
 */
public interface ISubsystemSource extends ISubsystemNetworkNode {

    /** How much this node can give up this tick. */
    int getAvailable();

    /** Take the amount the solve settled on; returns what was actually taken. */
    int extract(int amount);

    /**
     * What this node PRODUCES per tick, as opposed to what it currently holds.
     * <p>
     * A generator's production and its buffer are different quantities, and a readout that shows a
     * full buffer cannot tell a running plant from a stopped one with a full tank. Defaults to the
     * available amount for nodes where the distinction does not exist.
     */
    default int getGenerationPerTick() {
        return Math.max(0, getAvailable());
    }
}
