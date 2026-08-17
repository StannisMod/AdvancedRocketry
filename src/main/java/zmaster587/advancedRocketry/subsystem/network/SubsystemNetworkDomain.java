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
     * component's controllers and its full membership. The place to seed state from a console's
     * saved settings, and the place for anything a domain would otherwise recompute every tick:
     * topology is cached and capacities are not, so what depends only on WHICH BLOCKS ARE HERE
     * belongs on this side of the line.
     *
     * @param members every node of the component, whatever roles it plays, each appearing once
     */
    public void onComponentRebuilt(SubsystemNetworkState state, List<ISubsystemNetworkController> controllers,
                                   List<ISubsystemNetworkNode> members) {
    }

    /**
     * Called once per component per tick, after the solve has moved what it could and before the
     * state is published — and on the disconnected path too, because a network with nothing to
     * deliver is still a physical object.
     * <p>
     * This is where a domain whose commodity does not simply VANISH when no sink wanted it puts the
     * remainder. Shields and ventilation discard it: unclaimed shield energy stays in its generator
     * and unclaimed regeneration work is simply not done. Heat cannot — a machine does not get to
     * keep its waste heat because no radiator asked for it — so the residue becomes the pipes and
     * accumulators warming up, which is a property of the commodity rather than of the solver.
     *
     * @param members every node of the component, whatever roles it plays, each appearing once
     */
    public void onComponentTicked(SubsystemNetworkState state, List<ISubsystemNetworkNode> members) {
    }

    /** Null to stay silent; topology rebuilds are logged through it when present. */
    public Logger getLogger() {
        return null;
    }
}
