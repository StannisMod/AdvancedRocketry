package zmaster587.advancedRocketry.subsystem.network;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * What one network knows about itself: who is in it, and what last tick's solve found.
 * <p>
 * This object is the network's single source of truth. Consoles edit it and read it; they never
 * hold a private copy, so two consoles on one network cannot disagree. It survives a topology
 * rebuild by being re-attached to the component that inherited its members, which is what lets a
 * player's settings live through breaking and re-laying a cable.
 * <p>
 * Every quantity here is in the domain's commodity unit per tick; the network itself never learns
 * what that unit measures.
 */
public class SubsystemNetworkState {

    private BlockPos root = BlockPos.ORIGIN;
    private final Set<BlockPos> memberPositions = new HashSet<>();
    private boolean connected;
    private int status;
    private int cableCount;
    private int sourceCount;
    private int sinkCount;
    private int sourceAvailable;
    private int sinkRequested;
    private int cableCapacity;
    private int deliveredFlow;
    private int saturatedCables;
    private int generationPerTick;
    private int consumptionPerTick;
    private BlockPos bottleneck = BlockPos.ORIGIN;
    private int bottleneckUtilizationPermille;

    /**
     * A fresh state of the same concrete class, for a domain that has subclassed this one. The
     * default keeps subclass-specific settings intact by copying through {@link #copyInto}.
     */
    public SubsystemNetworkState copy() {
        SubsystemNetworkState copy = new SubsystemNetworkState();
        copyInto(copy);
        return copy;
    }

    /** Copies every field of THIS class into the target; a subclass overrides to add its own. */
    protected void copyInto(SubsystemNetworkState copy) {
        copy.root = root;
        copy.memberPositions.addAll(memberPositions);
        copy.connected = connected;
        copy.status = status;
        copy.cableCount = cableCount;
        copy.sourceCount = sourceCount;
        copy.sinkCount = sinkCount;
        copy.sourceAvailable = sourceAvailable;
        copy.sinkRequested = sinkRequested;
        copy.cableCapacity = cableCapacity;
        copy.deliveredFlow = deliveredFlow;
        copy.saturatedCables = saturatedCables;
        copy.generationPerTick = generationPerTick;
        copy.consumptionPerTick = consumptionPerTick;
        copy.bottleneck = bottleneck;
        copy.bottleneckUtilizationPermille = bottleneckUtilizationPermille;
    }

    public void clearMembers() {
        memberPositions.clear();
    }

    public void addMember(BlockPos pos) {
        if (pos != null) {
            memberPositions.add(pos);
        }
    }

    public BlockPos getRoot() {
        return root;
    }

    public void setRoot(BlockPos root) {
        this.root = root == null ? BlockPos.ORIGIN : root;
    }

    public boolean isConnected() {
        return connected;
    }

    /** One of {@link SubsystemNetworkStatus}. */
    public int getStatus() {
        return status;
    }

    public int getCableCount() {
        return cableCount;
    }

    public int getSourceCount() {
        return sourceCount;
    }

    public int getSinkCount() {
        return sinkCount;
    }

    public int getSourceAvailable() {
        return sourceAvailable;
    }

    public int getSinkRequested() {
        return sinkRequested;
    }

    public int getCableCapacity() {
        return cableCapacity;
    }

    public int getDeliveredFlow() {
        return deliveredFlow;
    }

    public int getSaturatedCables() {
        return saturatedCables;
    }

    public int getGenerationPerTick() {
        return generationPerTick;
    }

    public int getConsumptionPerTick() {
        return consumptionPerTick;
    }

    public BlockPos getBottleneck() {
        return bottleneck;
    }

    public int getBottleneckUtilizationPermille() {
        return bottleneckUtilizationPermille;
    }

    public Set<BlockPos> getMemberPositions() {
        return new HashSet<>(memberPositions);
    }

    public void setStatistics(boolean connected, int status, BlockPos root, int cableCount, int sourceCount, int sinkCount,
                              int sourceAvailable, int sinkRequested, int cableCapacity, int deliveredFlow,
                              int saturatedCables, BlockPos bottleneck, int bottleneckUtilizationPermille,
                              int generationPerTick, int consumptionPerTick) {
        this.connected = connected;
        this.status = status;
        this.root = root == null ? BlockPos.ORIGIN : root;
        this.cableCount = cableCount;
        this.sourceCount = sourceCount;
        this.sinkCount = sinkCount;
        this.sourceAvailable = sourceAvailable;
        this.sinkRequested = sinkRequested;
        this.cableCapacity = cableCapacity;
        this.deliveredFlow = deliveredFlow;
        this.saturatedCables = saturatedCables;
        this.bottleneck = bottleneck == null ? BlockPos.ORIGIN : bottleneck;
        this.bottleneckUtilizationPermille = bottleneckUtilizationPermille;
        this.generationPerTick = generationPerTick;
        this.consumptionPerTick = consumptionPerTick;
    }
}
