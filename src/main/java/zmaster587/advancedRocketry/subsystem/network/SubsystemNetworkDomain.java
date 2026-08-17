package zmaster587.advancedRocketry.subsystem.network;

import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * One commodity, and the identity that keeps it apart from the others.
 * <p>
 * Domains do not merge: a shield cable and a ventilation duct may be laid through the same wall and
 * will never see each other, because the graph is built per domain. The domain also owns the UNIT —
 * the network solves in whole integers per tick and never learns whether they are joules, litres of
 * air exchange or watts of heat.
 * <p>
 * Everything else here is a hook a domain MAY override; a domain that overrides nothing gets the
 * plain behaviour, which is the point of the primitive.
 */
public abstract class SubsystemNetworkDomain {

    private final String name;

    protected SubsystemNetworkDomain(String name) {
        this.name = name;
    }

    /** For logs and readouts. Not persisted anywhere. */
    public final String getName() {
        return name;
    }

    /**
     * A fresh state object. Override to attach domain settings a console can edit — the state is
     * where they belong, because it is the thing that survives a topology rebuild.
     */
    public SubsystemNetworkState newState() {
        return new SubsystemNetworkState();
    }

    /**
     * Called once per component after its membership is rebuilt and before it is solved, with that
     * component's controllers. The place to seed state from a console's saved settings.
     */
    public void onComponentRebuilt(SubsystemNetworkState state, List<ISubsystemNetworkController> controllers) {
    }

    /** Null to stay silent; topology rebuilds are logged through it when present. */
    public Logger getLogger() {
        return null;
    }
}
