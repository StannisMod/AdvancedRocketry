package zmaster587.advancedRocketry.subsystem.heat;

import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkNode;

/**
 * A block that is part of the coolant loop's thermal mass — a pipe, an accumulator.
 * <p>
 * A whole connected loop is ONE thermodynamic object with one temperature, so a member does not
 * have a temperature of its own; what it has is a heat CAPACITY, and a share of the loop's energy
 * proportional to it. The share is what each block writes down, which is why it is here rather than
 * in the network state: a network has no durable name to be saved under and is rebuilt from the
 * world every time anything is placed, while a block has its position. It also makes splitting
 * physical for free — cut a loop in half and each half keeps the energy its own blocks were
 * holding, which is the same temperature on both sides.
 */
public interface IHeatNode extends ISubsystemNetworkNode {

    /**
     * How much energy this block absorbs per kelvin, in heat units per K. Summed over the loop it
     * is the {@code C} of {@code T = T_ambient + Q / C}, so it is what decides whether a reactor
     * cooks the ship in seconds or in minutes.
     */
    int getHeatCapacity();

    /** This block's share of the loop's energy, in heat units. */
    long getStoredHeat();

    void setStoredHeat(long heat);
}
