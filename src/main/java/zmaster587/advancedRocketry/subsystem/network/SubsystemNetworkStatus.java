package zmaster587.advancedRocketry.subsystem.network;

/**
 * Why the network is delivering what it delivers — the one thing a player needs before deciding
 * whether to build another generator, another consumer, or another line.
 * <p>
 * Deliberately int constants rather than an enum: these values are already on the wire and in
 * saved console state.
 */
public final class SubsystemNetworkStatus {

    /** Nothing to solve: no source, or no sink. */
    public static final int DISCONNECTED = 1;
    /** Demand exceeds what the sources can give — build generation. */
    public static final int SOURCE_LIMITED = 2;
    /** Supply exceeds demand — the network is idling, not straining. */
    public static final int SINK_LIMITED = 3;
    /** Both ends could do more; the lines between them cannot — build another route. */
    public static final int CABLE_LIMITED = 4;
    /** Supply, demand and transport all meet. */
    public static final int BALANCED = 5;

    private SubsystemNetworkStatus() {
    }
}
