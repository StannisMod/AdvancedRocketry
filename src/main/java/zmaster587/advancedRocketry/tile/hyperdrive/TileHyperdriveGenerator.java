package zmaster587.advancedRocketry.tile.hyperdrive;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.hyperdrive.ComponentScan;
import zmaster587.advancedRocketry.hyperdrive.DriveTuning;
import zmaster587.advancedRocketry.hyperdrive.ShipDriveStats;
import zmaster587.advancedRocketry.tile.TileShipComponent;

/**
 * The ship's hyperspace field generator: the machine that decides how deep a well the ship can climb
 * out of, how fast it crosses, and how big a burst it takes to get going.
 *
 * <p>Its power is not a tier stamped on the block — it is <b>measured</b>, by walking the coils
 * welded to it. That is the progression the whole family is built around: a bigger generator is a
 * better generator, and "bigger" is something a player builds rather than something he unlocks.</p>
 *
 * <p>A generator is not a window on its own, but it does hold up a small one, so a first ship with
 * nothing but a generator, a capacitor and a navigation computer can still jump. Hull emitters are
 * what make the window big enough for a hull worth the name.</p>
 */
public class TileHyperdriveGenerator extends TileShipComponent {

    /** The one kind of component a generator counts. */
    static final String KIND_COIL = "coil";

    /**
     * What this generator is worth right now, measured from the blocks actually attached to it. It is
     * computed rather than stored on purpose: the coils ride the ship exactly as the generator does,
     * so re-counting them can never disagree with the ship that is actually flying — which a cached
     * number written at assembly time eventually would.
     */
    public ShipDriveStats stats() {
        return ShipDriveStats.ofPower(
                DriveTuning.GENERATOR_BASE_POWER + coilCount() * DriveTuning.POWER_PER_COIL);
    }

    /** How many coils are welded to this generator. */
    public int coilCount() {
        if (world == null) {
            return 0;
        }
        return scan().count(KIND_COIL);
    }

    /** The generator's own block plus every coil welded to it — the machine's full footprint. */
    public List<BlockPos> footprint() {
        List<BlockPos> blocks = new ArrayList<>();
        blocks.add(pos);
        if (world == null) {
            return blocks;
        }
        collectCoils(pos, blocks);
        return blocks;
    }

    private ComponentScan.Result scan() {
        return ComponentScan.from(pos, new ComponentScan.Component() {
            @Override
            public String kindAt(BlockPos at) {
                return isCoil(at) ? KIND_COIL : null;
            }
        }, DriveTuning.MAX_COILS);
    }

    private void collectCoils(BlockPos origin, List<BlockPos> into) {
        // Same walk as the count, kept separate so the count stays allocation-light: it runs on a
        // gate check, which a pilot may repeat as often as he likes.
        List<BlockPos> frontier = new ArrayList<>();
        frontier.add(origin);
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        seen.add(origin);
        int visited = 0;
        while (!frontier.isEmpty() && visited < DriveTuning.MAX_COILS) {
            BlockPos current = frontier.remove(frontier.size() - 1);
            for (net.minecraft.util.EnumFacing face : net.minecraft.util.EnumFacing.VALUES) {
                BlockPos next = current.offset(face);
                if (!seen.add(next) || !isCoil(next)) {
                    continue;
                }
                visited++;
                into.add(next);
                frontier.add(next);
            }
        }
    }

    private boolean isCoil(BlockPos at) {
        Block block = world.getBlockState(at).getBlock();
        return block != null && block == AdvancedRocketryBlocks.blockHyperdriveCoil;
    }
}
