package zmaster587.advancedRocketry.subsystem.network;

/**
 * A node the network delivers the commodity TO.
 */
public interface ISubsystemSink extends ISubsystemNetworkNode {

    /** How much this node is asking for this tick. */
    int getRequested();

    /** How much it could still hold, for consumers that buffer. */
    int getFreeCapacity();

    /** Take delivery of the amount the solve settled on; returns what was actually accepted. */
    int receive(int amount);

    /**
     * Redistribution priority. Under a deficit the network satisfies higher priorities first, so a
     * player can pour a starved supply into what matters ("all power to the rear shields", "keep
     * the bridge breathable"). Equal priority shares what is left. Default 0 = normal; a bulk store
     * keeps the default so real consumers, when raised, out-rank it.
     */
    default int getPriority() {
        return 0;
    }

    /**
     * What this node CONSUMES per tick, as opposed to what it is currently asking for. A consumer
     * topping up a buffer requests far more than it burns, and a readout that cannot tell them
     * apart reads a healthy network as overloaded.
     */
    default int getConsumptionPerTick() {
        return Math.max(0, getRequested());
    }
}
