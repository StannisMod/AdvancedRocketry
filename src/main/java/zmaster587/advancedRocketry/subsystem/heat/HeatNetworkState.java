package zmaster587.advancedRocketry.subsystem.heat;

import net.minecraft.util.math.BlockPos;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one coolant loop is, thermodynamically: one energy, one capacity, one temperature.
 * <p>
 * There is no per-pipe temperature and no ship-wide one either — a loop is the object that has a
 * temperature, and everything a player sees about heat inside the ship is read off this. The three
 * numbers here are a READOUT recomputed from the loop's blocks every tick, never the record: the
 * energy is written down block by block ({@link IHeatNode}), because a network is rebuilt from the
 * world whenever anything is placed and has no name of its own to be saved under. That is also why
 * copying this state when a loop splits creates nothing — the copy is overwritten from the members
 * on the next tick, and the members are the two halves of what there was.
 */
public class HeatNetworkState extends SubsystemNetworkState {

    private long storedHeat;
    private long heatCapacity;
    private double temperatureKelvin;
    private int generationThisTick;
    /**
     * The machines this loop touches, worked out when the loop was last rebuilt. Positions and not
     * tile references: a position survives a tile being recreated by a chunk reload, a reference
     * does not.
     */
    private List<BlockPos> emitterPositions = Collections.emptyList();

    @Override
    public SubsystemNetworkState copy() {
        HeatNetworkState copy = new HeatNetworkState();
        copyInto(copy);
        return copy;
    }

    @Override
    protected void copyInto(SubsystemNetworkState target) {
        super.copyInto(target);
        if (target instanceof HeatNetworkState) {
            HeatNetworkState heat = (HeatNetworkState) target;
            heat.storedHeat = storedHeat;
            heat.heatCapacity = heatCapacity;
            heat.temperatureKelvin = temperatureKelvin;
            heat.generationThisTick = generationThisTick;
            heat.emitterPositions = new ArrayList<>(emitterPositions);
        }
    }

    /** The machines this loop touches. Re-derived whenever the loop is rebuilt. */
    public List<BlockPos> getEmitterPositions() {
        return emitterPositions;
    }

    void setEmitterPositions(List<BlockPos> positions) {
        this.emitterPositions = positions == null ? Collections.<BlockPos>emptyList() : positions;
    }

    /** Energy the loop is holding, in heat units. */
    public long getStoredHeat() {
        return storedHeat;
    }

    /** Summed capacity of every block in the loop, in heat units per kelvin. */
    public long getHeatCapacity() {
        return heatCapacity;
    }

    /** The loop's temperature in kelvin — ambient when it holds nothing. */
    public double getTemperatureKelvin() {
        return temperatureKelvin;
    }

    /** Waste heat the loop picked up from the machines around it on the last tick. */
    public int getGenerationThisTick() {
        return generationThisTick;
    }

    void setThermalState(long storedHeat, long heatCapacity, double temperatureKelvin, int generationThisTick) {
        this.storedHeat = storedHeat;
        this.heatCapacity = heatCapacity;
        this.temperatureKelvin = temperatureKelvin;
        this.generationThisTick = generationThisTick;
    }
}
