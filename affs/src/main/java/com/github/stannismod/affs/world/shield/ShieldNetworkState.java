package com.github.stannismod.affs.world.shield;

import com.github.stannismod.affs.config.ModConfig;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

public final class ShieldNetworkState {

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
    private double shieldEnergyResistanceBias = ModConfig.shieldEnergyResistanceBias;

    public ShieldNetworkState copy() {
        ShieldNetworkState copy = new ShieldNetworkState();
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
        copy.shieldEnergyResistanceBias = shieldEnergyResistanceBias;
        return copy;
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

    public int getBottleneckUtilizationPermille() {
        return bottleneckUtilizationPermille;
    }

    public double getShieldEnergyResistanceBias() {
        return shieldEnergyResistanceBias;
    }

    public void setShieldEnergyResistanceBias(double shieldEnergyResistanceBias) {
        this.shieldEnergyResistanceBias = clamp01(shieldEnergyResistanceBias);
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

    private static double clamp01(double value) {
        if (value < 0.0D) {
            return 0.0D;
        }
        if (value > 1.0D) {
            return 1.0D;
        }
        return value;
    }
}
