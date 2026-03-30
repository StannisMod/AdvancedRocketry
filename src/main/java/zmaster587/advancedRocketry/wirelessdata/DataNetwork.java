package zmaster587.advancedRocketry.wirelessdata;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.advancedRocketry.api.satellite.IDataHandler;
import zmaster587.libVulpes.util.SingleEntry;

import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArraySet;

public class DataNetwork {

    private static final DataType[] DATA_TYPES = DataType.values();

    private final CopyOnWriteArraySet<Entry<TileEntity, EnumFacing>> sources = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<Entry<TileEntity, EnumFacing>> sinks = new CopyOnWriteArraySet<>();

    private final int networkID;

    /**
     * Scheduler state:
     *
     * - lastSuccessfulTransferTick:
     *   Real scheduler tick when this network last moved any data.
     *
     * - firstEligibleTick:
     *   Real scheduler tick when this network first became eligible to transfer
     *   (has at least one loaded source and one loaded sink) before ever having
     *   performed a successful transfer.
     *
     * This lets idle backoff be based on real elapsed ticks, not "number of polls".
     */
    private long lastSuccessfulTransferTick = -1L;
    private long firstEligibleTick = -1L;
    private boolean transferEligible = false;

    private DataNetwork(int networkID) {
        this.networkID = networkID;
    }

    public static DataNetwork createWithID(int id) {
        return new DataNetwork(id);
    }

    public boolean isEmpty() {
        return sources.isEmpty() && sinks.isEmpty();
    }

    public boolean hasSourcesAndSinks() {
        return !sources.isEmpty() && !sinks.isEmpty();
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

    /**
     * Called by the handler every scheduler tick so eligibility transitions
     * are tracked using real elapsed ticks.
     */
    public void updateSchedulingState(long currentTick) {
        boolean eligibleNow = hasSourcesAndSinks();

        if (eligibleNow) {
            if (!transferEligible) {
                transferEligible = true;

                // Start the idle-age baseline the first time this network becomes
                // actually eligible to transfer before any successful transfer exists.
                if (lastSuccessfulTransferTick < 0L) {
                    firstEligibleTick = currentTick;
                }
            }
        } else {
            transferEligible = false;

            // If the network has never transferred successfully, and it loses eligibility,
            // drop the bootstrap baseline so a later re-eligibility starts fresh.
            if (lastSuccessfulTransferTick < 0L) {
                firstEligibleTick = -1L;
            }
        }
    }

    /**
     * Returns real elapsed scheduler ticks since last successful transfer.
     * Before the first successful transfer, this falls back to elapsed ticks
     * since the network first became eligible to transfer.
     */
    public long getIdleAge(long currentTick) {
        long referenceTick = lastSuccessfulTransferTick >= 0L ? lastSuccessfulTransferTick : firstEligibleTick;
        return referenceTick >= 0L ? Math.max(0L, currentTick - referenceTick) : 0L;
    }

    public void noteSuccessfulTransfer(long currentTick) {
        lastSuccessfulTransferTick = currentTick;
    }

    /**
     * amountPerTransfer is the per-endpoint budget for this scheduled network tick.
     * This should scale with the scheduling interval so average throughput stays similar.
     *
     * @return true if any data moved this tick
     */
    public boolean tick(int amountPerTransfer) {
        int transferBudget = Math.max(1, amountPerTransfer);

        if (sources.isEmpty() || sinks.isEmpty()) {
            return false;
        }

        boolean movedAnything = false;

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
                demand += handler.addData(transferBudget, type, entry.getValue(), false);
            }

            for (Entry<TileEntity, EnumFacing> entry : sources) {
                TileEntity tile = entry.getKey();
                if (!(tile instanceof IDataHandler)) {
                    continue;
                }

                IDataHandler handler = (IDataHandler) tile;
                supply += handler.extractData(transferBudget, type, entry.getValue(), false);
            }

            int moved = Math.min(supply, demand);
            if (moved <= 0) {
                continue;
            }

            movedAnything = true;

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

        return movedAnything;
    }
}