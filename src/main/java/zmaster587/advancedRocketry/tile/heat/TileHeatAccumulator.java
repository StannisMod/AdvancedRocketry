package zmaster587.advancedRocketry.tile.heat;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.subsystem.heat.HeatNetwork;

/**
 * A block of thermal mass clipped onto a coolant loop: nothing but somewhere for a burst to go.
 * <p>
 * A hyperjump makes far more heat in a moment than any radiator can shed in one, so the thing that
 * survives it is not a bigger radiator but a slower rise. That is what this block buys, and it is
 * why it is deliberately dumb — no power, no controls, no upkeep. It is also what a ship spends to
 * run silent: with the sinks shut, how long the crew has is exactly how much mass they built.
 * <p>
 * It is not a better pipe. It carries what a pipe carries, so putting one inline neither speeds a
 * line up nor throttles it.
 */
public class TileHeatAccumulator extends TileHeatLoopBlock {

    @Override
    public int getHeatCapacity() {
        if (!HeatNetwork.enabled())
            return 0;
        return Math.max(0, ARConfiguration.getCurrentConfig().shipHeatAccumulatorCapacity);
    }
}
