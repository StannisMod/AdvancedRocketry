package com.github.stannismod.affs.te;

import com.github.stannismod.affs.config.ModConfig;
import com.github.stannismod.affs.gui.NetworkMapMarker;
import com.github.stannismod.affs.util.CodeUtils;
import com.github.stannismod.affs.world.contour.ContourFrameGeometry;
import com.github.stannismod.affs.world.shield.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TileEntityShieldConsole extends TileEntity implements ITickable, IShieldNetworkController, com.github.stannismod.affs.gui.INetworkMapSource {

    private static final int CLIENT_SYNC_BASE_INTERVAL_TICKS = 20;
    private static final int CLIENT_SYNC_JITTER_TICKS = 10;

    private String networkCode = "";
    private boolean networkConnected = false;
    private int networkStatus = 0;
    private int cableCount = 0;
    private int generatorCount = 0;
    private int injectorCount = 0;
    private int sourceAvailable = 0;
    private int sinkRequested = 0;
    private int cableCapacity = 0;
    private int deliveredFlow = 0;
    private int saturatedCables = 0;
    private int generationPerTick = 0;
    private int consumptionPerTick = 0;
    private int bottleneckUtilizationPermille = 0;
    private double shieldEnergyResistanceBias = ModConfig.shieldEnergyResistanceBias;
    private int rootX = 0;
    private int rootY = 0;
    private int rootZ = 0;
    private final List<NetworkMapMarker> mapMarkers = new ArrayList<>();
    private int clientSyncCountdown = -1;
    private boolean clientSyncQueued = false;

    @Override
    public void update() {
        if (world == null) {
            return;
        }

        if (world.isRemote) {
            return;
        }

        ShieldNetworkState state = ShieldNetworkManager.getState(world, pos);
        if (state != null) {
            if (state.getNetworkCode().isEmpty() && !networkCode.isEmpty()) {
                ShieldNetworkManager.setNetworkCode(world, pos, networkCode);
                state = ShieldNetworkManager.getState(world, pos);
            }
            applyNetworkState(state);
        } else {
            applyDisconnectedState();
        }
        rebuildMapSnapshot(state);
        tickClientSync();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote) {
            ShieldNetworkRegistry.register(this);
            ShieldNetworkManager.markDirty(world);
            if (!networkCode.isEmpty()) {
                ShieldNetworkManager.setNetworkCode(world, pos, networkCode);
            }
        }
    }

    @Override
    public void invalidate() {
        if (world != null && !world.isRemote) {
            ShieldNetworkRegistry.unregister(this);
            ShieldNetworkManager.markDirty(world);
        }
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        if (world != null && !world.isRemote) {
            ShieldNetworkRegistry.unregister(this);
            ShieldNetworkManager.markDirty(world);
        }
        super.onChunkUnload();
    }

    @Override
    public BlockPos getNodePos() {
        return pos;
    }

    @Override
    public net.minecraft.world.World getNodeWorld() {
        return world;
    }

    @Override
    public void applyNetworkState(ShieldNetworkState state) {
        if (world == null || world.isRemote || state == null) {
            return;
        }

        boolean changed = networkConnected != state.isConnected()
                || networkStatus != state.getStatus()
                || cableCount != state.getCableCount()
                || generatorCount != state.getSourceCount()
                || injectorCount != state.getSinkCount()
                || sourceAvailable != state.getSourceAvailable()
                || sinkRequested != state.getSinkRequested()
                || cableCapacity != state.getCableCapacity()
                || deliveredFlow != state.getDeliveredFlow()
                || saturatedCables != state.getSaturatedCables()
                || generationPerTick != state.getGenerationPerTick()
                || consumptionPerTick != state.getConsumptionPerTick()
                || bottleneckUtilizationPermille != state.getBottleneckUtilizationPermille()
                || Double.compare(shieldEnergyResistanceBias, state.getShieldEnergyResistanceBias()) != 0
                || rootX != state.getRoot().getX()
                || rootY != state.getRoot().getY()
                || rootZ != state.getRoot().getZ()
                || !networkCode.equals(state.getNetworkCode());

        networkConnected = state.isConnected();
        networkStatus = state.getStatus();
        cableCount = state.getCableCount();
        generatorCount = state.getSourceCount();
        injectorCount = state.getSinkCount();
        sourceAvailable = state.getSourceAvailable();
        sinkRequested = state.getSinkRequested();
        cableCapacity = state.getCableCapacity();
        deliveredFlow = state.getDeliveredFlow();
        saturatedCables = state.getSaturatedCables();
        generationPerTick = state.getGenerationPerTick();
        consumptionPerTick = state.getConsumptionPerTick();
        bottleneckUtilizationPermille = state.getBottleneckUtilizationPermille();
        shieldEnergyResistanceBias = state.getShieldEnergyResistanceBias();
        rootX = state.getRoot().getX();
        rootY = state.getRoot().getY();
        rootZ = state.getRoot().getZ();
        networkCode = state.getNetworkCode();

        if (changed) {
            markDirty();
            queueClientSync();
        }
    }

    private void applyDisconnectedState() {
        boolean changed = networkConnected
                || networkStatus != 0
                || cableCount != 0
                || generatorCount != 0
                || injectorCount != 0
                || sourceAvailable != 0
                || sinkRequested != 0
                || cableCapacity != 0
                || deliveredFlow != 0
                || saturatedCables != 0
                || generationPerTick != 0
                || consumptionPerTick != 0
                || bottleneckUtilizationPermille != 0
                || Double.compare(shieldEnergyResistanceBias, ModConfig.shieldEnergyResistanceBias) != 0
                || rootX != 0
                || rootY != 0
                || rootZ != 0;

        networkConnected = false;
        networkStatus = 0;
        cableCount = 0;
        generatorCount = 0;
        injectorCount = 0;
        sourceAvailable = 0;
        sinkRequested = 0;
        cableCapacity = 0;
        deliveredFlow = 0;
        saturatedCables = 0;
        generationPerTick = 0;
        consumptionPerTick = 0;
        bottleneckUtilizationPermille = 0;
        shieldEnergyResistanceBias = ModConfig.shieldEnergyResistanceBias;
        rootX = 0;
        rootY = 0;
        rootZ = 0;

        if (changed) {
            markDirty();
            queueClientSync();
        }
    }

    @Override
    public String getNetworkCode() {
        return networkCode;
    }

    public void applyNetworkCode(String code) {
        String normalized = CodeUtils.normalize(code);
        if (normalized.equals(networkCode)) {
            return;
        }
        networkCode = normalized;
        markDirty();
        queueClientSync();
    }

    public String getRootString() {
        return rootX + ", " + rootY + ", " + rootZ;
    }

    public int getGeneratorCount() {
        return generatorCount;
    }

    public int getInjectorCount() {
        return injectorCount;
    }

    public int getGenerationPerTick() {
        return generationPerTick;
    }

    public int getConsumptionPerTick() {
        return consumptionPerTick;
    }

    public String getNetworkStatusText() {
        switch (networkStatus) {
            case 1:
                return "disconnected";
            case 2:
                return "source-limited";
            case 3:
                return "sink-limited";
            case 4:
                return "cable-limited";
            case 5:
                return "balanced";
            default:
                return networkConnected ? "unknown" : "disconnected";
        }
    }

    public int getCableCount() {
        return cableCount;
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

    public String getBottleneckUtilizationText() {
        return (bottleneckUtilizationPermille / 10.0D) + "%";
    }

    @Override
    public double getShieldEnergyResistanceBias() {
        return shieldEnergyResistanceBias;
    }

    public String getShieldEnergyResistanceText() {
        double energy = (1.0D - shieldEnergyResistanceBias) * 100.0D;
        double physical = shieldEnergyResistanceBias * 100.0D;
        return Math.round(energy) + "% / " + Math.round(physical) + "%";
    }

    public void applyShieldEnergyResistanceBias(double value) {
        double clamped = value < 0.0D ? 0.0D : value > 1.0D ? 1.0D : value;
        if (Double.compare(shieldEnergyResistanceBias, clamped) == 0) {
            return;
        }
        shieldEnergyResistanceBias = clamped;
        markDirty();
        if (world != null && !world.isRemote) {
            ShieldNetworkManager.setShieldEnergyResistanceBias(world, pos, clamped);
            ShieldNetworkManager.markDirty(world);
        }
        queueClientSync();
    }

    public List<NetworkMapMarker> getMapMarkers() {
        return Collections.unmodifiableList(mapMarkers);
    }

    private void rebuildMapSnapshot(@Nullable ShieldNetworkState state) {
        List<NetworkMapMarker> nextMarkers = new ArrayList<>();

        if (state != null && world != null) {
            for (BlockPos memberPos : state.getMemberPositions()) {
                TileEntity member = world.getTileEntity(memberPos);
                if (member instanceof TileEntityFieldGenerator) {
                    TileEntityFieldGenerator fieldGenerator = (TileEntityFieldGenerator) member;
                    int radius = fieldGenerator.getRadius();
                    putMarker(nextMarkers, NetworkMapMarker.createArea(
                            memberPos.getX() - radius,
                            memberPos.getY() - radius,
                            memberPos.getZ() - radius,
                            memberPos.getX() + radius,
                            memberPos.getY() + radius,
                            memberPos.getZ() + radius,
                            NetworkMapMarker.KIND_FIELD,
                            memberPos.getX(),
                            memberPos.getY(),
                            memberPos.getZ()
                    ));
                    putMarker(nextMarkers, new NetworkMapMarker(memberPos.getX(), memberPos.getY(), memberPos.getZ(), NetworkMapMarker.KIND_FIELD));
                    continue;
                }
                if (member instanceof TileEntityShieldCable) {
                    putMarker(nextMarkers, new NetworkMapMarker(memberPos.getX(), memberPos.getY(), memberPos.getZ(), NetworkMapMarker.KIND_CABLE));
                    continue;
                }
                if (member instanceof TileEntityShieldGenerator) {
                    putMarker(nextMarkers, new NetworkMapMarker(memberPos.getX(), memberPos.getY(), memberPos.getZ(), NetworkMapMarker.KIND_GENERATOR));
                    continue;
                }
                if (member instanceof TileEntityContourInjector) {
                    TileEntityContourInjector injector = (TileEntityContourInjector) member;
                    putMarker(nextMarkers, new NetworkMapMarker(memberPos.getX(), memberPos.getY(), memberPos.getZ(), NetworkMapMarker.KIND_INJECTOR));
                    ContourFrameGeometry geometry = injector.getCurrentGeometry();
                    if (geometry != null) {
                        putMarker(nextMarkers, NetworkMapMarker.createArea(
                                geometry.getMinX(),
                                geometry.getMinY(),
                                geometry.getMinZ(),
                                geometry.getMaxX(),
                                geometry.getMaxY(),
                                geometry.getMaxZ(),
                                NetworkMapMarker.KIND_CONTOUR,
                                memberPos.getX(),
                                memberPos.getY(),
                                memberPos.getZ()
                        ));
                    }
                    continue;
                }
                if (member instanceof TileEntityShieldConsole  || member instanceof IShieldNetworkController) {
                    putMarker(nextMarkers, new NetworkMapMarker(memberPos.getX(), memberPos.getY(), memberPos.getZ(), NetworkMapMarker.KIND_CONSOLE));
                    continue;
                }
                if (member instanceof IShieldSink) {
                    putMarker(nextMarkers, new NetworkMapMarker(memberPos.getX(), memberPos.getY(), memberPos.getZ(), NetworkMapMarker.KIND_SINK));
                    continue;
                }
                if (member instanceof IShieldSource) {
                    putMarker(nextMarkers, new NetworkMapMarker(memberPos.getX(), memberPos.getY(), memberPos.getZ(), NetworkMapMarker.KIND_SOURCE));
                    continue;
                }
                putMarker(nextMarkers, new NetworkMapMarker(memberPos.getX(), memberPos.getY(), memberPos.getZ(), NetworkMapMarker.KIND_OTHER));
            }
        }

        nextMarkers.sort(Comparator
                .comparingInt(NetworkMapMarker::getKind)
                .thenComparingInt(NetworkMapMarker::getX)
                .thenComparingInt(NetworkMapMarker::getY)
                .thenComparingInt(NetworkMapMarker::getZ));

        if (!mapMarkers.equals(nextMarkers)) {
            mapMarkers.clear();
            mapMarkers.addAll(nextMarkers);
            markDirty();
            queueClientSync();
        }
    }

    private static void putMarker(List<NetworkMapMarker> markers, NetworkMapMarker marker) {
        for (int i = 0; i < markers.size(); i++) {
            NetworkMapMarker existing = markers.get(i);
            if (existing.matchesPosition(marker)) {
                if (marker.getPriority() >= existing.getPriority()) {
                    markers.set(i, marker);
                }
                return;
            }
        }
        markers.add(marker);
    }

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setString("networkCode", networkCode);
        compound.setBoolean("networkConnected", networkConnected);
        compound.setInteger("networkStatus", networkStatus);
        compound.setInteger("cableCount", cableCount);
        compound.setInteger("generatorCount", generatorCount);
        compound.setInteger("injectorCount", injectorCount);
        compound.setInteger("sourceAvailable", sourceAvailable);
        compound.setInteger("sinkRequested", sinkRequested);
        compound.setInteger("cableCapacity", cableCapacity);
        compound.setInteger("deliveredFlow", deliveredFlow);
        compound.setInteger("saturatedCables", saturatedCables);
        compound.setInteger("generationPerTick", generationPerTick);
        compound.setInteger("consumptionPerTick", consumptionPerTick);
        compound.setInteger("bottleneckUtilizationPermille", bottleneckUtilizationPermille);
        compound.setDouble("shieldEnergyResistanceBias", shieldEnergyResistanceBias);
        compound.setInteger("rootX", rootX);
        compound.setInteger("rootY", rootY);
        compound.setInteger("rootZ", rootZ);
        NBTTagList mapList = new NBTTagList();
        for (NetworkMapMarker marker : mapMarkers) {
            mapList.appendTag(marker.writeToNBT());
        }
        compound.setTag("mapMarkers", mapList);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        networkCode = CodeUtils.normalize(compound.getString("networkCode"));
        networkConnected = compound.getBoolean("networkConnected");
        networkStatus = compound.getInteger("networkStatus");
        cableCount = compound.getInteger("cableCount");
        generatorCount = compound.getInteger("generatorCount");
        injectorCount = compound.getInteger("injectorCount");
        sourceAvailable = compound.getInteger("sourceAvailable");
        sinkRequested = compound.getInteger("sinkRequested");
        cableCapacity = compound.getInteger("cableCapacity");
        deliveredFlow = compound.getInteger("deliveredFlow");
        saturatedCables = compound.getInteger("saturatedCables");
        generationPerTick = compound.getInteger("generationPerTick");
        consumptionPerTick = compound.getInteger("consumptionPerTick");
        bottleneckUtilizationPermille = compound.getInteger("bottleneckUtilizationPermille");
        shieldEnergyResistanceBias = compound.hasKey("shieldEnergyResistanceBias")
                ? Math.max(0.0D, Math.min(1.0D, compound.getDouble("shieldEnergyResistanceBias")))
                : ModConfig.shieldEnergyResistanceBias;
        rootX = compound.getInteger("rootX");
        rootY = compound.getInteger("rootY");
        rootZ = compound.getInteger("rootZ");
        mapMarkers.clear();
        if (compound.hasKey("mapMarkers")) {
            NBTTagList mapList = compound.getTagList("mapMarkers", 10);
            for (int i = 0; i < mapList.tagCount(); i++) {
                mapMarkers.add(NetworkMapMarker.readFromNBT(mapList.getCompoundTagAt(i)));
            }
        }
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        readFromNBT(tag);
    }

    @Nullable
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    private void queueClientSync() {
        if (world == null || world.isRemote) {
            return;
        }
        if (!clientSyncQueued) {
            clientSyncQueued = true;
            clientSyncCountdown = CLIENT_SYNC_BASE_INTERVAL_TICKS - 1 + world.rand.nextInt(CLIENT_SYNC_JITTER_TICKS + 1);
        }
    }

    private void tickClientSync() {
        if (world == null || world.isRemote || !clientSyncQueued) {
            return;
        }
        if (clientSyncCountdown > 0) {
            clientSyncCountdown--;
            return;
        }
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        clientSyncQueued = false;
        clientSyncCountdown = -1;
    }
}
