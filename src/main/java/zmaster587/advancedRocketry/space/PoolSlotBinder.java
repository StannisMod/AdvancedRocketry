package zmaster587.advancedRocketry.space;

import java.util.List;

/**
 * Production {@link SlotBinder} that drives the real world lifecycle through {@link SpaceSlotPool}.
 * A thin adapter: {@link SpaceManager}'s policy decides WHICH slot binds WHICH cell and whether an
 * eviction flushes or discards; this class carries those decisions out against live dimensions.
 */
public final class PoolSlotBinder implements SlotBinder {

    @Override
    public int[] slotDims() {
        List<Integer> dims = SpaceSlotPool.slotDims();
        int[] out = new int[dims.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = dims.get(i);
        }
        return out;
    }

    @Override
    public void load(int dimId, String cellKey) {
        SpaceSlotPool.load(dimId, cellKey);
    }

    @Override
    public void unload(int dimId) {
        SpaceSlotPool.unload(dimId);
    }

    @Override
    public void discard(int dimId) {
        SpaceSlotPool.discard(dimId);
    }

    @Override
    public void deleteStore(String cellKey) {
        SpaceSlotPool.deleteStore(cellKey);
    }
}
