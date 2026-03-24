package zmaster587.advancedRocketry.wirelessdata;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.advancedRocketry.api.satellite.IDataHandler;
import zmaster587.libVulpes.util.SingleEntry;

import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class DataNetwork {

    private static final DataType[] DATA_TYPES = DataType.values();

    private final CopyOnWriteArraySet<Entry<TileEntity, EnumFacing>> sources = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<Entry<TileEntity, EnumFacing>> sinks = new CopyOnWriteArraySet<>();

    private final int networkID;

    private DataNetwork(int networkID) {
        this.networkID = networkID;
    }

    public static DataNetwork createWithID(int id) {
        return new DataNetwork(id);
    }

    public int getNetworkID() {
        return networkID;
    }

    public Set<Entry<TileEntity, EnumFacing>> getSources() {
        return sources;
    }

    public Set<Entry<TileEntity, EnumFacing>> getSinks() {
        return sinks;
    }

    public void addSource(TileEntity tile, EnumFacing dir) {
        replaceEntry(sources, tile, dir);
    }

    public void addSink(TileEntity tile, EnumFacing dir) {
        replaceEntry(sinks, tile, dir);
    }

    private void replaceEntry(CopyOnWriteArraySet<Entry<TileEntity, EnumFacing>> set, TileEntity tile, EnumFacing dir) {
        for (Entry<TileEntity, EnumFacing> entry : set) {
            TileEntity existing = entry.getKey();

            if (existing == tile) {
                return;
            }

            if (existing != null && tile != null && existing.getPos().equals(tile.getPos())) {
                set.remove(entry);
                break;
            }
        }

        set.add(new SingleEntry<>(tile, dir));
    }

    public void removeFromAll(TileEntity tile) {
        removeFromSet(sources, tile);
        removeFromSet(sinks, tile);
    }

    private void removeFromSet(CopyOnWriteArraySet<Entry<TileEntity, EnumFacing>> set, TileEntity tile) {
        for (Entry<TileEntity, EnumFacing> entry : set) {
            TileEntity existing = entry.getKey();
            if (existing != null && tile != null && existing.getPos().equals(tile.getPos())) {
                set.remove(entry);
                return;
            }
        }
    }

    public void merge(DataNetwork other) {
        if (other == null || other == this) {
            return;
        }

        for (Entry<TileEntity, EnumFacing> entry : other.getSources()) {
            addSource(entry.getKey(), entry.getValue());
        }

        for (Entry<TileEntity, EnumFacing> entry : other.getSinks()) {
            addSink(entry.getKey(), entry.getValue());
        }
    }

    public void tick() {
        final int amountPerTransfer = 1;

        if (sources.isEmpty() || sinks.isEmpty()) {
            return;
        }

        for (DataType type : DATA_TYPES) {
            if (type == DataType.UNDEFINED) {
                continue;
            }

            int demand = 0;
            int supply = 0;

            for (Entry<TileEntity, EnumFacing> entry : sinks) {
                TileEntity tile = entry.getKey();
                if (!(tile instanceof IDataHandler)) {
                    continue;
                }

                IDataHandler handler = (IDataHandler) tile;
                demand += handler.addData(amountPerTransfer, type, entry.getValue(), false);
            }

            for (Entry<TileEntity, EnumFacing> entry : sources) {
                TileEntity tile = entry.getKey();
                if (!(tile instanceof IDataHandler)) {
                    continue;
                }

                IDataHandler handler = (IDataHandler) tile;
                supply += handler.extractData(amountPerTransfer, type, entry.getValue(), false);
            }

            int moved = Math.min(supply, demand);
            if (moved <= 0) {
                continue;
            }

            int remainingToInsert = moved;
            for (Entry<TileEntity, EnumFacing> entry : sinks) {
                if (remainingToInsert <= 0) {
                    break;
                }

                TileEntity tile = entry.getKey();
                if (!(tile instanceof IDataHandler)) {
                    continue;
                }

                IDataHandler handler = (IDataHandler) tile;
                remainingToInsert -= handler.addData(remainingToInsert, type, entry.getValue(), true);
            }

            int remainingToExtract = moved;
            for (Entry<TileEntity, EnumFacing> entry : sources) {
                if (remainingToExtract <= 0) {
                    break;
                }

                TileEntity tile = entry.getKey();
                if (!(tile instanceof IDataHandler)) {
                    continue;
                }

                IDataHandler handler = (IDataHandler) tile;
                remainingToExtract -= handler.extractData(remainingToExtract, type, entry.getValue(), true);
            }
        }
    }
}