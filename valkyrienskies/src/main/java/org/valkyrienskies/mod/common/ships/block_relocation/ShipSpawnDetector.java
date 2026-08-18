package org.valkyrienskies.mod.common.ships.block_relocation;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.World;
import org.valkyrienskies.mod.common.config.VSConfig;

public class ShipSpawnDetector extends SpatialDetector {

    private static final Set<Block> blacklist = new CopyOnWriteArraySet<>();

    static {
        VSConfig.registerSyncEvent(ShipSpawnDetector::syncWithConfig);
        // This static block doesn't get loaded until we try spawning a ship, so initially blacklist is empty.
        // We run the function here to fix that.
        syncWithConfig();
    }

    /**
     * This is called by {@link VSConfig#sync}
     */
    private static void syncWithConfig() {
        blacklist.clear();

        Arrays.stream(VSConfig.shipSpawnDetectorBlacklist)
            .map(Block::getBlockFromName)
            .forEach(blacklist::add);
    }

    /**
     * How many blocks the spawn blacklist currently holds.
     *
     * <p>Exists because {@link #syncWithConfig()} rebuilds the set NON-ATOMICALLY - a clear followed
     * by a repopulate - so a flood that runs in that window sees a partially built blacklist and
     * escapes through terrain it would normally refuse. A refused spawn reports this number beside
     * the flood size, which is what separates "the flood escaped" from "it escaped because the
     * blacklist was momentarily empty".</p>
     */
    public static int blacklistSize() {
        return blacklist.size();
    }

    private final MutableBlockPos mutablePos = new MutableBlockPos();

    ShipSpawnDetector(BlockPos start, World worldIn, int maximum, boolean checkCorners) {
        super(start, worldIn, maximum, checkCorners);
        // syncWithConfig();
        startDetection();
    }

    @Override
    public boolean isValidExpansion(int x, int y, int z) {
        mutablePos.setPos(x, y, z);
        IBlockState state = cache.getBlockState(mutablePos);
        if (state.getBlock() == Blocks.BEDROCK) {
            cleanHouse = true;
            return false;
        }
        return !blacklist.contains(state.getBlock());
    }

}
