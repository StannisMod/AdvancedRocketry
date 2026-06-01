package zmaster587.advancedRocketry.wirelessdata;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import zmaster587.advancedRocketry.api.DataStorage.DataType;
import zmaster587.advancedRocketry.api.satellite.IDataHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class DataNetwork {

    private static final DataType[] DATA_TYPES = DataType.values();

    private final CopyOnWriteArraySet<EndpointRef> sources = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<EndpointRef> sinks = new CopyOnWriteArraySet<>();

    private final int networkID;

    private long lastSuccessfulTransferTick = -1L;
    private long firstEligibleTick = -1L;
    private boolean transferEligible = false;

    /**
     * Only rotates who receives the +1 remainder first when a fair split
     * does not divide evenly.
     */
    private int sinkFairCursor = 0;
    private int sourceFairCursor = 0;

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

    public void addSource(TileEntity tile, EnumFacing dir, int priority) {
        replaceEntry(sources, tile, dir, priority);
    }

    public void addSink(TileEntity tile, EnumFacing dir, int priority) {
        replaceEntry(sinks, tile, dir, priority);
    }

    private void replaceEntry(CopyOnWriteArraySet<EndpointRef> set, TileEntity tile, EnumFacing dir, int priority) {
        for (EndpointRef entry : set) {
            TileEntity existing = entry.tile;

            if (existing == tile) {
                if (entry.side == dir && entry.priority == priority) {
                    return;
                }
                set.remove(entry);
                break;
            }

            if (existing != null && tile != null && existing.getPos().equals(tile.getPos())) {
                set.remove(entry);
                break;
            }
        }

        set.add(new EndpointRef(tile, dir, priority));
    }

    public void removeFromAll(TileEntity tile) {
        removeFromSet(sources, tile);
        removeFromSet(sinks, tile);
    }

    private void removeFromSet(CopyOnWriteArraySet<EndpointRef> set, TileEntity tile) {
        for (EndpointRef entry : set) {
            TileEntity existing = entry.tile;
            if (existing != null && tile != null && existing.getPos().equals(tile.getPos())) {
                set.remove(entry);
                return;
            }
        }
    }

    public void updateSchedulingState(long currentTick) {
        boolean eligibleNow = hasSourcesAndSinks();

        if (eligibleNow) {
            if (!transferEligible) {
                transferEligible = true;

                if (lastSuccessfulTransferTick < 0L) {
                    firstEligibleTick = currentTick;
                }
            }
        } else {
            transferEligible = false;

            if (lastSuccessfulTransferTick < 0L) {
                firstEligibleTick = -1L;
            }
        }
    }

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
     * Priority semantics:
     * - Only the highest sink-priority band with demand is eligible this tick.
     * - Only the highest source-priority band with supply is eligible this tick.
     * - Lower-priority bands do nothing while a higher band is still active.
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

            List<EndpointOffer> sinkOffers = collectSinkOffers(type, transferBudget);
            if (sinkOffers.isEmpty()) {
                continue;
            }

            List<EndpointOffer> sourceOffers = collectSourceOffers(type, transferBudget);
            if (sourceOffers.isEmpty()) {
                continue;
            }

            List<Integer> activeSinkBand = getHighestPriorityBandWithCapacity(sinkOffers);
            if (activeSinkBand.isEmpty()) {
                continue;
            }

            List<Integer> activeSourceBand = getHighestPriorityBandWithCapacity(sourceOffers);
            if (activeSourceBand.isEmpty()) {
                continue;
            }

            int sinkBandDemand = getBandCapacity(sinkOffers, activeSinkBand);
            int sourceBandSupply = getBandCapacity(sourceOffers, activeSourceBand);

            int moved = Math.min(sinkBandDemand, sourceBandSupply);
            if (moved <= 0) {
                continue;
            }

            int[] plannedSinkAllocs = new int[sinkOffers.size()];
            int[] plannedSourceAllocs = new int[sourceOffers.size()];

            int grantedToSinks = allocateFairlyIntoBand(
                    sinkOffers,
                    activeSinkBand,
                    plannedSinkAllocs,
                    moved,
                    sinkFairCursor
            );

            if (grantedToSinks <= 0) {
                continue;
            }

            int grantedFromSources = allocateFairlyIntoBand(
                    sourceOffers,
                    activeSourceBand,
                    plannedSourceAllocs,
                    grantedToSinks,
                    sourceFairCursor
            );

            if (grantedFromSources <= 0) {
                continue;
            }

            if (grantedToSinks > grantedFromSources) {
                trimBandAllocation(plannedSinkAllocs, activeSinkBand, grantedToSinks - grantedFromSources);
                grantedToSinks = grantedFromSources;
            } else if (grantedFromSources > grantedToSinks) {
                trimBandAllocation(plannedSourceAllocs, activeSourceBand, grantedFromSources - grantedToSinks);
                grantedFromSources = grantedToSinks;
            }

            if (grantedToSinks <= 0) {
                continue;
            }

            sinkFairCursor = advanceCursor(sinkFairCursor, activeSinkBand.size());
            sourceFairCursor = advanceCursor(sourceFairCursor, activeSourceBand.size());

            int actuallyInserted = commitSinkAllocs(sinkOffers, plannedSinkAllocs, type);
            if (actuallyInserted <= 0) {
                continue;
            }

            if (actuallyInserted < grantedFromSources) {
                trimBandAllocation(plannedSourceAllocs, activeSourceBand, grantedFromSources - actuallyInserted);
            }

            int actuallyExtracted = commitSourceAllocs(sourceOffers, plannedSourceAllocs, type);

            if (actuallyInserted > 0 && actuallyExtracted > 0) {
                movedAnything = true;
            }
        }

        return movedAnything;
    }

    private List<EndpointOffer> collectSinkOffers(DataType type, int transferBudget) {
        List<EndpointOffer> offers = new ArrayList<>();

        for (EndpointRef entry : sinks) {
            TileEntity tile = entry.tile;
            if (!(tile instanceof IDataHandler)) {
                continue;
            }

            IDataHandler handler = (IDataHandler) tile;
            int amount = handler.addData(transferBudget, type, entry.side, false);
            if (amount > 0) {
                offers.add(new EndpointOffer(handler, entry.side, entry.priority, amount));
            }
        }

        return offers;
    }

    private List<EndpointOffer> collectSourceOffers(DataType type, int transferBudget) {
        List<EndpointOffer> offers = new ArrayList<>();

        for (EndpointRef entry : sources) {
            TileEntity tile = entry.tile;
            if (!(tile instanceof IDataHandler)) {
                continue;
            }

            IDataHandler handler = (IDataHandler) tile;
            int amount = handler.extractData(transferBudget, type, entry.side, false);
            if (amount > 0) {
                offers.add(new EndpointOffer(handler, entry.side, entry.priority, amount));
            }
        }

        return offers;
    }

    private List<Integer> getHighestPriorityBandWithCapacity(List<EndpointOffer> offers) {
        TreeMap<Integer, List<Integer>> byPriority = new TreeMap<>(Collections.reverseOrder());

        for (int i = 0; i < offers.size(); i++) {
            byPriority.computeIfAbsent(offers.get(i).priority, k -> new ArrayList<>()).add(i);
        }

        for (List<Integer> band : byPriority.values()) {
            if (getBandCapacity(offers, band) > 0) {
                return band;
            }
        }

        return Collections.emptyList();
    }

    private int getBandCapacity(List<EndpointOffer> offers, List<Integer> bandIndices) {
        int total = 0;
        for (int idx : bandIndices) {
            total += offers.get(idx).offer;
        }
        return total;
    }

    /**
     * Fairly allocate "amount" into one priority band, respecting caps.
     * Lower-priority bands are ignored entirely by design.
     */
    private int allocateFairlyIntoBand(
            List<EndpointOffer> offers,
            List<Integer> bandIndices,
            int[] plannedAllocations,
            int amount,
            int cursor
    ) {
        if (bandIndices.isEmpty() || amount <= 0) {
            return 0;
        }

        int size = bandIndices.size();
        int start = Math.floorMod(cursor, size);
        int remaining = amount;
        int allocatedTotal = 0;

        while (remaining > 0) {
            int active = 0;

            for (int idx : bandIndices) {
                if (offers.get(idx).offer > plannedAllocations[idx]) {
                    active++;
                }
            }

            if (active <= 0) {
                break;
            }

            int baseShare = remaining / active;
            int extra = remaining % active;
            int grantedThisPass = 0;

            for (int step = 0; step < size; step++) {
                int bandPos = (start + step) % size;
                int idx = bandIndices.get(bandPos);

                int spare = offers.get(idx).offer - plannedAllocations[idx];
                if (spare <= 0) {
                    continue;
                }

                int target = baseShare;
                if (extra > 0) {
                    target++;
                    extra--;
                }

                if (target <= 0) {
                    continue;
                }

                int grant = Math.min(target, spare);
                if (grant <= 0) {
                    continue;
                }

                plannedAllocations[idx] += grant;
                remaining -= grant;
                allocatedTotal += grant;
                grantedThisPass += grant;

                if (remaining <= 0) {
                    break;
                }
            }

            if (grantedThisPass <= 0) {
                break;
            }
        }

        return allocatedTotal;
    }

    private void trimBandAllocation(int[] plannedAllocations, List<Integer> bandIndices, int amountToTrim) {
        int remaining = amountToTrim;
        if (remaining <= 0) {
            return;
        }

        while (remaining > 0) {
            int bestIdx = -1;
            int bestAlloc = 0;

            for (int idx : bandIndices) {
                int alloc = plannedAllocations[idx];
                if (alloc > bestAlloc) {
                    bestAlloc = alloc;
                    bestIdx = idx;
                }
            }

            if (bestIdx < 0 || bestAlloc <= 0) {
                break;
            }

            int trim = Math.min(bestAlloc, remaining);
            plannedAllocations[bestIdx] -= trim;
            remaining -= trim;
        }
    }

    private int commitSinkAllocs(List<EndpointOffer> sinkOffers, int[] allocations, DataType type) {
        int totalInserted = 0;

        for (int i = 0; i < sinkOffers.size(); i++) {
            int amount = allocations[i];
            if (amount <= 0) {
                continue;
            }

            EndpointOffer offer = sinkOffers.get(i);
            totalInserted += offer.handler.addData(amount, type, offer.side, true);
        }

        return totalInserted;
    }

    private int commitSourceAllocs(List<EndpointOffer> sourceOffers, int[] allocations, DataType type) {
        int totalExtracted = 0;

        for (int i = 0; i < sourceOffers.size(); i++) {
            int amount = allocations[i];
            if (amount <= 0) {
                continue;
            }

            EndpointOffer offer = sourceOffers.get(i);
            totalExtracted += offer.handler.extractData(amount, type, offer.side, true);
        }

        return totalExtracted;
    }

    private int advanceCursor(int current, int size) {
        if (size <= 0) {
            return 0;
        }
        return (current + 1) % size;
    }

    private static class EndpointRef {
        final TileEntity tile;
        final EnumFacing side;
        final int priority;

        EndpointRef(TileEntity tile, EnumFacing side, int priority) {
            this.tile = tile;
            this.side = side;
            this.priority = priority;
        }
    }

    private static class EndpointOffer {
        final IDataHandler handler;
        final EnumFacing side;
        final int priority;
        final int offer;

        EndpointOffer(IDataHandler handler, EnumFacing side, int priority, int offer) {
            this.handler = handler;
            this.side = side;
            this.priority = priority;
            this.offer = offer;
        }
    }
}