package zmaster587.advancedRocketry.api.capability;

/**
 * A machine that TAKES heat off a coolant loop and puts it somewhere that is not the loop.
 *
 * <p>The mirror of {@link IHeatEmitter}, and deliberately the same shape read backwards: the loop
 * asks how much this machine wants and then hands over what it can, so a sink standing between two
 * loops is fed by both instead of being counted twice. The loop decides how much moves, never the
 * machine - the same asymmetry a radiator lives under, and for the same reason.</p>
 *
 * <p>A capability rather than a plain interface for the reason the emitter is one: the machines this
 * system exists to serve mostly belong to other mods, and a mod that wants to soak a ship's heat
 * into something of its own can be given this without either side importing the other.</p>
 */
public interface IHeatSink {

    /**
     * How much heat this machine would take this tick, given how hot the loop offering it is.
     *
     * <p>The temperature is an ARGUMENT because a sink is allowed to be conditional - the emergency
     * dump only runs when the ship is already losing, which is what keeps it an emergency rather than
     * a cooling system. A sink that wants nothing answers zero and the loop moves nothing.</p>
     */
    long getSinkRequestPerTick(double loopKelvin);

    /** Take up to {@code amount}; returns what was actually absorbed, which is what the loop loses. */
    long acceptHeat(long amount);
}
