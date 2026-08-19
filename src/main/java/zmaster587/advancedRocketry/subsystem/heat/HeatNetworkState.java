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
    private long rejectedThisTick;
    private long pumpedOutThisTick;
    private long deliveredThisTick;
    private long pumpedInThisTick;
    private long workThisTick;
    private int exchangerCount;
    private int radiatingCells;
    /**
     * What the outside was putting into one radiating cell on the last tick, before any shield. A
     * property of WHERE the loop is rather than of the loop, and reported even when the loop has no
     * radiators to receive it — "there is a star over you and you have built nothing" is a thing a
     * player is entitled to be told.
     */
    private double incidentFluxPerCell;
    /**
     * Heat taken OUT of compartment air by the chillers breathing on it, this tick. Reported beside
     * the work and the delivery so that conservation across the air/coolant boundary is checkable
     * from ONE tick of ONE component — the same reason the coolant-to-coolant figures are all
     * reported by the cold loop.
     */
    private long airTakenThisTick;
    /**
     * Heat handed to this loop by a chiller since its last tick. Written by the COLD loop's tick and
     * read by this one, so it is an inbox rather than a statistic — a pump acts while the loop it
     * feeds is not the one being solved.
     */
    private long pumpedInPending;
    /** The chillers bolted onto this loop, worked out when it was last rebuilt. */
    private List<BlockPos> pumpPositions = Collections.emptyList();
    private List<BlockPos> sinkPositions = new java.util.ArrayList<>();
    private long sunkThisTick;

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
            heat.sinkPositions = sinkPositions;
            heat.sunkThisTick = sunkThisTick;
            heat.heatCapacity = heatCapacity;
            heat.temperatureKelvin = temperatureKelvin;
            heat.generationThisTick = generationThisTick;
            heat.emitterPositions = new ArrayList<>(emitterPositions);
            heat.rejectedThisTick = rejectedThisTick;
            heat.pumpedOutThisTick = pumpedOutThisTick;
            heat.deliveredThisTick = deliveredThisTick;
            heat.pumpedInThisTick = pumpedInThisTick;
            heat.workThisTick = workThisTick;
            heat.exchangerCount = exchangerCount;
            heat.radiatingCells = radiatingCells;
            heat.incidentFluxPerCell = incidentFluxPerCell;
            heat.airTakenThisTick = airTakenThisTick;
            heat.pumpPositions = new ArrayList<>(pumpPositions);
        }
    }

    /** Heat that left the LOOP on the last tick — the cold side of the exchange. */
    public long getRejectedThisTick() {
        return rejectedThisTick;
    }

    /** Heat a chiller took OFF this loop on the last tick — this loop was somebody's cold side. */
    public long getPumpedOutThisTick() {
        return pumpedOutThisTick;
    }

    /**
     * What the chillers drawing on this loop HANDED to their hot sides on the last tick — the hot-side
     * total, `Q + W`.
     * <p>
     * Reported by the COLD loop on purpose, beside {@link #getPumpedOutThisTick()} and
     * {@link #getWorkThisTick()}. All three come from one tick of one component, so the clause they
     * express — the hot side receives the heat plus the work — is checkable without depending on which
     * of two components the solver happened to visit first.
     */
    public long getDeliveredThisTick() {
        return deliveredThisTick;
    }

    /**
     * Heat a chiller put INTO this loop on the last tick — this loop was somebody's hot side. A second,
     * independent observation that the energy actually arrived, from the receiving end.
     */
    public long getPumpedInThisTick() {
        return pumpedInThisTick;
    }

    /** Electricity the chillers drawing on this loop paid on the last tick, in heat units. */
    public long getWorkThisTick() {
        return workThisTick;
    }

    /** A chiller hands its hot side energy out of turn; this is the inbox it lands in. */
    void addPumpedIn(long amount) {
        this.pumpedInPending += Math.max(0L, amount);
    }

    /** Drain the inbox: what arrived since this loop was last solved. */
    long takePumpedIn() {
        long pending = pumpedInPending;
        pumpedInPending = 0L;
        return pending;
    }

    /** The chillers bolted onto this loop. Re-derived whenever the loop is rebuilt. */
    /** What machines beside the loop took away for good on the last tick. */
    public long getSunkThisTick() {
        return sunkThisTick;
    }

    void setSunkThisTick(long sunk) {
        this.sunkThisTick = Math.max(0L, sunk);
    }

    public List<BlockPos> getSinkPositions() {
        return sinkPositions;
    }

    void setSinkPositions(List<BlockPos> positions) {
        this.sinkPositions = positions == null ? new java.util.ArrayList<BlockPos>() : positions;
    }

    public List<BlockPos> getPumpPositions() {
        return pumpPositions;
    }

    void setPumpPositions(List<BlockPos> positions) {
        this.pumpPositions = positions == null ? Collections.<BlockPos>emptyList() : positions;
    }

    /** How many machines on this loop can move heat out of it, working or not. */
    public int getExchangerCount() {
        return exchangerCount;
    }

    /**
     * Working surface across all of them. Read beside {@link #getExchangerCount()} this is the
     * blocked-state readout: three exchangers reporting two cells means one of them is obstructed,
     * and which one is a question for the block itself.
     */
    public int getRadiatingCells() {
        return radiatingCells;
    }

    /**
     * Energy the environment was putting into ONE radiating cell on the last tick, before the shield —
     * the single channel every outside source arrives through.
     * <p>
     * Reported unshielded on purpose: what a shield stopped is visible as the difference between this
     * and what the loop actually netted, and a readout that had already subtracted it could not tell a
     * shielded ship from one parked somewhere cold.
     */
    public double getIncidentFluxPerCell() {
        return incidentFluxPerCell;
    }

    /** Heat this loop's chillers took out of compartment air on the last tick. */
    public long getAirTakenThisTick() {
        return airTakenThisTick;
    }

    void setExchangeState(long rejectedThisTick, long pumpedOutThisTick, long deliveredThisTick,
                          long pumpedInThisTick, long workThisTick, int exchangerCount,
                          int radiatingCells, double incidentFluxPerCell, long airTakenThisTick) {
        this.rejectedThisTick = rejectedThisTick;
        this.pumpedOutThisTick = pumpedOutThisTick;
        this.deliveredThisTick = deliveredThisTick;
        this.pumpedInThisTick = pumpedInThisTick;
        this.workThisTick = workThisTick;
        this.exchangerCount = exchangerCount;
        this.radiatingCells = radiatingCells;
        this.incidentFluxPerCell = incidentFluxPerCell;
        this.airTakenThisTick = airTakenThisTick;
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
